package cc.microblock.TGStickerProvider.hook

import android.content.Context
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 统一的 Telegram 宿主反射桥接层：
 * - 未混淆客户端（Nekogram / NekoX / AyuGram 等）直接走原生类名零开销通道；
 * - 混淆客户端（Telegram 官方 Play / Web 版）通过 DexKit 按不变特征定位并缓存映射。
 */
internal class TelegramBridge private constructor(
    private val loader: ClassLoader,
    private val mediaDataControllerClass: Class<*>,
    private val mediaGetInstance: Method,
    private val mediaGetStickerSetById: Method,
    private val mediaCheckStickers: Method?,
    private val fileLoaderClass: Class<*>,
    private val fileGetInstance: Method,
    private val fileGetDirectory: Method?,
    private val fileGetPathToAttach: Method,
    private val fileGetAttachFileName: Method?,
    private val fileIsLoadingFile: Method?,
    private val fileLoadCandidates: List<Method>,
    private val fileCancelCandidates: List<Method>,
    private val userConfigClass: Class<*>?,
    private val connectionsClass: Class<*>?,
    private val nativeResumeNetwork: Method?,
    private val resumeNetworkMaybe: Method?,
    private val connectionsGetInstance: Method?,
    val isObfuscated: Boolean
) {
    private val downloadPriority: Int by lazy {
        runCatching {
            XposedHelpers.getStaticIntField(fileLoaderClass, "PRIORITY_NORMAL_UP")
        }.getOrDefault(2)
    }

    private val stickerTypeIds: List<Int> by lazy {
        val reflected = listOf("TYPE_IMAGE", "TYPE_MASK", "TYPE_EMOJIPACKS", "TYPE_EMOJI").mapNotNull { name ->
            runCatching { XposedHelpers.getStaticIntField(mediaDataControllerClass, name) }.getOrNull()
        }.distinct()
        if (reflected.isNotEmpty()) reflected else listOf(0, 1, 5, 4)
    }

    private val cachedMaxAccounts: Int by lazy {
        val cls = userConfigClass ?: return@lazy DEFAULT_MAX_ACCOUNT_COUNT
        runCatching {
            XposedHelpers.getStaticIntField(cls, "MAX_ACCOUNT_COUNT").coerceAtLeast(1)
        }.getOrDefault(DEFAULT_MAX_ACCOUNT_COUNT)
    }

    @Volatile private var cachedDocsField: Field? = null
    @Volatile private var cachedDocIdField: Field? = null
    @Volatile private var cachedLoadMethod: Method? = null
    @Volatile private var cachedCancelMethod: Method? = null

    fun getMediaDirectory(dirType: Int): File? {
        val method = fileGetDirectory ?: return null
        return runCatching {
            val dir = method.invoke(null, dirType) as? File
            if (dir != null && dir.isAbsolute && dir.exists()) dir else null
        }.getOrNull()
    }

    fun getMaxAccountCount(): Int = cachedMaxAccounts

    fun getMediaDataController(account: Int): Any? = runCatching {
        mediaGetInstance.invoke(null, account)
    }.getOrNull()

    fun getFileLoader(account: Int): Any? = runCatching {
        fileGetInstance.invoke(null, account)
    }.getOrNull()

    fun isAccountActivated(account: Int): Boolean {
        val cls = userConfigClass ?: return true
        if (isObfuscated) return true
        return runCatching {
            val user = XposedHelpers.callStaticMethod(cls, "getInstance", account)
            XposedHelpers.callMethod(user, "isClientActivated") == true
        }.getOrDefault(true)
    }

    fun checkAllStickerSets(account: Int) {
        val method = mediaCheckStickers ?: return
        val controller = getMediaDataController(account) ?: return
        for (type in stickerTypeIds) {
            runCatching { method.invoke(controller, type) }
        }
    }

    fun getStickerSetById(controller: Any, packId: Long): Any? = runCatching {
        mediaGetStickerSetById.invoke(controller, packId)
    }.getOrNull()

    /**
     * 从贴纸集对象提取文档列表（兼容混淆后的字段名）
     */
    fun extractDocuments(pack: Any, expectedDocIds: Set<Long>): List<Pair<Long, Any>> {
        val docsList = findDocumentsList(pack, expectedDocIds) ?: return emptyList()
        val result = ArrayList<Pair<Long, Any>>(docsList.size)
        for (doc in docsList) {
            if (doc == null) continue
            val docId = extractDocumentId(doc, expectedDocIds) ?: continue
            result.add(docId to doc)
        }
        return result
    }

    private fun findDocumentsList(pack: Any, expectedDocIds: Set<Long>): List<*>? {
        cachedDocsField?.let { field ->
            runCatching {
                val list = field.get(pack) as? List<*>
                if (list != null) return list
            }
        }
        // 未混淆快速路径
        runCatching {
            val f = findFieldByName(pack.javaClass, "documents")
            if (f != null) {
                val list = f.get(pack) as? List<*>
                if (list != null) {
                    cachedDocsField = f
                    return list
                }
            }
        }
        // 混淆路径：扫描 List 字段，优先匹配元素中能命中 expectedDocIds 的字段
        val listFields = collectFields(pack.javaClass).filter { List::class.java.isAssignableFrom(it.type) }
        var fallback: List<*>? = null
        for (field in listFields) {
            val list = runCatching { field.get(pack) as? List<*> }.getOrNull() ?: continue
            val sample = list.firstOrNull { it != null } ?: continue
            if (expectedDocIds.isNotEmpty() && findDocIdField(sample, expectedDocIds) != null) {
                cachedDocsField = field
                return list
            }
            if (fallback == null && hasMimeStringField(sample)) {
                fallback = list
            }
        }
        return fallback
    }

    private fun extractDocumentId(doc: Any, expectedDocIds: Set<Long>): Long? {
        cachedDocIdField?.let { field ->
            runCatching { return field.getLong(doc) }
        }
        findDocIdField(doc, expectedDocIds)?.let { field ->
            cachedDocIdField = field
            return runCatching { field.getLong(doc) }.getOrNull()
        }
        return null
    }

    private fun findDocIdField(doc: Any, expectedDocIds: Set<Long>): Field? {
        if (!isObfuscated) {
            findFieldByName(doc.javaClass, "id")?.let { field ->
                if (field.type == Long::class.javaPrimitiveType) return field
            }
        }
        val longFields = collectFields(doc.javaClass).filter { it.type == Long::class.javaPrimitiveType }
        if (expectedDocIds.isNotEmpty()) {
            for (field in longFields) {
                val value = runCatching { field.getLong(doc) }.getOrNull() ?: continue
                if (value in expectedDocIds) return field
            }
        }
        return findFieldByName(doc.javaClass, "id")?.takeIf { it.type == Long::class.javaPrimitiveType }
    }

    private fun hasMimeStringField(obj: Any): Boolean {
        for (field in collectFields(obj.javaClass)) {
            if (field.type != String::class.java) continue
            val value = runCatching { field.get(obj) as? String }.getOrNull() ?: continue
            if (value.startsWith("image/") || value.startsWith("video/") || value.startsWith("application/")) {
                return true
            }
        }
        return false
    }

    fun getPathToAttach(fileLoader: Any, document: Any, forceCache: Boolean): File? = runCatching {
        fileGetPathToAttach.invoke(fileLoader, document, forceCache) as? File
    }.getOrNull()

    fun getAttachFileName(document: Any): String? {
        val method = fileGetAttachFileName ?: return null
        return runCatching { method.invoke(null, document) as? String }.getOrNull()
    }

    fun isLoadingFile(fileLoader: Any, attachFileName: String?): Boolean {
        if (attachFileName.isNullOrEmpty()) return false
        val method = fileIsLoadingFile ?: return false
        return runCatching { method.invoke(fileLoader, attachFileName) == true }.getOrDefault(false)
    }

    fun loadFile(fileLoader: Any, document: Any, parent: Any) {
        val method = resolveLoadMethod(document.javaClass)
            ?: throw NoSuchMethodException("No compatible FileLoader.loadFile for ${document.javaClass.name}")
        method.invoke(fileLoader, document, parent, downloadPriority, 1)
    }

    fun cancelLoadFile(fileLoader: Any, document: Any) {
        val method = resolveCancelMethod(document.javaClass) ?: return
        runCatching { method.invoke(fileLoader, document) }
            .onFailure { YLog.warn("Could not cancel Telegram sticker download", it) }
    }

    fun resumeNetwork(account: Int) {
        runCatching {
            if (resumeNetworkMaybe != null && connectionsGetInstance != null) {
                val manager = connectionsGetInstance.invoke(null, account)
                if (manager != null) {
                    resumeNetworkMaybe.invoke(manager)
                    return
                }
            }
            if (nativeResumeNetwork != null) {
                nativeResumeNetwork.invoke(null, account, true)
            }
        }.onFailure { YLog.warn("Could not resume Telegram network", it) }
    }

    private fun resolveLoadMethod(docClass: Class<*>): Method? {
        cachedLoadMethod?.let { if (it.parameterTypes[0].isAssignableFrom(docClass)) return it }
        val match = fileLoadCandidates.firstOrNull { it.parameterTypes[0].isAssignableFrom(docClass) }
            ?: return null
        cachedLoadMethod = match
        return match
    }

    private fun resolveCancelMethod(docClass: Class<*>): Method? {
        cachedCancelMethod?.let { if (it.parameterTypes[0].isAssignableFrom(docClass)) return it }
        val match = fileCancelCandidates.firstOrNull { it.parameterTypes[0].isAssignableFrom(docClass) }
            ?: return null
        cachedCancelMethod = match
        return match
    }

    companion object {
        private const val DEFAULT_MAX_ACCOUNT_COUNT = 4
        private const val CACHE_FILE_NAME = "tgsp_dexkit_map_v1.json"

        @Volatile
        private var cachedBridge: TelegramBridge? = null
        @Volatile
        private var dexkitLoaded = false

        fun resolve(loader: ClassLoader, context: Context?): TelegramBridge? {
            cachedBridge?.let { return it }
            synchronized(this) {
                cachedBridge?.let { return it }
                val resolved = resolveUnobfuscated(loader)
                    ?: resolveWithDexKit(loader, context)
                if (resolved != null) {
                    cachedBridge = resolved
                }
                return resolved
            }
        }

        /**
         * 快速路径：直接按原生类名加载（兼容未混淆第三方客户端）
         */
        private fun resolveUnobfuscated(loader: ClassLoader): TelegramBridge? {
            return runCatching {
                val mediaCls = loader.loadClass("org.telegram.messenger.MediaDataController")
                val fileCls = loader.loadClass("org.telegram.messenger.FileLoader")
                val userCls = runCatching { loader.loadClass("org.telegram.messenger.UserConfig") }.getOrNull()
                val connCls = runCatching { loader.loadClass("org.telegram.tgnet.ConnectionsManager") }.getOrNull()

                val mediaGetInstance = mediaCls.getMethod("getInstance", Int::class.javaPrimitiveType)
                val mediaGetStickerSetById = mediaCls.getMethod("getStickerSetById", Long::class.javaPrimitiveType)
                val mediaCheckStickers = runCatching {
                    mediaCls.getMethod("checkStickers", Int::class.javaPrimitiveType)
                }.getOrNull()

                val fileGetInstance = fileCls.getMethod("getInstance", Int::class.javaPrimitiveType)
                val fileGetDirectory = runCatching {
                    fileCls.getMethod("getDirectory", Int::class.javaPrimitiveType)
                }.getOrNull()
                val fileGetPathToAttach = fileCls.methods.first { m ->
                    m.name == "getPathToAttach" &&
                        m.parameterTypes.size == 2 &&
                        m.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                        m.returnType == File::class.java
                }
                val fileGetAttachFileName = fileCls.methods.firstOrNull { m ->
                    m.name == "getAttachFileName" &&
                        Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 1 &&
                        m.returnType == String::class.java
                }
                val fileIsLoadingFile = runCatching {
                    fileCls.getMethod("isLoadingFile", String::class.java)
                }.getOrNull()
                val fileLoadCandidates = fileCls.methods.filter { m ->
                    m.name == "loadFile" &&
                        !Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 4 &&
                        m.parameterTypes[1] == Any::class.java &&
                        m.parameterTypes[2] == Int::class.javaPrimitiveType &&
                        m.parameterTypes[3] == Int::class.javaPrimitiveType &&
                        m.returnType == Void.TYPE
                }
                check(fileLoadCandidates.isNotEmpty())
                val fileCancelCandidates = fileCls.methods.filter { m ->
                    m.name == "cancelLoadFile" &&
                        !Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] != String::class.java &&
                        m.returnType == Void.TYPE
                }

                val connGetInstance = connCls?.let {
                    runCatching { it.getMethod("getInstance", Int::class.javaPrimitiveType) }.getOrNull()
                }
                val resumeMaybe = connCls?.let {
                    runCatching { it.getMethod("resumeNetworkMaybe") }.getOrNull()
                }
                val nativeResume = connCls?.declaredMethods?.firstOrNull { m ->
                    m.name == "native_resumeNetwork" &&
                        Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 2
                }?.apply { isAccessible = true }

                TelegramBridge(
                    loader = loader,
                    mediaDataControllerClass = mediaCls,
                    mediaGetInstance = mediaGetInstance,
                    mediaGetStickerSetById = mediaGetStickerSetById,
                    mediaCheckStickers = mediaCheckStickers,
                    fileLoaderClass = fileCls,
                    fileGetInstance = fileGetInstance,
                    fileGetDirectory = fileGetDirectory,
                    fileGetPathToAttach = fileGetPathToAttach,
                    fileGetAttachFileName = fileGetAttachFileName,
                    fileIsLoadingFile = fileIsLoadingFile,
                    fileLoadCandidates = fileLoadCandidates,
                    fileCancelCandidates = fileCancelCandidates,
                    userConfigClass = userCls,
                    connectionsClass = connCls,
                    nativeResumeNetwork = nativeResume,
                    resumeNetworkMaybe = resumeMaybe,
                    connectionsGetInstance = connGetInstance,
                    isObfuscated = false
                )
            }.getOrNull()
        }

        /**
         * 混淆路径：读取缓存或使用 DexKit 按源码不变特征定位核心类与方法
         */
        private fun resolveWithDexKit(loader: ClassLoader, context: Context?): TelegramBridge? {
            val apkPath = context?.applicationInfo?.sourceDir ?: return null
            val apkFile = File(apkPath)
            if (!apkFile.isFile) return null
            val apkStamp = "${apkFile.absolutePath}:${apkFile.length()}:${apkFile.lastModified()}"

            val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
            val cachedJson = runCatching {
                if (cacheFile.isFile) {
                    val obj = JSONObject(cacheFile.readText())
                    if (obj.optString("stamp") == apkStamp) obj else null
                } else null
            }.getOrNull()

            if (cachedJson != null) {
                buildFromMapping(loader, cachedJson)?.let {
                    YLog.info("Loaded obfuscated Telegram bridge from cache")
                    return it
                }
            }

            val scanned = scanWithDexKit(apkPath, apkStamp) ?: return null
            val bridge = buildFromMapping(loader, scanned) ?: return null
            runCatching { cacheFile.writeText(scanned.toString()) }
            YLog.info("Resolved obfuscated Telegram bridge via DexKit: " +
                "media=${scanned.optString("mediaClass")}, file=${scanned.optString("fileClass")}")
            return bridge
        }

        private fun scanWithDexKit(apkPath: String, apkStamp: String): JSONObject? {
            return runCatching {
                if (!dexkitLoaded) {
                    System.loadLibrary("dexkit")
                    dexkitLoaded = true
                }
                DexKitBridge.create(apkPath).use { bridge ->
                    // 1. 定位 MediaDataController（包含 stickers_v2 查询语句）
                    val mediaClassData = bridge.findClass {
                        matcher {
                            usingStrings("stickers_v2", "SELECT data, date, hash FROM stickers_v2 WHERE id = ")
                        }
                    }.firstOrNull() ?: bridge.findClass {
                        matcher {
                            usingStrings("stickers_v2")
                        }
                    }.firstOrNull { cls ->
                        cls.methods.any { m ->
                            Modifier.isStatic(m.modifiers) && m.paramCount == 1 &&
                                m.paramTypeNames.firstOrNull() == "int" &&
                                m.returnTypeName == cls.name
                        }
                    } ?: return@use null

                    // 2. 定位 FileLoader（包含 Telegram Documents 或 .preload 且存在静态 (int)->File）
                    val fileClassData = bridge.findClass {
                        matcher {
                            usingStrings("Telegram Documents")
                        }
                    }.firstOrNull { cls ->
                        cls.methods.any { m ->
                            Modifier.isStatic(m.modifiers) && m.paramCount == 1 &&
                                m.paramTypeNames.firstOrNull() == "int" &&
                                m.returnTypeName == "java.io.File"
                        }
                    } ?: bridge.findClass {
                        matcher {
                            usingStrings(".preload", ".temp")
                        }
                    }.firstOrNull { cls ->
                        cls.methods.any { m ->
                            Modifier.isStatic(m.modifiers) && m.paramCount == 1 &&
                                m.paramTypeNames.firstOrNull() == "int" &&
                                m.returnTypeName == "java.io.File"
                        }
                    } ?: return@use null

                    // 3. 定位 ConnectionsManager（native_resumeNetwork 永不混淆）
                    val resumeMethodData = bridge.findMethod {
                        matcher {
                            name = "native_resumeNetwork"
                        }
                    }.firstOrNull()

                    // 4. 定位 checkStickers(int) 与 loadStickers 方法特征
                    val stickersQueryMethod = mediaClassData.methods.firstOrNull { m ->
                        m.usingStrings.any { it.contains("stickers_v2") }
                    }
                    val checkStickersMethod = mediaClassData.methods.firstOrNull { m ->
                        !Modifier.isStatic(m.modifiers) && m.paramCount == 1 &&
                            m.paramTypeNames.firstOrNull() == "int" &&
                            m.returnTypeName == "void" &&
                            (stickersQueryMethod != null && m.invokes.any { it.name == stickersQueryMethod.name })
                    } ?: mediaClassData.methods.firstOrNull { m ->
                        !Modifier.isStatic(m.modifiers) && m.paramCount == 1 &&
                            m.paramTypeNames.firstOrNull() == "int" &&
                            m.returnTypeName == "void" &&
                            m.invokes.any { inv ->
                                inv.className == mediaClassData.name && inv.paramCount in 3..4
                            }
                    }

                    // 5. 定位 getStickerSetById(long)（返回类含 ArrayList 字段）
                    val getStickerSetMethod = mediaClassData.methods.firstOrNull { m ->
                        !Modifier.isStatic(m.modifiers) && m.paramCount == 1 &&
                            m.paramTypeNames.firstOrNull() == "long" &&
                            m.returnTypeName != "void" &&
                            !m.returnTypeName.startsWith("java.") &&
                            !m.returnTypeName.startsWith("android.") &&
                            (m.returnType?.fields?.any { f ->
                                f.typeName == "java.util.ArrayList" || f.typeName == "java.util.List"
                            } == true)
                    } ?: return@use null

                    JSONObject().apply {
                        put("stamp", apkStamp)
                        put("mediaClass", mediaClassData.name)
                        put("mediaGetStickerSet", getStickerSetMethod.name)
                        put("mediaCheckStickers", checkStickersMethod?.name.orEmpty())
                        put("fileClass", fileClassData.name)
                        put("connClass", resumeMethodData?.className.orEmpty())
                    }
                }
            }.onFailure {
                YLog.warn("DexKit scan failed for obfuscated Telegram", it)
            }.getOrNull()
        }

        private fun buildFromMapping(loader: ClassLoader, mapping: JSONObject): TelegramBridge? {
            return runCatching {
                val mediaCls = loader.loadClass(mapping.getString("mediaClass"))
                val fileCls = loader.loadClass(mapping.getString("fileClass"))
                val connName = mapping.optString("connClass")
                val connCls = if (connName.isNotEmpty()) runCatching { loader.loadClass(connName) }.getOrNull() else null

                val mediaGetInstance = mediaCls.declaredMethods.first { m ->
                    Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        m.returnType == mediaCls
                }.apply { isAccessible = true }

                val mediaGetStickerSetById = mediaCls.getDeclaredMethod(
                    mapping.getString("mediaGetStickerSet"),
                    Long::class.javaPrimitiveType
                ).apply { isAccessible = true }

                val checkName = mapping.optString("mediaCheckStickers")
                val mediaCheckStickers = if (checkName.isNotEmpty()) {
                    runCatching {
                        mediaCls.getDeclaredMethod(checkName, Int::class.javaPrimitiveType)
                            .apply { isAccessible = true }
                    }.getOrNull()
                } else null

                val fileGetInstance = fileCls.declaredMethods.first { m ->
                    Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        m.returnType == fileCls
                }.apply { isAccessible = true }

                val fileGetDirectory = fileCls.declaredMethods.firstOrNull { m ->
                    Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        m.returnType == File::class.java
                }?.apply { isAccessible = true }

                val fileGetPathToAttach = fileCls.declaredMethods.first { m ->
                    !Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 2 &&
                        m.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                        m.returnType == File::class.java &&
                        !m.parameterTypes[0].isPrimitive &&
                        m.parameterTypes[0] != String::class.java
                }.apply { isAccessible = true }

                val tlObjectType = fileGetPathToAttach.parameterTypes[0]
                val fileGetAttachFileName = fileCls.declaredMethods.firstOrNull { m ->
                    Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == tlObjectType &&
                        m.returnType == String::class.java
                }?.apply { isAccessible = true }

                val fileIsLoadingFile = fileCls.declaredMethods.firstOrNull { m ->
                    !Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == String::class.java &&
                        m.returnType == Boolean::class.javaPrimitiveType
                }?.apply { isAccessible = true }

                val fileLoadCandidates = fileCls.declaredMethods.filter { m ->
                    !Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 4 &&
                        !m.parameterTypes[0].isPrimitive &&
                        m.parameterTypes[1] == Any::class.java &&
                        m.parameterTypes[2] == Int::class.javaPrimitiveType &&
                        m.parameterTypes[3] == Int::class.javaPrimitiveType &&
                        m.returnType == Void.TYPE
                }.onEach { it.isAccessible = true }
                check(fileLoadCandidates.isNotEmpty())

                val fileCancelCandidates = fileCls.declaredMethods.filter { m ->
                    !Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 1 &&
                        !m.parameterTypes[0].isPrimitive &&
                        m.parameterTypes[0] != String::class.java &&
                        m.returnType == Void.TYPE
                }.onEach { it.isAccessible = true }

                val nativeResume = connCls?.declaredMethods?.firstOrNull { m ->
                    m.name == "native_resumeNetwork" &&
                        Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 2
                }?.apply { isAccessible = true }

                TelegramBridge(
                    loader = loader,
                    mediaDataControllerClass = mediaCls,
                    mediaGetInstance = mediaGetInstance,
                    mediaGetStickerSetById = mediaGetStickerSetById,
                    mediaCheckStickers = mediaCheckStickers,
                    fileLoaderClass = fileCls,
                    fileGetInstance = fileGetInstance,
                    fileGetDirectory = fileGetDirectory,
                    fileGetPathToAttach = fileGetPathToAttach,
                    fileGetAttachFileName = fileGetAttachFileName,
                    fileIsLoadingFile = fileIsLoadingFile,
                    fileLoadCandidates = fileLoadCandidates,
                    fileCancelCandidates = fileCancelCandidates,
                    userConfigClass = null,
                    connectionsClass = connCls,
                    nativeResumeNetwork = nativeResume,
                    resumeNetworkMaybe = null,
                    connectionsGetInstance = null,
                    isObfuscated = true
                )
            }.onFailure {
                YLog.warn("Could not build TelegramBridge from DexKit mapping", it)
            }.getOrNull()
        }

        private fun collectFields(clazz: Class<*>): List<Field> {
            val fields = mutableListOf<Field>()
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                for (f in current.declaredFields) {
                    if (!Modifier.isStatic(f.modifiers)) {
                        f.isAccessible = true
                        fields.add(f)
                    }
                }
                current = current.superclass
            }
            return fields
        }

        private fun findFieldByName(clazz: Class<*>, name: String): Field? {
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                val cls = current
                runCatching {
                    return cls.getDeclaredField(name).apply { isAccessible = true }
                }
                current = cls.superclass
            }
            return null
        }
    }
}

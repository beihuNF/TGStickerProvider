package cc.microblock.TGStickerProvider.utils

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import de.robv.android.xposed.XposedHelpers
import java.io.File

object CachePathHelper {
    /**
     * 获取宿主客户端的主缓存路径（兼容旧接口）
     */
    @JvmStatic
    fun getCachePath(context: Application?, packageName: String, classLoader: ClassLoader? = null): String {
        val paths = getCachePaths(context, packageName, classLoader)
        if (paths.isNotEmpty()) {
            return paths.first().absolutePath
        }
        return defaultExternalCacheDir(packageName).absolutePath
    }

    /**
     * 动态收集宿主客户端的所有候选缓存目录（包含 MEDIA_DIR_CACHE 与 MEDIA_DIR_DOCUMENT）
     */
    @JvmStatic
    fun getCachePaths(
        context: Application?,
        packageName: String,
        classLoader: ClassLoader? = null
    ): List<File> {
        val result = linkedMapOf<String, File>()
        fun addIfValid(dir: File?) {
            if (dir == null || !dir.isAbsolute) return
            val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!result.containsKey(key)) {
                result[key] = dir
            }
        }

        // 优先通过宿主 FileLoader（支持 DexKit 反混淆）获取真实缓存与文档双目录
        if (classLoader != null) {
            for (dir in getTelegramFileLoaderDirs(classLoader, context)) {
                addIfValid(dir)
            }
        }

        // 兼容 NekoX 等客户端在 SharedPreferences 中配置的自定义缓存目录
        if (context != null && isNekoX(context)) {
            val nekoXCachePath = getNekoXCachePath(context)
            if (!nekoXCachePath.isNullOrBlank()) {
                addIfValid(File(nekoXCachePath, "caches"))
                addIfValid(File(nekoXCachePath, "Telegram/Telegram Documents"))
            }
        }

        // 补充应用外部缓存与文档目录作为回退候选
        if (context != null) {
            addIfValid(context.externalCacheDir)
            context.getExternalFilesDir(null)?.let { extFiles ->
                addIfValid(File(extFiles, "Telegram/Telegram Documents"))
                addIfValid(File(extFiles, "caches"))
            }
        }

        if (result.isEmpty()) {
            addIfValid(defaultExternalCacheDir(packageName))
        }
        return result.values.toList()
    }

    /**
     * 通过 TelegramBridge 调用宿主 FileLoader.getDirectory 获取缓存（4）与文档（3）目录，兼容混淆后的官方客户端
     */
    private fun getTelegramFileLoaderDirs(classLoader: ClassLoader, context: Context?): List<File> {
        val bridge = cc.microblock.TGStickerProvider.hook.TelegramBridge.resolve(classLoader, context)
        if (bridge != null) {
            return listOf(4, 3).mapNotNull { dirType -> bridge.getMediaDirectory(dirType) }
        }
        return runCatching {
            val fileLoaderClass = classLoader.loadClass("org.telegram.messenger.FileLoader")
            val dirFields = listOf("MEDIA_DIR_CACHE", "MEDIA_DIR_DOCUMENT")
            dirFields.mapNotNull { fieldName ->
                runCatching {
                    val dirType = XposedHelpers.getStaticIntField(fileLoaderClass, fieldName)
                    val dir = XposedHelpers.callStaticMethod(fileLoaderClass, "getDirectory", dirType) as? File
                    if (dir != null && dir.isAbsolute && dir.exists()) dir else null
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 解析宿主私有数据目录（优先使用 Context.dataDir / filesDir，兼容多用户与分身）
     */
    @JvmStatic
    fun resolveHostFilesDir(context: Context?): File? {
        if (context == null) return null
        context.filesDir?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return File(context.dataDir, "files")
        }
        return null
    }

    /**
     * 通过 Context 检测是否为使用 nkmrcfg 配置的 NekoX 系客户端，避免硬编码 /data/data/
     */
    @JvmStatic
    fun isNekoX(context: Context): Boolean {
        val dataRoot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { context.dataDir }.getOrNull()
        } else {
            runCatching { context.filesDir?.parentFile }.getOrNull()
        }
        if (dataRoot != null && File(dataRoot, "shared_prefs/nkmrcfg.xml").isFile) {
            return true
        }
        return runCatching {
            context.getSharedPreferences("nkmrcfg", Context.MODE_PRIVATE).contains("cache_path")
        }.getOrDefault(false)
    }

    /**
     * 兼容旧签名的 NekoX 检测方法
     */
    @JvmStatic
    fun isNekoX(packageName: String): Boolean {
        return packageName == "nekox.messenger"
    }

    /**
     * 从 NekoX 偏好设置中读取自定义缓存路径
     */
    @JvmStatic
    fun getNekoXCachePath(context: Context): String? {
        val preferences: SharedPreferences =
            context.getSharedPreferences(
                "nkmrcfg",
                Context.MODE_PRIVATE
            )
        return preferences.getString("cache_path", null)
    }

    private fun defaultExternalCacheDir(packageName: String): File {
        val externalRoot = runCatching { Environment.getExternalStorageDirectory() }
            .getOrDefault(File("/storage/self/primary"))
        return File(externalRoot, "Android/data/$packageName/cache")
    }
}

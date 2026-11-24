package cc.microblock.TGStickerProvider.hook

import android.annotation.SuppressLint
import android.app.Application
import android.database.CursorWindow
import android.os.SystemClock
import cc.microblock.TGStickerProvider.*
import cc.microblock.TGStickerProvider.utils.CachePathHelper
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import java.io.File
import kotlin.concurrent.thread

@InjectYukiHookWithXposed(entryClassName = "TGStickerProvider", isUsingXposedModuleStatus = true)
class HookEntry : IYukiHookXposedInit {
    companion object {
        // 调整为安全的 16MB 窗口，兼顾大贴纸 BLOB 读取与低内存设备稳定性
        private const val SAFE_CURSOR_WINDOW_SIZE = 16 * 1024 * 1024
        private const val CONTEXT_WAIT_RETRIES = 20
        private const val CONTEXT_WAIT_INTERVAL_MS = 250L
    }

    @SuppressLint("DiscouragedPrivateApi")
    override fun onInit() = configs {
        debugLog {
            tag = "TGStickerProvider"
            isEnable = BuildConfig.DEBUG
        }
        runCatching {
            CursorWindow::class.java.getDeclaredField("sCursorWindowSize").apply {
                isAccessible = true
                setInt(null, SAFE_CURSOR_WINDOW_SIZE)
            }
        }.onFailure {
            YLog.warn("Could not adjust CursorWindow size", it)
        }
    }

    override fun onHook() = encase {
        val packageName = this.packageName
        if (packageName.startsWith("android") || Application.getProcessName() != packageName) return@encase

        thread(name = "TGStickerProvider") {
            Thread.sleep(1000)
            val hostLoader = this.appClassLoader ?: return@thread

            // 优先通过宿主 Context 动态获取私有 files 目录，兼容多用户与应用分身
            var files = CachePathHelper.resolveHostFilesDir(this.appContext)
            if (files == null) {
                repeat(CONTEXT_WAIT_RETRIES) {
                    Thread.sleep(CONTEXT_WAIT_INTERVAL_MS)
                    files = CachePathHelper.resolveHostFilesDir(this.appContext)
                    if (files != null) return@repeat
                }
            }
            val hostFilesDir = files ?: return@thread

            // 校验是否为 Telegram 系客户端，非 Telegram 宿主立即退出避免空转耗电
            if (!isTelegramHost(hostLoader, hostFilesDir)) {
                YLog.info("Skip non-Telegram host: $packageName")
                return@thread
            }

            val cache = TelegramStickerCache(hostFilesDir)
            val downloads = TelegramStickerDownloads(hostLoader, { this.appContext }, ::exportOriginal)
            var packs = emptyMap<Long, StickerPack>()
            var cachedPacks = emptyList<CachedStickerPack>()
            var lastCatalog: String? = null
            var catalogReady = false
            var listening = false
            var nextScan = 0L
            while (true) {
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextScan && !downloads.isActive) {
                        nextScan = now + 40_000
                        val updated = cache.read()
                        if (updated != null) {
                            val catalog = if (updated != cachedPacks || !catalogFile.isFile) {
                                encodeCatalog(updated.map { it.pack })
                            } else lastCatalog
                            if (catalog != null && (catalog != lastCatalog || !catalogFile.isFile)) {
                                writeAtomically(catalogFile) { it.writeText(catalog) }
                                lastCatalog = catalog
                            }
                            cachedPacks = updated
                            packs = updated.associate { it.pack.id to it.pack }
                            catalogReady = true
                            val context = this.appContext
                            if (!listening && context != null) {
                                downloads.listen(context)
                                listening = true
                            }
                            if (listening) {
                                updateActiveHost(packageName)
                            }
                            File(nomediaPath).apply { if (!exists()) createNewFile() }
                            // 每轮扫描动态刷新宿主缓存与文档双目录，解决冷启动初始化竞态问题
                            val cacheDirs = CachePathHelper.getCachePaths(this.appContext, packageName, hostLoader)
                            for (pack in cachedPacks) {
                                try {
                                    scanOriginals(cacheDirs, pack)
                                } catch (e: Exception) {
                                    YLog.warn("Could not export cached sticker pack ${pack.pack.id}", e)
                                }
                            }
                        }
                    }
                    if (catalogReady && listening) downloads.tick(packs)
                } catch (e: Exception) {
                    YLog.warn("Telegram sticker export failed", e)
                }
                Thread.sleep(if (downloads.isActive) 200 else 1000)
            }
        }
    }

    /**
     * 检测宿主进程是否为 Telegram 系客户端（存在 FileLoader 类或 cache4.db 数据库）
     */
    private fun isTelegramHost(loader: ClassLoader, filesDir: File): Boolean {
        val hasFileLoader = runCatching {
            loader.loadClass("org.telegram.messenger.FileLoader")
        }.isSuccess
        if (hasFileLoader) return true
        return hasTelegramCacheDb(filesDir)
    }

    /**
     * 检查宿主 files 目录及多账号子目录中是否存在 cache4.db
     */
    private fun hasTelegramCacheDb(filesDir: File): Boolean {
        if (File(filesDir, "cache4.db").isFile) return true
        return filesDir.listFiles().orEmpty().any { child ->
            child.isDirectory &&
                child.name.matches(Regex("account[0-9]+")) &&
                File(child, "cache4.db").isFile
        }
    }

    /**
     * 登记活跃宿主并更新首选下载宿主（当前活跃客户端在写入目录后接管下载广播）
     */
    private fun updateActiveHost(packageName: String) {
        runCatching { registerActiveHost(packageName) }
            .onFailure { YLog.warn("Could not register active host $packageName", it) }
        val currentRaw = runCatching {
            if (downloadHost.isFile) downloadHost.readText() else null
        }.getOrNull()
        if (currentRaw?.trim() != packageName) {
            writeAtomically(downloadHost) { it.writeText(packageName) }
        }
    }

    /**
     * 在所有候选缓存目录中扫描贴纸原文件（含无后缀文件）及缩略图
     */
    private fun scanOriginals(cacheDirs: List<File>, cached: CachedStickerPack) {
        val pack = cached.pack
        for (document in pack.documents) {
            val extension = document.extension ?: continue
            val target = File(pack.cacheDirectory, "${document.id}_high.$extension")
            if (target.isFile && target.length() > 0) continue

            val dcId = cached.dcIds[document.id]
            val source = if (dcId != null) {
                findFirstValidFile(
                    cacheDirs,
                    listOf("${dcId}_${document.id}.$extension", "${dcId}_${document.id}")
                )
            } else null

            if (source != null) {
                exportOriginal(pack, document, source)
            } else if (extension == "webp") {
                val low = File(pack.cacheDirectory, "${document.id}_low.webp")
                if (!low.exists()) {
                    val thumbnail = findFirstValidFile(
                        cacheDirs,
                        listOf("-${document.id}_1109.webp", "-${document.id}_1115.webp")
                    )
                    if (thumbnail != null) {
                        writeAtomically(low) { thumbnail.copyTo(it) }
                    }
                }
            }
        }
    }

    /**
     * 按顺序在多个候选目录与文件名中查找第一个非空文件
     */
    private fun findFirstValidFile(dirs: List<File>, names: List<String>): File? {
        for (dir in dirs) {
            for (name in names) {
                val candidate = File(dir, name)
                if (candidate.isFile && candidate.length() > 0) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun exportOriginal(pack: StickerPack, document: StickerDocument, original: File) {
        val target = File(pack.cacheDirectory, "${document.id}_high.${document.extension}")
        if (!target.isFile || target.length() == 0L) writeAtomically(target) { original.copyTo(it) }
        if (document.extension == "webp") File(pack.cacheDirectory, "${document.id}_low.webp").delete()
    }
}

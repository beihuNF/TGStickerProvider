package cc.microblock.TGStickerProvider.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cc.microblock.TGStickerProvider.DownloadProgress
import cc.microblock.TGStickerProvider.DownloadRequest
import cc.microblock.TGStickerProvider.StickerDocument
import cc.microblock.TGStickerProvider.StickerPack
import cc.microblock.TGStickerProvider.downloadAction
import cc.microblock.TGStickerProvider.downloadPermission
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal class TelegramStickerDownloads(
    private val loader: ClassLoader,
    private val export: (StickerPack, StickerDocument, File) -> Unit
) {
    private data class Task(
        val pack: StickerPack,
        val document: StickerDocument,
        val target: File,
        val retries: Int = 0
    )
    private data class NativeSticker(
        val fileLoader: Any,
        val parent: Any,
        val document: Any,
        val account: Int
    )
    private data class Transfer(
        val task: Task,
        val sticker: NativeSticker,
        val attachFileName: String?,
        val paths: List<File>,
        val deadline: Long,
        val startedByModule: NativeSticker?
    )

    companion object {
        private const val MAX_CONCURRENT_DOWNLOADS = 10
        private const val TRANSFER_TIMEOUT_MS = 25_000L
        private const val MAX_TASK_RETRIES = 1
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val NETWORK_KEEPALIVE_MS = 4_000L
        private const val DEFAULT_MAX_ACCOUNT_COUNT = 4
    }

    private val mainThread = Handler(Looper.getMainLooper())
    private var token: String? = null
    private var progress: DownloadProgress? = null
    private val pending = ArrayDeque<Task>()
    private val active = mutableListOf<Transfer>()
    private var native = emptyMap<Pair<Long, Long>, NativeSticker>()
    private val failedPacks = linkedSetOf<String>()
    private var lastPublished: DownloadProgress? = null
    private var nextHeartbeat = 0L
    private var nextNetworkKeepAlive = 0L

    /** 当前是否处于活跃下载状态 */
    val isActive: Boolean get() = active.isNotEmpty() || pending.isNotEmpty()

    private val downloadPriority: Int by lazy {
        runCatching {
            val fileLoaderClass = loader.loadClass("org.telegram.messenger.FileLoader")
            XposedHelpers.getStaticIntField(fileLoaderClass, "PRIORITY_NORMAL_UP")
        }.getOrDefault(2)
    }

    @Volatile private var request: DownloadRequest? = null

    @Suppress("DEPRECATION")
    fun listen(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != downloadAction) return
                val incoming = runCatching { UUID.fromString(intent.getStringExtra("token")).toString() }
                    .getOrNull() ?: return
                val ids = intent.getLongArrayExtra("packIds") ?: return
                if (ids.isEmpty() && request?.token == incoming) request = null
                else if (ids.size in 1..8192) request = DownloadRequest(incoming, ids.toSet())
            }
        }
        val filter = IntentFilter(downloadAction)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, downloadPermission, null, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, downloadPermission, null)
        }
    }

    fun tick(packs: Map<Long, StickerPack>) {
        val incoming = request
        if (incoming?.token != token) reset(incoming, packs)
        val state = progress ?: return
        publishProgress()
        if (state.done) return

        // 后台下载期间定期唤醒有活跃下载任务的账号网络连接，防止切后台后网络挂起
        keepNetworkAwake()

        val downloads = active.iterator()
        while (downloads.hasNext()) {
            val transfer = downloads.next()
            val original = transfer.paths.firstOrNull { candidate ->
                candidate.isFile && candidate.length() > 0 && isDownloadComplete(transfer, candidate)
            }
            if (original != null) {
                val success = runCatching {
                    export(transfer.task.pack, transfer.task.document, original)
                }.onFailure { YLog.warn("Could not export sticker original", it) }.isSuccess
                finish(transfer.task, success)
            } else if (SystemClock.elapsedRealtime() >= transfer.deadline) {
                stop(transfer)
                if (transfer.task.retries < MAX_TASK_RETRIES) {
                    // 超时任务允许自动重试一次
                    pending.addLast(transfer.task.copy(retries = transfer.task.retries + 1))
                } else {
                    finish(transfer.task, false)
                }
            } else continue
            downloads.remove()
        }

        // 保持并发下载窗口满载，同时避免单次轮询阻塞取消检查
        repeat((MAX_CONCURRENT_DOWNLOADS - active.size).coerceAtLeast(0)) {
            val task = pending.removeFirstOrNull() ?: return@repeat
            if (task.target.isFile && task.target.length() > 0) {
                finish(task, true)
                return@repeat
            }
            val sticker = native[task.pack.id to task.document.id]
            if (sticker == null) {
                finish(task, false)
                return@repeat
            }
            val transfer = runCatching {
                val paths = listOf(false, true).mapNotNull { forceCache ->
                    XposedHelpers.callMethod(sticker.fileLoader, "getPathToAttach", sticker.document, forceCache) as? File
                }.filter { it.isAbsolute }.distinct()
                check(paths.isNotEmpty())
                val attachFileName = runCatching {
                    XposedHelpers.callStaticMethod(
                        sticker.fileLoader.javaClass, "getAttachFileName", sticker.document
                    ) as? String
                }.getOrNull()
                val alreadyComplete = paths.any { candidate ->
                    candidate.isFile && candidate.length() > 0 &&
                        !isLoadingFile(sticker.fileLoader, attachFileName) &&
                        !hasTempFile(candidate)
                }
                val startedByModule = if (!alreadyComplete) {
                    if (isLoadingFile(sticker.fileLoader, attachFileName)) null
                    else {
                        // 直接将 TL_messages_stickerSet 作为 parentObject 传入 FileLoader.loadFile
                        XposedHelpers.callMethod(
                            sticker.fileLoader, "loadFile", sticker.document, sticker.parent, downloadPriority, 1
                        )
                        sticker
                    }
                } else null
                Transfer(
                    task,
                    sticker,
                    attachFileName,
                    paths,
                    SystemClock.elapsedRealtime() + TRANSFER_TIMEOUT_MS,
                    startedByModule
                )
            }.onFailure { YLog.warn("Telegram could not queue a sticker download", it) }.getOrNull()
            if (transfer == null) finish(task, false) else active.add(transfer)
        }

        if (active.isEmpty() && pending.isEmpty()) {
            progress = requireNotNull(progress).copy(done = true, message = failedPacks.take(5).joinToString("、"))
        }
        publishProgress()
    }

    /**
     * 校验目标文件已完成下载（不在加载中且无对应 .temp 临时文件）
     */
    private fun isDownloadComplete(transfer: Transfer, original: File): Boolean {
        if (isLoadingFile(transfer.sticker.fileLoader, transfer.attachFileName)) return false
        return !hasTempFile(original)
    }

    /**
     * 检查宿主 FileLoader 是否仍在下载指定文件名
     */
    private fun isLoadingFile(fileLoader: Any, attachFileName: String?): Boolean {
        if (attachFileName.isNullOrEmpty()) return false
        return runCatching {
            XposedHelpers.callMethod(fileLoader, "isLoadingFile", attachFileName) == true
        }.getOrDefault(false)
    }

    /**
     * 检查目标文件同目录下是否存在未完成的 .temp 临时文件
     */
    private fun hasTempFile(file: File): Boolean {
        val parent = file.parentFile ?: return false
        if (File(parent, "${file.name}.temp").exists()) return true
        val stem = file.nameWithoutExtension
        return stem.isNotEmpty() && File(parent, "$stem.temp").exists()
    }

    private fun stop(transfer: Transfer) {
        val sticker = transfer.startedByModule ?: return
        runCatching { XposedHelpers.callMethod(sticker.fileLoader, "cancelLoadFile", sticker.document) }
            .onFailure { YLog.warn("Could not cancel Telegram sticker download", it) }
    }

    private fun publishProgress() {
        val status = progress ?: return
        val now = SystemClock.elapsedRealtime()
        if (status == lastPublished && (status.done || now < nextHeartbeat)) return
        status.publish()
        lastPublished = status
        nextHeartbeat = now + HEARTBEAT_INTERVAL_MS
    }

    private fun reset(request: DownloadRequest?, packs: Map<Long, StickerPack>) {
        token = request?.token
        progress = null
        pending.clear()
        active.forEach(::stop)
        active.clear()
        failedPacks.clear()
        native = emptyMap()
        if (request == null) return

        for (pack in request.packIds.mapNotNull(packs::get)) {
            for (document in pack.documents) {
                val ext = document.extension ?: continue
                val target = File(pack.cacheDirectory, "${document.id}_high.$ext")
                if (!target.isFile || target.length() == 0L) pending.add(Task(pack, document, target))
            }
        }
        progress = DownloadProgress(request.token, pending.size, 0, 0, pending.isEmpty(),
            if (request.packIds.none(packs::containsKey)) "Telegram 还未加载所选贴纸包" else "")
        if (pending.isEmpty()) return

        native = runCatching {
            var found = snapshotNativeStickers(request.packIds)
            val hasMissingPacks = { map: Map<Pair<Long, Long>, NativeSticker> ->
                request.packIds.any { id -> map.keys.none { it.first == id } }
            }
            if (hasMissingPacks(found)) {
                // 若存在未加载到内存的请求贴纸包，主动触发 MediaDataController 加载并轮询等待
                triggerStickerSetsLoad()
                repeat(8) {
                    if (!hasMissingPacks(found)) return@repeat
                    Thread.sleep(400)
                    found = snapshotNativeStickers(request.packIds)
                }
            }
            found
        }.onFailure { YLog.warn("Telegram client does not expose a compatible sticker downloader", it) }
            .getOrDefault(emptyMap())
        if (native.isEmpty()) {
            progress = requireNotNull(progress).copy(finished = pending.size, failed = pending.size, done = true,
                message = "当前 Telegram 客户端未提供可用的贴纸下载接口，或尚未加载这些贴纸包")
            pending.clear()
        } else {
            nextNetworkKeepAlive = 0L
            keepNetworkAwake()
        }
    }

    private fun finish(task: Task, success: Boolean) {
        val state = requireNotNull(progress)
        if (!success) failedPacks.add(task.pack.title.replace('\n', ' '))
        progress = state.copy(finished = state.finished + 1, failed = state.failed + if (success) 0 else 1)
    }

    private fun snapshotNativeStickers(ids: Set<Long>): Map<Pair<Long, Long>, NativeSticker> {
        val snapshot = FutureTask { findNativeStickers(ids) }
        mainThread.post(snapshot)
        return try {
            snapshot.get(5, TimeUnit.SECONDS)
        } finally {
            snapshot.cancel(false)
        }
    }

    /**
     * 安全读取 UserConfig.MAX_ACCOUNT_COUNT，异常时回退为默认值 4
     */
    private fun getMaxAccountCount(configClass: Class<*>): Int {
        return runCatching {
            XposedHelpers.getStaticIntField(configClass, "MAX_ACCOUNT_COUNT").coerceAtLeast(1)
        }.getOrDefault(DEFAULT_MAX_ACCOUNT_COUNT)
    }

    /**
     * 主动触发 MediaDataController 从本地数据库加载贴纸集到内存
     */
    private fun triggerStickerSetsLoad() {
        val task = FutureTask {
            runCatching {
                val data = loader.loadClass("org.telegram.messenger.MediaDataController")
                val config = loader.loadClass("org.telegram.messenger.UserConfig")
                val accounts = getMaxAccountCount(config)
                val types = listOf("TYPE_IMAGE", "TYPE_MASK", "TYPE_EMOJIPACKS", "TYPE_EMOJI").mapNotNull { field ->
                    runCatching { XposedHelpers.getStaticIntField(data, field) }.getOrNull()
                }.distinct()
                for (account in 0 until accounts) {
                    val user = XposedHelpers.callStaticMethod(config, "getInstance", account)
                    if (XposedHelpers.callMethod(user, "isClientActivated") != true) continue
                    val controller = XposedHelpers.callStaticMethod(data, "getInstance", account)
                    for (type in types) {
                        XposedHelpers.callMethod(controller, "checkStickers", type)
                    }
                }
            }
        }
        mainThread.post(task)
        runCatching { task.get(3, TimeUnit.SECONDS) }
    }

    /**
     * 收集当前存在活跃或待下载任务的账号索引
     */
    private fun activeAccountIds(): Set<Int> {
        val accounts = linkedSetOf<Int>()
        for (transfer in active) {
            accounts.add(transfer.sticker.account)
        }
        if (accounts.isEmpty()) {
            for (task in pending) {
                native[task.pack.id to task.document.id]?.let { accounts.add(it.account) }
            }
        }
        return accounts
    }

    /**
     * 定期对有活跃下载任务的账号调用 ConnectionsManager.resumeNetworkMaybe 保持网络连接活跃
     */
    private fun keepNetworkAwake() {
        val now = SystemClock.elapsedRealtime()
        if (now < nextNetworkKeepAlive) return
        val accounts = activeAccountIds()
        if (accounts.isEmpty()) return
        nextNetworkKeepAlive = now + NETWORK_KEEPALIVE_MS
        runCatching {
            val config = loader.loadClass("org.telegram.messenger.UserConfig")
            val conn = loader.loadClass("org.telegram.tgnet.ConnectionsManager")
            for (account in accounts) {
                val user = XposedHelpers.callStaticMethod(config, "getInstance", account)
                if (XposedHelpers.callMethod(user, "isClientActivated") != true) continue
                val manager = XposedHelpers.callStaticMethod(conn, "getInstance", account)
                XposedHelpers.callMethod(manager, "resumeNetworkMaybe")
            }
        }.onFailure { YLog.warn("Could not resume Telegram network", it) }
    }

    private fun findNativeStickers(ids: Set<Long>): Map<Pair<Long, Long>, NativeSticker> {
        val data = loader.loadClass("org.telegram.messenger.MediaDataController")
        val config = loader.loadClass("org.telegram.messenger.UserConfig")
        val fileLoader = loader.loadClass("org.telegram.messenger.FileLoader")
        val accounts = getMaxAccountCount(config)
        return buildMap {
            for (account in 0 until accounts) {
                val user = XposedHelpers.callStaticMethod(config, "getInstance", account)
                if (XposedHelpers.callMethod(user, "isClientActivated") != true) continue
                val controller = XposedHelpers.callStaticMethod(data, "getInstance", account)
                val files = XposedHelpers.callStaticMethod(fileLoader, "getInstance", account)
                for (id in ids) {
                    val pack = XposedHelpers.callMethod(controller, "getStickerSetById", id) ?: continue
                    val docs = XposedHelpers.getObjectField(pack, "documents") as? List<*> ?: continue
                    for (document in docs.filterNotNull()) {
                        val docId = XposedHelpers.getLongField(document, "id")
                        val key = id to docId
                        if (!containsKey(key)) put(key, NativeSticker(files, pack, document, account))
                    }
                }
            }
        }
    }
}

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
import java.io.File
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal class TelegramStickerDownloads(
    private val loader: ClassLoader,
    private val contextProvider: () -> Context?,
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

    @Volatile private var request: DownloadRequest? = null

    private fun resolveBridge(): TelegramBridge? = TelegramBridge.resolve(loader, contextProvider())

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

        val bridge = resolveBridge() ?: return
        // 后台下载期间定期唤醒有活跃下载任务的账号网络连接，防止切后台后网络挂起
        keepNetworkAwake(bridge)

        val downloads = active.iterator()
        while (downloads.hasNext()) {
            val transfer = downloads.next()
            val original = transfer.paths.firstOrNull { candidate ->
                candidate.isFile && candidate.length() > 0 && isDownloadComplete(bridge, transfer, candidate)
            }
            if (original != null) {
                val success = runCatching {
                    export(transfer.task.pack, transfer.task.document, original)
                }.onFailure { YLog.warn("Could not export sticker original", it) }.isSuccess
                finish(transfer.task, success)
            } else if (SystemClock.elapsedRealtime() >= transfer.deadline) {
                stop(bridge, transfer)
                if (transfer.task.retries < MAX_TASK_RETRIES) {
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
                    bridge.getPathToAttach(sticker.fileLoader, sticker.document, forceCache)
                }.filter { it.isAbsolute }.distinct()
                check(paths.isNotEmpty())
                val attachFileName = bridge.getAttachFileName(sticker.document)
                val alreadyComplete = paths.any { candidate ->
                    candidate.isFile && candidate.length() > 0 &&
                        !bridge.isLoadingFile(sticker.fileLoader, attachFileName) &&
                        !hasTempFile(candidate)
                }
                val startedByModule = if (!alreadyComplete) {
                    if (bridge.isLoadingFile(sticker.fileLoader, attachFileName)) null
                    else {
                        bridge.loadFile(sticker.fileLoader, sticker.document, sticker.parent)
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

    private fun isDownloadComplete(bridge: TelegramBridge, transfer: Transfer, original: File): Boolean {
        if (bridge.isLoadingFile(transfer.sticker.fileLoader, transfer.attachFileName)) return false
        return !hasTempFile(original)
    }

    private fun hasTempFile(file: File): Boolean {
        val parent = file.parentFile ?: return false
        if (File(parent, "${file.name}.temp").exists()) return true
        val stem = file.nameWithoutExtension
        return stem.isNotEmpty() && File(parent, "$stem.temp").exists()
    }

    private fun stop(bridge: TelegramBridge?, transfer: Transfer) {
        val sticker = transfer.startedByModule ?: return
        bridge?.cancelLoadFile(sticker.fileLoader, sticker.document)
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
        val oldBridge = resolveBridge()
        token = request?.token
        progress = null
        pending.clear()
        active.forEach { stop(oldBridge, it) }
        active.clear()
        failedPacks.clear()
        native = emptyMap()
        if (request == null) return

        val expectedDocIdsByPack = mutableMapOf<Long, Set<Long>>()
        for (pack in request.packIds.mapNotNull(packs::get)) {
            expectedDocIdsByPack[pack.id] = pack.documents.map { it.id }.toSet()
            for (document in pack.documents) {
                val ext = document.extension ?: continue
                val target = File(pack.cacheDirectory, "${document.id}_high.$ext")
                if (!target.isFile || target.length() == 0L) pending.add(Task(pack, document, target))
            }
        }
        progress = DownloadProgress(
            request.token, pending.size, 0, 0, pending.isEmpty(),
            if (request.packIds.none(packs::containsKey)) "Telegram 还未加载所选贴纸包" else ""
        )
        if (pending.isEmpty()) return

        val bridge = resolveBridge()
        if (bridge == null) {
            val detail = TelegramBridge.lastError
            progress = requireNotNull(progress).copy(
                finished = pending.size,
                failed = pending.size,
                done = true,
                message = if (detail.isNotEmpty()) "未能解析下载接口（$detail）" else "未能解析当前 Telegram 客户端的下载接口"
            )
            pending.clear()
            return
        }

        native = runCatching {
            var found = snapshotNativeStickers(bridge, request.packIds, expectedDocIdsByPack)
            val hasMissingPacks = { map: Map<Pair<Long, Long>, NativeSticker> ->
                request.packIds.any { id -> map.keys.none { it.first == id } }
            }
            if (hasMissingPacks(found)) {
                triggerStickerSetsLoad(bridge)
                repeat(8) {
                    if (!hasMissingPacks(found)) return@repeat
                    Thread.sleep(400)
                    found = snapshotNativeStickers(bridge, request.packIds, expectedDocIdsByPack)
                }
            }
            found
        }.onFailure { YLog.warn("Telegram client does not expose a compatible sticker downloader", it) }
            .getOrDefault(emptyMap())

        if (native.isEmpty()) {
            progress = requireNotNull(progress).copy(
                finished = pending.size,
                failed = pending.size,
                done = true,
                message = "当前 Telegram 客户端尚未加载所选贴纸包，请先打开贴纸面板后重试"
            )
            pending.clear()
        } else {
            nextNetworkKeepAlive = 0L
            keepNetworkAwake(bridge)
        }
    }

    private fun finish(task: Task, success: Boolean) {
        val state = requireNotNull(progress)
        if (!success) failedPacks.add(task.pack.title.replace('\n', ' '))
        progress = state.copy(finished = state.finished + 1, failed = state.failed + if (success) 0 else 1)
    }

    private fun snapshotNativeStickers(
        bridge: TelegramBridge,
        ids: Set<Long>,
        expectedDocIdsByPack: Map<Long, Set<Long>>
    ): Map<Pair<Long, Long>, NativeSticker> {
        val snapshot = FutureTask { findNativeStickers(bridge, ids, expectedDocIdsByPack) }
        mainThread.post(snapshot)
        return try {
            snapshot.get(5, TimeUnit.SECONDS)
        } finally {
            snapshot.cancel(false)
        }
    }

    private fun triggerStickerSetsLoad(bridge: TelegramBridge) {
        val task = FutureTask {
            runCatching {
                val accounts = bridge.getMaxAccountCount()
                for (account in 0 until accounts) {
                    if (!bridge.isAccountActivated(account)) continue
                    bridge.checkAllStickerSets(account)
                }
            }
        }
        mainThread.post(task)
        runCatching { task.get(3, TimeUnit.SECONDS) }
    }

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

    private fun keepNetworkAwake(bridge: TelegramBridge) {
        val now = SystemClock.elapsedRealtime()
        if (now < nextNetworkKeepAlive) return
        val accounts = activeAccountIds()
        if (accounts.isEmpty()) return
        nextNetworkKeepAlive = now + NETWORK_KEEPALIVE_MS
        for (account in accounts) {
            if (!bridge.isAccountActivated(account)) continue
            bridge.resumeNetwork(account)
        }
    }

    private fun findNativeStickers(
        bridge: TelegramBridge,
        ids: Set<Long>,
        expectedDocIdsByPack: Map<Long, Set<Long>>
    ): Map<Pair<Long, Long>, NativeSticker> {
        val accounts = bridge.getMaxAccountCount()
        return buildMap {
            for (account in 0 until accounts) {
                if (!bridge.isAccountActivated(account)) continue
                val controller = bridge.getMediaDataController(account) ?: continue
                val files = bridge.getFileLoader(account) ?: continue
                for (id in ids) {
                    val pack = bridge.getStickerSetById(controller, id) ?: continue
                    val expectedDocs = expectedDocIdsByPack[id].orEmpty()
                    for ((docId, document) in bridge.extractDocuments(pack, expectedDocs)) {
                        val key = id to docId
                        if (!containsKey(key)) put(key, NativeSticker(files, pack, document, account))
                    }
                }
            }
        }
    }
}

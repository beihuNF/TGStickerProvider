@file:Suppress("SetTextI18n")

package cc.microblock.TGStickerProvider.ui.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cc.microblock.TGStickerProvider.*
import cc.microblock.TGStickerProvider.databinding.ActivityMainBinding
import cc.microblock.TGStickerProvider.ui.activity.base.BaseActivity
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.hook.log.YLog
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

data class StickerState(var highQuality: Int, var lowQuality: Int, var all: Int)
data class PreviewInfo(var type: String, var url: String, val version: Long = 0L)

internal data class StickerInfo(
    val name: String,
    val id: String,
    val hash: String,
    val remoteState: StickerState,
    val syncedState: StickerState,
    val all: Int,
    val type: String,
    val preview: PreviewInfo,
    val pack: StickerPack?,
    val category: StickerCategory,
    val unsupportedCount: Int,
    val syncedHighQualityGif: Boolean? = null
) {
    val missingCount: Int get() = (all - unsupportedCount - remoteState.all).coerceAtLeast(0)
    val downloadableCount: Int get() = ((pack?.documents?.count { it.extension != null } ?: 0) -
        remoteState.highQuality).coerceAtLeast(0)
    val hasAnimated: Boolean get() = type == "video/webm" || type == TGS_MIME
    val isPendingSync: Boolean
        get() = syncedState.all < remoteState.all ||
            syncedState.highQuality < remoteState.highQuality ||
            missingCount > 0

    fun needsQualityReconvert(targetHighQuality: Boolean): Boolean =
        hasAnimated && syncedState.all > 0 && syncedHighQualityGif != targetHighQuality

    fun isPendingFor(targetHighQuality: Boolean): Boolean =
        isPendingSync || needsQualityReconvert(targetHighQuality)
}

/**
 * 统一的 Material 3 进度对话框封装，用于下载、同步与缓存操作反馈
 */
internal class MaterialProgressDialog(
    private val activity: MainActivity,
    title: String,
    message: String,
    negativeText: String? = null,
    onNegative: (() -> Unit)? = null
) {
    private val contentView: View = LayoutInflater.from(activity).inflate(R.layout.dialog_progress, null)
    private val messageView: TextView = contentView.findViewById(R.id.progressMessage)
    private val indicator: LinearProgressIndicator = contentView.findViewById(R.id.progressIndicator)
    private val metaRow: View = contentView.findViewById(R.id.progressMetaRow)
    private val detailView: TextView = contentView.findViewById(R.id.progressDetail)
    private val percentView: TextView = contentView.findViewById(R.id.progressPercent)

    val dialog: AlertDialog

    init {
        messageView.text = message
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(contentView)
            .setCancelable(false)
        if (negativeText != null) {
            builder.setNegativeButton(negativeText) { _, _ -> onNegative?.invoke() }
        }
        dialog = builder.create()
    }

    val isShowing: Boolean get() = dialog.isShowing

    fun show(): MaterialProgressDialog {
        if (!activity.isFinishing && !activity.isDestroyed) dialog.show()
        return this
    }

    fun setMessage(text: String) {
        messageView.text = text
    }

    fun setProgress(current: Int, total: Int, detail: String) {
        val safeTotal = total.coerceAtLeast(1)
        val clamped = current.coerceIn(0, safeTotal)
        val percent = (clamped * 100) / safeTotal
        if (indicator.isIndeterminate) {
            indicator.isIndeterminate = false
        }
        indicator.max = safeTotal
        indicator.setProgressCompat(clamped, true)
        metaRow.visibility = View.VISIBLE
        detailView.text = detail
        percentView.text = "$percent%"
    }

    fun setIndeterminate(detail: String? = null) {
        if (!indicator.isIndeterminate) {
            indicator.isIndeterminate = true
        }
        if (detail != null) {
            metaRow.visibility = View.VISIBLE
            detailView.text = detail
            percentView.text = ""
        } else {
            metaRow.visibility = View.GONE
        }
    }

    fun dismiss() {
        if (dialog.isShowing) {
            runCatching { dialog.dismiss() }
        }
    }
}

/**
 * 贴纸包列表适配器：支持 DiffUtil 增量刷新、Glide 静态/视频首帧缩略图及 TGS 异步首帧内存缓存
 */
internal class RecyclerAdapterStickerList(
    private val act: MainActivity,
    private val tgsPreviewExecutor: ExecutorService
) : RecyclerView.Adapter<RecyclerAdapterStickerList.ViewHolder>() {

    private var stickerList: List<StickerInfo> = emptyList()
    private var lastTargetHighQuality: Boolean = act.useHighQualityGif

    // 限制 TGS 缩略图内存缓存为 4MB，避免大图占用过多堆内存
    private val tgsBitmapCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.sticker_name)
        val id: TextView = view.findViewById(R.id.sticker_id)
        val formatBadge: TextView = view.findViewById(R.id.formatBadge)
        val categoryBadge: TextView = view.findViewById(R.id.categoryBadge)
        val syncProgress: LinearProgressIndicator = view.findViewById(R.id.syncProgress)
        val syncedAll: TextView = view.findViewById(R.id.syncedAll)
        val syncedStatus: TextView = view.findViewById(R.id.syncedStatus)
        val exportedAll: TextView = view.findViewById(R.id.exportedAll)
        val exportedStatus: TextView = view.findViewById(R.id.exportedStatus)
        val totalAll: TextView = view.findViewById(R.id.totalAll)
        val totalStatus: TextView = view.findViewById(R.id.totalStatus)
        val downloadBtn: MaterialButton = view.findViewById(R.id.downloadBtn)
        val hideBtn: MaterialButton = view.findViewById(R.id.hideBtn)
        val syncBtn: MaterialButton = view.findViewById(R.id.syncBtn)
        val rmBtn: MaterialButton = view.findViewById(R.id.rmBtn)
        val imageView: ShapeableImageView = view.findViewById(R.id.imageView)
        val animatedBadge: ImageView = view.findViewById(R.id.animatedBadge)
        val unsupported: TextView = view.findViewById(R.id.unsupported)
    }

    /**
     * 使用 DiffUtil 计算增量变更并更新列表
     */
    fun submitList(newList: List<StickerInfo>, targetHighQuality: Boolean) {
        val oldList = stickerList
        val qualityChanged = lastTargetHighQuality != targetHighQuality
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldList[oldItemPosition].id == newList[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldList[oldItemPosition]
                val newItem = newList[newItemPosition]
                if (qualityChanged && newItem.hasAnimated) return false
                return oldItem == newItem
            }
        })
        stickerList = newList
        lastTargetHighQuality = targetHighQuality
        diffResult.dispatchUpdatesTo(this)
    }

    /**
     * 释放预览内存缓存
     */
    fun release() {
        tgsBitmapCache.evictAll()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sticker, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val s = stickerList[position]
        val ctx = holder.itemView.context
        val targetHighQuality = act.useHighQualityGif

        holder.name.text = s.name.replace('\n', ' ').trim()
        holder.id.text = s.id

        // 填充三列统计指标
        holder.syncedAll.text = s.syncedState.all.toString()
        holder.syncedStatus.text = "高 ${s.syncedState.highQuality} · 低 ${s.syncedState.lowQuality}"
        holder.exportedAll.text = s.remoteState.all.toString()
        holder.exportedStatus.text = "高 ${s.remoteState.highQuality} · 低 ${s.remoteState.lowQuality}"
        holder.totalAll.text = s.all.toString()
        holder.totalStatus.text = when {
            s.missingCount > 0 && s.unsupportedCount > 0 -> "缺 ${s.missingCount} · 异 ${s.unsupportedCount}"
            s.missingCount > 0 -> "缺 ${s.missingCount}"
            s.unsupportedCount > 0 -> "异 ${s.unsupportedCount}"
            else -> "完整"
        }

        // 绑定类别徽章
        when (s.category) {
            StickerCategory.MASK -> {
                holder.categoryBadge.visibility = View.VISIBLE
                holder.categoryBadge.text = "面具"
            }
            StickerCategory.EMOJI -> {
                holder.categoryBadge.visibility = View.VISIBLE
                holder.categoryBadge.text = "Emoji"
            }
            StickerCategory.REGULAR -> {
                holder.categoryBadge.visibility = View.GONE
            }
        }

        // 绑定格式徽章与语义配色
        val (badgeLabel, badgeBgRes, badgeTextRes) = when (s.type) {
            "image/webp" -> Triple("WEBP", R.color.colorBadgeWebpBg, R.color.colorBadgeWebpText)
            "video/webm" -> Triple("WEBM", R.color.colorBadgeWebmBg, R.color.colorBadgeWebmText)
            TGS_MIME -> Triple("TGS", R.color.colorBadgeTgsBg, R.color.colorBadgeTgsText)
            "unknown" -> Triple("未知", R.color.colorSurfaceSubtle, R.color.colorTextSecondary)
            else -> Triple("不支持", R.color.colorDangerContainer, R.color.colorOnDangerContainer)
        }
        holder.formatBadge.text = badgeLabel
        holder.formatBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, badgeBgRes))
        holder.formatBadge.setTextColor(ContextCompat.getColor(ctx, badgeTextRes))

        // 更新同步进度条
        val effectiveTotal = (s.all - s.unsupportedCount).coerceAtLeast(1)
        val progressPercent = ((s.syncedState.all * 100) / effectiveTotal).coerceIn(0, 100)
        holder.syncProgress.progress = progressPercent
        val needsQualitySwitch = s.needsQualityReconvert(targetHighQuality)
        val fullySynced = !s.isPendingSync && !needsQualitySwitch && s.syncedState.all > 0
        holder.syncProgress.setIndicatorColor(
            ContextCompat.getColor(ctx, if (fullySynced) R.color.colorSuccess else R.color.colorBrandPrimary)
        )

        val isSupportedFormat = s.type == "unknown" || s.type == "image/webp" || s.type == TGS_MIME || s.type == "video/webm"
        bindPreview(holder, s, isSupportedFormat)

        holder.rmBtn.visibility = View.VISIBLE
        holder.syncBtn.visibility = if (isSupportedFormat) View.VISIBLE else View.GONE
        holder.syncBtn.isEnabled = s.remoteState.all > 0
        holder.downloadBtn.isVisible = isSupportedFormat && s.downloadableCount > 0

        // 底部精简状态提示
        holder.unsupported.visibility = View.VISIBLE
        if (!isSupportedFormat) {
            holder.unsupported.text = "不支持的格式"
            holder.unsupported.setTextColor(ContextCompat.getColor(ctx, R.color.colorDanger))
        } else if (fullySynced) {
            holder.unsupported.text = "已同步"
            holder.unsupported.setTextColor(ContextCompat.getColor(ctx, R.color.colorSuccess))
        } else if (s.remoteState.all == 0) {
            holder.unsupported.text = "暂无缓存"
            holder.unsupported.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextTertiary))
        } else {
            val needSyncCount = (s.remoteState.all - s.syncedState.all).coerceAtLeast(0)
            val upgradeCount = (s.remoteState.highQuality - s.syncedState.highQuality).coerceAtLeast(0)
            holder.unsupported.text = when {
                needSyncCount > 0 -> "待同步 $needSyncCount 张"
                upgradeCount > 0 -> "可升级高清 $upgradeCount 张"
                needsQualitySwitch -> if (targetHighQuality) "可重转为高清 Gif" else "可重转为标准 Gif"
                s.missingCount > 0 -> "缺 ${s.missingCount} 张原件"
                else -> "就绪"
            }
            holder.unsupported.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextSecondary))
        }

        holder.downloadBtn.setOnClickListener { act.downloadSinglePack(s) }
        holder.hideBtn.setOnClickListener { act.hideStickerPack(s) }
        holder.syncBtn.setOnClickListener { act.syncStickerPacks(listOf(s)) }

        val cleanName = s.name.replace('\n', ' ').trim()
        val dialogDelExported = {
            showDeleteDialog(
                title = "删除导出缓存",
                message = "删除「$cleanName」的导出缓存？不影响已同步文件。"
            ) {
                File("$destDataPath/tgSync_${s.id}").deleteRecursively()
                if (s.pack == null) File("$stickerDataPath/${s.hash}").delete()
            }
        }

        holder.rmBtn.setOnClickListener {
            if (!isSupportedFormat) {
                dialogDelExported()
            } else {
                showDeleteDialog(
                    title = "删除已同步表情包",
                    message = "删除「$cleanName」已同步的 ${s.syncedState.all} 个文件？"
                ) {
                    File("$realDataPath/tgSync_${s.id}").deleteRecursively()
                    act.clearSyncedQualityRecord(s.id)
                }
            }
        }

        holder.rmBtn.setOnLongClickListener {
            dialogDelExported()
            true
        }
    }

    /**
     * 统一绑定 WEBP/WEBM/TGS 预览图与动图角标，避免 VideoView 解码器泄漏
     */
    private fun bindPreview(holder: ViewHolder, s: StickerInfo, isSupportedFormat: Boolean) {
        holder.imageView.tag = null
        val preview = s.preview
        val hasPreview = isSupportedFormat && preview.url.isNotEmpty()
        holder.animatedBadge.isVisible = hasPreview && (s.type == "video/webm" || s.type == TGS_MIME)

        if (!hasPreview) {
            Glide.with(act).clear(holder.imageView)
            holder.imageView.setImageResource(R.drawable.ic_sticker_empty)
            return
        }

        when (preview.type) {
            "webp" -> {
                Glide.with(act)
                    .load(File(preview.url))
                    .placeholder(R.drawable.ic_sticker_empty)
                    .error(R.drawable.ic_sticker_empty)
                    .into(holder.imageView)
            }
            "webm" -> {
                // 通过 Glide 解码视频首帧缩略图替代 VideoView，避免硬件解码器耗尽与黑屏闪烁
                Glide.with(act)
                    .asBitmap()
                    .load(File(preview.url))
                    .frame(0)
                    .placeholder(R.drawable.ic_sticker_empty)
                    .error(R.drawable.ic_sticker_empty)
                    .into(holder.imageView)
            }
            "tgs" -> {
                Glide.with(act).clear(holder.imageView)
                val cacheKey = "${preview.url}:${preview.version}"
                val cached = tgsBitmapCache.get(cacheKey)
                if (cached != null && !cached.isRecycled) {
                    holder.imageView.setImageBitmap(cached)
                    return
                }
                holder.imageView.tag = cacheKey
                holder.imageView.setImageResource(R.drawable.ic_sticker_empty)
                if (!tgsPreviewExecutor.isShutdown) {
                    runCatching {
                        tgsPreviewExecutor.execute {
                            val bitmap = renderTgsFirstFrame(File(preview.url))
                            if (bitmap != null) {
                                tgsBitmapCache.put(cacheKey, bitmap)
                                act.runOnUiThread {
                                    if (!act.isFinishing && !act.isDestroyed && holder.imageView.tag == cacheKey) {
                                        holder.imageView.setImageBitmap(bitmap)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                Glide.with(act).clear(holder.imageView)
                holder.imageView.setImageResource(R.drawable.ic_sticker_empty)
            }
        }
    }

    /**
     * 在后台线程解压 TGS 并通过 LottieDrawable 渲染首帧位图
     */
    private fun renderTgsFirstFrame(file: File): Bitmap? = runCatching {
        if (!file.isFile || file.length() == 0L) return null
        val composition = file.inputStream().buffered().use { input ->
            input.mark(2)
            val isGzip = input.read() == 0x1f && input.read() == 0x8b
            input.reset()
            (if (isGzip) GZIPInputStream(input) else input).use { stream ->
                LottieCompositionFactory.fromJsonInputStreamSync(stream, null).value
            }
        } ?: return null

        val targetSize = 128
        val bounds = composition.bounds
        val maxSide = maxOf(bounds.width(), bounds.height()).coerceAtLeast(1)
        val scale = targetSize.toFloat() / maxSide
        val width = (bounds.width() * scale).toInt().coerceIn(1, targetSize)
        val height = (bounds.height() * scale).toInt().coerceIn(1, targetSize)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val drawable = LottieDrawable().apply {
            setComposition(composition)
            setBounds(0, 0, width, height)
            progress = 0f
        }
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawable.draw(canvas)
        bitmap
    }.onFailure { YLog.warn("渲染 TGS 预览首帧失败: ${file.name}", it) }.getOrNull()

    private fun showDeleteDialog(title: String, message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(act)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("删除") { _, _ ->
                val progress = MaterialProgressDialog(act, "正在处理", "正在删除...").show()
                act.runBackgroundTask {
                    action()
                    act.runOnUiThread { progress.setMessage("正在更新列表...") }
                    act.updateStickerList()
                    act.runOnUiThread { progress.dismiss() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        // 视图回收时清除标记与 Glide 请求，消除异步复用错图
        holder.imageView.tag = null
        Glide.with(act).clear(holder.imageView)
    }

    override fun getItemCount(): Int = stickerList.size
}

class MainActivity : BaseActivity<ActivityMainBinding>() {

    companion object {
        private const val STATE_IN_MANAGE_MODE = "state_in_manage_mode"
        private const val STATE_FILTER = "state_filter"
        private const val STATE_REQUIRES_SYNC_ONLY = "state_requires_sync_only"
        private const val STATE_SELECTED_FORMAT = "state_selected_format"

        private const val PREF_SHOW_MASKS = "showMasks"
        private const val PREF_SHOW_EMOJI = "showEmoji"
        private const val PREF_HIGH_QUALITY_GIF = "useHighQualityGif"
        private const val PREF_HAS_READ_GUIDE = "hasReadGuide"

        private const val FORMAT_ALL = "all"
        private const val FORMAT_WEBP = "image/webp"
        private const val FORMAT_WEBM = "video/webm"
        private const val FORMAT_TGS = TGS_MIME
    }

    private val ignoreFile: File get() = File(exposedPath, "ignore.txt")
    private var packTitleMap: Map<String, String> = emptyMap()
    @Volatile
    private var allStickerPacks: List<StickerInfo> = emptyList()
    private val ignoredIds = linkedSetOf<String>()
    private val ignoreWriteLock = Any()

    // 统一管理主线程定时任务与后台线程池，便于在 onDestroy 时统一释放
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ignoreIoExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val tgsPreviewExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    private var downloadDialog: MaterialProgressDialog? = null
    private var activeDownloadPoll: Runnable? = null

    private val isSyncing = AtomicBoolean(false)
    private val syncCancelled = AtomicBoolean(false)
    private var activeSyncDialog: MaterialProgressDialog? = null
    @Volatile
    private var activeSyncExecutor: ExecutorService? = null

    private var searchDebounceRunnable: Runnable? = null

    private var inManageMode = false
    var filter = ""
    var requiresSyncOnly = false
    var useHighQualityGif = false
    private var selectedFormat = FORMAT_ALL

    private val preferences by lazy { getSharedPreferences("sticker_categories", MODE_PRIVATE) }
    private val syncMetaPrefs by lazy { getSharedPreferences("sticker_sync_meta", MODE_PRIVATE) }
    private var showMasks = false
    private var showEmoji = false

    private lateinit var stickerAdapter: RecyclerAdapterStickerList

    // Android 11+ 所有文件访问权限返回回调
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasStoragePermission()) {
            onGainedPermission()
        } else {
            showPermissionDeniedDialog(openAppSettings = false)
        }
    }

    // Android 9/10 存储权限请求回调
    private val legacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onGainedPermission()
        } else {
            val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            showPermissionDeniedDialog(openAppSettings = permanentlyDenied)
        }
    }

    private fun hasStoragePermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        if (hasStoragePermission()) {
            onGainedPermission()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(this, "请授予完全文件访问权限", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            val targetIntent = if (intent.resolveActivity(packageManager) != null) {
                intent
            } else {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
            manageStorageLauncher.launch(targetIntent)
        } else {
            legacyStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /**
     * 权限被拒绝后展示友好说明弹窗，不再递归死循环拉起设置页
     */
    private fun showPermissionDeniedDialog(openAppSettings: Boolean) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle("需要存储访问权限")
            .setMessage("本应用需要文件访问权限来读取 Telegram 导出缓存，并将转换后的表情包同步到本地目录。")
            .setNegativeButton("稍后再说", null)
            .setPositiveButton("去授权") { _, _ ->
                if (openAppSettings && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    manageStorageLauncher.launch(intent)
                } else {
                    requestPermission()
                }
            }
            .show()
    }

    private fun onGainedPermission() {
        runBackgroundTask { updateStickerList() }
    }

    internal fun runBackgroundTask(block: () -> Unit) {
        if (!ioExecutor.isShutdown) {
            runCatching { ioExecutor.execute(block) }
        }
    }

    internal fun clearSyncedQualityRecord(packId: String) {
        syncMetaPrefs.edit().remove("gif_quality_$packId").apply()
    }

    private fun readSyncedQualityRecord(packId: String): Boolean? {
        val key = "gif_quality_$packId"
        return if (syncMetaPrefs.contains(key)) syncMetaPrefs.getBoolean(key, false) else null
    }

    private fun saveSyncedQualityRecord(packId: String, highQuality: Boolean) {
        syncMetaPrefs.edit().putBoolean("gif_quality_$packId", highQuality).apply()
    }

    /**
     * 异步清理 cacheDir 中残留的 sticker-* 临时渲染目录
     */
    private fun cleanupStaleTempDirsAsync() {
        runBackgroundTask {
            runCatching {
                cleanupConversionTempDirs(cacheDir)
            }.onFailure { YLog.warn("清理临时缓存目录失败", it) }
        }
    }

    private data class SyncFileTask(
        val pack: StickerInfo,
        val cleanName: String,
        val id: String,
        val source: File,
        val destination: File,
        val existingForId: List<File>,
        val forceReconvert: Boolean
    )

    private fun cancelActiveSync() {
        if (syncCancelled.compareAndSet(false, true)) {
            cancelStickerConversions()
            runOnUiThread {
                activeSyncDialog?.setMessage("正在取消同步...")
            }
        }
    }

    internal fun syncStickerPacks(packs: List<StickerInfo>) {
        if (packs.isEmpty()) {
            Toast.makeText(this, "当前没有可同步的表情包", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isSyncing.compareAndSet(false, true)) {
            Toast.makeText(this, "正在同步中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        syncCancelled.set(false)
        val highQuality = useHighQualityGif
        val progress = MaterialProgressDialog(
            this,
            title = "正在同步",
            message = "正在扫描 ${packs.size} 个表情包...",
            negativeText = "取消"
        ) {
            cancelActiveSync()
        }.show()
        activeSyncDialog = progress

        runBackgroundTask {
            val successes = AtomicInteger(0)
            val failures = AtomicInteger(0)
            val completed = AtomicInteger(0)
            val errors = Collections.synchronizedList(mutableListOf<String>())
            val packHasFailure = Collections.synchronizedSet(mutableSetOf<String>())
            val packTouched = Collections.synchronizedSet(mutableSetOf<String>())
            val workerPool = Executors.newFixedThreadPool(2)
            activeSyncExecutor = workerPool

            try {
                // 先平铺扫描待转换的单张贴纸，实现单张维度的精确进度
                val tasks = mutableListOf<SyncFileTask>()
                for (pack in packs) {
                    if (syncCancelled.get()) break
                    val cleanName = pack.name.replace('\n', ' ').trim()
                    val documents = pack.pack?.documents?.associateBy { it.id.toString() }
                    val sources = stickerFiles(File(destDataPath, "tgSync_${pack.id}"), documents)
                    val output = File(realDataPath, "tgSync_${pack.id}")
                    val existing = output.listFiles().orEmpty().groupBy { it.name.substringBefore('_') }
                    val lastQuality = readSyncedQualityRecord(pack.id)

                    for ((id, source) in sources) {
                        val ext = if (source.extension == "webp") "png" else "gif"
                        val destination = File(output, "${source.nameWithoutExtension}.$ext")
                        val current = existing[id].orEmpty()
                        if (source.nameWithoutExtension.endsWith("_low") && current.any {
                                it.nameWithoutExtension == "${id}_high" && it.length() > 0
                            }) continue
                        val isAnimatedFile = source.extension != "webp"
                        val forceReconvert = isAnimatedFile && lastQuality != highQuality
                        tasks += SyncFileTask(pack, cleanName, id, source, destination, current, forceReconvert)
                    }
                }

                val totalCount = tasks.size
                if (totalCount > 0 && !syncCancelled.get()) {
                    runOnUiThread {
                        progress.setMessage("准备转换 $totalCount 张贴纸...")
                        progress.setProgress(0, totalCount, "已完成 0 / $totalCount")
                    }

                    val latch = CountDownLatch(totalCount)
                    for (task in tasks) {
                        workerPool.execute {
                            try {
                                if (syncCancelled.get()) return@execute
                                packTouched.add(task.pack.id)
                                val alreadyConverted = !task.forceReconvert &&
                                    task.destination.isFile &&
                                    task.destination.length() > 0 &&
                                    task.destination.lastModified() >= task.source.lastModified()
                                if (!alreadyConverted) {
                                    convertSticker(task.source, task.destination, highQuality, cacheDir, applicationContext)
                                }
                                if (task.source.nameWithoutExtension.endsWith("_high")) {
                                    task.existingForId
                                        .filter { it.nameWithoutExtension == "${task.id}_low" }
                                        .forEach { it.delete() }
                                }
                                successes.incrementAndGet()
                            } catch (e: Exception) {
                                if (!syncCancelled.get()) {
                                    YLog.error("Could not convert sticker ${task.id}", e)
                                    failures.incrementAndGet()
                                    packHasFailure.add(task.pack.id)
                                    synchronized(errors) {
                                        if (errors.size < 5) {
                                            errors += "${task.cleanName} / ${task.id}：${e.message}"
                                        }
                                    }
                                }
                            } finally {
                                val done = completed.incrementAndGet()
                                val ok = successes.get()
                                val fail = failures.get()
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed && progress.isShowing) {
                                        if (!syncCancelled.get()) {
                                            progress.setMessage(task.cleanName)
                                        }
                                        progress.setProgress(
                                            done,
                                            totalCount,
                                            "已完成 $done / $totalCount · 成功 $ok · 失败 $fail"
                                        )
                                    }
                                }
                                latch.countDown()
                            }
                        }
                    }
                    latch.await()
                }

                // 仅在未取消时记录各已成功同步包的 GIF 档位
                if (!syncCancelled.get()) {
                    for (pack in packs) {
                        if (pack.id in packTouched && pack.id !in packHasFailure) {
                            saveSyncedQualityRecord(pack.id, highQuality)
                        }
                    }
                }

                // 隔离 .nomedia 创建异常，防止影响同步收尾
                runCatching {
                    if (File(realDataPath).isDirectory) File(nomediaPath2).createNewFile()
                }.onFailure { YLog.warn("创建 .nomedia 失败", it) }
            } catch (e: Exception) {
                if (!syncCancelled.get()) {
                    YLog.error("Sticker sync did not finish", e)
                    failures.incrementAndGet()
                    errors += e.message.orEmpty()
                }
            } finally {
                workerPool.shutdownNow()
                activeSyncExecutor = null
                cleanupStaleTempDirsAsync()
                val wasCancelled = syncCancelled.get()
                isSyncing.set(false)
                updateStickerList()
                runOnUiThread {
                    if (activeSyncDialog === progress) activeSyncDialog = null
                    progress.dismiss()
                    if (!isFinishing && !isDestroyed) {
                        MaterialAlertDialogBuilder(this)
                            .setTitle(if (wasCancelled) "同步已取消" else "同步完成")
                            .setMessage(buildString {
                                append("成功 ${successes.get()} 张，失败 ${failures.get()} 张")
                                append("\n缓存：高清 ${packs.sumOf { it.remoteState.highQuality }} · 低清 ${packs.sumOf { it.remoteState.lowQuality }}")
                                val missing = packs.sumOf { it.missingCount }
                                val unsupported = packs.sumOf { it.unsupportedCount }
                                if (missing > 0 || unsupported > 0) {
                                    append("\n缺失 $missing · 不支持 $unsupported")
                                }
                                if (errors.isNotEmpty()) append("\n\n").append(errors.joinToString("\n"))
                            })
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }
            }
        }
    }

    /**
     * 单包快捷补齐高清原件入口
     */
    internal fun downloadSinglePack(pack: StickerInfo) {
        if (pack.downloadableCount <= 0) {
            Toast.makeText(this, "当前表情包没有可补齐的原件", Toast.LENGTH_SHORT).show()
            return
        }
        runBackgroundTask {
            val active = DownloadRequest.read(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (active != null) {
                    observeDownload()
                } else {
                    confirmOriginalDownloads(listOf(pack))
                }
            }
        }
    }

    internal fun offerOriginalDownloads(packs: List<StickerInfo>) {
        runBackgroundTask {
            val active = DownloadRequest.read(this)
            val hasCatalog = catalogFile.isFile
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (active != null) {
                    observeDownload()
                    return@runOnUiThread
                }
                val candidates = packs.filter { it.downloadableCount > 0 }
                if (candidates.isEmpty()) {
                    val message = if (!hasCatalog) "请先启用模块并打开 Telegram"
                    else "没有可补齐的高清原件"
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val checked = BooleanArray(candidates.size) { true }
                val labels = candidates.map {
                    "${it.name.replace('\n', ' ').trim()}（缺 ${it.downloadableCount} 张）"
                }.toTypedArray()

                val dialog = MaterialAlertDialogBuilder(this)
                    .setTitle("选择要补齐的表情包")
                    .setMultiChoiceItems(labels, checked) { d, index, selected ->
                        checked[index] = selected
                        val alert = d as AlertDialog
                        alert.getButton(AlertDialog.BUTTON_NEUTRAL)?.text =
                            if (checked.all { it }) "取消全选" else "全选"
                    }
                    .setNeutralButton("取消全选", null)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("下一步") { _, _ ->
                        val selected = candidates.filterIndexed { index, _ -> checked[index] }
                        if (selected.isNotEmpty()) confirmOriginalDownloads(selected)
                    }
                    .create()

                dialog.show()
                // 自定义全选/取消全选按钮点击，避免触发默认关闭弹窗行为
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { button ->
                    val selectAll = !checked.all { it }
                    val listView = dialog.listView
                    for (i in checked.indices) {
                        checked[i] = selectAll
                        listView.setItemChecked(i, selectAll)
                    }
                    (button as TextView).text = if (selectAll) "取消全选" else "全选"
                }
            }
        }
    }

    private fun confirmOriginalDownloads(packs: List<StickerInfo>) {
        val count = packs.sumOf { it.downloadableCount }
        val packIds = packs.mapNotNull { it.pack?.id }.toSet()
        MaterialAlertDialogBuilder(this)
            .setTitle("补齐高清原件")
            .setMessage("将通过 Telegram 下载 $count 张原件，请保持客户端与代理正常运行。")
            .setNegativeButton("取消", null)
            .setPositiveButton("开始下载") { _, _ ->
                submitDownloadRequest(packIds)
            }
            .show()
    }

    private fun submitDownloadRequest(packIds: Set<Long>) {
        if (packIds.isEmpty()) return
        runBackgroundTask {
            val request = DownloadRequest(UUID.randomUUID().toString(), packIds)
            val result = runCatching { request.submit(this) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    observeDownload()
                }.onFailure {
                    YLog.error("Could not submit sticker download", it)
                    Toast.makeText(this, "无法提交下载请求，请检查 Telegram 与存储权限", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stopDownloadPolling() {
        activeDownloadPoll?.let { mainHandler.removeCallbacks(it) }
        activeDownloadPoll = null
    }

    private fun observeDownload() {
        if (downloadDialog?.isShowing == true) return
        runBackgroundTask {
            val request = DownloadRequest.read(this) ?: return@runBackgroundTask
            runOnUiThread {
                if (isFinishing || isDestroyed || downloadDialog?.isShowing == true) return@runOnUiThread
                startDownloadObserverDialog(request)
            }
        }
    }

    private fun startDownloadObserverDialog(request: DownloadRequest) {
        val progress = MaterialProgressDialog(
            this,
            title = "补齐高清原件",
            message = "等待 Telegram 响应...",
            negativeText = "停止下载"
        ) {
            stopDownloadPolling()
            runBackgroundTask {
                runCatching { request.clear(this@MainActivity) }.onFailure {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "无法停止下载", Toast.LENGTH_SHORT).show()
                    }
                }
                updateStickerList()
            }
        }.show()
        progress.setIndeterminate("请保持 Telegram 客户端与代理运行")
        downloadDialog = progress

        var observedAt = SystemClock.elapsedRealtime()
        var lastModified = 0L
        var lastSend = 0L

        val poll = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed || !progress.isShowing) {
                    stopDownloadPolling()
                    return
                }
                val self = this
                // 磁盘读取放到后台线程，避免 500ms 轮询阻塞主线程
                runBackgroundTask {
                    val status = DownloadProgress.read(request.token)
                    val modified = downloadStatus.lastModified()
                    val now = SystemClock.elapsedRealtime()
                    val needResend = status == null && now - lastSend >= 5_000
                    if (needResend) {
                        runCatching { request.resend(this@MainActivity) }
                        lastSend = now
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed || !progress.isShowing) {
                            stopDownloadPolling()
                            return@runOnUiThread
                        }
                        if (status != null && modified != lastModified) {
                            lastModified = modified
                            observedAt = SystemClock.elapsedRealtime()
                            val successCount = (status.finished - status.failed).coerceAtLeast(0)
                            progress.setMessage("正在下载原件，请保持 Telegram 与代理运行")
                            progress.setProgress(
                                current = status.finished,
                                total = status.total,
                                detail = "已完成 ${status.finished} / ${status.total} · 成功 $successCount · 失败 ${status.failed}"
                            )
                        }
                        val timedOut = SystemClock.elapsedRealtime() - observedAt > 65_000
                        if (status?.done == true || timedOut) {
                            stopDownloadPolling()
                            progress.dismiss()
                            runBackgroundTask {
                                runCatching { request.clear(this@MainActivity) }.onFailure {
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity, "无法清除下载请求", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                updateStickerList()
                            }
                            val hasFailures = timedOut || (status != null && status.failed > 0)
                            val message = if (timedOut) {
                                "Telegram 暂未响应，请保持 Telegram 客户端与代理在前台或后台正常运行后重试。"
                            } else {
                                "成功 ${status!!.finished - status.failed} 张，失败 ${status.failed} 张。" +
                                    if (status.message.isEmpty()) "" else "\n${status.message}"
                            }
                            if (!isFinishing && !isDestroyed) {
                                val builder = MaterialAlertDialogBuilder(this@MainActivity)
                                    .setTitle(if (timedOut) "下载超时" else "下载完成")
                                    .setMessage(message)
                                    .setPositiveButton("确定", null)
                                if (hasFailures) {
                                    builder.setNeutralButton("重试") { _, _ ->
                                        submitDownloadRequest(request.packIds)
                                    }
                                }
                                builder.show()
                            }
                        } else {
                            mainHandler.postDelayed(self, 500)
                        }
                    }
                }
            }
        }
        stopDownloadPolling()
        activeDownloadPoll = poll
        mainHandler.post(poll)
    }

    /**
     * 读取 ignore.txt 中的隐藏表情包 ID 集合（仅在后台线程调用）
     */
    private fun readIgnoredIds(): LinkedHashSet<String> {
        val result = linkedSetOf<String>()
        runCatching {
            if (ignoreFile.isFile) {
                ignoreFile.useLines { lines ->
                    lines.map { it.substringBefore('#').trim() }
                        .filter(String::isNotEmpty)
                        .forEach(result::add)
                }
            }
        }.onFailure { YLog.warn("Could not read ignore.txt", it) }
        return result
    }

    /**
     * 按当前隐藏集合过滤全量列表并即时更新界面
     */
    private fun applyIgnoredFilter() {
        val snapshot = synchronized(ignoreWriteLock) { ignoredIds.toSet() }
        val sorted = allStickerPacks.filterNot { it.id in snapshot }
        if (Looper.myLooper() == mainLooper) stickerList.value = sorted
        else stickerList.postValue(sorted)
    }

    /**
     * 隐藏指定表情包：先即时更新内存列表与界面，再经单线程执行器串行持久化 ignore.txt
     */
    internal fun hideStickerPack(pack: StickerInfo) {
        val cleanName = pack.name.replace('\n', ' ').trim()
        synchronized(ignoreWriteLock) {
            ignoredIds.add(pack.id)
        }
        applyIgnoredFilter()
        Snackbar.make(binding.manage, "已隐藏「$cleanName」", Snackbar.LENGTH_LONG)
            .setAction("撤销") {
                unhideStickerIds(setOf(pack.id))
            }
            .show()

        if (!ignoreIoExecutor.isShutdown) {
            ignoreIoExecutor.execute {
                synchronized(ignoreWriteLock) {
                    runCatching {
                        ignoreFile.parentFile?.mkdirs()
                        val existing = readIgnoredIds()
                        if (pack.id !in existing) {
                            val prefix = if (ignoreFile.isFile && ignoreFile.length() > 0 &&
                                !ignoreFile.readText().endsWith('\n')
                            ) "\n" else ""
                            ignoreFile.appendText("$prefix${pack.id}\n")
                        }
                    }.onFailure { YLog.warn("Could not write ignore.txt", it) }
                }
            }
        }
    }

    /**
     * 恢复指定表情包显示：先即时更新界面，再经单线程执行器串行写回 ignore.txt
     */
    private fun unhideStickerIds(idsToRestore: Set<String>) {
        if (idsToRestore.isEmpty()) return
        synchronized(ignoreWriteLock) {
            ignoredIds.removeAll(idsToRestore)
        }
        applyIgnoredFilter()

        if (!ignoreIoExecutor.isShutdown) {
            ignoreIoExecutor.execute {
                synchronized(ignoreWriteLock) {
                    runCatching {
                        if (ignoreFile.isFile) {
                            val remaining = ignoreFile.readLines().filterNot { line ->
                                line.substringBefore('#').trim() in idsToRestore
                            }
                            if (remaining.isEmpty()) ignoreFile.delete()
                            else ignoreFile.writeText(remaining.joinToString("\n") + "\n")
                        }
                    }.onFailure { YLog.warn("Could not update ignore.txt", it) }
                }
            }
        }
    }

    /**
     * 在后台线程读取已隐藏列表后弹出管理窗口，支持勾选恢复或全部恢复
     */
    private fun showHiddenPacksDialog() {
        if (ignoreIoExecutor.isShutdown) return
        ignoreIoExecutor.execute {
            val hiddenIds = synchronized(ignoreWriteLock) {
                if (ignoredIds.isEmpty()) ignoredIds.addAll(readIgnoredIds())
                ignoredIds.toList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (hiddenIds.isEmpty()) {
                    Toast.makeText(this, "暂无已隐藏的表情包", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val checked = BooleanArray(hiddenIds.size) { false }
                val labels = hiddenIds.map { id ->
                    val title = packTitleMap[id]
                    if (title.isNullOrBlank()) id else "$title ($id)"
                }.toTypedArray()

                MaterialAlertDialogBuilder(this)
                    .setTitle("已隐藏的表情包（${hiddenIds.size}）")
                    .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                        checked[index] = isChecked
                    }
                    .setNeutralButton("全部恢复") { _, _ ->
                        unhideStickerIds(hiddenIds.toSet())
                        Toast.makeText(this, "已恢复全部隐藏的表情包", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .setPositiveButton("恢复所选") { _, _ ->
                        val toRestore = hiddenIds.filterIndexed { index, _ -> checked[index] }.toSet()
                        if (toRestore.isNotEmpty()) {
                            unhideStickerIds(toRestore)
                            Toast.makeText(this, "已恢复 ${toRestore.size} 个表情包", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .show()
            }
        }
    }

    private fun visiblePacks(list: List<StickerInfo> = stickerList.value.orEmpty()): List<StickerInfo> =
        list.filter {
            when (it.category) {
                StickerCategory.MASK -> if (!showMasks) return@filter false
                StickerCategory.EMOJI -> if (!showEmoji) return@filter false
                StickerCategory.REGULAR -> Unit
            }
            if (selectedFormat != FORMAT_ALL && it.type != selectedFormat) return@filter false
            if (requiresSyncOnly && !it.isPendingFor(useHighQualityGif)) return@filter false
            filter.isEmpty() ||
                it.name.contains(filter, ignoreCase = true) ||
                it.id.contains(filter, ignoreCase = true) ||
                it.type.contains(filter, ignoreCase = true)
        }

    private fun onFilterChanged(list: List<StickerInfo> = stickerList.value.orEmpty()) {
        val visible = visiblePacks(list)
        stickerAdapter.submitList(visible, useHighQualityGif)

        binding.textView.text = "${getString(R.string.sticker_list)} · ${visible.size}"
        val pendingCount = visible.count { it.isPendingFor(useHighQualityGif) }
        binding.listSummaryText.text = when {
            visible.isEmpty() -> ""
            pendingCount > 0 -> "待同步 $pendingCount"
            else -> "已全部同步"
        }
        binding.emptyStateView.isVisible = visible.isEmpty()
        binding.stickerManageView.isVisible = visible.isNotEmpty()
    }

    private fun updateCategoryChipLabel() {
        val extras = buildList {
            if (showMasks) add("面具")
            if (showEmoji) add("Emoji")
        }
        binding.stickerCategoriesChip.text = if (extras.isEmpty()) {
            getString(R.string.sticker_categories)
        } else {
            "类别 +${extras.joinToString("/")}"
        }
    }

    private fun applyManageMode(manage: Boolean) {
        inManageMode = manage
        binding.tips.visibility = if (manage) View.GONE else View.VISIBLE
        binding.manage.visibility = if (manage) View.VISIBLE else View.GONE
        updateToolbarMenuVisibility(manage)
    }

    private fun updateToolbarMenuVisibility(inManageMode: Boolean) {
        val menu = binding.topAppBar.menu
        menu.findItem(R.id.action_refresh)?.isVisible = inManageMode
        menu.findItem(R.id.action_help)?.isVisible = inManageMode
        menu.findItem(R.id.action_hidden_packs)?.isVisible = inManageMode
        menu.findItem(R.id.action_clear_cache)?.isVisible = inManageMode
    }

    private fun performRefreshList() {
        val progress = MaterialProgressDialog(this, "正在刷新", "正在读取贴纸列表...").show()
        runBackgroundTask {
            updateStickerList()
            runOnUiThread {
                progress.dismiss()
                Toast.makeText(this, "共 ${visiblePacks().size} 个表情包", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showClearCacheDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清除导出缓存")
            .setMessage("清除全部导出缓存？不会影响已同步的表情包。")
            .setPositiveButton("清除") { _, _ ->
                val progress = MaterialProgressDialog(this, "正在清除", "正在清理缓存文件...").show()
                runBackgroundTask {
                    File(exposedPath).deleteRecursively()
                    allStickerPacks = emptyList()
                    synchronized(ignoreWriteLock) { ignoredIds.clear() }
                    stickerList.postValue(emptyList())
                    runOnUiThread { progress.dismiss() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showUsageDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.usage_title)
            .setMessage("${getString(R.string.usage)}\n\n${getString(R.string.big_tips)}")
            .setPositiveButton("知道了", null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_IN_MANAGE_MODE, inManageMode)
        outState.putString(STATE_FILTER, filter)
        outState.putBoolean(STATE_REQUIRES_SYNC_ONLY, requiresSyncOnly)
        outState.putString(STATE_SELECTED_FORMAT, selectedFormat)
    }

    override fun onCreate() {
        refreshModuleStatus()
        // 恢复持久化偏好设置
        showMasks = preferences.getBoolean(PREF_SHOW_MASKS, false)
        showEmoji = preferences.getBoolean(PREF_SHOW_EMOJI, false)
        useHighQualityGif = preferences.getBoolean(PREF_HIGH_QUALITY_GIF, false)

        // 恢复配置变更状态，或冷启动时在已激活且读过引导页后直达管理页
        val saved = restoredInstanceState
        if (saved != null) {
            inManageMode = saved.getBoolean(STATE_IN_MANAGE_MODE, false)
            filter = saved.getString(STATE_FILTER, "").orEmpty()
            requiresSyncOnly = saved.getBoolean(STATE_REQUIRES_SYNC_ONLY, false)
            selectedFormat = saved.getString(STATE_SELECTED_FORMAT, FORMAT_ALL).orEmpty()
        } else {
            val hasReadGuide = preferences.getBoolean(PREF_HAS_READ_GUIDE, false)
            inManageMode = YukiHookAPI.Status.isModuleActive && hasReadGuide
        }

        stickerAdapter = RecyclerAdapterStickerList(this, tgsPreviewExecutor)
        binding.stickerManageView.layoutManager = LinearLayoutManager(this)
        binding.stickerManageView.adapter = stickerAdapter

        // 同步控件初始状态后再绑定监听器
        updateCategoryChipLabel()
        binding.requiresSyncOnlyChip.isChecked = requiresSyncOnly
        binding.gifQualityChip.isChecked = useHighQualityGif
        val formatChipId = when (selectedFormat) {
            FORMAT_WEBP -> R.id.formatWebpChip
            FORMAT_WEBM -> R.id.formatWebmChip
            FORMAT_TGS -> R.id.formatTgsChip
            else -> R.id.formatAllChip
        }
        binding.formatFilterGroup.check(formatChipId)
        if (filter.isNotEmpty()) {
            binding.searchBar.setText(filter)
        }
        applyManageMode(inManageMode)

        cleanupStaleTempDirsAsync()
        requestPermission()
        binding.mainTextVersion.text = getString(R.string.module_version, BuildConfig.VERSION_NAME)

        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    performRefreshList()
                    true
                }
                R.id.action_help -> {
                    showUsageDialog()
                    true
                }
                R.id.action_hidden_packs -> {
                    showHiddenPacksDialog()
                    true
                }
                R.id.action_clear_cache -> {
                    showClearCacheDialog()
                    true
                }
                else -> false
            }
        }

        binding.button2.setOnClickListener {
            preferences.edit().putBoolean(PREF_HAS_READ_GUIDE, true).apply()
            applyManageMode(true)
            binding.searchBar.clearFocus()
            onFilterChanged()
        }

        binding.requiresSyncOnlyChip.setOnCheckedChangeListener { _, isChecked ->
            requiresSyncOnly = isChecked
            onFilterChanged()
        }
        binding.gifQualityChip.setOnCheckedChangeListener { _, isChecked ->
            useHighQualityGif = isChecked
            preferences.edit().putBoolean(PREF_HIGH_QUALITY_GIF, isChecked).apply()
            onFilterChanged()
        }
        binding.formatFilterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.formatAllChip
            selectedFormat = when (checkedId) {
                R.id.formatWebpChip -> FORMAT_WEBP
                R.id.formatWebmChip -> FORMAT_WEBM
                R.id.formatTgsChip -> FORMAT_TGS
                else -> FORMAT_ALL
            }
            onFilterChanged()
        }

        binding.syncAllBtn.setOnClickListener { syncStickerPacks(visiblePacks()) }
        binding.downloadOriginalsBtn.setOnClickListener { offerOriginalDownloads(visiblePacks()) }
        binding.stickerCategoriesChip.setOnClickListener {
            val selected = booleanArrayOf(showMasks, showEmoji)
            MaterialAlertDialogBuilder(this)
                .setTitle("贴纸类别")
                .setMultiChoiceItems(arrayOf("面具", "自定义 Emoji"), selected) { _, index, checked ->
                    selected[index] = checked
                }
                .setNegativeButton("取消", null)
                .setPositiveButton("确定") { _, _ ->
                    showMasks = selected[0]
                    showEmoji = selected[1]
                    preferences.edit()
                        .putBoolean(PREF_SHOW_MASKS, showMasks)
                        .putBoolean(PREF_SHOW_EMOJI, showEmoji)
                        .apply()
                    updateCategoryChipLabel()
                    onFilterChanged()
                }
                .show()
        }

        // 搜索输入增加 200ms 防抖，减少快速键入时的频繁过滤计算
        binding.searchBar.doOnTextChanged { text, _, _, _ ->
            val nextQuery = text?.toString().orEmpty()
            searchDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
            val task = Runnable {
                filter = nextQuery
                onFilterChanged()
            }
            searchDebounceRunnable = task
            mainHandler.postDelayed(task, 200L)
        }

        binding.stickerManageView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE || newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    Glide.with(this@MainActivity).resumeRequests()
                } else {
                    Glide.with(this@MainActivity).pauseRequests()
                }
                super.onScrollStateChanged(recyclerView, newState)
            }
        })

        stickerList.observe(this) { list ->
            onFilterChanged(list)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshModuleStatus()
        observeDownload()
    }

    override fun onDestroy() {
        // 清理全部主线程延迟回调与防抖任务
        stopDownloadPolling()
        mainHandler.removeCallbacksAndMessages(null)
        searchDebounceRunnable = null

        // 关闭存活弹窗并取消活跃同步任务
        downloadDialog?.dismiss()
        downloadDialog = null
        activeSyncDialog?.dismiss()
        activeSyncDialog = null
        if (isSyncing.get()) {
            syncCancelled.set(true)
            cancelStickerConversions()
            activeSyncExecutor?.shutdownNow()
        }

        // 释放线程池与预览缓存，ignoreIoExecutor 使用温和 shutdown 以确保排队写入落盘
        ignoreIoExecutor.shutdown()
        ioExecutor.shutdownNow()
        tgsPreviewExecutor.shutdownNow()
        if (::stickerAdapter.isInitialized) {
            stickerAdapter.release()
        }
        super.onDestroy()
    }

    private val stickerList by lazy {
        MutableLiveData<List<StickerInfo>>(emptyList())
    }

    fun updateStickerList() {
        val rows = runCatching {
            if (catalogFile.isFile) readCatalog().map { pack ->
                val formats = pack.documents.map { it.mime }
                val type = formats.firstOrNull { it == "image/webp" || it == "video/webm" || it == TGS_MIME }
                    ?: formats.firstOrNull() ?: "unknown"
                stickerInfo(pack.shortName, pack.title, pack.id.toString(), pack.count, type, pack, pack.category)
            } else File(stickerDataPath).listFiles().orEmpty().mapNotNull { file ->
                if (!file.name.endsWith(".stickerData.txt.jpg")) return@mapNotNull null
                runCatching {
                    val lines = file.readLines()
                    require(stickerShortName.matches(lines[0]))
                    val category = if (lines[0].matches(Regex("masks[1-6]"))) StickerCategory.MASK
                    else StickerCategory.REGULAR
                    stickerInfo(
                        lines[0], lines[1], file.name, lines[2].toInt(),
                        lines.getOrElse(3) { "unknown" }, null, category
                    )
                }.getOrNull()
            }.distinctBy { it.id }
        }.onFailure { YLog.error("Could not read sticker list", it) }.getOrNull() ?: return

        packTitleMap = rows.associate { it.id to it.name.replace('\n', ' ').trim() }
        allStickerPacks = rows.sortedByDescending { it.remoteState.all }
        synchronized(ignoreWriteLock) {
            ignoredIds.clear()
            ignoredIds.addAll(readIgnoredIds())
        }
        applyIgnoredFilter()
    }

    private fun stickerInfo(
        shortName: String, title: String, key: String, count: Int, type: String,
        pack: StickerPack?, category: StickerCategory
    ): StickerInfo {
        val documents = pack?.documents?.associateBy { it.id.toString() }
        val cached = stickerFiles(File(destDataPath, "tgSync_$shortName"), documents)
        val synced = stickerFiles(File(realDataPath, "tgSync_$shortName"), documents, synced = true)
        val preview = selectPreviewFile(type, cached.values)
        val unsupported = pack?.documents?.count { it.extension == null }
            ?: if (type in setOf("image/webp", "video/webm", TGS_MIME, "unknown")) 0 else count
        return StickerInfo(
            name = title,
            id = shortName,
            hash = key,
            remoteState = stateOf(cached),
            syncedState = stateOf(synced),
            all = count,
            type = type,
            preview = PreviewInfo(
                type = preview?.extension.orEmpty(),
                url = preview?.absolutePath.orEmpty(),
                version = preview?.lastModified() ?: 0L
            ),
            pack = pack,
            category = category,
            unsupportedCount = unsupported,
            syncedHighQualityGif = readSyncedQualityRecord(shortName)
        )
    }

    /**
     * 按主格式优先选择可渲染的预览文件，支持 WEBP、WEBM 与 TGS
     */
    private fun selectPreviewFile(type: String, files: Collection<File>): File? {
        val preferredOrder = when (type) {
            "video/webm" -> listOf("webm", "webp", "tgs")
            TGS_MIME -> listOf("tgs", "webp", "webm")
            else -> listOf("webp", "tgs", "webm")
        }
        for (ext in preferredOrder) {
            files.firstOrNull { it.extension == ext }?.let { return it }
        }
        return null
    }

    /**
     * 刷新模块激活状态与顶部副标题
     */
    private fun refreshModuleStatus() {
        val active = YukiHookAPI.Status.isModuleActive
        val contentColor = ContextCompat.getColor(
            this,
            if (active) R.color.colorOnSuccessContainer else R.color.colorOnWarningContainer
        )
        binding.mainLinStatus.setBackgroundResource(
            if (active) R.drawable.bg_status_active else R.drawable.bg_status_inactive
        )
        binding.mainImgStatus.setImageResource(
            if (active) R.mipmap.ic_success else R.mipmap.ic_warn
        )
        binding.mainImgStatus.imageTintList = ColorStateList.valueOf(contentColor)
        binding.mainTextStatus.setTextColor(contentColor)
        binding.mainTextVersion.setTextColor(contentColor)
        binding.mainTextInfo.setTextColor(contentColor)
        binding.mainTextApiWay.setTextColor(contentColor)

        val statusLabel = getString(
            if (active) R.string.module_is_activated else R.string.module_not_activated
        )
        binding.mainTextStatus.text = statusLabel
        binding.topAppBar.subtitle = "$statusLabel · v${BuildConfig.VERSION_NAME}"
    }
}

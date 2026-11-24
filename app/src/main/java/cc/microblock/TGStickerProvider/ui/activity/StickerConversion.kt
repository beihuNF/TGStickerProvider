package cc.microblock.TGStickerProvider.ui.activity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PorterDuff
import cc.microblock.TGStickerProvider.writeAtomically
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.RenderMode
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.zip.GZIPInputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val TEMP_DIR_PREFIX = "sticker-"
private val LIBVPX_VP9_REGEX = Regex("""\blibvpx-vp9\b""")

// 缓存运行期对 libvpx-vp9 解码器的探测结果，避免重复调用 FFmpeg
private val supportsLibvpxVp9Decoder: Boolean by lazy { detectLibvpxVp9Decoder() }

internal fun convertSticker(
    source: File,
    destination: File,
    highQualityGif: Boolean,
    tempDirectory: File
) = convertSticker(source, destination, highQualityGif, tempDirectory, context = null)

internal fun convertSticker(
    source: File,
    destination: File,
    highQualityGif: Boolean,
    tempDirectory: File,
    context: Context?
) {
    writeAtomically(destination) { temp ->
        when (source.extension) {
            "webp" -> convertWebpToPng(source, temp)
            "webm" -> {
                val args = buildWebmGifArgs(source.absolutePath, highQualityGif, supportsLibvpxVp9Decoder)
                encodeGif(temp, *args)
            }
            "tgs" -> renderTgs(source, temp, highQualityGif, tempDirectory, context)
            else -> error("不支持的格式 ${source.extension}")
        }
        check(temp.length() > 0) { "转换结果为空" }
    }
}

/**
 * 清理缓存目录中残留的贴纸转换临时目录。
 */
internal fun cleanupConversionTempDirs(cacheDir: File) {
    val entries = cacheDir.listFiles() ?: return
    for (entry in entries) {
        if (entry.name.startsWith(TEMP_DIR_PREFIX)) {
            runCatching { entry.deleteRecursively() }
        }
    }
}

/**
 * 取消当前正在进行的 FFmpeg 贴纸转换任务。
 */
internal fun cancelStickerConversions() {
    FFmpegKit.cancel()
}

private fun convertWebpToPng(source: File, destination: File) {
    // 强制使用软件分配器解码，避免部分 ROM 对硬件位图直接压缩 PNG 失败
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    try {
        destination.outputStream().buffered().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG 转换失败" }
        }
    } finally {
        bitmap.recycle()
    }
}

internal fun hasLibvpxVp9Decoder(decodersOutput: String?): Boolean {
    if (decodersOutput.isNullOrBlank()) return false
    return LIBVPX_VP9_REGEX.containsMatchIn(decodersOutput)
}

private fun detectLibvpxVp9Decoder(): Boolean = runCatching {
    val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-decoders"))
    session.returnCode?.isValueSuccess == true && hasLibvpxVp9Decoder(session.allLogsAsString)
}.getOrDefault(false)

/**
 * 构建 WebM 转 GIF 的 FFmpeg 参数，按画质模式应用透明调色板与尺寸帧率上限。
 */
internal fun buildWebmGifArgs(
    sourcePath: String,
    highQuality: Boolean,
    useLibvpxVp9: Boolean
): Array<String> {
    val filter = if (highQuality) {
        "fps=20,scale=320:-1:flags=lanczos," +
            "split[frames][palette];" +
            "[palette]palettegen=reserve_transparent=1:stats_mode=diff[p];" +
            "[frames][p]paletteuse=diff_mode=rectangle"
    } else {
        "fps=12,scale=256:-1:flags=lanczos," +
            "split[frames][palette];" +
            "[palette]palettegen=reserve_transparent=1:stats_mode=diff[p];" +
            "[frames][p]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle"
    }
    return buildList {
        if (useLibvpxVp9) {
            // 显式指定 libvpx-vp9 解码器以保留 VP9 Alpha 透明通道
            add("-c:v")
            add("libvpx-vp9")
        }
        add("-i")
        add(sourcePath)
        add("-filter_complex")
        add(filter)
    }.toTypedArray()
}

/**
 * 构建 raw RGBA 帧流转 GIF 的 FFmpeg 参数。
 */
internal fun buildRawVideoGifArgs(
    inputPath: String,
    width: Int,
    height: Int,
    fps: Int,
    highQuality: Boolean
): Array<String> {
    val filter = if (highQuality) {
        "split[frames][palette];" +
            "[palette]palettegen=reserve_transparent=1:stats_mode=diff[p];" +
            "[frames][p]paletteuse=diff_mode=rectangle"
    } else {
        "split[frames][palette];" +
            "[palette]palettegen=reserve_transparent=1:stats_mode=diff[p];" +
            "[frames][p]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle"
    }
    return arrayOf(
        "-f", "rawvideo",
        "-pix_fmt", "rgba",
        "-s", "${width}x${height}",
        "-r", fps.toString(),
        "-i", inputPath,
        "-filter_complex", filter
    )
}

/**
 * 按起始帧与总帧跨度计算指定输出帧对应的归一化进度。
 */
internal fun tgsFrameProgress(
    frameIndex: Int,
    totalFrames: Int,
    startFrame: Float,
    durationFrames: Float,
    endFrame: Float
): Float {
    val span = endFrame - startFrame
    if (totalFrames <= 1 || durationFrames <= 0f || span <= 0f) return 0f
    val targetFrame = startFrame + (durationFrames * frameIndex) / totalFrames
    return ((targetFrame - startFrame) / span).coerceIn(0f, 1f)
}

private fun renderTgs(
    source: File,
    destination: File,
    highQuality: Boolean,
    tempDirectory: File,
    context: Context?
) {
    val composition = source.inputStream().buffered().use { input ->
        input.mark(2)
        val gzip = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        // Telegram 可能在 .tgs 扩展名下直接存储解压后的 JSON
        (if (gzip) GZIPInputStream(input) else input).use {
            val result = LottieCompositionFactory.fromJsonInputStreamSync(it, null)
            result.value ?: throw IllegalArgumentException("无法读取 TGS 动画", result.exception)
        }
    }
    require(composition.duration in 1f..10_000f) { "TGS 动画时长异常" }
    val fps = if (highQuality) 30 else 20
    val size = if (highQuality) 512 else 256
    val bounds = composition.bounds
    val scale = size.toFloat() / maxOf(bounds.width(), bounds.height()).coerceAtLeast(1)
    val width = (bounds.width() * scale).roundToInt().coerceAtLeast(1)
    val height = (bounds.height() * scale).roundToInt().coerceAtLeast(1)
    val frames = ceil(composition.duration / 1000f * fps).toInt().coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val drawable = LottieDrawable().apply {
        renderMode = RenderMode.SOFTWARE
        enableMergePathsForKitKatAndAbove(true)
        setComposition(composition)
        setBounds(0, 0, width, height)
    }
    try {
        val pipePath = context?.let { runCatching { FFmpegKitConfig.registerNewFFmpegPipe(it) }.getOrNull() }
        if (pipePath != null) {
            renderTgsViaPipe(composition, drawable, bitmap, frames, fps, highQuality, pipePath, destination)
        } else {
            renderTgsViaTempRawFile(composition, drawable, bitmap, frames, fps, highQuality, tempDirectory, destination)
        }
    } finally {
        drawable.clearComposition()
        bitmap.recycle()
    }
}

/**
 * 通过 FFmpeg 命名管道直接写入 raw RGBA 帧流进行编码。
 */
private fun renderTgsViaPipe(
    composition: LottieComposition,
    drawable: LottieDrawable,
    bitmap: Bitmap,
    frames: Int,
    fps: Int,
    highQuality: Boolean,
    pipePath: String,
    destination: File
) {
    val args = buildGifCommand(
        destination,
        *buildRawVideoGifArgs(pipePath, bitmap.width, bitmap.height, fps, highQuality)
    )
    val latch = CountDownLatch(1)
    var completedSession: FFmpegSession? = null
    // 先异步启动 FFmpeg 打开管道读端，避免写端打开命名管道时阻塞
    val session = FFmpegKit.executeWithArgumentsAsync(args) { finished ->
        completedSession = finished
        latch.countDown()
    }
    try {
        FileOutputStream(pipePath).buffered().use { output ->
            writeLottieRgbaFrames(composition, drawable, bitmap, frames, output)
        }
        latch.await()
        val result = completedSession ?: session
        check(result.returnCode?.isValueSuccess == true) { "GIF 转换失败（${result.returnCode}）" }
    } catch (t: Throwable) {
        FFmpegKit.cancel(session.sessionId)
        throw t
    } finally {
        FFmpegKitConfig.closeFFmpegPipe(pipePath)
    }
}

/**
 * 无 Context 或管道不可用时，回退为写入单个 raw RGBA 临时文件再由 FFmpeg 编码。
 */
private fun renderTgsViaTempRawFile(
    composition: LottieComposition,
    drawable: LottieDrawable,
    bitmap: Bitmap,
    frames: Int,
    fps: Int,
    highQuality: Boolean,
    tempDirectory: File,
    destination: File
) {
    val directory = Files.createTempDirectory(tempDirectory.toPath(), TEMP_DIR_PREFIX).toFile()
    try {
        val rawFile = File(directory, "frames.rgba")
        rawFile.outputStream().buffered().use { output ->
            writeLottieRgbaFrames(composition, drawable, bitmap, frames, output)
        }
        encodeGif(
            destination,
            *buildRawVideoGifArgs(rawFile.absolutePath, bitmap.width, bitmap.height, fps, highQuality)
        )
    } finally {
        directory.deleteRecursively()
    }
}

private fun writeLottieRgbaFrames(
    composition: LottieComposition,
    drawable: LottieDrawable,
    bitmap: Bitmap,
    frames: Int,
    output: OutputStream
) {
    val canvas = Canvas(bitmap)
    val startFrame = composition.startFrame
    val durationFrames = composition.durationFrames
    val endFrame = composition.endFrame
    val pixelBuffer = ByteBuffer.allocate(bitmap.width * bitmap.height * 4)
    val pixelBytes = pixelBuffer.array()
    repeat(frames) { frame ->
        drawable.progress = tgsFrameProgress(frame, frames, startFrame, durationFrames, endFrame)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawable.draw(canvas)
        pixelBuffer.rewind()
        bitmap.copyPixelsToBuffer(pixelBuffer)
        output.write(pixelBytes)
    }
    output.flush()
}

private fun buildGifCommand(destination: File, vararg args: String): Array<String> =
    arrayOf("-y", *args, "-loop", "0", "-f", "gif", destination.absolutePath)

private fun encodeGif(destination: File, vararg args: String) {
    val result = FFmpegKit.executeWithArguments(buildGifCommand(destination, *args))
    check(result.returnCode?.isValueSuccess == true) { "GIF 转换失败（${result.returnCode}）" }
}

private val exportName = Regex("(-?[0-9]+)_(high|low)\\.(webp|webm|tgs|png|gif)")

internal fun stickerFiles(
    directory: File,
    documents: Map<String, cc.microblock.TGStickerProvider.StickerDocument>?,
    synced: Boolean = false
): Map<String, File> = buildMap {
    for (file in directory.listFiles().orEmpty()) {
        val match = exportName.matchEntire(file.name) ?: continue
        if (!file.isFile || file.length() == 0L) continue
        val (id, quality, ext) = match.destructured
        if (synced && ext !in setOf("webp", "png", "gif")) continue
        if (!synced && ext !in setOf("webp", "webm", "tgs")) continue
        if (documents != null) {
            val original = documents[id]?.extension ?: continue
            if (!synced && original != ext) continue
            if (synced && (original == "webp") != (ext == "png" || ext == "webp")) continue
        }
        if (quality == "high" || !containsKey(id)) put(id, file)
    }
}

internal fun stateOf(files: Map<String, File>): StickerState {
    val high = files.values.count { it.nameWithoutExtension.endsWith("_high") }
    return StickerState(high, files.size - high, files.size)
}

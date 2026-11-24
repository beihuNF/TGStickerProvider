package cc.microblock.TGStickerProvider

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

internal const val TGS_MIME = "application/x-tgsticker"
internal val stickerShortName = Regex("[A-Za-z0-9_]+")

internal enum class StickerCategory { REGULAR, MASK, EMOJI }

internal data class StickerDocument(val id: Long, val mime: String) {
    val extension: String?
        get() = when (mime) {
            "image/webp" -> "webp"
            "video/webm" -> "webm"
            TGS_MIME -> "tgs"
            else -> null
        }
}

internal data class StickerPack(
    val id: Long,
    val shortName: String,
    val title: String,
    val count: Int,
    val category: StickerCategory,
    val documents: List<StickerDocument>
) {
    init { require(stickerShortName.matches(shortName)) }
    val cacheDirectory: File get() = File(destDataPath, "tgSync_$shortName")
}

internal val catalogFile = File(stickerDataPath, "catalog.json.jpg")
internal val downloadDirectory = File(tgspDataPath, "downloads")
internal val downloadHost = File(downloadDirectory, "telegram-host.txt.jpg")
internal val activeHostsFile = File(downloadDirectory, "hosts.json.jpg")
internal val downloadStatus = File(downloadDirectory, "status-v2.txt.jpg")
internal val downloadAction = "${BuildConfig.APPLICATION_ID}.STICKER_DOWNLOAD"
internal val downloadPermission = "${BuildConfig.APPLICATION_ID}.STICKER_DOWNLOAD_REQUEST"

private val hostPackageRegex = Regex("[A-Za-z][A-Za-z0-9_.]+")

/**
 * 原子写入目标文件，临时文件不使用前导点并在重命名失败时自动重试或回退覆写
 */
internal fun writeAtomically(destination: File, write: (File) -> Unit) {
    val parent = requireNotNull(destination.parentFile)
    parent.mkdirs()
    val ext = destination.extension
    val tempName = if (ext.isNotEmpty()) "tmp_${UUID.randomUUID()}.$ext" else "tmp_${UUID.randomUUID()}"
    val temp = File(parent, tempName)
    try {
        write(temp)
        if (temp.renameTo(destination)) return
        // 重命名失败时先尝试清理旧目标并重试一次
        if (destination.exists()) destination.delete()
        if (temp.renameTo(destination)) return
        // 仍失败时回退为直接覆写目标文件，兼容跨挂载点或受限存储介质
        runCatching {
            temp.copyTo(destination, overwrite = true)
        }.onFailure { cause ->
            throw IOException("Cannot publish ${destination.name}", cause)
        }
        if (!destination.isFile) throw IOException("Cannot publish ${destination.name}")
    } finally {
        temp.delete()
    }
}

/**
 * 登记活跃的 Telegram 宿主包名及其最近活跃时间戳
 */
internal fun registerActiveHost(packageName: String, timestamp: Long = System.currentTimeMillis()) {
    if (!hostPackageRegex.matches(packageName)) return
    val hosts = readActiveHosts().toMutableMap()
    hosts[packageName] = timestamp
    val json = JSONObject().apply {
        for ((pkg, lastSeen) in hosts) {
            put(pkg, lastSeen)
        }
    }
    writeAtomically(activeHostsFile) { it.writeText(json.toString()) }
}

/**
 * 读取已登记的活跃宿主映射（包名 -> 最近活跃时间戳）
 */
internal fun readActiveHosts(): Map<String, Long> = runCatching {
    if (!activeHostsFile.isFile) return emptyMap()
    val obj = JSONObject(activeHostsFile.readText())
    buildMap {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val pkg = keys.next()
            if (hostPackageRegex.matches(pkg)) {
                put(pkg, obj.optLong(pkg, 0L))
            }
        }
    }
}.getOrDefault(emptyMap())

/**
 * 手动指定首选贴纸下载宿主包名
 */
internal fun selectDownloadHost(packageName: String) {
    val trimmed = packageName.trim()
    require(hostPackageRegex.matches(trimmed)) { "Invalid host package name: $packageName" }
    writeAtomically(downloadHost) { it.writeText(trimmed) }
}

internal fun encodeCatalog(packs: Collection<StickerPack>): String = JSONArray().apply {
    for (pack in packs) {
        put(JSONObject().apply {
            put("id", pack.id)
            put("name", pack.shortName)
            put("title", pack.title)
            put("count", pack.count)
            put("category", pack.category.name)
            put("documents", JSONArray().apply {
                for (document in pack.documents) put(JSONArray().put(document.id).put(document.mime))
            })
        })
    }
}.toString()

internal fun readCatalog(): List<StickerPack> {
    val rows = JSONArray(catalogFile.readText())
    return (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        val documents = row.getJSONArray("documents")
        StickerPack(
            row.getLong("id"),
            row.getString("name"),
            row.getString("title"),
            row.getInt("count"),
            StickerCategory.valueOf(row.getString("category")),
            (0 until documents.length()).map { i ->
                val document = documents.getJSONArray(i)
                StickerDocument(document.getLong(0), document.getString(1))
            }
        )
    }
}

internal data class DownloadRequest(val token: String, val packIds: Set<Long>) {
    fun submit(context: Context) {
        val host = downloadHost.readText().trim()
        require(host.matches(Regex("[A-Za-z][A-Za-z0-9_.]+")))
        require(packIds.size in 1..8192)
        val payload = (listOf(host, token) + packIds.map(Long::toString)).joinToString("\n")
        writeAtomically(stateFile(context)) { it.writeText(payload) }
        send(context, host, token, packIds.toLongArray())
    }

    fun resend(context: Context) {
        val host = stateFile(context).bufferedReader().use { it.readLine() }
        send(context, host, token, packIds.toLongArray())
    }

    fun clear(context: Context) {
        if (read(context)?.token != token) return
        val local = stateFile(context)
        val host = local.bufferedReader().use { it.readLine() }
        check(local.delete())
        send(context, host, token, longArrayOf())
    }

    companion object {
        private fun stateFile(context: Context) = File(context.filesDir, "sticker-download.txt")

        fun read(context: Context): DownloadRequest? = runCatching {
            val lines = stateFile(context).readLines()
            val token = UUID.fromString(lines[1]).toString()
            DownloadRequest(token, lines.drop(2).map(String::toLong).toSet())
        }.getOrNull()

        private fun send(context: Context, host: String, token: String, ids: LongArray) {
            context.sendBroadcast(Intent(downloadAction).setPackage(host)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("token", token).putExtra("packIds", ids))
        }
    }
}

internal data class DownloadProgress(
    val token: String,
    val total: Int,
    val finished: Int,
    val failed: Int,
    val done: Boolean,
    val message: String = ""
) {
    fun publish() = writeAtomically(downloadStatus) {
        it.writeText(listOf(token, total, finished, failed, done, message).joinToString("\n"))
    }

    companion object {
        fun read(token: String): DownloadProgress? = runCatching {
            val lines = downloadStatus.readLines()
            if (lines.firstOrNull() != token) return null
            DownloadProgress(token, lines[1].toInt(), lines[2].toInt(), lines[3].toInt(),
                lines[4].toBooleanStrict(), lines.getOrElse(5) { "" })
        }.getOrNull()
    }
}

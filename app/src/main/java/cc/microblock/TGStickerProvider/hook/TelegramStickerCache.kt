package cc.microblock.TGStickerProvider.hook

import android.database.sqlite.SQLiteDatabase
import cc.microblock.TGStickerProvider.StickerCategory
import cc.microblock.TGStickerProvider.StickerDocument
import cc.microblock.TGStickerProvider.StickerPack
import cc.microblock.TGStickerProvider.stickerShortName
import cc.microblock.TGStickerProvider.hook.TelegramTLParser.SerializedData
import cc.microblock.TGStickerProvider.hook.TelegramTLParser.TLRPC
import com.highcapable.yukihookapi.hook.log.YLog
import java.io.File

internal data class CachedStickerPack(val pack: StickerPack, val dcIds: Map<Long, Int>)

internal class TelegramStickerCache(private val files: File) {
    private data class RowKey(val database: File, val id: Int)
    private data class Revision(val date: Long, val hash: Long, val size: Int)
    private data class Row(val revision: Revision?, val date: Long, val packs: List<CachedStickerPack>)

    private val cachedRows = mutableMapOf<RowKey, Row>()

    fun read(): List<CachedStickerPack>? {
        val databases = (listOf(files) + files.listFiles().orEmpty().filter {
            it.isDirectory && it.name.matches(Regex("account[0-9]+"))
        }).map { File(it, "cache4.db") }.filter(File::isFile)
        if (databases.isEmpty()) return null
        cachedRows.keys.removeAll { it.database !in databases }

        var readDatabase = false
        for (file in databases) {
            try {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    // 设置 SQLite 忙等待超时，缓解与宿主 WAL 写入的并发锁冲突
                    db.rawQuery("PRAGMA busy_timeout = 3000", null).use { it.moveToFirst() }
                    val presentIds = mutableSetOf<Int>()
                    db.rawQuery("SELECT id,date,hash,length(data) FROM stickers_v2 WHERE id IN (1,2,5,6)", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getInt(0)
                            presentIds.add(id)
                            val key = RowKey(file, id)
                            val revision = Revision(cursor.getLong(1), cursor.getLong(2), cursor.getInt(3))
                            if (cachedRows[key]?.revision == revision) continue
                            db.rawQuery("SELECT data FROM stickers_v2 WHERE id = ?", arrayOf(id.toString())).use { row ->
                                if (row.moveToFirst()) {
                                    val before = cachedRows[key]
                                    val parsed = parse(row.getBlob(0), id)
                                    // 完整解析成功时才更新 revision；部分失败时保留原缓存与原 revision 以便下次重试
                                    if (parsed.first) {
                                        cachedRows[key] = Row(revision, revision.date, parsed.second)
                                    } else if (before == null && parsed.second.isNotEmpty()) {
                                        cachedRows[key] = Row(null, revision.date, parsed.second)
                                    }
                                }
                            }
                        }
                    }
                    cachedRows.keys.removeAll { it.database == file && it.id !in presentIds }
                    readDatabase = true
                }
            } catch (e: Exception) {
                YLog.warn("Could not read Telegram sticker cache", e)
            }
        }
        if (!readDatabase && cachedRows.isEmpty()) return null
        return cachedRows.values.sortedByDescending { it.date }
            .flatMap { it.packs }.distinctBy { it.pack.id }
    }

    private fun parse(bytes: ByteArray, rowId: Int): Pair<Boolean, List<CachedStickerPack>> {
        val packs = mutableListOf<CachedStickerPack>()
        return try {
            val stream = SerializedData(bytes)
            val first = stream.readInt32(true)
            val legacy = TLRPC.TL_messages_stickerSet.isStickerSetConstructor(first)
            val count = if (legacy) stream.readInt32(true) else first
            require(count in 0..(bytes.size / 4))
            repeat(count) { index ->
                val constructor = if (legacy && index == 0) first else stream.readInt32(true)
                require(TLRPC.TL_messages_stickerSet.isStickerSetConstructor(constructor))
                val set = TLRPC.TL_messages_stickerSet.TLdeserialize(stream, constructor, true)
                // 安全跳过未变更或缺少元数据的贴纸集条目，不中断整行解析
                if (set is TLRPC.TL_messages_stickerSetNotModified || set.set == null) return@repeat
                val metadata = set.set
                val shortName = metadata.short_name.orEmpty()
                if (stickerShortName.matches(shortName)) {
                    val category = when {
                        metadata.masks || rowId == 2 -> StickerCategory.MASK
                        metadata.emojis || rowId == 5 || rowId == 6 -> StickerCategory.EMOJI
                        else -> StickerCategory.REGULAR
                    }
                    val docs = set.documents.orEmpty().filterNotNull().distinctBy { it.id }
                    packs += CachedStickerPack(
                        StickerPack(metadata.id, shortName, metadata.title.orEmpty(),
                            maxOf(metadata.count, docs.size), category,
                            docs.map { StickerDocument(it.id, it.mime_type.orEmpty()) }),
                        docs.associate { it.id to it.dc_id }
                    )
                }
            }
            true to packs
        } catch (e: Exception) {
            YLog.warn("Could not finish parsing sticker cache row $rowId", e)
            false to packs
        }
    }
}

package cc.microblock.TGStickerProvider.ui.activity

import cc.microblock.TGStickerProvider.StickerDocument
import cc.microblock.TGStickerProvider.TGS_MIME
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StickerFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun countsEachCurrentDocumentOnceAndPrefersTheOriginal() {
        val directory = temporary.newFolder()
        for (name in listOf("1_low.webp", "1_high.webp", "2_high.tgs", "3_high.webp",
            "99_high.webp", "4_high.webp", "5_low.webp")) {
            File(directory, name).writeText("sticker")
        }
        File(directory, "5_high.webp").createNewFile()
        val documents = listOf(
            StickerDocument(1, "image/webp"), StickerDocument(2, TGS_MIME),
            StickerDocument(3, "video/webm"), StickerDocument(4, "image/jpeg"),
            StickerDocument(5, "image/webp")
        ).associateBy { it.id.toString() }
        val files = stickerFiles(directory, documents)

        assertEquals("1_high.webp", files["1"]?.name)
        assertEquals("5_low.webp", files["5"]?.name)
        assertEquals(StickerState(2, 1, 3), stateOf(files))
    }

    @Test fun matchesConvertedExtensionsWhenSyncedIsTrue() {
        val directory = temporary.newFolder()
        for (name in listOf("1_high.png", "2_low.gif", "3_high.gif", "3_high.png")) {
            File(directory, name).writeText("converted")
        }
        val documents = listOf(
            StickerDocument(1, "image/webp"),
            StickerDocument(2, TGS_MIME),
            StickerDocument(3, "video/webm")
        ).associateBy { it.id.toString() }

        val files = stickerFiles(directory, documents, synced = true)

        assertEquals("1_high.png", files["1"]?.name)
        assertEquals("2_low.gif", files["2"]?.name)
        assertEquals("3_high.gif", files["3"]?.name)
        assertEquals(StickerState(2, 1, 3), stateOf(files))
    }
}

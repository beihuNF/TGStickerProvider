package cc.microblock.TGStickerProvider.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StickerConversionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun webmLowQualityUsesTransparentPaletteAndBounds() {
        val args = buildWebmGifArgs("/tmp/in.webm", highQuality = false, useLibvpxVp9 = false).toList()

        assertFalse(args.contains("libvpx-vp9"))
        assertEquals("-i", args[0])
        assertEquals("/tmp/in.webm", args[1])
        assertEquals("-filter_complex", args[2])
        val filter = args[3]
        assertTrue(filter.contains("fps=12,scale=256:-1:flags=lanczos"))
        assertTrue(filter.contains("palettegen=reserve_transparent=1:stats_mode=diff"))
        assertTrue(filter.contains("paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle"))
    }

    @Test fun webmHighQualityCapsFrameRateAndPrependsLibvpxVp9WhenSupported() {
        val args = buildWebmGifArgs("/tmp/in.webm", highQuality = true, useLibvpxVp9 = true).toList()

        assertEquals(listOf("-c:v", "libvpx-vp9", "-i", "/tmp/in.webm"), args.take(4))
        assertEquals("-filter_complex", args[4])
        val filter = args[5]
        assertTrue(filter.contains("fps=20,scale=320:-1:flags=lanczos"))
        assertTrue(filter.contains("palettegen=reserve_transparent=1:stats_mode=diff"))
        assertTrue(filter.contains("paletteuse=diff_mode=rectangle"))
    }

    @Test fun detectsLibvpxVp9DecoderFromFfmpegOutput() {
        val withVp9 = """
            Decoders:
             V....D vp9                  Google VP9
             V....D libvpx-vp9           libvpx VP9 (codec vp9)
        """.trimIndent()
        val withoutVp9 = """
            Decoders:
             V....D vp9                  Google VP9
             V....D libvpx               libvpx VP8 (codec vp8)
        """.trimIndent()

        assertTrue(hasLibvpxVp9Decoder(withVp9))
        assertFalse(hasLibvpxVp9Decoder(withoutVp9))
        assertFalse(hasLibvpxVp9Decoder(""))
        assertFalse(hasLibvpxVp9Decoder(null))
    }

    @Test fun rawVideoGifArgsConfigureRgbaInputAndPalette() {
        val args = buildRawVideoGifArgs(
            inputPath = "/data/cache/pipe_1",
            width = 256,
            height = 256,
            fps = 20,
            highQuality = false
        ).toList()

        assertEquals(
            listOf("-f", "rawvideo", "-pix_fmt", "rgba", "-s", "256x256", "-r", "20", "-i", "/data/cache/pipe_1"),
            args.take(10)
        )
        assertEquals("-filter_complex", args[10])
        assertTrue(args[11].contains("palettegen=reserve_transparent=1:stats_mode=diff"))
        assertTrue(args[11].contains("paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle"))
    }

    @Test fun tgsFrameProgressStepsAcrossStartAndDurationFrames() {
        assertEquals(0f, tgsFrameProgress(0, 4, startFrame = 10f, durationFrames = 40f, endFrame = 50f), 1e-5f)
        assertEquals(0.25f, tgsFrameProgress(1, 4, startFrame = 10f, durationFrames = 40f, endFrame = 50f), 1e-5f)
        assertEquals(0.75f, tgsFrameProgress(3, 4, startFrame = 10f, durationFrames = 40f, endFrame = 50f), 1e-5f)
        assertEquals(0f, tgsFrameProgress(0, 1, startFrame = 0f, durationFrames = 60f, endFrame = 60f), 1e-5f)
        assertEquals(0f, tgsFrameProgress(0, 4, startFrame = 10f, durationFrames = 0f, endFrame = 10f), 1e-5f)
    }

    @Test fun cleanupConversionTempDirsRemovesOnlyStickerPrefixedEntries() {
        val cacheDir = temporary.newFolder("cache")
        val staleDir = File(cacheDir, "sticker-12345").apply { mkdirs() }
        File(staleDir, "frames.rgba").writeText("raw")
        val anotherStale = File(cacheDir, "sticker-67890").apply { mkdirs() }
        val keepDir = File(cacheDir, "glide_cache").apply { mkdirs() }
        val keepFile = File(cacheDir, "other.tmp").apply { writeText("keep") }

        cleanupConversionTempDirs(cacheDir)

        assertFalse(staleDir.exists())
        assertFalse(anotherStale.exists())
        assertTrue(keepDir.exists())
        assertTrue(keepFile.exists())
    }
}

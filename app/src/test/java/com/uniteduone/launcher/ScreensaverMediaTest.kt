package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensaverMediaTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private val mp4Head = bytes(0, 0, 0, 0x20) + "ftypisom".toByteArray()
    private val movHead = bytes(0, 0, 0, 0x14) + "ftypqt  ".toByteArray()
    private val webmHead = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x86, 0x81)
    private val jpegHead = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 0x4A, 0x46)

    @Test fun kindByExtensionIgnoringCase() {
        assertEquals(MediaKind.PHOTO, ScreensaverMedia.kindOf("a.JPG"))
        assertEquals(MediaKind.PHOTO, ScreensaverMedia.kindOf("a.webp"))
        assertEquals(MediaKind.VIDEO, ScreensaverMedia.kindOf("clip.MP4"))
        assertEquals(MediaKind.VIDEO, ScreensaverMedia.kindOf("IMG_0001.MOV"))
        assertEquals(MediaKind.VIDEO, ScreensaverMedia.kindOf("x.webm"))
        assertNull(ScreensaverMedia.kindOf("x.mkv"))
        assertNull(ScreensaverMedia.kindOf("x.gif"))
        assertNull(ScreensaverMedia.kindOf("noext"))
        // 图库落盘临时文件(writeFileAtomically 的 .<名>.<随机>.tmp)不是媒体
        assertNull(ScreensaverMedia.kindOf(".a.mp4.123.tmp"))
    }

    @Test fun playTimeIsCappedAtSixtySeconds() {
        assertEquals(8_000L, ScreensaverMedia.playMs(8_000))
        assertEquals(60_000L, ScreensaverMedia.playMs(60_000))
        assertEquals(60_000L, ScreensaverMedia.playMs(301_000))
        assertEquals(60_000L, ScreensaverMedia.playMs(0))   // 读不到时长:按上限
        assertEquals(60_000L, ScreensaverMedia.playMs(-1))
        assertEquals(13_000L, ScreensaverMedia.endWaitMs(8_000))
    }

    @Test fun sniffsContainers() {
        assertEquals("isobmff", ScreensaverMedia.sniffContainer(mp4Head))
        assertEquals("isobmff", ScreensaverMedia.sniffContainer(movHead))
        assertEquals("isobmff", ScreensaverMedia.sniffContainer(bytes(0, 0, 0, 8) + "wide".toByteArray()))
        assertEquals("ebml", ScreensaverMedia.sniffContainer(webmHead))
        assertNull(ScreensaverMedia.sniffContainer(jpegHead))
        assertNull(ScreensaverMedia.sniffContainer(ByteArray(0)))
        assertNull(ScreensaverMedia.sniffContainer(bytes(0, 0, 0)))
    }

    @Test fun extensionMustMatchContainer() {
        assertTrue(ScreensaverMedia.containerMatches("mp4", "isobmff"))
        assertTrue(ScreensaverMedia.containerMatches("MOV", "isobmff"))
        assertTrue(ScreensaverMedia.containerMatches("webm", "ebml"))
        assertFalse(ScreensaverMedia.containerMatches("webm", "isobmff"))
        assertFalse(ScreensaverMedia.containerMatches("mp4", "ebml"))
        assertFalse(ScreensaverMedia.containerMatches("mp4", null))
        assertFalse(ScreensaverMedia.containerMatches("jpg", "isobmff"))
    }

    @Test fun uploadGatesByTypeAndSize() {
        val mb = 1024L * 1024
        // 屏保:照片、视频都收
        assertNull(ScreensaverMedia.uploadRejection("screensavers", "a.jpg", 5 * mb, jpegHead))
        assertNull(ScreensaverMedia.uploadRejection("screensavers", "a.mp4", 400 * mb, mp4Head))
        assertNull(ScreensaverMedia.uploadRejection("screensavers", "a.MOV", 10 * mb, movHead))
        assertNull(ScreensaverMedia.uploadRejection("screensavers", "a.webm", 10 * mb, webmHead))
        // 壁纸 / 卡片图:视频不收
        assertEquals("type", ScreensaverMedia.uploadRejection("wallpapers", "a.mp4", mb, mp4Head))
        assertEquals("type", ScreensaverMedia.uploadRejection("cards", "a.webm", mb, webmHead))
        // 大小:视频 500 MB,照片仍是 30 MB
        assertNull(ScreensaverMedia.uploadRejection("screensavers", "a.mp4", 500 * mb, mp4Head))
        assertEquals("video_size", ScreensaverMedia.uploadRejection("screensavers", "a.mp4", 500 * mb + 1, mp4Head))
        assertEquals("size", ScreensaverMedia.uploadRejection("screensavers", "a.jpg", 30 * mb + 1, jpegHead))
        // 扩展名是视频、文件头不是
        assertEquals("video_decode", ScreensaverMedia.uploadRejection("screensavers", "a.mp4", mb, jpegHead))
        assertEquals("video_decode", ScreensaverMedia.uploadRejection("screensavers", "a.webm", mb, mp4Head))
        // 名字 / 类型
        assertEquals("name", ScreensaverMedia.uploadRejection("screensavers", null, mb, mp4Head))
        assertEquals("type", ScreensaverMedia.uploadRejection("screensavers", "a.gif", mb, jpegHead))
        assertEquals("type", ScreensaverMedia.uploadRejection("screensavers", "a.mkv", mb, webmHead))
        // 原始上传读 body 之前的预检(文件头还没有):只看名字 / 类型 / 大小
        assertNull(ScreensaverMedia.uploadRejection("screensavers", "a.mp4", 100 * mb, null))
        assertEquals("video_size", ScreensaverMedia.uploadRejection("screensavers", "a.mp4", 600 * mb, null))
        assertEquals("type", ScreensaverMedia.uploadRejection("wallpapers", "a.mov", mb, null))
    }

    @Test fun formatsDurations() {
        assertEquals("0:08", ScreensaverMedia.formatDuration(8_000))
        assertEquals("0:01", ScreensaverMedia.formatDuration(400))      // 不足 1 s 向上取整
        assertEquals("0:09", ScreensaverMedia.formatDuration(8_001))
        assertEquals("1:05", ScreensaverMedia.formatDuration(65_000))
        assertEquals("10:00", ScreensaverMedia.formatDuration(600_000))
        assertEquals("1:02:03", ScreensaverMedia.formatDuration(3_723_000))
        assertNull(ScreensaverMedia.formatDuration(0))
        assertNull(ScreensaverMedia.formatDuration(-5))
    }

    @Test fun nextPlayableSkipsFailedItems() {
        val none: (Int) -> Boolean = { false }
        assertEquals(1, ScreensaverMedia.nextPlayable(0, 3, none))
        assertEquals(0, ScreensaverMedia.nextPlayable(2, 3, none))
        // 1 坏了:0 → 2
        assertEquals(2, ScreensaverMedia.nextPlayable(0, 3) { it == 1 })
        // 1、2 都坏:0 → 0(绕一圈回到自己)
        assertEquals(0, ScreensaverMedia.nextPlayable(0, 3) { it != 0 })
        // 只有一项(单视频):回到自己 = 循环
        assertEquals(0, ScreensaverMedia.nextPlayable(0, 1, none))
        // 全坏 / 空:null,原地等
        assertNull(ScreensaverMedia.nextPlayable(0, 3) { true })
        assertNull(ScreensaverMedia.nextPlayable(0, 0, none))
        // 过期下标先夹回
        assertEquals(0, ScreensaverMedia.nextPlayable(9, 3, none))
    }

    @Test fun cropScaleFillsWithoutDistortion() {
        // 16:9 视频放 16:9 视图:不缩
        assertEquals(1f to 1f, ScreensaverMedia.cropScale(1920, 1080, 3840, 2160))
        // 竖屏 9:16 视频放 16:9 视图:宽铺满,高放大裁掉上下
        val (sx, sy) = ScreensaverMedia.cropScale(1920, 1080, 1080, 1920)
        assertEquals(1f, sx, 1e-4f)
        assertEquals((1920f * (1920f / 1080f)) / 1080f, sy, 1e-3f)
        // 4:3 视频放 16:9:宽铺满,高按比例多出来
        val (ax, ay) = ScreensaverMedia.cropScale(1600, 900, 640, 480)
        assertEquals(1f, ax, 1e-4f)
        assertEquals(1200f / 900f, ay, 1e-4f)
        // 超宽 2.4:1 视频:高铺满,宽多出来
        val (wx, wy) = ScreensaverMedia.cropScale(1920, 1080, 2400, 1000)
        assertEquals(1f, wy, 1e-4f)
        assertEquals((2400f * 1.08f) / 1920f, wx, 1e-4f)
        // 尺寸不知道:不变
        assertEquals(1f to 1f, ScreensaverMedia.cropScale(0, 1080, 1920, 1080))
        assertEquals(1f to 1f, ScreensaverMedia.cropScale(1920, 1080, 0, 0))
    }

    @Test fun parsesSingleByteRanges() {
        assertEquals(0L..99L, ScreensaverMedia.parseRange("bytes=0-99", 1000))
        assertEquals(500L..999L, ScreensaverMedia.parseRange("bytes=500-", 1000))
        assertEquals(900L..999L, ScreensaverMedia.parseRange("bytes=-100", 1000))
        assertEquals(0L..999L, ScreensaverMedia.parseRange("bytes=-5000", 1000))   // 后缀比文件大:整个
        assertEquals(990L..999L, ScreensaverMedia.parseRange("bytes=990-5000", 1000)) // 尾端夹回
        assertEquals(0L..1L, ScreensaverMedia.parseRange("bytes=0-1", 1000))       // Safari 的探测请求
        assertNull(ScreensaverMedia.parseRange(null, 1000))
        assertNull(ScreensaverMedia.parseRange("bytes=1000-", 1000))               // 起点越界
        assertNull(ScreensaverMedia.parseRange("bytes=5-3", 1000))
        assertNull(ScreensaverMedia.parseRange("bytes=0-1,5-9", 1000))            // 多段不支持
        assertNull(ScreensaverMedia.parseRange("items=0-1", 1000))
        assertNull(ScreensaverMedia.parseRange("bytes=abc", 1000))
        assertNull(ScreensaverMedia.parseRange("bytes=-0", 1000))
        assertNull(ScreensaverMedia.parseRange("bytes=0-9", 0))
    }
}

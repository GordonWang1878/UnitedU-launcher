package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置外壳纯模型的边界(ShellModelTest 之外):选项 id 的解析、Bundle 里的栈字符串各种残缺写法、
 * 越界的选项档不预览、空栈 / 单帧栈、关于页让路、胶囊间距与预览框的退化输入。
 */
class ShellModelBoundaryTest {

    // ---- 选项 id ----

    @Test fun optionIdRoundTripsAndJunkIsNotAnOption() {
        for (i in 0..20) assertEquals(i, optionIndex(optionId(i)))
        for (id in listOf(null, "", "opt", "opt:", "opt:x", "opt:1.0", "xopt:1", "OPT:1", " opt:1")) {
            assertNull("「$id」", optionIndex(id))
        }
    }

    // ---- 页 id ----

    @Test fun pageIdParsersRejectEmptyAndForeignIds() {
        assertNull(ShellPages.optionsRow("o:"))
        assertNull(ShellPages.subRow("s:"))
        assertNull(ShellPages.groupOf("g:"))
        assertNull(ShellPages.groupOf("g:layout"))       // 组名区分大小写
        assertNull(ShellPages.optionsRow("g:LAYOUT"))
        assertNull(ShellPages.groupOf(ShellPages.ROOT))
        assertEquals("cardsPerRow", ShellPages.optionsRow(ShellPages.options("cardsPerRow")))
        assertEquals(STANDBY_ROW, ShellPages.subRow(ShellPages.sub(STANDBY_ROW)))
        for (g in GroupId.entries) assertEquals(g, ShellPages.groupOf(ShellPages.group(g)))
    }

    @Test fun pagesWithoutAKnownShapeHaveNoPreview() {
        for (page in listOf("", "o:", "o:nosuch", "g:", "s:$STANDBY_ROW", ShellPages.ROOT, ShellPages.RESTORE, ShellPages.HOME)) {
            assertFalse("「$page」", pageHasPreview(page))
        }
    }

    // ---- Bundle 里的栈 ----

    @Test fun truncatedOrMangledStacksDecodeToClosed() {
        for (text in listOf(" ", "|root", "root|", "root||g:LAYOUT", " root", "root |g:LAYOUT", "root|o:", "root|s:", "root|s:nope", "root|g:layout", "ROOT")) {
            assertTrue("「$text」", decodeShellStack(text).isEmpty())
        }
    }

    @Test fun anEmptyFocusDecodesAsUnset() {
        assertEquals(listOf(ShellFrame(ShellPages.ROOT, null)), decodeShellStack("root@"))
        assertEquals(listOf(ShellFrame(ShellPages.ROOT, null)), decodeShellStack("root"))
    }

    @Test fun aFocusContainingTheAtSignSurvivesTheRoundTrip() {
        // 只按第一个 @ 切:胶囊 id 里就算出现 @ 也原样回来
        val st = listOf(ShellFrame(ShellPages.ROOT, "a@b"), ShellFrame(ShellPages.group(GroupId.LAYOUT), null))
        assertEquals(st, decodeShellStack(encodeShellStack(st)))
    }

    @Test fun anEmptyStackEncodesToAnEmptyStringAndDecodesClosed() {
        assertEquals("", encodeShellStack(emptyList()))
        assertTrue(decodeShellStack(encodeShellStack(emptyList())).isEmpty())
    }

    // ---- 栈操作 ----

    @Test fun poppingASingleFrameClosesTheShell() {
        assertTrue(shellPop(shellOpened()).isEmpty())
        assertTrue(shellPop(emptyList()).isEmpty())
    }

    @Test fun setFocusOnlyTouchesTheTopFrame() {
        val st = shellPush(shellOpened(), ShellPages.group(GroupId.LAYOUT), null)
        val next = shellSetFocus(st, "cardsPerRow")
        assertSame(st[0], next[0])
        assertEquals(ShellFrame(ShellPages.group(GroupId.LAYOUT), "cardsPerRow"), next[1])
        // 栈顶 focus 为 null 时,设成任何 id 都算改变
        assertEquals(2, next.size)
    }

    @Test fun aboutPageShowsForEveryStackShapeExceptARestoreLayerOnTop() {
        assertFalse(aboutPageShown(false, emptyList()))
        assertFalse(aboutPageShown(false, shellOpened()))
        assertTrue(aboutPageShown(true, emptyList()))
        assertTrue(aboutPageShown(true, shellOpened()))
        assertFalse(aboutPageShown(true, shellPush(shellOpened(), ShellPages.RESTORE, SHELL_CANCEL)))
        // 确认层不在栈顶(它上面又压了一层,不该发生):照常画关于页,不留「开着却看不见」的黑洞
        assertTrue(aboutPageShown(true, shellPush(shellPush(shellOpened(), ShellPages.RESTORE, null), ShellPages.HOME, null)))
    }

    // ---- 预览—保存—放弃 ----

    @Test fun outOfRangeOptionFocusNeverPreviews() {
        val saved = Settings()
        for (row in listOf("cardsPerRow", "themeColor")) {
            for (focus in listOf("opt:-1", "opt:999", null, "garbage")) {
                val st = listOf(ShellFrame(ShellPages.ROOT, null), ShellFrame(ShellPages.options(row), focus))
                assertSame("$row $focus", saved, effectiveSettings(saved, st))
            }
        }
        assertSame(saved, effectiveSettings(saved, emptyList()))
    }

    // ---- 版式 ----

    @Test fun capsuleGapDegenerateInputs() {
        assertEquals(16f, capsuleGap(emptyList()))
        // 胶囊本身就比整列还高:间距落到下限,不会变负
        assertEquals(4f, capsuleGap(listOf(300f, 300f)))
        // 正好放得下:用首选间距
        assertEquals(16f, capsuleGap(listOf(242f, 242f)))
        assertEquals(15f, capsuleGap(listOf(242.5f, 242.5f)))
    }

    @Test fun previewRectFallsBackToSixteenByNineWhenTheWidthIsUnknown() {
        for (w in listOf(0f, -1f)) {
            val r = previewRect(w, 540f)
            assertEquals(PREVIEW_WIDTH_DP * 9f / 16f, r.height, 1e-4f)
            assertEquals(PREVIEW_LEFT_DP, r.x)
        }
    }
}

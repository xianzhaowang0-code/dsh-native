package xin.dshaa.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test
import xin.dshaa.nativeapp.ui.shell.native.SubpageShift

/**
 * M88-F（docs/183 §6）：设置二级页轻滑入。
 *
 * 拍板（docs/183 §10）：保留 H4 的组合保留，只加 1/10 屏宽位移 + 180ms 淡入，
 * 退出反向 140ms；二级页之间切换 6% + 160ms。
 */
class SubpageShiftTest {

    @Test fun enterFromSettingsHomeSlidesInFromRight() {
        assertEquals(1, SubpageShift.dir(null, "display"))
        assertEquals(SubpageShift.EDGE_FRACTION, SubpageShift.fraction(null, "display"), 0.0001f)
        assertEquals(180, SubpageShift.durationMs(null, "display"))
    }

    @Test fun exitSlidesBackAndIsFaster() {
        assertEquals(-1, SubpageShift.dir("display", null))
        assertEquals(SubpageShift.EDGE_FRACTION, SubpageShift.fraction("display", null), 0.0001f)
        assertEquals(140, SubpageShift.durationMs("display", null))
    }

    @Test fun siblingSwitchUsesSixPercentAndDoesNotCountAsNoop() {
        assertEquals(1, SubpageShift.dir("display", "permissions"))
        assertEquals(SubpageShift.SIBLING_FRACTION, SubpageShift.fraction("display", "permissions"), 0.0001f)
        assertEquals(160, SubpageShift.durationMs("display", "permissions"))
    }

    @Test fun samePageOrNoSubpageProducesNoShift() {
        assertEquals(0, SubpageShift.dir(null, null))
        assertEquals(0, SubpageShift.dir("display", "display"))
    }
}

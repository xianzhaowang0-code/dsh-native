package xin.dshaa.core.attachment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 归一化尺寸算法：与上游 `requestImageDimensions` 逐位一致（小图不放大、等比、预算内取整）。 */
class ImageGeometryTest {

    @Test
    fun smallImageNotEnlarged() {
        assertEquals(800 to 600, ImageNormalizer.targetDimensions(800, 600))
        assertEquals(2048 to 2048, ImageNormalizer.targetDimensions(2048, 2048))
    }

    @Test
    fun landscapeScaledIntoPixelBudget() {
        // 4000x3000：scale=0.5912… → 2364x1773（4,191,372 ≤ 2048²）
        assertEquals(2364 to 1773, ImageNormalizer.targetDimensions(4000, 3000))
    }

    @Test
    fun panoramaKeepsAspect() {
        // 5000x1000：4578x916（4579 超预算，上游算法逐像素回退一位）
        assertEquals(4578 to 916, ImageNormalizer.targetDimensions(5000, 1000))
    }

    @Test
    fun portraitKeepsAspect() {
        // 3000x4000 → 1773x2364（与 landscape 对称）
        assertEquals(1773 to 2364, ImageNormalizer.targetDimensions(3000, 4000))
    }

    @Test
    fun longEdgeCapApplies() {
        // 默认预算下长边上限 8192 不会触发；单独验证上限逻辑
        assertEquals(8192 to 8192, ImageNormalizer.targetDimensions(10000, 10000, maxPixels = 100_000_000, maxDimension = 8192))
        assertEquals(8192 to 4096, ImageNormalizer.targetDimensions(20000, 10000, maxPixels = 400_000_000, maxDimension = 8192))
    }

    @Test
    fun hugeSourceBytesNeverRejectOnlySkipPassThrough() {
        // M88-H：源文件字节数不参与「接受」判定，只决定是否原样透传。
        assertFalse(ImageNormalizer.passThroughEligible("image/png", 1, 60L * 1024 * 1024, 800, 600))
        assertFalse(ImageNormalizer.passThroughEligible("image/jpeg", 1, 50L * 1024 * 1024, 4000, 3000))
        assertFalse(
            ImageNormalizer.passThroughEligible("image/png", 1, 500L * 1024 * 1024 * 1024, 8192, 8192),
            "512GB 源文件也只是不透传，不会在这里被拒绝",
        )
    }

    @Test
    fun passThroughStillRequiresCleanSmallImage() {
        // 干净小图走透传（零重编码）
        assertTrue(ImageNormalizer.passThroughEligible("image/png", 1, 1024, 800, 600))
        assertTrue(
            ImageNormalizer.passThroughEligible(
                "image/webp", 1, ImageNormalizer.NORMALIZED_MAX_BYTES.toLong(), 2048, 2048,
            ),
        )
        // 超过 4MB / 2048² / 长边 8192 / 带旋转 / GIF 都不透传（走解码缩放；不是拒绝）
        assertFalse(
            ImageNormalizer.passThroughEligible(
                "image/png", 1, ImageNormalizer.NORMALIZED_MAX_BYTES.toLong() + 1, 800, 600,
            ),
        )
        assertFalse(ImageNormalizer.passThroughEligible("image/png", 1, 1024, 2049, 2048))
        assertFalse(ImageNormalizer.passThroughEligible("image/png", 1, 1024, 8193, 100))
        assertFalse(ImageNormalizer.passThroughEligible("image/png", 6, 1024, 800, 600))
        assertFalse(ImageNormalizer.passThroughEligible("image/gif", 1, 1024, 800, 600))
    }
}

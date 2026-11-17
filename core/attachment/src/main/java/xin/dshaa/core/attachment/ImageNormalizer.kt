package xin.dshaa.core.attachment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import xin.dshaa.core.tools.ImageStoreException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 图片归一化：把任意 PNG/JPEG/WebP/GIF 变成「单帧、8bit、方向已应用、尺寸/字节可控」的
 * 8-bit sRGB 图（对齐上游 `dsh-attachment-local` 的归一化语义，不追求逐字节相同的编码器）。
 *
 * M88-H（用户 2026-11-17）：**源文件字节数不设上限**（原 50MB 拦截已删除）。为了不整块读进
 * 内存：文件入口 [normalizeFile] 只读 bounds/EXIF + 按 `inSampleSize` 解码；只有 ≤4MB 的
 * 「干净小图」才整读做原样透传。
 *
 * 常量取上游部署默认值：
 * - 解码上限：单边 65535px、6400 万像素（防解码炸弹；不是文件大小限制）；
 * - 归一化上限：2048×2048 像素、长边 8192px、4MB（超出就等比缩小 / 降质量，仍超则报错）。
 *
 * 快手路径：本来就是干净小图（≤4MB、≤2048²、无旋转）→ **原字节透传**，不重编码
 * （截图场景走这条，零画质损失、零延迟）。
 */
object ImageNormalizer {

    const val MAX_SOURCE_PIXELS = 64_000_000L
    const val MAX_SOURCE_DIMENSION = 65_535
    const val NORMALIZED_MAX_BYTES = 4 * 1024 * 1024
    const val NORMALIZED_MAX_PIXELS = 2048 * 2048
    const val NORMALIZED_MAX_DIMENSION = 8192

    /** 解码时允许占用的近似内存上限（超过就加大 inSampleSize）。 */
    private const val MAX_DECODE_BYTES = 48L * 1024 * 1024

    /** `ExifInterface.ORIENTATION_NORMAL` 的常量值（纯函数不依赖 Android 类）。 */
    private const val ORIENTATION_NORMAL = 1

    data class Normalized(
        val data: ByteArray,
        val mediaType: String,
        val width: Int,
        val height: Int,
        val originalWidth: Int?,
        val originalHeight: Int?,
    )

    /**
     * 字节数组入口（测试/截图这类内存里已经有数据的路径）。
     *
     * M88-H：**不再按源大小拒绝**；只保留像素/单边解码上限。
     */
    fun normalize(data: ByteArray, declaredMediaType: String): Normalized {
        if (data.isEmpty()) throw ImageStoreException(ImageStoreException.Code.INVALID_IMAGE)
        return normalizeSource(
            declaredMediaType = declaredMediaType,
            sourceByteSize = data.size.toLong(),
            readBounds = {
                BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
                    BitmapFactory.decodeByteArray(data, 0, data.size, it)
                }
            },
            readOrientation = { exifOrientation(data) },
            passThrough = { data },
            decode = { sourceWidth, sourceHeight, targetWidth, targetHeight ->
                decodeScaled(data, sourceWidth, sourceHeight, targetWidth, targetHeight)
            },
        )
    }

    /**
     * 文件入口（M88-H）：**不把整个源文件读进内存**，文件大小不限。
     *
     * 先 `inJustDecodeBounds` + `ExifInterface(path)`，再按采样率 `decodeFile`；
     * 只有 ≤4MB 的干净小图会整读做原样透传。
     */
    fun normalizeFile(file: File, declaredMediaType: String): Normalized {
        if (!file.isFile || file.length() <= 0L) {
            throw ImageStoreException(ImageStoreException.Code.INVALID_IMAGE)
        }
        val path = file.path
        return normalizeSource(
            declaredMediaType = declaredMediaType,
            sourceByteSize = file.length(),
            readBounds = {
                BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
                    BitmapFactory.decodeFile(path, it)
                }
            },
            readOrientation = { exifOrientation(file) },
            passThrough = { file.readBytes() },
            decode = { sourceWidth, sourceHeight, targetWidth, targetHeight ->
                decodeScaledFile(file, sourceWidth, sourceHeight, targetWidth, targetHeight)
            },
        )
    }

    /**
     * 干净小图透传判定（纯函数，JVM 可测）。
     *
     * 注意 [sourceBytes] **只影响是否透传**，不影响是否接受——源文件再大也会走解码/缩放，
     * 不会因为字节数被拒绝（M88-H）。
     */
    fun passThroughEligible(
        declaredMediaType: String,
        orientation: Int,
        sourceBytes: Long,
        width: Int,
        height: Int,
    ): Boolean = declaredMediaType != "image/gif" &&
        orientation == ORIENTATION_NORMAL &&
        sourceBytes <= NORMALIZED_MAX_BYTES &&
        width.toLong() * height <= NORMALIZED_MAX_PIXELS &&
        max(width, height) <= NORMALIZED_MAX_DIMENSION

    private fun normalizeSource(
        declaredMediaType: String,
        sourceByteSize: Long,
        readBounds: () -> BitmapFactory.Options,
        readOrientation: () -> Int,
        passThrough: () -> ByteArray,
        decode: (sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int) -> Bitmap?,
    ): Normalized {
        val bounds = readBounds()
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw ImageStoreException(ImageStoreException.Code.INVALID_IMAGE)
        }
        val detected = bounds.outMimeType
        if (detected != null && detected != declaredMediaType) {
            throw ImageStoreException(ImageStoreException.Code.IMAGE_TYPE_MISMATCH)
        }
        if (max(sourceWidth, sourceHeight) > MAX_SOURCE_DIMENSION) {
            throw ImageStoreException(ImageStoreException.Code.IMAGE_DIMENSION_TOO_LARGE)
        }
        if (sourceWidth.toLong() * sourceHeight > MAX_SOURCE_PIXELS) {
            throw ImageStoreException(ImageStoreException.Code.IMAGE_TOO_MANY_PIXELS)
        }

        val orientation = readOrientation()
        val swapped = orientation == 5 || orientation == 6 || orientation == 7 || orientation == 8
        val displayWidth = if (swapped) sourceHeight else sourceWidth
        val displayHeight = if (swapped) sourceWidth else sourceHeight

        val passThrough = passThroughEligible(
            declaredMediaType = declaredMediaType,
            orientation = orientation,
            sourceBytes = sourceByteSize,
            width = displayWidth,
            height = displayHeight,
        )
        if (passThrough) {
            return Normalized(
                data = passThrough(),
                mediaType = declaredMediaType,
                width = displayWidth,
                height = displayHeight,
                originalWidth = null,
                originalHeight = null,
            )
        }

        val target = targetDimensions(displayWidth, displayHeight)
        val targetWidth = target.first
        val targetHeight = target.second
        var bitmap = decode(sourceWidth, sourceHeight, targetWidth, targetHeight)
            ?: throw ImageStoreException(ImageStoreException.Code.INVALID_IMAGE)
        bitmap = applyOrientation(bitmap, orientation)
        if (bitmap.width != targetWidth || bitmap.height != targetHeight) {
            val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }

        val usePng = bitmap.hasAlpha() || declaredMediaType == "image/png" || declaredMediaType == "image/gif"
        val encoded = encodeWithinLimit(bitmap, usePng)
        val output = encoded.first
        val finalBitmap = encoded.second
        val width = finalBitmap.width
        val height = finalBitmap.height
        if (finalBitmap !== bitmap) bitmap.recycle()
        finalBitmap.recycle()

        return Normalized(
            data = output,
            mediaType = if (usePng) "image/png" else "image/jpeg",
            width = width,
            height = height,
            originalWidth = if (displayWidth != width) displayWidth else null,
            originalHeight = if (displayHeight != height) displayHeight else null,
        )
    }

    /**
     * 等比缩到「总像素 ≤ 2048²」再压长边 ≤ 8192（上游 `requestImageDimensions` + 长边上限），
     * 小图不放大。与上游算法逐位一致（向下取整、短边四舍五入、超预算时逐像素回退）。
     */
    fun targetDimensions(width: Int, height: Int, maxPixels: Int = NORMALIZED_MAX_PIXELS, maxDimension: Int = NORMALIZED_MAX_DIMENSION): Pair<Int, Int> {
        val budget = requestImageDimensions(width, height, maxPixels)
        val longEdge = max(budget.first, budget.second)
        if (longEdge <= maxDimension) return budget
        val scale = maxDimension.toDouble() / longEdge
        return Pair(
            max(1, kotlin.math.floor(budget.first * scale).toInt()),
            max(1, kotlin.math.floor(budget.second * scale).toInt()),
        )
    }

    /** 上游 `requestImageDimensions`：总像素预算内的等比整数尺寸。 */
    private fun requestImageDimensions(width: Int, height: Int, maxPixels: Int): Pair<Int, Int> {
        val scale = min(1.0, sqrt(maxPixels.toDouble() / (width.toDouble() * height.toDouble())))
        if (scale >= 1.0) return Pair(width, height)
        if (width >= height) {
            var projectedWidth = max(1, kotlin.math.floor(width * scale).toInt())
            var projectedHeight = max(1, (projectedWidth.toDouble() * height / width).roundToInt())
            while (projectedWidth.toLong() * projectedHeight > maxPixels && projectedWidth > 1) {
                projectedWidth -= 1
                projectedHeight = max(1, (projectedWidth.toDouble() * height / width).roundToInt())
            }
            return Pair(projectedWidth, projectedHeight)
        }
        var projectedHeight = max(1, kotlin.math.floor(height * scale).toInt())
        var projectedWidth = max(1, (projectedHeight.toDouble() * width / height).roundToInt())
        while (projectedWidth.toLong() * projectedHeight > maxPixels && projectedHeight > 1) {
            projectedHeight -= 1
            projectedWidth = max(1, (projectedHeight.toDouble() * width / height).roundToInt())
        }
        return Pair(projectedWidth, projectedHeight)
    }

    private fun sampleSize(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        // 大图先按内存上限降采样，避免 48MP 直出 OOM
        while ((sourceWidth / sample).toLong() * (sourceHeight / sample) * 4 > MAX_DECODE_BYTES) {
            sample *= 2
        }
        return sample
    }

    private fun decodeScaled(data: ByteArray, sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(sourceWidth, sourceHeight, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    /** 文件解码（M88-H）：不进 ByteArray，BitmapFactory 直接按路径 + 采样率解码。 */
    private fun decodeScaledFile(file: File, sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(sourceWidth, sourceHeight, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    private fun exifOrientation(data: ByteArray): Int = try {
        val exif = ExifInterface(ByteArrayInputStream(data))
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** 文件 EXIF 方向（只读文件头，不整读）。 */
    private fun exifOrientation(file: File): Int = try {
        val exif = ExifInterface(file.absolutePath)
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** 应用 EXIF 方向（1/2/3/4 翻转，5/6/7/8 旋转）。 */
    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) return source
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return try {
            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (rotated !== source) source.recycle()
            rotated
        } catch (_: Exception) {
            source
        }
    }

    /** 编码到 ≤4MB：PNG 超了就缩小；JPEG 走 90/85/80/75/70 质量阶梯，仍超就缩小。 */
    private fun encodeWithinLimit(bitmap: Bitmap, png: Boolean): Pair<ByteArray, Bitmap> {
        var current = bitmap
        repeat(5) {
            if (png) {
                val bytes = encode(current, Bitmap.CompressFormat.PNG, 100)
                if (bytes.size <= NORMALIZED_MAX_BYTES) return bytes to current
            } else {
                for (quality in intArrayOf(90, 85, 80, 75, 70)) {
                    val bytes = encode(current, Bitmap.CompressFormat.JPEG, quality)
                    if (bytes.size <= NORMALIZED_MAX_BYTES) return bytes to current
                }
            }
            val nextWidth = max(1, (current.width * 0.75).toInt())
            val nextHeight = max(1, (current.height * 0.75).toInt())
            if (nextWidth == current.width && nextHeight == current.height) {
                throw ImageStoreException(ImageStoreException.Code.IMAGE_TOO_LARGE)
            }
            val next = Bitmap.createScaledBitmap(current, nextWidth, nextHeight, true)
            if (next !== current) current.recycle()
            current = next
        }
        throw ImageStoreException(ImageStoreException.Code.IMAGE_TOO_LARGE)
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(format, quality, out)) {
            throw ImageStoreException(ImageStoreException.Code.WRITE_FAILED, "image encoding failed")
        }
        return out.toByteArray()
    }
}

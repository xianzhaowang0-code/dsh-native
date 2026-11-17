package xin.dshaa.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

/**
 * M88-H（用户 2026-11-17）：文件/图片引用不再有 50MB 上限。
 *
 * 这里锁住复制通道：分块读写、按实际字节计数，**不因大小拒绝**；
 * 用「按需生成」的输入流验证 60MB（>旧 50MB 上限）也能完整复制，且不把内容整块放进内存。
 */
class AttachmentStreamsTest {

    /** 不真正持有数据的输入流：每次 read 填满缓冲，直到 [limit]。 */
    private class GeneratedStream(private val limit: Long) : InputStream() {
        private var emitted = 0L
        override fun read(): Int = if (emitted >= limit) -1 else (emitted++ % 251).toInt()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (emitted >= limit) return -1
            val n = minOf(len.toLong(), limit - emitted).toInt()
            for (i in 0 until n) b[off + i] = ((emitted + i) % 251).toByte()
            emitted += n
            return n
        }
    }

    /** 只计数、不保存内容（证明内存占用与文件大小无关）。 */
    private class CountingOutput : OutputStream() {
        var count = 0L
        override fun write(b: Int) { count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { count += len }
    }

    @Test
    fun copiesMoreThanTheOldFiftyMegabyteLimit() {
        val source = 60L * 1024 * 1024
        val out = CountingOutput()
        val copied = AttachmentStreams.copy(GeneratedStream(source), out)
        assertEquals(source, copied)
        assertEquals(source, out.count)
    }

    @Test
    fun copiesSmallPayloadByteExactly() {
        val bytes = ByteArray(200_000) { (it % 256).toByte() }
        val out = java.io.ByteArrayOutputStream()
        val copied = AttachmentStreams.copy(bytes.inputStream(), out)
        assertEquals(bytes.size.toLong(), copied)
        assertTrue(bytes.contentEquals(out.toByteArray()))
    }

    @Test
    fun emptyInputIsZeroNotAnError() {
        val out = CountingOutput()
        assertEquals(0L, AttachmentStreams.copy(ByteArray(0).inputStream(), out))
        assertEquals(0L, out.count)
    }
}

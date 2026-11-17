package xin.dshaa.nativeapp.data

import java.io.InputStream
import java.io.OutputStream

/**
 * M88-H（用户 2026-11-17）：附件/文件引用的分块复制。
 *
 * 用户要求文件和图片引用**不要 50MB 上限**；为了避免整块读进内存 OOM，
 * 一律按固定大小缓冲区分块读写（内存占用与文件大小无关），且不设字节上限。
 */
internal object AttachmentStreams {

    const val DEFAULT_BUFFER_SIZE = 64 * 1024

    /** 把 [input] 全部复制到 [output]，返回复制的字节数（空输入返回 0，不抛）。 */
    fun copy(
        input: InputStream,
        output: OutputStream,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
    ): Long {
        require(bufferSize > 0) { "bufferSize must be positive" }
        val buffer = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return total
    }
}

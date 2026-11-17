package xin.dshaa.nativeapp.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dshaa.ui.shellapi.chat.CONTINUE_TRUNCATED_PROMPT
import xin.dshaa.ui.shellapi.chat.TruncationKind
import xin.dshaa.ui.shellapi.chat.TruncationResumePreferences
import xin.dshaa.ui.shellapi.chat.TruncationResumeStore
import xin.dshaa.ui.shellapi.chat.truncationResumeNotice

/**
 * H1（docs/182 §H1）：截断后「继续生成」的会话级持久化与文案。
 * 纯 JVM 单测：设备离线也能跑；不依赖 SharedPreferences（用内存假实现）。
 */
class TruncationResumeTest {

    private class MemoryPrefs : TruncationResumePreferences {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun `mark and pending round trip`() {
        val prefs = MemoryPrefs()
        val store = TruncationResumeStore(prefs)
        assertNull(store.pending("s1"))
        store.mark("s1", TruncationKind.TOOL_CALL)
        assertEquals(TruncationKind.TOOL_CALL, store.pending("s1"))
        store.mark("s1", TruncationKind.TEXT)
        assertEquals(TruncationKind.TEXT, store.pending("s1"))
    }

    @Test
    fun `consume returns the marker exactly once`() {
        val store = TruncationResumeStore(MemoryPrefs())
        store.mark("s1", TruncationKind.TOOL_CALL)
        assertEquals(TruncationKind.TOOL_CALL, store.consume("s1"))
        assertNull(store.consume("s1"))
        assertNull(store.pending("s1"))
    }

    @Test
    fun `sessions are isolated`() {
        val store = TruncationResumeStore(MemoryPrefs())
        store.mark("a", TruncationKind.TOOL_CALL)
        store.mark("b", TruncationKind.TEXT)
        assertEquals(TruncationKind.TOOL_CALL, store.pending("a"))
        assertEquals(TruncationKind.TEXT, store.pending("b"))
        store.clear("a")
        assertNull(store.pending("a"))
        assertEquals("清一条不能误伤另一条", TruncationKind.TEXT, store.pending("b"))
    }

    @Test
    fun `consume one session keeps the other`() {
        val store = TruncationResumeStore(MemoryPrefs())
        store.mark("a", TruncationKind.TOOL_CALL)
        store.mark("b", TruncationKind.TEXT)
        assertEquals(TruncationKind.TOOL_CALL, store.consume("a"))
        assertNull(store.pending("a"))
        assertEquals(TruncationKind.TEXT, store.pending("b"))
    }

    @Test
    fun `cold start restores the marker from persisted prefs`() {
        val prefs = MemoryPrefs()
        TruncationResumeStore(prefs).mark("s1", TruncationKind.TEXT)
        // 模拟进程重启：同一份磁盘数据，新的 store 实例
        val reopened = TruncationResumeStore(prefs)
        assertEquals(TruncationKind.TEXT, reopened.pending("s1"))
    }

    @Test
    fun `unknown or corrupt stored values are ignored`() {
        val prefs = MemoryPrefs()
        prefs.values[TruncationResumeStore.key("s1")] = "WHATEVER"
        val store = TruncationResumeStore(prefs)
        assertNull(store.pending("s1"))
        assertNull(store.consume("s1"))
    }

    @Test
    fun `blank session ids are never persisted`() {
        val prefs = MemoryPrefs()
        val store = TruncationResumeStore(prefs)
        store.mark("", TruncationKind.TEXT)
        assertTrue(prefs.values.isEmpty())
        assertNull(store.pending(""))
    }

    @Test
    fun `resume prompt tells the model to continue and split writes`() {
        assertTrue(CONTINUE_TRUNCATED_PROMPT.contains("从中断处继续"))
        assertTrue(CONTINUE_TRUNCATED_PROMPT.contains("分几段写"))
    }

    @Test
    fun `notice is actionable and carries the limit and queue hint`() {
        val text = truncationResumeNotice(
            kind = TruncationKind.TOOL_CALL,
            outputTokenLimit = 8192,
            queuedMessages = true,
        )
        assertTrue(text.contains("8192"))
        assertTrue("要点出工具没执行：$text", text.contains("工具调用没有执行"))
        assertTrue("要给出 ① 继续生成：$text", text.contains("继续生成"))
        assertTrue("要给出 ② 分段写：$text", text.contains("分几段写"))
        assertTrue("要给出 ③ 重发说明分段：$text", text.contains("重发"))
        assertTrue("普通会话也要提示待发送消息保留：$text", text.contains("待发送消息已保留"))
    }

    @Test
    fun `notice omits limit and queue hint when not applicable`() {
        val text = truncationResumeNotice(
            kind = TruncationKind.TEXT,
            outputTokenLimit = 0,
            queuedMessages = false,
        )
        assertTrue(!text.contains("tokens"))
        assertTrue(!text.contains("待发送消息已保留"))
        assertTrue(text.contains("回复被截断"))
    }
}

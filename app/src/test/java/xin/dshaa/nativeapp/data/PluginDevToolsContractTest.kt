package xin.dshaa.nativeapp.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.4（用户 2026-11-15 第 8 条）：启停 / 删除都按**写工具**走现有审批卡。
 *
 * 审批是宿主按工具名分类的（`READ_ONLY_TOOLS` 之外的都弹卡），所以这里锁住契约：
 * `plugin_dev_delete` 必须在写工具名单里、且不在只读名单里。
 */
class PluginDevToolsContractTest {

    @Test fun enableAndDeleteAreWriteTools() {
        assertTrue("plugin_dev_enable" in PluginDevTools.writeNames)
        assertTrue("plugin_dev_delete" in PluginDevTools.writeNames)
    }

    @Test fun deleteIsNeverWhitelistedAsReadOnly() {
        assertFalse("plugin_dev_delete" in PluginDevTools.readOnlyNames)
        assertFalse("plugin_dev_enable" in PluginDevTools.readOnlyNames)
        // 只读名单仍只包含真正的查询类
        assertTrue("plugin_dev_list" in PluginDevTools.readOnlyNames)
        assertTrue("plugin_dev_test" in PluginDevTools.readOnlyNames)
    }
}

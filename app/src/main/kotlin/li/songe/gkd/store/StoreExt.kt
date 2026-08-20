package li.songe.gkd.store

import kotlinx.coroutines.Dispatchers
import li.songe.gkd.appScope
import li.songe.gkd.util.launchTry

/**
 * 公开版：这个文件原本还有 actionCountFlow / blockMatchAppListFlow / blockA11yAppListFlow /
 * a11yScopeAppListFlow / checkAppBlockMatch / switchStoreEnableMatch / updateEnableAutomator，
 * 全部是 GKD 内核（去广告）的规则匹配与无障碍白名单状态。内核已整个删除，这些一并去掉。
 *
 * 保留的只有 storeFlow（深色模式/动态取色，见 SettingsStore）和 initStore()。
 * createAnyFlow / createTextFlow 等底层实现在 StorageExt.kt，未改动，提词器等功能仍在用。
 */

val storeFlow by lazy {
    createAnyFlow(
        key = "store",
        default = { SettingsStore() }
    )
}

fun initStore() = appScope.launchTry(Dispatchers.IO) {
    // preload
    storeFlow.value
}

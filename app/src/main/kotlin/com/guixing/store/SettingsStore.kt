package com.guixing.store

import kotlinx.serialization.Serializable

/**
 * 公开版：这个类原本是 GKD 内核的全量设置项（无障碍模式、订阅排序、快照、通知文案、更新渠道……
 * 三十多个字段），依赖 util/Option.kt 里的一批枚举。GKD 内核已整个删除，那些字段和枚举都没有了
 * 读写方，因此这里只保留公开版真正在用的两项——它们由 ui/style/Theme.kt 读取，控制深色模式和
 * 动态取色。
 *
 * 提词器、电话拦截、液态玻璃各自有独立的 DataStore（TeleprompterStore / CallGuardStore /
 * GlassTuningStore 等），不走这里，所以不受影响。
 */
@Serializable
data class SettingsStore(
    /** null = 跟随系统 */
    val enableDarkTheme: Boolean? = null,
    val enableDynamicColor: Boolean = true,
)

package li.songe.gkd

/**
 * 版本标识 —— 2026-08-20。
 *
 * 这里是**公开版**工程（F:\gkd-public），私人版是另一个独立文件夹（F:\gkd-fork）。两边不是同一个
 * 工程的两个构建变体，是两份各自独立的源码。这个文件是两边故意保持不一样的地方。
 *
 * 公开版已经**物理删除**（不是隐藏）以下功能的源码：
 * - 去广告（GKD 内核）：a11y、shizuku、notif、ui 下的功能界面、MainActivity 及其外部入口
 * - 虚拟定位：mockloc 包、mock-location.html
 * 因此下面两个 HAS_* 常量只起自说明和兜底作用，代码里已经不存在会读到它们的分支了。
 *
 * 改动提示：任何一边改了共用功能（提词器、电话拦截、定位修改、液态玻璃组件等），另一边要手工同步。
 * 这是选择"两个独立文件夹"方案时明确接受的代价，见 docs/DECISIONS.md 2026-08-20 那条。
 */
object Edition {
    /** 显示在启动页底部和「关于」浮层里 */
    const val LABEL = "公开版"

    /** 去广告（GKD 内核）—— 本版本已物理删除 */
    const val HAS_AD_SKIP = false

    /** 虚拟定位 —— 本版本已物理删除 */
    const val HAS_MOCK_LOCATION = false

    /** 开发者署名，用户 2026-08-20 指定，两个版本一致 */
    const val DEVELOPER = "癸星人"
}

package com.guixing.callscreen

import kotlinx.serialization.Serializable
import com.guixing.store.createAnyFlow

@Serializable
enum class CallGuardMode { Blacklist, Whitelist }

/** 黑名单/白名单是互斥的两个模式（同一时间只有一个生效），不是同时生效的两张独立表——但两个模式
 * 各自的前缀规则列表从一开始就分开存（[blacklistPrefixes]/[whitelistPrefixes]），不是共用一份
 * 列表靠 [mode] 字段过滤：切换模式不清空另一个列表，各自独立维护。这个形状是故意避免重蹈"调参预设
 * 不按 scope 隔离，加第二个入口后互相串扰"那个坑（见 docs/PITFALLS.md 对应条目）——用两个字段而不是
 * 一份带 mode 标记的扁平列表，从数据结构上就不可能串。 */
@Serializable
data class CallGuardStore(
    // 2026-08-28 - 全局总开关，**默认关闭**。关着的时候 shouldBlockCall 一律返回 false，
    // 来电走系统默认流程，等于这个功能不存在。默认关闭是有意的：拦截电话是"漏接真实来电"这种
    // 不可逆代价，必须由用户显式打开，不能靠装上就生效。
    val enabled: Boolean = false,
    val mode: CallGuardMode = CallGuardMode.Blacklist,
    val blacklistPrefixes: List<String> = emptyList(),
    val whitelistPrefixes: List<String> = emptyList(),
) {
    val activePrefixes: List<String>
        get() = when (mode) {
            CallGuardMode.Blacklist -> blacklistPrefixes
            CallGuardMode.Whitelist -> whitelistPrefixes
        }

    fun withActivePrefixes(mutate: (List<String>) -> List<String>): CallGuardStore = when (mode) {
        CallGuardMode.Blacklist -> copy(blacklistPrefixes = mutate(blacklistPrefixes))
        CallGuardMode.Whitelist -> copy(whitelistPrefixes = mutate(whitelistPrefixes))
    }
}

val callGuardStoreFlow = createAnyFlow(
    key = "callguard_store",
    default = { CallGuardStore() },
)

fun updateCallGuardStore(mutate: (CallGuardStore) -> CallGuardStore) {
    callGuardStoreFlow.value = mutate(callGuardStoreFlow.value)
}

/** 剥离 tel: scheme 拿到 [raw] 原始号码之后的标准化：去掉空格/横杠，去掉 +86/86 国际区号前缀。
 * 国内场景除了 11 位手机号，也可能是带区号的座机号（比如济南区号 0531 开头），所以不假设号码一定是
 * 11 位手机号格式，只做字符串清理，不做长度/格式校验——"86" 前缀只在剩余长度大于 10 位时才剥离，
 * 避免误伤本来就以 86 开头、但明显不是国际区号场景的短号码。 */
fun normalizeCallGuardNumber(raw: String): String {
    val stripped = raw.replace(Regex("[\\s-]"), "")
    return when {
        stripped.startsWith("+86") -> stripped.removePrefix("+86")
        stripped.startsWith("86") && stripped.length > 10 -> stripped.removePrefix("86")
        else -> stripped
    }
}

/** 命中当前生效模式的前缀规则时，黑名单=该拒接，白名单=不该放行（等价于也是拒接）——统一返回
 * "是否应该拒接"，调用方（[CallGuardScreeningService]）不需要关心具体是哪个模式。 */
fun shouldBlockCall(normalizedNumber: String, store: CallGuardStore): Boolean {
    // 总开关关着：什么都不拦。
    if (!store.enabled) return false
    val active = store.activePrefixes.filter { it.isNotBlank() }
    // 2026-08-28 - 规则列表为空时**一律放行**，两个模式都是。
    //
    // 黑名单空 = 没有任何号码被加进来，本来就不该拦（用户原话："没有加入黑名单的不要拦截"）。
    // 白名单空更危险：按 !matched 的原始逻辑会变成"拦截所有来电"——一个刚切到白名单还没来得及
    // 填号码的用户会瞬间屏蔽全部电话。漏接真实来电是不可逆的，这里选择失败即放行。
    if (active.isEmpty()) return false
    val matched = active.any { prefix -> normalizedNumber.startsWith(prefix) }
    return when (store.mode) {
        CallGuardMode.Blacklist -> matched
        CallGuardMode.Whitelist -> !matched
    }
}

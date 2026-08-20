package li.songe.gkd.callscreen

import android.telecom.Call
import android.telecom.CallScreeningService

/** 平台可用性验证已通过 (2026-08-16，见 docs/STATE.md) - 现在接上真实的黑白名单判断。
 *
 * `setSkipNotification`/`setSkipCallLog` 这两项按用户要求顺手加上了，但 AOSP 文档里提到有一些
 * CallResponse 选项只有当这个 service 的 uid 跟系统默认拨号应用一致时才会被遵守——这个 app 不是
 * 默认拨号应用，这两项在真机上是否真的生效目前还没专门验证过，不要假设它们一定起作用，以真机日志/
 * 实际通知栏观察为准。
 *
 * 已知限制，不处理：CallScreeningService 接口本身拦不到已存在系统通讯录里的联系人来电，命中黑名单
 * 前缀的号码如果同时是已存联系人，电话依然会正常响铃——这不是这里的 bug。
 *
 * Logs through [CallGuardObserveLog], not a bare LogUtils.d call - adb wasn't convenient for
 * on-device verification, and this way every real screening decision shows up in the in-app log
 * viewer (CallGuardActivity -> 查看诊断日志) too, copyable without a cable. */
class CallGuardScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val rawNumber = callDetails.handle?.schemeSpecificPart ?: ""
        val normalized = normalizeCallGuardNumber(rawNumber)
        val store = callGuardStoreFlow.value
        val block = shouldBlockCall(normalized, store)

        CallGuardObserveLog.log(
            "onScreenCall, raw=$rawNumber, normalized=$normalized, mode=${store.mode}, " +
                "activePrefixes=${store.activePrefixes}, block=$block",
        )

        val response = if (block) {
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipNotification(true)
                .setSkipCallLog(true)
                .build()
        } else {
            CallResponse.Builder().build()
        }
        respondToCall(callDetails, response)
    }
}

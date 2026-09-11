package com.guixing.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.guixing.META
import com.guixing.app

/**
 * 公开版：这个文件原本还有 shareFile(MainActivity 扩展)、openWeChatScaner、openA11ySettings、
 * openApp、stopServiceByClass、startForegroundServiceByClass、Intent.extraCptName 等，
 * 全部服务于已删除的 GKD 内核（无障碍、订阅分享、各类后台服务）。
 * 保留功能里只有 TeleprompterStore 用到 tryStartActivity，其余按需保留 openUri/应用详情页。
 */

fun Context.tryStartActivity(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        toast("未找到可打开的应用")
    } catch (e: Throwable) {
        toast(e.message ?: e.stackTraceToString())
    }
}

fun openUri(uri: String) {
    openUri(uri.toUriOrNull() ?: return)
}

fun openUri(uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    app.tryStartActivity(intent)
}

private fun String.toUriOrNull(): Uri? = runCatching { Uri.parse(this) }.getOrNull()

fun openAppDetailsSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", META.appId, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    app.tryStartActivity(intent)
}

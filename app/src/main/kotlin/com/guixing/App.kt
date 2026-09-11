package com.guixing

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Application
import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import com.guixing.store.initStore
import com.guixing.util.AndroidTarget
import com.guixing.util.LogUtils
import com.guixing.util.deviceInfoDesc
import com.guixing.util.initToast
import com.guixing.util.launchTry
import com.guixing.util.toast
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.system.exitProcess


val appScope by lazy { MainScope() }

private lateinit var innerApp: App
val app: App
    get() = innerApp

private val applicationInfo by lazy {
    app.packageManager.getApplicationInfo(
        app.packageName,
        PackageManager.GET_META_DATA
    )
}

// 公开版：GKD 内核的 data 包（含 selfAppInfo）已删除，版本号改为直接问 PackageManager。
private val selfPkgInfo by lazy {
    app.packageManager.getPackageInfo(app.packageName, 0)
}

private fun getMetaString(key: String): String {
    return applicationInfo.metaData.getString(key) ?: error("Missing meta-data: $key")
}

// https://github.com/android-cs/16/blob/main/packages/SettingsLib/src/com/android/settingslib/accessibility/AccessibilityUtils.java#L41
private const val ENABLED_ACCESSIBILITY_SERVICES_SEPARATOR = ':'

@Serializable
data class AppMeta(
    val channel: String = getMetaString("channel"),
    val commitId: String = getMetaString("commitId"),
    val commitTime: Long = getMetaString("commitTime").toLong(),
    val tagName: String? = getMetaString("tagName").takeIf { it.isNotEmpty() },
    val debuggable: Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    val versionCode: Int = selfPkgInfo.longVersionCode.toInt(),
    val versionName: String = selfPkgInfo.versionName!!,
    val appId: String = app.packageName!!,
    val appName: String = app.getString(R.string.app_name)
) {
    val commitUrl = "https://github.com/gkd-kit/gkd/".run {
        plus(if (tagName != null) "tree/$tagName" else "commit/$commitId")
    }
    val isGkdChannel get() = channel == "gkd"
    val updateEnabled get() = isGkdChannel
    val isBeta get() = versionName.contains("beta")
}

val META by lazy { AppMeta() }

fun contentObserver(listener: () -> Unit) = object : ContentObserver(null) {
    override fun onChange(selfChange: Boolean) = listener()
}

class App : Application() {
    companion object {
        const val START_WAIT_TIME = 3000L
    }

    init {
        innerApp = this
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (AndroidTarget.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }

    fun registerObserver(
        uri: Uri,
        observer: ContentObserver
    ) {
        contentResolver.registerContentObserver(uri, false, observer)
    }

    fun unregisterObserver(observer: ContentObserver) {
        contentResolver.unregisterContentObserver(observer)
    }

    fun getSecureString(name: String): String? = Settings.Secure.getString(contentResolver, name)
    fun putSecureString(name: String, value: String?): Boolean {
        return Settings.Secure.putString(contentResolver, name, value)
    }

    fun putSecureInt(name: String, value: Int): Boolean {
        return Settings.Secure.putInt(contentResolver, name, value)
    }

    fun getSecureA11yServices(): MutableSet<ComponentName> {
        val value = getSecureString(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (value.isNullOrEmpty()) return mutableSetOf()
        return value.split(
            ENABLED_ACCESSIBILITY_SERVICES_SEPARATOR
        ).mapNotNull { ComponentName.unflattenFromString(it) }.toHashSet()
    }

    fun putSecureA11yServices(services: Set<ComponentName>) {
        putSecureString(
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            services.joinToString(ENABLED_ACCESSIBILITY_SERVICES_SEPARATOR.toString()) { it.flattenToShortString() }
        )
    }

    fun resolveAppId(intent: Intent): String? {
        return intent.resolveActivity(packageManager)?.packageName
    }

    fun getPkgInfo(appId: String): PackageInfo? = try {
        packageManager.getPackageInfo(appId, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun resolveAppId(action: String, category: String? = null): String? {
        val intent = Intent(action)
        if (category != null) {
            intent.addCategory(category)
        }
        return resolveAppId(intent)
    }

    fun startLaunchActivity() {
        val intent = packageManager.getLaunchIntentForPackage(META.appId)!!
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        startActivity(intent)
    }

    fun checkGrantedPermission(permission: String) = ContextCompat.checkSelfPermission(
        this,
        permission,
    ) == PackageManager.PERMISSION_GRANTED

    val startTime = System.currentTimeMillis()
    var justStarted: Boolean = true
        get() {
            if (field) {
                field = System.currentTimeMillis() - startTime < START_WAIT_TIME
            }
            return field
        }

    val activityManager by lazy { app.getSystemService(ACTIVITY_SERVICE) as ActivityManager }
    val appOpsManager by lazy { app.getSystemService(APP_OPS_SERVICE) as AppOpsManager }
    val inputMethodManager by lazy { app.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager }
    val inputManager by lazy { app.getSystemService(INPUT_SERVICE) as InputManager }
    val windowManager by lazy { app.getSystemService(WINDOW_SERVICE) as WindowManager }
    val displayManager by lazy { app.getSystemService(DISPLAY_SERVICE) as DisplayManager }
    val keyguardManager by lazy { app.getSystemService(KEYGUARD_SERVICE) as KeyguardManager }
    val clipboardManager by lazy { app.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }
    val powerManager by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    val a11yManager by lazy { getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager }
    val launcherApps by lazy { getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps }

    val compatDisplay: Display
        get() = if (AndroidTarget.R) {
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }

    override fun onCreate() {
        super.onCreate()
        LogUtils.d()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            toast(e.message ?: e.toString())
            LogUtils.d("UncaughtExceptionHandler", t, e)
            // 公开版：崩溃记录原本写进 GKD 内核的数据库（CrashData 表），内核已删除，
            // 这里退化成只写日志文件，其余行为（延迟后拉起启动页、结束进程）保持一致。
            appScope.launchTry(Dispatchers.IO) {
                LogUtils.d("crash", deviceInfoDesc, Log.getStackTraceString(e))
                delay(1500)
                runCatching { startLaunchActivity() }
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(0)
            }
        }
        // 公开版：只剩这两项。其余 initChannel/initAppState/initA11yFeat/initShizuku/
        // initSubsState/initA11yWhiteAppList/clearHttpSubs/syncFixState 都属于已删除的 GKD 内核。
        initToast()
        initStore()
    }
}

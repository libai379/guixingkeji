package com.guixing

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.guixing.observelog.WebToolObserveLog
import com.guixing.observelog.WebToolObserveLogScreen
import com.guixing.ui.style.AppTheme
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WebToolActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val sessions = ConcurrentHashMap<String, SaveSession>()

    // 日志那层的引用。开关做成 View 的 visibility 而不是 Compose 状态：返回键的处理在
    // onBackPressedDispatcher 里，那段代码看不见组合树内部的状态，而 visibility 它随时能读。
    private var observeLogView: ComposeView? = null

    private fun setObserveLogVisible(visible: Boolean) {
        observeLogView?.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun isObserveLogVisible() = observeLogView?.visibility == android.view.View.VISIBLE

    private class SaveSession(
        val outputStream: OutputStream,
        val uri: Uri,
        val isPending: Boolean,
        val legacyFile: File? = null,
        val mime: String = "",
    )

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        Log.d(
            "WebConsole",
            "fileChooserLauncher result, this=$this, resultCode=${result.resultCode}, " +
                "data=${result.data}, callback=$callback"
        )
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val clipData = data?.clipData
            val manualUris = when {
                clipData != null && clipData.itemCount > 0 -> {
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            // Manual parsing first: framework's parseResult() has been observed
            // returning null on some OEM ROMs even when clipData/data is valid.
            manualUris ?: WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
        } else {
            null
        }
        Log.d("WebConsole", "parsed uris=${uris?.joinToString { it.toString() }}")
        Log.d("WebConsole", "final uri count=${uris?.size ?: 0}")
        WebToolObserveLog.log(
            "选择文件返回 resultCode=${result.resultCode}, 文件数=${uris?.size ?: 0}",
        )
        callback?.onReceiveValue(uris)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(
            "WebConsole",
            "onCreate, this=$this, savedInstanceState=${savedInstanceState != null}"
        )
        val fileName = intent.getStringExtra(EXTRA_HTML_FILE)
        if (fileName == null) {
            finish()
            return
        }

        WebToolObserveLog.log("打开页面 $fileName")

        val webView = WebView(this)
        this.webView = webView
        // WebView + 一层 ComposeView 叠在同一个 FrameLayout 里：ComposeView 负责右下角那个日志
        // 按钮和展开后的日志界面。用叠层而不是新开一个 Activity，是为了不动 AndroidManifest
        // ——清单在同步脚本的 NEVER_COPY 里，改它意味着两个工程各改一遍。
        val root = FrameLayout(this)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // 日志界面单独一个铺满的 ComposeView，关闭时设成 GONE。**不能**用一个铺满的 ComposeView
        // 同时承载按钮和界面：铺满的那层即使画的是空白，也会挡在 WebView 前面参与触摸分发，地图选
        // 点会时灵时不灵。GONE 的 View 根本不进分发，这是唯一没有歧义的写法。
        val logView = ComposeView(this).apply {
            visibility = android.view.View.GONE
            setContent {
                AppTheme {
                    WebToolObserveLogScreen(onClose = { setObserveLogVisible(false) })
                }
            }
        }
        this.observeLogView = logView

        // 按钮那层只占自己那么大，靠 gravity 贴右下角，其余区域完全不在这层里，WebView 照常收触摸。
        val fabView = ComposeView(this).apply {
            setContent {
                AppTheme {
                    SmallFloatingActionButton(onClick = { setObserveLogVisible(true) }) {
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = "诊断日志",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        root.addView(
            logView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            fabView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.END,
            ).apply {
                val margin = (16 * resources.displayMetrics.density).toInt()
                rightMargin = margin
                bottomMargin = margin
            },
        )
        setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.addJavascriptInterface(AndroidSaveFileBridge(), "AndroidSaveFile")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                Log.d(
                    "WebConsole",
                    "onShowFileChooser called, this=${this@WebToolActivity}, existingCallback=${this@WebToolActivity.filePathCallback}"
                )
                this@WebToolActivity.filePathCallback?.onReceiveValue(null)
                this@WebToolActivity.filePathCallback = filePathCallback
                val multiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                // Deliberately not using fileChooserParams.createIntent(): that routes through
                // ACTION_GET_CONTENT's Photo Picker compat shim (PhotopickerGetContentActivity),
                // which has a reduced feature set — no HEIC, different sort order, missing items
                // compared to the real Photo Picker (MediaStore.ACTION_PICK_IMAGES).
                val chooserIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                        if (multiple) {
                            putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
                        }
                    }
                } else {
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
                    }
                }
                return try {
                    fileChooserLauncher.launch(chooserIntent)
                    true
                } catch (e: Exception) {
                    Log.d("WebConsole", "fileChooserLauncher.launch threw: $e")
                    this@WebToolActivity.filePathCallback = null
                    false
                }
            }

            override fun onConsoleMessage(message: String?, lineNumber: Int, sourceID: String?) {
                Log.d("WebConsole", "$message ($sourceID:$lineNumber)")
                // 页面本身的 console 就是这个模块最有价值的日志：Kotlin 侧只是个壳，选点/读写 EXIF
                // 的实际过程全在 JS 里。sourceID 是完整的 file:// 路径，只留文件名，否则每行都被
                // 一长串前缀顶到屏幕外。
                WebToolObserveLog.log("[页面] $message (${sourceID?.substringAfterLast('/')}:$lineNumber)")
            }
        }

        // Handles standard http(s) navigations that trigger a download.
        // NOTE: does not fire for blob: URLs, those are handled by the AndroidSaveFile JS bridge instead.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessedName)
                val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)
                Toast.makeText(this, "开始下载: $guessedName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        webView.loadUrl("file:///android_asset/web/$fileName")

        onBackPressedDispatcher.addCallback(this) {
            // 日志界面开着时，返回键先关它——顺序放在网页那层之前，否则会直接退出整个功能，
            // 用户看着日志按返回却整个页面没了。
            if (isObserveLogVisible()) {
                setObserveLogVisible(false)
                return@addCallback
            }
            // Let the page decide first (e.g. exit fullscreen player back to editor);
            // only finish the Activity if it reports it didn't consume the back press.
            webView.evaluateJavascript(
                "(function(){ if (typeof window.handleNativeBack === 'function') { return !!window.handleNativeBack(); } return false; })();"
            ) { result ->
                if (result != "true") {
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d("WebConsole", "onDestroy, this=$this, isFinishing=$isFinishing, isChangingConfigurations=$isChangingConfigurations")
        sessions.keys.toList().forEach { sessionId ->
            sessions.remove(sessionId)?.let { session ->
                try {
                    session.outputStream.close()
                } catch (e: Exception) {
                }
                if (session.isPending) {
                    try {
                        contentResolver.delete(session.uri, null, null)
                    } catch (e: Exception) {
                    }
                }
            }
        }
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private inner class AndroidSaveFileBridge {
        @JavascriptInterface
        fun beginSave(name: String, mime: String): String {
            return try {
                val safeName = name.ifBlank { "file_${System.currentTimeMillis()}" }
                val isVideo = mime.startsWith("video/")
                val sessionId = UUID.randomUUID().toString()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val collection = if (isVideo) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    }
                    val relDir =
                        (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/GPS定位修改"
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(collection, values) ?: return ""
                    val stream = contentResolver.openOutputStream(uri) ?: run {
                        contentResolver.delete(uri, null, null)
                        return ""
                    }
                    sessions[sessionId] = SaveSession(stream, uri, true, mime = mime)
                } else {
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(
                            if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                        ),
                        "GPS定位修改"
                    )
                    if (!dir.exists() && !dir.mkdirs()) return ""
                    val file = File(dir, safeName)
                    val stream = FileOutputStream(file)
                    sessions[sessionId] = SaveSession(stream, Uri.fromFile(file), false, file, mime)
                }
                WebToolObserveLog.log("开始保存 name=$safeName, mime=$mime, session=${sessionId.take(8)}")
                sessionId
            } catch (e: Exception) {
                WebToolObserveLog.log("开始保存失败 name=$name, mime=$mime, err=$e")
                ""
            }
        }

        @JavascriptInterface
        fun writeChunk(sessionId: String, base64Chunk: String): Boolean {
            val session = sessions[sessionId] ?: return false
            return try {
                val bytes = Base64.decode(base64Chunk, Base64.NO_WRAP)
                session.outputStream.write(bytes)
                true
            } catch (e: Exception) {
                sessions.remove(sessionId)
                try {
                    session.outputStream.close()
                } catch (e2: Exception) {
                }
                if (session.isPending) {
                    try {
                        contentResolver.delete(session.uri, null, null)
                    } catch (e2: Exception) {
                    }
                }
                false
            }
        }

        @JavascriptInterface
        fun endSave(sessionId: String): String {
            val session = sessions.remove(sessionId) ?: return ""
            return try {
                session.outputStream.flush()
                session.outputStream.close()
                if (session.isPending) {
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    contentResolver.update(session.uri, values, null, null)
                } else if (session.legacyFile != null) {
                    MediaScannerConnection.scanFile(
                        this@WebToolActivity,
                        arrayOf(session.legacyFile.absolutePath),
                        arrayOf(session.mime),
                        null
                    )
                }
                WebToolObserveLog.log("保存完成 uri=${session.uri}")
                session.uri.toString()
            } catch (e: Exception) {
                WebToolObserveLog.log("保存失败 uri=${session.uri}, err=$e")
                try {
                    session.outputStream.close()
                } catch (e2: Exception) {
                }
                if (session.isPending) {
                    try {
                        contentResolver.delete(session.uri, null, null)
                    } catch (e2: Exception) {
                    }
                }
                ""
            }
        }

        @JavascriptInterface
        fun setOrientation(mode: String) {
            runOnUiThread {
                requestedOrientation = when (mode) {
                    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    companion object {
        const val EXTRA_HTML_FILE = "html_file"
    }
}

package com.guixing

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.guixing.teleprompter.TeleprompterEditorScreen
import com.guixing.teleprompter.TeleprompterLibraryScreen
import com.guixing.teleprompter.TeleprompterObserveLogScreen
import com.guixing.teleprompter.TeleprompterPlayerScreen
import com.guixing.teleprompter.TeleprompterVm
import com.guixing.teleprompter.describe
import com.guixing.teleprompter.importDocxAndCreateDocument
import com.guixing.ui.style.AppTheme
import com.guixing.util.LogUtils
import com.guixing.util.toast

private const val TAG = "Prompter"

private sealed interface TeleprompterScreen {
    data object Library : TeleprompterScreen
    data class Editor(val docId: String) : TeleprompterScreen
    data class Player(val docId: String) : TeleprompterScreen
    // No docId of its own (the log is global, not per-document) - carries where to return to on
    // close/back instead.
    data class ObserveLog(val returnTo: TeleprompterScreen) : TeleprompterScreen
}

private fun extractDocxUri(intent: Intent): Uri? {
    // Logged unconditionally, before any extraction - if a sender's intent shape doesn't match
    // what we expect below, this is the only way to see what it actually sent instead of guessing.
    LogUtils.d(intent, tag = TAG)
    val uri = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        else -> null
    }
    LogUtils.d("extractDocxUri result=$uri", tag = TAG)
    return uri
}

class TeleprompterActivity : ComponentActivity() {
    // Held on the Activity (not inside the Composable) so onNewIntent - which fires when this
    // Activity is already on screen and WeChat's "用其他应用打开" targets it again - can push a
    // new docx Uri in without needing to recreate the whole screen state.
    private val pendingImportUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingImportUri.value = extractDocxUri(intent)
        setContent {
            AppTheme {
                val vm = viewModel<TeleprompterVm>()
                var screen by remember { mutableStateOf<TeleprompterScreen>(TeleprompterScreen.Library) }
                val importUri by pendingImportUri

                LaunchedEffect(importUri) {
                    val uri = importUri ?: return@LaunchedEffect
                    // Do NOT clear pendingImportUri here: it's this same LaunchedEffect's own
                    // key, so mutating it mid-flight cancels and restarts the effect right away,
                    // silently killing the import after parsing but before the doc gets saved
                    // (that's exactly what happened - logs showed a clean parse and then nothing
                    // after it, no exception, because cancellation doesn't throw through a catch
                    // block placed earlier in the now-abandoned coroutine). Re-composition alone
                    // won't re-run this effect unless importUri's *value* actually changes, so
                    // there's no need to reset it back to null at all.
                    importDocxAndCreateDocument(vm, uri).fold(
                        onSuccess = { (docId, summary) ->
                            screen = TeleprompterScreen.Editor(docId)
                            toast(summary.describe())
                        },
                        onFailure = { e -> toast("Word 导入失败: ${e.message}") },
                    )
                }

                BackHandler(enabled = screen !is TeleprompterScreen.Library) {
                    screen = when (val s = screen) {
                        is TeleprompterScreen.Player -> TeleprompterScreen.Editor(s.docId)
                        is TeleprompterScreen.Editor -> TeleprompterScreen.Library
                        is TeleprompterScreen.ObserveLog -> s.returnTo
                        TeleprompterScreen.Library -> TeleprompterScreen.Library
                    }
                }

                when (val s = screen) {
                    TeleprompterScreen.Library -> TeleprompterLibraryScreen(
                        vm = vm,
                        onOpenDocument = { docId ->
                            vm.openDocument(docId)
                            screen = TeleprompterScreen.Editor(docId)
                        },
                        onClose = { finish() },
                    )

                    is TeleprompterScreen.Editor -> TeleprompterEditorScreen(
                        vm = vm,
                        docId = s.docId,
                        onOpenLibrary = { screen = TeleprompterScreen.Library },
                        onOpenPlayer = { screen = TeleprompterScreen.Player(s.docId) },
                        onOpenObserveLog = { screen = TeleprompterScreen.ObserveLog(returnTo = s) },
                    )

                    is TeleprompterScreen.Player -> TeleprompterPlayerScreen(
                        vm = vm,
                        docId = s.docId,
                        onClose = { screen = TeleprompterScreen.Editor(s.docId) },
                    )

                    is TeleprompterScreen.ObserveLog -> TeleprompterObserveLogScreen(
                        onClose = { screen = s.returnTo },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingImportUri.value = extractDocxUri(intent)
    }
}

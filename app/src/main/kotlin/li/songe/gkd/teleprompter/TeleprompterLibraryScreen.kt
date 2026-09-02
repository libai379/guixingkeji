package li.songe.gkd.teleprompter

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.backdrops.LayerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import li.songe.gkd.glassui.GlassBackgroundEditor
import li.songe.gkd.glassui.GlassBackgroundConfig
import li.songe.gkd.glassui.GlassBackgroundLayer
import li.songe.gkd.glassui.LiquidButton
import li.songe.gkd.glassui.LiquidCompactButton
import li.songe.gkd.glassui.LiquidCompactIconButton
import li.songe.gkd.glassui.LiquidDialog
import li.songe.gkd.glasstuning.GlassTarget
import li.songe.gkd.glasstuning.GlassTuningActivity
import li.songe.gkd.glasstuning.TeleprompterLibraryGlassTargets
import li.songe.gkd.glasstuning.liveGlassParamsFor
import li.songe.gkd.glasstuning.tunedGlass
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.toast

private const val TAG = "Prompter"

@Composable
fun TeleprompterLibraryScreen(
    vm: TeleprompterVm,
    onOpenDocument: (String) -> Unit,
    onClose: () -> Unit,
) {
    val activity = LocalActivity.current!!
    val library by vm.library.collectAsState()
    val scope = rememberCoroutineScope()

    var renameTarget by remember { mutableStateOf<TeleprompterDocument?>(null) }
    var deleteTarget by remember { mutableStateOf<TeleprompterDocument?>(null) }
    // Holds both the new docId and its summary - navigating away in the same click handler that
    // sets this would unmount this screen before the dialog ever got a chance to render, so the
    // dialog's own confirm button drives the navigation instead of the import callback doing it.
    var docxImportResult by remember { mutableStateOf<Pair<String, DocxImportSummary>?>(null) }
    var docxError by remember { mutableStateOf<String?>(null) }
    var bgPickerOpen by remember { mutableStateOf(false) }
    // Shared single "which row's menu is open" state instead of per-row local state - only ever
    // one of these on screen at a time, so one overlay at the screen level (not one per row).
    var menuForDocId by remember { mutableStateOf<String?>(null) }
    // The triggering "更多" button's own layout coordinates (captured via onGloballyPositioned in
    // DocumentRow), read once at open time - lets the menu anchor itself under that specific
    // button instead of screen-center (2026-08-15, user flagged the centered version as wrong).
    var menuAnchor by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (isDocxUri(uri)) {
            scope.launch {
                importDocxAndCreateDocument(vm, uri).fold(
                    onSuccess = { result -> docxImportResult = result },
                    onFailure = { e ->
                        LogUtils.d("docx import UI failed, uri=$uri", e, tag = TAG)
                        docxError = e.message ?: "导入失败"
                    },
                )
            }
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            importTeleprompterFile(uri).fold(
                onSuccess = { count -> toast("已导入 $count 篇文档") },
                onFailure = { e ->
                    LogUtils.d("import UI failed, uri=$uri", e, tag = TAG)
                    toast("导入失败: ${e.message}")
                },
            )
        }
    }

    // Capture source for every glass surface on this screen (top bar, FAB, row "更多" buttons,
    // row-menu overlay) - a sibling of all of them, never their ancestor, same rule as every other
    // screen in this module (circular-capture crashes RenderThread otherwise).
    val backdrop = rememberLayerBackdrop()

    Box(Modifier.fillMaxSize()) {
        GlassBackgroundLayer(library.libraryBackground, backdrop, Modifier.fillMaxSize())

        if (library.documents.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("点右下角新建一篇提词稿", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // top 90dp -> 130dp (2026-08-16, "第一个目录跟顶栏加大距离") - first row was sitting
                // too close under the floating top bar.
                contentPadding = PaddingValues(top = 130.dp, start = 8.dp, end = 8.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(library.documents, key = { it.id }) { doc ->
                    DocumentRow(
                        doc = doc,
                        backdrop = backdrop,
                        onClick = { onOpenDocument(doc.id) },
                        onMenuClick = { coords -> menuAnchor = coords; menuForDocId = doc.id },
                    )
                }
            }
        }

        // Floating top bar - same recipe as every other screen in this module
        // (Other target, chromaticAberration off since it overlaps real scrolling content).
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .tunedGlass(
                    backdrop = backdrop,
                    params = liveGlassParamsFor(GlassTarget.TeleprompterLibrary).copy(chromaticAberration = false),
                    cornerRadius = 20.dp,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidCompactIconButton(onClick = onClose, backdrop = backdrop, sampleBackdrop = false, inactiveTint = Color.Transparent) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "提词器",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            LiquidCompactIconButton(onClick = { bgPickerOpen = true }, backdrop = backdrop, sampleBackdrop = false, inactiveTint = Color.Transparent) {
                Icon(Icons.Filled.Image, contentDescription = "设置背景")
            }
            LiquidCompactIconButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                backdrop = backdrop,
                sampleBackdrop = false,
                inactiveTint = Color.Transparent,
            ) {
                Icon(Icons.Filled.SaveAlt, contentDescription = "导入")
            }
            LiquidCompactIconButton(
                onClick = {
                    if (library.documents.isEmpty()) {
                        toast("还没有文档")
                    } else {
                        activity.exportTeleprompterLibrary()
                    }
                },
                backdrop = backdrop,
                sampleBackdrop = false,
                inactiveTint = Color.Transparent,
            ) {
                Icon(Icons.Filled.IosShare, contentDescription = "备份全部")
            }
            // 2026-08-16 - 目录页自己的液态效果调节入口，跟编辑页/播放页各自独立
            // (TeleprompterLibraryGlassTargets)，不再共用同一份参数。
            LiquidCompactIconButton(
                onClick = {
                    activity.startActivity(
                        GlassTuningActivity.restrictedIntent(activity, TeleprompterLibraryGlassTargets),
                    )
                },
                backdrop = backdrop,
                sampleBackdrop = false,
                inactiveTint = Color.Transparent,
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "目录页液态效果设置")
            }
        }

        // 2026-08-16 - 之前用默认参数（真实采样折射身后内容），跟身后的文档列表"融在一块儿"分不
        // 清；active=true 给它换成主题 primaryContainer 底色，独立于身后内容之外单独一层液态。
        // 图标本身放大一档（默认约 24dp -> 28dp），看起来更粗一些。
        LiquidCompactIconButton(
            onClick = {
                val id = vm.createDocument()
                onOpenDocument(id)
            },
            backdrop = backdrop,
            size = 56.dp,
            active = true,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(24.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "新建文档", modifier = Modifier.size(28.dp))
        }

        // Row "更多" menu - same custom same-tree-overlay pattern as the player screen's settings
        // dropdown (not androidx.compose.ui.window's DropdownMenu/Popup - can't sample this tree's
        // backdrop, see that screen's own doc comment). Anchored under the button that triggered
        // it (2026-08-15, user flagged the earlier screen-centered version as wrong) via
        // menuAnchor's onGloballyPositioned coordinates, right-edge-aligned to the button so it
        // doesn't run off the right side of the screen (the button always sits at a row's right end).
        val menuDoc = library.documents.find { it.id == menuForDocId }
        val anchor = menuAnchor
        if (menuDoc != null && anchor != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = null, indication = null) { menuForDocId = null },
            )
            val density = LocalDensity.current
            val menuWidth = 220.dp
            val anchorPositionPx = anchor.positionInRoot()
            val menuX = with(density) { (anchorPositionPx.x + anchor.size.width).toDp() } - menuWidth
            val menuY = with(density) { (anchorPositionPx.y + anchor.size.height).toDp() } + 4.dp
            Column(
                modifier = Modifier
                    .offset(x = menuX.coerceAtLeast(0.dp), y = menuY)
                    .tunedGlass(
                        backdrop = backdrop,
                        params = liveGlassParamsFor(GlassTarget.TeleprompterLibrary).copy(chromaticAberration = false),
                        cornerRadius = 16.dp,
                    )
                    .padding(vertical = 4.dp)
                    .width(menuWidth),
            ) {
                RowMenuItem(Icons.Filled.Edit, "重命名", backdrop) {
                    menuForDocId = null
                    renameTarget = menuDoc
                }
                RowMenuItem(Icons.Filled.IosShare, "导出", backdrop) {
                    menuForDocId = null
                    activity.exportTeleprompterDocument(menuDoc)
                }
                RowMenuItem(Icons.Filled.Description, "导出为 Word", backdrop) {
                    menuForDocId = null
                    scope.launch {
                        activity.exportTeleprompterDocumentAsWord(menuDoc, menuDoc.effectiveSettings(library.settings))
                    }
                }
                RowMenuItem(Icons.Filled.Delete, "删除", backdrop) {
                    menuForDocId = null
                    deleteTarget = menuDoc
                }
            }
        } else if (menuForDocId != null) {
            // The doc it pointed at got deleted out from under it - just clear the dangling id.
            menuForDocId = null
        }

        // Last child of this Box = highest z-order + same composition as LibraryBackgroundLayer's
        // layerBackdrop(backdrop) capture above, so this panel's own tunedGlass() can actually
        // sample+refract the real background (a Dialog/Popup opens its own window and can't see
        // it - same reasoning as TeleprompterSettingsWindow in the editor screen).
        if (bgPickerOpen) {
            LibraryBackgroundPickerPopup(
                backdrop = backdrop,
                current = library.libraryBackground,
                onDismiss = { bgPickerOpen = false },
                onUpdate = { mutate -> vm.updateLibraryBackground(mutate) },
            )
        }
    }

    renameTarget?.let { doc ->
        var text by remember(doc.id) { mutableStateOf(doc.title) }
        LiquidDialog(onDismissRequest = { renameTarget = null }, title = "重命名", target = GlassTarget.TeleprompterLibrary) { _ ->
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.padding(24.dp, 4.dp, 24.dp, 12.dp).fillMaxWidth(),
            )
            Row(
                Modifier.padding(16.dp, 8.dp, 16.dp, 16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 2026-08-28 - 跟 LiquidDialog 的确认/取消按钮同批改成主题色按钮：弹窗卡片已是
                // 不透明实心，玻璃按钮在上面只会采样到被挡住的遮罩。
                OutlinedButton(onClick = { renameTarget = null }, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        vm.renameDocument(doc.id, text.ifBlank { doc.title })
                        renameTarget = null
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("确定") }
            }
        }
    }

    deleteTarget?.let { doc ->
        LiquidDialog(
            onDismissRequest = { deleteTarget = null },
            title = "删除「${doc.title}」？",
            text = "删除后无法恢复，除非之前导出过备份",
            confirmLabel = "删除",
            onConfirm = {
                vm.deleteDocument(doc.id)
                deleteTarget = null
            },
            target = GlassTarget.TeleprompterLibrary,
        )
    }

    docxImportResult?.let { (docId, summary) ->
        LiquidDialog(
            onDismissRequest = { docxImportResult = null },
            title = "Word 导入完成",
            text = summary.describe(),
            confirmLabel = "查看",
            onConfirm = {
                docxImportResult = null
                onOpenDocument(docId)
            },
            dismissLabel = "留在这里",
            target = GlassTarget.TeleprompterLibrary,
        )
    }

    docxError?.let { message ->
        LiquidDialog(
            onDismissRequest = { docxError = null },
            title = "Word 导入失败",
            text = message,
            confirmLabel = "好",
            onConfirm = { docxError = null },
            dismissLabel = null,
            target = GlassTarget.TeleprompterLibrary,
        )
    }

}

@Composable
private fun RowMenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, backdrop: Backdrop, onClick: () -> Unit) {
    LiquidCompactButton(
        onClick = onClick,
        backdrop = backdrop,
        sampleBackdrop = false,
        inactiveTint = Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DocumentRow(
    doc: TeleprompterDocument,
    backdrop: Backdrop,
    onClick: () -> Unit,
    onMenuClick: (LayoutCoordinates) -> Unit,
) {
    var menuButtonCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Each row is its own liquid-glass card (2026-08-16, user request: "每一条目录都要有一个轮廓，
    // 液态的轮廓") - previously a bare Row with no background/outline of its own, floating directly
    // on the page background with nothing to visually separate one document from the next.
    // sampleBackdrop left at its default (true, real sampling) since the row itself is the outermost
    // glass surface here, not nested inside another one - same reasoning as the FAB on this screen.
    // chromaticAberration off: this card overlaps real scrolling content (the background layer and
    // neighboring rows), same rule as every other newly-floating element in this codebase.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .tunedGlass(
                backdrop = backdrop,
                params = liveGlassParamsFor(GlassTarget.TeleprompterLibrary).copy(chromaticAberration = false),
                cornerRadius = 16.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(doc.title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${doc.chapters.size} 章节",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LiquidCompactIconButton(
            onClick = { menuButtonCoords?.let(onMenuClick) },
            backdrop = backdrop,
            sampleBackdrop = false,
            inactiveTint = Color.Transparent,
            modifier = Modifier.padding(end = 8.dp).onGloballyPositioned { menuButtonCoords = it },
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
        }
    }
}

// A same-tree Box overlay (not LiquidDialog/Popup) sized like TeleprompterSettingsWindow
// (fillMaxWidth(0.92f)/fillMaxHeight(0.5f)) - user asked for the same half-screen panel look
// after seeing the earlier small LiquidDialog version; height was still 0.85f (near-fullscreen,
// stale from before TeleprompterSettingsWindow itself got its own 0.85f->0.5f pass) until
// 2026-08-16, when the user pointed out this panel never actually got the true half-screen
// treatment. Must stay outside any Dialog/Popup window
// so its tunedGlass() can sample [backdrop] (the real library background behind it) - same
// reasoning as TeleprompterSettingsWindow's own doc comment. The body is the shared
// GlassBackgroundEditor (glassui/GlassBackground.kt) - this function is now just popup chrome
// (scrim + panel + entrance animation + close button) around it, not its own copy of the
// mode-switcher/per-mode controls.
@Composable
private fun LibraryBackgroundPickerPopup(
    backdrop: Backdrop,
    current: GlassBackgroundConfig,
    onDismiss: () -> Unit,
    onUpdate: ((GlassBackgroundConfig) -> GlassBackgroundConfig) -> Unit,
) {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)) }

    // BoxScope.align below needs a Box lexically wrapping both children - the call site being
    // inside the library screen's own root Box doesn't extend BoxScope across this function
    // boundary, same reason TeleprompterSettingsWindow wraps its own scrim+panel pair this way.
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f * entrance.value))
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )

        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.5f)
                .graphicsLayer {
                    alpha = entrance.value
                    val s = 0.9f + 0.1f * entrance.value
                    scaleX = s
                    scaleY = s
                }
                .clickable(interactionSource = null, indication = null, onClick = {})
                .tunedGlass(
                    backdrop = backdrop,
                    params = liveGlassParamsFor(GlassTarget.TeleprompterLibrary).copy(chromaticAberration = false),
                    cornerRadius = 28.dp,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp, 16.dp, 12.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("设置背景", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                LiquidCompactIconButton(onClick = onDismiss, backdrop = backdrop, sampleBackdrop = false) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            GlassBackgroundEditor(
                backdrop = backdrop,
                current = current,
                onUpdate = onUpdate,
                target = GlassTarget.TeleprompterLibrary,
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
            )
        }
    }
}

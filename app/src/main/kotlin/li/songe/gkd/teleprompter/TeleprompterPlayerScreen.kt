package li.songe.gkd.teleprompter

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import li.songe.gkd.glassui.LiquidCompactButton
import li.songe.gkd.glassui.LiquidCompactIconButton
import li.songe.gkd.glassui.LiquidSlider
import li.songe.gkd.glasstuning.GlassTarget
import li.songe.gkd.glasstuning.liveGlassParamsFor
import li.songe.gkd.glasstuning.tunedGlass
import li.songe.gkd.util.LogUtils

private const val TAG = "Prompter"

// "屏幕中间60%的阅读区/顶部+底部各20%的边缘条" - fractions of screen height, not pixel constants,
// so the split stays correct across phones/tablets/orientations.
private const val EDGE_ZONE_FRACTION = 0.2f
private const val AUTO_HIDE_DELAY_MS = 3000L
private const val PULSE_FADE_MS = 800

private fun orientationLabel(o: TeleprompterOrientation) = when (o) {
    TeleprompterOrientation.System -> "跟随系统"
    TeleprompterOrientation.Portrait -> "强制竖屏"
    TeleprompterOrientation.Landscape -> "强制横屏"
}

@Composable
fun TeleprompterPlayerScreen(
    vm: TeleprompterVm,
    docId: String,
    onClose: () -> Unit,
) {
    val library by vm.library.collectAsState()
    val doc = library.documents.find { it.id == docId }
    if (doc == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val settings = library.settings

    var isPlaying by remember { mutableStateOf(true) }
    var speed by remember { mutableStateOf(settings.scrollSpeed.coerceIn(0.2f, 3f)) }
    var mirrored by remember { mutableStateOf(settings.mirrored) }
    var outlineExpanded by remember { mutableStateOf(false) }
    var orientationMenuExpanded by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    // Ephemeral, session-only: always starts at 1.0 (the document's real configured size) each
    // time the player is opened, never persisted - "播放页固定用1.0x" as the resting/default
    // value, with live pinch as a temporary in-the-moment override for this viewing only.
    var playerZoom by remember { mutableFloatStateOf(1f) }
    var showOnboarding by remember { mutableStateOf(!settings.hasSeenPlayerOnboarding) }
    // Bumped on every tap (center or edge) - the auto-hide effect below is keyed on this, so
    // each tap restarts its 3-second countdown. Deliberately NOT keyed on controlsVisible itself
    // (see the bug this whole file is being redesigned around: mixing UI-visibility state into
    // the SCROLL effect's key silently cancelled it - the fix here is the same principle applied
    // everywhere in this screen, not just the scroll effect: no gesture/visibility state is ever
    // in the same key as playback state).
    var lastInteractionAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pulseIcon by remember { mutableStateOf<ImageVector?>(null) }
    // Bumped only when something else legitimately took over `listState`'s scroll out from under
    // the auto-scroll driver (currently: chapter jump - see jumpToChapter) and needs it to
    // restart cleanly afterward. Part of the scroll effect's key for exactly that reason - see
    // the effect below.
    var scrollResumeToken by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // "承认抢占，并对它做出反应": userScrollEnabled stays true (manual drag-to-reread is a hard
    // requirement), so a real drag legitimately takes listState's MutatorMutex away from the
    // auto-scroll coroutine below. Instead of fighting that, isDragging is part of the auto-scroll
    // effect's own key so a drag START cleanly cancels it (no competing for the mutex against the
    // user's own gesture) and drag END (once truly idle - see the effect body) cleanly resumes it,
    // all without ever touching isPlaying.
    val isDragging by listState.interactionSource.collectIsDraggedAsState()

    fun registerInteraction() {
        lastInteractionAt = System.currentTimeMillis()
    }

    // ==========================================================================================
    // Single entry point for isPlaying - "本项目第四次出现同类问题" root cause audit:
    // every place in this file that can start/stop the visible auto-scroll, and how each is
    // handled so state and the scroll coroutine can never disagree again:
    //
    //   1. Center-zone tap           -> togglePlaying() -> setPlaying()
    //   2. Bottom-bar play/pause btn -> togglePlaying() -> setPlaying()
    //   3. Auto-scroll reaching the end (canScrollForward=false) -> setPlaying(false) directly
    //   4. Edge-zone tap (show/hide controls) -> only touches controlsVisible, never isPlaying
    //      or listState - confirmed by reading every branch below.
    //   5. Chapter jump (outline panel) -> touches listState directly (has to, to move the
    //      scroll position) but NEVER isPlaying - see jumpToChapter's own comment for how it
    //      avoids the mutex-preemption trap instead of ignoring it.
    //   6. Manual drag-to-reread (LazyColumn's own built-in scroll gesture, userScrollEnabled
    //      stays true - "直播时看漏一句要能拖回去重读，这是刚需") -> also touches listState
    //      directly and NEVER isPlaying - see the isDragging key on the auto-scroll effect below.
    //   7. Speed +/- -> only touches `speed`, which is a legitimate scroll-effect key - a normal
    //      Compose-driven restart (cancel old effect, launch new one with the new speed), not an
    //      external mutex preemption, so this one was never actually broken.
    //   8. Orientation change / mirror toggle / outline panel open-close / onboarding dismiss ->
    //      none of them touch isPlaying or listState at all (verified by reading each handler).
    //   9. No lifecycle callback (onPause/onStop/etc.) currently touches isPlaying - noted here
    //      so the next person adding one knows to route it through setPlaying() too.
    //
    // The original bug (button lagging 2-3 taps behind real playback state) was #1/#2 falling
    // victim to #5/#6's same root cause even without an explicit jump/drag: LazyColumn's built-in
    // scroll-gesture recognizer shares listState's MutatorMutex with our programmatic auto-scroll,
    // and could silently preempt (cancel-without-exception) whichever `.scroll{}` call was already
    // running - killing the auto-scroll coroutine without ever touching isPlaying. The fix is not
    // to disable manual scrolling (that would remove the "拖回去重读" feature this project
    // actually needs) but to make setPlaying() the only thing that can EVER touch isPlaying, and
    // to make the auto-scroll effect itself aware of when it legitimately doesn't hold the mutex
    // (isDragging, scrollResumeToken) so it steps aside cleanly instead of racing.
    // ==========================================================================================
    fun setPlaying(playing: Boolean, source: String) {
        TeleprompterObserveLog.log("setPlaying($playing) from \"$source\", was ${if (isPlaying) "playing" else "paused"}")
        isPlaying = playing
    }

    fun togglePlaying(source: String) {
        val next = !isPlaying
        setPlaying(next, source)
        pulseIcon = if (next) Icons.Filled.PlayArrow else Icons.Filled.Pause
        controlsVisible = true
        registerInteraction()
    }

    fun jumpToChapter(chapterId: String) {
        val idx = doc.chapters.indexOfFirst { it.id == chapterId }
        TeleprompterObserveLog.log("player jump requested, chapterId=$chapterId, index=$idx")
        if (idx >= 0) {
            // Deliberately a separate coroutine, not routed through the auto-scroll effect's own
            // loop - animateScrollToItem legitimately needs to take over listState's mutex to
            // move the position (same as the tap-preemption mechanism above, but this time
            // intentional). The bump AFTER it completes (not before/concurrently, which would let
            // the two race and cut the jump animation short) is what makes the auto-scroll driver
            // restart cleanly afterward instead of staying silently dead like the old bug -
            // "播放状态要保持" now means the actual scroll resumes, not just that the isPlaying
            // flag looks right while nothing moves.
            scope.launch {
                listState.animateScrollToItem(idx + 1) // +1 to skip the leading spacer item
                TeleprompterObserveLog.log("player jump finished, chapterId=$chapterId, requesting scroll resume")
                scrollResumeToken++
            }
        }
        outlineExpanded = false
    }

    LaunchedEffect(docId) {
        TeleprompterObserveLog.log(
            "player entered, docId=$docId, chapters=${doc.chapters.size}, " +
                "chapterIds=${doc.chapters.map { it.id.take(8) }}",
        )
    }

    // Player-only screen orientation lock, restored to follow-system the moment this screen is
    // left.
    val activity = LocalActivity.current
    DisposableEffect(doc.orientation) {
        activity?.requestedOrientation = when (doc.orientation) {
            TeleprompterOrientation.System -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            TeleprompterOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            TeleprompterOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // True immersive fullscreen, player-only - restored the moment this screen is left so the
    // editor never inherits it. BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE keeps a swipe-from-edge
    // escape hatch (bars come back temporarily) instead of the more aggressive
    // sticky-immersive-with-no-escape behavior.
    val view = LocalView.current
    val orientation = LocalConfiguration.current.orientation
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // Some devices/OEMs restore the system bars on their own across a rotation - re-hide
    // whenever the configuration's orientation actually changes so it doesn't silently come back.
    LaunchedEffect(orientation) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    // BUG FIX (3rd time this exact class of bug has hit this project - see the import-uri and
    // chapter-hydration LaunchedEffect incidents earlier in this codebase's history): this key
    // list is playback state ONLY (isPlaying, speed). It must never include controlsVisible or
    // any other purely-visual state - doing so would silently cancel-and-restart this coroutine
    // (no exception, since structured-concurrency cancellation doesn't throw through a normal
    // catch) every time the user hides/shows controls, freezing the scroll while the play/pause
    // icon kept claiming "playing".
    LaunchedEffect(isPlaying, speed, isDragging, scrollResumeToken) {
        if (!isPlaying || isDragging) return@LaunchedEffect
        // isDragging (from listState's own interactionSource) only reflects the PRESS - it flips
        // back to false the instant the finger lifts, while LazyColumn's own fling/decay
        // animation from that gesture keeps running (and keeps holding listState's mutex) well
        // after that. Re-entering listState.scroll{} immediately at finger-lift would preempt and
        // cut that fling short instead of letting "甩动" settle on its own - so wait for the
        // list to be truly idle before taking the mutex back.
        snapshotFlow { listState.isScrollInProgress }.first { !it }
        // Self-healing retry loop - found via real on-device testing, not theory. Diagnosed with
        // temporary logging of the exception + isScrollInProgress at the failure point: EVERY
        // tap under userScrollEnabled=true (even a plain tap that never becomes a drag, and even
        // an edge-zone tap that only ever touches controlsVisible) throws "Mutation interrupted"
        // out of listState.scroll{} with isScrollInProgress=false at that instant - confirmed by
        // A/B on-device: zero preemptions over 6s with no touch at all, one preemption per tap
        // across a 10-tap run. This is LazyColumn's own scrollable gesture detector optimistically
        // claiming listState's MutatorMutex on every pointer-down (before it knows whether the
        // gesture will resolve into a real drag or "just a tap") and releasing it a moment later -
        // it preempts our lower/equal-priority auto-scroll for that instant regardless of what the
        // gesture turns out to be. isDragging (DragInteraction-based) never reports this, since no
        // real drag ever happened - so a key-based restart alone can't catch it: the coroutine
        // just dies with isPlaying still true and nothing left to restart it (confirmed on-device:
        // two screenshots 3s apart came back byte-identical - permanently stalled).
        // The fix is this retry loop: catch the CancellationException, use isActive to tell "just
        // listState.scroll{} got preempted" (still active - reclaim it) apart from "this whole
        // LaunchedEffect is being cancelled" (not active - isPlaying/speed/isDragging/
        // scrollResumeToken changed, or left composition - rethrow and let it end normally). The
        // retry itself needs a REAL suspension before re-entering listState.scroll{}: an earlier
        // version retried with zero delay and produced a tight busy-loop (near-zero-latency
        // preempt->retry with no actual suspension - confirmed on-device: 33MB of log lines in
        // under a second, and all 10 taps of a test batch were dropped because the busy loop
        // appeared to starve input entirely). A single frame's wait is enough real suspension to
        // break that without being a noticeable stutter (the preemption itself is instantaneous -
        // isScrollInProgress is already false by the time we see it, so there's nothing to
        // actually wait out).
        while (true) {
            TeleprompterObserveLog.log(
                "scroll coroutine START, isPlaying=$isPlaying, speed=$speed, resumeToken=$scrollResumeToken",
            )
            try {
                listState.scroll {
                    while (true) {
                        withFrameMillis { }
                        scrollBy(1.2f * speed)
                        if (!listState.canScrollForward) {
                            setPlaying(false, "reached end")
                            LogUtils.d("player reached end, docId=$docId", tag = TAG)
                            return@scroll
                        }
                    }
                }
                TeleprompterObserveLog.log("scroll coroutine END, isPlaying=$isPlaying (reached end)")
                break
            } catch (e: CancellationException) {
                if (!isActive) {
                    TeleprompterObserveLog.log("scroll coroutine END, isPlaying=$isPlaying (effect cancelled)")
                    throw e
                }
                TeleprompterObserveLog.log("scroll coroutine preempted (${e.message}), reclaiming once idle")
                // The 150ms floor is deliberate, not just a backoff: reclaiming too fast (was
                // withFrameMillis{}, 1 frame) raced the SAME tap's own gesture recognition still
                // in flight and made taps stop reaching togglePlaying/edge-zone entirely on-device
                // (0/10 in a row) - worse than doing nothing. But a fixed delay alone isn't enough
                // either - found via real on-device testing of chapter-jump-while-playing: the
                // preemption here fires the INSTANT jumpToChapter's animateScrollToItem takes the
                // mutex, and that animation legitimately keeps running well past 150ms for a jump
                // spanning several chapters - reclaiming on a bare timer cut it short every time
                // (confirmed on-device: "player jump finished" never logged, scrollResumeToken
                // never bumped). So: wait out the floor first (covers the tap case, where nothing
                // else is really scrolling by then anyway), then ALSO wait for isScrollInProgress
                // to genuinely go idle (covers the jump/fling case) before taking the mutex back.
                delay(150)
                snapshotFlow { listState.isScrollInProgress }.first { !it }
            }
        }
    }

    // Auto-hide 3s after the last tap, but only while playing - "暂停时不自动隐藏（这时候用户多半
    // 在调设置）". Keyed on isPlaying + lastInteractionAt only, same discipline as above: this
    // effect DRIVES controlsVisible, so controlsVisible itself must not be a key (that would be
    // the same self-referential-key bug in a different shape).
    LaunchedEffect(isPlaying, lastInteractionAt) {
        if (!isPlaying) return@LaunchedEffect
        delay(AUTO_HIDE_DELAY_MS)
        controlsVisible = false
    }

    // "0.8秒淡出" - the big center play/pause flash on toggle.
    val pulseAlpha = remember { Animatable(0f) }
    LaunchedEffect(pulseIcon) {
        val icon = pulseIcon ?: return@LaunchedEffect
        pulseAlpha.snapTo(1f)
        pulseAlpha.animateTo(0f, animationSpec = tween(PULSE_FADE_MS))
        if (pulseIcon == icon) pulseIcon = null
    }

    val bgColor = Color(settings.backgroundColorArgb.toInt())
    val textColor = Color(settings.textColorArgb.toInt())
    // playerZoom (pinch) multiplies the actual font size fed into the layout (reflow), not a
    // canvas scale - a graphicsLayer scale doesn't rewrap text, it just visually stretches the
    // block, which ran zoomed-in text off the edge of the screen instead of wrapping it.
    // settings.overallScale ("整体缩放") is the same idea but a persistent, user-set multiplier -
    // both apply here for text that has no explicit per-run size, AND separately to every
    // explicit per-run size cascaded in from Word import (see scaledForDisplay below), which
    // fontSizeSp alone can no longer reach now that imports carry explicit sizes.
    val displayFontSizeSp = settings.fontSizeSp * settings.overallScale * playerZoom
    val forcedAlign = settings.textAlignMode.toComposeAlignOrNull()
    // 2026-08-22 行距解耦（方案 A，见 TeleprompterTextScaling.kt 头部）——跟编辑页同一处理：
    // 倍数 1.0 时留空走逐行自然排版；否则这里只作为"段内无显式字号"段落的兜底。
    val bodyLineHeight = if (settings.lineHeightMultiplier == 1f) {
        TextUnit.Unspecified
    } else {
        (displayFontSizeSp * settings.lineHeightMultiplier).sp
    }
    val textStyle = TextStyle(
        color = textColor,
        fontSize = displayFontSizeSp.sp,
        lineHeight = bodyLineHeight,
        fontFamily = doc.fontFamily.toComposeFontFamily(),
        textAlign = forcedAlign ?: TextAlign.Unspecified,
    )
    // Subtitle size/weight/color are document-level (doc.subtitleStyle), not derived from the
    // body's fontSizeSp - "整体缩放" still reaches it (overallScale multiplies subtitleStyle's own
    // size too, same "multiply everything, don't override" model as the body), but 正文默认字号
    // (settings.fontSizeSp) does not, matching the settings-page copy change explaining exactly that.
    val subtitleDisplaySizeSp = doc.subtitleStyle.fontSizeSp * settings.overallScale * playerZoom
    val subtitleTextStyle = TextStyle(
        color = doc.subtitleStyle.colorArgb?.let { Color(it.toInt()) } ?: textColor,
        fontSize = subtitleDisplaySizeSp.sp,
        lineHeight = (subtitleDisplaySizeSp * settings.lineHeightMultiplier).sp,
        fontWeight = if (doc.subtitleStyle.bold) FontWeight.Bold else FontWeight.Normal,
        fontFamily = doc.fontFamily.toComposeFontFamily(),
        textAlign = forcedAlign ?: TextAlign.Unspecified,
    )
    // Same fraction/orientation logic as the editor (see TeleprompterTextScaling's
    // contentWidthFraction) - "横屏文字铺满整行，眼睛横扫距离过大，提词器跟不上" applies even more
    // here than in the editor, since this is the screen actually being read from live.
    val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
    val contentWidthFraction = settings.contentWidthFraction(isLandscape)

    // Capture source for the bottom control bar's glass (task: "所有面板做我调试的液态效果") - lives
    // on the LazyColumn itself, a SIBLING of the control bar Row below within this same outer Box,
    // never an ancestor of it. An ancestor capturing a descendant that samples it back is a
    // circular layer reference that crashes RenderThread with a native stack overflow - confirmed
    // on-device via tombstone once already this session (see TeleprompterSettingsSheet's fix).
    val contentBackdrop = rememberLayerBackdrop()

    Box(
        Modifier
            .fillMaxSize()
            .background(bgColor)
            // Landscape is exactly where a notch/punch-hole is most likely to sit on a SIDE edge
            // instead of just the top - background still fills edge-to-edge (this comes after
            // .background() in the chain, so only the children's layout gets pushed in, not the
            // fill), but the content/control bars underneath it don't render into that dead zone.
            .displayCutoutPadding(),
    ) {
        LazyColumn(
            state = listState,
            // Manual drag-to-reread is a hard requirement ("直播时看漏一句要能拖回去重读") - stays
            // enabled (the default). The auto-scroll effect above reacts to isDragging instead of
            // this being disabled - see its own comment for why disabling it isn't the fix.
            modifier = Modifier
                .fillMaxSize()
                // Real scrolling text, captured for the control bar's glass to refract - painting
                // bgColor again here (redundant with the outer Box) so the captured layer has a
                // real background instead of transparency, since layerBackdrop only captures this
                // node's own subtree, not the ancestor Box's separately-painted background.
                .layerBackdrop(contentBackdrop)
                .background(bgColor)
                // Mirror is a genuine visual flip for the beam-splitter glass rig, so it stays a
                // graphicsLayer transform - only the magnitude (zoom) moved to textStyle above.
                .graphicsLayer { scaleX = if (mirrored) -1f else 1f }
                // Zone hit-testing deliberately lives HERE (LazyColumn's own modifier chain), not
                // on the outer Box - found via real on-device testing (not theory), three bugs
                // deep: (1) plain detectTapGestures() on the outer Box (Main pass - dispatched
                // child-before-parent) essentially never fired once userScrollEnabled=true was
                // restored below: LazyColumn's built-in scrollable gesture detector, a descendant
                // of that Box, saw the same Main-pass events first and evidently held onto them
                // long enough (as part of its own tap-vs-drag resolution) that a clean up event
                // never reached the ancestor Box's detector - confirmed with an explicit log
                // inside onTap that simply never printed. (2) Switching to PointerEventPass
                // .Initial on the outer Box fixed that, but the outer Box is also an ANCESTOR of
                // the top/bottom control-bar buttons (X/mirror/outline/settings/play-pause/speed
                // slider - separate sibling composables of LazyColumn, layered on top) - Initial
                // pass runs parent-to-child, so that fix made this handler intercept EVERY tap
                // anywhere on screen, including ones landing on those buttons (X sits in the top
                // 20% "edge zone" by screen position), completely breaking their own onClick -
                // confirmed on-device: tapping X repeatedly toggled controlsVisible but never
                // fired onClose. Moving the pointerInput here (LazyColumn's own modifier chain)
                // keeps the Initial-pass fix (still only races LazyColumn's OWN internal
                // scrollable) but does NOT, on its own, stop Initial pass from ALSO firing for
                // taps that land on a sibling on top of it - Compose dispatches Initial pass to
                // every node whose bounds contain the point, not just the topmost one; LazyColumn
                // fills the whole screen so its bounds always contain the tap regardless of what's
                // drawn over it. (3) So a tap on the speed slider (in the bottom 20% edge zone
                // while controls are visible) was STILL toggling controlsVisible off in parallel
                // with the slider's own drag handling, and that AnimatedVisibility exit transition
                // tore the slider out of composition mid-gesture, silently eating the drag before
                // it could apply a value - confirmed on-device: dragging the slider produced only
                // an "edge tap" log and zero speed change. Fixed by only ever toggling
                // controlsVisible ON here (never off) - the only content actually reachable
                // through the edge zones while controlsVisible is already true is real, on-top
                // sibling buttons (X/mirror/outline/settings/play-pause/speed), which should
                // always win untouched; there is nothing left in that space for this handler to
                // legitimately act on. Hiding controls again is handled by the 3s auto-hide effect
                // (or by explicitly leaving via X) instead of a second edge-tap direction.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(pass = PointerEventPass.Initial)
                        val up = waitForUpOrCancellation(pass = PointerEventPass.Initial) ?: return@awaitEachGesture
                        if (showOnboarding) return@awaitEachGesture
                        val offset = up.position
                        val topEdge = size.height * EDGE_ZONE_FRACTION
                        val bottomEdge = size.height * (1 - EDGE_ZONE_FRACTION)
                        if (offset.y < topEdge || offset.y > bottomEdge) {
                            if (!controlsVisible) {
                                controlsVisible = true
                                TeleprompterObserveLog.log("edge tap, offset=$offset, controlsVisible -> true")
                                registerInteraction()
                            }
                        } else {
                            togglePlaying("center tap")
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        playerZoom = (playerZoom * zoomChange).coerceIn(0.5f, 3f)
                    }
                },
            contentPadding = PaddingValues(horizontal = 24.dp),
        ) {
            item { Spacer(Modifier.height(320.dp)) }
            itemsIndexed(doc.chapters, key = { _, ch -> ch.id }) { index, chapter ->
                val readState = rememberRichTextState()
                LaunchedEffect(chapter.contentHtml) {
                    TeleprompterObserveLog.log(
                        "player chapter setHtml before, index=$index, chapterId=${chapter.id.take(8)}, " +
                            "subtitle=\"${chapter.subtitle}\", contentHtml len=${chapter.contentHtml.length}",
                    )
                    readState.setHtml(chapter.contentHtml)
                    TeleprompterObserveLog.log(
                        "player chapter setHtml after, index=$index, chapterId=${chapter.id.take(8)}, " +
                            "state.annotatedString.text len=${readState.annotatedString.text.length}",
                    )
                }
                // Read-only display transform: rescales this chapter's explicit per-run fontSize
                // and per-paragraph lineHeight (both cascaded in from Word import) by the
                // settings-page multipliers, and forces alignment if the setting isn't
                // FollowDocument. Never fed back into readState - display-only, per
                // TeleprompterTextScaling.kt's own doc.
                val displayText = remember(
                    readState.annotatedString, settings.overallScale, settings.lineHeightMultiplier,
                    forcedAlign, displayFontSizeSp,
                ) {
                    readState.annotatedString.scaledForDisplay(
                        sizeScale = settings.overallScale,
                        lineHeightScale = settings.lineHeightMultiplier,
                        forcedAlign = forcedAlign,
                        fallbackFontSizeSp = displayFontSizeSp,
                    )
                }
                Box(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier
                            .fillMaxWidth(contentWidthFraction)
                            .align(Alignment.TopCenter)
                            .padding(bottom = 56.dp),
                    ) {
                        Text(
                            chapter.subtitle,
                            style = subtitleTextStyle,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        Text(text = displayText, style = textStyle, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item { Spacer(Modifier.height(480.dp)) }
        }

        // Center play/pause flash - "直播时要能立刻确认操作生效了".
        if (pulseIcon != null) {
            Icon(
                pulseIcon!!,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(96.dp)
                    .graphicsLayer { alpha = pulseAlpha.value },
            )
        }

        // Same recipe as the bottom control bar below (defaultGlassParamsFor(Other) with
        // chromaticAberration off, contentBackdrop reused rather than a second capture) - "同一套
        //配方，不要另起一套" - 2026-08-14. sampleBackdrop=false on every button: this Row is
        // already its own tunedGlass() surface.
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                modifier = Modifier.tunedGlass(
                    backdrop = contentBackdrop,
                    params = liveGlassParamsFor(GlassTarget.TeleprompterPlayer).copy(chromaticAberration = false),
                    cornerRadius = 999.dp,
                ),
            ) {
                LiquidCompactIconButton(onClick = onClose, backdrop = contentBackdrop, sampleBackdrop = false, inactiveTint = Color.Transparent) {
                    Icon(Icons.Filled.Close, contentDescription = "退出播放")
                }
                LiquidCompactIconButton(
                    onClick = {
                        mirrored = !mirrored
                        vm.updateSettings { it.copy(mirrored = mirrored) }
                        registerInteraction()
                    },
                    backdrop = contentBackdrop,
                    sampleBackdrop = false,
                    inactiveTint = Color.Transparent,
                ) {
                    Icon(Icons.Filled.Flip, contentDescription = "镜像翻转")
                }
                LiquidCompactIconButton(
                    onClick = { orientationMenuExpanded = true; registerInteraction() },
                    backdrop = contentBackdrop,
                    sampleBackdrop = false,
                    inactiveTint = Color.Transparent,
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "屏幕方向")
                }
            }
        }

        // Custom same-tree dropdown, not androidx.compose.ui.window's DropdownMenu (a Popup - its
        // own separate window, can't sample this tree's contentBackdrop, same limitation that
        // ruled Popup out for the teleprompter settings window - see that screen's own doc
        // comment). Full-screen scrim behind it catches the outside-tap-to-dismiss same way the
        // settings window does; the menu card itself is a fixed offset near the settings icon
        // rather than pixel-anchored to it, matching how the settings window isn't anchored to its
        // own trigger either - not worth the extra plumbing for a menu this small.
        if (orientationMenuExpanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = null, indication = null) { orientationMenuExpanded = false },
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 60.dp, start = 12.dp)
                    .tunedGlass(
                        backdrop = contentBackdrop,
                        params = liveGlassParamsFor(GlassTarget.TeleprompterPlayer).copy(chromaticAberration = false),
                        cornerRadius = 16.dp,
                    )
                    .padding(vertical = 4.dp)
                    .width(IntrinsicSize.Max),
            ) {
                TeleprompterOrientation.entries.forEach { option ->
                    LiquidCompactButton(
                        onClick = {
                            orientationMenuExpanded = false
                            vm.updateDocumentOrientation(doc.id, option)
                        },
                        backdrop = contentBackdrop,
                        sampleBackdrop = false,
                        inactiveTint = Color.Transparent,
                        active = doc.orientation == option,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        if (doc.orientation == option) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        } else {
                            Spacer(Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(orientationLabel(option), modifier = Modifier.weight(1f))
                    }
                }
                LiquidCompactButton(
                    onClick = {
                        orientationMenuExpanded = false
                        showOnboarding = true
                    },
                    backdrop = contentBackdrop,
                    sampleBackdrop = false,
                    inactiveTint = Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Filled.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("再看一次操作说明", modifier = Modifier.weight(1f))
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    // chromaticAberration off - confirmed on-device (2026-08-12): with it on and
                    // a light player background, this bar showed the same rainbow-static
                    // corruption the outline drawer once did, even through patchedLens()'s
                    // clamp. The clamp only stops sampling outside the captured buffer; it
                    // doesn't stop the dispersion offset itself from separating red/blue far
                    // enough, against real high-contrast text, to read as visible color
                    // separation rather than subtle fringing while still fully in-bounds. Same
                    // root cause as the settings window's own chromaticAberration=false - see
                    // TeleprompterEditorScreen.kt's TeleprompterSettingsWindow for the fuller
                    // writeup.
                    .tunedGlass(
                        backdrop = contentBackdrop,
                        params = liveGlassParamsFor(GlassTarget.TeleprompterPlayer).copy(chromaticAberration = false),
                        cornerRadius = 999.dp,
                    )
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { togglePlaying("bottom bar button") }) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = textColor,
                    )
                }
                // Was +/- stepper buttons - "直播时太费劲", replaced with a slider matching
                // the settings page's range, that applies while dragging (onValueChange, not
                // onValueChangeFinished) so the scroll speed reacts live instead of only on
                // release. Sized wide/tall for a quick, imprecise thumb during a live show.
                Text(
                    "%.1fx".format(speed),
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                )
                LiquidSlider(
                    value = { speed },
                    onValueChange = { v ->
                        speed = v.coerceIn(0.2f, 3f)
                        registerInteraction()
                    },
                    backdrop = contentBackdrop,
                    valueRange = 0.2f..3f,
                    modifier = Modifier.width(220.dp).height(40.dp),
                    onValueChangeFinished = { registerInteraction() },
                )
            }
        }

        TeleprompterOutlinePanel(
            backdrop = contentBackdrop,
            expanded = outlineExpanded,
            onExpandedChange = { outlineExpanded = it; registerInteraction() },
            chapters = doc.chapters,
            onJump = { chapterId -> jumpToChapter(chapterId) },
            showTrigger = false,
            target = GlassTarget.TeleprompterPlayer,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
        )

        // Standalone side trigger for the outline (2026-08-15, replaces the old top-bar icon per
        // user request) - always present regardless of controlsVisible (dimmed instead of fully
        // hidden, so it stays reachable while controls are hidden). Now the shared
        // OutlineScrubTrigger (TeleprompterEditorScreen.kt, 2026-08-16 extraction, see its own doc
        // comment) - the editor screen's outline drawer trigger got the identical long-press-drag-
        // scrub behavior at the same time, so this logic lives in exactly one place.
        // 2026-08-16 (reverted the same day's earlier toggle experiment): hides while the drawer
        // is open again - dismissal is outside-tap-to-close (TeleprompterOutlinePanel's own
        // scrim) only, tapping the trigger again to close it was explicitly dropped.
        if (!outlineExpanded && doc.chapters.isNotEmpty()) {
            val triggerAlpha by animateFloatAsState(if (controlsVisible) 1f else 0.35f, label = "outlineTriggerAlpha")
            OutlineScrubTrigger(
                backdrop = contentBackdrop,
                chapters = doc.chapters,
                currentIndex = { (listState.firstVisibleItemIndex - 1).coerceAtLeast(0) },
                onOpenFull = { outlineExpanded = true },
                onJumpToIndex = { index -> doc.chapters.getOrNull(index)?.let { jumpToChapter(it.id) } },
                dimAlpha = triggerAlpha,
                textColor = textColor,
                onInteraction = { registerInteraction() },
                target = GlassTarget.TeleprompterPlayer,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            )
        }

        if (showOnboarding) {
            PlayerOnboardingOverlay(
                onDismiss = {
                    showOnboarding = false
                    vm.updateSettings { it.copy(hasSeenPlayerOnboarding = true) }
                },
            )
        }
    }
}

/**
 * First-launch tutorial for the tap zones, laid out with the exact same 20/60/20 weights as the
 * real hit-testing above it so the labels line up with where taps actually land. Consumes all
 * taps itself (only the "知道了" button responds) so it can't misfire the real player underneath.
 */
@Composable
private fun PlayerOnboardingOverlay(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .pointerInput(Unit) { detectTapGestures {} },
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(EDGE_ZONE_FRACTION)
                    .fillMaxWidth()
                    .border(width = 1.dp, color = Color.White.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("点击显示 / 隐藏控件", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            Box(Modifier.weight(1 - 2 * EDGE_ZONE_FRACTION).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("点击 播放 / 暂停", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            Box(
                Modifier
                    .weight(EDGE_ZONE_FRACTION)
                    .fillMaxWidth()
                    .border(width = 1.dp, color = Color.White.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("点击显示 / 隐藏控件", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
        ) {
            Text("知道了")
        }
    }
}

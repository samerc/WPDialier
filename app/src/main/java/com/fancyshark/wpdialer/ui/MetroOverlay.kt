package com.fancyshark.wpdialer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Widest a centered overlay panel (or the main scaffold) grows on tablets. */
val MetroMaxContentWidth = 600.dp

/**
 * Hides a subtree from TalkBack while an overlay covers it — Compose has no
 * modal semantics, so the content beneath must opt out itself.
 */
fun Modifier.hiddenFromAccessibility(hidden: Boolean): Modifier =
    if (hidden) this.clearAndSetSemantics {} else this

/**
 * False under a host that animates a full-page overlay itself (the setup
 * wizard / what's-new slide up) — its MetroOverlay then skips the swivel.
 * Every overlay resets it for its own content, so nested dialogs still swivel.
 */
val LocalOverlaySwivel = staticCompositionLocalOf { true }

private const val SWIVEL_IN_MS = 260
private const val SWIVEL_OUT_MS = 150
private const val SWIVEL_ANGLE = -60f

/**
 * Swivel progress of one overlay: 0 = folded away, 1 = shown. Dismissals
 * routed through [dismiss] swivel out first and only then run the caller's
 * callback, so the caller's `if (show)` removal happens after the motion.
 */
private class OverlayMotion(
    val progress: Animatable<Float, AnimationVector1D>,
    private val scope: CoroutineScope,
    private val animate: Boolean,
) {
    private var dismissing = false

    fun dismiss(then: () -> Unit) {
        if (dismissing) return
        if (!animate) {
            then()
            return
        }
        dismissing = true
        scope.launch {
            progress.animateTo(0f, tween(SWIVEL_OUT_MS, easing = MetroEasing.Accelerate))
            then()
            // A caller that keeps the overlay up must not be left with an
            // invisible one. Wait a few frames first: the usual removal
            // cancels this scope before then.
            repeat(3) { withFrameNanos { } }
            dismissing = false
            progress.snapTo(1f)
        }
    }
}

@Composable
private fun rememberOverlayMotion(): OverlayMotion {
    val animate = LocalOverlaySwivel.current && !Metro.reduceMotion
    val scope = rememberCoroutineScope()
    val motion = remember { OverlayMotion(Animatable(if (animate) 0f else 1f), scope, animate) }
    LaunchedEffect(motion) {
        motion.progress.animateTo(1f, tween(SWIVEL_IN_MS, easing = MetroEasing.Decelerate))
    }
    return motion
}

/** WP swivel: folds in around a horizontal axis at the top edge, with a fade. */
private fun Modifier.swivel(motion: OverlayMotion): Modifier = graphicsLayer {
    val p = motion.progress.value
    alpha = p
    rotationX = SWIVEL_ANGLE * (1f - p)
    transformOrigin = TransformOrigin(0.5f, 0f)
    cameraDistance = 12f * density
}

/**
 * Full-screen overlay container. Swallows every touch so nothing beneath
 * can be tapped or scrolled "through" it (a background alone doesn't block
 * pointer input in Compose), and owns back while shown. Its content
 * swivels in on appear; dismissals stay immediate (full-page overlays
 * often keep themselves up on back, e.g. a wizard stepping back).
 *
 * [onBack] null leaves back to whoever handles it next; [onBackdropTap]
 * runs on taps that no child consumed (null = taps are just swallowed).
 */
@Composable
fun MetroOverlay(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onBackdropTap: (() -> Unit)? = null,
    background: Color = Metro.Background,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val motion = rememberOverlayMotion()
    OverlayFrame(onBack, modifier, onBackdropTap, background, contentAlignment, motion) {
        Box(
            Modifier.matchParentSize().swivel(motion),
            contentAlignment = contentAlignment,
            content = content,
        )
    }
}

/** The touch-swallowing, back-owning frame; the backdrop fades with [motion]. */
@Composable
private fun OverlayFrame(
    onBack: (() -> Unit)?,
    modifier: Modifier,
    onBackdropTap: (() -> Unit)?,
    background: Color,
    contentAlignment: Alignment,
    motion: OverlayMotion,
    content: @Composable BoxScope.() -> Unit,
) {
    if (onBack != null) BackHandler { onBack() }
    val backdropTap by rememberUpdatedState(onBackdropTap)
    Box(
        modifier
            .fillMaxSize()
            .drawBehind { drawRect(background, alpha = motion.progress.value) }
            .pointerInput(Unit) { detectTapGestures { backdropTap?.invoke() } }
            .semantics { isTraversalGroup = true },
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalOverlaySwivel provides true) {
            content()
        }
    }
}

/**
 * Dimmed overlay with a centered panel — the shape shared by every Metro
 * chooser and confirm. Taps on the panel never reach the backdrop. The
 * panel swivels in; back and backdrop taps swivel it out before
 * [onDismiss] runs (dismissals from the caller's own buttons are instant).
 */
@Composable
fun MetroDialog(
    onDismiss: () -> Unit,
    dismissOnBackdrop: Boolean = true,
    backdropAlpha: Float = 0.96f,
    horizontalPadding: Dp = 32.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    MetroDialogImpl(onDismiss, dismissOnBackdrop, backdropAlpha, horizontalPadding) { content() }
}

/** [MetroDialog] whose content gets the animated dismiss: `dismiss(callback)`. */
@Composable
private fun MetroDialogImpl(
    onDismiss: () -> Unit,
    dismissOnBackdrop: Boolean,
    backdropAlpha: Float,
    horizontalPadding: Dp,
    content: @Composable ColumnScope.(dismiss: (() -> Unit) -> Unit) -> Unit,
) {
    val motion = rememberOverlayMotion()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val dismiss: () -> Unit = remember(motion) { { motion.dismiss { currentOnDismiss() } } }
    val animatedDismiss: (() -> Unit) -> Unit = remember(motion) { { then -> motion.dismiss(then) } }
    OverlayFrame(
        onBack = dismiss,
        modifier = Modifier,
        onBackdropTap = if (dismissOnBackdrop) dismiss else null,
        background = Metro.Background.copy(alpha = backdropAlpha),
        contentAlignment = Alignment.Center,
        motion = motion,
    ) {
        Column(
            Modifier
                .padding(horizontal = horizontalPadding)
                .widthIn(max = MetroMaxContentWidth)
                .fillMaxWidth()
                .swivel(motion)
                // Consume so taps inside the panel don't dismiss.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            content(animatedDismiss)
        }
    }
}

/**
 * Metro confirm overlay for destructive actions: big light title, subtle
 * detail line, filled confirm + outlined cancel. Confirm fires once — a
 * double-tap must not run the action (or its navigation) twice.
 */
@Composable
fun MetroConfirm(
    title: String,
    message: String?,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmFill: Color = Metro.Red,
    dismissOnBackdrop: Boolean = true,
) {
    var fired by remember { mutableStateOf(false) }
    MetroDialogImpl(
        onDismiss = onCancel,
        dismissOnBackdrop = dismissOnBackdrop,
        backdropAlpha = 0.96f,
        horizontalPadding = 32.dp,
    ) { dismiss ->
        Text(
            title,
            color = Metro.Foreground,
            style = Metro.Type.SubHeader,
            modifier = if (message == null) Modifier.padding(bottom = 16.dp) else Modifier,
        )
        if (message != null) {
            Text(
                message,
                color = Metro.Subtle,
                style = Metro.Type.Small,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetroButton(confirmLabel, fill = confirmFill, modifier = Modifier.weight(1f)) {
                if (!fired) {
                    fired = true
                    // Swivels out first; the guard keeps it single-shot.
                    dismiss(onConfirm)
                }
            }
            MetroButton(cancelLabel, modifier = Modifier.weight(1f)) {
                if (!fired) dismiss(onCancel)
            }
        }
    }
}

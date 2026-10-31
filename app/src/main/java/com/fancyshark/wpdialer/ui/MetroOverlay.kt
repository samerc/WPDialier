package com.fancyshark.wpdialer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Widest a centered overlay panel (or the main scaffold) grows on tablets. */
val MetroMaxContentWidth = 600.dp

/**
 * Hides a subtree from TalkBack while an overlay covers it — Compose has no
 * modal semantics, so the content beneath must opt out itself.
 */
fun Modifier.hiddenFromAccessibility(hidden: Boolean): Modifier =
    if (hidden) this.clearAndSetSemantics {} else this

/**
 * Full-screen overlay container. Swallows every touch so nothing beneath
 * can be tapped or scrolled "through" it (a background alone doesn't block
 * pointer input in Compose), and owns back while shown.
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
    if (onBack != null) BackHandler { onBack() }
    val backdropTap by rememberUpdatedState(onBackdropTap)
    Box(
        modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(Unit) { detectTapGestures { backdropTap?.invoke() } }
            .semantics { isTraversalGroup = true },
        contentAlignment = contentAlignment,
    ) {
        content()
    }
}

/**
 * Dimmed overlay with a centered panel — the shape shared by every Metro
 * chooser and confirm. Taps on the panel never reach the backdrop.
 */
@Composable
fun MetroDialog(
    onDismiss: () -> Unit,
    dismissOnBackdrop: Boolean = true,
    backdropAlpha: Float = 0.96f,
    horizontalPadding: Dp = 32.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    MetroOverlay(
        onBack = onDismiss,
        onBackdropTap = if (dismissOnBackdrop) onDismiss else null,
        background = Metro.Background.copy(alpha = backdropAlpha),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = horizontalPadding)
                .widthIn(max = MetroMaxContentWidth)
                .fillMaxWidth()
                // Consume so taps inside the panel don't dismiss.
                .pointerInput(Unit) { detectTapGestures { } },
            content = content,
        )
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
    MetroDialog(onDismiss = onCancel, dismissOnBackdrop = dismissOnBackdrop) {
        Text(
            title,
            color = Metro.Foreground,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            modifier = if (message == null) Modifier.padding(bottom = 16.dp) else Modifier,
        )
        if (message != null) {
            Text(
                message,
                color = Metro.Subtle,
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetroButton(confirmLabel, fill = confirmFill, modifier = Modifier.weight(1f)) {
                if (!fired) {
                    fired = true
                    onConfirm()
                }
            }
            MetroButton(cancelLabel, modifier = Modifier.weight(1f)) { onCancel() }
        }
    }
}

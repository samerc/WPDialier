package com.fancyshark.wpdialer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * WP-style in-app toast: a full-width accent strip that slides down from the
 * top, stays ~3s and dismisses on tap. Callable from any thread, no Context
 * needed; nothing shows unless a [MetroToastHost] is composed.
 */
object MetroToast {
    /** One toast; [id] distinguishes repeats of the same text. */
    data class Message(val text: String, val title: String?, val id: Long)

    private val ids = AtomicLong()
    private val _current = MutableStateFlow<Message?>(null)

    /** The toast on screen, or null. */
    val current: StateFlow<Message?> = _current

    /** Shows [message] (with an optional bold [title]), replacing any toast already up. */
    fun show(message: String, title: String? = null) {
        _current.value = Message(message, title, ids.incrementAndGet())
    }

    /** Hides the current toast (or only toast [id], when given). */
    fun dismiss(id: Long? = null) {
        val now = _current.value ?: return
        if (id == null || now.id == id) _current.compareAndSet(now, null)
    }
}

/** How long a [MetroToast] stays up before it slides away. */
private const val TOAST_MILLIS = 3000L

/**
 * Renders [MetroToast]. Place it LAST inside the root Box of a screen so it
 * draws over everything, aligned to the top; it pads itself below the
 * status bar.
 */
@Composable
fun MetroToastHost(modifier: Modifier = Modifier) {
    val message by MetroToast.current.collectAsState()
    val accent by AccentStore.accent.collectAsState()
    val reduceMotion = Metro.reduceMotion
    // Keep the last message composed while the strip slides out. A plain
    // holder, not state: the collected flow already drives recomposition.
    val last = remember { arrayOfNulls<MetroToast.Message>(1) }
    message?.let { last[0] = it }
    val shown = last[0]
    LaunchedEffect(message?.id) {
        val id = message?.id ?: return@LaunchedEffect
        delay(TOAST_MILLIS)
        MetroToast.dismiss(id)
    }
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier.windowInsetsPadding(WindowInsets.statusBars),
        enter = if (reduceMotion) EnterTransition.None else slideInVertically(tween(220)) { -it },
        exit = if (reduceMotion) ExitTransition.None else slideOutVertically(tween(180)) { -it },
    ) {
        val toast = shown ?: return@AnimatedVisibility
        val fg = Metro.onAccent(accent.color)
        Column(
            Modifier
                .fillMaxWidth()
                .background(accent.color)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { MetroToast.dismiss(toast.id) }
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (toast.title != null) {
                Text(toast.title, color = fg, style = Metro.Type.Small, fontWeight = FontWeight.Bold)
            }
            Text(toast.text, color = fg, style = Metro.Type.Small)
        }
    }
}

/**
 * WP indeterminate progress: five small accent dots accelerate in from the
 * start edge, bunch up drifting through the middle, then fly off the end,
 * looping. Fills the given width; 4dp tall dots. Static when motion is off.
 */
@Composable
fun MetroProgressDots(modifier: Modifier = Modifier) {
    val accent by AccentStore.accent.collectAsState()
    val reduceMotion = Metro.reduceMotion
    val color = accent.color
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current ==
        androidx.compose.ui.unit.LayoutDirection.Rtl
    val dots = 5
    val travel = 2000f   // ms one dot takes to cross
    val stagger = 160f   // ms between dots
    val cycle = travel + stagger * (dots - 1) + 500f
    val time = if (reduceMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "dots").animateFloat(
            initialValue = 0f,
            targetValue = cycle,
            animationSpec = infiniteRepeatable(tween(cycle.toInt(), easing = LinearEasing), RepeatMode.Restart),
            label = "dotsTime",
        )
    }
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        val r = size.height / 2f
        val gap = r * 4f
        if (time == null) {
            // Reduced motion: the bunch, parked in the middle.
            for (i in 0 until dots) {
                val x = size.width / 2f + (i - (dots - 1) / 2f) * gap
                drawCircle(color, r, Offset(x, r))
            }
            return@Canvas
        }
        val t = time.value // read in draw: the loop only redraws
        for (i in 0 until dots) {
            val p = (t - i * stagger) / travel
            if (p < 0f || p > 1f) continue
            // Fast-slow-fast: a linear drift plus a cubic, 0..1 over 0..1.
            val c = p - 0.5f
            val f = 0.5f + 0.25f * c + 3f * c * c * c
            // Later dots trail behind, so the leader is furthest along.
            val x = -r + f * (size.width + 2 * r)
            drawCircle(color, r, Offset(if (rtl) size.width - x else x, r))
        }
    }
}

/**
 * Empty-list placeholder: big lowercase Light [text] in the subtle color,
 * start-aligned at the standard 20dp page margin, with an optional [hint].
 */
@Composable
fun MetroEmptyState(text: String, modifier: Modifier = Modifier, hint: String? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text.lowercase(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]),
            color = Metro.Subtle,
            style = Metro.Type.SubHeader,
        )
        if (hint != null) {
            Text(
                hint,
                color = Metro.Subtle,
                style = Metro.Type.Small,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

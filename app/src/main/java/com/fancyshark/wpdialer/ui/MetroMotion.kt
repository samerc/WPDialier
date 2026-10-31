package com.fancyshark.wpdialer.ui

import android.os.SystemClock
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.delay

/** WP motion curves: exponential-style decelerate in, accelerate out. */
object MetroEasing {
    val Decelerate = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)
    val Accelerate = CubicBezierEasing(0.7f, 0f, 1f, 0.5f)
}

// ---------------------------------------------------------------------------
// Page transitions (back-stack navigation)
// ---------------------------------------------------------------------------

/** How the back stack's last change should animate. */
enum class NavMotion { None, TurnstileForward, TurnstileBack, SlideUp, SlideDown }

private const val TURNSTILE_ANGLE = 80f
private const val SLIDE_FRACTION = 0.25f

/**
 * Turnstile / slide page motion for one AnimatedContent entry. [this] is
 * the entry's enter/exit transition (AnimatedVisibilityScope.transition);
 * the custom animations added here keep an exiting page composed until
 * they finish. [motion] must stay fixed for the length of a transition.
 */
@Composable
fun Transition<EnterExitState>.navPageModifier(motion: NavMotion): Modifier {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val mirror = if (rtl) -1f else 1f
    fun angleAt(state: EnterExitState): Float = when (state) {
        EnterExitState.Visible -> 0f
        EnterExitState.PreEnter -> mirror * when (motion) {
            NavMotion.TurnstileForward -> TURNSTILE_ANGLE
            NavMotion.TurnstileBack -> -TURNSTILE_ANGLE
            else -> 0f
        }
        EnterExitState.PostExit -> mirror * when (motion) {
            NavMotion.TurnstileForward -> -TURNSTILE_ANGLE
            NavMotion.TurnstileBack -> TURNSTILE_ANGLE
            else -> 0f
        }
    }
    fun alphaAt(state: EnterExitState): Float =
        if (state == EnterExitState.Visible || motion == NavMotion.None) 1f else 0f
    fun slideAt(state: EnterExitState): Float = when {
        state == EnterExitState.PreEnter && motion == NavMotion.SlideUp -> SLIDE_FRACTION
        state == EnterExitState.PostExit && motion == NavMotion.SlideDown -> SLIDE_FRACTION
        else -> 0f
    }
    val angleState = animateFloat(
        transitionSpec = { navSpec(motion, entering = targetState == EnterExitState.Visible, alpha = false) },
        label = "navAngle",
    ) { angleAt(it) }
    val alphaState = animateFloat(
        transitionSpec = { navSpec(motion, entering = targetState == EnterExitState.Visible, alpha = true) },
        label = "navAlpha",
    ) { alphaAt(it) }
    val slideState = animateFloat(
        transitionSpec = { navSpec(motion, entering = targetState == EnterExitState.Visible, alpha = false) },
        label = "navSlide",
    ) { slideAt(it) }
    // An exiting page must not take taps while it swings away.
    val exiting = targetState == EnterExitState.PostExit
    return Modifier
        .graphicsLayer {
            rotationY = angleState.value
            alpha = alphaState.value
            translationY = slideState.value * size.height
            transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0.5f)
            cameraDistance = 12f * density
        }
        .then(
            if (exiting) {
                Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                }
            } else {
                Modifier
            },
        )
}

/**
 * Timings (ms): turnstile out 0-150 accelerating, in 120-300 decelerating;
 * slide-up in 60-320 over a quick fade of the page below; slide-down out
 * 0-180 with the page below fading back in 80-260.
 */
private fun navSpec(motion: NavMotion, entering: Boolean, alpha: Boolean): FiniteAnimationSpec<Float> =
    when (motion) {
        NavMotion.None -> snap()
        NavMotion.TurnstileForward, NavMotion.TurnstileBack ->
            if (entering) {
                tween(if (alpha) 120 else 180, delayMillis = 120, easing = MetroEasing.Decelerate)
            } else {
                tween(150, easing = MetroEasing.Accelerate)
            }
        NavMotion.SlideUp ->
            if (entering) tween(260, delayMillis = 60, easing = MetroEasing.Decelerate)
            else tween(100, easing = MetroEasing.Accelerate)
        NavMotion.SlideDown ->
            if (entering) tween(180, delayMillis = 80, easing = MetroEasing.Decelerate)
            else tween(180, easing = MetroEasing.Accelerate)
    }

// ---------------------------------------------------------------------------
// Continuum: a tapped list item's title flies to the next page's title
// ---------------------------------------------------------------------------

/** Where a [Continuum] flight lands: start inset, top and font size on the new page. */
data class ContinuumTarget(val startDp: Float, val topDp: Float, val fontSizeSp: Float)

/**
 * Holder for the continuum hand-off. A list row [arm]s it with its title's
 * window bounds right before navigating; the navigation [launch]es the
 * flight (or drops a stale/missing source) and [ContinuumOverlay] draws it.
 */
object Continuum {
    class Source(
        val token: Any,
        val text: String,
        val bounds: Rect,
        val fontSizeSp: Float,
        val armedAt: Long,
    )

    class Flight(val source: Source, val target: ContinuumTarget)

    // Contact lookups before a push can take a moment; older arms are stale.
    private const val MAX_ARM_AGE_MS = 1500L

    private var armed: Source? = null

    /** The flight in progress (rows hide their own title while it's theirs). */
    var flight by mutableStateOf<Flight?>(null)
        private set

    fun arm(token: Any, text: String, bounds: Rect?, fontSizeSp: Float) {
        armed = if (bounds == null || bounds.isEmpty || text.isBlank()) null
        else Source(token, text, bounds, fontSizeSp, SystemClock.uptimeMillis())
    }

    /** Consumes the armed source; flies only toward a [target] page when [enabled]. */
    fun launch(target: ContinuumTarget?, enabled: Boolean) {
        val source = armed
        armed = null
        flight = if (enabled && target != null && source != null &&
            SystemClock.uptimeMillis() - source.armedAt < MAX_ARM_AGE_MS
        ) {
            Flight(source, target)
        } else {
            null
        }
    }

    /** True while [token]'s title is the one in flight. */
    fun isFlying(token: Any): Boolean = flight?.source?.token == token

    internal fun land(done: Flight) {
        if (flight === done) flight = null
    }
}

/** Per-row holder for the title's last window bounds (no recomposition). */
class ContinuumAnchor {
    var bounds: Rect? = null
}

/** Records [anchor]'s bounds; put it on the title Text of a row. */
fun Modifier.continuumAnchor(anchor: ContinuumAnchor): Modifier =
    onGloballyPositioned { anchor.bounds = Rect(it.positionInWindow(), it.size.toSize()) }

private const val CONTINUUM_MS = 380

/**
 * Draws the [Continuum] flight. Place it over the page area (it maps window
 * coordinates into its own box); renders nothing between flights.
 */
@Composable
fun ContinuumOverlay(modifier: Modifier = Modifier) {
    val flight = Continuum.flight ?: return
    val progress = remember(flight) { Animatable(0f) }
    LaunchedEffect(flight) {
        progress.animateTo(1f, tween(CONTINUUM_MS, easing = MetroEasing.Decelerate))
        Continuum.land(flight)
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Plain holder: read during placement only.
    val origin = remember { arrayOf(Offset.Zero) }
    val source = flight.source
    val target = flight.target
    Box(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .onGloballyPositioned { origin[0] = it.positionInWindow() },
        contentAlignment = AbsoluteAlignment.TopLeft,
    ) {
        Text(
            source.text,
            color = Metro.Foreground,
            style = Metro.Type.SubHeader,
            fontSize = target.fontSizeSp.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.layout { measurable, constraints ->
                val startPx = target.startDp.dp.roundToPx()
                val margin = 20.dp.roundToPx()
                val maxW = (constraints.maxWidth - startPx - margin).coerceAtLeast(0)
                val p = measurable.measure(Constraints(maxWidth = maxW))
                layout(p.width, p.height) {
                    val f = progress.value
                    val o = origin[0]
                    val startScale = (source.fontSizeSp / target.fontSizeSp).coerceIn(0.3f, 3f)
                    val scale = startScale + (1f - startScale) * f
                    // Anchor the start edge (left in LTR, right in RTL).
                    val srcAnchor = if (rtl) source.bounds.right - o.x else source.bounds.left - o.x
                    val dstAnchor = if (rtl) (constraints.maxWidth - startPx).toFloat() else startPx.toFloat()
                    val anchor = srcAnchor + (dstAnchor - srcAnchor) * f
                    val x = if (rtl) anchor - p.width else anchor
                    val srcTop = source.bounds.top - o.y
                    val y = srcTop + (target.topDp.dp.toPx() - srcTop) * f
                    p.placeWithLayer(x.toInt(), y.toInt()) {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0f)
                        // Lands, then melts into the real title beneath.
                        alpha = if (f < 0.8f) 1f else ((1f - f) / 0.2f).coerceIn(0f, 1f)
                    }
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Feather: a pivot page's rows enter staggered once the pager settles on it
// ---------------------------------------------------------------------------

/** The pivot's coordinates, so a page can tell when the pager settled on it. */
class PivotFrame {
    var coordinates: LayoutCoordinates? = null
}

/** Provided around a Pivot by its host; null = pages count as always settled. */
val LocalPivotFrame = staticCompositionLocalOf<PivotFrame?> { null }

private const val FEATHER_ROWS = 10
private const val FEATHER_STAGGER_MS = 30L
private const val FEATHER_MS = 260
// Rows composed later than this after the trigger (scrolled in) just show.
private const val FEATHER_WINDOW_MS = 250L
private const val FEATHER_FALLBACK_MS = 600L

/**
 * Feather trigger for one list. Fires on the rising edge of "pager settled
 * on this page AND the list has rows" — so once per settle (or once when
 * the first load lands while settled), never on scroll or recomposition.
 */
@Stable
class FeatherState internal constructor(internal val enabled: Boolean) {
    var generation by mutableIntStateOf(0)
        private set
    internal var anchorIndex = 0
    internal var startedAt = 0L
    private var settled = false
    private var hasContent = false
    private var ready = false

    fun onSettled(now: Boolean, firstVisible: Int) {
        settled = now
        update(firstVisible)
    }

    fun onContent(now: Boolean, firstVisible: Int) {
        hasContent = now
        update(firstVisible)
    }

    private fun update(firstVisible: Int) {
        val now = settled && hasContent
        if (now && !ready && enabled) {
            anchorIndex = firstVisible
            startedAt = SystemClock.uptimeMillis()
            generation++
        }
        ready = now
    }
}

@Composable
fun rememberFeatherState(): FeatherState {
    val reduceMotion = Metro.reduceMotion
    return remember(reduceMotion) { FeatherState(!reduceMotion) }
}

/**
 * Settle detector: put it on the page's list. Compares the list's position
 * with the pivot's (both under the same page-transition layers, so a
 * turnstile doesn't read as "unsettled").
 */
@Composable
fun FeatherState.settleModifier(firstVisible: () -> Int): Modifier {
    val frame = LocalPivotFrame.current
    return Modifier.onGloballyPositioned { coords ->
        val pivot = frame?.coordinates?.takeIf { it.isAttached }
        val settled = pivot == null || !coords.isAttached ||
            kotlin.math.abs(pivot.localPositionOf(coords, Offset.Zero).x) < 1f
        onSettled(settled, firstVisible())
    }
}

/** Row [index]'s feather: slides in from +40dp (mirrored in RTL) with a fade. */
@Composable
fun FeatherState.rowModifier(index: Int): Modifier {
    val gen = generation
    val slot = index - anchorIndex
    val anim = remember(gen) {
        // Before the first trigger the top rows wait hidden (no one-frame
        // flash ahead of the feather); after it, only rows composed right
        // at the trigger animate — rows scrolled in later just show.
        val hidden = enabled && slot in 0 until FEATHER_ROWS &&
            (gen == 0 || SystemClock.uptimeMillis() - startedAt < FEATHER_WINDOW_MS)
        Animatable(if (hidden) 0f else 1f)
    }
    LaunchedEffect(anim) {
        if (anim.value < 1f) {
            // Safety net: a settle that never registers must not leave the
            // rows invisible (a trigger re-keys [anim] and cancels this).
            delay(if (gen == 0) FEATHER_FALLBACK_MS else slot * FEATHER_STAGGER_MS)
            anim.animateTo(1f, tween(FEATHER_MS, easing = MetroEasing.Decelerate))
        }
    }
    val mirror = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    return Modifier.graphicsLayer {
        val p = anim.value
        alpha = p
        translationX = (1f - p) * 40.dp.toPx() * mirror
    }
}

/** Jump-list tile [index]: quick staggered scale + fade in when the grid opens. */
@Composable
fun jumpTileModifier(index: Int): Modifier {
    val reduceMotion = Metro.reduceMotion
    val anim = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(anim) {
        if (anim.value < 1f) {
            delay(index * 10L)
            anim.animateTo(1f, tween(160, easing = MetroEasing.Decelerate))
        }
    }
    return Modifier.graphicsLayer {
        val p = anim.value
        alpha = p
        val s = 0.7f + 0.3f * p
        scaleX = s
        scaleY = s
    }
}

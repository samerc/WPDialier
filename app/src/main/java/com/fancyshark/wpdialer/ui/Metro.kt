package com.fancyshark.wpdialer.ui

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Core Metro (Windows Phone) design palette; supports dark + light themes. */
object Metro {
    var light by mutableStateOf(false)

    val Background: Color get() = if (light) Color(0xFFFFFFFF) else Color(0xFF000000)
    val Foreground: Color get() = if (light) Color(0xFF000000) else Color(0xFFFFFFFF)
    val Subtle: Color get() = if (light) Color(0xFF666666) else Color(0xFF999999)
    val Dim: Color get() = if (light) Color(0xFFC5C5C5) else Color(0xFF3F3F3F)
    val Chrome: Color get() = if (light) Color(0xFFDDDDDD) else Color(0xFF1F1F1F)
    val Key: Color get() = if (light) Color(0xFFE4E4E4) else Color(0xFF3A3A3A)
    val Red = Color(0xFFE51400)

    /**
     * [accent] as a TEXT color, blended until it reaches 4.5:1 against the
     * theme background: toward black on light (lime/yellow/amber on white is
     * ~2:1), toward white on dark (cobalt/indigo/brown on black is ~3:1).
     * Accents that already pass are returned untouched (WP-authentic).
     */
    fun textAccent(accent: Color): Color {
        val bg = Background
        val toward = if (light) Color.Black else Color.White
        var c = accent
        var step = 0
        while (contrast(c, bg) < 4.5f && step < 20) {
            c = lerp(accent, toward, (++step) * 0.05f)
        }
        return c
    }

    /** True when the user turned animations off (animator duration scale 0). */
    fun reduceMotion(context: Context): Boolean =
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f

    /** Composable form of [reduceMotion]; read once per context. Skip or shorten animations when true. */
    val reduceMotion: Boolean
        @Composable get() {
            val context = androidx.compose.ui.platform.LocalContext.current
            return remember(context) { reduceMotion(context) }
        }

    /**
     * WP8.1 type ramp. Each token merges onto [LocalTextStyle], so the
     * Selawik/system font toggle provided at the root still applies; pass as
     * `Text(style = Metro.Type.Large)` and override color/weight per call.
     * Sizes are scaled from WP's 480px-wide ramp to Android dp, kept close
     * to what the screens already used. All weights are bundled Selawik
     * cuts (Light = selawkl, Semilight = selawksl).
     */
    object Type {
        /** WP "semilight" (350) — maps to the bundled selawksl cut. */
        val Semilight = FontWeight(350)

        /** Small uppercase app name above a page/pivot title (letter-spaced). */
        val AppTitle: TextStyle
            @Composable @ReadOnlyComposable
            get() = LocalTextStyle.current.merge(
                TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, letterSpacing = 2.sp),
            )

        /** Pivot/panorama and page titles — 48sp Light. */
        val PageTitle: TextStyle
            @Composable @ReadOnlyComposable
            get() = LocalTextStyle.current.merge(
                TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Light),
            )

        /** Big in-page headers (app name on About, contact name) — 40sp Light. */
        val Header: TextStyle
            @Composable @ReadOnlyComposable
            get() = LocalTextStyle.current.merge(
                TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Light),
            )

        /** Section headers, dialog titles, empty states — 26sp Light. */
        val SubHeader: TextStyle
            @Composable @ReadOnlyComposable
            get() = LocalTextStyle.current.merge(
                TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Light),
            )

        /** List primary text (names, setting titles) — 22sp Semilight. */
        val Large: TextStyle
            @Composable @ReadOnlyComposable
            get() = LocalTextStyle.current.merge(
                TextStyle(fontSize = 22.sp, fontWeight = Semilight),
            )

        /** Body copy, inputs, buttons, menu items — 18sp Normal. */
        val Body: TextStyle
            @Composable @ReadOnlyComposable
            get() = LocalTextStyle.current.merge(
                TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal),
            )

        /** Secondary/subtle text under a primary line — 15sp Normal. */
        val Small: TextStyle
            @Composable @ReadOnlyComposable
            get() = LocalTextStyle.current.merge(
                TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
            )

        /** Labels (app-bar icon captions, key captions) — 11sp Normal. */
        val Tiny: TextStyle
            @Composable @ReadOnlyComposable
            get() = LocalTextStyle.current.merge(
                TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal),
            )
    }

    /** Text color on an accent-filled tile: white unless that drops below 3:1. */
    fun onAccent(fill: Color): Color =
        if (contrast(Color.White, fill) < 3f) Color.Black else Color.White

    /** WCAG contrast ratio of two opaque colors. */
    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return if (la > lb) la / lb else lb / la
    }
}

/**
 * Keeps [Metro.light] in step with the theme mode pref and, for "follow
 * system", the configuration's night mode. Activities declare uiMode in
 * configChanges, so a system dark-mode flip updates LocalConfiguration
 * (and isSystemInDarkTheme) without recreation — this re-reads it.
 * Call at the root of every setContent, before [MetroSystemBars].
 */
@Composable
fun MetroThemeSync() {
    val mode by com.fancyshark.wpdialer.data.AppPrefs.themeMode.collectAsState()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val light = com.fancyshark.wpdialer.data.AppPrefs.resolveLight(mode, systemDark)
    androidx.compose.runtime.SideEffect {
        if (Metro.light != light) Metro.light = light
    }
}

/**
 * System-bar icon appearance for the current theme. targetSdk 36 forces
 * edge-to-edge (the theme's bar colors are ignored on 35+), so dark icons
 * must be requested explicitly on the light theme; pre-35 the bars are
 * still painted, so they also take the theme background.
 */
@Composable
fun MetroSystemBars(window: android.view.Window) {
    val light = Metro.light
    val background = Metro.Background
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        if (android.os.Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.statusBarColor = background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = background.toArgb()
        }
    }
}

/**
 * The "✕" glyph button (remove a row, close a picker): labeled for TalkBack
 * and padded to a 48dp touch target without changing how the glyph looks.
 */
@Composable
fun MetroCloseMark(
    label: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 20.sp,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = label,
            ) { onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text("✕", color = Metro.Subtle, fontSize = fontSize)
    }
}

/**
 * The signature WP press-tilt: the tile pivots toward the touch point while
 * pressed. Globally toggleable in settings.
 */
fun Modifier.metroTilt(enabled: Boolean): Modifier = this.composed {
    if (!enabled) return@composed Modifier
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    Modifier
        .graphicsLayer {
            rotationX = tiltX
            rotationY = tiltY
            cameraDistance = 20f * density
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val cx = size.width / 2f
                val cy = size.height / 2f
                if (cx > 0 && cy > 0) {
                    tiltY = ((down.position.x - cx) / cx).coerceIn(-1f, 1f) * 6f
                    tiltX = -((down.position.y - cy) / cy).coerceIn(-1f, 1f) * 6f
                }
                waitForUpOrCancellation()
                tiltX = 0f
                tiltY = 0f
            }
        }
}

/**
 * WP-style indication for default clickables app-wide: no Material ripple; a
 * dim tint while pressed and an accent outline when focused — the outline is
 * what makes D-pad navigation usable on flip/keypad phones.
 */
object MetroIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        MetroIndicationNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0x4D657472
}

private class MetroIndicationNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {
    private var pressed = false
    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed = true
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                }
                invalidateDraw()
            }
        }
    }

    // Lazy layouts reuse nodes; without clearing, a row rebuilt mid-press
    // resurrects with the pressed overlay stuck on.
    override fun onDetach() {
        pressed = false
        focused = false
    }

    override fun onReset() {
        pressed = false
        focused = false
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) drawRect(Metro.Foreground.copy(alpha = 0.08f))
        if (focused) {
            val stroke = 2.dp.toPx()
            drawRect(
                AccentStore.accent.value.color,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke),
            )
        }
    }
}

data class Accent(val name: String, val color: Color)

/** The classic Windows Phone 8 accent palette. */
val WpAccents = listOf(
    Accent("lime", Color(0xFFA4C400)),
    Accent("green", Color(0xFF60A917)),
    Accent("emerald", Color(0xFF008A00)),
    Accent("teal", Color(0xFF00ABA9)),
    Accent("cyan", Color(0xFF1BA1E2)),
    Accent("cobalt", Color(0xFF0050EF)),
    Accent("indigo", Color(0xFF6A00FF)),
    Accent("violet", Color(0xFFAA00FF)),
    Accent("pink", Color(0xFFF472D0)),
    Accent("magenta", Color(0xFFD80073)),
    Accent("crimson", Color(0xFFA20025)),
    Accent("red", Color(0xFFE51400)),
    Accent("orange", Color(0xFFFA6800)),
    Accent("amber", Color(0xFFF0A30A)),
    Accent("yellow", Color(0xFFE3C800)),
    Accent("brown", Color(0xFF825A2C)),
    Accent("olive", Color(0xFF6D8764)),
    Accent("steel", Color(0xFF647687)),
    Accent("mauve", Color(0xFF76608A)),
    // Not a WP accent, but kept: it shipped before taupe, and the stored
    // pref is the accent name.
    Accent("sienna", Color(0xFFA0522D)),
    Accent("taupe", Color(0xFF87794E)),
)

object AccentStore {
    private val _accent = MutableStateFlow(WpAccents.first { it.name == "cobalt" })
    val accent: StateFlow<Accent> = _accent

    fun init(context: Context) {
        val name = context.getSharedPreferences("wp", Context.MODE_PRIVATE)
            .getString("accent", "cobalt")
        _accent.value = WpAccents.firstOrNull { it.name == name } ?: _accent.value
    }

    fun set(context: Context, accent: Accent) {
        _accent.value = accent
        context.getSharedPreferences("wp", Context.MODE_PRIVATE)
            .edit().putString("accent", accent.name).apply()
        // The home-screen tile widget is painted in the accent color.
        com.fancyshark.wpdialer.widget.TileWidget.updateAll(context)
    }
}

/**
 * Standard WP button: 3px border, transparent body; pressing inverts to a solid
 * white fill with black text. When [fill] is given the body is a solid color.
 */
@Composable
fun MetroButton(
    text: String,
    modifier: Modifier = Modifier,
    fill: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val focusAccent by AccentStore.accent.collectAsState()
    val bg = when {
        pressed -> Metro.Foreground
        fill != null -> fill
        else -> Color.Transparent
    }
    val fg = when {
        pressed -> Metro.Background
        !enabled -> Metro.Dim
        // Lime/yellow/amber fills can't carry white text.
        fill != null -> Metro.onAccent(fill)
        else -> Metro.Foreground
    }
    val borderColor = when {
        pressed -> Metro.Foreground
        focused -> focusAccent.color
        fill != null -> fill
        !enabled -> Metro.Dim
        else -> Metro.Foreground
    }
    val tiltOn by com.fancyshark.wpdialer.data.AppPrefs.tilt.collectAsState()
    Box(
        modifier
            .metroTilt(tiltOn)
            .border(3.dp, borderColor)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, style = Metro.Type.Body)
    }
}

/**
 * WP pivot control: small uppercase app title, oversized lowercase page
 * headers (non-selected ones dimmed to 40%, tracking the swipe), swipeable
 * pages.
 */
@Composable
fun Pivot(
    title: String,
    pages: List<Pair<String, @Composable () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Column(modifier) {
        Text(
            title,
            color = Metro.Foreground,
            style = Metro.Type.AppTitle,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp),
        )
        // Headers pan with the page swipe, like a real WP pivot: the current
        // title sits at the start edge and the others trail off-screen.
        val titleWidths = remember(pages.size) { IntArray(pages.size) }
        var widthTick by remember { mutableStateOf(0) }
        // Disabled horizontalScroll grants children unbounded width so long
        // titles are never clipped; panning is driven by the offset instead.
        Row(
            Modifier
                .padding(start = 12.dp, top = 2.dp)
                .horizontalScroll(rememberScrollState(), enabled = false)
                .offset {
                    widthTick // read so remeasure retriggers offset
                    val progress = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    val whole = progress.toInt().coerceIn(0, pages.size - 1)
                    val base = (0 until whole).sumOf { titleWidths[it] }
                    val frac = (progress - whole) * titleWidths.getOrElse(whole) { 0 }
                    // Toward-start pan; the offset modifier mirrors for RTL.
                    IntOffset(-(base + frac.toInt()), 0)
                },
        ) {
            val accent by AccentStore.accent.collectAsState()
            pages.forEachIndexed { i, (name, _) ->
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                Text(
                    name,
                    maxLines = 1,
                    softWrap = false,
                    color = if (focused) Metro.textAccent(accent.color) else Metro.Foreground,
                    style = Metro.Type.PageTitle,
                    modifier = Modifier
                        // Non-selected headers sit at 40% of the foreground,
                        // brightening as the swipe brings them in. Read in the
                        // layer block so a swipe only redraws, never recomposes.
                        .graphicsLayer {
                            val progress = pagerState.currentPage + pagerState.currentPageOffsetFraction
                            val distance = kotlin.math.abs(progress - i).coerceIn(0f, 1f)
                            alpha = if (focused) 1f else 1f - 0.6f * distance
                        }
                        .onSizeChanged {
                            if (titleWidths[i] != it.width) {
                                titleWidths[i] = it.width
                                widthTick++
                            }
                        }
                        .padding(end = 16.dp)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { scope.launch { pagerState.animateScrollToPage(i) } },
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) { page ->
            pages[page].second()
        }
    }
}

/** WP text box: solid white field with black text and an accent caret. */
@Composable
fun MetroTextBox(
    value: String,
    onValueChange: (String) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    textSize: TextUnit = 18.sp,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    Box(
        modifier
            .fillMaxWidth()
            .border(2.dp, Metro.Foreground)
            .background(Metro.Background)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, color = Metro.Subtle, fontSize = textSize)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = TextStyle(
                color = Metro.Foreground,
                fontSize = textSize,
                fontFamily = LocalTextStyle.current.fontFamily,
            ),
            cursorBrush = SolidColor(accent),
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

class AppBarAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * WP application bar: chrome-colored strip along the bottom holding round
 * outlined icon buttons and the "•••" on the end edge. Tapping "•••" or
 * swiping up slides the bar up to reveal a label under each icon plus the
 * text menu items; swiping down (or back) closes it. [minimized] shows only
 * the ~30dp "•••" strip until it is expanded. A downward flick on the closed
 * bar triggers [onSwipeDown] (the reach gesture) — never while it is open.
 */
@Composable
fun MetroAppBar(
    actions: List<AppBarAction>,
    menu: List<Pair<String, () -> Unit>> = emptyList(),
    modifier: Modifier = Modifier,
    onSwipeDown: (() -> Unit)? = null,
    minimized: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val accent by AccentStore.accent.collectAsState()
    val swipeDown by rememberUpdatedState(onSwipeDown)
    val menuExpanded by rememberUpdatedState(expanded)
    val reduceMotion = Metro.reduceMotion
    // Back closes the expanded bar before it navigates anywhere.
    androidx.activity.compose.BackHandler(enabled = expanded) { expanded = false }
    val moreLabel = androidx.compose.ui.res.stringResource(
        com.fancyshark.wpdialer.R.string.ui_more_options,
    )
    Column(
        modifier
            .fillMaxWidth()
            .background(Metro.Chrome)
            .then(if (reduceMotion) Modifier else Modifier.animateContentSize())
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { _, dragAmount -> total += dragAmount },
                    onDragEnd = {
                        // Low bar on purpose: the strip is only ~60dp tall
                        // and system edge gestures can steal long swipes,
                        // so a short flick must be enough.
                        val threshold = 28.dp.toPx()
                        when {
                            total < -threshold -> expanded = true
                            // Open bar: a downward flick is a dismiss, not a reach.
                            total > threshold && menuExpanded -> expanded = false
                            total > threshold -> swipeDown?.invoke()
                        }
                    },
                )
            },
    ) {
        if (minimized && !expanded) {
            // Collapsed minimized bar: just the dots; the whole strip expands.
            val stripInteraction = remember { MutableInteractionSource() }
            val stripFocused by stripInteraction.collectIsFocusedAsState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clickable(
                        interactionSource = stripInteraction,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = moreLabel,
                    ) { expanded = true }
                    .semantics { contentDescription = moreLabel },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    "•••",
                    color = if (stripFocused) Metro.textAccent(accent.color) else Metro.Foreground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 20.dp),
                )
            }
            return@Column
        }
        Box(Modifier.fillMaxWidth()) {
            val dotsInteraction = remember { MutableInteractionSource() }
            val dotsFocused by dotsInteraction.collectIsFocusedAsState()
            // 48dp target; the dots stay drawn at the strip's top edge.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(width = 56.dp, height = 48.dp)
                    .clickable(
                        interactionSource = dotsInteraction,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = moreLabel,
                    ) { expanded = !expanded }
                    .semantics { contentDescription = moreLabel },
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    "•••",
                    color = if (dotsFocused) Metro.textAccent(accent.color) else Metro.Foreground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                Modifier
                    .align(Alignment.Center)
                    .padding(top = 14.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                actions.forEach { action ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val interaction = remember { MutableInteractionSource() }
                        val focused by interaction.collectIsFocusedAsState()
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .border(
                                    if (focused) 3.dp else 2.dp,
                                    if (focused) accent.color else Metro.Foreground,
                                    CircleShape,
                                )
                                .clickable(
                                    interactionSource = interaction,
                                    indication = null,
                                    role = Role.Button,
                                ) {
                                    expanded = false
                                    action.onClick()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                action.icon,
                                contentDescription = action.label,
                                tint = Metro.Foreground,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        if (expanded) {
                            Text(
                                action.label,
                                color = Metro.Foreground,
                                style = Metro.Type.Tiny,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    // The icon already carries the label for TalkBack.
                                    .clearAndSetSemantics {},
                            )
                        }
                    }
                }
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 20.dp, bottom = 16.dp, top = 4.dp)) {
                menu.forEach { (label, onClick) ->
                    val interaction = remember { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    Text(
                        label,
                        color = if (focused) Metro.textAccent(accent.color) else Metro.Foreground,
                        style = Metro.Type.Body,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                            ) {
                                expanded = false
                                onClick()
                            }
                            .padding(vertical = 9.dp),
                    )
                }
            }
        }
    }
}

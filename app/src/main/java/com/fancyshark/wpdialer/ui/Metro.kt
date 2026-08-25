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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    Accent("sienna", Color(0xFFA0522D)),
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
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 19.sp, fontWeight = FontWeight.Normal)
    }
}

/**
 * WP pivot control: small uppercase app title, oversized lowercase page
 * headers, swipeable pages.
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
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 2.sp,
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
                    color = when {
                        focused -> accent.color
                        pagerState.currentPage == i -> Metro.Foreground
                        else -> Metro.Dim
                    },
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier
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
 * outlined icon buttons; tapping the "..." dots expands it to reveal icon
 * labels and text menu items.
 */
@Composable
fun MetroAppBar(
    actions: List<AppBarAction>,
    menu: List<Pair<String, () -> Unit>> = emptyList(),
    modifier: Modifier = Modifier,
    onSwipeDown: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val accent by AccentStore.accent.collectAsState()
    val swipeDown by rememberUpdatedState(onSwipeDown)
    val menuExpanded by rememberUpdatedState(expanded)
    Column(
        modifier
            .fillMaxWidth()
            .background(Metro.Chrome)
            .animateContentSize()
            .then(
                if (onSwipeDown != null) {
                    Modifier.pointerInput(Unit) {
                        // Reachability trigger: a downward flick on the bar.
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onVerticalDrag = { _, dragAmount -> total += dragAmount },
                            onDragEnd = {
                                // Low bar on purpose: the strip is only ~60dp
                                // tall and system edge gestures can steal long
                                // swipes, so a short flick must be enough.
                                // Not while the "..." menu is open — a flick
                                // there is a dismiss, not a reach.
                                if (total > 28.dp.toPx() && !menuExpanded) {
                                    swipeDown?.invoke()
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(Modifier.fillMaxWidth()) {
            val dotsInteraction = remember { MutableInteractionSource() }
            val dotsFocused by dotsInteraction.collectIsFocusedAsState()
            Text(
                "•••",
                color = if (dotsFocused) accent.color else Metro.Foreground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clickable(
                        interactionSource = dotsInteraction,
                        indication = null,
                    ) { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
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
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp),
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
                        color = if (focused) accent.color else Metro.Foreground,
                        fontSize = 18.sp,
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

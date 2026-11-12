package ai.cashdrive.app.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.cashdrive.app.R

/**
 * The look of the full-screen pages (credits, settings; design §8), in dp on the 4/8 grid: the body keeps
 * the header's 32dp side margin, two columns 32dp apart once the body is [WIDE_MIN] wide, sections 40dp
 * apart with their header 16dp above the content, cards with a 20dp radius and 24dp padding, 16dp apart.
 * Cards are the surface token with a 1px edge (white on the light page, the meter face with its hairline at
 * night); the hero is the meter face in both looks, the one dark instrument of the page.
 */
internal object PageLook {
    const val PAD = 32
    const val TOP = 8
    const val BOTTOM = 48
    const val COLUMN_GAP = 32
    const val WIDE_MIN = 1100
    const val SECTION_GAP = 40
    const val HEADER_GAP = 16
    const val CARD_RADIUS = 20
    const val CARD_PAD = 24
    const val CARD_GAP = 16
    const val BADGE = 56
    const val GLYPH = 32
    const val ENTER_RISE = 16
    const val ENTER_MS = 240L
    const val TOGGLE_MS = 200L
    /** A gentler press than buttons for whole cards: 2% instead of 5%. */
    const val CARD_PRESS = 0.98f
    /** Amber at 16% (the reward cards' badge wash), behind amber glyphs and the today chip. */
    const val WASH = 0x29E0A93C

    fun cardColor(tokens: PleosTokens) = tokens.surface
    fun cardEdge(tokens: PleosTokens) = if (tokens.night) MeterFace.EDGE else tokens.line
    /** Rules inside a card: the line token by day, the meter's rule grey at night (the line token glares on the face). */
    fun rule(tokens: PleosTokens) = if (tokens.night) MeterFace.RULE else tokens.line
}

/**
 * A full-screen page: the 88dp header (back button, 32dp title) over a scrolling [body] padded like the
 * header. [enter] plays the entrance once: the body rises 16dp and fades in (240 ms, ease-out cubic);
 * a page rebuilt for a new look, or with motion reduced, appears in place.
 */
@SuppressLint("ViewConstructor")
internal class PageScaffold(private val kit: PleosKit, tokens: PleosTokens, title: String, onBack: () -> Unit) : LinearLayout(kit.context) {
    val body = LinearLayout(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(tokens.background)
        // The page takes every touch, so nothing on the home below reacts through it.
        isClickable = true
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(kit.dp(PageLook.PAD), 0, kit.dp(PageLook.PAD), 0)
            addView(kit.iconButton(R.drawable.ic_home_back, "뒤로", onBack).also { kit.tint(it, tokens.text, tokens.soft) })
            addView(kit.text(title, 32, tokens.text, bold = true).apply { setPadding(kit.dp(20), 0, 0, 0); letterSpacing = -0.01f })
        }, LayoutParams(-1, kit.dp(HomeView.NAV)))
        body.orientation = VERTICAL
        body.setPadding(kit.dp(PageLook.PAD), kit.dp(PageLook.TOP), kit.dp(PageLook.PAD), kit.dp(PageLook.BOTTOM))
        val scroll = ScrollView(context).apply { overScrollMode = OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = true }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, 0, 1f))
    }

    fun enter(animate: Boolean) {
        body.animate().cancel()
        if (!animate) { body.alpha = 1f; body.translationY = 0f; return }
        body.alpha = 0f
        body.translationY = kit.dp(PageLook.ENTER_RISE).toFloat()
        body.animate().alpha(1f).translationY(0f).setDuration(PageLook.ENTER_MS).setInterpolator(EASE_OUT_CUBIC).withLayer().start()
    }

    /** Ends the entrance on its final state (the page is leaving or being rebuilt). */
    fun settle() {
        body.animate().cancel()
        body.alpha = 1f; body.translationY = 0f
    }
}

/**
 * Two columns [gap] apart once the width reaches [wideMin], each as tall as its content (or both as tall
 * as the taller one with [matchHeights]); narrower, the second stacks under the first, [gap] below it.
 * Child 0 is the start column, child 1 the end column.
 */
@SuppressLint("ViewConstructor")
internal class Columns(context: Context, private val gap: Int, private val wideMin: Int, private val matchHeights: Boolean = false) : ViewGroup(context) {
    private var wide = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val start = getChildAt(0)
        val end = getChildAt(1)
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        wide = width >= wideMin
        if (wide) {
            val startWidth = (width - gap) / 2
            val endWidth = width - gap - startWidth
            start.measure(exactly(startWidth), free)
            end.measure(exactly(endWidth), free)
            val height = maxOf(start.measuredHeight, end.measuredHeight)
            if (matchHeights) {
                start.measure(exactly(startWidth), exactly(height))
                end.measure(exactly(endWidth), exactly(height))
            }
            setMeasuredDimension(width, height)
        } else {
            start.measure(exactly(width), free)
            end.measure(exactly(width), free)
            setMeasuredDimension(width, start.measuredHeight + gap + end.measuredHeight)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val start = getChildAt(0)
        val end = getChildAt(1)
        start.layout(0, 0, start.measuredWidth, start.measuredHeight)
        val endLeft = if (wide) start.measuredWidth + gap else 0
        val endTop = if (wide) 0 else start.measuredHeight + gap
        end.layout(endLeft, endTop, endLeft + end.measuredWidth, endTop + end.measuredHeight)
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}

/** A vertical column of a page body. */
internal fun PleosKit.pageColumn(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

/**
 * A section header: the 24dp title in bold sub-text grey and, when given, a figure at the end in the same
 * grey (tabular). 40dp above it unless it opens its column, 16dp below it.
 */
internal fun PleosKit.sectionHeader(tokens: PleosTokens, title: String, trailing: String? = null, first: Boolean = false): View =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(title, 24, tokens.subText, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        trailing?.let { addView(text(it, 24, tokens.subText).apply { fontFeatureSettings = "tnum" }) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = if (first) 0 else dp(PageLook.SECTION_GAP)
            bottomMargin = dp(PageLook.HEADER_GAP)
        }
    }

/**
 * A page footnote: the info mark and one or two grey 22dp lines, 4dp in from the cards' edge (settings under
 * the switches, credits under the redemption cards).
 */
internal fun PleosKit.pageFootnote(tokens: PleosTokens, text: String): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(4), 0, dp(4), 0)
    addView(ImageView(context).apply {
        setImageResource(R.drawable.ic_toast_info); imageTintList = ColorStateList.valueOf(tokens.subText)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(24), dp(24)))
    addView(text(text, 22, tokens.subText).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
}

/** A card's face: the surface token, 20dp corners, a 1px edge. */
internal fun PleosKit.cardFace(tokens: PleosTokens): GradientDrawable = GradientDrawable().apply {
    setColor(PageLook.cardColor(tokens))
    cornerRadius = dp(PageLook.CARD_RADIUS).toFloat()
    setStroke(1, PageLook.cardEdge(tokens))
}

/** A one-line text view that ends with an ellipsis rather than wrapping. */
internal fun PleosKit.singleLine(value: String, size: Int, color: Int, bold: Boolean = false): TextView =
    text(value, size, color, bold).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

/**
 * A round icon badge: [glyph] dp of a white vector glyph centred on a disc as big as the badge's layout
 * params. [setLit] crossfades a second layer over the base one (amber wash, accent glyph), so selection
 * changes alpha only.
 */
@SuppressLint("ViewConstructor")
internal class IconBadge(context: Context, kit: PleosKit, icon: Int, glyph: Int,
                         baseDisc: Int, baseGlyph: Int, litDisc: Int, litGlyph: Int) : FrameLayout(context) {
    private val lit = FrameLayout(context)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(layer(kit, icon, glyph, baseDisc, baseGlyph), LayoutParams(-1, -1))
        lit.addView(layer(kit, icon, glyph, litDisc, litGlyph), LayoutParams(-1, -1))
        addView(lit, LayoutParams(-1, -1))
    }

    fun setLit(on: Boolean, animate: Boolean) {
        lit.animate().cancel()
        val target = if (on) 1f else 0f
        if (!animate) { lit.alpha = target; return }
        lit.animate().alpha(target).setDuration(PageLook.TOGGLE_MS).setInterpolator(EASE_OUT_CUBIC).start()
    }

    private fun layer(kit: PleosKit, icon: Int, glyph: Int, disc: Int, tint: Int): View = FrameLayout(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(disc) }
        addView(ImageView(context).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(tint) },
            LayoutParams(kit.dp(glyph), kit.dp(glyph), Gravity.CENTER))
    }

    companion object {
        /** A badge that is always amber: the wash and the accent glyph (history rows, empty state). */
        fun amber(context: Context, kit: PleosKit, tokens: PleosTokens, icon: Int, glyph: Int) =
            IconBadge(context, kit, icon, glyph, PageLook.WASH, tokens.accent, PageLook.WASH, tokens.accent).apply { setLit(true, false) }

        /** A neutral badge (soft disc, text-colour glyph) that turns amber when lit; it starts unlit. */
        fun selectable(context: Context, kit: PleosKit, tokens: PleosTokens, icon: Int, glyph: Int) =
            IconBadge(context, kit, icon, glyph, tokens.soft, tokens.text, PageLook.WASH, tokens.accent).apply { setLit(false, false) }
    }
}

/**
 * The Pleos switch, 72x40dp: a pill track and a 32dp thumb 4dp inside it. Turning on slides the thumb 32dp
 * to the end while the filled track and the "on" thumb fade in over the line-colour track and the white
 * thumb (200 ms, ease-out cubic): transform and alpha only. The card around it speaks for it.
 */
@SuppressLint("ViewConstructor")
internal class PageSwitch(context: Context, private val kit: PleosKit, tokens: PleosTokens, on: Boolean) : FrameLayout(context) {
    private val trackOn = View(context)
    private val thumb = FrameLayout(context)
    private val thumbOn = View(context)

    var checked = on
        private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(View(context).apply { background = pill(tokens.line) }, LayoutParams(-1, -1))
        trackOn.background = pill(tokens.filled)
        addView(trackOn, LayoutParams(-1, -1))
        // The white disc is the thumb's own background, so its oval outline casts the 2dp shadow.
        thumb.background = disc(Color.WHITE)
        thumbOn.background = disc(tokens.onFilled)
        thumb.addView(thumbOn, LayoutParams(-1, -1))
        thumb.elevation = kit.dp(2).toFloat()
        addView(thumb, LayoutParams(kit.dp(THUMB), kit.dp(THUMB), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = kit.dp(INSET) })
        setChecked(on, animate = false)
    }

    fun setChecked(on: Boolean, animate: Boolean) {
        checked = on
        val alpha = if (on) 1f else 0f
        val shift = if (on) kit.dp(TRAVEL).toFloat() else 0f
        listOf(trackOn, thumbOn, thumb).forEach { it.animate().cancel() }
        if (!animate) {
            trackOn.alpha = alpha; thumbOn.alpha = alpha; thumb.translationX = shift
            return
        }
        trackOn.animate().alpha(alpha).setDuration(PageLook.TOGGLE_MS).setInterpolator(EASE_OUT_CUBIC).start()
        thumbOn.animate().alpha(alpha).setDuration(PageLook.TOGGLE_MS).setInterpolator(EASE_OUT_CUBIC).start()
        thumb.animate().translationX(shift).setDuration(PageLook.TOGGLE_MS).setInterpolator(EASE_OUT_CUBIC).start()
    }

    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = kit.dp(20).toFloat() }
    private fun disc(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private companion object {
        const val THUMB = 32
        const val INSET = 4
        /** 72 wide, 4dp inset on both sides: the thumb travels 72 - 2 x 4 - 32. */
        const val TRAVEL = 32
    }
}

/**
 * The radio mark, 32dp: a 3dp ring in the sub-text grey; selected, an accent ring fades in over it and a
 * 14dp accent dot scales up from 40% in the middle (200 ms, ease-out cubic). Transform and alpha only.
 */
@SuppressLint("ViewConstructor")
internal class PageRadio(context: Context, kit: PleosKit, tokens: PleosTokens, selected: Boolean) : FrameLayout(context) {
    private val ringOn = View(context)
    private val dot = View(context)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(View(context).apply { background = ring(kit, tokens.subText) }, LayoutParams(-1, -1))
        ringOn.background = ring(kit, tokens.accent)
        addView(ringOn, LayoutParams(-1, -1))
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tokens.accent) }
        addView(dot, LayoutParams(kit.dp(14), kit.dp(14), Gravity.CENTER))
        setChecked(selected, animate = false)
    }

    fun setChecked(on: Boolean, animate: Boolean) {
        val alpha = if (on) 1f else 0f
        val scale = if (on) 1f else 0.4f
        ringOn.animate().cancel(); dot.animate().cancel()
        if (!animate) {
            ringOn.alpha = alpha; dot.alpha = alpha; dot.scaleX = scale; dot.scaleY = scale
            return
        }
        ringOn.animate().alpha(alpha).setDuration(PageLook.TOGGLE_MS).setInterpolator(EASE_OUT_CUBIC).start()
        dot.animate().alpha(alpha).scaleX(scale).scaleY(scale).setDuration(PageLook.TOGGLE_MS).setInterpolator(EASE_OUT_CUBIC).start()
    }

    private fun ring(kit: PleosKit, color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(kit.dp(3), color)
    }
}

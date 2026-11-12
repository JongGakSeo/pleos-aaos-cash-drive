package ai.cashdrive.app.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import ai.cashdrive.app.R

/**
 * The settings page (design §8, §13-12). Section "주행": two switch cards side by side on the wide panel,
 * "주행 중 움직임" (the scene's horse and the meter's figures move while driving) and "움직임 줄이기" (the
 * scene stands still and the figures change at once), each with its glyph, and under them a footnote:
 * briefings play only in park, whatever these say. Section "이용 방법" (review 2026-11-09): one card with
 * the service's three steps side by side, each an amber glyph, a title and one line (1 km a ticket, up to
 * ten a day; park and the briefing plays; to the end and 10 credits). Section "브리핑 편성": the rotation's
 * three creatives side by side, each a 16:10 picture with its length chip, the title and its source line
 * (what a ticket plays, in order). Section "테마" (2026-11-11, planning/specs/2026-11-11-3d-themes-design.md):
 * the three scene themes side by side, each its picture, name and one line; a tap selects it (the page is
 * open only in park). No commercial toggle and no data notice (user decision 2026-11-09).
 * Debug builds of the demo flavor add their test tools at the end.
 *
 * The host keeps the values: a tap asks it to flip one, and it opens the page again with the new values,
 * which [update] shows by sliding the switch in place (the page is not rebuilt).
 */
internal class SettingsPage(private val kit: PleosKit, private val tokens: PleosTokens, private val actions: HomeView.Actions) {
    private val context: Context = kit.context
    private lateinit var scaffold: PageScaffold
    private lateinit var motion: SwitchCard
    private lateinit var reduce: SwitchCard
    private val themeTiles = mutableMapOf<String, ThemeTile>()

    fun build(motionWhileDriving: Boolean, reduceMotion: Boolean, themeId: String, tools: List<Pair<String, () -> Unit>>): View {
        scaffold = PageScaffold(kit, tokens, "설정") { actions.onCloseOverlay() }
        val body = scaffold.body
        body.addView(kit.sectionHeader(tokens, "주행", first = true))
        motion = SwitchCard(R.drawable.ic_setting_motion, MOTION, MOTION_HELP, motionWhileDriving) { actions.onMotionWhileDrivingToggle() }
        reduce = SwitchCard(R.drawable.ic_setting_reduce, REDUCE, REDUCE_HELP, reduceMotion) { actions.onReduceMotionToggle() }
        val pair = Columns(context, dp(PageLook.COLUMN_GAP), dp(PageLook.WIDE_MIN), matchHeights = true)
        pair.addView(motion.view)
        pair.addView(reduce.view)
        body.addView(pair, LinearLayout.LayoutParams(-1, -2))
        body.addView(kit.pageFootnote(tokens, FOOTNOTE), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(PageLook.HEADER_GAP) })
        body.addView(kit.sectionHeader(tokens, "테마", "주행 화면의 장면"))
        body.addView(themes(themeId), LinearLayout.LayoutParams(-1, -2))
        body.addView(kit.sectionHeader(tokens, "이용 방법"))
        body.addView(howItWorks(), LinearLayout.LayoutParams(-1, -2))
        body.addView(kit.sectionHeader(tokens, "브리핑 편성", "${BriefingCatalog.rotation.size}편 · 시청권마다 차례로 재생"))
        body.addView(lineup(), LinearLayout.LayoutParams(-1, -2))
        if (tools.isNotEmpty()) {
            body.addView(kit.sectionHeader(tokens, "테스트 도구"))
            body.addView(toolList(tools), LinearLayout.LayoutParams(-1, -2))
        }
        return scaffold
    }

    fun enter(animate: Boolean) = scaffold.enter(animate)

    /** The host's values after a toggle: each switch slides to its value when [animate], or stands there at once. */
    fun update(motionWhileDriving: Boolean, reduceMotion: Boolean, themeId: String, animate: Boolean) {
        motion.set(motionWhileDriving, animate)
        reduce.set(reduceMotion, animate)
        themeTiles.forEach { (id, tile) -> tile.set(id == themeId) }
    }

    fun dispose() {
        if (::scaffold.isInitialized) scaffold.settle()
    }


    /**
     * "테마": one card with the three themes side by side (stacked on a narrow panel), hairlines between
     * them. Each: the theme's 16:10 still (16dp corners), the 26dp name and a 20dp line; the selected one
     * carries a "사용 중" chip on its picture. The whole tile is the target.
     */
    private fun themes(selected: String): View = StepRow(context, dp(PageLook.WIDE_MIN), PageLook.rule(tokens), dp(32)).apply {
        background = kit.cardFace(tokens)
        THEMES.forEach { theme ->
            val tile = ThemeTile(theme, theme.id == selected)
            themeTiles[theme.id] = tile
            addView(tile.view)
        }
    }

    private inner class ThemeTile(private val theme: Theme, on: Boolean) {
        val view = LinearLayout(context)
        private val chip = kit.text("사용 중", 20, 0xFF1D1E21.toInt(), bold = true).apply {
            background = kit.rounded(0xFFE0A93C.toInt(), 16f); setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        init {
            view.orientation = LinearLayout.VERTICAL
            view.setPadding(dp(28), dp(28), dp(28), dp(28))
            val picture = AspectFrame(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
            }
            picture.addView(ImageView(context).apply {
                setImageResource(Stills.thumb(theme.id, tokens.night)); scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
            picture.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })
            view.addView(picture, LinearLayout.LayoutParams(-1, -2))
            view.addView(kit.singleLine(theme.name, 26, tokens.text, bold = true).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
            view.addView(kit.text(theme.line, 20, tokens.subText).apply {
                maxLines = 2; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            kit.pressable(view, PageLook.CARD_PRESS) { actions.onThemeSelected(theme.id) }
            set(on)
        }

        fun set(on: Boolean) {
            chip.visibility = if (on) View.VISIBLE else View.GONE
            view.contentDescription = "${theme.name} 테마, ${theme.line}" + if (on) ", 사용 중" else ""
        }
    }

    /**
     * "이용 방법": one card, the three steps side by side (stacked on a narrow panel) with hairlines between
     * them. Each step: a 64dp amber badge, the 28dp title and one or two 22dp lines. Read only.
     */
    private fun howItWorks(): View = StepRow(context, dp(PageLook.WIDE_MIN), PageLook.rule(tokens), dp(32)).apply {
        background = kit.cardFace(tokens)
        STEPS.forEach { addView(stepView(it)) }
    }

    private fun stepView(step: Step): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(32), dp(32), dp(32), dp(32))
        contentDescription = "${step.title}, ${step.body}"
        isScreenReaderFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(IconBadge.amber(context, kit, tokens, step.icon, 32).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(kit.singleLine(step.title, 28, tokens.text, bold = true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        addView(kit.text(step.body, 22, tokens.subText).apply {
            maxLines = 2; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    /**
     * "브리핑 편성": one card with the rotation's creatives side by side (stacked on a narrow panel), hairlines
     * between them. Each: a 16:10 picture (16dp corners) with a "15초" chip, the 26dp title and the 20dp
     * source line. A creative without a picture shows its topic glyph on an amber wash. Read only.
     */
    private fun lineup(): View = StepRow(context, dp(PageLook.WIDE_MIN), PageLook.rule(tokens), dp(32)).apply {
        background = kit.cardFace(tokens)
        BriefingCatalog.rotation.forEachIndexed { index, creative -> addView(lineupView(index, creative)) }
    }

    private fun lineupView(index: Int, creative: Creative): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(28), dp(28), dp(28))
        contentDescription = "${index + 1}번째 브리핑, ${creative.title}, ${creative.source}, 15초"
        isScreenReaderFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        val picture = AspectFrame(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
            }
        }
        val thumb = thumbFor(creative)
        if (thumb != null) {
            picture.addView(ImageView(context).apply { setImageResource(thumb); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
        } else {
            picture.background = kit.rounded(PageLook.WASH, 16f)
            picture.addView(IconBadge.amber(context, kit, tokens, CreditsFormat.iconFor(creative), 40), FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER))
        }
        picture.addView(kit.text("${index + 1}", 20, 0xFFFFFFFF.toInt(), bold = true).apply {
            gravity = Gravity.CENTER; background = kit.rounded(0xB31D1E21.toInt(), 16f); setPadding(dp(12), dp(6), dp(12), dp(6))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })
        picture.addView(kit.text("15초", 20, 0xFFFFFFFF.toInt(), bold = true).apply {
            background = kit.rounded(0xB31D1E21.toInt(), 16f); setPadding(dp(12), dp(6), dp(12), dp(6))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(12), dp(12)) })
        addView(picture, LinearLayout.LayoutParams(-1, -2))
        addView(kit.singleLine(creative.title, 26, tokens.text, bold = true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        addView(kit.text(creative.source, 20, tokens.subText).apply {
            maxLines = 2; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    /**
     * A creative's picture for the lineup, when the app carries one. The PSA's picture lives with its clip in
     * src/psa/ and is in the APK only when the PSA is (build switch kilomoa.includePsa), so it is looked up by name.
     */
    private fun thumbFor(creative: Creative): Int? = when (creative.id) {
        "psa-right-turn" -> context.resources.getIdentifier("thumb_psa_right_turn", "drawable", context.packageName).takeIf { it != 0 }
        "school-zone" -> R.drawable.thumb_school_zone
        "emergency-yield" -> R.drawable.thumb_emergency_yield
        "right-turn-stop" -> R.drawable.thumb_right_turn
        else -> null
    }

    /** 16:10 at the column's width: the lineup's pictures keep one shape in every column. */
    private class AspectFrame(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec,
            MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(widthMeasureSpec) * 10) / 16, MeasureSpec.EXACTLY))
    }

    /** The demo flavor's test tools: one card of 80dp rows with hairlines between them. */
    private fun toolList(tools: List<Pair<String, () -> Unit>>): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = kit.cardFace(tokens)
        setPadding(0, dp(8), 0, dp(8))
        tools.forEachIndexed { index, (name, run) ->
            if (index > 0) addView(View(context).apply { setBackgroundColor(PageLook.rule(tokens)) }, LinearLayout.LayoutParams(-1, 1).apply {
                leftMargin = dp(PageLook.CARD_PAD); rightMargin = dp(PageLook.CARD_PAD)
            })
            addView(kit.singleLine(name, 26, tokens.text).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(80)
                setPadding(dp(PageLook.CARD_PAD), 0, dp(PageLook.CARD_PAD), 0)
                kit.pressable(this, PageLook.CARD_PRESS, run)
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun dp(value: Int) = kit.dp(value)

    /**
     * A switch card: the glyph on a neutral badge, the 28dp title over a 22dp line, the switch at the end;
     * the whole card is the target. Reported to accessibility as a switch with its state.
     */
    private inner class SwitchCard(icon: Int, private val label: String, help: String, on: Boolean, toggle: () -> Unit) {
        val view = LinearLayout(context)
        private val switch = PageSwitch(context, kit, tokens, on)

        init {
            view.orientation = LinearLayout.HORIZONTAL
            view.gravity = Gravity.CENTER_VERTICAL
            view.background = kit.cardFace(tokens)
            val pad = dp(PageLook.CARD_PAD)
            view.setPadding(pad, pad, pad, pad)
            view.addView(IconBadge.selectable(context, kit, tokens, icon, PageLook.GLYPH), LinearLayout.LayoutParams(dp(PageLook.BADGE), dp(PageLook.BADGE)))
            val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            words.addView(kit.singleLine(label, 28, tokens.text, bold = true))
            words.addView(kit.text(help, 22, tokens.subText).apply { maxLines = 2 }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            view.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(20); rightMargin = dp(24) })
            view.addView(switch, LinearLayout.LayoutParams(dp(72), dp(40)))
            view.contentDescription = "$label, $help"
            view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Switch::class.java.name
                    info.isCheckable = true
                    info.isChecked = switch.checked
                }
            }
            kit.pressable(view, PageLook.CARD_PRESS, toggle)
        }

        fun set(on: Boolean, animate: Boolean) = switch.setChecked(on, animate && on != switch.checked)
    }

    private class Step(val icon: Int, val title: String, val body: String)

    /**
     * Steps side by side in equal widths and heights once the row is [wideMin] wide, stacked otherwise, with a
     * 1px [ruleColor] hairline between neighbours, [inset] short of the card's edges.
     */
    private class StepRow(context: Context, private val wideMin: Int, ruleColor: Int, private val inset: Int) : ViewGroup(context) {
        private val rule = Paint().apply { color = ruleColor }
        private var wide = true

        init { setWillNotDraw(false) }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            wide = width >= wideMin
            var height = 0
            if (wide && childCount > 0) {
                val each = width / childCount
                for (i in 0 until childCount) {
                    getChildAt(i).measure(exactly(each), free)
                    height = maxOf(height, getChildAt(i).measuredHeight)
                }
                for (i in 0 until childCount) getChildAt(i).measure(exactly(each), exactly(height))
            } else {
                for (i in 0 until childCount) {
                    getChildAt(i).measure(exactly(width), free)
                    height += getChildAt(i).measuredHeight
                }
            }
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var x = 0
            var y = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                if (wide) x += child.measuredWidth else y += child.measuredHeight
            }
        }

        override fun onDraw(canvas: Canvas) {
            for (i in 1 until childCount) {
                val child = getChildAt(i)
                if (wide) canvas.drawRect(child.left.toFloat(), inset.toFloat(), child.left + 1f, (height - inset).toFloat(), rule)
                else canvas.drawRect(inset.toFloat(), child.top.toFloat(), (width - inset).toFloat(), child.top + 1f, rule)
            }
        }

        private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
    }

    private companion object {
        const val MOTION = "주행 중 움직임"
        /** Design §13-12: the setting covers the scene's horse and the meter panel, in the page's "~해요" tone. */
        const val MOTION_HELP = "주행 중에도 장면과 미터가 움직여요"
        const val REDUCE = "움직임 줄이기"
        const val REDUCE_HELP = "장면과 숫자를 움직임 없이 보여 줘요"
        const val FOOTNOTE = "이 설정과 관계없이 브리핑은 P단에 주차했을 때만 재생돼요"
        /** The scene themes (core ThemeIds), in the order the page shows them. */
        val THEMES = listOf(
            Theme("horse", "초원 말", "달리는 동안 조랑말이 초원을 달려요"),
            Theme("mine", "보석 광산", "달리는 동안 광부가 수정을 캐요"),
            Theme("mining", "꼬마 광부", "달리는 동안 아이들이 바위를 캐요"),
        )
        /** The service as it runs: 1 km per ticket (TripMeter), ten a day, 15 s briefings, 10 credits each. */
        val STEPS = listOf(
            Step(R.drawable.ic_route, "1 km마다 시청권 1장", "달린 거리만큼 쌓이고, 하루 10장까지 받아요"),
            Step(R.drawable.ic_stub, "주차하면 바로 브리핑", "P단에 세우면 시청권 1장으로 15초 브리핑이 재생돼요"),
            Step(R.drawable.ic_brief_check, "끝까지 보면 10 크레딧", "적립된 크레딧은 크레딧 화면에서 쓸 곳을 골라요"),
        )
    }
}

/** A scene theme on the settings page: the session's id, its name and one line. */
internal class Theme(val id: String, val name: String, val line: String)

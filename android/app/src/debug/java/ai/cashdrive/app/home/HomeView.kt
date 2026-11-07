package ai.cashdrive.app.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import ai.cashdrive.app.R

/** Pleos Connect grayscale tokens (basic_00..900). Light for day, dark for night. */
data class PleosTokens(
    val background: Int, val surface: Int, val popup: Int, val text: Int, val subText: Int,
    val line: Int, val filled: Int, val onFilled: Int, val soft: Int, val toast: Int, val onToast: Int,
) {
    companion object {
        val light = PleosTokens(0xFFF7F8FA.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF131417.toInt(),
            0xFF686A72.toInt(), 0xFFE0E1E6.toInt(), 0xFF44464E.toInt(), 0xFFFFFFFF.toInt(), 0x0F131417,
            0xFF313236.toInt(), 0xFFFFFFFF.toInt())
        val dark = PleosTokens(0xFF1D1E21.toInt(), 0xFF131417.toInt(), 0xFF313236.toInt(), 0xFFFFFFFF.toInt(),
            0xFFC7C9CE.toInt(), 0xFF44464E.toInt(), 0xFFE0E1E6.toInt(), 0xFF131417.toInt(), 0x14FFFFFF,
            0xFFE0E1E6.toInt(), 0xFF131417.toInt())
    }
}

object Themes {
    val ids = listOf("horse", "mine", "mining")
    fun name(id: String) = when (id) { "mine" -> "보석 광산"; "mining" -> "코인 채굴"; else -> "초원 말" }
    fun color(id: String) = when (id) { "mine" -> 0xFF1F9E9A.toInt(); "mining" -> 0xFFD69A12.toInt(); else -> 0xFF5FA350.toInt() }
    fun icon(id: String) = when (id) { "mine" -> R.drawable.ic_theme_mine; "mining" -> R.drawable.ic_theme_mining; else -> R.drawable.ic_theme_horse }
    @Suppress("UNUSED_PARAMETER")
    fun still(context: Context, id: String, night: Boolean, wide: Boolean): Int = when (id) {
        "mine" -> if (night) { if (wide) R.drawable.scene_mine_night_wide else R.drawable.scene_mine_night_narrow }
            else { if (wide) R.drawable.scene_mine_day_wide else R.drawable.scene_mine_day_narrow }
        "mining" -> if (night) { if (wide) R.drawable.scene_mining_night_wide else R.drawable.scene_mining_night_narrow }
            else { if (wide) R.drawable.scene_mining_day_wide else R.drawable.scene_mining_day_narrow }
        else -> if (night) { if (wide) R.drawable.scene_horse_night_wide else R.drawable.scene_horse_night_narrow }
            else { if (wide) R.drawable.scene_horse_day_wide else R.drawable.scene_horse_day_narrow }
    }
}

enum class VehicleLabel(val text: String) { STOPPED("정차 중"), DRIVING("주행 중"), CHECKING("확인 중") }

/**
 * Builds the Pleos-style home: page navigation, scene host, figures panel, ad player,
 * theme dropdown, settings page, toast and lock screen. Holds no reward or safety logic.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class HomeView(private val context: Context, private val actions: Actions) {
    interface Actions {
        fun onThemeMenu()
        fun onThemeSelected(id: String)
        fun onSettings()
        fun onCloseOverlay()
        fun onSoundToggle()
        fun onReduceMotionToggle()
        fun demoTools(): List<Pair<String, () -> Unit>>
    }

    private var tokens = PleosTokens.light
    private var wide = false
    val root = FrameLayout(context)
    val sceneHost = FrameLayout(context).apply { id = View.generateViewId() }
    private val scene = FrameLayout(context)
    private val still = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val nav = LinearLayout(context)
    private val appIcon = FrameLayout(context)
    private val appIconImage = ImageView(context)
    private val titleBlock = LinearLayout(context)
    private val title = TextView(context)
    private val chevron = ImageView(context)
    private val subtitle = TextView(context)
    private val dot = View(context)
    private val settingsButton = iconButton(R.drawable.ic_home_gear, "설정") { actions.onSettings() }
    private val dock = LinearLayout(context)
    private val figures = List(3) { Figure(it) }
    private val ad = FrameLayout(context)
    private val adTitle = TextView(context)
    private val adSegments = SegmentsView(context)
    private val adTime = TextView(context)
    private val adSound = iconButton(R.drawable.ic_home_sound, "소리") { actions.onSoundToggle() }
    private val overlay = FrameLayout(context)
    private val toast = TextView(context)
    private val lock = LinearLayout(context)
    private val lockTitle = TextView(context)
    private val lockBody = TextView(context)
    private val lockButton = TextView(context)
    private var hideToast: Runnable? = null

    var menuOpen = false; private set
    var settingsOpen = false; private set

    init {
        scene.addView(still, match())
        scene.addView(sceneHost, match())
        root.addView(scene)
        buildAd(); root.addView(ad); ad.visibility = View.GONE
        buildNav(); root.addView(nav)
        buildDock(); root.addView(dock)
        root.addView(overlay, match()); overlay.visibility = View.GONE
        buildToast(); root.addView(toast)
        buildLock(); root.addView(lock, match()); lock.visibility = View.GONE
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) layout(r - l, b - t)
        }
    }

    fun setTokens(value: PleosTokens) {
        tokens = value
        root.setBackgroundColor(tokens.background)
        nav.setBackgroundColor(tokens.background)
        title.setTextColor(tokens.text); subtitle.setTextColor(tokens.subText)
        chevron.imageTintList = ColorStateList.valueOf(tokens.text)
        tint(settingsButton, tokens.text, tokens.soft)
        dock.setBackgroundColor(tokens.surface)
        figures.forEach { it.colors() }
        toast.background = rounded(tokens.toast, 16f); toast.setTextColor(tokens.onToast)
        lock.setBackgroundColor(tokens.background); lockTitle.setTextColor(tokens.text); lockBody.setTextColor(tokens.subText)
        dock.dividers()
    }

    fun setTheme(id: String, night: Boolean) {
        appIcon.background = rounded(Themes.color(id), 16f)
        appIconImage.setImageResource(Themes.icon(id))
        title.text = Themes.name(id)
        still.setImageResource(Themes.still(context, id, night, wide))
        stillKey = Triple(id, night, wide)
    }
    private var stillKey = Triple("horse", false, false)

    fun setVehicle(label: VehicleLabel, menusAllowed: Boolean, adPlaying: Boolean) {
        subtitle.text = label.text + if (adPlaying) " · 광고 재생 중" else ""
        dot.background = when (label) {
            VehicleLabel.STOPPED -> oval(0xFF1FA463.toInt(), 0)
            VehicleLabel.DRIVING -> oval(0xFFE08A00.toInt(), 0)
            VehicleLabel.CHECKING -> oval(Color.TRANSPARENT, tokens.subText)
        }
        chevron.visibility = if (menusAllowed) View.VISIBLE else View.GONE
        titleBlock.isClickable = menusAllowed
        settingsButton.visibility = if (menusAllowed) View.VISIBLE else View.INVISIBLE
        lock.visibility = if (label == VehicleLabel.CHECKING) View.VISIBLE else View.GONE
    }

    fun setFigures(total: Int, trip: Int, remaining: Int) {
        figures[0].set("총 포인트", "%,d P".format(total))
        figures[1].set("이번 주행", "+%,d P".format(trip))
        figures[2].set("남은 광고", "${remaining}개")
    }

    fun setAd(progress: AdPlayer.Progress?, sound: Boolean) {
        ad.visibility = if (progress == null) View.GONE else View.VISIBLE
        if (progress == null) return
        adTitle.text = "광고 예시 ${progress.index}"
        adSegments.set(progress.total, progress.index - 1, progress.positionMs.toFloat() / progress.durationMs)
        val left = ((progress.durationMs - progress.positionMs + 999) / 1000).coerceAtLeast(0)
        adTime.text = "${progress.index}/${progress.total} · 0:%02d 남음".format(left)
        (adSound.getChildAt(0) as ImageView).setImageResource(if (sound) R.drawable.ic_home_sound else R.drawable.ic_home_mute)
        adSound.contentDescription = if (sound) "소리 끄기" else "소리 켜기"
    }

    fun showToast(message: String) {
        toast.text = message
        toast.visibility = View.VISIBLE
        hideToast?.let { root.removeCallbacks(it) }
        hideToast = Runnable { toast.visibility = View.GONE }.also { root.postDelayed(it, 3200) }
    }

    fun openThemeMenu(selected: String, night: Boolean) {
        closeOverlay(); menuOpen = true
        overlay.visibility = View.VISIBLE
        overlay.setOnClickListener { actions.onCloseOverlay() }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(tokens.popup, 24f); elevation = dp(20).toFloat()
            setPadding(dp(12), dp(12), dp(12), dp(12)); isClickable = true
        }
        Themes.ids.forEachIndexed { index, id ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(112); setPadding(dp(12), dp(12), dp(12), dp(12))
                if (id == selected) background = rounded(tokens.soft, 16f)
                contentDescription = Themes.name(id) + if (id == selected) ", 사용 중" else ""
                pressable { actions.onThemeSelected(id) }
            }
            row.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(Themes.still(context, id, night, false)); clip(12f)
            }, LinearLayout.LayoutParams(dp(136), dp(88)))
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(12), 0)
                addView(text(Themes.name(id), 28, tokens.text, bold = true))
                if (id == selected) addView(text("사용 중", 24, tokens.subText))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (id == selected) row.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_home_check); imageTintList = ColorStateList.valueOf(tokens.text)
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            if (index > 0) card.addView(View(context).apply { setBackgroundColor(tokens.line) }, LinearLayout.LayoutParams(-1, 1))
            card.addView(row)
        }
        val width = minOf(dp(560), root.width - dp(48))
        overlay.addView(card, FrameLayout.LayoutParams(width, -2).apply { leftMargin = dp(24); topMargin = dp(NAV - 8) })
    }

    fun openSettings(sound: Boolean, reduceMotion: Boolean) {
        closeOverlay(); settingsOpen = true
        overlay.visibility = View.VISIBLE
        overlay.setOnClickListener(null)
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(tokens.background); isClickable = true }
        page.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(32), 0, dp(32), 0)
            addView(iconButton(R.drawable.ic_home_back, "뒤로") { actions.onCloseOverlay() }.also { tint(it, tokens.text, tokens.soft) })
            addView(text("설정", 32, tokens.text, bold = true).apply { setPadding(dp(20), 0, 0, 0) })
        }, LinearLayout.LayoutParams(-1, dp(NAV)))
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(8), dp(32), dp(8)) }
        list.addView(switchRow("광고 소리", "내비 안내가 나오면 줄어들어요", sound) { actions.onSoundToggle() })
        list.addView(switchRow("움직임 줄이기", "장면을 멈춘 그림으로 보여 줘요", reduceMotion) { actions.onReduceMotionToggle() })
        list.addView(infoRow("계정", "시연 계정"))
        list.addView(infoRow("데이터", "이 기기에만 저장된 시연 값"))
        val tools = actions.demoTools()
        if (tools.isNotEmpty()) {
            list.addView(text("시연 도구", 24, tokens.subText).apply { setPadding(0, dp(28), 0, dp(8)) })
            tools.forEach { (label, run) -> list.addView(actionRow(label, run)) }
        }
        page.addView(android.widget.ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        overlay.addView(page, match())
    }

    fun closeOverlay() {
        menuOpen = false; settingsOpen = false
        overlay.removeAllViews(); overlay.visibility = View.GONE
    }

    fun setLock(title: String, body: String) { lockTitle.text = title; lockBody.text = body }

    fun setLockAction(label: String, action: () -> Unit) {
        lockButton.text = label
        lockButton.pressable(action)
    }

    fun showLockAction(show: Boolean) {
        lockButton.visibility = if (show) View.VISIBLE else View.GONE
        lockButton.background = rounded(tokens.filled, 14f)
        lockButton.setTextColor(tokens.onFilled)
    }

    private fun layout(width: Int, height: Int) {
        val nowWide = width.toFloat() / height >= 1.4f
        wide = nowWide
        nav.layoutParams = FrameLayout.LayoutParams(-1, dp(NAV), Gravity.TOP)
        val sceneParams = FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = dp(NAV)
            if (wide) rightMargin = dp(SIDE) else bottomMargin = dp(DOCK)
        }
        scene.layoutParams = sceneParams
        ad.layoutParams = FrameLayout.LayoutParams(sceneParams)
        dock.orientation = if (wide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        dock.layoutParams = if (wide) FrameLayout.LayoutParams(dp(SIDE), -1, Gravity.END).apply { topMargin = dp(NAV) }
            else FrameLayout.LayoutParams(-1, dp(DOCK), Gravity.BOTTOM)
        dock.gravity = if (wide) Gravity.CENTER_VERTICAL else Gravity.CENTER_VERTICAL
        dock.setPadding(if (wide) dp(40) else dp(8), 0, if (wide) dp(40) else dp(8), 0)
        figures.forEachIndexed { index, figure ->
            figure.view.layoutParams = if (wide) LinearLayout.LayoutParams(-1, -2)
                else LinearLayout.LayoutParams(0, -2, if (index == 0) 1.35f else 1f)
            figure.wide = wide
        }
        dock.dividers()
        toast.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = if (wide) dp(24) else dp(DOCK + 24)
            rightMargin = if (wide) dp(SIDE) else 0
        }
        toast.maxWidth = minOf(dp(792), width - dp(48))
        if (stillKey.third != wide) setTheme(stillKey.first, stillKey.second)
        root.requestLayout()
    }

    private fun buildNav() {
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL
        nav.setPadding(dp(32), 0, dp(32), 0)
        appIconImage.imageTintList = ColorStateList.valueOf(Color.WHITE)
        appIcon.addView(appIconImage, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        nav.addView(appIcon, LinearLayout.LayoutParams(dp(56), dp(56)))
        titleBlock.orientation = LinearLayout.VERTICAL
        titleBlock.gravity = Gravity.CENTER_VERTICAL
        titleBlock.minimumHeight = dp(88)
        titleBlock.setPadding(dp(12), 0, dp(12), 0)
        titleBlock.contentDescription = "테마 바꾸기"
        val titleRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        style(title, 32, bold = true)
        titleRow.addView(title)
        titleRow.addView(chevron.apply { setImageResource(R.drawable.ic_home_chevron) }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { leftMargin = dp(8) })
        val subRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        subRow.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(8) })
        style(subtitle, 24)
        subRow.addView(subtitle)
        titleBlock.addView(titleRow); titleBlock.addView(subRow)
        titleBlock.pressable { actions.onThemeMenu() }
        nav.addView(titleBlock, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        nav.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        nav.addView(settingsButton)
    }

    private fun buildDock() {
        figures.forEach { dock.addView(it.view) }
    }

    private fun buildAd() {
        ad.setBackgroundColor(0xFF0E0F11.toInt())
        val center = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        style(adTitle, 48, bold = true); adTitle.setTextColor(Color.WHITE); adTitle.gravity = Gravity.CENTER
        center.addView(adTitle)
        center.addView(text("실제 광고 소재가 재생될 자리", 26, 0xB8FFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
        ad.addView(center, match())
        val chip = text("광고", 24, Color.WHITE, bold = true).apply {
            background = rounded(0x29FFFFFF, 10f); setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        ad.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(24), dp(24), 0, 0) })
        tint(adSound, Color.WHITE, 0x29FFFFFF)
        ad.addView(adSound, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(24), dp(24), 0) })
        val bottom = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        bottom.addView(adSegments, LinearLayout.LayoutParams(-1, dp(8)))
        style(adTime, 24); adTime.setTextColor(Color.WHITE); adTime.setPadding(0, dp(14), 0, 0)
        bottom.addView(adTime)
        ad.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(24), 0, dp(24), dp(24)) })
        ad.isClickable = true
    }

    private fun buildToast() {
        style(toast, 26); toast.gravity = Gravity.CENTER
        toast.setPadding(dp(32), dp(22), dp(32), dp(22))
        toast.elevation = dp(20).toFloat()
        toast.visibility = View.GONE
    }

    private fun buildLock() {
        lock.orientation = LinearLayout.VERTICAL
        lock.gravity = Gravity.CENTER
        lock.isClickable = true
        lock.addView(ProgressBar(context).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(28) })
        style(lockTitle, 36, bold = true); style(lockBody, 28)
        lockTitle.gravity = Gravity.CENTER; lockBody.gravity = Gravity.CENTER
        lock.addView(lockTitle); lock.addView(lockBody, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        style(lockButton, 28, bold = true)
        lockButton.gravity = Gravity.CENTER
        lockButton.setPadding(dp(40), 0, dp(40), 0)
        lockButton.visibility = View.GONE
        lock.addView(lockButton, LinearLayout.LayoutParams(-2, dp(88)).apply { topMargin = dp(36) })
        lock.setPadding(dp(48), dp(48), dp(48), dp(48))
    }

    private fun switchRow(label: String, help: String, on: Boolean, toggle: () -> Unit): View =
        row(label, help).apply {
            addView(SwitchView(context, on, tokens), LinearLayout.LayoutParams(dp(72), dp(40)))
            pressable(toggle)
            contentDescription = "$label, ${if (on) "켜짐" else "꺼짐"}"
        }

    private fun infoRow(label: String, value: String): View = row(label, null).apply { addView(text(value, 26, tokens.subText)) }
    private fun actionRow(label: String, run: () -> Unit): View = row(label, null).apply { pressable(run) }

    private fun row(label: String, help: String?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(104)
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(label, 28, tokens.text))
        help?.let { left.addView(text(it, 24, tokens.subText)) }
        addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        foreground = BottomLine(tokens.line)
    }

    private inner class Figure(private val index: Int) {
        val view = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val label = TextView(context).also { style(it, 24) }
        val value = TextView(context).also { style(it, 36, bold = true); it.fontFeatureSettings = "tnum" }
        var wide = false
            set(v) { field = v; spacing() }
        init { view.addView(label); view.addView(value); spacing() }
        fun set(name: String, number: String) {
            label.text = name; value.text = number
            view.contentDescription = "$name $number"
        }
        fun colors() { label.setTextColor(tokens.subText); value.setTextColor(tokens.text) }
        private fun spacing() {
            value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (index == 0) 48f else 36f)
            if (wide) view.setPadding(0, dp(32), 0, dp(32)) else view.setPadding(dp(24), 0, dp(24), 0)
        }
    }

    private fun LinearLayout.dividers() {
        val horizontal = this.orientation == LinearLayout.HORIZONTAL
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply {
            setColor(tokens.line)
            if (horizontal) setSize(1, dp(96)) else setSize(1, 1)
        }
        dividerPadding = 0
        figures.forEach { it.colors() }
    }

    private fun iconButton(icon: Int, label: String, click: () -> Unit) = FrameLayout(context).apply {
        contentDescription = label
        addView(ImageView(context).apply { setImageResource(icon) }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        pressable(click)
    }

    private fun tint(button: FrameLayout, fg: Int, bg: Int) {
        (button.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(fg)
        button.background = oval(bg, 0)
    }

    /** Pleos button interaction: shrink to 95% while pressed, act on release inside the target. */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.pressable(click: () -> Unit) {
        isClickable = true; isFocusable = true
        setOnClickListener { click() }
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }
            false
        }
    }

    private fun View.clip(radius: Float) {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(radius.toInt()).toFloat())
        }
        clipToOutline = true
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(context).apply { text = value; setTextColor(color); style(this, size, bold) }

    private fun style(view: TextView, size: Int, bold: Boolean = false) {
        // Pleos type scale in dp so vehicle panels keep the specified sizes.
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size.toFloat())
        view.typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        view.includeFontPadding = false
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat() }
    private fun oval(color: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color); if (stroke != 0) setStroke(dp(3), stroke)
    }
    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private class BottomLine(color: Int) : android.graphics.drawable.Drawable() {
        private val paint = Paint().apply { this.color = color }
        override fun draw(canvas: Canvas) { canvas.drawRect(0f, bounds.bottom - 1f, bounds.right.toFloat(), bounds.bottom.toFloat(), paint) }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private class SegmentsView(context: Context) : View(context) {
        private var count = 1; private var index = 0; private var progress = 0f
        private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x47FFFFFF }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val rect = RectF()
        fun set(count: Int, index: Int, progress: Float) { this.count = maxOf(1, count); this.index = index; this.progress = progress.coerceIn(0f, 1f); invalidate() }
        override fun onDraw(canvas: Canvas) {
            val gap = 8 * resources.displayMetrics.density
            val w = (width - gap * (count - 1)) / count
            val r = height / 2f
            for (i in 0 until count) {
                val left = i * (w + gap)
                rect.set(left, 0f, left + w, height.toFloat())
                canvas.drawRoundRect(rect, r, r, back)
                val part = when { i < index -> 1f; i == index -> progress; else -> 0f }
                if (part > 0f) { rect.right = left + w * part; canvas.drawRoundRect(rect, r, r, fill) }
            }
        }
    }

    private class SwitchView(context: Context, private val on: Boolean, private val tokens: PleosTokens) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat(); val r = h / 2
            paint.color = if (on) tokens.filled else tokens.line
            rect.set(0f, 0f, width.toFloat(), h)
            canvas.drawRoundRect(rect, r, r, paint)
            paint.color = if (on) tokens.onFilled else Color.WHITE
            val inset = h * 0.1f
            val cx = if (on) width - r else r
            canvas.drawCircle(cx, r, r - inset, paint)
        }
    }

    companion object {
        const val NAV = 112
        const val DOCK = 176
        const val SIDE = 440
    }
}

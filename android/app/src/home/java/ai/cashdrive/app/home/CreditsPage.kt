package ai.cashdrive.app.home

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import ai.cashdrive.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One credited briefing in the history: its title and glyph, its points and when it was credited (null before the log began). */
data class CreditEntry(val title: String, val icon: Int, val points: Int, val atMs: Long?)

/**
 * One way to use the credits: the stored preference id, the card's title and line, its own glyph, and what
 * the settlement does with the month's credits ([settles], the settlement card's line).
 */
data class Redemption(val id: String, val label: String, val help: String, val icon: Int, val settles: String = "")

/** One day of the week chart: its label (weekday, or "오늘"), the points credited that day, and whether it is today. */
data class DayBar(val label: String, val points: Int, val today: Boolean)

/**
 * The credits page's copy and pure decisions (JVM-tested): the redemption cards, today's chip, the time of a
 * history row, the history rows themselves and where the balance's count-up starts. No Android view
 * dependency. The copy describes the service as it runs (user decision 2026-11-09, design §2-3).
 */
object CreditsFormat {
    const val RATE = "1 크레딧 = 1원"
    const val SETTLEMENT = "다음 정산에 반영 · 매월 1일"
    const val EMPTY_TITLE = "아직 적립 내역이 없어요"
    const val EMPTY_BODY = "주차하고 브리핑을 끝까지 보면 10 크레딧씩 쌓여요"
    /** Under the redemption cards: the choice can change any time and applies from the next settlement. */
    const val REDEMPTION_NOTE = "전환 방식은 언제든 바꿀 수 있어요. 바꾼 방식은 다음 정산부터 적용돼요"
    /** Rows drawn on the page, newest first; the section header still counts every entry. */
    const val HISTORY_LIMIT = 50

    /** In display order, the first one the default. Names only, never a partner's mark (design §9). */
    val REDEMPTIONS: List<Redemption> = listOf(
        Redemption("incar", "인카 페이먼트 자동 적립", "차에서 결제할 때 바로 쓸 수 있어요", R.drawable.ic_pay_card, "인카 페이먼트로 자동 적립돼요"),
        Redemption("bluemembers", "블루멤버스 포인트 전환", "블루멤버스 포인트로 바꿔서 쌓아요", R.drawable.ic_membership, "블루멤버스 포인트로 전환돼요"),
        Redemption("donation", "교통안전 기부", "교통안전 캠페인에 기부해요", R.drawable.ic_safety, "교통안전 캠페인에 기부돼요"),
    )
    val DEFAULT_REDEMPTION: String = REDEMPTIONS.first().id

    /** The week chart's height in points: the best day of the week, never less than [WEEK_MIN_SCALE]. */
    const val WEEK_MIN_SCALE = 30
    private val WEEKDAYS = listOf("월", "화", "수", "목", "금", "토", "일")

    /**
     * The last seven days ending today, oldest first, with the points the credits [log] holds for each
     * device-local day; today's label is "오늘", the others their weekday.
     */
    fun week(log: List<Pair<String, Long>>, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<DayBar> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val perDay = log.groupingBy { Instant.ofEpochMilli(it.second).atZone(zone).toLocalDate() }.eachCount()
        return (6 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            DayBar(if (back == 0) "오늘" else WEEKDAYS[day.dayOfWeek.value - 1], (perDay[day] ?: 0) * CreditsLog.POINTS_PER_TICKET, back == 0)
        }
    }

    /** The settlement card's line: "이번 달 30 크레딧 · 인카 페이먼트로 자동 적립돼요" for the stored [redemption]. */
    fun settlementLine(monthPoints: Int, redemption: String?): String {
        val option = REDEMPTIONS.firstOrNull { it.id == redemptionOrDefault(redemption) } ?: REDEMPTIONS.first()
        return "이번 달 ${balance(monthPoints.coerceAtLeast(0))} 크레딧 · ${option.settles}"
    }

    /** The chart's full height in points for [points] (the week's days). */
    fun weekScale(points: List<Int>): Int = maxOf(WEEK_MIN_SCALE, points.maxOrNull() ?: 0)

    private fun nextSettlement(nowMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().withDayOfMonth(1).plusMonths(1)

    /** The next settlement day, the 1st of next month: "12월 1일". */
    fun settlementDate(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
        nextSettlement(nowMs, zone).let { "${it.monthValue}월 ${it.dayOfMonth}일" }

    /** Whole days from today to the next settlement (at least 1). */
    fun daysToSettlement(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Int {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(today, nextSettlement(nowMs, zone)).toInt().coerceAtLeast(1)
    }

    fun dDay(days: Int): String = "D-${days.coerceAtLeast(0)}"

    /** Points the credits [log] holds for the current calendar month. */
    fun monthPoints(log: List<Pair<String, Long>>, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Int {
        val month = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone))
        return log.count { YearMonth.from(Instant.ofEpochMilli(it.second).atZone(zone)) == month } * CreditsLog.POINTS_PER_TICKET
    }

    /** How far the month has gone, today counted: day of month over the month's length (0..1). */
    fun monthProgress(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Float {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return (today.dayOfMonth.toFloat() / today.lengthOfMonth()).coerceIn(0f, 1f)
    }

    /** The stored preference when it names a card, the default otherwise. */
    fun redemptionOrDefault(id: String?): String = REDEMPTIONS.firstOrNull { it.id == id }?.id ?: DEFAULT_REDEMPTION

    /** A figure with thousands separators ("1,230"), the same in every locale. */
    fun balance(value: Int): String = String.format(Locale.KOREA, "%,d", value)

    /** The hero's chip: "오늘 +20" (never negative). */
    fun today(points: Int): String = "오늘 +${balance(points.coerceAtLeast(0))}"

    /** The history header's count: "3건". */
    fun entries(count: Int): String = "${balance(count.coerceAtLeast(0))}건"

    /**
     * Where the balance counts up from when the page opens: the balance before today's credits, so the
     * run is today's gain. Never below zero; the balance itself (no run) when nothing came in today.
     */
    fun countFrom(balance: Int, todayPoints: Int): Int {
        val total = balance.coerceAtLeast(0)
        return (total - todayPoints.coerceAtLeast(0)).coerceIn(0, total)
    }

    /**
     * When a history row was credited, on the device's calendar: "오늘 14:32", "어제 21:05", "11월 7일
     * 09:12" within the year, "2025년 12월 31일" before it. A day after today (a clock set back) is named
     * by its date.
     */
    fun timeLabel(epochMs: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(zone)
        val day = at.toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val clock = "%02d:%02d".format(Locale.ROOT, at.hour, at.minute)
        return when {
            day == today -> "오늘 $clock"
            day == today.minusDays(1) -> "어제 $clock"
            day.year == today.year -> "${day.monthValue}월 ${day.dayOfMonth}일 $clock"
            else -> "${day.year}년 ${day.monthValue}월 ${day.dayOfMonth}일"
        }
    }

    /**
     * The history rows, newest first, at most [limit]. The ledger holds [creditedCount] briefings in
     * rotation order (entry i played creative i of the rotation); the credits log ([log], oldest first)
     * began with the reward sequence, so its newest times belong to the ledger's newest entries and
     * entries older than the log have no time.
     */
    fun history(creditedCount: Int, log: List<Pair<String, Long>>, limit: Int = HISTORY_LIMIT): List<CreditEntry> {
        val count = creditedCount.coerceAtLeast(0)
        if (count == 0 || limit <= 0) return emptyList()
        val times = log.takeLast(count).map { it.second }
        val untimed = count - times.size
        return (count - 1 downTo maxOf(0, count - limit)).map { index ->
            val creative = BriefingCatalog.forTicket(index)
            CreditEntry(creative.title, iconFor(creative), CreditsLog.POINTS_PER_TICKET, times.getOrNull(index - untimed))
        }
    }

    /**
     * A row's glyph: a self-made briefing's topic, the sign a driver knows it by, whether it plays as cards
     * or as a video (otherwise its first card's glyph), and any other video's play mark.
     */
    fun iconFor(creative: Creative): Int = TOPICS[creative.id] ?: when (creative) {
        is MotionCreative -> creative.briefing.cards.firstOrNull()?.icon ?: R.drawable.ic_brief_check
        is VideoCreative -> R.drawable.ic_brief_video
    }

    /** Topic glyphs read at the history's 28dp; the school zone's first card (the speed sign) does not. */
    private val TOPICS: Map<String, Int> = mapOf(
        "right-turn-stop" to R.drawable.ic_brief_turn_right,
        "school-zone" to R.drawable.ic_brief_school,
        "emergency-yield" to R.drawable.ic_brief_siren,
    )
}

/**
 * The credits page (design §8), two columns on the wide panel:
 * - Start: "보유 크레딧" over the hero, the meter's dark face in both looks: the balance in 64dp amber,
 *   which counts up from the balance before today when the page opens, today's chip ("오늘 +20") and
 *   "1 크레딧 = 1원", with three ticket stubs fanned at its end. Under it "적립 내역": one card of rows,
 *   each the briefing's glyph, its title, when it was credited (credits log) and "+10".
 * - End: "전환 방식": three cards, each its own glyph, title, one line and a radio. The selected card has
 *   the accent edge, an amber badge and, in place of its line, "다음 정산에 반영 · 매월 1일". A footnote
 *   closes the column; the two columns are one height, so with a short history (up to [ALIGNED_ROWS] rows)
 *   the footnote sits on the history card's bottom line, and under the cards otherwise.
 * Rendering only: the balance and the entries come from the session's ledger through the host, today's
 * points and the times from the credits log. Choosing a card asks the host to store the preference; the
 * host opens the page again with it and [select] shows it in place (the page is not rebuilt).
 */
@SuppressLint("SetTextI18n")
internal class CreditsPage(private val kit: PleosKit, private val tokens: PleosTokens, private val actions: HomeView.Actions) {
    /** What the page shows, built by the host from the ledger, the credits log and the stored preference. */
    data class Model(
        val credits: Int,
        val todayPoints: Int,
        val history: List<CreditEntry>,
        val historyCount: Int,
        val redemption: String,
        /** The last seven days for the week chart, oldest first ([CreditsFormat.week]). */
        val week: List<DayBar> = emptyList(),
        /** Points credited this month, for the settlement card. */
        val monthPoints: Int = 0,
    )

    private val context: Context = kit.context
    private val cards = mutableListOf<RedemptionCard>()
    private lateinit var scaffold: PageScaffold
    private lateinit var number: TextView
    private lateinit var fan: TicketFan
    private var chart: WeekChart? = null
    private var settlementLine: TextView? = null
    private var countAnimator: ValueAnimator? = null

    var model: Model? = null
        private set

    fun build(model: Model): View {
        this.model = model
        scaffold = PageScaffold(kit, tokens, "크레딧") { actions.onCloseOverlay() }
        val start = kit.pageColumn()
        start.addView(kit.sectionHeader(tokens, "보유 크레딧", first = true))
        start.addView(hero(model), LinearLayout.LayoutParams(-1, -2))
        start.addView(kit.sectionHeader(tokens, "적립 내역", CreditsFormat.entries(model.historyCount).takeIf { model.historyCount > 0 }))
        start.addView(history(model), LinearLayout.LayoutParams(-1, -2))
        start.addView(kit.sectionHeader(tokens, "최근 7일", CreditsFormat.today(model.todayPoints).takeIf { model.todayPoints > 0 }))
        start.addView(weekCard(model), LinearLayout.LayoutParams(-1, -2))
        val end = kit.pageColumn()
        end.addView(kit.sectionHeader(tokens, "전환 방식", first = true))
        CreditsFormat.REDEMPTIONS.forEachIndexed { index, option ->
            val card = RedemptionCard(option, option.id == model.redemption)
            cards += card
            end.addView(card.view, LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(PageLook.CARD_GAP) })
        }
        end.addView(kit.sectionHeader(tokens, "다음 정산"))
        end.addView(settlementCard(model), LinearLayout.LayoutParams(-1, -2))
        if (model.history.size <= ALIGNED_ROWS) end.addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f))
        end.addView(kit.pageFootnote(tokens, CreditsFormat.REDEMPTION_NOTE), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(PageLook.HEADER_GAP) })
        val columns = Columns(context, dp(PageLook.COLUMN_GAP), dp(PageLook.WIDE_MIN), matchHeights = true)
        columns.addView(start)
        columns.addView(end)
        scaffold.body.addView(columns, LinearLayout.LayoutParams(-1, -2))
        return scaffold
    }

    /**
     * Shows the page. With [animate] (a fresh open, parked with motion) the body rises in, the tickets
     * fan out and the balance counts up through today's gain (600 ms, ease-out cubic); otherwise every
     * value stands at once.
     */
    fun enter(animate: Boolean) {
        scaffold.enter(animate)
        fan.open(animate)
        chart?.grow(animate)
        val current = model ?: return
        val from = CreditsFormat.countFrom(current.credits, current.todayPoints)
        if (animate && from < current.credits) countUp(from, current.credits)
    }

    /** The host stored [id]: the cards follow in place, crossfading when [animate]. */
    fun select(id: String, animate: Boolean) {
        model = model?.copy(redemption = id)
        cards.forEach { it.setSelected(it.option.id == id, animate) }
        model?.let { settlementLine?.text = settlementText(it) }
    }

    /** The page is leaving: a running count-up ends on the balance and the tickets stand fanned. */
    fun dispose() {
        countAnimator?.cancel()
        chart?.grow(animate = false)
        if (::fan.isInitialized) fan.open(animate = false)
        if (::scaffold.isInitialized) scaffold.settle()
    }

    /**
     * The hero: the dark face with the balance and its unit, today's chip and the rate, and the ticket fan
     * in a soft, still amber light (so the hero stands apart from the night cards, which share its face).
     */
    private fun hero(model: Model): View {
        val face = FrameLayout(context).apply {
            val radius = dp(24).toFloat()
            val plate = GradientDrawable().apply { setColor(MeterFace.FACE); cornerRadius = radius; setStroke(1, MeterFace.EDGE) }
            val light = GradientDrawable().apply {
                cornerRadius = radius
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(HERO_LIGHT_RADIUS).toFloat()
                setGradientCenter(HERO_LIGHT_X, 0.5f)
                colors = intArrayOf(HERO_LIGHT, HERO_LIGHT and 0x00FFFFFF)
            }
            // The plate comes first: its rounded outline is the one the face clips to.
            background = LayerDrawable(arrayOf<Drawable>(plate, light))
            // The fanned tickets may reach into the padding, never past the rounded face.
            clipToOutline = true
            minimumHeight = dp(HERO_MIN)
            setPadding(dp(32), dp(28), dp(32), dp(28))
            contentDescription = "보유 크레딧 ${CreditsFormat.balance(model.credits)}, ${CreditsFormat.today(model.todayPoints)}, ${CreditsFormat.RATE}"
            isScreenReaderFocusable = true
        }
        fan = TicketFan(context, kit)
        face.addView(fan, FrameLayout.LayoutParams(dp(TicketFan.WIDTH), dp(TicketFan.HEIGHT), Gravity.END or Gravity.CENTER_VERTICAL))

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        val figure = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = true }
        val final = CreditsFormat.balance(model.credits)
        number = kit.text(final, 64, MeterFace.AMBER, bold = true).apply {
            fontFeatureSettings = "tnum"; letterSpacing = -0.01f; maxLines = 1
            // Right-aligned in a box as wide as the final figure: during the count-up new digits appear on
            // the left and "크레딧" never moves.
            gravity = Gravity.END
        }
        figure.addView(number, LinearLayout.LayoutParams(ceil(number.paint.measureText(final)).toInt() + 2, -2))
        figure.addView(kit.text("크레딧", 28, MeterFace.UNIT), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        column.addView(figure)

        val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val earned = model.todayPoints > 0
        footer.addView(kit.text(CreditsFormat.today(model.todayPoints), 22, if (earned) MeterFace.AMBER else MeterFace.LABEL, bold = true).apply {
            fontFeatureSettings = "tnum"
            background = kit.rounded(if (earned) PageLook.WASH else QUIET_CHIP, 18f)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        })
        footer.addView(kit.text(CreditsFormat.RATE, 22, MeterFace.LABEL), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16) })
        column.addView(footer, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        face.addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        return face
    }

    /** "적립 내역": one card with a row per credited briefing, hairlines between them; or the empty state. */
    private fun history(model: Model): View {
        val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = kit.cardFace(tokens) }
        if (model.history.isEmpty()) {
            card.addView(emptyState(), LinearLayout.LayoutParams(-1, -2))
            return card
        }
        card.setPadding(0, dp(8), 0, dp(8))
        val now = System.currentTimeMillis()
        model.history.forEachIndexed { index, entry ->
            if (index > 0) card.addView(View(context).apply { setBackgroundColor(PageLook.rule(tokens)) }, LinearLayout.LayoutParams(-1, 1).apply {
                // The hairline starts under the words, not under the badge.
                leftMargin = dp(PageLook.CARD_PAD + ROW_BADGE + ROW_GAP); rightMargin = dp(PageLook.CARD_PAD)
            })
            card.addView(historyRow(entry, now), LinearLayout.LayoutParams(-1, -2))
        }
        if (model.historyCount > model.history.size) {
            card.addView(kit.text("최근 ${model.history.size}건까지 보여 줘요", 22, tokens.subText).apply {
                gravity = Gravity.CENTER; setPadding(dp(24), dp(16), dp(24), dp(16))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        return card
    }

    /** One row, 88dp: the amber badge with the briefing's glyph, title over time, "+10" in amber at the end. */
    private fun historyRow(entry: CreditEntry, now: Long): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ROW_HEIGHT)
        setPadding(dp(PageLook.CARD_PAD), dp(16), dp(PageLook.CARD_PAD), dp(16))
        addView(IconBadge.amber(context, kit, tokens, entry.icon, ROW_GLYPH), LinearLayout.LayoutParams(dp(ROW_BADGE), dp(ROW_BADGE)))
        val time = entry.atMs?.let { CreditsFormat.timeLabel(it, now) }
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        words.addView(kit.singleLine(entry.title, 26, tokens.text))
        time?.let { words.addView(kit.singleLine(it, 22, tokens.subText).apply { fontFeatureSettings = "tnum" }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) }) }
        addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(ROW_GAP); rightMargin = dp(ROW_GAP) })
        addView(kit.text("+${CreditsFormat.balance(entry.points)}", 26, tokens.accent, bold = true).apply { fontFeatureSettings = "tnum" })
        contentDescription = listOfNotNull(entry.title, time, "${entry.points} 크레딧").joinToString(", ")
        isScreenReaderFocusable = true
        for (i in 0 until childCount) getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** No credits yet: the stub badge, one line in the text colour and one in grey, centred in the card. */
    private fun emptyState(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(32), dp(40), dp(32), dp(40))
        addView(IconBadge.amber(context, kit, tokens, R.drawable.ic_stub, 32), LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(kit.text(CreditsFormat.EMPTY_TITLE, 26, tokens.text, bold = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        addView(kit.text(CreditsFormat.EMPTY_BODY, 22, tokens.subText).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
    }

    /** "최근 7일": the week's bars in one card, today's in amber, the others in a quieter amber. */
    private fun weekCard(model: Model): View {
        val card = FrameLayout(context).apply {
            background = kit.cardFace(tokens)
            setPadding(dp(PageLook.CARD_PAD), dp(PageLook.CARD_PAD), dp(PageLook.CARD_PAD), dp(20))
        }
        val bars = model.week.ifEmpty { CreditsFormat.week(emptyList()) }
        val view = WeekChart(context, kit, tokens, bars, CreditsFormat.weekScale(bars.map { it.points }))
        view.contentDescription = "최근 7일 적립, " + bars.joinToString(", ") { "${it.label} ${it.points} 크레딧" }
        card.addView(view, FrameLayout.LayoutParams(-1, dp(WeekChart.HEIGHT)))
        chart = view
        return card
    }

    /**
     * "다음 정산": the calendar badge, the date ("12월 1일") with its D-day chip, what this month has gathered
     * and where it goes (the selected redemption), and a bar of how far the month has gone.
     */
    private fun settlementCard(model: Model): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.cardFace(tokens)
            val pad = dp(PageLook.CARD_PAD)
            setPadding(pad, pad, pad, pad)
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(IconBadge.amber(context, kit, tokens, R.drawable.ic_calendar, PageLook.GLYPH), LinearLayout.LayoutParams(dp(PageLook.BADGE), dp(PageLook.BADGE)))
        head.addView(kit.singleLine(CreditsFormat.settlementDate(), 36, tokens.text, bold = true).apply { fontFeatureSettings = "tnum" },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(20) })
        head.addView(kit.text(CreditsFormat.dDay(CreditsFormat.daysToSettlement()), 22, tokens.accent, bold = true).apply {
            fontFeatureSettings = "tnum"
            background = kit.rounded(PageLook.WASH, 18f)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        })
        card.addView(head)
        val line = kit.text(settlementText(model), 22, tokens.subText).apply { maxLines = 2 }
        settlementLine = line
        card.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val progress = CreditsFormat.monthProgress()
        val track = FrameLayout(context).apply { background = kit.rounded(PageLook.rule(tokens), 4f) }
        track.addView(View(context).apply { background = kit.rounded(tokens.accent, 4f); pivotX = 0f; scaleX = progress },
            FrameLayout.LayoutParams(-1, -1))
        card.addView(track, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(20) })
        card.contentDescription = "다음 정산 ${CreditsFormat.settlementDate()}, ${CreditsFormat.dDay(CreditsFormat.daysToSettlement())}, ${settlementText(model)}"
        card.isScreenReaderFocusable = true
        for (i in 0 until card.childCount) card.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        return card
    }

    /** "이번 달 30 크레딧 · 인카 페이먼트로 자동 적립돼요": the month so far and the selected card's settlement. */
    private fun settlementText(model: Model): String = CreditsFormat.settlementLine(model.monthPoints, model.redemption)

    private fun countUp(from: Int, to: Int) {
        countAnimator?.cancel()
        number.text = CreditsFormat.balance(from)
        var shown = from
        countAnimator = ValueAnimator.ofFloat(from.toFloat(), to.toFloat()).apply {
            duration = COUNT_MS
            startDelay = COUNT_DELAY_MS
            interpolator = EASE_OUT_CUBIC
            addUpdateListener {
                val value = (it.animatedValue as Float).roundToInt()
                if (value != shown) { shown = value; number.text = CreditsFormat.balance(value) }
            }
            whenDone { countAnimator = null; number.text = CreditsFormat.balance(to) }
            start()
        }
    }

    private fun dp(value: Int) = kit.dp(value)

    /**
     * One redemption card: the glyph on a badge, the title, one line and the radio; the whole card is the
     * target. Selected, the 2dp accent edge fades in, the badge turns amber, the radio fills and the line
     * gives way to "다음 정산에 반영 · 매월 1일" with a check (crossfades, 200 ms). Both lines share one slot,
     * so the card's height never changes and choosing another card moves nothing.
     */
    private inner class RedemptionCard(val option: Redemption, selected: Boolean) {
        val view = FrameLayout(context)
        private val badge = IconBadge.selectable(context, kit, tokens, option.icon, PageLook.GLYPH)
        private val help = kit.singleLine(option.help, 22, tokens.subText)
        private val settled = LinearLayout(context)
        private val radio = PageRadio(context, kit, tokens, selected)
        private val edge = Edge(context)
        private var checked = selected

        init {
            view.background = kit.cardFace(tokens)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = dp(PageLook.CARD_PAD)
                setPadding(pad, pad, pad, pad)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            row.addView(badge, LinearLayout.LayoutParams(dp(PageLook.BADGE), dp(PageLook.BADGE)))
            val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            words.addView(kit.singleLine(option.label, 28, tokens.text, bold = true))
            val slot = FrameLayout(context)
            slot.addView(help, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            settled.orientation = LinearLayout.HORIZONTAL
            settled.gravity = Gravity.CENTER_VERTICAL
            settled.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_brief_check); imageTintList = ColorStateList.valueOf(tokens.accent)
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            settled.addView(kit.singleLine(CreditsFormat.SETTLEMENT, 22, tokens.accent), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            slot.addView(settled, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            words.addView(slot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            row.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(20); rightMargin = dp(24) })
            row.addView(radio, LinearLayout.LayoutParams(dp(32), dp(32)))
            view.addView(row, FrameLayout.LayoutParams(-1, -2))
            edge.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT); cornerRadius = dp(PageLook.CARD_RADIUS).toFloat(); setStroke(dp(2), tokens.accent)
            }
            edge.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            view.addView(edge, FrameLayout.LayoutParams(-1, -1))
            view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = checked
                }
            }
            kit.pressable(view, PageLook.CARD_PRESS) { actions.onRedemptionSelected(option.id) }
            setSelected(selected, animate = false)
        }

        fun setSelected(on: Boolean, animate: Boolean) {
            checked = on
            badge.setLit(on, animate)
            radio.setChecked(on, animate)
            fade(edge, on, animate)
            fade(settled, on, animate)
            fade(help, !on, animate)
            view.contentDescription = "${option.label}, ${if (on) CreditsFormat.SETTLEMENT else option.help}"
        }
    }

    /**
     * The selected card's accent edge, as big as the card and never bigger. A plain match-parent View would
     * take a whole AT_MOST height (the end column is measured to the start column's height) and stretch the
     * card over its neighbours; this one is 0 until the card measures it exactly.
     */
    private class Edge(context: Context) : View(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension(exactOrZero(widthMeasureSpec), exactOrZero(heightMeasureSpec))

        private fun exactOrZero(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
    }

    private fun fade(view: View, visible: Boolean, animate: Boolean) {
        view.animate().cancel()
        val target = if (visible) 1f else 0f
        if (!animate) { view.alpha = target; return }
        view.animate().alpha(target).setDuration(PageLook.TOGGLE_MS).setInterpolator(EASE_OUT_CUBIC).start()
    }

    private companion object {
        /** History rows up to which the end column's footnote sits on the history card's bottom line. */
        const val ALIGNED_ROWS = 5
        const val HERO_MIN = 216
        const val ROW_HEIGHT = 88
        const val ROW_BADGE = 48
        const val ROW_GLYPH = 28
        const val ROW_GAP = 16
        const val COUNT_MS = 600L
        /** The count-up starts as the page settles, so the eye is on the figure when it moves. */
        const val COUNT_DELAY_MS = 120L
        /** Today's chip with nothing in yet: white at 8% on the face, the label grey. */
        const val QUIET_CHIP = 0x14FFFFFF
        /** The light behind the fan: amber at 14% fading to clear over 280dp, centred on the fan. */
        const val HERO_LIGHT = 0x24E0A93C
        const val HERO_LIGHT_RADIUS = 280
        const val HERO_LIGHT_X = 0.8f
    }
}

/**
 * The week chart: seven columns, oldest left, today right. Each is a rounded track the full height with the
 * day's points filling it from the bottom (today amber, the other days amber at 45%), the figure above a
 * filled bar and the day's label under it (today's in the text colour, bold). Opening the page grows the
 * bars from the bottom (320 ms, 30 ms apart); drawn on the canvas, decorative for accessibility (the card
 * carries the description).
 */
@SuppressLint("ViewConstructor")
private class WeekChart(
    context: Context,
    private val kit: PleosKit,
    private val tokens: PleosTokens,
    private val bars: List<DayBar>,
    private val scale: Int,
) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PageLook.rule(tokens) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val figure = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = kit.dp(20).toFloat(); typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum"
    }
    private val label = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = kit.dp(20).toFloat(); textAlign = Paint.Align.CENTER }
    private val box = android.graphics.RectF()
    private var growth = 1f
    private var animator: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun grow(animate: Boolean) {
        animator?.cancel()
        if (!animate) { growth = 1f; invalidate(); return }
        growth = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GROW_MS + STAGGER_MS * (bars.size - 1)
            startDelay = 80L
            addUpdateListener { growth = it.animatedValue as Float; invalidate() }
            whenDone { animator = null; growth = 1f; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val n = bars.size
        val labelBand = kit.dp(36).toFloat()
        val figureBand = kit.dp(30).toFloat()
        val top = figureBand
        val bottom = height - labelBand
        val slot = width / n.toFloat()
        val barW = minOf(kit.dp(44).toFloat(), slot * 0.56f)
        val radius = barW / 2f
        val total = (GROW_MS + STAGGER_MS * (n - 1)).toFloat()
        bars.forEachIndexed { i, bar ->
            val cx = slot * i + slot / 2f
            box.set(cx - barW / 2f, top, cx + barW / 2f, bottom)
            canvas.drawRoundRect(box, radius, radius, track)
            // Each bar starts STAGGER_MS after its left neighbour and grows over GROW_MS (ease-out cubic).
            val local = ((growth * total - STAGGER_MS * i) / GROW_MS).coerceIn(0f, 1f)
            val share = (bar.points.toFloat() / scale.coerceAtLeast(1)).coerceIn(0f, 1f) * EASE_OUT_CUBIC.getInterpolation(local)
            if (bar.points > 0) {
                val h = maxOf(barW, (bottom - top) * share)
                box.set(cx - barW / 2f, bottom - h, cx + barW / 2f, bottom)
                fill.color = tokens.accent
                fill.alpha = if (bar.today) 255 else 115
                canvas.drawRoundRect(box, radius, radius, fill)
                figure.color = if (bar.today) tokens.accent else tokens.subText
                figure.alpha = (255 * local).toInt()
                canvas.drawText(bar.points.toString(), cx, bottom - h - kit.dp(10), figure)
            }
            label.color = if (bar.today) tokens.text else tokens.subText
            label.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (bar.today) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            canvas.drawText(bar.label, cx, height - kit.dp(6).toFloat(), label)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val HEIGHT = 220
        private const val GROW_MS = 320L
        private const val STAGGER_MS = 30L
    }
}

/**
 * The hero's decoration: three ticket stubs fanned like a wallet, drawn exactly like the meter's stub row
 * at 2.6x (not issued, watched, ready; the ready one in front). When the page opens they spread from one
 * stack (360 ms, ease-out cubic, 40 ms apart, front first): rotation, translation and alpha only.
 * Decorative for accessibility.
 */
@SuppressLint("ViewConstructor")
private class TicketFan(context: Context, private val kit: PleosKit) : FrameLayout(context) {
    private val tickets = List(3) { Ticket(context, it) }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipChildren = false
        tickets.forEach { addView(it, LayoutParams(kit.dp(TICKET_W), kit.dp(TICKET_H), Gravity.CENTER)) }
        open(animate = false)
    }

    fun open(animate: Boolean) {
        val front = REST.last()
        tickets.forEachIndexed { index, ticket ->
            ticket.animate().cancel()
            val rest = REST[index]
            if (!animate) {
                ticket.rotation = rest.turn; ticket.translationX = px(rest.dx); ticket.translationY = px(rest.dy); ticket.alpha = 1f
                return@forEachIndexed
            }
            ticket.rotation = front.turn; ticket.translationX = px(front.dx); ticket.translationY = px(front.dy)
            ticket.alpha = if (index == tickets.lastIndex) 1f else 0f
            ticket.animate().rotation(rest.turn).translationX(px(rest.dx)).translationY(px(rest.dy)).alpha(1f)
                .setStartDelay(OPEN_DELAY_MS + STAGGER_MS * (tickets.lastIndex - index))
                .setDuration(OPEN_MS).setInterpolator(EASE_OUT_CUBIC).start()
        }
    }

    private fun px(dp: Int) = kit.dp(dp).toFloat()

    /** One stub at the meter's 44x30dp proportions, scaled to the view, so the strokes and dots scale with it. */
    private class Ticket(context: Context, private val look: Int) : View(context) {
        private val density = resources.displayMetrics.density
        private val painter = StubPainter(density).apply { size(STUB_W * density, STUB_H * density) }
        /** The meter stub's outline (corners 14%, notches 18% of the height), filled with the face under a watched stub. */
        private val base = Path().apply {
            val w = STUB_W * density; val h = STUB_H * density
            addRoundRect(0f, 0f, w, h, h * 0.14f, h * 0.14f, Path.Direction.CW)
            op(Path().apply {
                addCircle(0f, h / 2f, h * 0.18f, Path.Direction.CW)
                addCircle(w, h / 2f, h * 0.18f, Path.Direction.CW)
            }, Path.Op.DIFFERENCE)
        }
        private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MeterFace.FACE }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.scale(width / (STUB_W * density), height / (STUB_H * density))
            when (look) {
                0 -> painter.drawEmpty(canvas)
                1 -> { canvas.drawPath(base, face); painter.drawWatched(canvas) }
                else -> painter.drawReady(canvas)
            }
            canvas.restore()
        }
    }

    private data class Rest(val turn: Float, val dx: Int, val dy: Int)

    companion object {
        const val WIDTH = 232
        const val HEIGHT = 160
        private const val STUB_W = 44f
        private const val STUB_H = 30f
        private const val TICKET_W = 114
        private const val TICKET_H = 78
        private const val OPEN_MS = 360L
        private const val OPEN_DELAY_MS = 80L
        private const val STAGGER_MS = 40L
        /** Not issued (back), watched, ready (front): turned and shifted up and to the end like a held fan. */
        private val REST = listOf(Rest(14f, 40, -22), Rest(4f, 18, -8), Rest(-8f, -10, 14))
    }
}

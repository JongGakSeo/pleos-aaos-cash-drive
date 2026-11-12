package ai.cashdrive.app.home

import ai.cashdrive.app.BuildConfig
import ai.cashdrive.app.R
import kotlin.math.roundToLong

/** One card of a briefing: a monochrome icon, one sentence (40 characters or fewer) and its on-screen time. */
data class BriefingCard(val icon: Int, val line: String, val seconds: Float)

/**
 * A self-made briefing shown for one ticket. [basis] is the bottom line of the player ("자체 제작 · 근거: ...").
 * [video] is its silent animated clip under assets/, captioned with the cards' sentences on the cards'
 * own clock; without one the cards themselves play.
 */
data class Briefing(
    val id: String,
    val title: String,
    val cards: List<BriefingCard>,
    val basis: String,
    val video: String? = null,
)

/**
 * The bundled creatives. Every self-made text here is the reviewed wording from
 * planning/content/briefings-2026-11-08.md, copied verbatim: statute references only, no agency
 * names, logos or "제공/감수/협력" credits, no fines. Each self-made briefing plays as a 15-second
 * animated clip drawn for it (tools/video/briefings/, rendered by tools/video/render_briefing.mjs, record
 * in planning/content/briefings-video.md) with its card sentences as the app's captions; the cards stay the
 * wording's source of truth and stand in when a clip cannot be shown. The one third-party creative is the
 * PSA excerpt ([psa]; frames and build record in planning/content/psa/, permission record in
 * planning/psa-permission.md). This object holds data and timing only, so it has no Android
 * view dependency and runs in plain JVM unit tests.
 */
object BriefingCatalog {
    /** Every ticket plays for this long; the cards of each briefing add up to it and the PSA excerpt is cut to it. */
    const val DURATION_MS = 15_000L

    /**
     * Whether the PSA excerpt takes the first slot of the rotation (the welcome ticket). It follows the build
     * switch `-Pkilomoa.includePsa=true` (android/app/build.gradle.kts), which also puts the clip into the APK,
     * and stays off until the permission record is complete; there is no settings UI for this on purpose.
     */
    val INCLUDE_PSA: Boolean = BuildConfig.INCLUDE_PSA

    /** Three self-made briefings, 15 seconds each (4 s + 4 s + 4 s + 3 s). The fourth card is the basis card. */
    val publicService: List<Briefing> = listOf(
        Briefing(
            id = RIGHT_TURN_ID,
            title = "우회전, 먼저 멈춰요",
            cards = listOf(
                BriefingCard(R.drawable.ic_brief_stop, "앞 신호가 빨간불이면 횡단보도 앞에서 먼저 멈추고 우회전해요", 4f),
                BriefingCard(R.drawable.ic_brief_pedestrian, "건너는 사람, 건너려는 사람이 있으면 멈춰서 기다려요", 4f),
                BriefingCard(R.drawable.ic_brief_turn_right, "우회전 신호등이 있는 곳은 그 신호를 따라요. 빨간불엔 금지예요", 4f),
                BriefingCard(R.drawable.ic_brief_check, "자체 제작 · 도로교통법 시행규칙 별표 2", 3f),
            ),
            basis = "자체 제작 · 근거: 도로교통법 시행규칙 별표2 (시행 2023-01-22)",
            video = "creatives/brief-right-turn.mp4",
        ),
        Briefing(
            id = "school-zone",
            title = "어린이 보호구역에서는",
            cards = listOf(
                BriefingCard(R.drawable.ic_brief_speed_30, "어린이 보호구역이에요. 표지판 제한속도를 꼭 지켜요", 4f),
                BriefingCard(R.drawable.ic_brief_no_parking, "보호구역 안에서는 잠깐이라도 차를 세우거나 주차하지 않아요", 4f),
                BriefingCard(R.drawable.ic_brief_school, "신호등 없는 횡단보도 앞에선 사람이 없어도 일단 멈춰요", 4f),
                BriefingCard(R.drawable.ic_brief_check, "자체 제작 · 도로교통법 제12조·제27조·제32조", 3f),
            ),
            basis = "자체 제작 · 근거: 도로교통법 제27조 (시행 2022-07-12)",
            video = "creatives/brief-school-zone.mp4",
        ),
        Briefing(
            id = "emergency-yield",
            title = "사이렌이 들리면",
            cards = listOf(
                BriefingCard(R.drawable.ic_brief_siren, "사이렌이 들리면 긴급차가 어디서 오는지 먼저 살펴요", 4f),
                BriefingCard(R.drawable.ic_brief_stop, "교차로나 그 근처라면 교차로를 피해서 일시정지해요", 4f),
                BriefingCard(R.drawable.ic_brief_yield, "그 밖의 곳에서는 긴급차가 먼저 갈 수 있게 길을 양보해요", 4f),
                BriefingCard(R.drawable.ic_brief_check, "자체 제작 · 도로교통법 제29조", 3f),
            ),
            basis = "자체 제작 · 근거: 도로교통법 제29조 (시행 2019-03-28)",
            video = "creatives/brief-emergency-yield.mp4",
        ),
    )

    /**
     * The 15-second excerpt of the Gyeonggi Bukbu police PSA "올바른 우회전 방법은?", built by
     * tools/video/make_psa.py from five of the captured frames (3 s each). The captions are cue points
     * on the ticket timeline, one per frame, drawn by the app over the silent video.
     */
    val psa: VideoCreative = VideoCreative(
        id = "psa-right-turn",
        title = "올바른 우회전 방법은?",
        asset = "creatives/psa-right-turn.mp4",
        captions = listOf(
            Caption(0, "① 전방 차량 신호가 적색이면 반드시 일시정지"),
            Caption(3_000, "보행자가 건너고 있거나 건너려 한다면 기다렸다가 우회전"),
            Caption(6_000, "② 우회전 전용 신호기가 있으면 신호에 따라 우회전"),
            Caption(9_000, "③ 차량·횡단보도 신호가 모두 녹색이면 보행자가 없을 때 서행하며 우회전"),
            Caption(12_000, "모두의 안전을 위해 신호를 꼭 지켜주세요"),
        ),
        source = "경기북부경찰청 · 올바른 우회전 방법은?",
        width = 720,
        height = 1280,
    )

    /** The three creatives tickets cycle through; the welcome ticket (index 0) plays the first one. */
    val rotation: List<Creative> = rotationFor(INCLUDE_PSA)

    /**
     * With the PSA the rotation is [PSA, school zone, emergency yield]: the self-made right-turn
     * briefing covers the same topic and steps aside. Without it the three self-made briefings cycle.
     * Every entry is a video.
     */
    fun rotationFor(includePsa: Boolean): List<Creative> {
        val own = publicService.map(::creativeFor)
        return if (includePsa) listOf(psa) + own.filter { it.id != RIGHT_TURN_ID } else own
    }

    /**
     * How [briefing] plays: its clip ([Briefing.video], [CLIP_SIZE] square) with the card sentences as
     * captions and the basis as the source line, its cards standing in should the clip fail; a briefing
     * without a clip plays its cards.
     */
    fun creativeFor(briefing: Briefing): Creative = briefing.video?.let { asset ->
        VideoCreative(
            id = briefing.id,
            title = briefing.title,
            asset = asset,
            captions = captionsFor(briefing),
            source = briefing.basis,
            width = CLIP_SIZE,
            height = CLIP_SIZE,
            standIn = briefing,
        )
    } ?: MotionCreative(briefing)

    /** One caption per card, each starting where the card would come on (4 s + 4 s + 4 s + 3 s: 0, 4, 8, 12 s). */
    fun captionsFor(briefing: Briefing): List<Caption> {
        var startMs = 0L
        return briefing.cards.map { card -> Caption(startMs, card.line).also { startMs += (card.seconds * 1_000).roundToLong() } }
    }

    /**
     * Picks the creative for the next ticket. [index] is the number of tickets played so far, so the
     * welcome ticket is index 0 (a negative index is treated as 0).
     */
    fun forTicket(index: Int): Creative = rotation[index.coerceAtLeast(0) % rotation.size]

    /** True once every creative of the rotation has been seen, so the player shows "다시 보기". */
    fun isReplay(index: Int): Boolean = index.coerceAtLeast(0) >= rotation.size

    /** The title credited for ticket [index], for the credits history. */
    fun titleFor(index: Int): String = forTicket(index).title

    /** The card on screen at [positionMs] of the ticket; before 0 the first card, past the end the last one. */
    fun cardAt(briefing: Briefing, positionMs: Long): BriefingCard {
        require(briefing.cards.isNotEmpty()) { "briefing ${briefing.id} has no cards" }
        var endMs = 0.0
        for (card in briefing.cards) {
            endMs += card.seconds * 1_000.0
            if (positionMs < endMs) return card
        }
        return briefing.cards.last()
    }

    private const val RIGHT_TURN_ID = "right-turn-stop"

    /** The self-made clips are 1080 x 1080: a square fills the theater's stage in both layouts. */
    private const val CLIP_SIZE = 1080
}

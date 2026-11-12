package ai.cashdrive.app.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import ai.cashdrive.app.R

/**
 * The "사용 기술" page behind the top bar's "소개" chip (2026-11-11, planning/specs/2026-11-11-3d-themes-design.md):
 * how the scene was made. The question and one line, then two product cards side by side (stacked on a
 * narrow panel). "VARCO 3D": one line and the pony's four production stills (the team's drawing, the 3D
 * model, the rig, a motion) where a text-made scene would show its prompt. "VARCO Sound": planned, for the
 * sound effects and background sound, with the product's logo in the same place. Each card's
 * "사용 기술 알아보기" shows the product site's QR code in a small dialog over the page, to scan with a phone.
 * Under the cards: the credit line and two text buttons, the privacy notice and the content inquiry's mail
 * QR code.
 *
 * Text and pictures only. The QR codes are drawables made at development time and nothing opens a
 * browser or sends anything from the car. Like every page it is open only in park.
 */
internal class AboutPage(private val kit: PleosKit, private val tokens: PleosTokens, private val actions: HomeView.Actions) {
    private val context: Context = kit.context
    private lateinit var scaffold: PageScaffold
    private lateinit var frame: FrameLayout
    private var dialog: View? = null

    fun build(): View {
        scaffold = PageScaffold(kit, tokens, "사용 기술") { actions.onCloseOverlay() }
        val body = scaffold.body
        body.addView(kit.text(TITLE, 32, tokens.text, bold = true))
        body.addView(kit.text(INTRO, 22, tokens.subText), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val cards = Columns(context, dp(PageLook.COLUMN_GAP), dp(PageLook.WIDE_MIN), matchHeights = true)
        cards.addView(productCard("VARCO 3D", null, THREE_D, steps(), SITE_3D))
        cards.addView(productCard("VARCO Sound", PLANNED, SOUND, soundLogo(), SITE_SOUND, fill = true))
        body.addView(cards, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(PageLook.COLUMN_GAP) })
        body.addView(kit.pageFootnote(tokens, CREDIT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(PageLook.HEADER_GAP) })
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(textButton("개인정보 처리방침") { showNotice("개인정보 처리방침", PRIVACY) })
            addView(textButton("콘텐츠 문의") { showQr(CONTACT) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(PageLook.CARD_GAP) })
        frame = FrameLayout(context).apply { addView(scaffold, FrameLayout.LayoutParams(-1, -1)) }
        return frame
    }

    fun enter(animate: Boolean) = scaffold.enter(animate)

    fun dispose() {
        if (::scaffold.isInitialized) scaffold.settle()
    }

    /** Closes the dialog over the page if one is open and says whether it was (the system's back). */
    fun back(): Boolean {
        val open = dialog ?: return false
        frame.removeView(open)
        scaffold.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        dialog = null
        return true
    }

    /**
     * One product: the 28dp name (with an amber chip when it is only planned), a 22dp line, the production
     * stills if any, and at the card's foot, under a hairline, the row that shows the product site's QR code.
     */
    private fun productCard(name: String, chip: String?, line: String, details: View?, site: Site, fill: Boolean = false): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = kit.cardFace(tokens)
        setPadding(dp(32), dp(32), dp(32), dp(8))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(kit.singleLine(name, 28, tokens.text, bold = true), LinearLayout.LayoutParams(-2, -2))
            if (chip != null) addView(kit.text(chip, 20, tokens.accent, bold = true).apply {
                setPadding(dp(12), dp(4), dp(12), dp(4)); background = kit.rounded(PageLook.WASH, 14f)
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        })
        addView(kit.text(line, 22, tokens.subText), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        // The stills keep their own height and the rest stays free; a logo takes all the room down to the rule.
        if (details != null && fill) addView(details, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(24) })
        else {
            if (details != null) addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
            addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f))
        }
        addView(View(context).apply { setBackgroundColor(PageLook.rule(tokens)) }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(24) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(80)
            contentDescription = "$name, $MORE"
            addView(kit.singleLine(MORE, 26, tokens.text), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_home_back); rotation = 180f; imageTintList = ColorStateList.valueOf(tokens.subText)
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            kit.pressable(this, PageLook.CARD_PRESS) { showQr(site) }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /** The pony's four production stills in a row, each a square picture on a white plate over its caption. */
    private fun steps(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        contentDescription = "$STEPS_LABEL: ${STEPS.joinToString(", ") { it.second }}"
        addView(kit.singleLine(STEPS_LABEL, 20, tokens.subText).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            STEPS.forEachIndexed { index, (picture, caption) ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(SquareImage(context).apply {
                        setImageResource(picture); scaleType = ImageView.ScaleType.CENTER_CROP
                        clipToOutline = true
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                        }
                    }, LinearLayout.LayoutParams(-1, -2))
                    addView(kit.singleLine("${index + 1}. $caption", 20, tokens.text).apply { gravity = Gravity.CENTER_HORIZONTAL },
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(12) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    /** The VARCO Sound logo (as on sound.varco.ai), centred on a white plate that fills the room the other card gives its stills. */
    private fun soundLogo(): View = FrameLayout(context).apply {
        background = kit.rounded(LOGO_PLATE, 16f)
        contentDescription = "VARCO Sound 로고"
        addView(ImageView(context).apply {
            setImageResource(R.drawable.about_varco_sound_logo); adjustViewBounds = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(520), -2, Gravity.CENTER))
        minimumHeight = dp(160)
    }

    private fun textButton(label: String, click: () -> Unit): View = kit.singleLine(label, 22, tokens.text).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(64)
        setPadding(dp(16), 0, dp(16), 0)
        kit.pressable(this, click)
    }

    /** A site's QR code on its white plate, the line that says what scanning does and the address, as a dialog. */
    private fun showQr(site: Site) = showDialog(site.title) { card ->
        card.addView(ImageView(context).apply { setImageResource(site.qr); contentDescription = "${site.title} QR 코드" },
            LinearLayout.LayoutParams(dp(320), dp(320)).apply { topMargin = dp(20); gravity = Gravity.CENTER_HORIZONTAL })
        card.addView(kit.text(site.line, 24, tokens.text).apply { gravity = Gravity.CENTER_HORIZONTAL },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        card.addView(kit.singleLine(site.address, 22, tokens.subText).apply { gravity = Gravity.CENTER_HORIZONTAL },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun showNotice(title: String, text: String) = showDialog(title) { card ->
        card.addView(kit.text(text, 22, tokens.subText), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }

    /** A card over a scrim: the title, [fill]'s content and "닫기". A tap on the scrim closes it too. */
    private fun showDialog(title: String, fill: (LinearLayout) -> Unit) {
        back()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.cardFace(tokens)
            setPadding(dp(32), dp(32), dp(32), dp(16))
            isClickable = true
            addView(kit.singleLine(title, 28, tokens.text, bold = true))
        }
        fill(card)
        card.addView(kit.singleLine("닫기", 24, tokens.text, bold = true).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(64)
            setPadding(dp(24), 0, dp(24), 0)
            kit.pressable(this) { back() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); gravity = Gravity.END })
        val scrim = FrameLayout(context).apply {
            setBackgroundColor(SCRIM)
            setOnClickListener { back() }
            addView(card, FrameLayout.LayoutParams(dp(600), -2, Gravity.CENTER))
        }
        scaffold.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        frame.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        dialog = scrim
    }

    private fun dp(value: Int) = kit.dp(value)

    /** As tall as it is wide. */
    private class SquareImage(context: Context) : ImageView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }

    /** What a QR dialog shows: its title, the code, the line under it and the address in words. */
    private class Site(val title: String, val qr: Int, val line: String, val address: String)

    private companion object {
        const val TITLE = "이 장면은 어떻게 만들었을까요?"
        const val INTRO = "장면의 캐릭터는 VARCO 3D로 미리 제작했습니다. 효과음과 배경음은 VARCO Sound로 만들 예정입니다."
        const val THREE_D = "팀이 그린 캐릭터를 VARCO 3D에서 입체 모델로 만들고, 뼈대와 동작을 입혔어요."
        const val SOUND = "달리는 소리 같은 효과음과 배경음을 VARCO Sound로 만들어 넣을 예정이에요."
        const val PLANNED = "추후 예정"
        const val MORE = "사용 기술 알아보기"
        const val CREDIT = "3D · VARCO 3D / 사운드 · VARCO Sound(예정)"
        const val STEPS_LABEL = "제작 과정 · 초원 말의 조랑말"
        const val SCAN = "휴대폰으로 스캔하고 직접 만들어 보세요."
        /** The same facts the permission gate and the README state: speed and gear only, no location, a ledger on the device. */
        const val PRIVACY = "킬로모아는 차량의 속도와 기어, 화면 제한 상태만 읽어요. 위치와 이동 경로는 읽지 않아요. " +
            "크레딧과 시청 기록은 이 기기에만 저장돼요.\n\n" +
            "QR 코드는 휴대폰에서 각 서비스의 공식 사이트를 열고, 그곳의 개인정보 처리방침이 적용돼요. " +
            "문의 메일은 휴대폰에서 직접 보낼 때만 전송돼요."
        const val SCRIM = 0x66000000
        const val LOGO_PLATE = 0xFFFFFFFF.toInt()
        val STEPS = listOf(
            R.drawable.about_step_1_art to "그림",
            R.drawable.about_step_2_model to "3D 모델",
            R.drawable.about_step_3_rig to "뼈대",
            R.drawable.about_step_4_motion to "동작",
        )
        val SITE_3D = Site("VARCO 3D", R.drawable.qr_varco_3d, SCAN, "3d.varco.ai")
        val SITE_SOUND = Site("VARCO Sound", R.drawable.qr_varco_sound, SCAN, "sound.varco.ai")
        val CONTACT = Site("콘텐츠 문의", R.drawable.qr_contact, "휴대폰으로 스캔하면 문의 메일을 쓸 수 있어요.", "crabyshy@gmail.com")
    }
}

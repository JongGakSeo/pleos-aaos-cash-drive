package ai.cashdrive.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.cashdrive.app.network.SandboxApi
import ai.cashdrive.app.network.TokenVault
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Accessibility actions verify screen logic, not physical tap coordinates or CCP compliance. */
@RunWith(AndroidJUnit4::class)
class SemanticFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private fun matches(text: String, exact: Boolean = false): List<AccessibilityNodeInfo> =
        automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty().filter {
            it.packageName?.toString() == context.packageName && it.isVisibleToUser &&
                (!exact || it.text?.toString() == text)
        }

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(150)
        }
        fail("Timed out: $description")
    }
    private fun click(label: String) = waitFor("click $label") {
        matches(label, exact = true).firstOrNull { it.isClickable && it.isEnabled }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }
    private fun sees(text: String) = waitFor(text) { matches(text).isNotEmpty() }
    private fun metric(label: String, value: String) = waitFor("$label = $value") {
        matches(label, exact = true).any { labelNode ->
            val parent = labelNode.parent
            parent != null && (0 until parent.childCount).any { parent.getChild(it)?.text?.toString() == value }
        }
    }
    private fun serverBalance(): Long {
        sees("서버 확정 ")
        return Regex("서버 확정 (\\d+)P").find(matches("서버 확정 ").first().text.toString())!!.groupValues[1].toLong()
    }

    @Test fun mockOnboardingAndSandboxCreditRecoverFromRevocation() {
        assumeTrue("Demo variant only", BuildConfig.DEMO_SIGNALS)
        // Explicit fixture reset: this test is only for the project-owned demo AVD.
        context.getSharedPreferences("cash_drive_local", 0).edit().clear().commit()
        // A new isolated server DB cannot recognize a bearer saved by an earlier test run.
        // Preserve it without exposing it; start this UI fixture explicitly signed out.
        val fixtureVault = TokenVault(context)
        val previousSession = fixtureVault.load()
        fixtureVault.clear()
        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            click("시작하기")
            sees("안전 상태 확인 중")
            assertTrue(matches("설정", exact = true).isEmpty())
            click("UXR 제한 데모 · 비제한으로 전환")
            sees("안전 상태 확인 중")
            assertTrue(matches("로그인", exact = true).isEmpty())
            click("기어 데모: 미확인 · 다음")
            click("로그인")
            click("Google · QR 로그인 데모")
            sees("실제 스캔 불가")
            click("데모 계정으로 계속")
            sees("안전한 시점에 확인")
            click("다시 보지 않기")
            click("계속")
            click("선택 완료")
            metric("확정 포인트", "0 P")
            click("데모 거리 +1km → 대기 10P")
            metric("적립 대기 · 이번 주행", "10 P · 10 P")
            metric("확정 포인트", "0 P")
            click("광고 안내 · 10P · 5초 데모")
            click("광고 보고 적립")
            sees("완료 확인 대기 · 10P")
            metric("확정 포인트", "0 P")
            click("확인하고 적립 10P")
            metric("확정 포인트", "10 P")
            click("설정")
            click("로컬 서버 샌드박스")
            click("로컬 시험 계정 연결")
            sees("연결됨 · 서버 원장 기준")
            val baseline = serverBalance()
            click("합성 거리 +1km 전송 / 실패 재시도")
            sees("CLIENT_REPORTED_UNVERIFIED")
            click("합성 광고 6초 시작 · 10P")
            sees("합성 완료 증명 저장")
            assertEquals(baseline, serverBalance())
            click("서버 확인하고 10P 받기")
            sees("서버 원장에 한 번 적립됨")
            assertEquals(baseline + 10, serverBalance())

            // Server-side revocation must clear stale displayed credentials and allow reconnect.
            val saved = checkNotNull(TokenVault(context).load())
            SandboxApi("http://10.0.2.2:8787", true).logout(saved.accessToken)
            click("서버 잔액·대기 새로고침")
            sees("로그인이 만료되었습니다")
            assertTrue(matches("서버 확정 ").isEmpty())
            click("로컬 시험 계정 연결")
            sees("연결됨 · 서버 원장 기준")
            assertEquals(baseline + 10, serverBalance())
            context.openFileOutput("semantic-ui-server.png", 0).use {
                checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            activity?.let { opened -> instrumentation.runOnMainSync { opened.finish() } }
            if (previousSession == null) fixtureVault.clear() else fixtureVault.save(previousSession)
        }
    }
}

package ai.cashdrive.core

import ai.cashdrive.core.render.PreviewSession
import ai.cashdrive.core.safety.SafetyState
import org.junit.Assert.*
import org.junit.Test

class PreviewSessionTest {
    @Test fun unknownOrHiddenCannotStart() {
        val session = PreviewSession()
        session.setVisible(true)
        for (state in listOf(SafetyState.UNKNOWN, SafetyState.RESTRICTED, SafetyState.STOPPED_UNQUALIFIED)) {
            session.setSafety(state)
            assertFalse(session.start().playing)
        }
        session.setVisible(false)
        session.setSafety(SafetyState.PARKED_ALLOWED)
        assertFalse(session.start().playing)
    }
    @Test fun safetyRecoveryRequiresAnotherExplicitStart() {
        val session = PreviewSession()
        session.setVisible(true); session.setSafety(SafetyState.PARKED_ALLOWED)
        assertTrue(session.start().playing)
        val revoked = session.setSafety(SafetyState.UNKNOWN)
        assertFalse(revoked.playing)
        assertFalse(session.setSafety(SafetyState.PARKED_ALLOWED).playing)
        val restarted = session.start()
        assertTrue(restarted.playing)
        assertTrue(restarted.generation > revoked.generation)
    }
    @Test fun backgroundAndResizeStopDoNotResumeImplicitly() {
        val session = PreviewSession()
        session.setVisible(true); session.setSafety(SafetyState.PARKED_ALLOWED); session.start()
        session.setVisible(false)
        assertFalse(session.setVisible(true).playing)
        session.start()
        assertFalse(session.stop().playing)
        assertFalse(session.setSafety(SafetyState.PARKED_ALLOWED).playing)
    }
}

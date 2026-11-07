package ai.cashdrive.core.session

import ai.cashdrive.core.rewards.RewardTicket
import ai.cashdrive.core.rewards.WalletSnapshot
import ai.cashdrive.core.safety.SafetyState

/** UI contract v1. All points in LOCAL_MOCK are demonstration values, never money. */
interface CashDriveSession {
    val state: CashDriveState
    /** Called immediately and after each change, on the caller's thread. Close on UI disposal. */
    fun observe(listener: (CashDriveState) -> Unit): AutoCloseable
    fun startAd(ticketId: String): SessionResult
    /** Explicit MOCK completion signal; this is not a provider callback or SSV. */
    fun completeAd(ticketId: String): SessionResult
    fun confirm(ticketId: String): SessionResult
    /** Dismiss only. Completed but unconfirmed tickets remain claimable. */
    fun receiveLater(ticketId: String): SessionResult
    fun selectTheme(themeId: String): SessionResult
}

data class CashDriveState(
    val accountId: String,
    val safetyState: SafetyState,
    val confirmedPoints: Int,
    val tripCandidatePoints: Int,
    /** PENDING tickets needing a new ad; excludes playing/awaiting/credited tickets. */
    val remainingAdCount: Int,
    val awaitingConfirmationCount: Int,
    val themeId: String,
    val tickets: List<RewardTicket>,
    val storageAvailable: Boolean,
    val mode: String = "LOCAL_MOCK",
) {
    val actionsAllowed: Boolean get() = storageAvailable && safetyState == SafetyState.PARKED_ALLOWED
}

enum class SessionCode { OK, LOCKED, UNKNOWN_TICKET, INVALID_TRANSITION, STORAGE_ERROR }
data class SessionResult(val code: SessionCode, val state: CashDriveState)

object ThemeIds {
    const val DEFAULT = "horse"
    val supported: Set<String> = setOf("horse", "mine", "mining")
    /** Missing, legacy gpu, blank, or unsupported values all use horse. */
    fun normalize(value: String?): String = value?.takeIf { it in supported } ?: DEFAULT
}

data class DemoAccountRecord(
    val wallet: WalletSnapshot,
    val themeId: String? = ThemeIds.DEFAULT,
    val tripId: String = "demo-initial",
    val tripCandidatePoints: Int = 0,
)

/**
 * Local MOCK persistence boundary. update must atomically read, transform, and durably commit
 * one account. Throw on failure without changing storage; never silently discard a ledger.
 * Implementations serialize updates across session instances and keep accounts isolated.
 */
interface DemoAccountStore {
    fun read(accountId: String): DemoAccountRecord?
    fun update(accountId: String, change: (DemoAccountRecord?) -> DemoAccountRecord): DemoAccountRecord
}

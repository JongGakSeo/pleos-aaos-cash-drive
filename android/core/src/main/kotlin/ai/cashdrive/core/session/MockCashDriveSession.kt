package ai.cashdrive.core.session

import ai.cashdrive.core.rewards.RewardWallet
import ai.cashdrive.core.rewards.TicketStatus
import ai.cashdrive.core.rewards.WalletSnapshot
import ai.cashdrive.core.safety.SafetyState

/** Local durable demo ledger. Never construct this as a real ad/payment provider. */
class MockCashDriveSession(private val accountId: String, private val store: DemoAccountStore) : CashDriveSession {
    private var safety = SafetyState.UNKNOWN
    private var available = false
    private var record = emptyRecord()
    private val listeners = linkedSetOf<(CashDriveState) -> Unit>()

    init {
        require(accountId.isNotBlank())
        // A new process/session cannot resume a previous mock timer or stale parked permission.
        reload(recoverPlayback = true)
    }

    @get:Synchronized
    override val state: CashDriveState get() = CashDriveState(
        accountId, safety, record.wallet.confirmedPoints, record.tripCandidatePoints,
        record.wallet.tickets.count { it.status == TicketStatus.PENDING },
        record.wallet.tickets.count { it.status == TicketStatus.AWAITING_CONFIRMATION },
        ThemeIds.normalize(record.themeId), record.wallet.tickets.toList(), available,
    )

    @Synchronized override fun observe(listener: (CashDriveState) -> Unit): AutoCloseable {
        listeners += listener
        listener(state)
        return AutoCloseable { synchronized(this) { listeners -= listener } }
    }

    /** Host sends only reducer-derived state; UI does not pass safety into ad/claim commands. */
    @Synchronized fun updateSafety(value: SafetyState): CashDriveState {
        safety = value
        if (value != SafetyState.PARKED_ALLOWED) reload(recoverPlayback = true)
        publish()
        return state
    }

    /** Retry storage after a failure / refresh another session's durable changes. */
    @Synchronized fun refresh(): CashDriveState {
        reload(recoverPlayback = safety != SafetyState.PARKED_ALLOWED)
        publish()
        return state
    }

    @Synchronized override fun startAd(ticketId: String) = changeTicket(ticketId, setOf(TicketStatus.PENDING, TicketStatus.AD_PLAYING), starting = true) { wallet -> wallet.beginAd(ticketId, safety) }
    @Synchronized override fun completeAd(ticketId: String) = changeTicket(ticketId, setOf(TicketStatus.AD_PLAYING, TicketStatus.AWAITING_CONFIRMATION)) { wallet -> wallet.adCompleted(ticketId, safety) }
    @Synchronized override fun confirm(ticketId: String) = changeTicket(ticketId, setOf(TicketStatus.AWAITING_CONFIRMATION, TicketStatus.CREDITED)) { wallet -> wallet.confirm(ticketId, safety, online = true) }

    @Synchronized override fun receiveLater(ticketId: String): SessionResult {
        if (!state.actionsAllowed) return result(if (available) SessionCode.LOCKED else SessionCode.STORAGE_ERROR)
        return mutate { current ->
            val ticket = current.wallet.tickets.find { it.id == ticketId } ?: throw UnknownTicket()
            require(ticket.status == TicketStatus.AWAITING_CONFIRMATION || ticket.status == TicketStatus.CREDITED)
            current
        }
    }

    @Synchronized override fun selectTheme(themeId: String): SessionResult {
        if (!state.actionsAllowed) return result(if (available) SessionCode.LOCKED else SessionCode.STORAGE_ERROR)
        return mutate { it.copy(themeId = ThemeIds.normalize(themeId)) }
    }

    /** Explicit synthetic fixture input; not vehicle distance. Reusing a ticket is idempotent. */
    @Synchronized fun addMockDistance(ticketId: String, points: Int = 10): SessionResult {
        if (!state.actionsAllowed) return result(if (available) SessionCode.LOCKED else SessionCode.STORAGE_ERROR)
        require(ticketId.isNotBlank() && points > 0)
        return mutate { current ->
            val exists = current.wallet.tickets.any { it.id == ticketId }
            val wallet = liveWallet(current).addPending(ticketId, points)
            current.copy(wallet = wallet, tripCandidatePoints = if (exists) current.tripCandidatePoints else Math.addExact(current.tripCandidatePoints, points))
        }
    }

    @Synchronized fun beginMockTrip(tripId: String): SessionResult {
        if (!state.actionsAllowed) return result(if (available) SessionCode.LOCKED else SessionCode.STORAGE_ERROR)
        require(tripId.isNotBlank())
        return mutate { if (it.tripId == tripId) it else it.copy(tripId = tripId, tripCandidatePoints = 0) }
    }

    private fun changeTicket(ticketId: String, allowed: Set<TicketStatus>, starting: Boolean = false, change: (RewardWallet) -> WalletSnapshot): SessionResult {
        if (!state.actionsAllowed) return result(if (available) SessionCode.LOCKED else SessionCode.STORAGE_ERROR)
        return mutate { current ->
            val ticket = current.wallet.tickets.find { it.id == ticketId } ?: throw UnknownTicket()
            require(ticket.status in allowed)
            if (starting) require(current.wallet.tickets.none { it.id != ticketId && it.status == TicketStatus.AD_PLAYING })
            current.copy(wallet = change(liveWallet(current)))
        }
    }

    private fun liveWallet(current: DemoAccountRecord): RewardWallet = RewardWallet(accountId, current.wallet).also { wallet ->
        // Constructor recovers stale playback; reapply only this live session's stored playback.
        current.wallet.tickets.filter { it.status == TicketStatus.AD_PLAYING }.forEach { wallet.beginAd(it.id, safety) }
    }

    private fun mutate(change: (DemoAccountRecord) -> DemoAccountRecord): SessionResult {
        val updated = try {
            store.update(accountId) { raw ->
                val current = validate(raw ?: emptyRecord())
                validate(change(current))
            }
        } catch (_: UnknownTicket) { return result(SessionCode.UNKNOWN_TICKET)
        } catch (_: IllegalArgumentException) { return result(SessionCode.INVALID_TRANSITION)
        } catch (_: Exception) { available = false; publish(); return result(SessionCode.STORAGE_ERROR) }
        record = updated
        available = true
        publish()
        return result(SessionCode.OK)
    }

    private fun reload(recoverPlayback: Boolean) {
        try {
            record = store.update(accountId) { raw ->
                val current = validate(raw ?: emptyRecord())
                if (recoverPlayback) current.copy(wallet = RewardWallet(accountId, current.wallet).snapshot()) else current
            }
            available = true
        } catch (_: Exception) { available = false }
    }

    private fun validate(value: DemoAccountRecord): DemoAccountRecord {
        require(value.wallet.accountId == accountId && value.tripCandidatePoints >= 0 && value.tripId.isNotBlank())
        RewardWallet(accountId, value.wallet) // validates without mutating the durable record
        return value.copy(themeId = ThemeIds.normalize(value.themeId))
    }

    private fun emptyRecord() = DemoAccountRecord(WalletSnapshot(accountId, 0, emptyList()))
    private fun result(code: SessionCode) = SessionResult(code, state)
    private fun publish() { val snapshot = state; listeners.toList().forEach { listener -> runCatching { listener(snapshot) } } }
    private class UnknownTicket : RuntimeException()
}

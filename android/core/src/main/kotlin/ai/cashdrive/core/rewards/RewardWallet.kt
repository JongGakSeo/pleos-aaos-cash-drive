package ai.cashdrive.core.rewards

import ai.cashdrive.core.safety.SafetyState

enum class TicketStatus { PENDING, AD_PLAYING, AWAITING_CONFIRMATION, CREDITED }
data class RewardTicket(val id: String, val ownerAccountId: String, val points: Int, val status: TicketStatus)
data class WalletSnapshot(val accountId: String, val confirmedPoints: Int, val tickets: List<RewardTicket>) {
    val pendingPoints: Int get() = tickets.filter { it.status != TicketStatus.CREDITED }.sumOf { it.points }
}

/** Local demo/domain ledger. It is not a server ledger and does not prove ad completion. */
class RewardWallet(private val accountId: String, private val initial: WalletSnapshot? = null) {
    init {
        require(accountId.isNotBlank())
        require(initial == null || initial.accountId == accountId) { "cross-account snapshot" }
        require(initial == null || initial.confirmedPoints >= 0)
        require(initial == null || initial.tickets.all { it.ownerAccountId == accountId && it.points > 0 && it.id.isNotBlank() })
        require(initial == null || initial.tickets.map { it.id }.distinct().size == initial.tickets.size)
    }
    private var confirmed = initial?.confirmedPoints ?: 0
    private val tickets = linkedMapOf<String, RewardTicket>().apply {
        initial?.tickets?.forEach { if (it.ownerAccountId == accountId) put(it.id, if (it.status == TicketStatus.AD_PLAYING) it.copy(status = TicketStatus.PENDING) else it) }
    }

    @Synchronized fun snapshot() = WalletSnapshot(accountId, confirmed, tickets.values.toList())

    @Synchronized fun addPending(ticketId: String, points: Int): WalletSnapshot {
        require(points > 0)
        val existing = tickets[ticketId]
        require(existing == null || existing.points == points) { "ticket id reused with different value" }
        if (existing == null) tickets[ticketId] = RewardTicket(ticketId, accountId, points, TicketStatus.PENDING)
        return snapshot()
    }

    @Synchronized fun beginAd(ticketId: String, safety: SafetyState): WalletSnapshot {
        require(safety == SafetyState.PARKED_ALLOWED)
        return transition(ticketId, TicketStatus.PENDING, TicketStatus.AD_PLAYING)
    }

    @Synchronized fun interruptAd(ticketId: String): WalletSnapshot {
        val ticket = tickets[ticketId]
        if (ticket?.status == TicketStatus.AD_PLAYING) tickets[ticketId] = ticket.copy(status = TicketStatus.PENDING)
        return snapshot()
    }

    @Synchronized fun adCompleted(ticketId: String, safety: SafetyState): WalletSnapshot {
        require(safety == SafetyState.PARKED_ALLOWED) { "ad completion must be observed while parked and unrestricted" }
        return transition(ticketId, TicketStatus.AD_PLAYING, TicketStatus.AWAITING_CONFIRMATION)
    }

    @Synchronized fun confirm(ticketId: String, safety: SafetyState, online: Boolean): WalletSnapshot {
        require(safety == SafetyState.PARKED_ALLOWED && online) { "confirmation requires parked, unrestricted, online state" }
        val ticket = tickets[ticketId] ?: return snapshot()
        if (ticket.status == TicketStatus.AWAITING_CONFIRMATION) {
            confirmed = Math.addExact(confirmed, ticket.points)
            tickets[ticketId] = ticket.copy(status = TicketStatus.CREDITED)
        }
        return snapshot()
    }

    private fun transition(id: String, from: TicketStatus, to: TicketStatus): WalletSnapshot {
        val ticket = requireNotNull(tickets[id]) { "unknown ticket" }
        require(ticket.ownerAccountId == accountId)
        if (ticket.status == from) tickets[id] = ticket.copy(status = to)
        else require(ticket.status == to) { "invalid ticket transition ${ticket.status} -> $to" }
        return snapshot()
    }
}

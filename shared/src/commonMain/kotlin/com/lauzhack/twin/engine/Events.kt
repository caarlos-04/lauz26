package com.lauzhack.twin.engine

import com.lauzhack.twin.model.CaseType
import com.lauzhack.twin.model.Effect
import com.lauzhack.twin.model.Event
import com.lauzhack.twin.model.EventOption
import com.lauzhack.twin.model.ExpenseCategory
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.Loan
import com.lauzhack.twin.model.PendingChange
import kotlin.math.min

// Events change fields of the statement; the simulator moves time (docs/README.md 5.3). Python reference: tools/game.py.

/** Order used to resolve the luck. */
val CASE_ORDER = listOf(CaseType.PESSIMISTIC, CaseType.NEUTRAL, CaseType.OPTIMISTIC)

/** The effects of a resolved case as pending changes, at the start of the turn (the statement's month) plus their
 *  offset. A change that lasts `months` also schedules its reversal. */
fun applyEffects(s: FinancialStatement, effects: List<Effect>, eventId: String): FinancialStatement {
    val t0 = s.month
    val pending = s.pendingChanges.toMutableList()
    fun add(month: Int, change: PendingChange.() -> PendingChange) {
        pending += PendingChange(month = month, sourceEventId = eventId).change()
    }
    for (eff in effects) {
        when (eff) {
            is Effect.SetIncome -> add(t0 + eff.startOffsetMonths) { copy(monthlyIncomeChf = eff.monthlyChf) }
            is Effect.IncomeFactor -> {
                val start = t0 + eff.startOffsetMonths
                add(start) { copy(incomeFactor = eff.factor) }
                eff.months?.let { add(start + it) { copy(incomeFactor = 1 / eff.factor) } }
            }
            is Effect.SetHousehold -> add(t0 + eff.startOffsetMonths) { copy(household = eff.household) }
            is Effect.MarketRent -> add(t0 + eff.startOffsetMonths) { copy(marketRentQuantile = eff.quantile) }
            is Effect.IncomeDelta -> {
                val start = t0 + eff.startOffsetMonths
                add(start) { copy(incomeDeltaChf = eff.deltaChf) }
                eff.months?.let { add(start + it) { copy(incomeDeltaChf = -eff.deltaChf) } }
            }
            is Effect.ExpenseDelta -> {
                val start = t0 + eff.startOffsetMonths
                add(start) { copy(expenseCategory = eff.category, expenseDeltaChf = eff.deltaChf) }
                eff.months?.let { add(start + it) { copy(expenseCategory = eff.category, expenseDeltaChf = -eff.deltaChf) } }
            }
            is Effect.OneOff -> {                   // one month only (negative = a one-time gain)
                val start = t0 + eff.startOffsetMonths
                add(start) { copy(expenseCategory = ExpenseCategory.OTHER, expenseDeltaChf = eff.chf) }
                add(start + 1) { copy(expenseCategory = ExpenseCategory.OTHER, expenseDeltaChf = -eff.chf) }
            }
            is Effect.AddLiability -> add(t0) {
                copy(loan = Loan(principalChf = eff.principalChf, annualRate = eff.annualRate,
                                 monthlyPaymentChf = round2(annuityPayment(eff.principalChf, eff.annualRate, eff.termMonths))))
            }
            is Effect.SetEmployment -> add(t0 + eff.startOffsetMonths) { copy(employment = eff.employment) }
            is Effect.SetFact -> add(t0 + eff.startOffsetMonths) { copy(factKey = eff.key, factValue = eff.value) }
            is Effect.AddFlag -> add(t0 + eff.startOffsetMonths) { copy(flag = eff.flag) }
        }
    }
    return s.copy(pendingChanges = pending)
}

/** Life changes due at the start of the turn already count for picking the event (docs/README.md section 7).
 *  Money changes stay pending: the simulator applies them in the month's step. */
fun applyDueLifeChanges(s: FinancialStatement): FinancialStatement {
    var person = s.person
    val still = mutableListOf<PendingChange>()
    for (ch in s.pendingChanges) {
        when {
            ch.month > s.month -> still += ch
            ch.employment != null -> person = person.copy(employment = ch.employment)
            ch.factKey != null -> person = person.copy(facts = person.facts + (ch.factKey to ch.factValue!!))
            ch.flag != null -> if (ch.flag !in person.flags) person = person.copy(flags = person.flags + ch.flag)
            ch.household != null -> person = person.copy(household = ch.household)
            else -> still += ch
        }
    }
    return s.copy(person = person, pendingChanges = still)
}

/** Do the event's requirements match the statement at the start of this turn? */
fun isEligible(ev: Event, s: FinancialStatement, turn: Int, used: Set<String>): Boolean {
    val req = ev.requires
    val p = s.person
    if (turn < ev.earliestTurn) return false
    if (!ev.repeatable && ev.id in used) return false
    if (req == null) return true
    return (req.employment == null || p.employment in req.employment) &&
        (req.household == null || p.household in req.household) &&
        (req.minAge == null || p.age >= req.minAge) &&
        (req.maxAge == null || p.age <= req.maxAge) &&
        (req.facts ?: emptyMap()).all { (k, values) -> p.facts[k] in values } &&
        (req.flagsAll ?: emptyList()).all { it in p.flags } &&
        (req.flagsNone ?: emptyList()).none { it in p.flags } &&
        (req.minCashChf == null || s.cashChf >= req.minCashChf) &&
        (req.maxCashChf == null || s.cashChf <= req.maxCashChf)
}

/** The eligible event with the highest priority; ties broken with the seeded number of this turn (file order). */
fun pickEvent(events: List<Event>, s: FinancialStatement, turn: Int, used: Set<String>, seed: Long): Event? {
    val candidates = events.filter { isEligible(it, s, turn, used) }
    if (candidates.isEmpty()) return null
    val top = candidates.maxOf { it.priority }
    val best = candidates.filter { it.priority == top }
    val u = PortableRandom.unit(seed, turn, PortableRandom.EVENT_SALT)
    return best[min((u * best.size).toInt(), best.size - 1)]
}

/** PESSIMISTIC / NEUTRAL / OPTIMISTIC: the first case whose cumulative probability exceeds the luck. */
fun caseFromLuck(option: EventOption, luck: Double): CaseType {
    var acc = 0.0
    for (c in CASE_ORDER) {
        acc += option.cases.getValue(c).prob
        if (luck < acc) return c
    }
    return CASE_ORDER.last()
}

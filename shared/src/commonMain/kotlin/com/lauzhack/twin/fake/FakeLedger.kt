package com.lauzhack.twin.fake

import com.lauzhack.twin.model.AccountStatus
import com.lauzhack.twin.model.AccountType
import com.lauzhack.twin.model.Assumptions
import com.lauzhack.twin.model.BalanceSheet
import com.lauzhack.twin.model.CashAccount
import com.lauzhack.twin.model.CashFlow
import com.lauzhack.twin.model.DebtLine
import com.lauzhack.twin.model.Effect
import com.lauzhack.twin.model.ExpenseCategory
import com.lauzhack.twin.model.ExpenseStream
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.FlowLine
import com.lauzhack.twin.model.GoalState
import com.lauzhack.twin.model.GoalStatus
import com.lauzhack.twin.model.Indexation
import com.lauzhack.twin.model.IncomeStream
import com.lauzhack.twin.model.LifeChange
import com.lauzhack.twin.model.Liability
import com.lauzhack.twin.model.LiquidityStatus
import com.lauzhack.twin.model.Origin
import com.lauzhack.twin.model.PersonSnapshot
import com.lauzhack.twin.model.ProgressBasis
import com.lauzhack.twin.model.StatementMetrics
import com.lauzhack.twin.model.UserProfile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Simplified money engine for the fake (docs section 7, loosely): applies effects and advances
 * [FinancialStatement]s month by month. Placeholder for Dev A's engine; values are illustrative.
 */
internal class FakeLedger(private val assumptions: Assumptions) {

    // ---------- Building statements ----------

    fun opening(
        profile: UserProfile,
        cashChf: Double,
        investments: List<com.lauzhack.twin.model.Investment>,
        income: List<IncomeStream>,
        expenses: List<ExpenseStream>,
        facts: Map<com.lauzhack.twin.model.FactKey, String>,
        flags: List<String> = emptyList(),
    ): FinancialStatement = recompute(
        FinancialStatement(
            version = 1,
            profileId = profile.id,
            month = 0,
            period = "${profile.startMonth}-01",
            origin = Origin.DERIVED,
            currency = "CHF",
            person = PersonSnapshot(
                displayName = profile.displayName,
                ageAtStart = profile.ageAtStart,
                age = profile.ageAtStart,
                city = profile.city,
                canton = null,
                country = "CH",
                employment = profile.employment,
                household = profile.household,
                facts = facts,
                flags = flags,
                synthetic = true,
            ),
            accounts = listOf(CashAccount("cash", AccountType.CASH, "CHF", cashChf, AccountStatus.OK)),
            investments = investments,
            liabilities = emptyList(),
            incomeStreams = income,
            expenseStreams = expenses,
            cashFlow = null,
            balanceSheet = BalanceSheet(0.0, 0.0, 0.0, 0.0, 0.0),
            goals = profile.goals.map {
                GoalStatus(it.id, it.name, it.targetChf, it.targetDate, it.targetMonth, it.priority,
                    ProgressBasis.NET_WORTH, 0.0, 0.0, it.targetMonth, GoalState.IN_PROGRESS)
            },
            scenarioParameters = emptyList(),
            metrics = StatementMetrics("RUN_RATE", 0.0, 0.0, 0.0, null, null, null, null, LiquidityStatus.OK, 0.0),
            pendingLifeChanges = emptyList(),
        ),
        startMonth = profile.startMonth,
    )

    /** Recomputes the derived parts (balance sheet, metrics, goal statuses, account status). */
    fun recompute(s: FinancialStatement, startMonth: String? = null): FinancialStatement {
        val cash = s.cash
        val investments = s.investments.sumOf { it.valueChf }
        val debt = s.liabilities.sumOf { it.principalChf }
        val netWorth = cash + investments - debt
        val t = s.month
        val income = s.incomeStreams.filter { it.activeAt(t) }.sumOf { it.monthlyChf }
        val expenses = s.expenseStreams.filter { it.activeAt(t) }.sumOf { it.monthlyChf }
        val debtService = s.liabilities.filter { it.principalChf > 0 }.sumOf { it.monthlyPaymentChf }
        val outflows = expenses + debtService
        val net = income - outflows
        val essential = s.expenseStreams.filter { it.activeAt(t) && it.category != ExpenseCategory.TRAVEL }.sumOf { it.monthlyChf }
        val rent = s.expenseStreams.filter { it.activeAt(t) && it.category == ExpenseCategory.RENT }.sumOf { it.monthlyChf }
        return s.copy(
            period = startMonth?.let { "$it-01" } ?: s.period,
            accounts = s.accounts.map {
                it.copy(status = if (it.balanceChf < 0) AccountStatus.NEGATIVE_REQUIRES_FINANCING else AccountStatus.OK)
            },
            balanceSheet = BalanceSheet(cash, investments, cash + investments, debt, netWorth),
            metrics = StatementMetrics(
                basis = "RUN_RATE",
                monthlyIncomeChf = income,
                monthlyOutflowsChf = outflows,
                monthlyNetCashFlowChf = net,
                savingsRate = if (income > 0) net / income else null,
                debtServiceRatio = if (income > 0) debtService / income else null,
                rentToIncomeRatio = if (income > 0) rent / income else null,
                emergencyFundMonths = if (essential > 0) cash / essential else null,
                liquidityStatus = when {
                    cash < 0 -> LiquidityStatus.NEGATIVE
                    essential > 0 && cash < 3 * essential -> LiquidityStatus.LOW
                    else -> LiquidityStatus.OK
                },
                netWorthChf = netWorth,
            ),
            goals = s.goals.map { g ->
                val current = netWorth
                val reached = g.status == GoalState.REACHED || (t <= g.targetMonth && current >= g.targetChf)
                g.copy(
                    currentChf = current,
                    progressRatio = (current / g.targetChf).coerceIn(0.0, 1.0),
                    monthsLeft = max(0, g.targetMonth - t),
                    status = when {
                        reached -> GoalState.REACHED
                        t >= g.targetMonth -> GoalState.MISSED
                        else -> GoalState.IN_PROGRESS
                    },
                )
            },
        )
    }

    // ---------- Effects (start of a turn) ----------

    /** Applies the effects of a case at month [s].month. Delayed effects become streams or pending life changes. */
    fun applyEffects(s: FinancialStatement, effects: List<Effect>, eventId: String): FinancialStatement =
        recompute(effects.fold(s) { acc, effect -> applyEffect(acc, effect, eventId) })

    private fun applyEffect(s: FinancialStatement, effect: Effect, eventId: String): FinancialStatement {
        val m = s.month
        return when (effect) {
            is Effect.SetIncome -> {
                val from = m + effect.startOffsetMonths
                s.copy(incomeStreams = s.incomeStreams.map {
                    if (it.label == effect.streamId && (it.untilMonth == null || it.untilMonth > from)) it.copy(untilMonth = maxOf(it.fromMonth, from)) else it
                } + IncomeStream("${effect.streamId}@$from", effect.streamId, effect.monthlyChf, from, null,
                    Indexation.WAGES, Origin.SIMULATED, eventId))
            }
            is Effect.IncomeDelta -> {
                val from = m + effect.startOffsetMonths
                s.copy(incomeStreams = s.incomeStreams + IncomeStream("${effect.streamId}+$eventId@$from", effect.streamId,
                    effect.deltaChf, from, effect.months?.let { from + it }, Indexation.WAGES, Origin.SIMULATED, eventId))
            }
            is Effect.ExpenseDelta -> {
                val from = m + effect.startOffsetMonths
                s.copy(expenseStreams = s.expenseStreams + ExpenseStream("${effect.category.name.lowercase()}+$eventId@$from",
                    effect.category, effect.deltaChf, from, effect.months?.let { from + it }, Indexation.CPI, Origin.SIMULATED, eventId))
            }
            is Effect.OneOff ->
                if (effect.startOffsetMonths == 0) s.withCash(s.cash - effect.chf)
                else {
                    val from = m + effect.startOffsetMonths
                    s.copy(expenseStreams = s.expenseStreams + ExpenseStream("oneoff+$eventId@$from", ExpenseCategory.OTHER,
                        effect.chf, from, from + 1, Indexation.NONE, Origin.SIMULATED, eventId))
                }
            is Effect.AddLiability -> {
                val r = effect.annualRate / 12
                val payment = if (r == 0.0) effect.principalChf / effect.termMonths
                else effect.principalChf * r / (1 - (1 + r).pow(-effect.termMonths))
                val loan = Liability("${effect.liabilityType.name.lowercase()}+$eventId@$m", effect.liabilityType,
                    effect.principalChf, effect.annualRate, payment, paymentIsAssumed = true, sourceEventId = eventId)
                s.copy(liabilities = s.liabilities + loan)
                    .let { if (effect.disburseToCash) it.withCash(it.cash + effect.principalChf) else it }
            }
            is Effect.AssetReturnDelta -> s.copy(investments = s.investments.map {
                if (it.type == effect.assetType) it.copy(annualReturn = (it.annualReturn ?: assumptions.investmentReturnAnnual) + effect.deltaAnnualRate) else it
            })
            is Effect.SetEmployment -> s.lifeChange(LifeChange(m + effect.startOffsetMonths, eventId, employment = effect.employment))
            is Effect.SetFact -> s.lifeChange(LifeChange(m + effect.startOffsetMonths, eventId, factKey = effect.key, factValue = effect.value))
            is Effect.AddFlag -> s.lifeChange(LifeChange(m + effect.startOffsetMonths, eventId, flag = effect.flag))
        }
    }

    private fun FinancialStatement.lifeChange(change: LifeChange): FinancialStatement =
        if (change.month <= month) applyLifeChange(this, change) else copy(pendingLifeChanges = pendingLifeChanges + change)

    private fun applyLifeChange(s: FinancialStatement, c: LifeChange): FinancialStatement = s.copy(
        person = s.person.copy(
            employment = c.employment ?: s.person.employment,
            facts = if (c.factKey != null && c.factValue != null) s.person.facts + (c.factKey to c.factValue) else s.person.facts,
            flags = if (c.flag != null && c.flag !in s.person.flags) s.person.flags + c.flag else s.person.flags,
        ),
    )

    // ---------- Monthly step ----------

    /** Advances one month: flows, debt, financing cost on negative cash, investment growth, due life changes. */
    fun step(s: FinancialStatement, noiseChf: Double = 0.0, returnShock: Double = 0.0): FinancialStatement {
        val t = s.month
        val years = t / 12.0
        val incomeLines = s.incomeStreams.filter { it.activeAt(t) }.map {
            val growth = if (it.indexation == Indexation.WAGES) (1 + assumptions.salaryGrowthAnnual).pow(years) else 1.0
            FlowLine("income:${it.label}", it.monthlyChf * growth, it.origin, it.sourceEventId)
        }
        val expenseLines = s.expenseStreams.filter { it.activeAt(t) }.map {
            val growth = if (it.indexation == Indexation.CPI) (1 + assumptions.inflationAnnual).pow(years) else 1.0
            FlowLine("expense:${it.category.name}", it.monthlyChf * growth, it.origin, it.sourceEventId)
        }
        val debtLines = mutableListOf<DebtLine>()
        val liabilities = s.liabilities.map { l ->
            if (l.principalChf <= 0.0) return@map l
            val interest = l.principalChf * l.annualRate / 12
            val payment = min(l.monthlyPaymentChf, l.principalChf + interest)
            debtLines += DebtLine(l.id, payment, interest, payment - interest, l.sourceEventId)
            l.copy(principalChf = max(0.0, l.principalChf + interest - payment))
        }
        val financing = if (s.cash < 0) -s.cash * assumptions.financingRateAnnual / 12 else 0.0
        val totalIncome = incomeLines.sumOf { it.amountChf }
        val totalExpenses = expenseLines.sumOf { it.amountChf }
        val totalDebt = debtLines.sumOf { it.paymentChf }
        val net = totalIncome - totalExpenses - totalDebt - financing + noiseChf
        val closing = s.cash + net
        val next = t + 1
        var out = s.copy(
            month = next,
            origin = Origin.SIMULATED,
            accounts = s.accounts.map { it.copy(balanceChf = closing) },
            investments = s.investments.map {
                val r = (it.annualReturn ?: assumptions.investmentReturnAnnual) + returnShock
                it.copy(valueChf = it.valueChf * (1 + r / 12))
            },
            liabilities = liabilities,
            person = s.person.copy(age = s.person.ageAtStart + next / 12),
            cashFlow = CashFlow(s.cash, incomeLines, expenseLines, debtLines, noiseChf, financing,
                totalIncome, totalExpenses, totalDebt, net, closing),
            pendingLifeChanges = s.pendingLifeChanges.filter { it.month > next },
        )
        s.pendingLifeChanges.filter { it.month <= next }.forEach { out = applyLifeChange(out, it) }
        return recompute(out)
    }

    /** [start] plus the next [months] statements. */
    fun advance(start: FinancialStatement, months: Int, noise: ((FinancialStatement) -> Double)? = null, returnShock: Double = 0.0): List<FinancialStatement> {
        val out = ArrayList<FinancialStatement>(months + 1)
        out += start
        var s = start
        repeat(months) {
            s = step(s, noise?.invoke(s) ?: 0.0, returnShock)
            out += s
        }
        return out
    }
}

internal fun IncomeStream.activeAt(t: Int) = fromMonth <= t && (untilMonth == null || t < untilMonth)
internal fun ExpenseStream.activeAt(t: Int) = fromMonth <= t && (untilMonth == null || t < untilMonth)

internal val FinancialStatement.cash: Double get() = accounts.sumOf { it.balanceChf }

internal fun FinancialStatement.withCash(value: Double): FinancialStatement =
    copy(accounts = accounts.mapIndexed { i, a -> if (i == 0) a.copy(balanceChf = value) else a.copy(balanceChf = 0.0) })

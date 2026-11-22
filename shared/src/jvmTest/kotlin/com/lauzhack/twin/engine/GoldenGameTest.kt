package com.lauzhack.twin.engine

import com.lauzhack.twin.model.AttributionItem
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.StoryBeat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The Kotlin engine must reproduce the games played by the Python reference (tools/golden.py), month by month.
// tools/out/ is git-ignored: generate it with
//   python tools/flow_model.py && python tools/macro.py && python tools/golden.py
// The test is skipped when the files are missing.
class GoldenGameTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "content").isDirectory }
    private val out = File(root, "tools/out")

    @Serializable
    private data class Golden(
        val name: String, val seed: Long, val choices: List<String>, val opening: FinancialStatement,
        val monthly: List<FinancialStatement>, val statements: List<FinancialStatement>, val beats: List<StoryBeat>,
        val attribution: List<AttributionItem>, val startNetWorth: Double, val endNetWorth: Double,
    )

    private val engine: TwinEngine? by lazy {
        val model = File(out, "flow-model.json").takeIf { it.exists() } ?: return@lazy null
        val macro = File(out, "macro.json").takeIf { it.exists() } ?: return@lazy null
        TwinEngine.fromJson(model.readText(), macro.readText(), File(root, "content/events.json").readText())
    }

    private fun goldens(): List<Golden> =
        File(out, "golden").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
            .map { json.decodeFromString<Golden>(it.readText()) }

    @Test
    fun kotlinReproducesThePythonGames() {
        val eng = engine ?: return
        val games = goldens()
        if (games.isEmpty()) return
        var worstMoney = 0.0
        for (g in games) {
            val rec = eng.play(g.opening, g.choices, g.seed)
            assertEquals(g.monthly.size, rec.monthly.size, g.name)
            for ((exp, act) in g.monthly.zip(rec.monthly)) {
                val where = "${g.name} month ${exp.month}"
                assertEquals(exp.period, act.period, where)
                assertEquals(exp.person, act.person, where)
                assertEquals(exp.pendingChanges, act.pendingChanges, where)
                assertEquals(exp.eventExpensesChf, act.eventExpensesChf, where)
                for ((label, e, a) in listOf(
                    Triple("cash", exp.cashChf, act.cashChf),
                    Triple("income", exp.monthlyIncomeChf, act.monthlyIncomeChf),
                    Triple("rent", exp.monthlyRentChf, act.monthlyRentChf),
                    Triple("investments", exp.investmentsChf, act.investmentsChf),
                    Triple("loan", exp.loan?.principalChf ?: 0.0, act.loan?.principalChf ?: 0.0),
                    Triple("net", exp.cashFlow?.netChf ?: 0.0, act.cashFlow?.netChf ?: 0.0),
                )) {
                    worstMoney = maxOf(worstMoney, abs(e - a))
                    assertTrue(abs(e - a) <= MONEY_TOLERANCE, "$where $label: python $e, kotlin $a")
                }
                assertEquals(exp.cashFlow?.incomeChf?.keys, act.cashFlow?.incomeChf?.keys, "$where income lines")
                assertEquals(exp.cashFlow?.expensesChf?.keys, act.cashFlow?.expensesChf?.keys, "$where expense lines")
            }
            assertEquals(g.beats.map { listOf(it.month, it.kind, it.eventId) }, rec.beats.map { listOf(it.month, it.kind, it.eventId) }, g.name)
            assertEquals(g.beats.map { it.data }, rec.beats.map { it.data }, "${g.name} beat data")
            val attribution = rec.attribution.items.associate { it.key to it.chf }
            assertEquals(g.attribution.map { it.key }.toSet(), attribution.keys, g.name)
            for (item in g.attribution) {
                assertTrue(abs(item.chf - attribution.getValue(item.key)) <= MONEY_TOLERANCE * 10, "${g.name} ${item.key}")
            }
            val gap = rec.attribution.startNetWorth + rec.attribution.items.sumOf { it.chf } - rec.attribution.endNetWorth
            assertTrue(abs(gap) <= 0.5, "${g.name} attribution gap $gap")
        }
        println("golden games: ${games.size}, largest money difference vs Python: $worstMoney CHF")
    }

    private companion object {
        const val MONEY_TOLERANCE = 0.01            // CHF: the engines agree to the cent (today: exactly)
    }
}

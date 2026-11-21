package com.lauzhack.twin.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Checks that the simulator files written by tools/ (docs/simulator.md) decode into the shared model classes.
// tools/out/ is git-ignored: generate it with
//   python tools/flow_model.py && python tools/macro.py
//   python tools/simulate.py --profile P00001 --dump tools/out/simulated-P00001.json
// Each test is skipped when its file is missing.
class SimulationContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val out: File = File(
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "content").isDirectory },
        "tools/out",
    )

    @Serializable
    private data class StatementDump(val profileId: String, val statements: List<FinancialStatement>)

    @Test
    fun flowModelDecodes() {
        val file = File(out, "flow-model.json").takeIf { it.exists() } ?: return
        val model = json.decodeFromString<FlowModel>(file.readText())
        val keys = model.flows.map { it.key }.toSet()
        assertTrue(keys.containsAll(setOf("salary", "rent", "bonus", "groceries", "tuition")), keys.toString())
        val featureKeys = FlowFeature.entries.map { it.key }.toSet()
        assertEquals(featureKeys, model.meta.features.toSet())
        for (f in model.flows) {
            assertTrue(f.months.isNotEmpty() && f.months.all { it in 1..12 }, f.key)
            assertTrue(f.probability.base in 0.0..1.0, f.key)
            assertEquals(11, f.amount.residualDeciles.size, f.key)
            assertTrue(featureKeys.containsAll(f.probability.factors.keys + f.amount.factors.keys), f.key)
            assertEquals(f.amount.factors.keys, f.amount.unknown.keys, f.key)
            assertEquals(featureKeys, f.amount.selection.map { it.feature }.toSet() + f.amount.factors.keys, f.key)
            assertEquals(f.direction == FlowDirection.IN, f.expenseCategory == null, f.key)
        }
        assertEquals("VD", model.cityCanton["Lausanne"])
        assertEquals(model.meta.numericFeatures.values.toSet(), model.meta.bandEdges.keys)
        for ((field, edges) in model.meta.bandEdges) assertEquals(edges.sorted(), edges, field)
    }

    @Test
    fun macroTrendsDecode() {
        val file = File(out, "macro.json").takeIf { it.exists() } ?: return
        val trends = json.decodeFromString<MacroFile>(file.readText()).trends
        val career = trends.careerCurveByAge.value
        if (career != null) {
            assertTrue(career.defaultCurve in career.curves)
            assertTrue(career.occupationCurve.values.all { it in career.curves }, career.occupationCurve.toString())
            for ((name, curve) in career.curves) {
                assertTrue(curve.points.all { it.size == 2 }, name)
                assertEquals(curve.points.map { it[0] }.sorted(), curve.points.map { it[0] }, name)
            }
        }
        for (rate in listOf(trends.priceInflationAnnual, trends.wageGrowthAnnual, trends.rentGrowthSittingTenantAnnual)) {
            assertTrue(abs(rate.value) < 0.1, "annual fraction expected, got ${rate.value}")
        }
    }

    @Test
    fun simulatedStatementsDecodeAndBalance() {
        val file = File(out, "simulated-P00001.json").takeIf { it.exists() } ?: return
        val dump = json.decodeFromString<StatementDump>(file.readText())
        assertTrue(dump.statements.size > 1)
        for ((prev, s) in dump.statements.zipWithNext()) {
            val cf = requireNotNull(s.cashFlow)
            val lines = cf.incomeChf.values.sum() - cf.expensesChf.values.sum() - cf.loanPaymentChf - cf.financingCostChf
            assertEquals(cf.netChf, lines, 0.02, "month ${s.month}")
            assertEquals(prev.cashChf + cf.netChf, s.cashChf, 0.02, "month ${s.month}")
            assertEquals(prev.month + 1, s.month)
        }
    }
}

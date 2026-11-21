"""Tests for the learned flow model and the reference simulator. Run from tools/: python -m unittest -v
They use a small hand-written model, so they do not need the dataset or tools/out/."""
import random
import unittest

from flow_model import FEATURES, FIXED_EDGES, band_label, fit_multiplicative, forward_select, rereference
from simulate import run, step
from statement import check_statement
from test_support import MACRO, MODEL, expense, income, person



class FittingTests(unittest.TestCase):
    def test_backfitting_recovers_multiplicative_factors(self):
        rows = [({"a": a, "b": b}, 10.0 * (2.0 if a == "y" else 1.0) * (0.5 if b == "q" else 1.0), 1)
                for a in "xy" for b in "pq" for _ in range(5)]
        fit = rereference(fit_multiplicative(rows, ["a", "b"]), rows)
        pred = fit["base"] * fit["factors"]["a"]["y"] * fit["factors"]["b"]["q"]
        self.assertAlmostEqual(pred, 10.0, places=6)
        self.assertAlmostEqual(fit["factors"]["a"]["y"] / fit["factors"]["a"]["x"], 2.0, places=6)

    def test_forward_selection_keeps_signal_and_drops_noise(self):
        rng = random.Random(1)
        rows = []
        for i in range(600):
            x = {k: "L0" for k in FEATURES}
            x["canton"] = rng.choice(["BE", "GE"])
            x["household"] = rng.choice(["SINGLE", "FAMILY"])          # irrelevant
            y = 100 * (1.25 if x["canton"] == "GE" else 1.0) * rng.uniform(0.95, 1.05)
            rows.append((x, y, 1))
        fit, report = forward_select(rows)
        kept = {r["feature"] for r in report if r["kept"]}
        self.assertEqual(kept, {"canton"})

    def test_equivalent_fields_pick_the_simplest(self):
        """Students are exactly the NOT_WORKING people in the data: studying (2 levels) must win over employment (4)."""
        rng = random.Random(2)
        rows = []
        for i in range(600):
            x = {k: "L0" for k in FEATURES}
            x["employment"] = rng.choice(["EMPLOYED", "RETIRED", "PART_TIME", "NOT_WORKING"])
            x["studying"] = "YES" if x["employment"] == "NOT_WORKING" else "NO"
            y = 100 * (0.8 if x["studying"] == "YES" else 1.0) * rng.uniform(0.95, 1.05)
            rows.append((x, y, 1))
        _, report = forward_select(rows)
        self.assertEqual({r["feature"] for r in report if r["kept"]}, {"studying"})

    def test_ineligible_levels_get_an_exact_zero(self):
        rows = [({"employment": e}, (0.5 if e == "EMPLOYED" else 0.0), 10) for e in ("EMPLOYED", "RETIRED") * 20]
        fit = rereference(fit_multiplicative(rows, ["employment"]), rows)
        self.assertEqual(fit["factors"]["employment"]["RETIRED"], 0.0)
        self.assertAlmostEqual(fit["base"], 0.5)

    def test_bands(self):
        self.assertEqual(band_label(20, FIXED_EDGES["age"]), "18-25")
        self.assertEqual(band_label(70, FIXED_EDGES["age"]), "65+")
        self.assertEqual(band_label(1200, FIXED_EDGES["income"]), "<2500")
        self.assertEqual(band_label(12000, FIXED_EDGES["income"]), "10000+")
        self.assertEqual(band_label(-500, [0, 5000]), "<0")              # negative cash has its own band
        self.assertEqual(band_label(5, None), "UNKNOWN")


class SimulatorTests(unittest.TestCase):
    def test_identities_hold_for_ten_years(self):
        s0 = person(cash=500.0, income=1000.0, education="BACHELOR_IN_PROGRESS", employment="NOT_WORKING",
                    loan={"principalChf": 5000.0, "annualRate": 0.05, "monthlyPaymentChf": 200.0})
        sts = run(s0, 120, MODEL, macro=MACRO)
        self.assertEqual([e for prev, s in zip(sts, sts[1:]) for e in check_statement(s, prev)], [])
        self.assertLess(sts[-1]["cashChf"], 0)                                  # burns cash -> financing
        self.assertGreater(sts[-1]["cashFlow"]["financingCostChf"], 0)
        self.assertEqual(sts[-1]["loan"]["principalChf"], 0.0)                  # loan paid off

    def test_expected_month_is_the_sum_of_the_flows(self):
        s1 = step(person(), MODEL)                                              # January, Bern, employed
        self.assertAlmostEqual(s1["cashFlow"]["netChf"], 5000 - 1500 - 300, places=2)
        self.assertEqual(s1["period"], "2025-01")

    def test_tuition_only_when_studying_and_only_in_february_and_september(self):
        sts = run(person(education="BACHELOR_IN_PROGRESS", employment="NOT_WORKING"), 12, MODEL)
        self.assertEqual([s["month"] for s in sts[1:] if expense(s, "tuition")], [2, 9])
        self.assertFalse(any(expense(s, "tuition") for s in run(person(), 12, MODEL)[1:]))

    def test_december_bonus_depends_on_employment(self):
        dec = run(person(), 12, MODEL)[12]
        self.assertAlmostEqual(income(dec, "bonus"), 0.5 * 0.6 * 5000, places=2)
        self.assertIsNone(income(run(person(employment="PART_TIME"), 12, MODEL)[12], "bonus"))

    def test_event_changes_fields_and_the_model_reacts(self):
        s0 = person()
        s0["pendingChanges"] = [{"month": 3, "sourceEventId": "E-move", "factKey": "CITY", "factValue": "Geneva"},
                                {"month": 3, "sourceEventId": "E-move", "employment": "PART_TIME"}]
        sts = run(s0, 12, MODEL)
        self.assertAlmostEqual(expense(sts[3], "groceries"), 300.0)   # month 3 = March; the change applies in April
        self.assertAlmostEqual(expense(sts[4], "groceries"), 375.0)   # Geneva: +25 %
        self.assertEqual(sts[4]["person"]["facts"]["CITY"], "Geneva")
        self.assertIsNone(income(sts[12], "bonus"))                   # part-time: no December bonus

    def test_trends_compound_every_month(self):
        sts = run(person(), 13, MODEL, macro=MACRO)
        self.assertEqual(sts[1]["monthlyIncomeChf"], 5000.0)                     # the first month at the opening level
        self.assertAlmostEqual(sts[7]["monthlyIncomeChf"], 5000 * 1.01 ** (6 / 12), delta=0.05)
        self.assertAlmostEqual(sts[13]["monthlyIncomeChf"], 5050.0, delta=0.05)  # a year later: the yearly rate
        self.assertAlmostEqual(sts[13]["monthlyRentChf"], 1522.5, delta=0.05)
        self.assertAlmostEqual(expense(sts[13], "groceries"), 306.0, places=2)

    def test_career_follows_age_and_occupation_only_while_working(self):
        curves = {"defaultCurve": "allPositions",
                  "occupationCurve": {"PROFESSIONAL": "allPositions", "SENIOR": "management"},
                  "curves": {"allPositions": {"points": [[25, 5000.0], [35, 6000.0], [45, 5500.0]], "flatAfterAge": 35},
                             "management": {"points": [[25, 5000.0], [35, 8000.0]], "flatAfterAge": 35}}}
        macro = {"trends": {**MACRO["trends"], "careerCurveByAge": {"value": curves}}}
        pay = lambda s0: run(s0, 13, MODEL, macro=macro)[13]["monthlyIncomeChf"]
        self.assertAlmostEqual(pay(person(age=25)), 5000 * 1.01 * 5100 / 5000, delta=0.1)   # PROFESSIONAL
        senior = person(age=25)
        senior["person"]["facts"]["OCCUPATION"] = "SENIOR"                       # management curve: steeper
        self.assertAlmostEqual(pay(senior), 5000 * 1.01 * 5300 / 5000, delta=0.1)
        student = person(age=25, employment="NOT_WORKING", education="BACHELOR_IN_PROGRESS")
        self.assertAlmostEqual(pay(student), 5050.0, delta=0.1)                  # no job: no career step
        self.assertAlmostEqual(pay(person(age=50)), 5050.0, delta=0.1)           # flat after the peak

    def test_age_grows_every_twelve_months(self):
        sts = run(person(age=20), 24, MODEL)
        self.assertEqual([sts[m]["person"]["age"] for m in (0, 11, 12, 23, 24)], [20, 20, 21, 21, 22])

    def test_sampled_months_average_to_the_expected_month(self):
        s0 = person()
        expected = step(s0, MODEL)["cashFlow"]["netChf"]
        rng = random.Random(7)
        draws = [step(s0, MODEL, mode="sample", rng=rng)["cashFlow"]["netChf"] for _ in range(2000)]
        self.assertAlmostEqual(sum(draws) / len(draws), expected, delta=25)

    def test_unknown_city_uses_the_reference_level(self):
        self.assertAlmostEqual(expense(step(person(city="ABROAD"), MODEL), "groceries"), 300.0)


if __name__ == "__main__":
    unittest.main()

"""Sanity tests for the offline tools. Run from tools/: python -m unittest -v"""
import json
import unittest

from backtest import analytic_band
from calibrate import DEFAULT_FACTS, annuity_payment, build_calibration, build_profile, initial_life, sample_profiles
from build_statements import statements_for
from common import (calibration_employment, deciles, default_data_dir, load_dataset, month_index, quantile,
                    segment_key, work_status)
from statement import check_statement, goal_target_month, period_of

DATA_DIR = default_data_dir()
FACTS = json.loads(DEFAULT_FACTS.read_text(encoding="utf-8"))


class HelperTests(unittest.TestCase):
    def test_quantile_interpolates(self):
        self.assertEqual(quantile([1, 2, 3, 4, 5], 0.5), 3)
        self.assertAlmostEqual(quantile([0, 10], 0.25), 2.5)

    def test_deciles_are_monotone_and_have_11_values(self):
        d = deciles([5, 1, 9, 3, 7, 2, 8])
        self.assertEqual(len(d), 11)
        self.assertEqual(d, sorted(d))

    def test_month_index(self):
        self.assertEqual(month_index("2025-01", "2025-01-01"), 0)
        self.assertEqual(month_index("2025-01", "2029-12-31"), 59)

    def test_annuity_pays_off_the_loan(self):
        pay = annuity_payment(10000, 0.06, 60)
        balance = 10000.0
        for _ in range(60):
            balance = balance * (1 + 0.06 / 12) - pay
        self.assertAlmostEqual(balance, 0.0, places=4)

    def test_statement_months_and_periods(self):
        self.assertEqual(period_of("2025-01", 0), "2025-01-01")
        self.assertEqual(period_of("2025-01", 1), "2025-01")
        self.assertEqual(period_of("2025-01", 12), "2025-12")
        self.assertEqual(period_of("2025-01", 13), "2026-01")
        self.assertEqual(goal_target_month("2025-01", "2029-12-31"), 60)   # end of Dec 2029

    def test_studying_and_working_are_independent(self):
        self.assertEqual(work_status("STUDENT"), "NOT_WORKING")
        self.assertEqual(work_status("PART_TIME"), "PART_TIME")
        employed = initial_life({"employment": "EMPLOYED", "household": "SINGLE", "city": "Bern"}, FACTS)
        self.assertEqual(employed["facts"]["EDUCATION"], "UNKNOWN")
        self.assertEqual(employed["facts"]["OCCUPATION"], "PROFESSIONAL")

    def test_calibration_employment(self):
        self.assertEqual(calibration_employment("NOT_WORKING", "BACHELOR_IN_PROGRESS"), "STUDENT")
        self.assertEqual(calibration_employment("PART_TIME", "MASTER_IN_PROGRESS"), "PART_TIME")   # works -> its own segment
        self.assertEqual(calibration_employment("NOT_WORKING", "MASTER"), "UNEMPLOYED")           # assumption
        self.assertEqual(calibration_employment("RETIRED", "UNKNOWN"), "RETIRED")

    def test_segment_key(self):
        self.assertEqual(segment_key("student", "single"), "STUDENT|SINGLE")

    def test_initial_life_from_profile_rules(self):
        life = initial_life({"employment": "STUDENT", "household": "SHARED_HOUSING", "city": "Lausanne"}, FACTS)
        self.assertEqual(life["employment"], "NOT_WORKING")      # a student without a job; studying is in EDUCATION
        self.assertEqual(life["facts"], {"EDUCATION": "BACHELOR_IN_PROGRESS", "OCCUPATION": "NONE",
                                         "HOUSING": "SHARED_FLAT", "FAMILY": "SINGLE", "CITY": "Lausanne"})
        self.assertEqual(life["flags"], [])

    def test_analytic_band_widens_with_sqrt_of_months(self):
        seg = {"livingCostChf": {"mean": 1500, "sd": 0}, "monthlyNoiseSdChf": 100, "decemberBonusRatio": 0}
        prof = {"openingCash": 0, "income": 3000, "rent": 1000, "employment": "STUDENT"}
        band = analytic_band(prof, ["2025-01", "2025-02", "2025-03", "2025-04"], seg)
        self.assertAlmostEqual(band[0][1], 500)
        self.assertAlmostEqual(band[3][2] - band[3][1], 2 * (band[0][2] - band[0][1]))  # sqrt(4) = 2


@unittest.skipUnless((DATA_DIR / "synthetic-profiles.csv").exists(), "dataset not found (set LAUZ_DATA_DIR)")
class DatasetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ds = load_dataset(DATA_DIR)
        cls.calib = build_calibration(cls.ds, sorted(cls.ds["profiles"]))

    def test_every_profile_has_24_months(self):
        self.assertTrue(all(len(r) == 24 for r in self.ds["series"].values()))

    def test_calibration_has_full_and_fallback_segments(self):
        keys = {s["segment"] for s in self.calib}
        self.assertIn("STUDENT|SINGLE", keys)
        self.assertIn("STUDENT|ANY", keys)

    def test_calibration_values_are_consistent(self):
        for s in self.calib:
            lc = s["livingCostChf"]
            self.assertLessEqual(lc["p10"], lc["p50"])
            self.assertLessEqual(lc["p50"], lc["p90"])
            self.assertGreater(s["monthlyNoiseSdChf"], 0)

    def test_sample_contains_alexia_and_covers_all_segments(self):
        sample = sample_profiles(self.ds, 50, seed=1)
        self.assertIn("P00001", sample)
        segs = {segment_key(self.ds["profiles"][p]["employment"], self.ds["profiles"][p]["household"]) for p in sample}
        self.assertEqual(len(segs), 24)

    def test_every_profile_gets_a_valid_initial_life(self):
        for pid in self.ds["profiles"]:
            build_profile(self.ds, pid, FACTS)   # raises if a fact is not in the catalog

    def test_alexia_opening_statement(self):
        st = build_profile(self.ds, "P00001", FACTS)["initialStatement"]
        self.assertEqual(st["month"], 0)
        self.assertIsNone(st["cashFlow"])
        self.assertEqual(st["balanceSheet"]["cashChf"], 8000.0)
        self.assertEqual(st["investments"], [])                       # Alexia is the only one without investments
        self.assertEqual({e["id"] for e in st["expenseStreams"]}, {"rent", "living"})
        self.assertEqual(st["person"]["facts"]["CITY"], "Lausanne")
        self.assertEqual(st["person"]["employment"], "NOT_WORKING")
        self.assertEqual(st["person"]["facts"]["EDUCATION"], "BACHELOR_IN_PROGRESS")
        self.assertEqual(st["person"]["canton"], "VD")
        self.assertEqual({p["parameter"] for p in st["scenarioParameters"]}, {"annual_tuition_chf", "study_duration_years"})

    def test_cash_is_not_double_counted(self):
        st = build_profile(self.ds, "P00002", FACTS)["initialStatement"]
        self.assertTrue(all(i["type"] != "CASH" for i in st["investments"]))
        self.assertAlmostEqual(st["balanceSheet"]["cashChf"], float(self.ds["raw_profiles"]["P00002"]["opening_cash_chf"]))

    def test_every_statement_of_every_profile_is_consistent(self):
        for pid in self.ds["profiles"]:
            sts = statements_for(self.ds, pid, FACTS)
            self.assertEqual(len(sts), 25)
            self.assertEqual(sts[-1]["balanceSheet"]["cashChf"], self.ds["series"][pid][-1]["closing"])
            for s in sts:
                self.assertEqual(check_statement(s), [], f"{pid} month {s['month']}")


if __name__ == "__main__":
    unittest.main()

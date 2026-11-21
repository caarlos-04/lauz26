"""Sanity tests for the offline tools. Run from tools/: python -m unittest -v"""
import unittest

from calibrate import annuity_payment, build_calibration, build_profile, sample_profiles
from common import deciles, default_data_dir, load_dataset, month_index, quantile, segment_key

DATA_DIR = default_data_dir()


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

    def test_segment_key(self):
        self.assertEqual(segment_key("student", "single"), "STUDENT|SINGLE")


@unittest.skipUnless((DATA_DIR / "synthetic-profiles.csv").exists(), "dataset not found (set LAUZ_DATA_DIR)")
class DatasetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ds = load_dataset(DATA_DIR)
        cls.calib = build_calibration(cls.ds, sorted(cls.ds["profiles"]), seed=1)

    def test_every_profile_has_24_months(self):
        self.assertTrue(all(len(r) == 24 for r in self.ds["series"].values()))

    def test_calibration_has_full_and_fallback_segments(self):
        keys = {s["segment"] for s in self.calib}
        self.assertIn("STUDENT|SINGLE", keys)
        self.assertIn("STUDENT|ANY", keys)

    def test_living_cost_quantiles_are_ordered(self):
        for s in self.calib:
            lc = s["livingCostChf"]
            self.assertLessEqual(lc["p10"], lc["p50"])
            self.assertLessEqual(lc["p50"], lc["p90"])
            self.assertTrue(s["residualsChf"])

    def test_sample_contains_alexia_and_covers_all_segments(self):
        sample = sample_profiles(self.ds, 50, seed=1)
        self.assertIn("P00001", sample)
        segs = {segment_key(self.ds["profiles"][p]["employment"], self.ds["profiles"][p]["household"]) for p in sample}
        self.assertEqual(len(segs), 24)

    def test_initial_state_is_consistent_with_profile(self):
        built = build_profile(self.ds, "P00001")
        st = built["initialState"]
        self.assertEqual(st["cash"], 8000.0)
        self.assertEqual(st["month"], 0)
        self.assertEqual({e["id"] for e in st["expenses"]}, {"rent", "living"})


if __name__ == "__main__":
    unittest.main()

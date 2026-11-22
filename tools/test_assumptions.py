"""Tests for tools/assumptions.py. They read the committed content/assumptions.json, not the dataset."""
import copy
import json
import unittest

import assumptions as asm


class AssumptionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.a = json.loads(asm.OUT_JSON.read_text(encoding="utf-8"))
        cls.macro = json.loads(asm.MACRO_JSON.read_text(encoding="utf-8")) if asm.MACRO_JSON.exists() else None

    def test_committed_file_is_valid(self):
        self.assertEqual([], asm.check(self.a, self.macro))

    def test_every_sensitivity_parameter_has_a_source_and_both_languages(self):
        for p in self.a["sensitivity"]:
            self.assertTrue(p["source"], p["id"])
            self.assertIn(p["origin"], ("OBSERVED", "ASSUMED"), p["id"])
            self.assertTrue(p["label"]["EN"] and p["label"]["ES"], p["id"])

    def test_engine_defaults_are_the_same(self):
        from simulate import DEFAULTS
        self.assertEqual(DEFAULTS["financingRateAnnual"], self.a["financingRateAnnual"])
        self.assertEqual(DEFAULTS["investmentReturnAnnual"], self.a["investmentReturnAnnual"])

    def broken(self, change):
        a = copy.deepcopy(self.a)
        change(a)
        return asm.check(a, None)

    def test_check_catches_bad_probabilities(self):
        self.assertTrue(self.broken(lambda a: a["defaultCaseProbs"].update(NEUTRAL=0.7)))
        self.assertTrue(self.broken(lambda a: a["defaultCaseProbs"].pop("OPTIMISTIC")))

    def test_check_catches_bad_ranges_and_missing_objectives(self):
        self.assertTrue(self.broken(lambda a: a["sensitivity"][0].update(low=2.0)))
        self.assertTrue(self.broken(lambda a: a["objectivePenalties"].pop("SAFE")))
        self.assertTrue(self.broken(lambda a: a["sensitivity"].append(dict(a["sensitivity"][0]))))   # duplicate id

    def test_check_catches_a_drift_from_the_engine(self):
        self.assertTrue(self.broken(lambda a: a.update(financingRateAnnual=0.05)))


if __name__ == "__main__":
    unittest.main()

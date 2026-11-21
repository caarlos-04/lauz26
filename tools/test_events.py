"""Tests for the content validator and the reference engine. Run from tools/: python -m unittest -v"""
import copy
import json
import unittest

from validate_events import (DEFAULT_EVENTS, DEFAULT_FACTS, Engine, apply_effects, case_from_luck, net_worth,
                             play, validate)

CAT = json.loads(DEFAULT_FACTS.read_text(encoding="utf-8"))
PROFILE = {"id": "T", "ageAtStart": 20, "startMonth": "2025-01", "goals": []}


def twin(cash=1000.0, employment="STUDENT"):
    return {"month": 0,
            "finance": {"cash": cash, "assets": [], "liabilities": [],
                        "income": [{"id": "income", "label": "income", "monthlyChf": 1000.0, "fromMonth": 0,
                                    "untilMonth": None, "sourceEventId": None}],
                        "expenses": [{"id": "rent", "category": "RENT", "monthlyChf": 400.0, "fromMonth": 0,
                                      "untilMonth": None, "sourceEventId": None}]},
            "life": {"employment": employment, "facts": {"EDUCATION": "BACHELOR_IN_PROGRESS"}, "flags": [], "pending": []}}


def run(tw, months, engine=None, attribution=None):
    return (engine or Engine(PROFILE, {})).run(tw, months, attribution if attribution is not None else {}, [], {})


class ValidatorTests(unittest.TestCase):
    def setUp(self):
        self.doc = json.loads(DEFAULT_EVENTS.read_text(encoding="utf-8"))
        self.case = self.doc["events"][0]["options"][0]["cases"]["NEUTRAL"]

    def test_content_files_are_valid(self):
        self.assertEqual(validate(self.doc, CAT), [])

    def test_probabilities_must_sum_to_one(self):
        self.case["prob"] = 0.9
        self.assertTrue(any("sum to" in e for e in validate(self.doc, CAT)))

    def test_all_three_cases_required(self):
        del self.doc["events"][0]["options"][1]["cases"]["OPTIMISTIC"]
        self.assertTrue(any("cases must be" in e for e in validate(self.doc, CAT)))

    def test_exactly_two_options(self):
        self.doc["events"][0]["options"].append(copy.deepcopy(self.doc["events"][0]["options"][0]))
        self.assertTrue(any("exactly 2 options" in e for e in validate(self.doc, CAT)))

    def test_unknown_effect_and_fields_rejected(self):
        self.case["effects"] += [{"type": "win_lottery", "chf": 1}, {"type": "one_off", "chf": 1, "when": 3}]
        errors = validate(self.doc, CAT)
        self.assertTrue(any("unknown effect type" in e for e in errors))
        self.assertTrue(any("unknown fields" in e for e in errors))

    def test_facts_and_flags_must_exist_in_catalog(self):
        self.case["effects"] += [{"type": "set_fact", "key": "EDUCATION", "value": "PHD"},
                                 {"type": "add_flag", "flag": "won_lottery"}]
        self.doc["events"][0]["requires"]["flagsAll"] = ["unknown_flag"]
        errors = validate(self.doc, CAT)
        self.assertTrue(any("EDUCATION='PHD'" in e for e in errors))
        self.assertTrue(any("'won_lottery'" in e for e in errors))
        self.assertTrue(any("'unknown_flag'" in e for e in errors))

    def test_both_languages_required(self):
        self.doc["events"][0]["title"] = {"ES": "Solo español"}
        self.assertTrue(any(".title" in e for e in validate(self.doc, CAT)))


class EngineRuleTests(unittest.TestCase):
    def test_no_effects_cash_identity(self):
        tw, _ = run(twin(), 12)
        self.assertAlmostEqual(tw["finance"]["cash"], 1000 + 12 * 600)

    def test_set_income_replaces_stream_from_offset(self):
        tw = apply_effects(twin(), [{"type": "set_income", "streamId": "income", "monthlyChf": 3000,
                                     "startOffsetMonths": 2}], "E")
        tw, _ = run(tw, 4)
        self.assertAlmostEqual(tw["finance"]["cash"], 1000 + 2 * 600 + 2 * 2600)

    def test_temporary_expense_and_one_off(self):
        tw = apply_effects(twin(), [{"type": "expense_delta", "category": "TUITION", "deltaChf": 100, "months": 3},
                                    {"type": "one_off", "chf": 500, "startOffsetMonths": 5}], "E")
        tw, _ = run(tw, 10)
        self.assertAlmostEqual(tw["finance"]["cash"], 1000 + 10 * 600 - 3 * 100 - 500)

    def test_loan_is_disbursed_and_fully_repaid(self):
        tw = apply_effects(twin(), [{"type": "add_liability", "liabilityType": "PERSONAL_LOAN",
                                     "principalChf": 12000, "annualRate": 0.05, "termMonths": 24}], "E")
        self.assertAlmostEqual(tw["finance"]["cash"], 13000)
        tw, _ = run(tw, 30)
        self.assertAlmostEqual(tw["finance"]["liabilities"][0]["principalChf"], 0.0, places=6)

    def test_negative_cash_pays_financing_interest(self):
        tw, _ = run(twin(cash=-12000), 1, Engine(PROFILE, {}, financing_rate=0.12))
        self.assertAlmostEqual(tw["finance"]["cash"], -12000 + 600 - 120)

    def test_december_bonus_only_when_employed(self):
        eng = Engine(PROFILE, {"EMPLOYED": 0.5})
        student, _ = run(twin(), 12, eng)
        employed, _ = run(twin(employment="EMPLOYED"), 12, eng)
        self.assertAlmostEqual(employed["finance"]["cash"] - student["finance"]["cash"], 500)

    def test_scheduled_life_changes(self):
        tw = apply_effects(twin(), [{"type": "set_employment", "employment": "EMPLOYED", "startOffsetMonths": 3},
                                    {"type": "set_fact", "key": "EDUCATION", "value": "MASTER", "startOffsetMonths": 3},
                                    {"type": "add_flag", "flag": "did_master", "startOffsetMonths": 3}], "E")
        early, _ = run(tw, 3)
        self.assertEqual(early["life"]["employment"], "STUDENT")
        later, _ = run(tw, 4)
        self.assertEqual(later["life"]["employment"], "EMPLOYED")
        self.assertEqual(later["life"]["facts"]["EDUCATION"], "MASTER")
        self.assertIn("did_master", later["life"]["flags"])

    def test_attribution_identity(self):
        tw = apply_effects(twin(cash=-500), [{"type": "add_liability", "liabilityType": "PERSONAL_LOAN",
                                              "principalChf": 5000, "annualRate": 0.07, "termMonths": 12},
                                             {"type": "one_off", "chf": 7000}], "E")
        attribution, start = {}, net_worth(tw)
        end, _ = run(tw, 24, Engine(PROFILE, {}), attribution)
        self.assertAlmostEqual(start + sum(attribution.values()), net_worth(end), places=6)

    def test_luck_resolves_cases_in_order(self):
        opt = {"cases": {"PESSIMISTIC": {"prob": 0.2}, "NEUTRAL": {"prob": 0.6}, "OPTIMISTIC": {"prob": 0.2}}}
        self.assertEqual(case_from_luck(opt, 0.1), "PESSIMISTIC")
        self.assertEqual(case_from_luck(opt, 0.5), "NEUTRAL")
        self.assertEqual(case_from_luck(opt, 0.95), "OPTIMISTIC")

    def test_requirements(self):
        eng = Engine(PROFILE, {})
        ev = {"id": "E", "requires": {"maxCashChf": 0, "flagsNone": ["took_loan"],
                                      "facts": {"EDUCATION": ["BACHELOR_IN_PROGRESS"]}}}
        self.assertFalse(eng.eligible(ev, twin(cash=100), 0, set()))
        self.assertTrue(eng.eligible(ev, twin(cash=-100), 0, set()))
        self.assertFalse(eng.eligible(ev, twin(cash=-100), 0, {"E"}))     # not repeatable


class StoryTests(unittest.TestCase):
    def test_master_then_financing_chain_for_alexia_like_profile(self):
        events = json.loads(DEFAULT_EVENTS.read_text(encoding="utf-8"))["events"]
        item = {"profile": {**PROFILE, "displayName": "T"},
                "initialFinance": twin(cash=8000)["finance"] | {"expenses": [
                    {"id": "rent", "category": "RENT", "monthlyChf": 900.0, "fromMonth": 0, "untilMonth": None,
                     "sourceEventId": None},
                    {"id": "living", "category": "LIVING", "monthlyChf": 1300.0, "fromMonth": 0, "untilMonth": None,
                     "sourceEventId": None}]},
                "initialLife": twin()["life"]}
        res = play(events, item, {"EMPLOYED": 0.35}, CAT, ["A", "B"], [0.5, 0.5], seed=1)
        decisions = [b["eventId"] for b in res["beats"] if b["kind"] == "DECISION"]
        self.assertEqual(decisions, ["E-master", "E-financing"])
        self.assertIn("did_master", res["twin"]["life"]["flags"])
        self.assertAlmostEqual(res["attributionGap"], 0.0, places=6)


if __name__ == "__main__":
    unittest.main()

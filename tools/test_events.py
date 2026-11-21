"""Tests for the content validator and the game loop on statements. Run from tools/: python -m unittest -v"""
import copy
import json
import unittest

from game import advance, apply_effects, case_from_luck, eligible, play
from simulate import run
from statement import check_statement, goal_state, net_worth
from test_support import FACTS as CAT
from test_support import person, simple_model
from validate_events import DEFAULT_EVENTS, validate

GOALS = [{"name": "Emergency savings", "targetChf": 20000.0, "targetDate": "2029-12-31", "priority": "HIGH"}]
MODEL = simple_model()                          # flows = income and rent: hand-computable cash


def student(cash=1000.0, employment="NOT_WORKING"):
    """Income 1,000, rent 400: +600 a month with the simple model."""
    return person(education="BACHELOR_IN_PROGRESS", employment=employment, cash=cash, income=1000.0, rent=400.0,
                  city="Lausanne", goals=GOALS)


def after(s0, months, model=MODEL, **kw):
    return run(s0, months, model, **kw)[-1]


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

    def test_household_change_needs_its_facts(self):
        self.case["effects"] += [{"type": "set_household", "household": "SHARED_HOUSING"}]
        errors = validate(self.doc, CAT)
        self.assertTrue(any("needs set_fact HOUSING=SHARED_FLAT" in e for e in errors), errors)

    def test_new_effects_are_checked(self):
        self.case["effects"] += [{"type": "income_factor", "factor": 0}, {"type": "market_rent", "quantile": 2}]
        errors = validate(self.doc, CAT)
        self.assertTrue(any("factor must be > 0" in e for e in errors))
        self.assertTrue(any("quantile must be in [0, 1]" in e for e in errors))

    def test_both_languages_required(self):
        self.doc["events"][0]["title"] = {"ES": "Solo español"}
        self.assertTrue(any(".title" in e for e in validate(self.doc, CAT)))


class EngineRuleTests(unittest.TestCase):
    def test_no_effects_cash_identity(self):
        self.assertAlmostEqual(after(student(), 12)["cashChf"], 1000 + 12 * 600)

    def test_set_income_from_offset(self):
        s = apply_effects(student(), [{"type": "set_income", "monthlyChf": 3000, "startOffsetMonths": 2}], "E")
        self.assertAlmostEqual(after(s, 4)["cashChf"], 1000 + 2 * 600 + 2 * 2600)

    def test_temporary_expense_and_one_off(self):
        s = apply_effects(student(), [{"type": "expense_delta", "category": "TUITION", "deltaChf": 100, "months": 3},
                                      {"type": "one_off", "chf": 500, "startOffsetMonths": 5}], "E")
        end = after(s, 10)
        self.assertAlmostEqual(end["cashChf"], 1000 + 10 * 600 - 3 * 100 - 500)
        self.assertEqual(end["eventExpensesChf"], {})                 # both ended exactly

    def test_loan_is_paid_out_and_fully_repaid(self):
        s = apply_effects(student(), [{"type": "add_liability", "principalChf": 12000, "annualRate": 0.05,
                                       "termMonths": 24}], "E")
        m1 = after(s, 1)
        payment = m1["cashFlow"]["loanPaymentChf"]
        self.assertAlmostEqual(m1["cashFlow"]["incomeChf"]["loan"], 12000)          # paid out in the first month
        self.assertAlmostEqual(m1["cashChf"], 1000 + 12000 + 600 - payment, places=2)
        interest = 12000 * 0.05 / 12
        self.assertAlmostEqual(m1["loan"]["principalChf"], 12000 + interest - payment, places=1)
        self.assertAlmostEqual(after(s, 30)["loan"]["principalChf"], 0.0, places=1)

    def test_a_second_loan_is_merged(self):
        s = student()
        s["loan"] = {"principalChf": 10000.0, "annualRate": 0.04, "monthlyPaymentChf": 300.0}
        s = apply_effects(s, [{"type": "add_liability", "principalChf": 10000, "annualRate": 0.08, "termMonths": 60}], "E")
        loan = after(s, 1)["loan"]
        self.assertAlmostEqual(loan["annualRate"], 0.06)
        self.assertGreater(loan["principalChf"], 19000)

    def test_negative_cash_pays_financing_interest(self):
        end = after(student(cash=-12000), 1, rules={"financingRateAnnual": 0.12})
        self.assertAlmostEqual(end["cashChf"], -12000 + 600 - 120)

    def test_december_bonus_only_when_employed(self):
        model = simple_model(bonus_ratio=0.5)
        not_working = after(student(), 12, model)
        employed = after(student(employment="EMPLOYED"), 12, model)
        self.assertAlmostEqual(employed["cashChf"] - not_working["cashChf"], 500)

    def test_scheduled_life_changes(self):
        s = apply_effects(student(), [{"type": "set_employment", "employment": "EMPLOYED", "startOffsetMonths": 3},
                                      {"type": "set_fact", "key": "EDUCATION", "value": "MASTER", "startOffsetMonths": 3},
                                      {"type": "add_flag", "flag": "did_master", "startOffsetMonths": 3}], "E")
        early, later = after(s, 3), after(s, 4)
        self.assertEqual(early["person"]["employment"], "NOT_WORKING")
        self.assertEqual(later["person"]["employment"], "EMPLOYED")
        self.assertEqual(later["person"]["facts"]["EDUCATION"], "MASTER")
        self.assertIn("did_master", later["person"]["flags"])
        self.assertEqual(later["pendingChanges"], [])

    def test_attribution_identity(self):
        s = apply_effects(student(cash=-500), [{"type": "add_liability", "principalChf": 5000, "annualRate": 0.07,
                                                "termMonths": 12},
                                               {"type": "one_off", "chf": 7000}], "E")
        attribution = {}
        end, _ = advance(s, 24, MODEL, None, [], attribution)
        self.assertAlmostEqual(net_worth(s) + sum(attribution.values()), net_worth(end), delta=0.5)

    def test_working_student_is_still_a_student(self):
        """Part-time master's: studies AND works. EDUCATION stays 'in progress'; only the work status changes."""
        s = apply_effects(student(), [
            {"type": "set_fact", "key": "EDUCATION", "value": "MASTER_IN_PROGRESS"},
            {"type": "set_employment", "employment": "PART_TIME"},
            {"type": "income_delta", "deltaChf": 2400, "months": 24},
            {"type": "expense_delta", "category": "TUITION", "deltaChf": 667, "months": 24}], "E")
        end = after(s, 12)
        self.assertEqual(end["person"]["employment"], "PART_TIME")
        self.assertEqual(end["person"]["facts"]["EDUCATION"], "MASTER_IN_PROGRESS")
        self.assertAlmostEqual(end["cashChf"], 1000 + 12 * (1000 + 2400 - 400 - 667))
        ev = {"id": "E", "requires": {"facts": {"EDUCATION": ["MASTER_IN_PROGRESS"]}, "employment": ["PART_TIME"]}}
        self.assertTrue(eligible(ev, end, 0, set()))

    def test_event_tuition_replaces_the_learned_tuition(self):
        model = simple_model(tuition=900.0)
        self.assertAlmostEqual(after(student(), 12, model)["cashChf"], 1000 + 12 * 600 - 2 * 900)   # Feb and Sep
        s = apply_effects(student(), [{"type": "expense_delta", "category": "TUITION", "deltaChf": 500,
                                       "months": 12}], "E")
        self.assertAlmostEqual(after(s, 12, model)["cashChf"], 1000 + 12 * 100)                     # only the event's

    def test_income_factor_is_undone_after_its_months(self):
        s = apply_effects(student(), [{"type": "income_factor", "factor": 0.5, "months": 3}], "E")
        sts = run(s, 6, MODEL)
        self.assertEqual([x["monthlyIncomeChf"] for x in sts[1:]], [500.0, 500.0, 500.0, 1000.0, 1000.0, 1000.0])
        self.assertAlmostEqual(sts[-1]["cashChf"], 1000 + 3 * 100 + 3 * 600)

    def test_moving_sets_the_market_rent_of_the_new_city_and_household(self):
        s = person(city="Bern", rent=1800.0)                       # above the market rent of Bern (1,000)
        moved = apply_effects(s, [{"type": "set_fact", "key": "CITY", "value": "Geneva"},
                                  {"type": "market_rent", "quantile": 0.5}], "E")
        self.assertAlmostEqual(after(moved, 1)["monthlyRentChf"], 1500.0)          # single, Geneva
        shared = apply_effects(s, [{"type": "set_household", "household": "SHARED_HOUSING"},
                                   {"type": "set_fact", "key": "HOUSING", "value": "SHARED_FLAT"},
                                   {"type": "set_fact", "key": "FAMILY", "value": "SINGLE"},
                                   {"type": "market_rent", "quantile": 0.75}], "E")
        m1 = after(shared, 1)
        self.assertEqual(m1["person"]["household"], "SHARED_HOUSING")
        self.assertAlmostEqual(m1["monthlyRentChf"], 1000 * 0.5 * 1.25)            # shared, Bern, p75 of 0.5..1.5

    def test_requirements_on_household(self):
        ev = {"id": "E", "requires": {"household": ["SHARED_HOUSING"]}}
        self.assertFalse(eligible(ev, student(), 0, set()))
        s = student()
        s["person"]["household"] = "SHARED_HOUSING"
        self.assertTrue(eligible(ev, s, 0, set()))

    def test_luck_resolves_cases_in_order(self):
        opt = {"cases": {"PESSIMISTIC": {"prob": 0.2}, "NEUTRAL": {"prob": 0.6}, "OPTIMISTIC": {"prob": 0.2}}}
        self.assertEqual(case_from_luck(opt, 0.1), "PESSIMISTIC")
        self.assertEqual(case_from_luck(opt, 0.5), "NEUTRAL")
        self.assertEqual(case_from_luck(opt, 0.95), "OPTIMISTIC")

    def test_requirements(self):
        ev = {"id": "E", "requires": {"maxCashChf": 0, "flagsNone": ["took_loan"],
                                      "facts": {"EDUCATION": ["BACHELOR_IN_PROGRESS"]}}}
        self.assertFalse(eligible(ev, student(cash=100), 0, set()))
        self.assertTrue(eligible(ev, student(cash=-100), 0, set()))
        self.assertFalse(eligible(ev, student(cash=-100), 0, {"E"}))     # not repeatable


class StoryTests(unittest.TestCase):
    def setUp(self):
        events = json.loads(DEFAULT_EVENTS.read_text(encoding="utf-8"))["events"]
        alexia_like = person(education="BACHELOR_IN_PROGRESS", employment="NOT_WORKING", cash=8000.0, income=1200.0,
                             rent=900.0, city="Lausanne", goals=GOALS)
        self.res = play(events, alexia_like, simple_model(living=1300.0, bonus_ratio=0.35), None, ["A", "B"],
                        [0.5, 0.5], seed=1)

    def test_master_then_financing_chain(self):
        decisions = [b["eventId"] for b in self.res["beats"] if b["kind"] == "DECISION"]
        self.assertEqual(decisions[:2], ["E-master", "E-financing"])
        self.assertEqual(len(decisions), 5)                            # no quiet turn: an event for every stage
        self.assertEqual(len(set(decisions)), 5)                       # none repeated
        self.assertIn("did_master", self.res["statements"][-1]["person"]["flags"])
        self.assertAlmostEqual(self.res["attributionGap"], 0.0, delta=0.5)
        flags = {b["data"]["flag"]: b["eventId"] for b in self.res["beats"] if "flag" in b["data"]}
        self.assertEqual(flags["did_master"], "E-master")              # each milestone credited to its own event

    def test_one_consistent_statement_per_turn(self):
        sts, monthly = self.res["statements"], self.res["monthly"]
        self.assertEqual([s["month"] for s in sts], [0, 24, 48, 72, 96, 120])
        self.assertEqual(sts[2]["person"]["facts"]["EDUCATION"], "MASTER")
        self.assertEqual([e for prev, s in zip(monthly, monthly[1:]) for e in check_statement(s, prev)], [])

    def test_cash_goal_uses_cash_basis(self):
        s = self.res["statements"][-1]
        self.assertEqual(goal_state(s["goals"][0], s), "REACHED")


if __name__ == "__main__":
    unittest.main()

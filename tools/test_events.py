"""Tests for the content validator and the game loop on statements. Run from tools/: python -m unittest -v"""
import copy
import json
import unittest

from game import (FULL_RANDOM, NOTHING, SAME_LEVEL, actions_at, advance, apply_effects, child_id, draw,
                  eligible, fnv1a64, node_luck, outcome_text, play, probit, resolve)
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
        self.master = next(a for a in self.doc["actions"] if a["id"] == "A-master")
        self.case = self.master                      # the effects of an action are its median outcome

    def test_content_files_are_valid(self):
        self.assertEqual(validate(self.doc, CAT), [])

    def test_distributions_are_checked(self):
        spec = self.master["uncertain"][1]
        spec["low"]["value"] = 99999                                     # above the median
        spec["targets"].append({"effect": 0, "field": "monthlyChf"})     # the tuition effect has no such field
        errors = validate(self.doc, CAT)
        self.assertTrue(any("low.value <= median" in e for e in errors), errors)
        self.assertTrue(any("is not a field of an effect" in e for e in errors), errors)

    def test_outcome_placeholders_must_be_uncertain_numbers(self):
        self.master["outcome"]["EN"] += " {bonus}"
        self.assertTrue(any("{bonus} is not an uncertain number" in e for e in validate(self.doc, CAT)))

    def test_nothing_is_always_in_the_pool(self):
        self.doc["actions"] = [a for a in self.doc["actions"] if a["id"] != NOTHING]
        self.assertTrue(any("'NOTHING'" in e for e in validate(self.doc, CAT)))

    def test_only_nothing_may_have_no_effects(self):
        self.case["effects"] = []
        self.assertTrue(any("action A-master: no effects" in e for e in validate(self.doc, CAT)))

    def test_shock_needs_a_probability(self):
        self.doc["shocks"][0]["probability"] = 1.5
        self.assertTrue(any("probability must be in (0, 1)" in e for e in validate(self.doc, CAT)))

    def test_unknown_effect_and_fields_rejected(self):
        self.case["effects"] += [{"type": "win_lottery", "chf": 1}, {"type": "one_off", "chf": 1, "when": 3}]
        errors = validate(self.doc, CAT)
        self.assertTrue(any("unknown effect type" in e for e in errors))
        self.assertTrue(any("unknown fields" in e for e in errors))

    def test_facts_and_flags_must_exist_in_catalog(self):
        self.case["effects"] += [{"type": "set_fact", "key": "EDUCATION", "value": "PHD"},
                                 {"type": "add_flag", "flag": "won_lottery"}]
        self.master["requires"]["flagsAll"] = ["unknown_flag"]
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
        self.master["title"] = {"ES": "Solo español"}
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

    def test_probit(self):
        import statistics as st
        for p in (0.01, 0.05, 0.25, 0.5, 0.8, 0.95):
            self.assertAlmostEqual(st.NormalDist().inv_cdf(p), probit(p), places=7)

    def test_luck_is_a_percentile_of_each_uncertain_number(self):
        salary = {"name": "salary", "law": "LOGNORMAL", "median": 6100, "low": {"q": 0.25, "value": 4500},
                  "high": {"q": 0.75, "value": 7500}, "better": "HIGH", "step": 50, "format": "chf", "targets": []}
        # the distribution goes through the three values of the data
        self.assertEqual([4500, 6100, 7500], [draw(salary, x) for x in (0.25, 0.5, 0.75)])
        self.assertEqual(draw(salary, 0.95), draw(salary, 0.999))      # no draws beyond p95
        cost = dict(salary, better="LOW")                               # a cost: good luck = the low side
        self.assertEqual(4500, draw(cost, 0.75))
        rent = {"law": "QUANTILE", "median": 0.5, "low": {"q": 0.05, "value": 0.05}, "high": {"q": 0.95, "value": 0.95},
                "better": "LOW", "step": 0.01}
        self.assertAlmostEqual(0.2, draw(rent, 0.8))

    def test_one_luck_sets_every_uncertain_number_and_the_story(self):
        master = json.loads(DEFAULT_EVENTS.read_text(encoding="utf-8"))["actions"][1]
        effects, values = resolve(master, 0.9)
        self.assertEqual({"tuition": 115, "salary": 9050}, values)       # good luck: cheap tuition AND a high salary
        self.assertEqual(115, effects[0]["deltaChf"])
        self.assertEqual(9050, effects[6]["monthlyChf"])
        self.assertEqual("Pagas 115 CHF al mes de matrícula durante dos años. Después empiezas a trabajar con 9’050 CHF al mes.",
                         outcome_text(master, values, "ES"))

    def test_requirements(self):
        ev = {"id": "E", "requires": {"maxCashChf": 0, "flagsNone": ["took_loan"],
                                      "facts": {"EDUCATION": ["BACHELOR_IN_PROGRESS"]}}}
        self.assertFalse(eligible(ev, student(cash=100), 0, set()))
        self.assertTrue(eligible(ev, student(cash=-100), 0, set()))
        self.assertFalse(eligible(ev, student(cash=-100), 0, {"E"}))     # not repeatable


class StoryTests(unittest.TestCase):
    def setUp(self):
        doc = json.loads(DEFAULT_EVENTS.read_text(encoding="utf-8"))
        self.actions, self.shocks = doc["actions"], doc["shocks"]
        self.alexia_like = person(education="BACHELOR_IN_PROGRESS", employment="NOT_WORKING", cash=8000.0,
                                  income=1200.0, rent=900.0, city="Lausanne", goals=GOALS)
        self.model = simple_model(living=1300.0, bonus_ratio=0.35)
        self.res = self.play(["A-master", "A-loan"])

    def play(self, path, seed=1, mode=SAME_LEVEL, shocks=None):
        return play(self.actions, self.shocks if shocks is None else shocks, self.alexia_like, self.model, None, path,
                    seed, mode)

    def test_the_player_drives_the_life(self):
        decisions = [b["eventId"] for b in self.res["beats"] if b["kind"] == "DECISION"]
        self.assertEqual(decisions, ["A-master", "A-loan"])          # only what was chosen
        quiet = [b for b in self.res["beats"] if b["kind"] == "QUIET_PERIOD"]
        self.assertEqual(len(quiet), 3)                                # the missing turns are NOTHING
        self.assertIn("did_master", self.res["statements"][-1]["person"]["flags"])
        self.assertAlmostEqual(self.res["attributionGap"], 0.0, delta=0.5)
        flags = {b["data"]["flag"]: b["eventId"] for b in self.res["beats"] if "flag" in b["data"]}
        self.assertEqual(flags["did_master"], "A-master")              # each milestone credited to its own action

    def test_one_consistent_statement_per_turn(self):
        sts, monthly = self.res["statements"], self.res["monthly"]
        self.assertEqual([s["month"] for s in sts], [0, 24, 48, 72, 96, 120])
        self.assertEqual(sts[2]["person"]["facts"]["EDUCATION"], "MASTER")
        self.assertEqual([e for prev, s in zip(monthly, monthly[1:]) for e in check_statement(s, prev)], [])

    def test_nothing_keeps_what_is_under_way(self):
        """A master's started at turn 0 ends at month 24 although the player then does nothing."""
        res = self.play(["A-master", NOTHING])
        self.assertEqual(res["statements"][2]["person"]["facts"]["EDUCATION"], "MASTER")
        self.assertEqual(res["statements"][2]["person"]["employment"], "EMPLOYED")

    def test_pool_always_has_nothing_and_respects_requirements(self):
        ids = [a["id"] for a in actions_at(self.actions, self.alexia_like, 0, set())]
        self.assertEqual(ids[0], NOTHING)
        self.assertIn("A-master", ids)
        self.assertNotIn("A-loan", ids)                                 # cash is positive
        self.assertNotIn("A-master", [a["id"] for a in actions_at(self.actions, self.alexia_like, 0, {"A-master"})])
        with self.assertRaises(ValueError):
            self.play(["A-loan"])                                       # not available at the start

    def test_same_level_luck_is_shared_by_siblings_full_random_is_not(self):
        same = {a: node_luck(7, SAME_LEVEL, child_id("", a), 0) for a in ("A-master", "A-start-working", NOTHING)}
        self.assertEqual(len(set(same.values())), 1)
        full = {a: node_luck(7, FULL_RANDOM, child_id("", a), 0) for a in ("A-master", "A-start-working", NOTHING)}
        self.assertEqual(len(set(full.values())), 3)
        self.assertEqual(child_id("A-master", NOTHING), "A-master/NOTHING")

    def test_portable_hash(self):
        self.assertEqual(fnv1a64(""), 0xCBF29CE484222325)
        self.assertEqual(fnv1a64("a"), 0xAF63DC4C8601EC8C)

    def test_a_shock_happens_whatever_you_choose_and_is_told(self):
        sure = [dict(self.shocks[1], requires={"employment": ["EMPLOYED"]}, probability=0.999999)]
        res = self.play(["A-start-working"], shocks=sure)
        shock = [b for b in res["beats"] if b["kind"] == "SHOCK"]
        self.assertEqual(shock[0]["eventId"], "S-job-loss")
        lost = [b for b in res["beats"] if b["data"].get("employment") == "UNEMPLOYED"]
        self.assertEqual(lost[0]["eventId"], "S-job-loss")
        self.assertAlmostEqual(res["attributionGap"], 0.0, delta=0.5)
        # a student who keeps studying cannot lose a job she does not have
        self.assertFalse([b for b in self.play([NOTHING], shocks=sure)["beats"][:6] if b["kind"] == "SHOCK"])

    def test_cash_goal_uses_cash_basis_and_its_deadline(self):
        res = self.play(["A-start-working"])
        reached = [b for b in res["beats"] if b["kind"] == "GOAL_REACHED"]
        self.assertEqual("Emergency savings", reached[0]["data"]["goal"])         # measured on cash, before 2029-12
        hit = next(m for m in res["monthly"] if m["cashChf"] >= GOALS[0]["targetChf"])
        self.assertEqual(reached[0]["month"], hit["month"])
        end = res["statements"][-1]
        self.assertEqual("MISSED", goal_state(end["goals"][0], end))   # after its date a goal can no longer be reached


if __name__ == "__main__":
    unittest.main()

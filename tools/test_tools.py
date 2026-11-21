"""Tests for the shared helpers and the FinancialStatement contract. Run from tools/: python -m unittest -v"""
import unittest

from common import deciles, default_data_dir, load_dataset, month_index, quantile, work_status
from statement import (annuity_payment, check_statement, dataset_opening, goal_state, initial_facts, net_worth,
                       next_period)
from test_support import FACTS, person

DATA_DIR = default_data_dir()
# The whole contract (shared/.../model/FinancialStatement.kt): nothing else is stored.
STATEMENT_FIELDS = {"profileId", "month", "period", "person", "cashChf", "investmentsChf", "loan", "monthlyIncomeChf",
                    "monthlyRentChf", "eventExpensesChf", "cashFlow", "goals", "pendingChanges"}
PERSON_FIELDS = {"displayName", "age", "employment", "household", "facts", "flags"}


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

    def test_periods(self):
        self.assertEqual(next_period("2025-01-01"), "2025-01")      # opening -> first month
        self.assertEqual(next_period("2025-01"), "2025-02")
        self.assertEqual(next_period("2025-12"), "2026-01")


class StatementTests(unittest.TestCase):
    def test_studying_and_working_are_independent(self):
        self.assertEqual(work_status("STUDENT"), "NOT_WORKING")
        self.assertEqual(work_status("PART_TIME"), "PART_TIME")
        employed = initial_facts("EMPLOYED", "SINGLE", "Bern", FACTS)
        self.assertEqual(employed["EDUCATION"], "UNKNOWN")
        self.assertEqual(employed["OCCUPATION"], "PROFESSIONAL")

    def test_initial_facts_from_profile_rules(self):
        self.assertEqual(initial_facts("STUDENT", "SHARED_HOUSING", "Lausanne", FACTS),
                         {"EDUCATION": "BACHELOR_IN_PROGRESS", "OCCUPATION": "NONE", "HOUSING": "SHARED_FLAT",
                          "FAMILY": "SINGLE", "CITY": "Lausanne"})

    def test_new_person_opening_statement(self):
        s = person(education="BACHELOR_IN_PROGRESS", employment="PART_TIME", city="Geneva")      # a working student
        self.assertEqual(set(s), STATEMENT_FIELDS)
        self.assertEqual(set(s["person"]), PERSON_FIELDS)
        self.assertEqual((s["month"], s["period"], s["cashFlow"]), (0, "2025-01-01", None))
        self.assertEqual(s["person"]["facts"]["EDUCATION"], "BACHELOR_IN_PROGRESS")
        self.assertEqual(s["person"]["facts"]["CITY"], "Geneva")
        self.assertEqual(s["person"]["employment"], "PART_TIME")

    def test_net_worth_and_goals_are_computed(self):
        s = person(cash=12000.0, investments=5000.0, loan={"principalChf": 3000.0, "annualRate": 0.05,
                                                            "monthlyPaymentChf": 100.0},
                   goals=[{"name": "Emergency savings", "targetChf": 10000.0, "targetDate": "2029-12-31",
                           "priority": "HIGH"},
                          {"name": "Long-term financial resilience", "targetChf": 15000.0,
                           "targetDate": "2029-12-31", "priority": "MEDIUM"}])
        self.assertEqual(net_worth(s), 14000.0)
        self.assertEqual([goal_state(g, s) for g in s["goals"]], ["REACHED", "IN_PROGRESS"])   # cash vs net worth


@unittest.skipUnless((DATA_DIR / "synthetic-profiles.csv").exists(), "dataset not found (set LAUZ_DATA_DIR)")
class DatasetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ds = load_dataset(DATA_DIR)

    def test_every_profile_has_24_months(self):
        self.assertTrue(all(len(r) == 24 for r in self.ds["series"].values()))

    def test_every_profile_gets_a_valid_opening_statement(self):
        for pid in self.ds["profiles"]:
            s = dataset_opening(self.ds, pid, FACTS)               # raises if a fact is not in the catalog
            self.assertEqual(set(s), STATEMENT_FIELDS, pid)

    def test_alexia_opening_statement(self):
        s = dataset_opening(self.ds, "P00001", FACTS)
        self.assertEqual((s["cashChf"], s["investmentsChf"], s["loan"]), (8000.0, 0.0, None))   # no investments
        self.assertEqual((s["monthlyIncomeChf"], s["monthlyRentChf"]), (1200.0, 900.0))
        self.assertEqual(s["person"]["facts"]["CITY"], "Lausanne")
        self.assertEqual(s["person"]["employment"], "NOT_WORKING")
        self.assertEqual(s["person"]["facts"]["EDUCATION"], "BACHELOR_IN_PROGRESS")
        self.assertEqual([g["targetDate"] for g in s["goals"]], ["2029-12-31", "2032-12-31"])

    def test_dataset_loan_gets_an_assumed_annuity(self):
        loan = dataset_opening(self.ds, "P00013", FACTS)["loan"]
        self.assertGreater(loan["monthlyPaymentChf"], 0)          # the dataset has 0: 60-month annuity assumed


if __name__ == "__main__":
    unittest.main()

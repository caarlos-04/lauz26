"""Tests for tools/interview.py. They read the committed content (content/interview*.json), not the dataset."""
import json
import unittest

import interview as iv


class InterviewContentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.interview, cls.defaults, cls.facts = iv.load_committed()

    def test_committed_content_is_valid(self):
        self.assertEqual([], iv.check(self.interview, self.defaults, self.facts))

    def test_every_profile_field_is_asked(self):
        # the Kotlin enum is the contract (EDUCATION was missing before it was added there)
        fields = iv.kotlin_enum("ProfileField")
        self.assertIn("EDUCATION", fields)
        asked = {f for q in self.interview["questions"] for o in q["options"] for f in o["sets"]}
        self.assertEqual(set(fields), asked)

    def test_numeric_questions_offer_i_dont_know(self):
        for qid in ("income", "rent", "cash"):
            q = next(x for x in self.interview["questions"] if x["id"] == qid)
            self.assertTrue(any(o["sets"] == {} for o in q["options"]), qid)

    def test_cities_are_the_dataset_cities(self):
        q = next(x for x in self.interview["questions"] if x["id"] == "city")
        cities = {o["sets"]["CITY"] for o in q["options"]}
        self.assertEqual(12, len(cities))
        self.assertNotIn("ABROAD", cities)     # nobody in the dataset lives abroad: no data to simulate it
        self.assertTrue(cities <= set(self.facts["facts"]["CITY"]))

    def test_texts_in_both_languages_and_strings_export(self):
        en, es = iv.strings_xml(self.interview, "EN"), iv.strings_xml(self.interview, "ES")
        for q in self.interview["questions"]:
            for key in [q["textKey"]] + [o["labelKey"] for o in q["options"]]:
                self.assertIn(f'name="{key}"', en)
                self.assertIn(f'name="{key}"', es)
        self.assertIn("¿Cuántos años tienes?", es)
        for name in self.defaults["goals"]:                      # every dataset goal has a UI text
            self.assertIn(f'name="{iv.goal_key(name)}"', es)


class OpeningStatementTest(unittest.TestCase):
    # Same case as InterviewBuilderTest.kt: keep both in sync.
    ANSWERS = [("age", "a1"), ("city", "lausanne"), ("household", "single"), ("employment", "not_working"),
               ("education", "bachelor_in_progress"), ("income", "idk"), ("rent", "rdk"), ("cash", "c2"),
               ("goal", "emergency_savings"), ("objective", "balanced"), ("mincash", "m3")]

    @classmethod
    def setUpClass(cls):
        cls.interview, cls.defaults, cls.facts = iv.load_committed()

    def build(self, answers):
        return iv.person_from_fields(iv.fields_from_answers(self.interview, answers), self.defaults, self.facts)

    def test_student_with_defaults(self):
        s, prefs = self.build(self.ANSWERS)
        p = s["person"]
        self.assertEqual((21, "NOT_WORKING", "SINGLE"), (p["age"], p["employment"], p["household"]))
        self.assertEqual({"EDUCATION": "BACHELOR_IN_PROGRESS", "OCCUPATION": "NONE", "HOUSING": "RENTED_FLAT",
                          "FAMILY": "SINGLE", "CITY": "Lausanne"}, p["facts"])
        self.assertEqual(1200.0, s["monthlyIncomeChf"])     # default: median income of the dataset's students
        self.assertEqual(1590.0, s["monthlyRentChf"])       # default: median rent, Lausanne x SINGLE
        self.assertEqual(9000.0, s["cashChf"])
        self.assertEqual({"name": "Emergency savings", "targetChf": 10400.0, "targetDate": "2029-12-31", "priority": "HIGH"},
                         s["goals"][0])
        self.assertEqual({"objective": "BALANCED", "minCashChf": 3500.0, "penaltyChfPerMonthBelowMin": 500.0}, prefs)
        self.assertEqual((0.0, None, 0, "2025-01-01"), (s["investmentsChf"], s["loan"], s["month"], s["period"]))

    def test_answers_override_defaults(self):
        answers = [(q, "i4" if q == "income" else "r4" if q == "rent" else o) for q, o in self.ANSWERS]
        s, _ = self.build(answers)
        self.assertEqual(8750.0, s["monthlyIncomeChf"])
        self.assertEqual(2250.0, s["monthlyRentChf"])

    def test_working_student_keeps_both(self):
        answers = [(q, "part_time" if q == "employment" else "master_in_progress" if q == "education" else o) for q, o in self.ANSWERS]
        s, _ = self.build(answers)
        self.assertEqual("PART_TIME", s["person"]["employment"])
        self.assertEqual("MASTER_IN_PROGRESS", s["person"]["facts"]["EDUCATION"])
        self.assertEqual("PROFESSIONAL", s["person"]["facts"]["OCCUPATION"])

    def test_shared_flat_and_family_facts(self):
        s, _ = self.build([(q, "shared" if q == "household" else o) for q, o in self.ANSWERS])
        self.assertEqual(("SHARED_FLAT", "SINGLE"), (s["person"]["facts"]["HOUSING"], s["person"]["facts"]["FAMILY"]))
        s, _ = self.build([(q, "family" if q == "household" else o) for q, o in self.ANSWERS])
        self.assertEqual("FAMILY", s["person"]["facts"]["FAMILY"])

    def test_missing_field_is_reported(self):
        with self.assertRaises(ValueError):
            self.build([a for a in self.ANSWERS if a[0] != "city"])

    def test_goal_is_clamped_to_the_dataset_range(self):
        answers = [(q, "i5" if q == "income" else "long_term_financial_resilience" if q == "goal" else o) for q, o in self.ANSWERS]
        s, _ = self.build(answers)     # 21.1 x 10,750 = 226,800: inside the range
        self.assertEqual(226800.0, s["goals"][0]["targetChf"])
        low = [(q, "i1" if q == "income" else o) for q, o in self.ANSWERS]
        s, _ = self.build(low)         # 8.7 x 950 = 8,265 -> raised to the smallest goal in the dataset
        self.assertEqual(10000.0, s["goals"][0]["targetChf"])


class HelpersTest(unittest.TestCase):
    def test_representative_uses_the_median_only_with_enough_people(self):
        enough = [100.0] * iv.MIN_PEOPLE
        self.assertEqual((100, iv.MIN_PEOPLE), iv.representative(enough, 0, 500, 50, 0))
        self.assertEqual(250, iv.representative([100.0], 0, 500, 50, 0)[0])        # middle of the band
        self.assertEqual(1250, iv.representative([], 1000, None, 50, 0)[0])        # open top: 1.25 x lower edge
        self.assertEqual(1000, iv.representative([], None, 2000, 50, 0)[0])        # open bottom: half the edge

    def test_money_labels(self):
        self.assertEqual("CHF 2’500 – 5’000", iv.money_label(2500, 5000)["EN"])
        self.assertEqual("Más de CHF 10’000", iv.money_label(10000, None)["ES"])
        self.assertEqual("Less than CHF 1’200", iv.money_label(None, 1200)["EN"])

    def test_age_bands(self):
        self.assertEqual("18–24", iv.age_label(18, 25)["EN"])
        self.assertEqual("65 or older", iv.age_label(65, None)["EN"])


if __name__ == "__main__":
    unittest.main()

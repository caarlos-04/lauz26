"""Tests for tools/chronicle.py. Same beats as ChronicleTest.kt: keep both in sync."""
import unittest

import chronicle as ch


def b(month, age, kind, event=None, **data):
    return {"month": month, "age": age, "kind": kind, "eventId": event, "data": {k: str(v) for k, v in data.items()}}


BEATS = [
    b(0, 20, "START", cash=8000),
    b(0, 20, "DECISION", "A-master", label="ignored: texts come from events.json"),
    b(0, 20, "OUTCOME", "A-master", luck="0.50", tuition="400", salary="6100"),
    b(0, 20, "LIFE_CHANGE", "A-master", fact="EDUCATION", value="MASTER_IN_PROGRESS"),
    b(14, 21, "CASH_NEGATIVE", cash=-995),
    b(24, 22, "QUIET_PERIOD", "NOTHING"),
    b(25, 22, "LIFE_CHANGE", "A-master", employment="EMPLOYED"),
    b(25, 22, "LIFE_CHANGE", "A-master", flag="did_master"),
    b(61, 25, "GOAL_REACHED", goal="Career transition", target=39366),
    b(120, 30, "END", netWorth=158098, cash=158098),
]


class ChronicleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tpl, cls.facts, cls.events = ch.load("chronicle.json"), ch.load("facts.json"), ch.load("events.json")

    def lines(self, lang):
        return [x["text"] for x in ch.render(BEATS, lang, self.tpl, self.events, self.facts)]

    def test_templates_are_valid(self):
        self.assertEqual([], ch.check(self.tpl, self.facts, self.events))

    def test_english(self):
        narrative = "You pay CHF 400 a month in tuition for two years. Then you start working at CHF 6’100 a month."
        self.assertEqual([
            "You start at 20, in 2025, with CHF 8’000 in your account.",
            "At 20 (2025) you decided: Do a master's.",
            narrative,
            "At 20 your studies reached a new stage: Studying for a master's.",          # cause = the decision being told
            "At 21 your account went into the red (CHF 995 below zero) and you started paying interest on it.",
            "At 22 you carried on as you were for two years, with no new decisions.",
            "At 22 you started working full time (because of “Do a master's”).",
            "Milestone at 22: Did a master's (because of “Do a master's”).",
            "At 25 you reached your goal: a career change (CHF 39’366).",
            "10 years later, at 30, your net worth is CHF 158’098, CHF 158’098 of it in cash.",
        ], self.lines("EN"))

    def test_spanish_money_and_paragraphs(self):
        es = self.lines("ES")
        self.assertEqual("A los 21 años tu cuenta quedó en números rojos (995 CHF por debajo de cero) y empezaste a pagar intereses.", es[4])
        self.assertEqual("10 años después, con 30 años, tu patrimonio es de 158’098 CHF, de los que 158’098 CHF están en efectivo.", es[-1])
        self.assertEqual(4, len(ch.paragraphs(ch.render(BEATS, "ES", self.tpl, self.events, self.facts))))

    def test_unknown_codes_never_crash(self):
        lines = ch.render([b(0, 20, "LIFE_CHANGE", "A-nope", flag="new_flag"), b(1, 20, "GOAL_MISSED", goal="Some goal", target=1)],
                          "EN", self.tpl, self.events, self.facts)
        self.assertEqual("Milestone at 20: new_flag.", lines[0]["text"])
        self.assertIn("Some goal", lines[1]["text"])

    def test_money_format(self):
        self.assertEqual("CHF -5’120", ch.chf(-5120, "EN"))
        self.assertEqual("1’234’567 CHF", ch.chf(1234567, "ES"))
        self.assertEqual("CHF 0", ch.chf(0, "EN"))


if __name__ == "__main__":
    unittest.main()

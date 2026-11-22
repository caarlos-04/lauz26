"""Tests for tools/ship_resources.py: what the app ships is identical to content/ and holds no dataset rows."""
import unittest

import ship_resources as sr


class ResourcesTest(unittest.TestCase):
    def test_shipped_resources_are_current_and_aggregate_only(self):
        self.assertEqual([], sr.check())

    def test_row_identifiers_are_detected(self):
        for text in ("P00001", "T-P00001-202501-0001", "A-P01200-CASH", "AS-P00001-INV"):
            self.assertTrue(sr.ROW_ID.search(text), text)
        for text in ("salary_multiplier", "P1 Interview", "CHF 8’000", "A-master", "S-job-loss"):
            self.assertIsNone(sr.ROW_ID.search(text), text)


if __name__ == "__main__":
    unittest.main()

import json
import unittest
from capture_screening_diagnostics import sanitize

class CaptureTests(unittest.TestCase):
    def line(self, body, tag="Telecom"):
        return sanitize("09-17 17:23:40.352 2007 2007 W " + tag + ": " + body)

    def test_preserves_negation_order_and_exception(self):
        result = self.line("CallScreeningService could not bind: SecurityException permission denied")
        self.assertEqual(result["context"], "CallScreeningService could not bind : SecurityException permission denied")

    def test_keeps_unknown_warning_as_lossy_row(self):
        result = self.line("OEM_UNDOCUMENTED_REASON")
        self.assertEqual(result["context"], "<redacted>")
        self.assertGreater(result["redacted_tokens"], 0)
        self.assertTrue(result["lossy"])

    def test_redacts_private_values_even_dictionary_words(self):
        result = json.dumps(self.line('CallScreeningService caller=+86 13812345678; name="blocked"; number=555-123-4567; uri=tel:13812345678; contact=Alice; failed'))
        for private in ["13812345678", "555", "Alice", "blocked", "tel:"]:
            self.assertNotIn(private, result)
        self.assertIn("failed", result)

    def test_retains_known_component_and_rejects_unknown_package(self):
        result = self.line("bind com.callguard.app/.screening.CallGuardScreeningService com.private.person")
        self.assertIn("com.callguard.app/.screening.CallGuardScreeningService", result["context"])
        self.assertNotIn("com.private.person", result["context"])

    def test_preserves_app_event_and_monotonic_time(self):
        result = self.line("responded eventId=605b45c0-b9ab-4c78-bd31-8a42dc4bd8be decision=ALLOW respondedAtMs=487849953 latencyMs=0", "CallGuard")
        self.assertEqual(result["eventId"], "605b45c0-b9ab-4c78-bd31-8a42dc4bd8be")
        self.assertEqual(result["respondedAtMs"], 487849953)
        self.assertEqual(result["latencyMs"], 0)

    def test_rejects_unselected_tag_and_unstructured_lines(self):
        self.assertIsNone(self.line("private text", "Unselected"))
        self.assertIsNone(sanitize("arbitrary raw stack continuation"))

if __name__ == "__main__":
    unittest.main(verbosity=2)

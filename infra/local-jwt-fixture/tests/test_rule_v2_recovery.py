import importlib.util
from pathlib import Path
import sys
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location("rule_v2_recovery", ROOT / "verify_rule_v2_recovery.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
outage_spec = importlib.util.spec_from_file_location("rule_v2_ai_outage", ROOT / "verify_rule_v2_ai_outage.py")
outage = importlib.util.module_from_spec(outage_spec)
outage_spec.loader.exec_module(outage)

TX = "12345678-1234-4234-8234-123456789012"
CASE = "12345678-1234-4234-8234-123456789013"
REQUEST = "12345678-1234-4234-8234-123456789014"
EXECUTION = "12345678-1234-4234-8234-123456789015"


class RecoveryTests(unittest.TestCase):
    def test_outage_preparation_failure_does_not_request_report(self):
        with (mock.patch.object(outage.shared, "load_fixtures", return_value={
                "fixtures": [{"id": "synthetic"}], "scoringPolicyVersion": "v2"}),
              mock.patch.object(outage.shared, "make_input", return_value=([], {"transactionId": TX})),
              mock.patch.object(outage.shared, "api", side_effect=outage.shared.EvaluationError("RULE_UNAVAILABLE")),
              mock.patch.object(outage.urllib.request, "urlopen") as request):
            with self.assertRaises(outage.shared.EvaluationError):
                outage.prepare()
            request.assert_not_called()

    def test_outage_requires_prepared_case(self):
        with mock.patch.object(outage.urllib.request, "urlopen") as request:
            with self.assertRaises(outage.shared.EvaluationError):
                outage.verify(None)
            request.assert_not_called()

    def test_reuses_exact_case_request_execution_chain(self):
        responses = [
            {"transaction": {"processingStatus": "HELD"}},
            {"case": {"caseStatus": "IN_REVIEW"}},
            {"currentReport": {"caseId": CASE, "executionId": EXECUTION,
                               "initiatingAiRequestId": REQUEST, "reportStatus": "COMPLETED"}},
            {"caseId": CASE, "executionId": EXECUTION, "reportStatus": "COMPLETED"},
        ]
        with mock.patch.object(module.shared, "api", side_effect=responses):
            result = module.verify(TX, CASE, REQUEST, EXECUTION)
        self.assertEqual(result["status"], "VERIFIED")

    def test_other_case_report_cannot_pass(self):
        responses = [
            {"transaction": {"processingStatus": "HELD"}},
            {"case": {"caseStatus": "IN_REVIEW"}},
            {"currentReport": {"caseId": TX, "executionId": EXECUTION,
                               "initiatingAiRequestId": REQUEST, "reportStatus": "COMPLETED"}},
            {"caseId": CASE, "executionId": EXECUTION, "reportStatus": "COMPLETED"},
        ]
        with mock.patch.object(module.shared, "api", side_effect=responses):
            with self.assertRaises(module.shared.EvaluationError):
                module.verify(TX, CASE, REQUEST, EXECUTION)


if __name__ == "__main__":
    unittest.main()

import datetime as dt
import json
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_critical_fixture as critical
import critical_fixture_worker as worker
import verify_e2e as gate


class CriticalFixtureTests(unittest.TestCase):
    def test_publish_requires_existing_v1_and_closes_it_at_one_future_cutoff(self):
        ctx = mock.Mock()
        with mock.patch.object(gate, "sql_scalar", side_effect=["0", "4", "4|4"]) as sql:
            cutoff = critical._publish(ctx)
        command = ctx.execute.call_args.args[0]
        self.assertIn("--finguardops.rule-v2-local-publication.enabled=true", command)
        self.assertIn("--finguardops.rule-v2-local-publication.confirmation=PUBLISH_RULE_V2_LOCAL", command)
        self.assertIn("--finguardops.rule-v2-local-publication.effective-from=" + cutoff, command)
        self.assertIn("FINGUARDOPS_KAFKA_ENABLED=false", command)
        self.assertEqual(sql.call_count, 3)
        self.assertGreater(dt.datetime.fromisoformat(cutoff.replace("Z", "+00:00")),
                           dt.datetime.now(dt.timezone.utc))

    def test_publish_refuses_existing_v2_without_mutation(self):
        ctx = mock.Mock()
        with mock.patch.object(gate, "sql_scalar", return_value="4"):
            with self.assertRaisesRegex(gate.VerificationError, "CRITICAL_V2_ALREADY_PUBLISHED"):
                critical._publish(ctx)
        ctx.execute.assert_not_called()

    def test_create_requires_four_distinct_events_and_stored_v2_result(self):
        ctx = mock.Mock()
        ctx.repo = Path(__file__).resolve().parents[3]
        case_id = "00000000-0000-4000-8000-000000000001"
        transaction_id = "00000000-0000-4000-8000-000000000002"
        ctx.execute.return_value = json.dumps({"transactionId": transaction_id,
            "caseId": case_id, "riskScore": 85, "riskLevel": "CRITICAL",
            "transactionStatus": "HELD"}).encode()
        cutoff = critical._stamp(dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=1))
        with (mock.patch.object(gate, "sql_scalar", side_effect=[
            "4", "85|CRITICAL|scoring-policy-v2|rule-v2|4|4|4|2|2"]),
              mock.patch.object(gate, "read_secret", return_value="dummy-secret")):
            result = critical._create(ctx, cutoff)
        command = ctx.execute.call_args.args[0]
        self.assertEqual(command[:4], ["exec", "-T", "external-risk-mock", "python"])
        self.assertNotIn("dummy-secret", str(command))
        self.assertEqual(result["caseId"], case_id)

    def test_worker_connects_four_facts_to_one_held_transaction(self):
        seen = []

        def intake(path, body, **kwargs):
            if path.endswith("/token"):
                return {"access_token": "dummy-token"}
            seen.append((path, body, kwargs))
            if path.endswith("behavior-events"):
                return {"eventId": body["eventId"]}
            return {"transactionId": body["transactionId"], "processingStatus": "HELD",
                    "riskLevel": "CRITICAL", "riskResponseOutcome": "HELD",
                    "caseId": "00000000-0000-4000-8000-000000000001"}

        with mock.patch.object(worker, "request", side_effect=intake):
            result = worker.create({"transaction": "dummy-secret", "behavior": "other-secret"})
        self.assertEqual([item[1]["eventType"] for item in seen[:4]], [
            "DEVICE_REGISTERED", "PASSWORD_CHANGED", "TRANSFER_LIMIT_CHANGED",
            "BENEFICIARY_REGISTERED"])
        self.assertEqual(len({item[1]["eventId"] for item in seen[:4]}), 4)
        self.assertEqual(seen[4][1]["deviceRef"], seen[0][1]["deviceRef"])
        self.assertEqual(seen[4][1]["recipientAccountRef"], seen[3][1]["beneficiaryRef"])
        self.assertEqual(result["riskScore"], 85)


if __name__ == "__main__":
    unittest.main()

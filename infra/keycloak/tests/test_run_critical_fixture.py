import datetime as dt
import json
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import critical_fixture_worker as worker
import run_critical_fixture as critical
import verify_e2e as gate


class CriticalFixtureTests(unittest.TestCase):
    def test_publish_requires_existing_v1_and_closes_it_at_one_future_cutoff(self):
        ctx = mock.Mock()
        with mock.patch.object(
            gate, "sql_scalar", side_effect=["0", "4", "4|4"]
        ) as sql:
            cutoff = critical._publish(ctx)
        command = ctx.execute.call_args.args[0]
        self.assertIn("--finguardops.rule-v2-local-publication.enabled=true", command)
        self.assertIn(
            "--finguardops.rule-v2-local-publication.confirmation=PUBLISH_RULE_V2_LOCAL",
            command,
        )
        self.assertIn(
            "--finguardops.rule-v2-local-publication.effective-from=" + cutoff, command
        )
        self.assertIn("FINGUARDOPS_KAFKA_ENABLED=false", command)
        self.assertEqual(sql.call_count, 3)
        self.assertGreater(
            dt.datetime.fromisoformat(cutoff.replace("Z", "+00:00")),
            dt.datetime.now(dt.timezone.utc),
        )

    def test_publish_refuses_existing_v2_without_mutation(self):
        ctx = mock.Mock()
        with mock.patch.object(gate, "sql_scalar", return_value="4"):
            with self.assertRaisesRegex(
                gate.VerificationError, "CRITICAL_V2_ALREADY_PUBLISHED"
            ):
                critical._publish(ctx)
        ctx.execute.assert_not_called()

    def test_create_requires_adopted_rule_ml_result_and_separate_failure(self):
        ctx = mock.Mock()
        ctx.repo = Path(__file__).resolve().parents[3]
        case_id = "00000000-0000-4000-8000-000000000001"
        transaction_id = "00000000-0000-4000-8000-000000000002"
        detection_id = "00000000-0000-4000-8000-000000000003"
        failed_id = "00000000-0000-4000-8000-000000000004"
        model_hash = "42344d398008babdd6a0404c750b24f1f27f1260aeac65851195d81500a876af"
        ctx.execute.return_value = json.dumps(
            {
                "transactionId": transaction_id,
                "caseId": case_id,
                "detectionResultId": detection_id,
                "failedTransactionId": failed_id,
                "riskScore": 100,
                "riskLevel": "CRITICAL",
                "transactionStatus": "HELD",
            }
        ).encode()
        cutoff = critical._stamp(
            dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=1)
        )
        with (
            mock.patch.object(
                gate,
                "sql_scalar",
                side_effect=[
                    "4",
                    json.dumps(
                        {
                            "id": detection_id,
                            "status": "HELD",
                            "response": "HELD",
                            "rule": 85,
                            "ml": 35,
                            "score": 100,
                            "level": "CRITICAL",
                            "policy": "rule-ml-policy-v1",
                            "feature": "fraud-feature-v1",
                            "model": "fraud-logistic-v2",
                            "hash": model_hash,
                            "cutoff": "2026-10-10T01:00:00Z",
                            "cutoffEqual": True,
                            "rules": 4,
                            "mlEvidence": 1,
                            "cases": 1,
                            "audit": 4,
                            "events": 12,
                        }
                    ),
                    json.dumps(
                        {
                            "status": "FAILED",
                            "adopted": None,
                            "response": None,
                            "analysis": "FAILED",
                            "code": "ML_EVENT_LIMIT_EXCEEDED",
                            "score": None,
                            "evidence": 0,
                            "cases": 0,
                            "audit": 0,
                        }
                    ),
                ],
            ),
            mock.patch.object(gate, "read_secret", return_value="dummy-secret"),
            mock.patch.object(
                gate, "dependency_hit_counts", side_effect=[(0, 0), (2, 2)]
            ),
            mock.patch.object(
                gate,
                "service_logs",
                side_effect=["", '"POST /api/v1/ml-inference HTTP/1.1" 200 OK'],
            ),
        ):
            result = critical._create(ctx, cutoff)
        command = ctx.execute.call_args.args[0]
        self.assertEqual(command[:4], ["exec", "-T", "external-risk-mock", "python"])
        self.assertNotIn("dummy-secret", str(command))
        self.assertEqual(result["caseId"], case_id)
        self.assertEqual(result["mlContribution"], 35)

    def test_worker_connects_twelve_facts_replay_and_limit_failure(self):
        seen = []

        def intake(path, body, **kwargs):
            if path.endswith("/token"):
                return {"access_token": "dummy-token"}
            seen.append((path, body, kwargs))
            if path.endswith("behavior-events"):
                return {"eventId": body["eventId"]}
            return {
                "transactionId": body["transactionId"],
                "processingStatus": "HELD",
                "riskLevel": "CRITICAL",
                "riskResponseOutcome": "HELD",
                "caseId": "00000000-0000-4000-8000-000000000001",
                "adoptedDetectionResultId": "00000000-0000-4000-8000-000000000003",
            }

        with (
            mock.patch.object(worker, "request", side_effect=intake),
            mock.patch.object(worker, "failed_request") as failed,
        ):
            result = worker.create(
                {"transaction": "dummy-secret", "behavior": "other-secret"}
            )
        self.assertEqual(len(seen), 1015)
        self.assertEqual(len({item[1]["eventId"] for item in seen[:12]}), 12)
        self.assertEqual(
            [item[1]["eventType"] for item in seen[::3][:4]],
            [
                "DEVICE_REGISTERED",
                "PASSWORD_CHANGED",
                "TRANSFER_LIMIT_CHANGED",
                "BENEFICIARY_REGISTERED",
            ],
        )
        self.assertEqual(seen[12][1], seen[13][1])
        normal_cutoff = dt.datetime.fromisoformat(
            seen[12][1]["occurredAt"].replace("Z", "+00:00")
        )
        self.assertLessEqual(normal_cutoff, dt.datetime.now(dt.timezone.utc))
        for _, body, _ in seen[:12]:
            self.assertLessEqual(
                dt.datetime.fromisoformat(body["occurredAt"].replace("Z", "+00:00")),
                normal_cutoff,
            )
        self.assertEqual(seen[12][2]["key"], seen[13][2]["key"])
        self.assertEqual(seen[12][1]["deviceRef"], seen[0][1]["deviceRef"])
        self.assertEqual(
            seen[12][1]["recipientAccountRef"], seen[9][1]["beneficiaryRef"]
        )
        self.assertEqual(result["riskScore"], 100)
        self.assertEqual(
            failed.call_args.args[1]["transactionId"], result["failedTransactionId"]
        )
        failed_cutoff = dt.datetime.fromisoformat(
            failed.call_args.args[1]["occurredAt"].replace("Z", "+00:00")
        )
        self.assertLessEqual(failed_cutoff, dt.datetime.now(dt.timezone.utc))


if __name__ == "__main__":
    unittest.main()

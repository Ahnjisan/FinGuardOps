import importlib.util
import json
import pathlib
import sys
import unittest
from types import SimpleNamespace
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location("evaluate_qwen_reports", ROOT / "evaluate_qwen_reports.py")
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)
FIXTURES = ROOT / "qwen_evaluation_fixtures.json"
DIGEST = "a" * 64


def attempt(number=1, outcome="COMPLETED", inputs=12, outputs=20):
    return {"attemptNumber": number, "provider": "OLLAMA_LOCAL", "model": DIGEST,
            "outcome": outcome, "inputTokens": inputs, "outputTokens": outputs,
            "totalTokens": None if inputs is None or outputs is None else inputs + outputs,
            "latencyMs": 45000 if outcome == "TIMEOUT" else 23000,
            "estimatedCost": None, "costCurrency": None}


def detail(status, attempts, trigger=None, source=None):
    inputs = None if any(a["inputTokens"] is None for a in attempts) else sum(a["inputTokens"] for a in attempts)
    outputs = None if any(a["outputTokens"] is None for a in attempts) else sum(a["outputTokens"] for a in attempts)
    return {"reportStatus": status, "reportSource": source, "failureCode": None,
            "fallbackUsed": status == "FALLBACK_COMPLETED",
            "fallbackTriggerCode": trigger, "inputTokens": inputs, "outputTokens": outputs,
            "totalTokens": None if inputs is None or outputs is None else inputs + outputs,
            "estimatedCost": None, "costCurrency": None}


class FixtureTests(unittest.TestCase):
    def test_v1_fixtures_and_no_reachable_critical(self):
        manifest = evaluation.load_fixtures(FIXTURES)
        self.assertEqual(len(manifest["fixtures"]), 5)
        self.assertEqual(manifest["criticalAvailability"], "UNREACHABLE_WITH_RULE_V1_MAX_75")
        self.assertEqual(max(x["expectedRiskScore"] for x in manifest["fixtures"]), 75)
        self.assertEqual(manifest["fixtures"][0]["expectedRiskLevel"], "LOW")

    def test_mutated_fixture_rejected(self):
        source = json.loads(FIXTURES.read_text(encoding="utf-8"))
        for mutation in (
            lambda d: d["fixtures"][0].update(expectedRiskLevel="HIGH"),
            lambda d: d["fixtures"][1].update(expectedReasonCodes=[]),
            lambda d: d["fixtures"][2]["events"].append("DEVICE_REGISTERED"),
            lambda d: d["fixtures"][3].update(expectedRiskScore=80),
        ):
            data = json.loads(json.dumps(source))
            mutation(data)
            with mock.patch.object(evaluation.Path, "read_text", return_value=json.dumps(data)):
                with self.assertRaises(evaluation.EvaluationError) as error:
                    evaluation.load_fixtures(FIXTURES)
                self.assertEqual(error.exception.code, "FIXTURE_INVALID")

    def test_distinct_transaction_and_event_ids_each_repetition(self):
        fixture = evaluation.load_fixtures(FIXTURES)["fixtures"][2]
        events_a, tx_a = evaluation.make_input(fixture)
        events_b, tx_b = evaluation.make_input(fixture)
        self.assertNotEqual(tx_a["transactionId"], tx_b["transactionId"])
        self.assertNotEqual(tx_a["externalCustomerRef"], tx_b["externalCustomerRef"])
        self.assertTrue(set(x["eventId"] for x in events_a).isdisjoint(x["eventId"] for x in events_b))
        self.assertNotEqual(evaluation.idempotency_key("tx"), evaluation.idempotency_key("tx"))
        self.assertNotEqual(evaluation.idempotency_key("report"), evaluation.idempotency_key("report"))

    def test_detection_checked_before_report(self):
        fixture = evaluation.load_fixtures(FIXTURES)["fixtures"][1]
        created = {"riskLevel": "HIGH", "riskResponseOutcome": "ADDITIONAL_AUTH_REQUIRED", "caseId": "case"}
        result = {"adoptedResult": {"riskLevel": "HIGH", "riskScore": 55,
                 "scoringPolicyVersion": "scoring-policy-v1", "detectionResultVersion": 1,
                 "ruleEvidence": [{"reasonCode": code} for code in fixture["expectedReasonCodes"]]}}
        self.assertEqual(evaluation.assert_detection(fixture, created, result), 1)
        result["adoptedResult"]["ruleEvidence"].pop()
        with self.assertRaises(evaluation.EvaluationError):
            evaluation.assert_detection(fixture, created, result)

    def test_smoke_selects_one_validated_fixture_through_same_evaluator(self):
        selected = []
        def record(fixture, repetition, prior_ids, digest, results):
            selected.append((fixture["id"], repetition))
        args = SimpleNamespace(fixtures=FIXTURES, model_tag="qwen3.5:4b", digest=DIGEST,
                               quantization="Q4_K_M", repetitions=1,
                               fixture_id="security_change_high")
        with mock.patch.object(evaluation, "model_identity", return_value={"digest": DIGEST}), \
             mock.patch.object(evaluation, "evaluate_one", side_effect=record):
            self.assertEqual(evaluation.evaluate(args)["status"], "COMPLETED")
        self.assertEqual(selected, [("security_change_high", 1)])
        args.fixture_id = "unknown_fixture"
        with mock.patch.object(evaluation, "model_identity", return_value={"digest": DIGEST}):
            result = evaluation.evaluate(args)
        self.assertEqual((result["status"], result["errorCode"]),
                         ("INCOMPLETE", "FIXTURE_INVALID"))


class ResultTests(unittest.TestCase):
    def test_cache_shared_and_reused_id_rejected(self):
        base = {"cacheHit": False, "executionShared": False, "reportStatus": "PENDING",
                "aiRequestId": "request-1", "executionId": "execution-1"}
        ids = set()
        evaluation.require_new_execution(base, ids)
        with self.assertRaises(evaluation.EvaluationError):
            evaluation.require_new_execution(base, ids)
        for field in ("cacheHit", "executionShared"):
            candidate = {**base, "aiRequestId": "request-2", "executionId": "execution-2", field: True}
            with self.assertRaises(evaluation.EvaluationError) as error:
                evaluation.require_new_execution(candidate, ids)
            self.assertEqual(error.exception.code, "CACHE_OR_SHARED_EXECUTION")

    def test_reason_missing_duplicate_and_prohibited_claims(self):
        report = {"reportId": "report", "executionId": "execution", "caseId": "case",
                  "initiatingAiRequestId": "request", "detectionResultVersion": 1,
                  "generatedAt": "2026-10-08T00:00:00Z", "failureCode": None,
                  "fallbackTriggerCode": None, "traceId": "test-trace",
                  "reportStatus": "COMPLETED", "reportSource": "LLM",
                  "timelineSummary": "자료 없음", "promptVersion": "prompt-1", "modelVersion": "model-1",
                  "summary": "조사 보조 초안입니다.",
                  "keyReasons": [{"reasonCode": "A", "description": "채택된 RULE을 검토하세요."},
                                 {"reasonCode": "B", "description": "채택된 RULE을 검토하세요."}],
                  "investigationChecklist": [next(iter(evaluation.CHECKLIST))]}
        self.assertTrue(all(evaluation.quality(report, ["A", "B"]).values()))
        self.assertFalse(evaluation.quality(report, ["A", "B", "C"])["reasonCodes"])
        report["keyReasons"][1]["reasonCode"] = "A"
        self.assertFalse(evaluation.quality(report, ["A", "B"])["reasonCodes"])
        report["summary"] = "고객이 실제 신규 기기를 사용했습니다."
        self.assertFalse(evaluation.quality(report, ["A", "B"])["unsupportedClaims"])
        report["investigationChecklist"] = ["고객을 제재하세요."]
        self.assertFalse(evaluation.quality(report, ["A", "B"])["checklist"])
        report["investigationChecklist"] = [{"untrusted": "shape"}]
        self.assertFalse(evaluation.quality(report, ["A", "B"])["structure"])

    def test_fallback_timeout_twice_and_timeout_then_success(self):
        timed_out = [attempt(1, "TIMEOUT", None, None), attempt(2, "TIMEOUT", None, None)]
        safe = evaluation.compare_attempts(timed_out, json.loads(json.dumps(timed_out)), DIGEST)
        evaluation.validate_outcome(detail("FALLBACK_COMPLETED", safe, "LLM_TIMEOUT", "TEMPLATE_FALLBACK"), safe, {})
        second_success = [timed_out[0], attempt(2)]
        safe = evaluation.compare_attempts(second_success, json.loads(json.dumps(second_success)), DIGEST)
        evaluation.validate_outcome(detail("COMPLETED", safe, source="LLM"), safe, {})
        self.assertIsNone(detail("COMPLETED", safe, source="LLM")["inputTokens"])

    def test_fallback_cause_must_follow_last_stored_attempt(self):
        scenarios = (
            ("TIMEOUT", "LLM_TIMEOUT"),
            ("CONNECTION_FAILED", "LLM_UNAVAILABLE"),
            ("PROVIDER_ERROR", "LLM_UNAVAILABLE"),
            ("INVALID_OUTPUT", "LLM_OUTPUT_REJECTED"),
        )
        for outcome, trigger in scenarios:
            with self.subTest(outcome=outcome):
                rows = [attempt(outcome=outcome, inputs=None, outputs=None)]
                evaluation.validate_outcome(
                    detail("FALLBACK_COMPLETED", rows, trigger, "TEMPLATE_FALLBACK"), rows, {})
                wrong = "LLM_UNAVAILABLE" if trigger != "LLM_UNAVAILABLE" else "LLM_TIMEOUT"
                with self.assertRaises(evaluation.EvaluationError):
                    evaluation.validate_outcome(
                        detail("FALLBACK_COMPLETED", rows, wrong, "TEMPLATE_FALLBACK"), rows, {})
        twice = [attempt(1, "TIMEOUT", None, None), attempt(2, "TIMEOUT", None, None)]
        with self.assertRaises(evaluation.EvaluationError):
            evaluation.validate_outcome(
                detail("FALLBACK_COMPLETED", twice, "LLM_UNAVAILABLE", "TEMPLATE_FALLBACK"), twice, {})

    def test_zero_attempt_preflight_and_completed_fields(self):
        # AI Service verify_model() may time out before /api/chat and store no attempt.
        for trigger in ("LLM_TIMEOUT", "LLM_UNAVAILABLE"):
            evaluation.validate_outcome(
                detail("FALLBACK_COMPLETED", [], trigger, "TEMPLATE_FALLBACK"), [], {})
        with self.assertRaises(evaluation.EvaluationError):
            evaluation.validate_outcome(
                detail("FALLBACK_COMPLETED", [], "LLM_OUTPUT_REJECTED", "TEMPLATE_FALLBACK"), [], {})
        completed = [attempt()]
        for field, value in (("fallbackTriggerCode", "LLM_TIMEOUT"),
                             ("failureCode", "LLM_TIMEOUT"), ("fallbackUsed", True)):
            candidate = detail("COMPLETED", completed, source="LLM")
            candidate[field] = value
            with self.subTest(field=field), self.assertRaises(evaluation.EvaluationError):
                evaluation.validate_outcome(candidate, completed, {})

    def test_failed_template_fallback_preserves_cause(self):
        rows = [attempt(outcome="TIMEOUT", inputs=None, outputs=None)]
        failed = detail("FAILED", rows, "LLM_TIMEOUT")
        failed["failureCode"] = "TEMPLATE_FALLBACK_FAILED"
        evaluation.validate_outcome(failed, rows, None)
        failed["fallbackTriggerCode"] = "LLM_UNAVAILABLE"
        with self.assertRaises(evaluation.EvaluationError):
            evaluation.validate_outcome(failed, rows, None)

    def test_backend_pre_generation_failure_has_no_stored_attempts(self):
        for code in evaluation.PRE_GENERATION_FAILURES:
            failed = detail("FAILED", [])
            failed["failureCode"] = code
            evaluation.validate_outcome(failed, [], None)
            with self.subTest(code=code), self.assertRaises(evaluation.EvaluationError):
                evaluation.validate_outcome(failed, [attempt()], None)
        failed = detail("FAILED", [])
        failed["failureCode"] = "TEMPLATE_FALLBACK_FAILED"
        with self.assertRaises(evaluation.EvaluationError):
            evaluation.validate_outcome(failed, [], None)

    def test_db_attempt_same_count_field_and_order_mismatches(self):
        reported = [attempt(1, "TIMEOUT", None, None), attempt(2)]
        safe_reported = [{key: row[key] for key in evaluation.ATTEMPT_FIELDS} for row in reported]
        self.assertEqual(evaluation.compare_persisted_attempts(
            safe_reported, json.loads(json.dumps(safe_reported)), DIGEST), 2)
        for field, value in (("outcome", "CONNECTION_FAILED"), ("latencyMs", 45001),
                             ("inputTokens", 0), ("outputTokens", 0),
                             ("attemptNumber", 2)):
            db = json.loads(json.dumps(safe_reported))
            db[0][field] = value
            with self.subTest(field=field), self.assertRaises(evaluation.EvaluationError):
                evaluation.compare_persisted_attempts(safe_reported, db, DIGEST)
        with self.assertRaises(evaluation.EvaluationError):
            evaluation.compare_persisted_attempts(safe_reported, list(reversed(safe_reported)), DIGEST)
        with self.assertRaises(evaluation.EvaluationError):
            evaluation.compare_persisted_attempts(safe_reported, safe_reported[:1], DIGEST)

    def test_aggregate_tokens_require_exact_sum_or_null(self):
        measured = [attempt(1, inputs=12, outputs=20)]
        evaluation.validate_outcome(detail("COMPLETED", measured, source="LLM"), measured, {})
        for field, value in (("inputTokens", None), ("inputTokens", 13),
                             ("outputTokens", None), ("outputTokens", 21),
                             ("totalTokens", None), ("totalTokens", 33)):
            candidate = detail("COMPLETED", measured, source="LLM")
            candidate[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(evaluation.EvaluationError):
                evaluation.validate_outcome(candidate, measured, {})
        partial = [attempt(1, "TIMEOUT", None, None), attempt(2)]
        evaluation.validate_outcome(detail("COMPLETED", partial, source="LLM"), partial, {})
        candidate = detail("COMPLETED", partial, source="LLM")
        candidate["inputTokens"] = 12
        with self.assertRaises(evaluation.EvaluationError):
            evaluation.validate_outcome(candidate, partial, {})

    def test_connection_failure_and_zero_attempt_model_unavailable(self):
        failed = [attempt(1, "CONNECTION_FAILED", None, None),
                  attempt(2, "CONNECTION_FAILED", None, None)]
        evaluation.validate_outcome(detail("FALLBACK_COMPLETED", failed, "LLM_UNAVAILABLE", "TEMPLATE_FALLBACK"), failed, {})
        evaluation.validate_outcome(detail("FALLBACK_COMPLETED", [], "LLM_UNAVAILABLE", "TEMPLATE_FALLBACK"), [], {})
        self.assertEqual(evaluation.compare_attempts([], [], DIGEST), [])
        self.assertIsNone(detail("FALLBACK_COMPLETED", [failed[0]], "LLM_UNAVAILABLE", "TEMPLATE_FALLBACK")["outputTokens"])

    def test_digest_mismatch_and_missing_model(self):
        for models in ([], [{"name": "qwen3.5:4b", "digest": "b" * 64,
                            "details": {"quantization_level": "Q4_K_M"}}]):
            response = mock.MagicMock()
            response.__enter__.return_value = response
            with mock.patch.object(evaluation.urllib.request, "urlopen", return_value=response), \
                 mock.patch.object(evaluation.json, "load", return_value={"models": models}):
                with self.assertRaises(evaluation.EvaluationError) as error:
                    evaluation.model_identity("qwen3.5:4b", DIGEST, "Q4_K_M")
                self.assertEqual(error.exception.code, "MODEL_IDENTITY_MISMATCH")

    def test_attempt_contract_and_null_cost(self):
        good = [attempt()]
        evaluation.compare_attempts(good, json.loads(json.dumps(good)), DIGEST)
        bad = json.loads(json.dumps(good))
        bad[0]["estimatedCost"] = "0"
        with self.assertRaises(evaluation.EvaluationError):
            evaluation.compare_attempts(good, bad, DIGEST)
        invalid_retry = [attempt(1, "INVALID_OUTPUT"), attempt(2)]
        with self.assertRaises(evaluation.EvaluationError):
            evaluation.validate_outcome(detail("COMPLETED", invalid_retry, source="LLM"),
                                        invalid_retry, {})


if __name__ == "__main__":
    unittest.main()

import importlib.util
import datetime as dt
from contextlib import ExitStack
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock


RUNNER = Path(__file__).resolve().parents[1] / "run.py"
spec = importlib.util.spec_from_file_location("local_critical_run", RUNNER)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class SafetyGateTests(unittest.TestCase):
    def test_verify_waits_for_published_v2_effective_time(self):
        start = dt.datetime(2026, 10, 9, tzinfo=dt.timezone.utc)
        cutoff = start + dt.timedelta(seconds=11)
        clock = [start]
        waits = []

        def sleep(seconds):
            waits.append(seconds)
            clock[0] += dt.timedelta(seconds=seconds)

        with mock.patch.object(runner, "emit") as emitted:
            runner.wait_for_rule_v2(cutoff, now=lambda: clock[0], sleep=sleep)
        self.assertEqual(waits, [5, 5, 1])
        emitted.assert_called_once()

    def test_verify_rejects_unbounded_v2_effective_time(self):
        start = dt.datetime(2026, 10, 9, tzinfo=dt.timezone.utc)
        with self.assertRaisesRegex(runner.GateError, "RULE_V2_EFFECTIVE_TIME_TOO_FAR"):
            runner.wait_for_rule_v2(start + dt.timedelta(seconds=151), now=lambda: start,
                                    sleep=lambda _seconds: self.fail("wait must not start"))

    def test_backend_pod_ignores_ready_pod_being_deleted(self):
        def pod(name, deleting=False):
            metadata = {"name": name}
            if deleting:
                metadata["deletionTimestamp"] = "2026-10-09T03:00:00Z"
            return {"metadata": metadata, "status": {"phase": "Running",
                    "containerStatuses": [{"ready": True}] * 4}}

        response = mock.Mock(stdout=json.dumps({"items": [pod("old", True), pod("current")]}))
        with mock.patch.object(runner, "kubectl", return_value=response):
            self.assertEqual(runner.backend_pod({}), "current")
        response.stdout = json.dumps({"items": [pod("one"), pod("two")]})
        with mock.patch.object(runner, "kubectl", return_value=response):
            with self.assertRaisesRegex(runner.GateError, "BACKEND_POD_NOT_READY"):
                runner.backend_pod({})

    def test_namespace_lookup_transient_error_is_not_absence(self):
        response = mock.Mock(returncode=1, stdout="", stderr="timeout")
        with mock.patch.object(runner, "kubectl", return_value=response):
            with self.assertRaisesRegex(runner.GateError, "NAMESPACE_LOOKUP_FAILED"):
                runner.assert_namespace({"runId": "12345678"}, absent=True)

    def test_namespace_create_conflict_never_applies(self):
        state = {"runId": "12345678", "cluster": "finguardops-371-12345678"}
        with (mock.patch.object(runner, "mutation_gate"),
              mock.patch.object(runner, "kubectl", side_effect=runner.GateError("STAGE_COMMAND_FAILED")) as kube,
              mock.patch.object(runner, "save") as save):
            with self.assertRaises(runner.GateError):
                runner.create_namespace(state)
            self.assertEqual(kube.call_args.args[:2], (state, "create"))
            save.assert_not_called()

    def test_namespace_uid_mismatch_rejected(self):
        state = {"runId": "12345678", "namespaceUid": "original"}
        payload = {"metadata": {"uid": "replacement", "labels": {
            "app.kubernetes.io/part-of": runner.OWNER, "finguardops.run-id": "12345678"}}}
        with mock.patch.object(runner, "kubectl", return_value=mock.Mock(returncode=0, stdout=json.dumps(payload))):
            with self.assertRaisesRegex(runner.GateError, "NAMESPACE_OWNERSHIP_MISMATCH"):
                runner.assert_namespace(state)

    def test_docker_context_change_rejected(self):
        with mock.patch.object(runner, "docker_identity", return_value={"context": "other", "daemonId": "id"}):
            with self.assertRaisesRegex(runner.GateError, "DOCKER_IDENTITY_MISMATCH"):
                runner.assert_docker({"docker": {"context": "default", "daemonId": "id"}})

    def test_source_hash_change_rejected(self):
        with mock.patch.object(runner, "source_fingerprint", return_value="changed"):
            with self.assertRaisesRegex(runner.GateError, "SOURCE_HASH_MISMATCH"):
                runner.assert_source({"sourceHash": "original"})

    def test_image_id_change_rejected(self):
        state = {"sourceHash": "x", "images": {"backend": "tag"},
                 "imageIds": {"backend": "sha256:original"}}
        with (mock.patch.object(runner, "assert_docker"), mock.patch.object(runner, "assert_source"),
              mock.patch.object(runner, "docker_image_id", return_value="sha256:replacement"),
              mock.patch.object(runner, "node_image_id", return_value="sha256:original")):
            with self.assertRaisesRegex(runner.GateError, "IMAGE_ID_MISMATCH"):
                runner.assert_images(state)

    def test_base_platform_digest_change_rejected(self):
        state = {"images": {}, "baseAliases": {"postgres": "local:postgres",
                 "python": "local:python"}, "baseImageIds": {
                 "postgres": {"source": "sha256:source", "platform": "sha256:platform"},
                 "python": {"source": "sha256:source", "platform": "sha256:platform"}}}
        with (mock.patch.object(runner, "assert_docker"),
              mock.patch.object(runner, "assert_source"),
              mock.patch.object(runner, "docker_image_id", return_value="sha256:source"),
              mock.patch.object(runner, "docker_platform_image_id", return_value="sha256:platform"),
              mock.patch.object(runner, "node_image_id", return_value="sha256:replaced")):
            with self.assertRaisesRegex(runner.GateError, "BASE_IMAGE_ID_MISMATCH"):
                runner.assert_images(state)

    def test_publication_job_has_required_ai_origin(self):
        state = {"runId": "12345678", "images": {"backend": "local:backend"}}
        created = mock.Mock(stdout=json.dumps({"metadata": {"name": "publish-rule-v1", "uid": "job-uid"}}))
        with (mock.patch.object(runner, "capture_lineage"),
              mock.patch.object(runner, "mutation_gate"),
              mock.patch.object(runner, "kubectl", return_value=created) as kube,
              mock.patch.object(runner, "save"), mock.patch.object(runner, "emit")):
            runner.publication_job(state, "v1", "2099-01-01T00:00:00Z")
        job = json.loads(kube.call_args_list[0].kwargs["input_text"])
        env = {entry["name"]: entry for entry in job["spec"]["template"]["spec"]["containers"][0]["env"]}
        self.assertEqual(env["FINGUARDOPS_AI_SERVICE_BASE_URL"]["value"], "http://ai-service:8000")

    def test_node_image_uses_loaded_target_digest_not_cri_config_id(self):
        image = "finguardops-backend:k8s-371-test"
        target = "sha256:" + "a" * 64
        config = "sha256:" + "b" * 64
        cri = {"status": {"id": config, "repoTags": ["docker.io/library/" + image]}}
        inspected = "docker.io/library/" + image + "\n└── application/vnd.oci.image.index.v1+json @" + target
        with mock.patch.object(runner, "run", side_effect=[mock.Mock(stdout=json.dumps(cri)),
                                                         mock.Mock(stdout=inspected)]) as command:
            self.assertEqual(runner.node_image_id({"cluster": "finguardops-371-test"}, image), target)
            self.assertEqual(command.call_count, 2)

    def test_node_image_without_cri_reference_fails_closed(self):
        with mock.patch.object(runner, "run", return_value=mock.Mock(stdout=json.dumps({
                "status": {"id": "sha256:" + "a" * 64}}))):
            with self.assertRaisesRegex(runner.GateError, "NODE_IMAGE_REFERENCE_MISSING"):
                runner.node_image_id({"cluster": "finguardops-371-test"}, "missing:tag")

    def test_node_image_uses_target_not_pinned_reference_header(self):
        pinned = "sha256:" + "a" * 64
        actual = "sha256:" + "b" * 64
        reference = "docker.io/library/postgres@" + pinned
        cri = {"status": {"id": "sha256:" + "c" * 64, "repoDigests": [reference]}}
        inspected = reference + "\n└── application/vnd.oci.image.index.v1+json @" + actual
        with mock.patch.object(runner, "run", side_effect=[mock.Mock(stdout=json.dumps(cri)),
                                                         mock.Mock(stdout=inspected)]):
            self.assertEqual(runner.node_image_id({"cluster": "finguardops-371-test"}, reference), actual)

    def test_secret_content_change_rejected_without_output(self):
        state = {"resourceUids": {"secret/local-runtime": "uid"}, "secretHash": "original"}
        with (mock.patch.object(runner, "fixed_resource_uids", return_value=state["resourceUids"]),
              mock.patch.object(runner, "secret_content_hash", return_value="changed")):
            with self.assertRaisesRegex(runner.GateError, "RESOURCE_UID_MISMATCH"):
                runner.assert_resources(state)

    def test_unexpected_pod_or_pvc_blocks_inventory(self):
        state = {"runId": "12345678", "cluster": "finguardops-371-12345678"}
        item = {"kind": "Pod", "metadata": {"name": "other", "uid": "other", "ownerReferences": []}}
        with (mock.patch.object(runner, "assert_namespace"),
              mock.patch.object(runner, "run", return_value=mock.Mock(stdout="pods\n")),
              mock.patch.object(runner, "kubectl", return_value=mock.Mock(stdout=json.dumps({"items": [item]})))):
            with self.assertRaisesRegex(runner.GateError, "UNEXPECTED_NAMESPACE_RESOURCE"):
                runner.namespace_inventory(state)
        item["kind"] = "PersistentVolumeClaim"
        with (mock.patch.object(runner, "assert_namespace"),
              mock.patch.object(runner, "run", return_value=mock.Mock(stdout="persistentvolumeclaims\n")),
              mock.patch.object(runner, "kubectl", return_value=mock.Mock(stdout=json.dumps({"items": [item]})))):
            with self.assertRaisesRegex(runner.GateError, "UNEXPECTED_NAMESPACE_RESOURCE"):
                runner.namespace_inventory(state)

    def test_other_project_namespace_blocks_cluster_disposal(self):
        payload = {"items": [{"metadata": {"name": "default"}},
                             {"metadata": {"name": "other-project"}}]}
        with mock.patch.object(runner, "kubectl", return_value=mock.Mock(stdout=json.dumps(payload))):
            with self.assertRaisesRegex(runner.GateError, "UNEXPECTED_CLUSTER_NAMESPACE"):
                runner.assert_cluster_namespaces({"cluster": "finguardops-371-12345678"})

    def test_missing_operator_disposition_never_deletes(self):
        state = {"cluster": "finguardops-371-12345678", "runId": "12345678",
                 "stage": "VERIFIED"}
        with (mock.patch.object(runner, "load", return_value=state),
              mock.patch.object(runner, "assert_cluster"), mock.patch.object(runner, "assert_namespace"),
              mock.patch.object(runner, "assert_resources"),
              mock.patch.object(runner, "DECISION", mock.Mock(is_file=lambda: False)),
              mock.patch.object(runner, "kubectl") as kube,
              mock.patch.object(runner, "run") as command):
            with self.assertRaisesRegex(runner.GateError, "OPERATOR_DISPOSITION_MISSING"):
                runner.cleanup("delete", "12345678")
            kube.assert_not_called()
            command.assert_not_called()

    def test_changed_database_inventory_rejects_recorded_decision(self):
        state = {"runId": "12345678", "namespaceUid": "namespace-uid"}
        recorded = {"tables": {"financial_transaction": {"rows": 1, "hash": "a" * 32}}}
        changed = {"tables": {"financial_transaction": {"rows": 1, "hash": "b" * 32}}}
        decision = {"runId": "12345678", "namespaceUid": "namespace-uid",
                    "decision": "delete", "inventoryHash": runner.inventory_hash(recorded)}
        with self.assertRaisesRegex(runner.GateError, "DELETION_INVENTORY_CHANGED"):
            runner.assert_disposition(state, decision, recorded, changed)

    def test_probe_replacement_is_preserved(self):
        state = {"runId": "12345678", "baseAliases": {"python": "local:python"}}
        created = mock.Mock(stdout=json.dumps({"metadata": {"uid": "original"}}))
        replaced = mock.Mock(stdout=json.dumps({"metadata": {"uid": "other"}}))
        with (mock.patch.object(runner, "mutation_gate"),
              mock.patch.object(runner, "save"),
              mock.patch.object(runner, "kubectl", side_effect=[created, replaced]) as kube,
              mock.patch.object(runner.time, "monotonic", side_effect=[0, 100])):
            with self.assertRaisesRegex(runner.GateError, "PROBE_UID_MISMATCH"):
                runner.wrong_secret_probe(state)
            self.assertEqual(kube.call_count, 2)

    def test_partial_cleanup_without_captured_node_never_deletes(self):
        state = {"runId": "12345678", "stage": "CREATING"}
        with (mock.patch.object(runner, "load", return_value=state),
              mock.patch.object(runner, "run") as command):
            with self.assertRaisesRegex(runner.GateError, "PARTIAL_CLUSTER_OWNERSHIP_UNPROVEN"):
                runner.cleanup_partial("12345678")
            command.assert_not_called()

    def test_evidence_sql_joins_adopted_result_and_versions(self):
        ids = {key: "12345678-1234-4234-8234-123456789abc" for key in
               ("transactionId", "caseId", "aiRequestId", "executionId")}
        sql = runner.evidence_sql(ids)
        self.assertIn("de.detection_result_id=ft.adopted_detection_result_id", sql)
        self.assertIn("ae.detection_result_id=dr.id", sql)
        self.assertIn("report.detection_result_version=dr.detection_result_version", sql)

    def test_other_detection_evidence_or_report_cannot_fill_counts(self):
        # The SQL counts only adopted-result rows; mixed rows yield 3 and 0.
        with self.assertRaisesRegex(runner.GateError, "DB_EVIDENCE_MISMATCH"):
            runner.validate_db_counts([1, 1, 3, 5, 0, 20, 4, 1, 1, 1, 1, 1])

    def test_preflight_rejects_low_host_memory_before_cluster_mutation(self):
        docker = mock.Mock(stdout=json.dumps({"MemTotal": 8 * 2**30, "NCPU": 12}))
        with (mock.patch.object(runner, "required_tools"),
              mock.patch.object(runner, "run", return_value=docker),
              mock.patch.object(runner, "free_memory", return_value=2 * 2**30),
              mock.patch.object(runner.shutil, "disk_usage", return_value=mock.Mock(free=100 * 2**30)),
              mock.patch.object(runner, "emit")):
            with self.assertRaisesRegex(runner.GateError, "INSUFFICIENT_CAPACITY"):
                runner.capacity_gate()

    def test_explicit_low_memory_option_warns_with_real_shortfall(self):
        docker = mock.Mock(stdout=json.dumps({"MemTotal": 8 * 2**30, "NCPU": 12}))
        with (mock.patch.object(runner, "ALLOW_LOW_HOST_MEMORY", True),
              mock.patch.object(runner, "required_tools"),
              mock.patch.object(runner, "run", return_value=docker),
              mock.patch.object(runner, "free_memory", return_value=3 * 2**30),
              mock.patch.object(runner.shutil, "disk_usage", return_value=mock.Mock(free=100 * 2**30)),
              mock.patch.object(runner, "emit") as emit):
            measured = runner.capacity_gate()
            self.assertEqual(measured["hostFreeGiB"], 3)
            emit.assert_any_call("capacity", "LOW_HOST_MEMORY_WARNING",
                                 hostFreeGiB=3.0, shortfallGiB=1.0)

    def test_low_memory_option_cannot_waive_critical_host_margin(self):
        docker = mock.Mock(stdout=json.dumps({"MemTotal": 8 * 2**30, "NCPU": 12}))
        with (mock.patch.object(runner, "ALLOW_LOW_HOST_MEMORY", True),
              mock.patch.object(runner, "required_tools"),
              mock.patch.object(runner, "run", return_value=docker),
              mock.patch.object(runner, "free_memory", return_value=int(0.5 * 2**30)),
              mock.patch.object(runner.shutil, "disk_usage", return_value=mock.Mock(free=100 * 2**30)),
              mock.patch.object(runner, "emit")):
            with self.assertRaisesRegex(runner.GateError, "HOST_MEMORY_CRITICAL"):
                runner.capacity_gate()

    def test_explicit_critical_memory_option_warns_without_waiving_other_gates(self):
        healthy = mock.Mock(stdout=json.dumps({"MemTotal": 8 * 2**30, "NCPU": 12}))
        with (mock.patch.object(runner, "ALLOW_LOW_HOST_MEMORY", True),
              mock.patch.object(runner, "ALLOW_CRITICAL_HOST_MEMORY", True),
              mock.patch.object(runner, "required_tools"),
              mock.patch.object(runner, "run", return_value=healthy),
              mock.patch.object(runner, "free_memory", return_value=int(0.5 * 2**30)),
              mock.patch.object(runner.shutil, "disk_usage", return_value=mock.Mock(free=100 * 2**30)),
              mock.patch.object(runner, "emit") as emit):
            self.assertEqual(runner.capacity_gate()["hostFreeGiB"], 0.5)
            emit.assert_any_call("capacity", "CRITICAL_HOST_MEMORY_WARNING",
                                 hostFreeGiB=0.5, shortfallGiB=0.5)
            emit.assert_any_call("capacity", "LOW_HOST_MEMORY_WARNING",
                                 hostFreeGiB=0.5, shortfallGiB=3.5)
        insufficient = mock.Mock(stdout=json.dumps({"MemTotal": 5 * 2**30, "NCPU": 12}))
        with (mock.patch.object(runner, "ALLOW_LOW_HOST_MEMORY", True),
              mock.patch.object(runner, "ALLOW_CRITICAL_HOST_MEMORY", True),
              mock.patch.object(runner, "required_tools"),
              mock.patch.object(runner, "run", return_value=insufficient),
              mock.patch.object(runner, "free_memory", return_value=int(0.5 * 2**30)),
              mock.patch.object(runner.shutil, "disk_usage", return_value=mock.Mock(free=100 * 2**30)),
              mock.patch.object(runner, "emit")):
            with self.assertRaisesRegex(runner.GateError, "INSUFFICIENT_CAPACITY"):
                runner.capacity_gate()

    def test_low_memory_option_keeps_docker_disk_cpu_gate(self):
        docker = mock.Mock(stdout=json.dumps({"MemTotal": 5 * 2**30, "NCPU": 12}))
        with (mock.patch.object(runner, "ALLOW_LOW_HOST_MEMORY", True),
              mock.patch.object(runner, "required_tools"),
              mock.patch.object(runner, "run", return_value=docker),
              mock.patch.object(runner, "free_memory", return_value=3 * 2**30),
              mock.patch.object(runner.shutil, "disk_usage", return_value=mock.Mock(free=100 * 2**30)),
              mock.patch.object(runner, "emit")):
            with self.assertRaisesRegex(runner.GateError, "INSUFFICIENT_CAPACITY"):
                runner.capacity_gate()

    def test_existing_unowned_namespace_is_rejected(self):
        state = {"runId": "12345678"}
        response = mock.Mock(returncode=0, stdout=json.dumps({"metadata": {"labels": {
            "app.kubernetes.io/part-of": "different-project"}}}))
        with mock.patch.object(runner, "kubectl", return_value=response):
            with self.assertRaisesRegex(runner.GateError, "NAMESPACE_OWNERSHIP_MISMATCH"):
                runner.assert_namespace(state)

    def test_preserve_never_deletes_namespace_cluster_or_image(self):
        state = {"cluster": "finguardops-371-12345678", "runId": "12345678", "stage": "VERIFIED"}
        with (mock.patch.object(runner, "load", return_value=state),
              mock.patch.object(runner, "assert_cluster"),
              mock.patch.object(runner, "assert_namespace"),
              mock.patch.object(runner, "assert_resources"),
              mock.patch.object(runner, "inspect_storage"),
              mock.patch.object(runner, "kubectl") as kube,
              mock.patch.object(runner, "run") as command,
              mock.patch.object(runner, "emit")):
            runner.cleanup("preserve", None)
            kube.assert_not_called()
            command.assert_not_called()

    def test_delete_requires_exact_run_id_and_verified_evidence(self):
        state = {"cluster": "finguardops-371-12345678", "runId": "12345678",
                 "stage": "WORKLOADS_APPLIED"}
        with (mock.patch.object(runner, "load", return_value=state),
              mock.patch.object(runner, "assert_cluster"),
              mock.patch.object(runner, "assert_namespace"),
              mock.patch.object(runner, "assert_resources"),
              mock.patch.object(runner, "inspect_storage"),
              mock.patch.object(runner, "kubectl") as kube):
            with self.assertRaisesRegex(runner.GateError, "DELETE_CONFIRMATION_OR_EVIDENCE_MISSING"):
                runner.cleanup("delete", "12345678")
            kube.assert_not_called()

    def test_cluster_storage_rejects_extra_volume_and_released_retain(self):
        state = {"resourceUids": {"pvc/postgres-data": "claim-1"}}
        claim = {"metadata": {"namespace": runner.NAMESPACE, "name": "postgres-data", "uid": "claim-1"},
                 "spec": {"volumeName": "pv-1"}}
        volume = {"metadata": {"name": "pv-1", "uid": "volume-1"}, "spec": {
            "claimRef": {"namespace": runner.NAMESPACE, "name": "postgres-data", "uid": "claim-1"},
            "persistentVolumeReclaimPolicy": "Delete", "hostPath": {"path": "/owned"}}}
        released = {"metadata": {"name": "old-pv", "uid": "volume-old"}, "spec": {
            "persistentVolumeReclaimPolicy": "Retain", "hostPath": {"path": "/old"}}}
        for partial, claims, volumes in ((False, [claim], [volume, released]),
                                         (True, [], [released]), (True, [claim], [volume])):
            with self.subTest(partial=partial, claims=len(claims), volumes=len(volumes)):
                responses = [mock.Mock(stdout=json.dumps({"items": claims})),
                             mock.Mock(stdout=json.dumps({"items": volumes}))]
                with mock.patch.object(runner, "kubectl", side_effect=responses):
                    with self.assertRaisesRegex(runner.GateError, "UNEXPECTED_CLUSTER_STORAGE"):
                        runner.cluster_storage_inventory(state, partial=partial)

    def test_forged_owner_and_unexpected_replicaset_rejected(self):
        state = {"runId": "12345678", "cluster": "finguardops-371-12345678",
                 "resourceUids": {"deployment/backend": "dep-1"}}
        cases = [
            {"kind": "Pod", "metadata": {"name": "backend-fake", "uid": "pod-1",
                "ownerReferences": [{"kind": "Secret", "name": "backend", "uid": "dep-1", "controller": True}]}},
            {"kind": "ReplicaSet", "metadata": {"name": "backend-fake", "uid": "rs-1",
                "ownerReferences": [{"kind": "Deployment", "name": "backend", "uid": "wrong", "controller": True}]}},
            {"kind": "ReplicaSet", "metadata": {"name": "backend-fake", "uid": "rs-2",
                "ownerReferences": [{"kind": "Deployment", "name": "backend", "uid": "dep-1", "controller": True}]}},
        ]
        for item in cases:
            with self.subTest(kind=item["kind"]):
                with (mock.patch.object(runner, "assert_namespace"),
                      mock.patch.object(runner, "run", return_value=mock.Mock(stdout="pods\n")),
                      mock.patch.object(runner, "kubectl", return_value=mock.Mock(
                          stdout=json.dumps({"items": [item]})))):
                    with self.assertRaisesRegex(runner.GateError, "UNEXPECTED_NAMESPACE_RESOURCE"):
                        runner.namespace_inventory(state)

    def test_mutation_gate_rejects_identity_source_image_and_secret_changes(self):
        state = {"secretHash": "original", "resourceUids": {"secret/local-runtime": "uid"}}
        for failed, error in (("assert_cluster", "DOCKER_IDENTITY_MISMATCH"),
                              ("assert_source", "SOURCE_HASH_MISMATCH"),
                              ("assert_images", "IMAGE_ID_MISMATCH"),
                              ("secret_content_hash", "SECRET_HASH_MISMATCH")):
            with self.subTest(failed=failed):
                with (mock.patch.object(runner, "assert_cluster"),
                      mock.patch.object(runner, "assert_source"),
                      mock.patch.object(runner, "assert_images"),
                      mock.patch.object(runner, "assert_namespace"),
                      mock.patch.object(runner, "namespace_inventory"),
                      mock.patch.object(runner, "secret_content_hash", return_value="original"),
                      mock.patch.object(runner, "kubectl") as kube):
                    if failed == "secret_content_hash":
                        with mock.patch.object(runner, failed, return_value="changed"):
                            with self.assertRaisesRegex(runner.GateError, error):
                                runner.mutation_gate(state)
                    else:
                        with mock.patch.object(runner, failed, side_effect=runner.GateError(error)):
                            with self.assertRaisesRegex(runner.GateError, error):
                                runner.mutation_gate(state)
                    kube.assert_not_called()

    def test_recorded_deployment_replicaset_pod_lineage(self):
        state = {"runId": "12345678", "cluster": "finguardops-371-12345678",
                 "resourceUids": {"deployment/backend": "dep-1"}}
        template = {"metadata": {"labels": {"app": "backend"}},
                    "spec": {"automountServiceAccountToken": False,
                             "containers": [{"name": "backend", "image": "local:good"}]}}
        rs_template = json.loads(json.dumps(template))
        rs_template["metadata"]["labels"]["pod-template-hash"] = "hash1"
        deployment = {"kind": "Deployment", "metadata": {"name": "backend", "uid": "dep-1"},
                      "spec": {"template": template}}
        replica = {"kind": "ReplicaSet", "metadata": {"name": "backend-hash1", "uid": "rs-1",
                   "labels": {"pod-template-hash": "hash1"}, "ownerReferences": [{
                       "kind": "Deployment", "name": "backend", "uid": "dep-1", "controller": True}]},
                   "spec": {"template": rs_template}}
        pod_spec = json.loads(json.dumps(rs_template["spec"]))
        pod_spec["nodeName"] = state["cluster"] + "-control-plane"
        pod_spec["tolerations"] = [{"effect": "NoExecute", "key": key,
                                    "operator": "Exists", "tolerationSeconds": 300}
                                   for key in ("node.kubernetes.io/not-ready",
                                               "node.kubernetes.io/unreachable")]
        pod = {"kind": "Pod", "metadata": {"name": "backend-hash1-pod1", "uid": "pod-1",
               "namespace": runner.NAMESPACE, "labels": rs_template["metadata"]["labels"],
               "ownerReferences": [{"kind": "ReplicaSet", "name": "backend-hash1",
                                    "uid": "rs-1", "controller": True}]}, "spec": pod_spec}
        with (mock.patch.object(runner, "assert_namespace"),
              mock.patch.object(runner, "run", return_value=mock.Mock(stdout="pods\n")),
              mock.patch.object(runner, "kubectl", return_value=mock.Mock(
                  stdout=json.dumps({"items": [deployment, replica, pod]}))),
              mock.patch.object(runner, "save")):
            with self.assertRaisesRegex(runner.GateError, "UNEXPECTED_NAMESPACE_RESOURCE"):
                runner.namespace_inventory(state)
            runner.namespace_inventory(state, capture=True)
            self.assertEqual(state["replicaSetUids"], {"backend-hash1": "rs-1"})
            self.assertEqual(state["podUids"], {"backend-hash1-pod1": "pod-1"})
            runner.namespace_inventory(state)

    def test_same_prefix_forged_pod_spec_and_endpoint_slice_owner_rejected(self):
        state = {"runId": "12345678", "cluster": "finguardops-371-12345678",
                 "resourceUids": {"service/backend": "service-1"}}
        template = {"metadata": {"labels": {"app": "backend", "pod-template-hash": "hash1"}},
                    "spec": {"automountServiceAccountToken": False,
                             "containers": [{"name": "backend", "image": "local:good"}]}}
        rs = {"kind": "ReplicaSet", "name": "backend-hash1", "uid": "rs-1",
              "spec": {"template": template}}
        pod = {"kind": "Pod", "name": "backend-hash1-pod1", "uid": "pod-1",
               "namespace": runner.NAMESPACE, "labels": template["metadata"]["labels"],
               "spec": {**template["spec"], "nodeName": state["cluster"] + "-control-plane",
                        "tolerations": [{"effect": "NoExecute", "key": key,
                                         "operator": "Exists", "tolerationSeconds": 300}
                                        for key in ("node.kubernetes.io/not-ready",
                                                    "node.kubernetes.io/unreachable")]}}
        runner.pod_matches_template(pod, rs, state)
        for change in ({"image": "local:other"}, {"hostNetwork": True}):
            altered = json.loads(json.dumps(pod))
            if "image" in change:
                altered["spec"]["containers"][0]["image"] = change["image"]
            else:
                altered["spec"].update(change)
            with self.assertRaisesRegex(runner.GateError, "POD_TEMPLATE_SPEC_MISMATCH"):
                runner.pod_matches_template(altered, rs, state)
        altered = json.loads(json.dumps(pod))
        altered["labels"]["other-project"] = "true"
        with self.assertRaisesRegex(runner.GateError, "POD_TEMPLATE_LABEL_MISMATCH"):
            runner.pod_matches_template(altered, rs, state)
        deployment = {"kind": "Deployment", "metadata": {"name": "backend", "uid": "dep-1"},
                      "spec": {"template": {"metadata": {"labels": {"app": "backend"}},
                                             "spec": template["spec"]}}}
        replica = {"kind": "ReplicaSet", "metadata": {"name": "backend-hash1", "uid": "rs-1",
                   "labels": {"pod-template-hash": "hash1"}, "ownerReferences": [{
                       "kind": "Deployment", "name": "backend", "uid": "dep-1", "controller": True}]},
                   "spec": {"template": template}}
        bad_spec = json.loads(json.dumps(pod["spec"]))
        bad_spec["containers"][0]["image"] = "local:other"
        forged = {"kind": "Pod", "metadata": {"name": pod["name"], "uid": pod["uid"],
                  "namespace": runner.NAMESPACE, "labels": pod["labels"],
                  "ownerReferences": [{"kind": "ReplicaSet", "name": "backend-hash1",
                                       "uid": "rs-1", "controller": True}]},
                  "spec": bad_spec}
        state["resourceUids"]["deployment/backend"] = "dep-1"
        with (mock.patch.object(runner, "assert_namespace"),
              mock.patch.object(runner, "run", return_value=mock.Mock(stdout="pods\n")),
              mock.patch.object(runner, "kubectl", return_value=mock.Mock(
                  stdout=json.dumps({"items": [deployment, replica, forged]}))),
              mock.patch.object(runner, "save")):
            with self.assertRaisesRegex(runner.GateError, "POD_TEMPLATE_SPEC_MISMATCH"):
                runner.namespace_inventory(state, capture=True)
        slice_item = {"kind": "EndpointSlice", "metadata": {"name": "backend-fake",
            "uid": "slice-1", "namespace": runner.NAMESPACE,
            "labels": {"kubernetes.io/service-name": "backend"},
            "ownerReferences": [{"kind": "Service", "name": "backend",
                                 "uid": "wrong", "controller": True}]}}
        with (mock.patch.object(runner, "assert_namespace"),
              mock.patch.object(runner, "run", return_value=mock.Mock(stdout="endpointslices.discovery.k8s.io\n")),
              mock.patch.object(runner, "kubectl", return_value=mock.Mock(
                  stdout=json.dumps({"items": [slice_item]})))):
            with self.assertRaisesRegex(runner.GateError, "UNEXPECTED_NAMESPACE_RESOURCE"):
                runner.namespace_inventory(state)

    def test_failed_or_unrecorded_stage_cannot_be_followed_by_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            with mock.patch.object(runner, "STATE", Path(directory) / "state.json"):
                runner.record_stage("12345678", "preflight", 0, "PASS")
                runner.record_stage("12345678", "prepare", 1, "BLOCKED", "SOURCE_HASH_MISMATCH")
                with self.assertRaisesRegex(runner.GateError, "EXECUTION_STAGE_ORDER_OR_FAILURE"):
                    runner.assert_stage_order("12345678", "deploy")
        with tempfile.TemporaryDirectory() as directory:
            with mock.patch.object(runner, "STATE", Path(directory) / "state.json"):
                with mock.patch.object(runner.os, "write", side_effect=OSError("disk error")):
                    with self.assertRaisesRegex(runner.GateError, "EXECUTION_EVIDENCE_WRITE_FAILED"):
                        runner.record_stage("12345678", "preflight", 0, "PASS")
                with self.assertRaisesRegex(runner.GateError, "EXECUTION_STAGE_ORDER_OR_FAILURE"):
                    runner.assert_stage_order("12345678", "prepare")
        with (mock.patch.object(runner.sys, "argv", ["run.py", "preflight", "--run-id", "12345678"]),
              mock.patch.object(runner, "assert_stage_order"),
              mock.patch.object(runner, "capacity_gate"),
              mock.patch.object(runner, "required_tools"),
              mock.patch.object(runner, "base_image_gate"),
              mock.patch.object(runner, "build_input_inventory"),
              mock.patch.object(runner, "record_stage",
                                side_effect=runner.GateError("EXECUTION_EVIDENCE_WRITE_FAILED")),
              mock.patch.object(runner, "emit") as emit):
            self.assertEqual(runner.main(), 1)
            emit.assert_any_call("preflight", "BLOCKED", code="EXECUTION_EVIDENCE_WRITE_FAILED")

    def test_invalid_v2_time_records_fixed_unexpected_error_and_blocks_successor(self):
        with tempfile.TemporaryDirectory() as directory:
            with mock.patch.object(runner, "STATE", Path(directory) / "state.json"):
                for stage in runner.STAGES[:3]:
                    runner.record_stage("12345678", stage, 0, "PASS")
                state = {"runId": "12345678", "stage": "PUBLISHED",
                         "v2EffectiveFrom": "bad-secret-payload"}
                with (mock.patch.object(runner.sys, "argv", ["run.py", "verify", "--run-id", "12345678"]),
                      mock.patch.object(runner, "load", return_value=state),
                      mock.patch.object(runner, "emit") as emitted):
                    self.assertEqual(runner.main(), 1)
                entries = runner.execution_entries("12345678")
                self.assertEqual(len(entries), 4)
                self.assertEqual(entries[-1]["stage"], "verify")
                self.assertEqual(entries[-1]["status"], "BLOCKED")
                self.assertEqual(entries[-1]["exitCode"], 1)
                self.assertEqual(entries[-1]["code"], "UNEXPECTED_STAGE_FAILURE")
                self.assertTrue(entries[-1]["utc"].endswith("Z"))
                self.assertNotIn("bad-secret-payload", runner.stage_log("12345678").read_text())
                emitted.assert_called_with("verify", "BLOCKED", code="UNEXPECTED_STAGE_FAILURE")
                with self.assertRaisesRegex(runner.GateError, "EXECUTION_STAGE_ORDER_OR_FAILURE"):
                    runner.assert_stage_order("12345678", "recover")

    def test_interrupt_and_system_exit_have_distinct_safe_evidence(self):
        for failure, code in ((KeyboardInterrupt("sensitive"), "STAGE_INTERRUPTED"),
                              (SystemExit("sensitive"), "STAGE_SYSTEM_EXIT")):
            with self.subTest(code=code), tempfile.TemporaryDirectory() as directory:
                with (mock.patch.object(runner, "STATE", Path(directory) / "state.json"),
                      mock.patch.object(runner.sys, "argv", ["run.py", "preflight", "--run-id", "12345678"]),
                      mock.patch.object(runner, "capacity_gate", side_effect=failure),
                      mock.patch.object(runner, "emit")):
                    self.assertEqual(runner.main(), 1)
                    entry = runner.execution_entries("12345678")[0]
                self.assertEqual((entry["status"], entry["exitCode"], entry["code"]),
                                 ("BLOCKED", 1, code))
                self.assertNotIn("sensitive", (Path(directory) / "execution-12345678.jsonl").read_text())

    def test_recovery_checks_are_durable_before_final_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            with mock.patch.object(runner, "STATE", Path(directory) / "state.json"):
                for stage in runner.STAGES[:4]:
                    runner.record_stage("12345678", stage, 0, "PASS")
                state = {"runId": "12345678"}
                for check in runner.RECOVERY_CHECKS[:-1]:
                    runner.record_recovery_check(state, check)
                with self.assertRaisesRegex(runner.GateError, "RECOVERY_EVIDENCE_INCOMPLETE"):
                    runner.recovery_evidence_hash("12345678")
                with self.assertRaisesRegex(runner.GateError, "EXECUTION_STAGE_ORDER_OR_FAILURE"):
                    runner.assert_stage_order("12345678", "storage")
                runner.record_recovery_check(state, runner.RECOVERY_CHECKS[-1])
                digest = runner.recovery_evidence_hash("12345678")
                runner.record_stage("12345678", "recover", 0, "PASS",
                                    safe={"recoveryChecks": len(runner.RECOVERY_CHECKS),
                                          "recoveryEvidenceSha256": digest})
                runner.assert_stage_order("12345678", "storage")
                entries = runner.execution_entries("12345678")
                checks = [entry for entry in entries if "check" in entry]
                self.assertEqual(len(checks), len(runner.RECOVERY_CHECKS))
                self.assertEqual([entry["check"] for entry in checks], list(runner.RECOVERY_CHECKS))
                self.assertTrue(all(entry["utc"].endswith("Z") and entry["exitCode"] == 0
                                    and entry["safe"] == runner.RECOVERY_RESULTS[entry["check"]]
                                    for entry in checks))

    def test_recovery_evidence_write_failure_prevents_final_pass(self):
        state = {"runId": "12345678", "stage": "VERIFIED"}
        with tempfile.TemporaryDirectory() as directory:
            with ExitStack() as stack:
                patches = {
                    "STATE": Path(directory) / "state.json", "load": mock.Mock(return_value=state),
                    "capacity_gate": mock.Mock(), "assert_cluster": mock.Mock(),
                    "assert_namespace": mock.Mock(), "assert_resources": mock.Mock(),
                    "assert_workload_images": mock.Mock(), "assert_images": mock.Mock(),
                    "namespace_inventory": mock.Mock(), "recheck": mock.Mock(return_value="baseline"),
                    "backend_pod": mock.Mock(return_value="pod-1"),
                    "kubectl": mock.Mock(return_value=mock.Mock(stdout='{"status":"VERIFIED"}')),
                    "outage": mock.Mock(), "save": mock.Mock(), "emit": mock.Mock(),
                }
                for name, replacement in patches.items():
                    stack.enter_context(mock.patch.object(runner, name, replacement))
                stack.enter_context(mock.patch.object(runner.os, "write", side_effect=OSError("sensitive")))
                with self.assertRaisesRegex(runner.GateError, "EXECUTION_EVIDENCE_WRITE_FAILED"):
                    runner.recover()
                self.assertEqual(state["stage"], "VERIFIED")
                patches["outage"].assert_not_called()
                patches["save"].assert_not_called()
                self.assertFalse(any(call.args[:2] == ("recover", "PASS")
                                     for call in patches["emit"].call_args_list))

    def test_recovery_subcheck_failure_records_blocked_not_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            with mock.patch.object(runner, "STATE", Path(directory) / "state.json"):
                for stage in runner.STAGES[:4]:
                    runner.record_stage("12345678", stage, 0, "PASS")
                with (mock.patch.object(runner.sys, "argv", ["run.py", "recover", "--run-id", "12345678"]),
                      mock.patch.object(runner, "load", return_value={"runId": "12345678"}),
                      mock.patch.object(runner, "recover",
                                        side_effect=runner.GateError("AI_OUTAGE_CONTRACT_FAILED")),
                      mock.patch.object(runner, "emit") as emitted):
                    self.assertEqual(runner.main(), 1)
                entry = runner.recorded_stages("12345678")[-1]
                self.assertEqual((entry["stage"], entry["status"], entry["exitCode"], entry["code"]),
                                 ("recover", "BLOCKED", 1, "AI_OUTAGE_CONTRACT_FAILED"))
                self.assertFalse(any(call.args[:2] == ("recover", "PASS")
                                     for call in emitted.call_args_list))
                with self.assertRaisesRegex(runner.GateError, "EXECUTION_STAGE_ORDER_OR_FAILURE"):
                    runner.assert_stage_order("12345678", "storage")

    def test_recovery_final_record_failure_does_not_emit_pass(self):
        with (mock.patch.object(runner.sys, "argv", ["run.py", "recover", "--run-id", "12345678"]),
              mock.patch.object(runner, "assert_stage_order"),
              mock.patch.object(runner, "load", return_value={"runId": "12345678",
                                                       "evidence": {"transactionId": "opaque",
                                                                    "caseId": "opaque"}}),
              mock.patch.object(runner, "recover", return_value="a" * 64),
              mock.patch.object(runner, "record_stage",
                                side_effect=runner.GateError("EXECUTION_EVIDENCE_WRITE_FAILED")),
              mock.patch.object(runner, "emit") as emitted):
            self.assertEqual(runner.main(), 1)
            emitted.assert_called_once_with("recover", "BLOCKED",
                                            code="EXECUTION_EVIDENCE_WRITE_FAILED")

    def test_build_inputs_report_possible_network_without_claiming_offline(self):
        with (mock.patch.object(runner, "run", return_value=mock.Mock(returncode=1)),
              mock.patch.object(runner, "emit") as emit):
            result = runner.build_input_inventory()
            self.assertTrue(any("ghcr.io/" in image for image in result["missing"]))
            self.assertTrue(any(call.args[0:2] == ("build-inputs", "INSPECTED")
                                and call.kwargs.get("buildNetworkAccess") == "NOT_MEASURED"
                                for call in emit.call_args_list))

    def test_manual_kind_cleanup_requires_command_success_and_audit(self):
        state = {"runId": "12345678"}
        with (mock.patch.object(runner, "load", return_value=state),
              mock.patch.object(runner, "run") as command):
            with self.assertRaisesRegex(runner.GateError, "MANUAL_KIND_CLEANUP_NOT_PROVEN"):
                runner.record_kind_cleanup(1)
            command.assert_not_called()

    def test_bad_image_patch_timeout_after_apply_reverts_and_checks_readiness(self):
        state = {"images": {"backend": "local:good"}}
        original = {"metadata": {"uid": "dep-1", "resourceVersion": "10", "generation": 5},
                    "spec": {"template": {"metadata": {"annotations": {}}, "spec": {
                        "containers": [{"name": "backend", "image": "local:good"}]}}}}
        changed = json.loads(json.dumps(original))
        changed["metadata"].update(resourceVersion="11", generation=6)
        changed["spec"]["template"]["spec"]["containers"][0]["image"] = "local:good-missing"
        current = [original]
        def kube(_state, *args, **kwargs):
            if "get" in args:
                return mock.Mock(stdout=json.dumps(current[0]))
            if "patch" in args:
                payload = json.loads(args[args.index("-p") + 1])
                if current[0] is original:
                    changed["spec"]["template"]["metadata"]["annotations"] = payload[-1]["value"]
                    current[0] = changed
                    raise runner.GateError("COMMAND_UNAVAILABLE_OR_TIMED_OUT")
                current[0] = original
                return mock.Mock(returncode=0)
            if "status" in args:
                return mock.Mock(returncode=1)
            raise AssertionError(args)
        with (mock.patch.object(runner, "mutation_gate") as gate,
              mock.patch.object(runner, "kubectl", side_effect=kube),
              mock.patch.object(runner, "capture_lineage"),
              mock.patch.object(runner, "rollout") as rollout,
              mock.patch.object(runner, "recheck", return_value="baseline"),
              mock.patch.object(runner, "record_recovery_check"),
              mock.patch.object(runner, "emit")):
            runner.bad_image_recovery(state, "baseline")
            self.assertEqual(gate.call_count, 3)
            rollout.assert_called_once_with(state, "backend")
            self.assertIs(current[0], original)

    def test_cleanup_paths_never_issue_kind_delete(self):
        code = RUNNER.read_text(encoding="utf-8")
        self.assertNotIn('"kind", "delete"', code)
        self.assertIn("cluster_storage_inventory(state, partial=True)", code)
        self.assertIn("cluster_storage_inventory(state)", code)

    def test_full_cleanup_storage_gate_blocks_namespace_delete(self):
        state = {"runId": "12345678", "stage": "VERIFIED", "namespaceUid": "ns-1"}
        disposition = {"runId": "12345678", "decision": "delete", "namespaceUid": "ns-1"}
        inventory_file = mock.Mock(is_file=lambda: True, read_text=lambda **_: json.dumps({"tables": {}}))
        parent = mock.MagicMock()
        parent.__truediv__ = mock.Mock(return_value=inventory_file)
        receipt = mock.Mock()
        receipt.parent = parent
        with (mock.patch.object(runner, "load", return_value=state),
              mock.patch.object(runner, "assert_cluster"),
              mock.patch.object(runner, "assert_namespace"),
              mock.patch.object(runner, "assert_resources"),
              mock.patch.object(runner, "DECISION", mock.Mock(
                  is_file=lambda: True, read_text=lambda **_: json.dumps(disposition))),
              mock.patch.object(runner, "STATE", receipt),
              mock.patch.object(runner, "db_counts"),
              mock.patch.object(runner, "deletion_inventory",
                                side_effect=runner.GateError("UNEXPECTED_CLUSTER_STORAGE")),
              mock.patch.object(runner, "kubectl") as kube,
              mock.patch.object(runner, "run") as command):
            with self.assertRaisesRegex(runner.GateError, "UNEXPECTED_CLUSTER_STORAGE"):
                runner.cleanup("delete", "12345678")
            kube.assert_not_called()
            command.assert_not_called()

    def test_partial_cleanup_storage_gate_blocks_namespace_delete(self):
        state = {"runId": "12345678", "stage": "NAMESPACE_CREATED",
                 "nodeContainerId": "node-1", "nodeUid": "kube-node-1", "namespaceUid": "ns-1"}
        with (mock.patch.object(runner, "load", return_value=state),
              mock.patch.object(runner, "assert_cluster"),
              mock.patch.object(runner, "assert_cluster_namespaces"),
              mock.patch.object(runner, "assert_source"),
              mock.patch.object(runner, "assert_partial_namespace_empty"),
              mock.patch.object(runner, "cluster_storage_inventory",
                                side_effect=runner.GateError("UNEXPECTED_CLUSTER_STORAGE")),
              mock.patch.object(runner, "kubectl") as kube,
              mock.patch.object(runner, "run") as command):
            with self.assertRaisesRegex(runner.GateError, "UNEXPECTED_CLUSTER_STORAGE"):
                runner.cleanup_partial("12345678")
            kube.assert_not_called()
            command.assert_not_called()

    def test_representative_deploy_recover_inventory_cleanup_mock_path(self):
        first_id = "12345678-1234-4234-8234-123456789abc"
        second_id = "22345678-1234-4234-8234-123456789abc"
        state = {"runId": "12345678", "stage": "IMAGES_LOADED", "images": {
            "backend": "local:backend", "ai": "local:ai"}, "cluster": "finguardops-371-12345678",
            "baseAliases": {"postgres": "local:postgres", "python": "local:python"},
            "evidence": {"transactionId": first_id, "caseId": first_id},
            "namespaceUid": "ns-1"}
        created_secret = mock.Mock(stdout=json.dumps({"metadata": {
            "name": "local-runtime", "uid": "secret-1"}}))
        storageclass = mock.Mock(stdout=json.dumps({"items": [{"metadata": {"name": "local-path",
            "annotations": {"storageclass.kubernetes.io/is-default-class": "true"}},
            "reclaimPolicy": "Delete"}]}))
        def deploy_kube(_state, *args, **_kwargs):
            if "storageclass" in args:
                return storageclass
            if "create" in args and "-o" in args:
                return created_secret
            return mock.Mock(returncode=0, stdout="")
        with (mock.patch.object(runner, "load", return_value=state),
              mock.patch.object(runner, "capacity_gate"),
              mock.patch.object(runner, "assert_cluster"),
              mock.patch.object(runner, "assert_images"),
              mock.patch.object(runner, "assert_namespace"),
              mock.patch.object(runner, "create_namespace"),
              mock.patch.object(runner, "namespace_inventory"),
              mock.patch.object(runner, "mutation_gate"),
              mock.patch.object(runner, "capture_lineage"),
              mock.patch.object(runner, "secret_content_hash", return_value="secret-hash"),
              mock.patch.object(runner, "code_configmaps"),
              mock.patch.object(runner, "fixed_resource_uids", return_value={"deployment/backend": "dep-1"}),
              mock.patch.object(runner, "publication_job") as publication,
              mock.patch.object(runner, "kubectl", side_effect=deploy_kube),
              mock.patch.object(runner, "save"), mock.patch.object(runner, "emit")):
            runner.deploy()
            self.assertEqual(state["stage"], "PUBLISHED")
            self.assertEqual([call.args[1] for call in publication.call_args_list], ["v1", "v2"])
        state["stage"] = "VERIFIED"
        prepared = {"status": "PREPARED", "transactionId": second_id, "caseId": second_id}
        def recover_kube(_state, *args, **_kwargs):
            if any("verify_rule_v2_negative.py" in str(arg) for arg in args):
                return mock.Mock(stdout=json.dumps({"status": "VERIFIED"}))
            if any("verify_rule_v2_ai_outage.py" in str(arg) for arg in args) and "prepare" in args:
                return mock.Mock(stdout=json.dumps(prepared))
            if any("verify_rule_v2_ai_outage.py" in str(arg) for arg in args) and "verify" in args:
                return mock.Mock(stdout=json.dumps({**prepared, "status": "VERIFIED",
                                                    "reportRequestStatus": 503}))
            return mock.Mock(returncode=0)
        def fake_outage(_state, workload, _service, during=None):
            if workload == "ai-service":
                self.assertIsNotNone(during)
                during()
        with ExitStack() as stack:
            for name in ("capacity_gate", "assert_cluster", "assert_namespace", "assert_resources",
                         "assert_workload_images", "assert_images", "namespace_inventory",
                         "mutation_gate", "wrong_secret_probe", "rollout", "save", "emit"):
                stack.enter_context(mock.patch.object(runner, name))
            stack.enter_context(mock.patch.object(runner, "load", return_value=state))
            stack.enter_context(mock.patch.object(runner, "recheck", return_value="baseline"))
            stack.enter_context(mock.patch.object(runner, "backend_pod", return_value="pod-1"))
            stack.enter_context(mock.patch.object(runner, "ready_endpoints", return_value=1))
            stack.enter_context(mock.patch.object(runner, "recovery_evidence_hash", return_value="a" * 64))
            stack.enter_context(mock.patch.object(runner, "kubectl", side_effect=recover_kube))
            outage = stack.enter_context(mock.patch.object(runner, "outage", side_effect=fake_outage))
            image_recovery = stack.enter_context(mock.patch.object(runner, "bad_image_recovery"))
            checks = stack.enter_context(mock.patch.object(runner, "record_recovery_check"))
            self.assertEqual(runner.recover(), "a" * 64)
            self.assertEqual(state["stage"], "RECOVERED")
            self.assertEqual(outage.call_count, 3)
            image_recovery.assert_called_once_with(state, "baseline")
            self.assertEqual([call.args[1] for call in checks.call_args_list],
                             list(runner.RECOVERY_CHECKS[:-2]))
        inventory = {"tables": {"financial_transaction": {"rows": 1, "hash": "a" * 32}},
                     "resources": [], "clusterStorage": {"claims": [], "volumes": []}}
        disposition = {"runId": state["runId"], "decision": "delete", "namespaceUid": "ns-1",
                       "inventoryHash": runner.inventory_hash(inventory)}
        inventory_file = mock.Mock(is_file=lambda: True,
                                   read_text=lambda **_: json.dumps(inventory))
        parent = mock.MagicMock()
        parent.__truediv__ = mock.Mock(return_value=inventory_file)
        receipt = mock.Mock()
        receipt.parent = parent
        with (mock.patch.object(runner, "load", return_value=state),
              mock.patch.object(runner, "STATE", receipt),
              mock.patch.object(runner, "DECISION", mock.Mock(
                  is_file=lambda: True, read_text=lambda **_: json.dumps(disposition))),
              mock.patch.object(runner, "db_counts"),
              mock.patch.object(runner, "deletion_inventory", return_value=inventory),
              mock.patch.object(runner, "assert_cluster"),
              mock.patch.object(runner, "assert_namespace"),
              mock.patch.object(runner, "assert_resources"),
              mock.patch.object(runner, "assert_cluster_namespaces"),
              mock.patch.object(runner, "mutation_gate") as gate,
              mock.patch.object(runner, "kubectl", return_value=mock.Mock(returncode=0)) as kube,
              mock.patch.object(runner, "run") as command,
              mock.patch.object(runner.os, "chmod"),
              mock.patch.object(runner, "save"), mock.patch.object(runner, "emit")):
            runner.record_inventory()
            runner.cleanup("delete", state["runId"])
            self.assertEqual(state["stage"], "NAMESPACE_DELETED_CLUSTER_RETAINED")
            gate.assert_called_once_with(state)
            self.assertEqual(kube.call_count, 1)
            self.assertIn("delete", kube.call_args.args)
            command.assert_not_called()


if __name__ == "__main__":
    unittest.main()

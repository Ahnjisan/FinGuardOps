#!/usr/bin/env python3
"""Issue 371 owned kind lifecycle. Output is bounded to stages, IDs and counts."""

import argparse
import ctypes
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import time
import uuid


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
STATE = HERE / ".local" / "state.json"
DECISION = HERE / ".local" / "disposition.json"
OWNER = "finguardops-371"
NAMESPACE = "finguardops-371-local"
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
BASE_IMAGES = (
    "postgres@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94",
    "python@sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7",
)
BASE_SERVICES = ("postgres", "python")
RUN_ID_RE = re.compile(r"^[0-9a-f]{8}$")
STAGES = ("preflight", "prepare", "deploy", "verify", "recover", "storage",
          "inventory", "cleanup", "kind-cleanup-preflight", "record-kind-cleanup",
          "cleanup-partial")
RECOVERY_CHECKS = (
    "jwt-negative", "wrong-secret", "ai-outage-case-prepared",
    "ai-outage-503-business-unchanged", "ai-service-outage-data-unchanged",
    "backend-outage-data-unchanged", "postgres-outage-data-unchanged",
    "backend-restart-data-unchanged", "ai-service-restart-data-unchanged",
    "postgresql-restart-data-unchanged", "bad-image-readiness-failed",
    "image-revert-ready-data-unchanged",
)
RECOVERY_RESULTS = {
    "jwt-negative": {"variantsRejected": 3},
    "wrong-secret": {"configRejected": True, "backendReady": True},
    "ai-outage-case-prepared": {"criticalHeldCasePrepared": True},
    "ai-outage-503-business-unchanged": {"reportRequestStatus": 503, "businessUnchanged": True},
    "ai-service-outage-data-unchanged": {"businessUnchanged": True},
    "backend-outage-data-unchanged": {"businessUnchanged": True},
    "postgres-outage-data-unchanged": {"businessUnchanged": True},
    "backend-restart-data-unchanged": {"businessUnchanged": True},
    "ai-service-restart-data-unchanged": {"businessUnchanged": True},
    "postgresql-restart-data-unchanged": {"businessUnchanged": True},
    "bad-image-readiness-failed": {"readinessFailureObserved": True},
    "image-revert-ready-data-unchanged": {"readinessRestored": True, "businessUnchanged": True},
}
ALLOW_LOW_HOST_MEMORY = False
ALLOW_CRITICAL_HOST_MEMORY = False


class GateError(Exception):
    pass


def run(argv, *, input_text=None, check=True, timeout=600):
    try:
        result = subprocess.run(argv, input=input_text, text=True, encoding="utf-8", errors="replace",
                                capture_output=True,
                                timeout=timeout, check=False, cwd=ROOT)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise GateError("COMMAND_UNAVAILABLE_OR_TIMED_OUT") from exc
    if check and result.returncode:
        # Command stderr may include credentials or fixture payloads.
        raise GateError("STAGE_COMMAND_FAILED")
    return result


def emit(stage, status, **safe):
    print(json.dumps({"stage": stage, "status": status, **safe}, separators=(",", ":")))


def required_tools(*names):
    missing = [name for name in names if shutil.which(name) is None]
    if missing:
        raise GateError("MISSING_TOOL_" + "_".join(missing).upper())


def free_memory():
    if sys.platform == "win32":
        class MemoryStatus(ctypes.Structure):
            _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong),
                        ("total", ctypes.c_ulonglong), ("available", ctypes.c_ulonglong),
                        ("page_total", ctypes.c_ulonglong), ("page_available", ctypes.c_ulonglong),
                        ("virtual_total", ctypes.c_ulonglong), ("virtual_available", ctypes.c_ulonglong),
                        ("extended", ctypes.c_ulonglong)]
        status = MemoryStatus()
        status.length = ctypes.sizeof(status)
        if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
            raise GateError("MEMORY_INVENTORY_FAILED")
        return status.available
    return int(Path("/proc/meminfo").read_text().split("MemAvailable:")[1].split()[0]) * 1024


def capacity_gate():
    required_tools("docker")
    info = json.loads(run(["docker", "info", "--format", "{{json .}}"], timeout=30).stdout)
    host_free = free_memory()
    disk_free = shutil.disk_usage(HERE).free
    docker_mem = int(info["MemTotal"])
    cpus = int(info["NCPU"])
    measured = {"hostFreeGiB": round(host_free / 2**30, 2),
                "dockerGiB": round(docker_mem / 2**30, 2),
                "diskFreeGiB": round(disk_free / 2**30, 2), "dockerCPUs": cpus}
    emit("capacity", "MEASURED", **measured)
    # The critical override is explicit and per invocation; other gates remain.
    if host_free < 1 * 2**30:
        if not ALLOW_CRITICAL_HOST_MEMORY:
            raise GateError("HOST_MEMORY_CRITICAL")
        emit("capacity", "CRITICAL_HOST_MEMORY_WARNING",
             hostFreeGiB=measured["hostFreeGiB"],
             shortfallGiB=round((1 * 2**30 - host_free) / 2**30, 2))
    if docker_mem < 6 * 2**30 or disk_free < 15 * 2**30 or cpus < 4:
        raise GateError("INSUFFICIENT_CAPACITY")
    if host_free < 4 * 2**30:
        if not ALLOW_LOW_HOST_MEMORY:
            raise GateError("INSUFFICIENT_CAPACITY")
        emit("capacity", "LOW_HOST_MEMORY_WARNING",
             hostFreeGiB=measured["hostFreeGiB"],
             shortfallGiB=round((4 * 2**30 - host_free) / 2**30, 2))
    return measured


def base_image_gate():
    for image in BASE_IMAGES:
        if run(["docker", "image", "inspect", image], check=False, timeout=30).returncode:
            raise GateError("PINNED_BASE_IMAGE_NOT_LOCAL")
        pinned = "sha256:" + image.rsplit("@sha256:", 1)[1]
        if docker_image_id(image) != pinned:
            raise GateError("PINNED_BASE_IMAGE_ID_MISMATCH")
        docker_platform_image_id(image)


def build_input_inventory():
    """Report local FROM/COPY image availability without claiming an offline build."""
    refs = set()
    for directory in ("backend", "ai-service"):
        dockerfile = (ROOT / directory / "Dockerfile").read_text(encoding="utf-8")
        aliases = set()
        for line in dockerfile.splitlines():
            parts = line.strip().split()
            if not parts:
                continue
            if parts[0].upper() == "FROM":
                image = next((part for part in parts[1:] if not part.startswith("--")), None)
                if image and image not in aliases:
                    refs.add(image)
                if "AS" in (part.upper() for part in parts):
                    aliases.add(parts[-1])
            elif parts[0].upper() == "COPY":
                for part in parts[1:]:
                    if part.startswith("--from="):
                        image = part.removeprefix("--from=")
                        if image not in aliases and not image.isdigit():
                            refs.add(image)
    local, missing = [], []
    for image in sorted(refs):
        target = local if run(["docker", "image", "inspect", image],
                              check=False, timeout=30).returncode == 0 else missing
        target.append(image)
    emit("build-inputs", "INSPECTED", local=local, missing=missing,
         buildNetworkAccess="NOT_MEASURED", packageDownloadMayOccur=True)
    return {"local": local, "missing": missing}


def save(state):
    STATE.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd = os.open(STATE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600) if not STATE.exists() else None
    if fd is not None:
        os.close(fd)
    temp = STATE.with_suffix(".tmp")
    temp.write_text(json.dumps(state, separators=(",", ":")), encoding="utf-8")
    os.chmod(temp, 0o600)
    temp.replace(STATE)


def load():
    if not STATE.is_file():
        raise GateError("OWNERSHIP_RECEIPT_MISSING")
    state = json.loads(STATE.read_text(encoding="utf-8"))
    if state.get("owner") != OWNER or not re.fullmatch(r"finguardops-371-[0-9a-f]{8}", state.get("cluster", "")):
        raise GateError("OWNERSHIP_RECEIPT_INVALID")
    return state


def stage_log(run_id):
    if not RUN_ID_RE.fullmatch(run_id or ""):
        raise GateError("RUN_ID_INVALID")
    return STATE.parent / ("execution-" + run_id + ".jsonl")


def execution_entries(run_id):
    path = stage_log(run_id)
    if not path.exists():
        return []
    try:
        entries = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    except (OSError, ValueError) as exc:
        raise GateError("EXECUTION_RECORD_INVALID") from exc
    for entry in entries:
        if (entry.get("runId") != run_id or entry.get("stage") not in STAGES
                or entry.get("exitCode") not in (0, 1)):
            raise GateError("EXECUTION_RECORD_INVALID")
        if "check" in entry:
            if (entry["stage"] != "recover" or entry["check"] not in RECOVERY_CHECKS
                    or entry["exitCode"] != 0 or entry.get("status") != "PASS"
                    or entry.get("safe") != RECOVERY_RESULTS[entry["check"]]):
                raise GateError("EXECUTION_RECORD_INVALID")
        elif entry.get("status") not in ("PASS", "BLOCKED", "RECOVERY_ONLY"):
            raise GateError("EXECUTION_RECORD_INVALID")
        try:
            if not entry.get("utc", "").endswith("Z"):
                raise ValueError("not UTC")
            dt.datetime.fromisoformat(entry["utc"].replace("Z", "+00:00"))
        except (TypeError, ValueError) as exc:
            raise GateError("EXECUTION_RECORD_INVALID") from exc
    return entries


def recorded_stages(run_id):
    return [entry for entry in execution_entries(run_id) if "check" not in entry]


def assert_stage_order(run_id, stage):
    all_entries = execution_entries(run_id)
    entries = [entry for entry in all_entries if "check" not in entry]
    checks = [entry["check"] for entry in all_entries if "check" in entry]
    if stage == "cleanup-partial":
        return
    index = STAGES.index(stage)
    if len(entries) != index or any(entry["stage"] != STAGES[position]
                                    or entry["exitCode"] != 0 or entry["status"] != "PASS"
                                    for position, entry in enumerate(entries)):
        raise GateError("EXECUTION_STAGE_ORDER_OR_FAILURE")
    if (stage == "recover" and checks) or (index > STAGES.index("recover")
                                            and checks and tuple(checks) != RECOVERY_CHECKS):
        raise GateError("EXECUTION_STAGE_ORDER_OR_FAILURE")


def append_execution_entry(run_id, entry):
    path = stage_log(run_id)
    encoded = (json.dumps(entry, separators=(",", ":")) + "\n").encode("utf-8")
    try:
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        try:
            os.chmod(path, 0o600)
            if os.write(fd, encoded) != len(encoded):
                raise OSError("short execution evidence write")
            os.fsync(fd)
        finally:
            os.close(fd)
    except OSError as exc:
        raise GateError("EXECUTION_EVIDENCE_WRITE_FAILED") from exc


def execution_utc():
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def record_stage(run_id, stage, exit_code, status, code=None, safe=None):
    entry = {"runId": run_id, "stage": stage, "utc": execution_utc(),
             "exitCode": exit_code, "status": status}
    if code:
        entry["code"] = code
    if safe:
        entry["safe"] = safe
    append_execution_entry(run_id, entry)


def record_recovery_check(state, check):
    run_id = state["runId"]
    existing = [entry["check"] for entry in execution_entries(run_id) if "check" in entry]
    if (check not in RECOVERY_CHECKS or len(existing) >= len(RECOVERY_CHECKS)
            or check != RECOVERY_CHECKS[len(existing)]):
        raise GateError("RECOVERY_EVIDENCE_ORDER_INVALID")
    append_execution_entry(run_id, {"runId": run_id, "stage": "recover", "check": check,
                                    "utc": execution_utc(), "exitCode": 0, "status": "PASS",
                                    "safe": RECOVERY_RESULTS[check]})


def recovery_evidence_hash(run_id):
    checks = [entry for entry in execution_entries(run_id) if "check" in entry]
    if tuple(entry["check"] for entry in checks) != RECOVERY_CHECKS:
        raise GateError("RECOVERY_EVIDENCE_INCOMPLETE")
    return hashlib.sha256(json.dumps(checks, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def fingerprint(paths):
    digest = hashlib.sha256()
    for path in sorted(paths, key=lambda p: str(p)):
        digest.update(str(path.relative_to(ROOT)).replace("\\", "/").encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()


def source_fingerprint():
    sources = []
    excluded = {"build", "target", ".gradle", "__pycache__", ".pytest_cache",
                ".ruff_cache", ".venv", "venv", "out", "node_modules", "logs", "htmlcov", ".local"}
    for directory in (ROOT / "backend", ROOT / "ai-service"):
        for current, dirs, files in os.walk(directory):
            dirs[:] = [name for name in dirs if name not in excluded]
            sources.extend(Path(current) / name for name in files
                           if not name.startswith(".env") and not name.endswith(".log")
                           and name != ".coverage")
    sources.extend(p for p in (HERE / "workloads.yaml", HERE / "kind-config.yaml", HERE / "run.py"))
    for directory in (ROOT / "infra" / "local-jwt-fixture", ROOT / "infra" / "external-risk-mock",
                      ROOT / "infra" / "ollama-mock"):
        for current, dirs, files in os.walk(directory):
            dirs[:] = [name for name in dirs if name not in excluded]
            sources.extend(Path(current) / name for name in files
                           if not name.startswith(".env") and not name.endswith(".log"))
    return fingerprint(sources)


def assert_source(state):
    if state.get("sourceHash") != source_fingerprint():
        raise GateError("SOURCE_HASH_MISMATCH")


def docker_identity():
    context_name = run(["docker", "context", "show"], timeout=30).stdout.strip()
    info = json.loads(run(["docker", "info", "--format", "{{json .}}"], timeout=30).stdout)
    if not context_name or not info.get("ID"):
        raise GateError("DOCKER_IDENTITY_UNAVAILABLE")
    return {"context": context_name, "daemonId": info["ID"]}


def assert_docker(state):
    if state.get("docker") != docker_identity():
        raise GateError("DOCKER_IDENTITY_MISMATCH")


def docker_image_id(image):
    result = run(["docker", "image", "inspect", "--format", "{{.Id}}", image], timeout=30)
    image_id = result.stdout.strip()
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", image_id):
        raise GateError("IMAGE_ID_INVALID")
    return image_id


def docker_platform_image_id(image):
    result = run(["docker", "image", "inspect", "--platform", "linux/amd64",
                  "--format", "{{.Id}}", image], timeout=30)
    image_id = result.stdout.strip()
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", image_id):
        raise GateError("PLATFORM_IMAGE_ID_INVALID")
    return image_id


def node_container(state):
    result = json.loads(run(["docker", "container", "inspect", state["cluster"] + "-control-plane"],
                            timeout=30).stdout)[0]
    if (result["Config"]["Labels"].get("io.x-k8s.kind.cluster") != state["cluster"]
            or not result["State"]["Running"]):
        raise GateError("KIND_NODE_OWNERSHIP_MISMATCH")
    return result["Id"]


def node_image_id(state, image):
    result = json.loads(run(["docker", "exec", state["cluster"] + "-control-plane",
                             "crictl", "inspecti", "-o", "json", image], timeout=30).stdout)
    # Docker Buildx may identify an OCI index while CRI status.id names its
    # platform config. Compare the containerd image target for the loaded ref.
    references = result["status"].get("repoTags", []) + result["status"].get("repoDigests", [])
    if not references:
        raise GateError("NODE_IMAGE_REFERENCE_MISSING")
    inspected = run(["docker", "exec", state["cluster"] + "-control-plane", "ctr", "-n", "k8s.io",
                     "images", "inspect", references[0]], timeout=30).stdout
    # The first line is the reference, which may itself contain a pinned digest;
    # only a following descriptor line identifies containerd's actual target.
    match = next((found for line in inspected.splitlines()[1:]
                  if "application/" in line
                  for found in [re.search(r"@(?P<digest>sha256:[0-9a-f]{64})\b", line)]
                  if found), None)
    if not match:
        raise GateError("NODE_IMAGE_TARGET_DIGEST_MISSING")
    return match.group("digest")


def assert_images(state):
    assert_docker(state)
    assert_source(state)
    for service, image in state["images"].items():
        expected = state.get("imageIds", {}).get(service)
        if not expected or docker_image_id(image) != expected or node_image_id(state, image) != expected:
            raise GateError("IMAGE_ID_MISMATCH")
    for service, image in zip(BASE_SERVICES, BASE_IMAGES):
        alias = state.get("baseAliases", {}).get(service)
        expected = state.get("baseImageIds", {}).get(service, {})
        if (not alias or expected.get("source") != docker_image_id(image)
                or expected.get("source") != docker_image_id(alias)
                or expected.get("platform") != docker_platform_image_id(alias)
                or expected.get("platform") != node_image_id(state, alias)):
            raise GateError("BASE_IMAGE_ID_MISMATCH")


def mutation_gate(state, *, absent=False, backend=None, partial=False):
    """Re-read ownership immediately before a Kubernetes mutation."""
    assert_cluster(state)
    assert_source(state)
    if partial:
        for service, expected in state.get("imageIds", {}).items():
            image = state["images"][service]
            if docker_image_id(image) != expected or node_image_id(state, image) != expected:
                raise GateError("IMAGE_ID_MISMATCH")
    else:
        assert_images(state)
    assert_namespace(state, absent=absent)
    if not absent:
        for key, expected in state.get("bootstrapUids", {}).items():
            kind, name = key.split("/", 1)
            actual = json.loads(kubectl(state, "-n", NAMESPACE, "get", kind, name,
                                        "-o", "json").stdout)["metadata"].get("uid")
            if actual != expected:
                raise GateError("BOOTSTRAP_UID_MISMATCH")
        if state.get("secretHash") and secret_content_hash(state) != state["secretHash"]:
            raise GateError("SECRET_HASH_MISMATCH")
        if state.get("resourceUids"):
            assert_resources(state)
            assert_workload_images(state, backend=backend)
        namespace_inventory(state)


def fixed_resource_uids(state):
    resources = (("secret", "local-runtime"), ("configmap", "jwt-code"),
                 ("configmap", "risk-code"), ("configmap", "ollama-code"),
                 ("pvc", "postgres-data"), ("deployment", "postgresql"),
                 ("deployment", "ai-service"), ("deployment", "backend"),
                 ("service", "postgresql"), ("service", "ai-service"), ("service", "backend"))
    return {kind + "/" + name: json.loads(kubectl(state, "-n", NAMESPACE, "get", kind, name,
                                                   "-o", "json").stdout)["metadata"]["uid"]
            for kind, name in resources}


def secret_content_hash(state):
    secret = json.loads(kubectl(state, "-n", NAMESPACE, "get", "secret", "local-runtime",
                                "-o", "json").stdout)
    return hashlib.sha256(json.dumps(secret.get("data", {}), sort_keys=True).encode()).hexdigest()


def assert_resources(state):
    expected = state.get("resourceUids")
    if (not expected or fixed_resource_uids(state) != expected
            or not state.get("secretHash") or secret_content_hash(state) != state["secretHash"]):
        raise GateError("RESOURCE_UID_MISMATCH")


def assert_workload_images(state, *, backend=None):
    expected = {"postgresql": {"postgresql": state["baseAliases"]["postgres"]},
                "ai-service": {"ai-service": state["images"]["ai"]},
                "backend": {"backend": backend or state["images"]["backend"],
                            "jwt-fixture": state["baseAliases"]["python"],
                            "external-risk-mock": state["images"]["ai"],
                            "ollama-mock": state["images"]["ai"]}}
    for name, images in expected.items():
        deployment = json.loads(kubectl(state, "-n", NAMESPACE, "get", "deployment", name,
                                        "-o", "json").stdout)
        actual = {container["name"]: container["image"] for container in
                  deployment["spec"]["template"]["spec"]["containers"]}
        if actual != images:
            raise GateError("WORKLOAD_IMAGE_MISMATCH")


def context(state):
    return "kind-" + state["cluster"]


def quantity(value):
    match = re.fullmatch(r"([0-9]+)(Ki|Mi|Gi|Ti|m)?", value or "")
    if not match:
        raise GateError("NODE_CAPACITY_INVALID")
    unit = match.group(2)
    return int(match.group(1)) * {None: 1, "m": 0.001, "Ki": 2**10,
                                   "Mi": 2**20, "Gi": 2**30, "Ti": 2**40}[unit]


def kubectl(state, *args, input_text=None, check=True, timeout=600):
    return run(["kubectl", "--kubeconfig", str(HERE / ".local" / "kubeconfig"),
                "--context", context(state), *args], input_text=input_text,
               check=check, timeout=timeout)


def assert_cluster(state):
    required_tools("kind", "kubectl")
    assert_docker(state)
    clusters = run(["kind", "get", "clusters"], timeout=30).stdout.splitlines()
    if state["cluster"] not in clusters:
        raise GateError("OWNED_CLUSTER_MISSING")
    if state.get("nodeContainerId") != node_container(state):
        raise GateError("KIND_NODE_ID_MISMATCH")
    result = kubectl(state, "get", "nodes", "-o", "json", timeout=30)
    nodes = json.loads(result.stdout)["items"]
    ready = [condition["status"] for condition in nodes[0]["status"].get("conditions", [])
             if condition["type"] == "Ready"] if len(nodes) == 1 else []
    if ready != ["True"]:
        raise GateError("CLUSTER_NOT_READY")
    node = nodes[0]
    if node["metadata"].get("uid") != state.get("nodeUid"):
        raise GateError("KIND_NODE_UID_MISMATCH")
    conditions = {item["type"]: item["status"] for item in node["status"].get("conditions", [])}
    allocatable = node["status"].get("allocatable", {})
    if (conditions.get("DiskPressure") != "False"
            or conditions.get("MemoryPressure") != "False"
            or quantity(allocatable.get("cpu")) < 4
            or quantity(allocatable.get("memory")) < 5 * 2**30
            or quantity(allocatable.get("ephemeral-storage")) < 10 * 2**30):
        raise GateError("NODE_CAPACITY_OR_PRESSURE")


def assert_namespace(state, *, absent=False):
    result = kubectl(state, "get", "namespace", NAMESPACE, "-o", "json",
                     "--ignore-not-found", check=False, timeout=30)
    if absent:
        if result.returncode:
            raise GateError("NAMESPACE_LOOKUP_FAILED")
        if result.stdout.strip():
            raise GateError("NAMESPACE_ALREADY_EXISTS")
        return
    if result.returncode or not result.stdout.strip():
        raise GateError("OWNED_NAMESPACE_MISSING")
    metadata = json.loads(result.stdout)["metadata"]
    labels = metadata.get("labels", {})
    if (not state.get("namespaceUid") or metadata.get("uid") != state["namespaceUid"]
            or labels.get("app.kubernetes.io/part-of") != OWNER
            or labels.get("finguardops.run-id") != state["runId"]):
        raise GateError("NAMESPACE_OWNERSHIP_MISMATCH")


def assert_cluster_namespaces(state):
    items = json.loads(kubectl(state, "get", "namespaces", "-o", "json").stdout)["items"]
    allowed = {"default", "kube-system", "kube-public", "kube-node-lease",
               "local-path-storage", NAMESPACE}
    if {item["metadata"]["name"] for item in items} - allowed:
        raise GateError("UNEXPECTED_CLUSTER_NAMESPACE")


def create_namespace(state):
    mutation_gate(state, absent=True)
    document = {"apiVersion": "v1", "kind": "Namespace", "metadata": {"name": NAMESPACE,
        "labels": {"app.kubernetes.io/part-of": OWNER, "finguardops.run-id": state["runId"]}}}
    created = kubectl(state, "create", "-f", "-", "-o", "json", input_text=json.dumps(document))
    metadata = json.loads(created.stdout)["metadata"]
    if (metadata.get("name") != NAMESPACE or not metadata.get("uid")
            or metadata.get("labels", {}).get("finguardops.run-id") != state["runId"]):
        raise GateError("NAMESPACE_CREATE_IDENTITY_MISSING")
    state["namespaceUid"] = metadata["uid"]
    save(state)
    assert_namespace(state)


def prepare(node_image, run_id=None):
    if STATE.exists():
        raise GateError("EXISTING_RECEIPT_REQUIRES_REVIEW")
    required_tools("kind", "kubectl", "docker", "git")
    capacity_gate()
    base_image_gate()
    build_input_inventory()
    if not node_image or not re.fullmatch(r"kindest/node:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}", node_image):
        raise GateError("PINNED_KIND_NODE_IMAGE_REQUIRED")
    if run(["docker", "image", "inspect", node_image], check=False, timeout=30).returncode:
        raise GateError("KIND_NODE_IMAGE_NOT_LOCAL")
    sha = run(["git", "rev-parse", "HEAD"], timeout=10).stdout.strip()
    branch = run(["git", "branch", "--show-current"], timeout=10).stdout.strip()
    if branch != "feature/371-local-k8s-critical-e2e" or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise GateError("WRONG_SOURCE_BRANCH")
    run_id = run_id or uuid.uuid4().hex[:8]
    state = {"owner": OWNER, "runId": run_id, "cluster": OWNER + "-" + run_id,
             "namespace": NAMESPACE, "revision": sha, "stage": "CREATING", "nodeImage": node_image,
             "docker": docker_identity(), "sourceHash": source_fingerprint(),
             "images": {"backend": "finguardops-backend:k8s-371-" + run_id,
                        "ai": "finguardops-ai-service:k8s-371-" + run_id},
             "baseAliases": {service: "finguardops-" + service + ":k8s-371-" + run_id
                             for service in BASE_SERVICES}}
    clusters = run(["kind", "get", "clusters"], timeout=30).stdout.splitlines()
    if state["cluster"] in clusters:
        raise GateError("CLUSTER_ALREADY_EXISTS")
    save(state)
    run(["kind", "create", "cluster", "--name", state["cluster"],
         "--config", str(HERE / "kind-config.yaml"),
         "--image", node_image,
         "--kubeconfig", str(HERE / ".local" / "kubeconfig"), "--wait", "120s"], timeout=240)
    assert_docker(state)
    state["nodeContainerId"] = node_container(state)
    nodes = json.loads(kubectl(state, "get", "nodes", "-o", "json", timeout=30).stdout)["items"]
    if len(nodes) != 1:
        raise GateError("KIND_NODE_COUNT_MISMATCH")
    state["nodeUid"] = nodes[0]["metadata"]["uid"]
    save(state)
    assert_cluster(state)
    state["stage"] = "CLUSTER_CREATED"
    save(state)
    for service, directory in (("backend", "backend"), ("ai", "ai-service")):
        capacity_gate()
        assert_cluster(state)
        assert_source(state)
        run(["docker", "build", "-f", str(ROOT / directory / "Dockerfile"),
             "-t", state["images"][service], str(ROOT / directory)], timeout=1800)
        image_id = docker_image_id(state["images"][service])
        run(["kind", "load", "docker-image", state["images"][service],
             "--name", state["cluster"]], timeout=300)
        if node_image_id(state, state["images"][service]) != image_id:
            raise GateError("LOADED_IMAGE_ID_MISMATCH")
        state.setdefault("imageIds", {})[service] = image_id
        save(state)
        emit("image", "LOADED", service=service, image=state["images"][service])
    for service, image in zip(BASE_SERVICES, BASE_IMAGES):
        capacity_gate()
        # Docker Desktop can have a partial multiarch cache. Export the already
        # pinned local amd64 platform into a unique run alias for kind.
        alias = state["baseAliases"][service]
        if run(["docker", "image", "inspect", alias], check=False, timeout=30).returncode == 0:
            raise GateError("BASE_ALIAS_ALREADY_EXISTS")
        source_id = docker_image_id(image)
        run(["docker", "tag", image, alias], timeout=30)
        if docker_image_id(alias) != source_id:
            raise GateError("BASE_ALIAS_SOURCE_MISMATCH")
        platform_id = docker_platform_image_id(alias)
        archive = HERE / ".local" / (service + "-" + run_id + ".tar")
        run(["docker", "image", "save", "--platform", "linux/amd64", "-o",
             str(archive), alias], timeout=300)
        assert_cluster(state)
        assert_source(state)
        run(["kind", "load", "image-archive", str(archive), "--name", state["cluster"]],
            timeout=300)
        if node_image_id(state, alias) != platform_id:
            raise GateError("LOADED_BASE_IMAGE_ID_MISMATCH")
        state.setdefault("baseImageIds", {})[service] = {"source": source_id,
                                                         "platform": platform_id}
        save(state)
    assert_images(state)
    state["stage"] = "IMAGES_LOADED"
    save(state)
    emit("prepare", "PASS", cluster=state["cluster"], namespace=NAMESPACE)


def code_configmaps(state):
    fixture = ROOT / "infra" / "local-jwt-fixture"
    configs = {
        "jwt-code": {p.name: p.read_text(encoding="utf-8") for p in
                     (fixture / "fixture.py", fixture / "evaluate_qwen_reports.py",
                      fixture / "verify_rule_v2_critical.py", fixture / "verify_rule_v2_recovery.py",
                      fixture / "verify_rule_v2_negative.py", fixture / "verify_rule_v2_ai_outage.py",
                      fixture / "rule_v2_critical_fixture.json")},
        "risk-code": {"app.py": (ROOT / "infra/external-risk-mock/app.py").read_text(encoding="utf-8")},
        "ollama-code": {"app.py": (ROOT / "infra/ollama-mock/app.py").read_text(encoding="utf-8")},
    }
    for name, data in configs.items():
        mutation_gate(state)
        created = kubectl(state, "-n", NAMESPACE, "create", "-f", "-", "-o", "json",
                input_text=json.dumps({"apiVersion": "v1", "kind": "ConfigMap",
                                       "metadata": {"name": name, "namespace": NAMESPACE,
                                                    "labels": {"app.kubernetes.io/part-of": OWNER}},
                                       "data": data}))
        meta = json.loads(created.stdout)["metadata"]
        if meta.get("name") != name or not meta.get("uid"):
            raise GateError("CONFIGMAP_UID_MISSING")
        state.setdefault("bootstrapUids", {})["configmap/" + name] = meta["uid"]
        save(state)


def publication_job(state, version, cutoff):
    name = "publish-rule-" + version
    flag = "rule-v1-default-publication" if version == "v1" else "rule-v2-local-publication"
    confirm = "PUBLISH_RULE_V1_DEFAULT_V1" if version == "v1" else "PUBLISH_RULE_V2_LOCAL"
    job = {"apiVersion": "batch/v1", "kind": "Job",
           "metadata": {"name": name, "namespace": NAMESPACE,
                        "labels": {"app.kubernetes.io/part-of": OWNER}},
           "spec": {"backoffLimit": 0, "template": {"metadata": {"labels": {"app": name}},
              "spec": {"restartPolicy": "Never", "automountServiceAccountToken": False,
                       "containers": [{"name": "publisher",
                 "image": state["images"]["backend"], "imagePullPolicy": "Never",
                 "env": [{"name": "SPRING_PROFILES_ACTIVE", "value": "local," + flag},
                         {"name": "SPRING_DATASOURCE_URL", "value": "jdbc:postgresql://postgresql:5432/finguardops"},
                         {"name": "SPRING_DATASOURCE_USERNAME", "value": "finguardops"},
                         {"name": "SPRING_DATASOURCE_PASSWORD", "valueFrom": {"secretKeyRef": {"name": "local-runtime", "key": "postgres-password"}}},
                         {"name": "FINGUARDOPS_AI_SERVICE_BASE_URL", "value": "http://ai-service:8000"},
                         {"name": "FINGUARDOPS_KAFKA_ENABLED", "value": "false"},
                         {"name": "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED", "value": "false"}],
                 "args": ["--spring.main.web-application-type=none",
                          "--finguardops." + flag + ".enabled=true",
                          "--finguardops." + flag + ".confirmation=" + confirm,
                          "--finguardops." + flag + ".effective-from=" + cutoff],
                 "resources": {"requests": {"cpu": "200m", "memory": "512Mi"},
                               "limits": {"memory": "2Gi"}}}]}}}}
    capture_lineage(state)
    mutation_gate(state)
    created = kubectl(state, "-n", NAMESPACE, "create", "-f", "-", "-o", "json",
                      input_text=json.dumps(job))
    metadata = json.loads(created.stdout)["metadata"]
    if metadata.get("name") != name or not metadata.get("uid"):
        raise GateError("JOB_UID_MISSING")
    state.setdefault("jobUids", {})[name] = metadata["uid"]
    save(state)
    kubectl(state, "-n", NAMESPACE, "wait", "--for=condition=complete", "job/" + name,
            "--timeout=240s", timeout=260)
    capture_lineage(state)
    emit("publication", "PASS", version=version, effectiveFrom=cutoff)


def deploy():
    state = load()
    if state["stage"] != "IMAGES_LOADED":
        raise GateError("WRONG_LIFECYCLE_STAGE")
    capacity_gate()
    assert_cluster(state)
    assert_images(state)
    assert_namespace(state, absent=True)
    storage = kubectl(state, "get", "storageclass", "-o", "json", timeout=30)
    defaults = [item for item in json.loads(storage.stdout)["items"]
                if item["metadata"].get("annotations", {}).get("storageclass.kubernetes.io/is-default-class") == "true"]
    if len(defaults) != 1:
        raise GateError("STORAGE_CLASS_NOT_UNAMBIGUOUS")
    state["storageClass"] = defaults[0]["metadata"]["name"]
    state["reclaimPolicy"] = defaults[0].get("reclaimPolicy", "Delete")
    create_namespace(state)
    state["stage"] = "NAMESPACE_CREATED"
    save(state)
    namespace_inventory(state)
    # Create, not apply: kubectl's last-applied annotation would duplicate secret data.
    runtime_secret = {"apiVersion": "v1", "kind": "Secret", "metadata": {"name": "local-runtime", "namespace": NAMESPACE,
                      "labels": {"app.kubernetes.io/part-of": OWNER}},
                      "type": "Opaque", "stringData": {"postgres-password": secrets.token_urlsafe(32),
                                                         "external-risk-key": secrets.token_urlsafe(32)}}
    mutation_gate(state)
    created = kubectl(state, "-n", NAMESPACE, "create", "-f", "-", "-o", "json",
                      input_text=json.dumps(runtime_secret))
    meta = json.loads(created.stdout)["metadata"]
    if meta.get("name") != "local-runtime" or not meta.get("uid"):
        raise GateError("SECRET_UID_MISSING")
    state.setdefault("bootstrapUids", {})["secret/local-runtime"] = meta["uid"]
    save(state)
    state["secretHash"] = secret_content_hash(state)
    save(state)
    code_configmaps(state)
    template = (HERE / "workloads.yaml").read_text(encoding="utf-8")
    template = template.replace("__BACKEND_IMAGE__", state["images"]["backend"])
    template = template.replace("__AI_IMAGE__", state["images"]["ai"])
    template = template.replace("__POSTGRES_IMAGE__", state["baseAliases"]["postgres"])
    template = template.replace("__PYTHON_IMAGE__", state["baseAliases"]["python"])
    mutation_gate(state)
    kubectl(state, "-n", NAMESPACE, "create", "-f", "-", input_text=template)
    state["resourceUids"] = fixed_resource_uids(state)
    state["stage"] = "WORKLOADS_APPLIED"
    save(state)
    kubectl(state, "-n", NAMESPACE, "rollout", "status", "deployment/postgresql", "--timeout=180s", timeout=200)
    capture_lineage(state)
    base = dt.datetime.now(dt.timezone.utc)
    v1 = (base + dt.timedelta(minutes=2)).isoformat(timespec="seconds").replace("+00:00", "Z")
    publication_job(state, "v1", v1)
    v2 = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=2)).isoformat(timespec="seconds").replace("+00:00", "Z")
    publication_job(state, "v2", v2)
    state["v2EffectiveFrom"] = v2
    state["stage"] = "PUBLISHED"
    save(state)
    for name in ("ai-service", "backend"):
        kubectl(state, "-n", NAMESPACE, "rollout", "status", "deployment/" + name,
                "--timeout=240s", timeout=260)
        capture_lineage(state)
    emit("deploy", "PASS", namespace=NAMESPACE, storageClass=state["storageClass"],
         reclaimPolicy=state["reclaimPolicy"], v2EffectiveFrom=v2)


def backend_pod(state):
    pods = json.loads(kubectl(state, "-n", NAMESPACE, "get", "pods", "-l", "app=backend", "-o", "json").stdout)["items"]
    ready = [pod["metadata"]["name"] for pod in pods
             if not pod["metadata"].get("deletionTimestamp")
             and pod["status"].get("phase") == "Running"
             and len(pod["status"].get("containerStatuses", [])) == 4
             and all(item.get("ready") for item in pod["status"]["containerStatuses"])]
    if len(ready) != 1:
        raise GateError("BACKEND_POD_NOT_READY")
    return ready[0]


def wait_for_rule_v2(cutoff, *, now=None, sleep=None):
    now = now or (lambda: dt.datetime.now(dt.timezone.utc))
    sleep = sleep or time.sleep
    remaining = (cutoff - now()).total_seconds()
    if remaining > 150:
        raise GateError("RULE_V2_EFFECTIVE_TIME_TOO_FAR")
    deadline = time.monotonic() + 150
    while remaining > 0:
        if time.monotonic() >= deadline:
            raise GateError("RULE_V2_EFFECTIVE_WAIT_TIMED_OUT")
        sleep(min(remaining, 5))
        remaining = (cutoff - now()).total_seconds()
    emit("rule-effective", "PASS", effectiveFrom=cutoff.isoformat().replace("+00:00", "Z"))


def verify():
    state = load()
    if state["stage"] not in {"PUBLISHED", "VERIFIED", "RECOVERED"}:
        raise GateError("WRONG_LIFECYCLE_STAGE")
    cutoff = dt.datetime.fromisoformat(state["v2EffectiveFrom"].replace("Z", "+00:00"))
    wait_for_rule_v2(cutoff)
    capacity_gate()
    assert_cluster(state)
    assert_namespace(state)
    assert_resources(state)
    assert_workload_images(state)
    assert_images(state)
    pod = backend_pod(state)
    result = kubectl(state, "-n", NAMESPACE, "exec", pod, "-c", "jwt-fixture", "--",
                     "python", "-B", "/opt/local-jwt-fixture/verify_rule_v2_critical.py", timeout=420)
    try:
        evidence = json.loads(result.stdout.strip())
    except ValueError as exc:
        raise GateError("FIXTURE_RESULT_INVALID") from exc
    if evidence.get("status") != "VERIFIED" or evidence.get("riskScore") != 85 or evidence.get("riskLevel") != "CRITICAL" or evidence.get("transactionStatus") != "HELD":
        raise GateError("BUSINESS_E2E_FAILED")
    for key in ("transactionId", "caseId", "aiRequestId", "executionId"):
        if not UUID_RE.fullmatch(evidence.get(key, "")):
            raise GateError("BUSINESS_ID_INVALID")
    state["evidence"] = {key: evidence[key] for key in ("transactionId", "caseId", "aiRequestId", "executionId")}
    db_counts(state)
    state["stage"] = "VERIFIED"
    save(state)
    emit("verify", "PASS", **state["evidence"], riskScore=85, riskLevel="CRITICAL",
         transactionStatus="HELD", reportStatus=evidence["reportStatus"],
         reportSource=evidence["reportSource"], provider="SYNTHETIC_OLLAMA_MOCK",
         auditCount=evidence["auditCount"])


def evidence_sql(ids):
    if not ids or not all(UUID_RE.fullmatch(ids.get(key, "")) for key in
                          ("transactionId", "caseId", "aiRequestId", "executionId")):
        raise GateError("EVIDENCE_NOT_VERIFIED")
    tx, case, request, execution = (ids[key] for key in
                                    ("transactionId", "caseId", "aiRequestId", "executionId"))
    return f"""SELECT
      (SELECT count(*) FROM financial_transaction ft JOIN detection_result dr
       ON dr.id=ft.adopted_detection_result_id AND dr.financial_transaction_id=ft.id
       WHERE ft.transaction_id='{tx}' AND ft.processing_status='HELD'
       AND dr.risk_score=85 AND dr.risk_level='CRITICAL'),
      (SELECT count(*) FROM fraud_case fc JOIN case_transaction ct ON ct.fraud_case_id=fc.id
       JOIN financial_transaction ft ON ft.id=ct.financial_transaction_id
       WHERE fc.case_id='{case}' AND ft.transaction_id='{tx}' AND fc.case_status='IN_REVIEW'),
      (SELECT count(*) FROM detection_evidence de JOIN financial_transaction ft
       ON de.detection_result_id=ft.adopted_detection_result_id
       WHERE ft.transaction_id='{tx}'),
      (SELECT count(*) FROM audit_log WHERE case_id='{case}' OR transaction_id='{tx}'),
      (SELECT count(*) FROM ai_report_request ar JOIN ai_report_execution ae ON ae.id=ar.execution_id
       JOIN fraud_case fc ON fc.id=ar.fraud_case_id
       JOIN ai_report report ON report.id=ar.report_id AND report.execution_id=ae.id
       JOIN case_transaction ct ON ct.fraud_case_id=fc.id
       JOIN financial_transaction ft ON ft.id=ct.financial_transaction_id
       JOIN detection_result dr ON dr.id=ft.adopted_detection_result_id
       WHERE ar.ai_request_id='{request}' AND ae.execution_id='{execution}'
       AND fc.case_id='{case}' AND ft.transaction_id='{tx}'
       AND ae.fraud_case_id=fc.id AND ae.detection_result_id=dr.id
       AND ae.detection_result_version=dr.detection_result_version
       AND ar.detection_result_version=dr.detection_result_version
       AND report.fraud_case_id=fc.id AND report.detection_result_version=dr.detection_result_version),
      (SELECT count(*) FROM flyway_schema_history WHERE success=true),
      (SELECT count(*) FROM rule_version WHERE version_number=2 AND status='PUBLISHED'
       AND effective_from<=now() AND (effective_to IS NULL OR effective_to>now())),
      (SELECT count(*) FROM audit_log WHERE case_id='{case}' AND transaction_id='{tx}'
       AND action='CASE_CREATED'),
      (SELECT count(*) FROM audit_log WHERE case_id='{case}' AND transaction_id='{tx}'
       AND action='CASE_TRANSACTION_LINKED'),
      (SELECT count(*) FROM audit_log WHERE transaction_id='{tx}'
       AND action='TRANSACTION_STATUS_CHANGED'),
      (SELECT count(*) FROM audit_log WHERE case_id='{case}' AND action='CASE_STATUS_CHANGED'),
      (SELECT count(*) FROM audit_log WHERE case_id='{case}' AND action='CASE_NOTE_CREATED');"""


def db_counts(state):
    sql = evidence_sql(state.get("evidence"))
    result = kubectl(state, "-n", NAMESPACE, "exec", "deployment/postgresql", "-c", "postgresql",
                     "--", "psql", "-U", "finguardops", "-d", "finguardops", "-Atqc", sql, timeout=30)
    try:
        counts = [int(value) for value in result.stdout.strip().split("|")]
    except ValueError as exc:
        raise GateError("DB_EVIDENCE_INVALID") from exc
    validate_db_counts(counts)
    emit("database", "VERIFIED", transactionRows=counts[0], caseLinks=counts[1],
         evidenceRows=counts[2], auditRows=counts[3], reportChains=counts[4],
         flywayApplied=counts[5], activeRuleV2=counts[6])
    return counts


def validate_db_counts(counts):
    if (len(counts) != 12 or counts[0:3] != [1, 1, 4] or counts[3] < 5
            or counts[4] != 1 or counts[5] < 20 or counts[6] != 4
            or any(count < 1 for count in counts[7:])):
        raise GateError("DB_EVIDENCE_MISMATCH")


def recheck(state):
    ids = state["evidence"]
    pod = backend_pod(state)
    result = kubectl(state, "-n", NAMESPACE, "exec", pod, "-c", "jwt-fixture", "--",
                     "python", "-B", "/opt/local-jwt-fixture/verify_rule_v2_recovery.py",
                     "--transaction-id", ids["transactionId"], "--case-id", ids["caseId"],
                     "--request-id", ids["aiRequestId"], "--execution-id", ids["executionId"], timeout=60)
    try:
        observed = json.loads(result.stdout.strip())
    except ValueError as exc:
        raise GateError("RECOVERY_RESULT_INVALID") from exc
    if observed.get("status") != "VERIFIED" or any(observed.get(key) != value for key, value in ids.items()):
        raise GateError("RECOVERY_STATE_MISMATCH")
    return db_counts(state)


def rollout(state, name):
    kubectl(state, "-n", NAMESPACE, "rollout", "status", "deployment/" + name,
            "--timeout=240s", timeout=260)
    capture_lineage(state)


def ready_endpoints(state, service):
    result = kubectl(state, "-n", NAMESPACE, "get", "endpoints", service, "-o", "json", timeout=30)
    subsets = json.loads(result.stdout).get("subsets", [])
    return sum(len(item.get("addresses", [])) for item in subsets)


def wait_endpoints(state, service, expected, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if ready_endpoints(state, service) == expected:
            return
        time.sleep(2)
    raise GateError("READINESS_ENDPOINT_MISMATCH")


def outage(state, workload, service, during=None):
    capacity_gate()
    mutation_gate(state)
    try:
        mutation_gate(state)
        kubectl(state, "-n", NAMESPACE, "scale", "deployment/" + workload, "--replicas=0")
        wait_endpoints(state, service, 0)
        if workload == "postgresql":
            wait_endpoints(state, "backend", 0)
        emit("readiness-outage", "PASS", workload=workload, readyEndpoints=0)
        if during is not None:
            during()
    finally:
        mutation_gate(state)
        kubectl(state, "-n", NAMESPACE, "scale", "deployment/" + workload, "--replicas=1")
    rollout(state, workload)
    wait_endpoints(state, service, 1)


def wrong_secret_probe(state):
    name = "wrong-secret-probe-" + state["runId"]
    pod = {"apiVersion": "v1", "kind": "Pod", "metadata": {"name": name,
           "namespace": NAMESPACE, "labels": {"app.kubernetes.io/part-of": OWNER}},
           "spec": {"restartPolicy": "Never", "containers": [{"name": "probe",
              "image": state["baseAliases"]["python"], "imagePullPolicy": "Never",
              "command": ["python", "-c", "import time; time.sleep(20)"],
              "env": [{"name": "WRONG_SECRET", "valueFrom": {"secretKeyRef": {
                  "name": "local-runtime", "key": "nonexistent-key"}}}]}]}}
    mutation_gate(state)
    created = kubectl(state, "-n", NAMESPACE, "create", "-f", "-", "-o", "json",
                      input_text=json.dumps(pod))
    pod_uid = json.loads(created.stdout)["metadata"]["uid"]
    if not pod_uid:
        raise GateError("PROBE_UID_MISSING")
    state["probeUid"] = pod_uid
    save(state)
    try:
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            observed = json.loads(kubectl(state, "-n", NAMESPACE, "get", "pod", name, "-o", "json").stdout)
            statuses = observed.get("status", {}).get("containerStatuses", [])
            if statuses:
                reason = statuses[0].get("state", {}).get("waiting", {}).get("reason")
                if reason == "CreateContainerConfigError" and ready_endpoints(state, "backend") == 1:
                    emit("wrong-secret", "EXPECTED_FAILURE", workloadReadyEndpoints=1)
                    return
            time.sleep(2)
        raise GateError("WRONG_SECRET_NOT_REJECTED")
    finally:
        mutation_gate(state)
        current = json.loads(kubectl(state, "-n", NAMESPACE, "get", "pod", name, "-o", "json").stdout)
        if current["metadata"].get("uid") != pod_uid:
            raise GateError("PROBE_UID_MISMATCH")
        kubectl(state, "-n", NAMESPACE, "delete", "pod", name, "--wait=true", "--timeout=60s", timeout=75)
        state.pop("probeUid", None)
        save(state)


def recover():
    state = load()
    if state["stage"] not in {"VERIFIED", "RECOVERED"}:
        raise GateError("VERIFIED_EVIDENCE_REQUIRED")
    capacity_gate()
    assert_cluster(state)
    assert_namespace(state)
    assert_resources(state)
    assert_workload_images(state)
    assert_images(state)
    namespace_inventory(state)
    baseline = recheck(state)
    pod = backend_pod(state)
    negative = kubectl(state, "-n", NAMESPACE, "exec", pod, "-c", "jwt-fixture", "--",
                       "python", "-B", "/opt/local-jwt-fixture/verify_rule_v2_negative.py", timeout=60)
    if json.loads(negative.stdout.strip()).get("status") != "VERIFIED":
        raise GateError("JWT_NEGATIVE_FAILED")
    record_recovery_check(state, "jwt-negative")
    emit("negative-jwt", "PASS", variants=3)
    wrong_secret_probe(state)
    record_recovery_check(state, "wrong-secret")
    prepared_result = kubectl(state, "-n", NAMESPACE, "exec", pod, "-c", "jwt-fixture", "--",
                              "python", "-B", "/opt/local-jwt-fixture/verify_rule_v2_ai_outage.py",
                              "prepare", timeout=120)
    try:
        prepared = json.loads(prepared_result.stdout.strip())
    except ValueError as exc:
        raise GateError("AI_OUTAGE_PREPARATION_INVALID") from exc
    if (prepared.get("status") != "PREPARED"
            or not all(UUID_RE.fullmatch(prepared.get(key, "")) for key in ("transactionId", "caseId"))
            or prepared["transactionId"] == state["evidence"]["transactionId"]):
        raise GateError("AI_OUTAGE_PREPARATION_FAILED")
    record_recovery_check(state, "ai-outage-case-prepared")
    emit("ai-outage-case", "PREPARED", transactionId=prepared["transactionId"], caseId=prepared["caseId"])
    def ai_outage_probe():
        if ready_endpoints(state, "backend") != 1:
            raise GateError("BACKEND_UNAVAILABLE_DURING_AI_OUTAGE")
        pod = backend_pod(state)
        result = kubectl(state, "-n", NAMESPACE, "exec", "-i", pod, "-c", "jwt-fixture", "--",
                         "python", "-B", "/opt/local-jwt-fixture/verify_rule_v2_ai_outage.py",
                         "verify", input_text=json.dumps(prepared), timeout=120)
        try:
            observed = json.loads(result.stdout.strip())
        except ValueError as exc:
            raise GateError("AI_OUTAGE_RESULT_INVALID") from exc
        if (observed.get("status") != "VERIFIED" or observed.get("reportRequestStatus") != 503
                or any(observed.get(key) != prepared[key] for key in ("transactionId", "caseId"))):
            raise GateError("AI_OUTAGE_CONTRACT_FAILED")
        record_recovery_check(state, "ai-outage-503-business-unchanged")
        emit("ai-outage", "PASS", transactionId=observed["transactionId"],
             caseId=observed["caseId"], reportRequestStatus=503)

    outage(state, "ai-service", "ai-service", during=ai_outage_probe)
    if recheck(state) != baseline:
        raise GateError("AI_OUTAGE_CHANGED_COMPLETED_REPORT")
    record_recovery_check(state, "ai-service-outage-data-unchanged")
    outage(state, "backend", "backend")
    if recheck(state) != baseline:
        raise GateError("BACKEND_OUTAGE_CHANGED_DATA")
    record_recovery_check(state, "backend-outage-data-unchanged")
    outage(state, "postgresql", "postgresql")
    if recheck(state) != baseline:
        raise GateError("DB_OUTAGE_CHANGED_DATA")
    record_recovery_check(state, "postgres-outage-data-unchanged")
    for name in ("backend", "ai-service", "postgresql"):
        capacity_gate()
        mutation_gate(state)
        kubectl(state, "-n", NAMESPACE, "rollout", "restart", "deployment/" + name)
        rollout(state, name)
        if recheck(state) != baseline:
            raise GateError("RESTART_DATA_CHANGED")
        record_recovery_check(state, name + "-restart-data-unchanged")
        emit("restart", "PASS", workload=name)
    bad_image_recovery(state, baseline)
    evidence_hash = recovery_evidence_hash(state["runId"])
    state["stage"] = "RECOVERED"
    save(state)
    return evidence_hash


def bad_image_recovery(state, baseline):
    bad = state["images"]["backend"] + "-missing"
    mutation_gate(state)
    original = json.loads(kubectl(state, "-n", NAMESPACE, "get", "deployment", "backend", "-o", "json").stdout)
    template = original["spec"]["template"]
    containers = template["spec"]["containers"]
    indexes = [index for index, item in enumerate(containers) if item["name"] == "backend"]
    if len(indexes) != 1 or containers[indexes[0]]["image"] != state["images"]["backend"]:
        raise GateError("BACKEND_IMAGE_BASELINE_MISMATCH")
    marker = str(uuid.uuid4())
    annotations = dict(template["metadata"].get("annotations", {}))
    marker_key = "finguardops.io/recovery-run"
    if marker_key in annotations:
        raise GateError("RECOVERY_MARKER_ALREADY_EXISTS")
    annotations[marker_key] = marker
    image_path = "/spec/template/spec/containers/" + str(indexes[0]) + "/image"
    patch = [{"op": "test", "path": "/metadata/uid", "value": original["metadata"]["uid"]},
             {"op": "test", "path": "/metadata/resourceVersion", "value": original["metadata"]["resourceVersion"]},
             {"op": "replace", "path": image_path, "value": bad},
             {"op": "add", "path": "/spec/template/metadata/annotations", "value": annotations}]
    patch_error = None
    try:
        try:
            mutation_gate(state)
            kubectl(state, "-n", NAMESPACE, "patch", "deployment", "backend", "--type=json",
                    "-p", json.dumps(patch))
        except GateError as exc:
            patch_error = exc
        current = json.loads(kubectl(state, "-n", NAMESPACE, "get", "deployment", "backend", "-o", "json").stdout)
        changed = (current["metadata"].get("uid") == original["metadata"]["uid"]
                   and current["spec"]["template"]["spec"]["containers"][indexes[0]]["image"] == bad
                   and current["spec"]["template"]["metadata"].get("annotations", {}).get(marker_key) == marker
                   and current["metadata"].get("generation") == original["metadata"].get("generation", 0) + 1)
        if not changed:
            raise GateError("BAD_IMAGE_CHANGE_OWNERSHIP_UNPROVEN") from patch_error
        attempt = kubectl(state, "-n", NAMESPACE, "rollout", "status", "deployment/backend",
                          "--timeout=35s", timeout=50, check=False)
        if attempt.returncode == 0:
            raise GateError("BAD_IMAGE_UNEXPECTEDLY_READY")
        capture_lineage(state)
        record_recovery_check(state, "bad-image-readiness-failed")
        emit("bad-image", "EXPECTED_FAILURE")
    finally:
        current = json.loads(kubectl(state, "-n", NAMESPACE, "get", "deployment", "backend", "-o", "json").stdout)
        owned = (current["metadata"].get("uid") == original["metadata"]["uid"]
                 and current["spec"]["template"]["spec"]["containers"][indexes[0]]["image"] == bad
                 and current["spec"]["template"]["metadata"].get("annotations", {}).get(marker_key) == marker
                 and current["metadata"].get("generation") == original["metadata"].get("generation", 0) + 1)
        if not owned:
            raise GateError("BAD_IMAGE_CHANGE_OWNERSHIP_UNPROVEN")
        mutation_gate(state, backend=bad)
        marker_path = "/spec/template/metadata/annotations/" + marker_key.replace("~", "~0").replace("/", "~1")
        revert = [{"op": "test", "path": "/metadata/uid", "value": original["metadata"]["uid"]},
                  {"op": "test", "path": "/metadata/resourceVersion", "value": current["metadata"]["resourceVersion"]},
                  {"op": "test", "path": image_path, "value": bad},
                  {"op": "test", "path": marker_path, "value": marker},
                  {"op": "replace", "path": image_path, "value": state["images"]["backend"]},
                  {"op": "remove", "path": marker_path}]
        kubectl(state, "-n", NAMESPACE, "patch", "deployment", "backend", "--type=json",
                "-p", json.dumps(revert))
    rollout(state, "backend")
    if recheck(state) != baseline:
        raise GateError("IMAGE_REVERT_DATA_CHANGED")
    record_recovery_check(state, "image-revert-ready-data-unchanged")
    emit("image-revert", "PASS", image=state["images"]["backend"])

def inspect_storage():
    state = load()
    assert_cluster(state)
    assert_namespace(state)
    claim = json.loads(kubectl(state, "-n", NAMESPACE, "get", "pvc", "postgres-data", "-o", "json").stdout)
    pv_name = claim["spec"].get("volumeName")
    if not pv_name:
        raise GateError("PVC_NOT_BOUND")
    pv = json.loads(kubectl(state, "get", "pv", pv_name, "-o", "json").stdout)
    claim_ref = pv["spec"].get("claimRef", {})
    if claim_ref.get("namespace") != NAMESPACE or claim_ref.get("name") != "postgres-data" or claim_ref.get("uid") != claim["metadata"]["uid"]:
        raise GateError("PV_OWNERSHIP_MISMATCH")
    if pv["spec"].get("storageClassName") != state.get("storageClass"):
        raise GateError("PV_STORAGE_CLASS_MISMATCH")
    emit("storage", "INSPECTED", pvc=claim["metadata"]["name"], pv=pv_name,
          claimPhase=claim["status"]["phase"], reclaimPolicy=pv["spec"]["persistentVolumeReclaimPolicy"],
          namespaceDeletionMayDeleteData=True)
    return {"pvc": {"name": claim["metadata"]["name"], "uid": claim["metadata"]["uid"]},
            "pv": {"name": pv_name, "uid": pv["metadata"]["uid"],
                   "reclaimPolicy": pv["spec"]["persistentVolumeReclaimPolicy"]}}


def cluster_storage_inventory(state, *, partial=False):
    """Inventory *all* claims and volumes, including released Retain volumes."""
    claims = json.loads(kubectl(state, "get", "pvc", "--all-namespaces", "-o", "json").stdout)["items"]
    volumes = json.loads(kubectl(state, "get", "pv", "-o", "json").stdout)["items"]
    if partial:
        if claims or volumes:
            raise GateError("UNEXPECTED_CLUSTER_STORAGE")
    else:
        if len(claims) != 1 or len(volumes) != 1:
            raise GateError("UNEXPECTED_CLUSTER_STORAGE")
        claim, volume = claims[0], volumes[0]
        ref = volume["spec"].get("claimRef", {})
        if (claim["metadata"].get("namespace") != NAMESPACE
                or claim["metadata"].get("name") != "postgres-data"
                or claim["metadata"].get("uid") != state.get("resourceUids", {}).get("pvc/postgres-data")
                or claim["spec"].get("volumeName") != volume["metadata"].get("name")
                or (ref.get("namespace"), ref.get("name"), ref.get("uid")) !=
                   (NAMESPACE, "postgres-data", claim["metadata"]["uid"])):
            raise GateError("CLUSTER_STORAGE_OWNERSHIP_MISMATCH")
    return {"claims": [{"namespace": c["metadata"].get("namespace"),
                        "name": c["metadata"]["name"], "uid": c["metadata"]["uid"],
                        "volumeName": c["spec"].get("volumeName")} for c in claims],
            "volumes": [{"name": v["metadata"]["name"], "uid": v["metadata"]["uid"],
                         "claimRef": v["spec"].get("claimRef"),
                         "reclaimPolicy": v["spec"].get("persistentVolumeReclaimPolicy"),
                         "backing": {key: v["spec"][key] for key in ("hostPath", "local", "csi")
                                     if key in v["spec"]}} for v in volumes]}


def pod_matches_template(pod, controller, state):
    """Compare an observed Pod with its recorded controller's admitted template."""
    template = controller["spec"]["template"]
    expected_labels = dict(template.get("metadata", {}).get("labels", {}))
    if controller["kind"] == "Job":
        name, uid = controller["name"], controller["uid"]
        expected_labels.update({"batch.kubernetes.io/controller-uid": uid,
                                "batch.kubernetes.io/job-name": name,
                                "controller-uid": uid, "job-name": name})
    if pod["labels"] != expected_labels or pod["namespace"] != NAMESPACE:
        raise GateError("POD_TEMPLATE_LABEL_MISMATCH")
    actual = json.loads(json.dumps(pod["spec"]))
    expected = template["spec"]
    defaults = {"dnsPolicy": "ClusterFirst", "enableServiceLinks": True,
                "preemptionPolicy": "PreemptLowerPriority", "priority": 0,
                "restartPolicy": "Always", "schedulerName": "default-scheduler",
                "serviceAccount": "default", "serviceAccountName": "default",
                "terminationGracePeriodSeconds": 30}
    node_name = actual.pop("nodeName", None)
    if node_name != state["cluster"] + "-control-plane":
        raise GateError("POD_NODE_MISMATCH")
    tolerations = actual.pop("tolerations", [])
    expected_tolerations = [{"effect": "NoExecute", "key": key,
                             "operator": "Exists", "tolerationSeconds": 300}
                            for key in ("node.kubernetes.io/not-ready",
                                        "node.kubernetes.io/unreachable")]
    if sorted(tolerations, key=lambda value: value.get("key", "")) != sorted(
            expected_tolerations, key=lambda value: value["key"]):
        raise GateError("POD_TOLERATION_MISMATCH")
    for key, value in defaults.items():
        if key not in expected and actual.get(key) == value:
            actual.pop(key)
    if actual != expected:
        raise GateError("POD_TEMPLATE_SPEC_MISMATCH")


def namespace_inventory(state, *, capture=False):
    assert_namespace(state)
    resources = run(["kubectl", "--kubeconfig", str(HERE / ".local" / "kubeconfig"),
                     "--context", context(state), "api-resources", "--namespaced",
                     "--verbs=list", "-o", "name"], timeout=60).stdout.splitlines()
    observed = []
    for resource in resources:
        items = json.loads(kubectl(state, "-n", NAMESPACE, "get", resource,
                                   "-o", "json", timeout=60).stdout)["items"]
        for item in items:
            meta = item["metadata"]
            retained = {key: item[key] for key in
                        ("spec", "data", "type", "endpoints", "ports", "subsets") if key in item}
            observed.append({"resource": resource, "kind": item["kind"], "name": meta["name"],
                             "namespace": meta.get("namespace"),
                             "uid": meta["uid"], "owners": meta.get("ownerReferences", []),
                             "labels": meta.get("labels", {}),
                             "spec": item.get("spec", {}),
                             "contentHash": hashlib.sha256(json.dumps(retained, sort_keys=True).encode()).hexdigest()})
    observed.sort(key=lambda item: (item["resource"], item["name"]))
    fixed = {"PersistentVolumeClaim": {"postgres-data"},
             "Service": {"backend", "ai-service", "postgresql"},
             "Deployment": {"backend", "ai-service", "postgresql"},
             "ConfigMap": {"jwt-code", "risk-code", "ollama-code", "kube-root-ca.crt"},
             "Secret": {"local-runtime"}, "ServiceAccount": {"default"},
             "Job": {"publish-rule-v1", "publish-rule-v2"},
             "Endpoints": {"backend", "ai-service", "postgresql"}}
    deployments = {name: state.get("resourceUids", {}).get("deployment/" + name)
                   for name in fixed["Deployment"]}
    jobs = state.get("jobUids", {})
    def owned_by(item, kind, owners):
        refs = item["owners"]
        return (len(refs) == 1 and refs[0].get("kind") == kind
                and refs[0].get("controller") is True
                and refs[0].get("name") in owners
                and refs[0].get("uid") == owners[refs[0]["name"]]
                and item["name"].startswith(refs[0]["name"] + "-"))

    deployment_templates = {item["name"]: item["spec"].get("template") for item in observed
                            if item["kind"] == "Deployment"}
    approved_rs = state.get("replicaSetUids", {})
    approved_pods = state.get("podUids", {})
    controllers = {item["name"]: item for item in observed
                   if item["kind"] in {"ReplicaSet", "Job"}}
    replicas = {}
    for item in observed:
        if item["kind"] != "ReplicaSet" or not owned_by(item, "Deployment", deployments):
            continue
        if approved_rs.get(item["name"]) == item["uid"]:
            replicas[item["name"]] = item["uid"]
            continue
        if capture:
            parent = item["owners"][0]["name"]
            template = json.loads(json.dumps(item["spec"].get("template", {})))
            label_hash = item["labels"].get("pod-template-hash")
            if (not label_hash or item["name"] != parent + "-" + label_hash
                    or template.get("metadata", {}).get("labels", {}).get("pod-template-hash") != label_hash):
                raise GateError("UNEXPECTED_REPLICASET_TEMPLATE")
            template["metadata"]["labels"].pop("pod-template-hash")
            if template != deployment_templates.get(parent):
                raise GateError("UNEXPECTED_REPLICASET_TEMPLATE")
            replicas[item["name"]] = item["uid"]
    for item in observed:
        if item["kind"] in fixed and item["name"] in fixed[item["kind"]]:
            if item["kind"] == "Deployment" and item["uid"] != deployments[item["name"]]:
                raise GateError("UNEXPECTED_NAMESPACE_RESOURCE")
            if item["kind"] == "Job" and item["uid"] != jobs.get(item["name"]):
                raise GateError("UNEXPECTED_NAMESPACE_RESOURCE")
            continue
        if item["kind"] == "ReplicaSet" and replicas.get(item["name"]) == item["uid"]:
            continue
        if item["kind"] == "Pod":
            owner = item["owners"][0] if len(item["owners"]) == 1 else {}
            controlled = (owned_by(item, "ReplicaSet", replicas)
                          or owned_by(item, "Job", jobs))
            if controlled and (capture or approved_pods.get(item["name"]) == item["uid"]):
                parent = controllers.get(owner.get("name"))
                if not parent or parent["uid"] != owner.get("uid"):
                    raise GateError("POD_CONTROLLER_MISMATCH")
                pod_matches_template(item, parent, state)
                continue
            if (item["name"] == "wrong-secret-probe-" + state["runId"]
                    and item["uid"] == state.get("probeUid") and not item["owners"]):
                continue
        if item["kind"] == "EndpointSlice":
            service = item["labels"].get("kubernetes.io/service-name")
            owners = item["owners"]
            if (service in fixed["Service"] and item["namespace"] == NAMESPACE
                    and item["name"].startswith(service + "-") and len(owners) == 1
                    and owners[0].get("kind") == "Service"
                    and owners[0].get("name") == service
                    and owners[0].get("controller") is True
                    and owners[0].get("uid") == state.get("resourceUids", {}).get("service/" + service)):
                continue
        if item["kind"] == "Event":
            continue  # Included by UID in the inventory; event names and counts can change.
        raise GateError("UNEXPECTED_NAMESPACE_RESOURCE")
    if capture:
        state["replicaSetUids"] = replicas
        state["podUids"] = {item["name"]: item["uid"] for item in observed
                            if item["kind"] == "Pod" and (owned_by(item, "ReplicaSet", replicas)
                                                          or owned_by(item, "Job", jobs))}
        save(state)
    return [{key: item[key] for key in ("resource", "name", "uid", "owners", "contentHash")}
            for item in observed]


def capture_lineage(state):
    assert_cluster(state)
    assert_namespace(state)
    assert_resources(state)
    assert_images(state)
    namespace_inventory(state, capture=True)


def database_inventory(state):
    # Aggregate exact counts and content fingerprints inside PostgreSQL; no row values leave it.
    query = """SELECT string_agg(format('SELECT %L AS table_name, count(*) AS rows,
      md5(coalesce(string_agg(md5(row_to_json(t)::text), '''' ORDER BY md5(row_to_json(t)::text)), ''''))
      AS content_hash FROM %I.%I t',
      tablename, schemaname, tablename), ' UNION ALL ' ORDER BY tablename)
      FROM pg_tables WHERE schemaname='public'"""
    prefix = ["-n", NAMESPACE, "exec", "deployment/postgresql", "-c", "postgresql", "--",
              "psql", "-U", "finguardops", "-d", "finguardops", "-Atqc"]
    generated = kubectl(state, *prefix, query, timeout=60).stdout.strip()
    if not generated or not generated.startswith("SELECT '"):
        raise GateError("DB_INVENTORY_UNAVAILABLE")
    rows = kubectl(state, *prefix, generated, timeout=60).stdout.strip().splitlines()
    inventory = {}
    for line in rows:
        name, count, content_hash = line.rsplit("|", 2)
        if not re.fullmatch(r"[0-9a-f]{32}", content_hash):
            raise GateError("DB_INVENTORY_HASH_INVALID")
        inventory[name] = {"rows": int(count), "hash": content_hash}
    if len(inventory) < 20 or "financial_transaction" not in inventory:
        raise GateError("DB_INVENTORY_INCOMPLETE")
    return inventory


def deletion_inventory(state):
    assert_cluster(state)
    assert_cluster_namespaces(state)
    assert_images(state)
    assert_namespace(state)
    assert_resources(state)
    assert_workload_images(state)
    storage = inspect_storage()
    cluster_storage = cluster_storage_inventory(state)
    resources = namespace_inventory(state)
    tables = database_inventory(state)
    return {"runId": state["runId"], "docker": state["docker"],
            "nodeContainerId": state["nodeContainerId"], "nodeUid": state["nodeUid"],
            "namespaceUid": state["namespaceUid"], "images": state["imageIds"],
            "storage": storage, "clusterStorage": cluster_storage,
            "resources": resources, "tables": tables}


def inventory_hash(inventory):
    return hashlib.sha256(json.dumps(inventory, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def record_inventory():
    state = load()
    if state["stage"] not in {"VERIFIED", "RECOVERED"}:
        raise GateError("VERIFIED_EVIDENCE_REQUIRED")
    db_counts(state)
    inventory = deletion_inventory(state)
    path = STATE.parent / "inventory.json"
    path.write_text(json.dumps(inventory, indent=2), encoding="utf-8")
    os.chmod(path, 0o600)
    emit("inventory", "RECORDED", path=str(path), hash=inventory_hash(inventory),
         tables=len(inventory["tables"]), resources=len(inventory["resources"]))


def assert_disposition(state, disposition, recorded, current):
    if (disposition.get("runId") != state["runId"] or disposition.get("decision") != "delete"
            or disposition.get("namespaceUid") != state["namespaceUid"]
            or disposition.get("inventoryHash") != inventory_hash(recorded)):
        raise GateError("OPERATOR_DISPOSITION_MISMATCH")
    if current != recorded:
        raise GateError("DELETION_INVENTORY_CHANGED")


def cleanup(decision, confirmation):
    state = load()
    assert_cluster(state)
    assert_namespace(state)
    if state["stage"] in {"WORKLOADS_APPLIED", "PUBLISHED", "VERIFIED", "RECOVERED"}:
        assert_resources(state)
    if decision == "preserve":
        inspect_storage()
        emit("cleanup", "PRESERVED", cluster=state["cluster"], namespace=NAMESPACE,
              pvc="postgres-data", reason="DATA_REVIEW_REQUIRED")
        return
    if confirmation != state["runId"] or state["stage"] not in {"VERIFIED", "RECOVERED"}:
        raise GateError("DELETE_CONFIRMATION_OR_EVIDENCE_MISSING")
    if not DECISION.is_file():
        raise GateError("OPERATOR_DISPOSITION_MISSING")
    disposition = json.loads(DECISION.read_text(encoding="utf-8"))
    if not (STATE.parent / "inventory.json").is_file():
        raise GateError("RECORDED_INVENTORY_MISSING")
    recorded = json.loads((STATE.parent / "inventory.json").read_text(encoding="utf-8"))
    db_counts(state)
    assert_disposition(state, disposition, recorded, deletion_inventory(state))
    # The namespace owns the PVC. Deleting it can delete the PV's backing data.
    assert_cluster_namespaces(state)
    assert_disposition(state, disposition, recorded, deletion_inventory(state))
    mutation_gate(state)
    kubectl(state, "delete", "namespace", NAMESPACE, "--wait=true", "--timeout=180s", timeout=200)
    state["stage"] = "NAMESPACE_DELETED_CLUSTER_RETAINED"
    save(state)
    emit("cleanup", "NAMESPACE_DELETED_CLUSTER_RETAINED", cluster=state["cluster"], namespace=NAMESPACE)


def assert_partial_namespace_empty(state):
    assert_namespace(state)
    namespace_inventory(state)
    for resource in ("pvc", "deployment", "statefulset", "job", "pod"):
        items = json.loads(kubectl(state, "-n", NAMESPACE, "get", resource,
                                   "-o", "json").stdout)["items"]
        if items:
            raise GateError("PARTIAL_NAMESPACE_MAY_CONTAIN_DATA")


def cleanup_partial(confirmation):
    state = load()
    if (confirmation != state["runId"] or state["stage"] not in
            {"CREATING", "CLUSTER_CREATED", "IMAGES_LOADED", "NAMESPACE_CREATED"}):
        raise GateError("PARTIAL_CLEANUP_STAGE_OR_CONFIRMATION")
    # A failed create without captured IDs is preserved for manual review.
    if not state.get("nodeContainerId") or not state.get("nodeUid"):
        raise GateError("PARTIAL_CLUSTER_OWNERSHIP_UNPROVEN")
    assert_cluster(state)
    assert_cluster_namespaces(state)
    assert_source(state)
    namespace_created = bool(state.get("namespaceUid"))
    if namespace_created:
        assert_partial_namespace_empty(state)
    else:
        assert_namespace(state, absent=True)
    cluster_storage_inventory(state, partial=True)
    for service, image_id in state.get("imageIds", {}).items():
        image = state["images"][service]
        if docker_image_id(image) != image_id or node_image_id(state, image) != image_id:
            raise GateError("IMAGE_ID_MISMATCH")
    for service, image in zip(BASE_SERVICES, BASE_IMAGES):
        alias = state.get("baseAliases", {}).get(service)
        observed = state.get("baseImageIds", {}).get(service)
        if alias and observed:
            if (docker_image_id(image) != observed["source"]
                    or docker_image_id(alias) != observed["source"]
                    or docker_platform_image_id(alias) != observed["platform"]
                    or node_image_id(state, alias) != observed["platform"]):
                raise GateError("BASE_IMAGE_ID_MISMATCH")
    assert_cluster(state)
    assert_cluster_namespaces(state)
    assert_source(state)
    cluster_storage_inventory(state, partial=True)
    if namespace_created:
        assert_partial_namespace_empty(state)
        cluster_storage_inventory(state, partial=True)
        mutation_gate(state, partial=True)
        kubectl(state, "delete", "namespace", NAMESPACE, "--wait=true", "--timeout=180s", timeout=200)
    else:
        assert_namespace(state, absent=True)
    state["stage"] = "PARTIAL_NAMESPACE_DELETED_CLUSTER_RETAINED"
    save(state)
    emit("cleanup-partial", "CLUSTER_RETAINED", cluster=state["cluster"])


def kind_cleanup_preflight():
    """Record a zero-storage ownership audit; this does not delete kind."""
    state = load()
    if state["stage"] != "NAMESPACE_DELETED_CLUSTER_RETAINED":
        raise GateError("KIND_CLEANUP_STAGE_INVALID")
    assert_cluster(state)
    assert_images(state)
    assert_namespace(state, absent=True)
    names = {item["metadata"]["name"] for item in
             json.loads(kubectl(state, "get", "namespaces", "-o", "json").stdout)["items"]}
    if names != {"default", "kube-system", "kube-public", "kube-node-lease",
                 "local-path-storage"}:
        raise GateError("KIND_CLEANUP_NAMESPACE_REMAINS")
    if (json.loads(kubectl(state, "get", "pvc", "--all-namespaces", "-o", "json").stdout)["items"]
            or json.loads(kubectl(state, "get", "pv", "-o", "json").stdout)["items"]):
        raise GateError("KIND_CLEANUP_STORAGE_REMAINS")
    backing = run(["docker", "exec", state["cluster"] + "-control-plane", "sh", "-c",
                   "if test -d /var/local-path-provisioner; then find /var/local-path-provisioner -mindepth 1 -print; fi"],
                  timeout=30).stdout.strip()
    if backing:
        raise GateError("KIND_CLEANUP_BACKING_REMAINS")
    node = json.loads(run(["docker", "container", "inspect", state["nodeContainerId"]],
                          timeout=30).stdout)[0]
    mounts = [entry for entry in node["Mounts"] if entry.get("Type") == "volume"]
    if (node["Id"] != state["nodeContainerId"] or len(mounts) != 1
            or mounts[0].get("Destination") != "/var"
            or set(node["NetworkSettings"]["Networks"]) != {"kind"}):
        raise GateError("KIND_CLEANUP_NODE_MOUNT_MISMATCH")
    volume_id = mounts[0]["Name"]
    volume = json.loads(run(["docker", "volume", "inspect", volume_id], timeout=30).stdout)[0]
    if volume["Name"] != volume_id or volume.get("Labels", {}).get("com.docker.volume.anonymous") != "":
        raise GateError("KIND_CLEANUP_VOLUME_OWNERSHIP")
    network = json.loads(run(["docker", "network", "inspect", "kind"], timeout=30).stdout)[0]
    if set(network.get("Containers", {})) != {state["nodeContainerId"]}:
        raise GateError("KIND_CLEANUP_NETWORK_SHARED")
    audit = {"runId": state["runId"], "cluster": state["cluster"],
             "docker": state["docker"], "sourceHash": state["sourceHash"],
             "nodeId": state["nodeContainerId"], "nodeUid": state["nodeUid"],
             "networkId": network["Id"], "volumeId": volume_id,
             "namespaceCount": len(names), "pvcCount": 0, "pvCount": 0,
             "backingEmpty": True, "images": state["images"],
             "baseAliases": state["baseAliases"]}
    path = STATE.parent / ("kind-cleanup-preflight-" + state["runId"] + ".json")
    if path.exists():
        raise GateError("KIND_CLEANUP_AUDIT_ALREADY_EXISTS")
    path.write_text(json.dumps(audit, sort_keys=True, indent=2), encoding="utf-8")
    os.chmod(path, 0o600)
    emit("kind-cleanup-preflight", "PASS", runId=state["runId"],
         nodeId=state["nodeContainerId"], networkId=network["Id"], volumeId=volume_id)


def record_kind_cleanup(command_exit):
    state = load()
    path = STATE.parent / ("kind-cleanup-preflight-" + state["runId"] + ".json")
    if command_exit != 0 or not path.is_file():
        raise GateError("MANUAL_KIND_CLEANUP_NOT_PROVEN")
    audit = json.loads(path.read_text(encoding="utf-8"))
    if (audit.get("runId") != state["runId"] or audit.get("cluster") != state["cluster"]
            or audit.get("docker") != state["docker"]
            or audit.get("sourceHash") != state["sourceHash"]
            or audit.get("nodeId") != state["nodeContainerId"]
            or audit.get("nodeUid") != state["nodeUid"]
            or audit.get("pvcCount") != 0 or audit.get("pvCount") != 0
            or audit.get("backingEmpty") is not True):
        raise GateError("MANUAL_KIND_CLEANUP_AUDIT_MISMATCH")
    assert_docker(state)
    if (state["cluster"] in run(["kind", "get", "clusters"], timeout=30).stdout.splitlines()
            or run(["docker", "container", "inspect", state["nodeContainerId"]],
                   check=False, timeout=30).returncode == 0
            or run(["docker", "volume", "inspect", audit["volumeId"]],
                   check=False, timeout=30).returncode == 0
            or run(["docker", "network", "inspect", audit["networkId"]],
                   check=False, timeout=30).returncode == 0):
        raise GateError("MANUAL_KIND_CLEANUP_RESOURCE_REMAINS")
    for image in list(state["images"].values()) + list(state["baseAliases"].values()):
        if run(["docker", "image", "inspect", image], check=False, timeout=30).returncode == 0:
            raise GateError("MANUAL_KIND_CLEANUP_IMAGE_REMAINS")
    emit("record-kind-cleanup", "PASS", runId=state["runId"], cluster=state["cluster"])


def main():
    global ALLOW_LOW_HOST_MEMORY, ALLOW_CRITICAL_HOST_MEMORY
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("preflight", "prepare", "deploy", "verify", "recover",
                                         "storage", "inventory", "cleanup", "cleanup-partial",
                                         "kind-cleanup-preflight", "record-kind-cleanup"))
    parser.add_argument("--run-id", required=True, help="new unique 8-hex execution id")
    parser.add_argument("--decision", choices=("preserve", "delete"), default="preserve")
    parser.add_argument("--confirm-run-id")
    parser.add_argument("--command-exit", type=int,
                        help="actual exit code captured from the explicit manual kind deletion")
    parser.add_argument("--node-image", help="already-local kindest/node image for prepare")
    parser.add_argument("--allow-low-host-memory", action="store_true",
                        help="this invocation only: warn below 4 GiB host free RAM")
    parser.add_argument("--allow-critical-host-memory", action="store_true",
                        help="this invocation only: warn below 1 GiB host free RAM; requires low-memory flag")
    args = parser.parse_args()
    if args.allow_critical_host_memory and not args.allow_low_host_memory:
        parser.error("--allow-critical-host-memory requires --allow-low-host-memory")
    ALLOW_LOW_HOST_MEMORY = args.allow_low_host_memory
    ALLOW_CRITICAL_HOST_MEMORY = args.allow_critical_host_memory
    if not RUN_ID_RE.fullmatch(args.run_id):
        parser.error("--run-id must be eight lowercase hexadecimal characters")
    exit_code, error_code = 0, None
    stage_safe, post_record_emit = None, None
    try:
        assert_stage_order(args.run_id, args.mode)
        if args.mode not in {"preflight", "prepare"} and load()["runId"] != args.run_id:
            raise GateError("RUN_ID_RECEIPT_MISMATCH")
        if args.mode == "preflight":
            capacity_gate()
            required_tools("kind", "kubectl", "git")
            base_image_gate()
            build_input_inventory()
            emit("preflight", "PASS")
        elif args.mode == "prepare":
            prepare(args.node_image, args.run_id)
        elif args.mode == "deploy":
            deploy()
        elif args.mode == "verify":
            verify()
        elif args.mode == "recover":
            recovery_hash = recover()
            stage_safe = {"recoveryChecks": len(RECOVERY_CHECKS),
                          "recoveryEvidenceSha256": recovery_hash}
            ids = load()["evidence"]
            post_record_emit = {"transactionId": ids["transactionId"],
                                "caseId": ids["caseId"]}
        elif args.mode == "cleanup":
            cleanup(args.decision, args.confirm_run_id)
        elif args.mode == "cleanup-partial":
            cleanup_partial(args.confirm_run_id)
        elif args.mode == "inventory":
            record_inventory()
        elif args.mode == "kind-cleanup-preflight":
            kind_cleanup_preflight()
        elif args.mode == "record-kind-cleanup":
            record_kind_cleanup(args.command_exit)
        else:
            inspect_storage()
    except GateError as exc:
        exit_code, error_code = 1, str(exc)
    except KeyboardInterrupt:
        exit_code, error_code = 1, "STAGE_INTERRUPTED"
    except SystemExit:
        exit_code, error_code = 1, "STAGE_SYSTEM_EXIT"
    except Exception:
        exit_code, error_code = 1, "UNEXPECTED_STAGE_FAILURE"
    try:
        recorded_status = ("BLOCKED" if exit_code else
                           "RECOVERY_ONLY" if args.mode == "cleanup-partial" else "PASS")
        record_stage(args.run_id, args.mode, exit_code, recorded_status, error_code,
                     {"manualKindCommandExitCode": args.command_exit}
                     if args.mode == "record-kind-cleanup" else stage_safe)
    except (GateError, KeyboardInterrupt, SystemExit, Exception):
        emit(args.mode, "BLOCKED", code="EXECUTION_EVIDENCE_WRITE_FAILED")
        return 1
    if error_code:
        emit(args.mode, "BLOCKED", code=error_code)
    elif post_record_emit is not None:
        emit("recover", "PASS", **post_record_emit)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())

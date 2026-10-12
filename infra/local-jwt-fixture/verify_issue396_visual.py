"""Issue #396 isolated visual evidence fixture for a committed checkout."""
import datetime as dt
import importlib.util
import json
import os
import re
import ssl
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "infra" / "keycloak"))
import verify_e2e as gate

spec = importlib.util.spec_from_file_location("issue394_visual", ROOT / "infra" / "local-jwt-fixture" / "verify_issue394_e2e.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

PROJECT = "finguardops-keycloak-browser-e2e"
API_IN_SERVICE = r"""import json,sys,urllib.request,urllib.error
p=json.load(sys.stdin)
assert p['path'].startswith('/api/v1/') and p['method'] in ('GET','POST','PATCH','OPTIONS')
headers={k:v for k,v in p['headers'].items() if k.lower() in ('authorization','content-type','idempotency-key')}
data=p.get('body')
request=urllib.request.Request('http://backend:8080'+p['path'],data=data.encode('utf-8') if data is not None else None,headers=headers,method=p['method'])
try: response=urllib.request.urlopen(request,timeout=30)
except urllib.error.HTTPError as error: response=error
with response: print(json.dumps({'status':response.status,'body':response.read().decode('utf-8')}))
"""


class Context:
    def __init__(self):
        manifest_path = Path(os.environ["FINGUARDOPS_E2E_FIXTURE_MANIFEST"]).resolve(strict=True)
        identity = json.loads(manifest_path.read_text(encoding="utf-8"))
        run_id = identity["runId"]
        commit_sha = identity["commitSha"]
        tag = "e2e-" + commit_sha[:12] + "-" + run_id
        contract = gate.load_owner_contract({
            "FINGUARDOPS_E2E_BACKEND_IMAGE": "finguardops-backend:" + tag,
            "FINGUARDOPS_E2E_AI_SERVICE_IMAGE": "finguardops-ai-service:" + tag,
            "FINGUARDOPS_E2E_REVISION": commit_sha,
            "FINGUARDOPS_E2E_SOURCE_TREE": identity["treeSha"],
            "FINGUARDOPS_E2E_RUN_ID": run_id,
            "FINGUARDOPS_E2E_REPOSITORY_ID": identity["repositoryId"],
        })
        if identity["composeProject"] != PROJECT or run_id != os.environ["FINGUARDOPS_E2E_RUN_ID"]:
            raise RuntimeError("visual fixture owner mismatch")
        if os.environ.get(gate.COMPOSE_PROJECT_ENVIRONMENT) != PROJECT:
            raise RuntimeError("visual project owner mismatch")
        if contract.commit_sha != os.popen("git rev-parse HEAD").read().strip():
            raise RuntimeError("visual source mismatch")
        self.contract = contract
        self.project = PROJECT
        self.host = gate.HostContext(ROOT, PROJECT, 120, 1800, contract)
        # The official browser runner removes this variable from Playwright;
        # restore the already validated fixture directory only for Compose.
        self.host.environment["FINGUARDOPS_E2E_FIXTURE_DIR"] = str(manifest_path.parent)
        self.host.environment["FINGUARDOPS_ISSUE384_AI_IMAGE"] = contract.ai_service_image
        self.host.environment["FINGUARDOPS_ISSUE384_BACKEND_IMAGE"] = contract.backend_image
        self.directory = ROOT / "backend" / "build" / ("visual-capture-" + contract.run_id)
        self.directory.mkdir(parents=True, exist_ok=True)
        cert = ROOT / "infra" / "keycloak" / ".local" / "tls" / "localhost.crt"
        self.ssl = ssl.create_default_context(cafile=str(cert))

    def compose_run(self, arguments, *, timeout=None, **_):
        return self.host.execute(arguments, timeout=timeout).decode("utf-8", "strict")

    def sql_scalar(self, query):
        return gate.sql_scalar(self.host, query)

    def request_api(self, method, path, headers, body):
        if not re.fullmatch(r"/api/v1/[A-Za-z0-9/_?=&.%-]{1,240}", path):
            raise RuntimeError("visual API path invalid")
        payload = json.dumps({"method": method, "path": path, "headers": headers,
                              "body": body}, separators=(",", ":")).encode("utf-8")
        output = self.host.execute(["exec", "-T", "ai-service", "python", "-c", API_IN_SERVICE],
                                   input_bytes=payload, timeout=60)
        return json.loads(output)

    def probe(self, method, url, expected, token, body, headers):
        split = urllib.parse.urlsplit(url)
        path = split.path + ("?" + split.query if split.query else "")
        request_body = None if body is None else json.dumps(body, separators=(",", ":"))
        result = self.request_api(method, path,
                                  {**headers, "Authorization": "Bearer " + token}, request_body)
        if result["status"] != expected:
            raise RuntimeError(f"visual API status {result['status']}, expected {expected}")
        return {"body": json.loads(result["body"]) if result["body"] else None}

    def token(self, client, secret_name):
        path = ROOT / "infra" / "keycloak" / ".local" / "secrets" / secret_name
        secret = gate.read_secret(path)
        data = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": client,
                                       "client_secret": secret}).encode("ascii")
        request = urllib.request.Request("https://localhost:8443/realms/finguardops-local/protocol/openid-connect/token",
                                         data=data, headers={"Content-Type": "application/x-www-form-urlencoded"})
        with urllib.request.urlopen(request, context=self.ssl, timeout=30) as response:
            return json.load(response)["access_token"]

    def assert_owned_mock(self):
        name = PROJECT + "-external-risk-mock-1"
        output = subprocess.run(["docker", "inspect", name, "--format", "{{json .}}"],
                                capture_output=True, text=True, timeout=20, check=True)
        record = json.loads(output.stdout)
        labels = record["Config"]["Labels"]
        if labels.get("com.docker.compose.project") != PROJECT or \
                labels.get("com.docker.compose.service") != "external-risk-mock" or \
                record["Config"]["Image"] != self.contract.ai_service_image or \
                record["State"]["OOMKilled"]:
            raise RuntimeError("visual mock owner mismatch")
        image = subprocess.run(["docker", "image", "inspect", record["Image"],
                                "--format", "{{json .Config.Labels}}"],
                               capture_output=True, text=True, timeout=20, check=True)
        image_labels = json.loads(image.stdout)
        if image_labels.get("com.finguardops.e2e.run-id") != self.contract.run_id or \
                image_labels.get("org.opencontainers.image.revision") != self.contract.commit_sha:
            raise RuntimeError("visual mock image owner mismatch")

    def switch_mock(self, exact_device):
        self.assert_owned_mock()
        if exact_device:
            self.host.compose.extend(["-f", str(ROOT / "infra" / "compose.issue384-e2e.yml")])
        else:
            self.host.compose = self.host.compose[:-2]
        self.compose_run(["up", "-d", "--no-build", "--no-deps", "--force-recreate", "--wait",
                          "external-risk-mock"], timeout=180)
        self.assert_owned_mock()


def publish(ctx):
    phases = [("v2", "rule-v2-local-publication", "PUBLISH_RULE_V2_LOCAL"),
              ("v3", "rule-v3-local-publication", "PUBLISH_RULE_V3_LOCAL"),
              ("v4", "rule-v4-local-publication", "PUBLISH_RULE_V4_LOCAL"),
              ("v5", "rule-v5-local-publication", "PUBLISH_RULE_V5_LOCAL"),
              ("v6", "rule-v6-local-publication", "PUBLISH_RULE_V6_LOCAL"),
              ("v7", "rule-v7-local-publication", "PUBLISH_RULE_V7_LOCAL")]
    for version, profile, confirmation in phases:
        effective = m.now() + dt.timedelta(seconds=60)
        ctx.compose_run(["run", "--rm", "--no-deps", "--pull", "never", "-T",
                         "-e", "SPRING_PROFILES_ACTIVE=local," + profile,
                         "-e", "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false",
                         "-e", "FINGUARDOPS_KAFKA_ENABLED=false", "backend",
                         "--spring.main.web-application-type=none",
                         f"--finguardops.{profile}.enabled=true",
                         f"--finguardops.{profile}.confirmation={confirmation}",
                         f"--finguardops.{profile}.effective-from={m.stamp(effective)}"], timeout=240)
        while m.now() <= effective:
            time.sleep(0.5)
        print(json.dumps({"step": "publish-" + version, "exitCode": 0}), flush=True)


def seed_history(ctx, customer, account, suffix):
    for index in range(10):
        body = m.transaction(customer, account, f"visual-prior-{index}-{suffix}", 100000,
                             device="visual-old-device-" + suffix,
                             occurred=m.now() - dt.timedelta(days=3 + index % 3, seconds=index))
        tid = body["transactionId"]
        ctx.sql_scalar("""INSERT INTO financial_transaction
          (transaction_id,transaction_type,amount,currency_code,occurred_at,
           external_customer_ref,sender_account_ref,recipient_account_ref,
           channel,device_ref,processing_status)
          VALUES ('%s','ACCOUNT_TRANSFER',100000,'KRW','%s','%s','%s','%s',
                  'MOBILE_BANKING','%s','RECEIVED');
          UPDATE financial_transaction SET processing_status='APPROVED',version=version+1
          WHERE transaction_id='%s'""" %
          (tid, body["occurredAt"], customer, account, body["recipientAccountRef"],
           body["deviceRef"], tid))
        assert ctx.sql_scalar("SELECT (approved_at IS NOT NULL)::text FROM financial_transaction "
                              "WHERE transaction_id='%s'" % tid) == "true"


def generate(ctx):
    ingest = ctx.token("finguardops-transaction-ingestor", "transaction-service-client-secret")
    behavior = ctx.token("finguardops-behavior-ingestor", "behavior-service-client-secret")
    suffix = uuid.uuid4().hex[:12]
    date = m.now().astimezone(dt.timezone(dt.timedelta(hours=9))).date().isoformat()
    entries = []

    def submit(body, code=None, anchors=None):
        response = m.create(ctx, ingest, body)
        stored = m.snapshot(ctx, body["transactionId"])
        assert stored["detectionResultId"] == response["adoptedDetectionResultId"]
        assert stored["status"] == response["processingStatus"]
        if code:
            assert response["caseId"] in stored["caseIds"]
            assert m.RULES[code] in {row["ruleCode"] for row in m.evidence_rows(ctx, body["transactionId"])}
            entries.append({"scenario": code, "body": body, "response": response,
                            "anchors": anchors or {}, "stored": stored})
        return response

    publish(ctx)
    scn3 = m.transaction("visual-003-" + suffix, "visual-003-sender-" + suffix,
                         "issue382-synthetic-risk-" + suffix, 10000000)
    submit(scn3, "SCN003")
    ctx.switch_mock(True)

    c1, a1 = "visual-001-" + suffix, "visual-001-sender-" + suffix
    seed_history(ctx, c1, a1, suffix)
    device = "visual-001-new-device-" + suffix
    recipient = "visual-001-new-recipient-" + suffix
    device_at = m.now() - dt.timedelta(minutes=5)
    device_event = m.event(ctx, behavior, "DEVICE_REGISTERED", c1, a1, device=device, occurred=device_at)
    m.event(ctx, behavior, "BENEFICIARY_REGISTERED", c1, a1, recipient=recipient,
            occurred=m.now() - dt.timedelta(minutes=4))
    coverage = {}
    for kind in ("TRANSFER_HISTORY_COMPLETE", "DEVICE_HISTORY_COMPLETE"):
        coverage[kind] = m.fact(ctx, behavior, kind, c1, state="COMPLETE",
                                from_at=dt.datetime(1970, 1, 1, tzinfo=m.UTC),
                                through=m.now() + dt.timedelta(minutes=3))
    trust = m.fact(ctx, behavior, "DEVICE_TRUST", c1, account=a1, device=device,
                   event_id=device_event, at=device_at, state="UNVERIFIED")
    time.sleep(1.2)
    submit(m.transaction(c1, a1, recipient, 1000000, device=device), "SCN001",
           {"deviceEventId": device_event, "deviceTrustFactId": trust,
            "deviceCoverageFactId": coverage["DEVICE_HISTORY_COMPLETE"]})

    c2, a2 = "visual-002-" + suffix, "visual-002-sender-" + suffix
    password_at, limit_at = m.now() - dt.timedelta(minutes=10), m.now() - dt.timedelta(minutes=5)
    password = m.event(ctx, behavior, "PASSWORD_CHANGED", c2, a2, occurred=password_at)
    limit = m.event(ctx, behavior, "TRANSFER_LIMIT_CHANGED", c2, a2, occurred=limit_at)
    limit_fact = m.fact(ctx, behavior, "LIMIT_CHANGE", c2, account=a2, event_id=limit,
                        at=limit_at, state="SUCCEEDED", scope="PER_TRANSFER", old=2000000,
                        new=12000000)
    identity = m.fact(ctx, behavior, "IDENTITY_CHECK", c2, account=a2,
                      event_id=password, at=password_at, state="VERIFIED")
    time.sleep(1.2)
    submit(m.transaction(c2, a2, "visual-002-recipient-" + suffix, 10000000), "SCN002",
           {"passwordEventId": password, "limitEventId": limit,
            "limitFactId": limit_fact, "identityFactId": identity})

    c4, a4 = "visual-004-" + suffix, "visual-004-sender-" + suffix
    prior4 = []
    for index in range(2):
        source = m.transaction(c4, a4, f"visual-004-prior-{index}-{suffix}", 1200000,
                               device="visual-004-safe-" + suffix)
        submit(source)
        prior4.append(source["transactionId"])
    coverage4 = m.fact(ctx, behavior, "TRANSFER_HISTORY_COMPLETE", c4, state="COMPLETE",
                        from_at=dt.datetime(1970, 1, 1, tzinfo=m.UTC),
                        through=m.now() + dt.timedelta(minutes=3))
    recipient4 = "visual-004-target-" + suffix
    m.event(ctx, behavior, "BENEFICIARY_REGISTERED", c4, a4, recipient=recipient4,
            occurred=m.now() - dt.timedelta(minutes=2))
    time.sleep(1.2)
    submit(m.transaction(c4, a4, recipient4, 10000000,
                         device="issue384-synthetic-risk-" + suffix), "SCN004",
           {"coverageFactId": coverage4, "caseTransactionIds": prior4})

    collector = "issue382-synthetic-risk-" + suffix
    prior5 = []
    for index in range(2):
        source = m.transaction(f"visual-005-prior-{index}-{suffix}",
                               f"visual-005-sender-{index}-{suffix}", collector, 2000000,
                               device="visual-005-safe-" + suffix)
        submit(source)
        prior5.append(source["transactionId"])
    coverage5 = m.cover(ctx, behavior, "INBOUND_ACCOUNT_HISTORY_COMPLETE",
                        "visual-005-owner-" + suffix, account=collector)
    ctx.switch_mock(False)
    time.sleep(1.2)
    submit(m.transaction("visual-005-target-" + suffix, "visual-005-target-sender-" + suffix,
                         collector, 10000000,
                         device="issue384-synthetic-risk-" + suffix), "SCN005",
           {"coverageFactId": coverage5, "caseTransactionIds": prior5})

    c6, a6 = "visual-006-" + suffix, "visual-006-account-" + suffix
    source6 = m.transaction("visual-006-origin-" + suffix,
                            "visual-006-origin-account-" + suffix, a6, 10000000)
    submit(source6)
    target6 = m.transaction(c6, a6, None, 8000000, kind="ATM_WITHDRAWAL")
    coverage6 = m.cover(ctx, behavior, "ACCOUNT_FLOW_HISTORY_COMPLETE", c6, account=a6)
    link6 = m.link(ctx, behavior, "SCN006", source6["transactionId"], target6["transactionId"], c6, a6)
    time.sleep(1.2)
    target6["occurredAt"] = m.stamp(m.now())
    submit(target6, "SCN006", {"sourceTransactionId": source6["transactionId"],
                               "currentLinkId": link6, "historyCoverageFactId": coverage6,
                               "caseTransactionIds": [source6["transactionId"]]})

    c7, a7 = "visual-007-" + suffix, "visual-007-account-" + suffix
    loan = m.transaction(c7, a7, None, 10000000, kind="LOAN_DISBURSED")
    submit(loan)
    recipient7 = "visual-007-new-recipient-" + suffix
    beneficiary7 = m.event(ctx, behavior, "BENEFICIARY_REGISTERED", c7, a7,
                           recipient=recipient7, occurred=m.now())
    target7 = m.transaction(c7, a7, recipient7, 8000000)
    coverage7 = m.cover(ctx, behavior, "ACCOUNT_FLOW_HISTORY_COMPLETE", c7, account=a7)
    link7 = m.link(ctx, behavior, "SCN007", loan["transactionId"], target7["transactionId"], c7, a7)
    time.sleep(1.2)
    target7["occurredAt"] = m.stamp(m.now())
    submit(target7, "SCN007", {"sourceTransactionId": loan["transactionId"],
                               "currentLinkId": link7, "beneficiaryEventId": beneficiary7,
                               "historyCoverageFactId": coverage7,
                               "caseTransactionIds": [loan["transactionId"]]})

    c8, hub = "visual-008-" + suffix, "visual-008-hub-" + suffix
    external = ["visual-008-external-a-" + suffix, "visual-008-external-b-" + suffix]
    owners = {account: m.open_owner(ctx, behavior, c8, account) for account in external}
    time.sleep(1.2)
    sources8 = []
    for account in external:
        source = m.transaction(c8, account, hub, 5000000, kind="OPEN_BANKING_TRANSFER")
        submit(source)
        sources8.append(source)
    target8 = m.transaction(c8, hub, "visual-008-out-" + suffix, 8000000)
    coverage8 = m.cover(ctx, behavior, "ACCOUNT_FLOW_HISTORY_COMPLETE", c8, account=hub)
    stages8 = []
    for source in sources8:
        link8 = m.link(ctx, behavior, "SCN008", source["transactionId"],
                       target8["transactionId"], c8, hub)
        stages8.append({"sourceTransactionId": source["transactionId"], "linkId": link8,
                        "ownershipAssertionId": owners[source["senderAccountRef"]]})
    time.sleep(1.2)
    target8["occurredAt"] = m.stamp(m.now())
    submit(target8, "SCN008", {"stages": stages8, "historyCoverageFactId": coverage8,
                               "caseTransactionIds": [source["transactionId"] for source in sources8]})

    by_code = {entry["scenario"]: entry for entry in entries}
    assert set(by_code) == set(m.RULES)
    contract = {
        "SCN001": ("'rule-ml-policy-v3','rule-ml-policy-v4','rule-ml-policy-v5','rule-ml-policy-v6'", "4",
                   "SCN001-002-contract-v1", ("deviceEventId", "deviceCoverageFactId", "deviceTrustFactId")),
        "SCN002": ("'rule-ml-policy-v3','rule-ml-policy-v4','rule-ml-policy-v5','rule-ml-policy-v6'", "4",
                   "SCN001-002-contract-v1", ("passwordEventId", "limitEventId", "limitFactId")),
        "SCN003": ("'scoring-policy-v3','rule-ml-policy-v2','rule-ml-policy-v3','rule-ml-policy-v4',"
                   "'rule-ml-policy-v5','rule-ml-policy-v6'", "1", None, ("providerAsOf", "lookedUpAt")),
        "SCN004": ("'rule-ml-policy-v4','rule-ml-policy-v5','rule-ml-policy-v6'", "1",
                   "SCN004-005-contract-v1", ("coverageFactId",)),
        "SCN005": ("'rule-ml-policy-v4','rule-ml-policy-v5','rule-ml-policy-v6'", "1",
                   "SCN004-005-contract-v1", ("coverageFactId",)),
        "SCN006": ("'rule-ml-policy-v5','rule-ml-policy-v6'", "1",
                   "SCN006-007-contract-v1", ("sourceTransactionId", "currentLinkId", "historyCoverageFactId")),
        "SCN007": ("'rule-ml-policy-v5','rule-ml-policy-v6'", "1",
                   "SCN006-007-contract-v1", ("sourceTransactionId", "beneficiaryEventId", "historyCoverageFactId")),
        "SCN008": ("'rule-ml-policy-v6'", "1", "SCN008-contract-v1", ("historyCoverageFactId",)),
    }
    scenario_ids = {}
    for code, rule in m.RULES.items():
        policies, rule_version, source_version, required = contract[code]
        conditions = ["d.scoring_policy_version IN (" + policies + ")",
                      "e.rule_version='" + rule_version + "'"]
        if source_version:
            conditions.append("e.observation_summary @> '{\"sourceVersion\":\"" +
                              source_version + "\"}'::jsonb")
        conditions.extend("jsonb_exists(e.observation_summary,'" + key + "')" for key in required)
        if code == "SCN003":
            conditions.append("EXISTS (SELECT 1 FROM detection_evidence x "
                              "WHERE x.detection_result_id=d.id AND x.evidence_type='EXTERNAL_RISK' "
                              "AND x.observation_summary @> '{\"recipientAccountMatched\":true}'::jsonb)")
        if code == "SCN008":
            conditions.append("CASE WHEN jsonb_typeof(e.observation_summary->'stages')='array' "
                              "THEN jsonb_array_length(e.observation_summary->'stages')>=2 ELSE false END")
        query = """SELECT COALESCE(json_agg(DISTINCT t.transaction_id), '[]'::json)::text
            FROM financial_transaction t JOIN detection_result d ON d.id=t.adopted_detection_result_id
            JOIN detection_evidence e ON e.detection_result_id=d.id
            WHERE (t.occurred_at AT TIME ZONE 'Asia/Seoul')::date='%s'
              AND d.analysis_status='COMPLETED' AND e.evidence_type='RULE'
              AND e.rule_code='%s' AND %s""" % (date, rule, " AND ".join(conditions))
        scenario_ids[code] = sorted(json.loads(ctx.sql_scalar(query)))
        entry_id = by_code[code]["body"]["transactionId"]
        assert entry_id in scenario_ids[code], "scenario target not in contract DB set"
    expected_scenarios = {code: len(ids) for code, ids in scenario_ids.items()}
    raw_scn002 = json.loads(ctx.sql_scalar("""SELECT COALESCE(json_agg(DISTINCT t.transaction_id),'[]'::json)::text
        FROM financial_transaction t JOIN detection_result d ON d.id=t.adopted_detection_result_id
        JOIN detection_evidence e ON e.detection_result_id=d.id
        WHERE (t.occurred_at AT TIME ZONE 'Asia/Seoul')::date='%s'
          AND e.evidence_type='RULE' AND e.rule_code='RECENT_SECURITY_CHANGE_HIGH_AMOUNT'""" % date))
    scn002_exclusions = []
    for transaction_id in sorted(set(raw_scn002) - set(scenario_ids["SCN002"])):
        if not re.fullmatch(r"[0-9a-f-]{36}", transaction_id):
            raise RuntimeError("scenario transaction identity invalid")
        flags = json.loads(ctx.sql_scalar("""SELECT json_build_object(
          'completed',d.analysis_status='COMPLETED',
          'policy',d.scoring_policy_version IN ('rule-ml-policy-v3','rule-ml-policy-v4',
            'rule-ml-policy-v5','rule-ml-policy-v6'),
          'ruleVersion',e.rule_version='4',
          'sourceVersion',e.observation_summary @> '{"sourceVersion":"SCN001-002-contract-v1"}'::jsonb,
          'passwordEventId',jsonb_exists(e.observation_summary,'passwordEventId'),
          'limitEventId',jsonb_exists(e.observation_summary,'limitEventId'),
          'limitFactId',jsonb_exists(e.observation_summary,'limitFactId'))
          FROM financial_transaction t JOIN detection_result d ON d.id=t.adopted_detection_result_id
          JOIN detection_evidence e ON e.detection_result_id=d.id
          WHERE t.transaction_id='%s' AND e.evidence_type='RULE'
            AND e.rule_code='RECENT_SECURITY_CHANGE_HIGH_AMOUNT' LIMIT 1""" % transaction_id))
        scn002_exclusions.append({"transactionId": transaction_id,
                                  "failedPredicates": sorted(k for k, value in flags.items() if not value)})
    dashboard = json.loads(ctx.sql_scalar("""SELECT json_build_object(
      'total',(SELECT count(DISTINCT transaction_id) FROM financial_transaction
               WHERE (occurred_at AT TIME ZONE 'Asia/Seoul')::date='%s'),
      'classified',(SELECT count(DISTINCT transaction_id) FROM financial_transaction
               WHERE (occurred_at AT TIME ZONE 'Asia/Seoul')::date='%s'
                 AND adopted_detection_result_id IS NOT NULL),
      'newCases',(SELECT count(DISTINCT case_id) FROM fraud_case
               WHERE (created_at AT TIME ZONE 'Asia/Seoul')::date='%s'))""" % (date, date, date)))
    payload = {"runId": ctx.contract.run_id, "commitSha": ctx.contract.commit_sha,
               "date": date, "entries": entries, "dashboard": dashboard,
               "scenarios": expected_scenarios, "scenarioIds": scenario_ids,
               "scn002Exclusions": scn002_exclusions}
    (ctx.directory / "fixtures.json").write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"step": "generated", "exitCode": 0, "runId": ctx.contract.run_id,
                      "scenarios": sorted(by_code), "dashboard": dashboard}), flush=True)


def validate(ctx):
    token = json.load(sys.stdin)["analystToken"]
    data = json.loads((ctx.directory / "fixtures.json").read_text(encoding="utf-8"))
    assert data["commitSha"] == ctx.contract.commit_sha and data["runId"] == ctx.contract.run_id
    for entry in data["entries"]:
        m.check(ctx, token, entry["body"], entry["response"], entry["scenario"], entry["anchors"])
    print(json.dumps({"step": "validated", "exitCode": 0,
                      "scenarios": [entry["scenario"] for entry in data["entries"]]}), flush=True)


def relay(ctx):
    request = json.load(sys.stdin)
    result = ctx.request_api(request["method"], request["path"],
                             request["headers"], request.get("body"))
    print(json.dumps(result, separators=(",", ":")), flush=True)


def verify_report(ctx):
    ids = json.load(sys.stdin)
    for key in ("caseId", "aiRequestId", "executionId", "reportId"):
        if not isinstance(ids.get(key), str) or not re.fullmatch(
                r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", ids[key]):
            raise RuntimeError("report identity invalid")
    stored = json.loads(ctx.sql_scalar("""SELECT json_build_object(
        'caseId',fc.case_id,'requestId',q.ai_request_id,
        'executionId',e.execution_id,'reportId',r.report_id,
        'reportSource',r.report_source,'reportStatus',r.report_status,
        'requestVersion',q.detection_result_version,
        'executionVersion',e.detection_result_version,
        'reportVersion',r.detection_result_version)
        FROM ai_report_request q JOIN ai_report_execution e ON e.id=q.execution_id
        JOIN ai_report r ON r.execution_id=e.id JOIN fraud_case fc ON fc.id=r.fraud_case_id
        WHERE q.ai_request_id='%s'""" % ids["aiRequestId"]))
    assert stored["caseId"] == ids["caseId"]
    assert stored["requestId"] == ids["aiRequestId"]
    assert stored["executionId"] == ids["executionId"]
    assert stored["reportId"] == ids["reportId"]
    assert stored["requestVersion"] == stored["executionVersion"] == stored["reportVersion"] == ids["version"]
    assert stored["reportSource"] == ids["source"] and stored["reportStatus"] == ids["status"]
    print(json.dumps({"step": "report-linked", "exitCode": 0}), flush=True)


def dashboard_status(ctx):
    date = json.load(sys.stdin)["date"]
    if not re.fullmatch(r"20[0-9]{2}-[01][0-9]-[0-3][0-9]", date):
        raise RuntimeError("dashboard date invalid")
    value = ctx.sql_scalar("""SELECT COALESCE(json_object_agg(case_status,n),'{}'::json)::text
        FROM (SELECT case_status,count(DISTINCT case_id) n FROM fraud_case
        WHERE (created_at AT TIME ZONE 'Asia/Seoul')::date='%s' GROUP BY case_status) x""" % date)
    print(json.dumps(json.loads(value)), flush=True)


if __name__ == "__main__":
    try:
        context = Context()
        if sys.argv[1] == "generate":
            generate(context)
        elif sys.argv[1] == "validate":
            validate(context)
        elif sys.argv[1] == "relay":
            relay(context)
        elif sys.argv[1] == "verify-report":
            verify_report(context)
        elif sys.argv[1] == "dashboard-status":
            dashboard_status(context)
        else:
            raise SystemExit(2)
    except Exception as error:
        run_id = os.environ.get("FINGUARDOPS_E2E_RUN_ID", "")
        if len(run_id) == 32 and all(ch in "0123456789abcdef" for ch in run_id):
            directory = ROOT / "backend" / "build" / ("visual-capture-" + run_id)
            directory.mkdir(parents=True, exist_ok=True)
            frames = traceback.extract_tb(error.__traceback__)
            (directory / "failure.json").write_text(json.dumps({
                "phase": sys.argv[1] if len(sys.argv) > 1 else "unknown",
                "type": type(error).__name__,
                "code": str(error) if isinstance(error, gate.VerificationError)
                        or (isinstance(error, RuntimeError) and
                            re.fullmatch(r"visual API status [0-9]{3}, expected [0-9]{3}",
                                         str(error))) else None,
                "frames": [{"file": Path(frame.filename).name, "line": frame.lineno}
                           for frame in frames[-4:]],
            }), encoding="utf-8")
        raise

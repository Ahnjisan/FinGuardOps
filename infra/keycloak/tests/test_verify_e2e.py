import base64
import contextlib
import copy
import io
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import types
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_e2e


def publication_success_output(newline="\n"):
    return (
        "2026-09-26T07:54:13.123Z  INFO 1 --- [           main] "
        "c.a.b.r.o.RuleV1DefaultRuleSetPublicationRunner : "
        + verify_e2e.RULE_PUBLICATION_RUNNER_SUCCESS_EVIDENCE
        + " ruleVersionIds=[R001, R002, R003, R004] "
        "effectiveFrom=2026-09-26T07:55:13Z "
        "publishedAt=2026-09-26T07:54:14Z ruleSetVersion=1"
        + newline
    ).encode()


# A production-equivalent synthetic capture. It is NOT a copy of real Docker
# output: it is assembled from repository-owned producer contracts only —
# RuleV1DefaultRuleSetPublicationRunner.reportSuccess's message, the
# PublicationOutcome.PUBLISHED literal, RuleV1DefaultRuleSetPublicationResult's
# four UUID ruleVersionIds and 64-hex ruleSetVersion, Spring Boot 3.5.16's
# CONSOLE_LOG_PATTERN (%5p level, PID, "---", [%15.15t] thread and the
# %-40.40logger{39} truncation) and application.yml's
# logging.pattern.correlation field.
PRODUCTION_LOGGER = ".o.RuleV1DefaultRuleSetPublicationRunner"
PRODUCTION_CORRELATION = "[traceId=no-trace] "
PRODUCTION_RULE_VERSION_IDS = (
    "6c9f1a2b-1111-4111-8111-111111111111",
    "7a1b2c3d-2222-4222-8222-222222222222",
    "8c2d3e4f-3333-4333-8333-333333333333",
    "9e3f4a5b-4444-4444-8444-444444444444",
)
PRODUCTION_BANNER = (
    "\n"
    "  .   ____          _            __ _ _\n"
    " /\\\\ / ___'_ __ _ _(_)_ __  __ _ \\ \\ \\ \\\n"
    "( ( )\\___ | '_ | '_| | '_ \\/ _` | \\ \\ \\ \\\n"
    " \\\\/  ___)| |_)| | | | | || (_| |  ) ) ) )\n"
    "  '  |____| .__|_| |_|_| |_\\__, | / / / /\n"
    " =========|_|==============|___/=/_/_/_/\n"
    "\n"
    " :: Spring Boot ::               (v3.5.16)\n"
    "\n"
)


def production_boot_line(logger, message, level="INFO", timestamp="2026-09-26T12:45:14.512Z"):
    field = logger if len(logger) >= 40 else logger.ljust(40)
    return "%s %5s 1 --- [           main] %s%s : %s" % (
        timestamp, level, PRODUCTION_CORRELATION, field, message
    )


def production_success_message():
    return (
        verify_e2e.RULE_PUBLICATION_RUNNER_SUCCESS_EVIDENCE
        + " ruleVersionIds=[" + ", ".join(PRODUCTION_RULE_VERSION_IDS) + "]"
        + " effectiveFrom=2026-09-26T12:46:13Z"
        + " publishedAt=2026-09-26T12:45:14.512345Z"
        + " ruleSetVersion=" + "a" * 64
    )


def publication_production_success_output(newline="\n"):
    lines = [
        production_boot_line(
            "c.a.backend.BackendApplication",
            "Starting BackendApplication v0.0.1 using Java 21.0.5 with PID 1"
            " (/app/backend.jar started by root in /)",
            timestamp="2026-09-26T12:45:08.001Z",
        ),
        production_boot_line(
            "c.a.backend.BackendApplication",
            'The following 2 profiles are active: "local",'
            ' "rule-v1-default-publication"',
            timestamp="2026-09-26T12:45:08.004Z",
        ),
        production_boot_line(
            "faultConfigurationDelegate$Registrar",
            "Bootstrapping Spring Data JPA repositories in DEFAULT mode.",
            timestamp="2026-09-26T12:45:09.512Z",
        ),
        production_boot_line(
            "o.f.c.internal.license.VersionPrinter",
            "Flyway Community Edition 11.7.2 by Redgate",
            timestamp="2026-09-26T12:45:10.001Z",
        ),
        production_boot_line(
            "c.i.database.base.BaseDatabaseType",
            "Database: jdbc:postgresql://postgresql:5432/finguardops"
            " (PostgreSQL 17.6)",
            timestamp="2026-09-26T12:45:10.002Z",
        ),
        production_boot_line(
            "com.zaxxer.hikari.HikariDataSource",
            "HikariPool-1 - Starting...",
            timestamp="2026-09-26T12:45:10.500Z",
        ),
        production_boot_line(
            "o.hibernate.jpa.internal.util.LogHelper",
            "HHH000204: Processing PersistenceUnitInfo [name: default]",
            timestamp="2026-09-26T12:45:11.100Z",
        ),
        production_boot_line(
            "o.s.b.a.orm.jpa.JpaBaseConfiguration",
            "spring.jpa.open-in-view is disabled",
            level="WARN",
            timestamp="2026-09-26T12:45:13.900Z",
        ),
        production_boot_line(PRODUCTION_LOGGER, production_success_message()),
        production_boot_line(
            "c.a.backend.BackendApplication",
            "Started BackendApplication in 6.82 seconds (process running for 7.31)",
            timestamp="2026-09-26T12:45:14.600Z",
        ),
        production_boot_line(
            "com.zaxxer.hikari.HikariDataSource",
            "HikariPool-1 - Shutdown initiated...",
            timestamp="2026-09-26T12:45:14.700Z",
        ),
        production_boot_line(
            "com.zaxxer.hikari.HikariDataSource",
            "HikariPool-1 - Shutdown completed.",
            timestamp="2026-09-26T12:45:14.760Z",
        ),
    ]
    body = PRODUCTION_BANNER + "".join(line + "\n" for line in lines)
    if newline != "\n":
        body = body.replace("\n", newline)
    return body.encode()


def service(image=None, *, secrets=()):
    value = {
        "image": image,
        "network_mode": "service:backend",
        "depends_on": {},
        "user": "10001:10001",
        "read_only": True,
        "tmpfs": ["/tmp:rw,nosuid,nodev,noexec"],
        "cap_drop": ["ALL"],
        "security_opt": ["no-new-privileges:true"],
        "secrets": [
            {"source": name, "target": name, "mode": verify_e2e.EXPECTED_SECRET_MODES[name]}
            for name in secrets
        ],
        "volumes": [],
    }
    return value


def valid_config():
    backend = {
        "environment": {
            "FINGUARDOPS_SECURITY_ISSUER": verify_e2e.ISSUER,
            "FINGUARDOPS_SECURITY_JWK_SET_URI": verify_e2e.JWK_SET_URI,
            "FINGUARDOPS_SECURITY_INSECURE_LOOPBACK_JWK_ALLOWED": "true",
        },
        "ports": [{"host_ip": "127.0.0.1", "published": "8443", "target": 8443}],
        "networks": {"application": None, "observability": None, "prometheus-ui": None},
    }
    keycloak = {
        "image": verify_e2e.KEYCLOAK_IMAGE,
        "entrypoint": ["bash", "/opt/finguardops/start-keycloak.sh"],
        "command": [],
        "network_mode": "service:backend",
        "depends_on": {"backend": {"condition": "service_healthy"}},
        "environment": {
            "KC_HOSTNAME": "https://localhost:8443",
            "KC_HTTP_ENABLED": "true",
            "KC_HTTP_HOST": "0.0.0.0",
            "KC_HTTP_PORT": "8082",
            "KC_HTTPS_PORT": "8443",
            "KC_HTTPS_CERTIFICATE_FILE": "/run/secrets/keycloak_tls_certificate",
            "KC_HTTPS_CERTIFICATE_KEY_FILE": "/run/secrets/keycloak_tls_private_key",
            "KC_HTTP_MANAGEMENT_HOST": "127.0.0.1",
            "KC_HTTP_MANAGEMENT_PORT": "9000",
            "KC_HTTP_MANAGEMENT_SCHEME": "http",
            "KC_HEALTH_ENABLED": "true",
            "KC_DB": "dev-file",
        },
        "secrets": [
            {"source": name, "target": name, "mode": verify_e2e.EXPECTED_SECRET_MODES[name]}
            for name in verify_e2e.EXPECTED_SECRETS["keycloak"]
        ],
    }
    bootstrap = service(verify_e2e.HELPER_IMAGE, secrets=verify_e2e.EXPECTED_SECRETS["keycloak-bootstrap"])
    bootstrap["entrypoint"] = ["python", "-B", "/opt/finguardops/bootstrap.py"]
    bootstrap["command"] = ["reconcile"]
    bootstrap["volumes"] = [{"type": "bind", "source": "infra/keycloak/bootstrap.py", "target": "/opt/finguardops/bootstrap.py", "read_only": True}]
    bootstrap["depends_on"] = {"keycloak": {"condition": "service_healthy"}}
    bootstrap["environment"] = {"KEYCLOAK_ADMIN_BASE_URL": verify_e2e.INTERNAL_BASE_URL}
    verifier = service(verify_e2e.HELPER_IMAGE, secrets=verify_e2e.EXPECTED_SECRETS["keycloak-verify"])
    verifier["entrypoint"] = ["python", "-B", "/opt/finguardops/verify_e2e.py"]
    verifier["command"] = ["runtime"]
    verifier["volumes"] = [{"type": "bind", "source": "infra/keycloak/verify_e2e.py", "target": "/opt/finguardops/verify_e2e.py", "read_only": True}]
    verifier["depends_on"] = {
        "keycloak-bootstrap": {"condition": "service_completed_successfully"},
        "external-risk-mock": {"condition": "service_healthy"},
    }
    verifier["environment"] = {
        "KEYCLOAK_INTERNAL_BASE_URL": verify_e2e.INTERNAL_BASE_URL,
        "KEYCLOAK_MANAGEMENT_BASE_URL": verify_e2e.MANAGEMENT_BASE_URL,
    }
    run_fixture = service(
        verify_e2e.HELPER_IMAGE,
        secrets=verify_e2e.EXPECTED_SECRETS["keycloak-run-fixture"],
    )
    run_fixture["entrypoint"] = ["python", "-B", "/opt/finguardops/verify_e2e.py"]
    run_fixture["command"] = ["run-fixture"]
    run_fixture["volumes"] = [
        {"type": "bind", "source": "infra/keycloak/verify_e2e.py", "target": "/opt/finguardops/verify_e2e.py", "read_only": True},
        {"type": "bind", "source": "C:/Temp/finguardops-keycloak-e2e-fixture-" + "0" * 32, "target": "/finguardops/fixture", "read_only": False},
    ]
    run_fixture["environment"] = {
        "PYTHONDONTWRITEBYTECODE": "1",
        "KEYCLOAK_INTERNAL_BASE_URL": verify_e2e.INTERNAL_BASE_URL,
        "KEYCLOAK_MANAGEMENT_BASE_URL": verify_e2e.MANAGEMENT_BASE_URL,
        "FINGUARDOPS_E2E_RUN_ID": "0" * 32,
        "FINGUARDOPS_E2E_REPOSITORY_ID": "a" * 64,
        "FINGUARDOPS_E2E_REVISION": "b" * 40,
        "FINGUARDOPS_E2E_SOURCE_TREE": "c" * 40,
        verify_e2e.COMPOSE_PROJECT_ENVIRONMENT: verify_e2e.RUN_FIXTURE_PROJECT,
        verify_e2e.FIXTURE_PLAN_ENVIRONMENT: "",
    }
    run_fixture["depends_on"] = {"backend": {"condition": "service_started"}}
    keycloak["volumes"] = [
        {"type": "volume", "source": "keycloak-data", "target": "/opt/keycloak/data"},
        {"type": "bind", "source": "infra/keycloak/realm/finguardops-local-realm.json", "target": "/opt/keycloak/data/import/finguardops-local-realm.json", "read_only": True},
        {"type": "bind", "source": "infra/keycloak/start-keycloak.sh", "target": "/opt/finguardops/start-keycloak.sh", "read_only": True},
    ]
    services = {
        "backend": backend,
        "keycloak": keycloak,
        "keycloak-bootstrap": bootstrap,
        "keycloak-verify": verifier,
        "keycloak-run-fixture": run_fixture,
    }
    for name in verify_e2e.EXPECTED_KEYCLOAK_SERVICES - set(services):
        services[name] = {}
    services["ai-service"]["command"] = list(
        verify_e2e.EXPECTED_AI_SERVICE_COMMAND
    )
    return {
        "services": services,
        "networks": {"application": {"internal": True}, "observability": {"internal": True}, "prometheus-ui": {}},
        "volumes": {name: {} for name in verify_e2e.EXPECTED_MERGED_NAMED_VOLUMES},
        "secrets": {
            name: {"file": path}
            for name, path in verify_e2e.EXPECTED_SECRET_FILES.items()
        },
    }


def valid_owner_environment():
    run_id = "0123456789abcdef0123456789abcdef"
    commit_sha = "b" * 40
    suffix = f"e2e-{commit_sha[:12]}-{run_id}"
    return {
        "FINGUARDOPS_E2E_BACKEND_IMAGE": f"finguardops-backend:{suffix}",
        "FINGUARDOPS_E2E_AI_SERVICE_IMAGE": f"finguardops-ai-service:{suffix}",
        "FINGUARDOPS_E2E_REVISION": commit_sha,
        "FINGUARDOPS_E2E_SOURCE_TREE": "c" * 40,
        "FINGUARDOPS_E2E_RUN_ID": run_id,
        "FINGUARDOPS_E2E_REPOSITORY_ID": "a" * 64,
    }


def valid_run_fixture_environment():
    return valid_owner_environment() | {
        verify_e2e.COMPOSE_PROJECT_ENVIRONMENT: verify_e2e.RUN_FIXTURE_PROJECT,
    }


def valid_realm():
    return json.loads((Path(__file__).resolve().parents[1] / "realm/finguardops-local-realm.json").read_text("utf-8"))


def token(payload_overrides=None, header_overrides=None):
    header = {"alg": "RS256", "kid": "kid-1"}
    payload = {
        "iss": verify_e2e.ISSUER,
        "aud": verify_e2e.AUDIENCE,
        "sub": "32a6a5db-71e4-4e58-8b3f-ec8c2c07b69a",
        "principal_type": "SERVICE",
        "roles": ["TRANSACTION_INGESTOR"],
        "iat": 100,
        "exp": 1000,
    }
    header.update(header_overrides or {})
    payload.update(payload_overrides or {})

    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=").decode()

    return encode(header) + "." + encode(payload) + ".signature"


def valid_plan():
    return {
        "transactionId": "32a6a5db-71e4-4e58-8b3f-ec8c2c07b69a",
        "passwordEventId": "e54cbf7e-d857-4ca0-bff3-8d4321b7722a",
        "transferLimitEventId": "9334da6a-1a03-44fd-a71d-f59a44a94225",
        "idempotencyKey": "kc241-" + "a" * 32,
        "duplicateIdempotencyKey": "kc241-" + "b" * 32,
        "customerRef": "kc241-customer-123456789abc",
        "senderRef": "kc241-sender-123456789abc",
        "recipientRef": "kc241-recipient-123456789abc",
        "passwordOccurredAt": "2026-09-05T01:00:03Z",
        "transferLimitOccurredAt": "2026-09-05T01:01:03Z",
        "transactionOccurredAt": "2026-09-05T01:02:03Z",
    }


def valid_fixture_identity():
    owner = valid_owner_environment()
    return {
        "schemaVersion": 1,
        "runId": owner["FINGUARDOPS_E2E_RUN_ID"],
        "repositoryId": owner["FINGUARDOPS_E2E_REPOSITORY_ID"],
        "commitSha": owner["FINGUARDOPS_E2E_REVISION"],
        "treeSha": owner["FINGUARDOPS_E2E_SOURCE_TREE"],
        "composeProject": verify_e2e.RUN_FIXTURE_PROJECT,
        "transactionId": valid_plan()["transactionId"],
        "caseId": "d20a2f8d-7b67-4cdd-8b73-a8fc4b1f2703",
        "expectedRiskLevel": "HIGH",
        "expectedResponseOutcome": "ADDITIONAL_AUTH_REQUIRED",
        "expectedInitialCaseStatus": "OPEN",
    }


def snapshot_fixture(rows_by_table=None):
    rows_by_table = rows_by_table or {}
    return {
        table: verify_e2e.table_snapshot(table, tuple(rows_by_table.get(table, ())))
        for table in verify_e2e.BUSINESS_TABLES
    }


def valid_run_fixture_state():
    owner = valid_owner_environment()
    return {
        "schemaVersion": 1,
        "runId": owner["FINGUARDOPS_E2E_RUN_ID"],
        "repositoryId": owner["FINGUARDOPS_E2E_REPOSITORY_ID"],
        "commitSha": owner["FINGUARDOPS_E2E_REVISION"],
        "treeSha": owner["FINGUARDOPS_E2E_SOURCE_TREE"],
        "composeProject": verify_e2e.RUN_FIXTURE_PROJECT,
        "plan": valid_plan(),
        "database": verify_e2e.snapshot_to_state(snapshot_fixture()),
        "dependencies": [0, 0],
        "metrics": [0.0, 0.0],
    }


def snapshot_output(rows_by_table=None):
    rows_by_table = rows_by_table or {}
    lines = []
    for table in verify_e2e.BUSINESS_TABLES:
        name = table.encode("ascii")
        lines.append(verify_e2e.SNAPSHOT_BEGIN_PREFIX + name)
        lines.extend(rows_by_table.get(table, ()))
        lines.append(verify_e2e.SNAPSHOT_END_PREFIX + name)
    return b"\n".join(lines) + b"\n"


class VerifyTests(unittest.TestCase):
    def assert_static_failure(self, mutate, code):
        config = valid_config()
        mutate(config)
        with self.assertRaisesRegex(verify_e2e.VerificationError, code):
            verify_e2e.validate_static(config, valid_realm())

    def test_static_valid_configuration(self):
        verify_e2e.validate_static(valid_config(), valid_realm())

    def test_rule_v2_access_log_must_be_explicitly_enabled(self):
        for command in (
            ["--host", "0.0.0.0", "--port", "8000"],
            ["--host", "0.0.0.0", "--port", "8000", "--no-access-log"],
            ["--access-log", "--host", "0.0.0.0", "--port", "8000"],
        ):
            with self.subTest(command=command):
                self.assert_static_failure(
                    lambda config, value=command: config["services"]["ai-service"].update(
                        {"command": value}
                    ),
                    "STATIC_RULE_ACCESS_LOG",
                )

    def test_compose_json_octal_secret_modes_are_valid(self):
        config = valid_config()
        for service_name in verify_e2e.EXPECTED_SECRETS:
            for secret in config["services"][service_name]["secrets"]:
                secret["mode"] = "0" + format(secret["mode"], "o")
        verify_e2e.validate_static(config, valid_realm())

    def test_fixture_and_keycloak_together_rejected(self):
        self.assert_static_failure(lambda c: c["services"].update({"local-jwt-fixture": {}}), "STATIC_MULTIPLE_ISSUERS")

    def test_fixture_only_contract_remains_valid(self):
        config = {
            "services": {
                "backend": {"environment": {"FINGUARDOPS_SECURITY_ISSUER": verify_e2e.FIXTURE_ISSUER, "FINGUARDOPS_SECURITY_JWK_SET_URI": verify_e2e.FIXTURE_JWK}},
                "local-jwt-fixture": {},
            }
        }
        verify_e2e.validate_static(config)

    def test_issuer_jwk_mix_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["backend"]["environment"].update({"FINGUARDOPS_SECURITY_JWK_SET_URI": verify_e2e.FIXTURE_JWK}), "STATIC_ISSUER_JWK_MIXED")

    def test_image_digest_mutation(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak"].update({"image": verify_e2e.KEYCLOAK_IMAGE[:-1] + "0"}), "STATIC_KEYCLOAK_IMAGE")

    def test_keycloak_privileged_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak"].update({"privileged": True}), "STATIC_KEYCLOAK_PRIVILEGED")

    def test_keycloak_cap_add_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak"].update({"cap_add": ["NET_ADMIN"]}), "STATIC_KEYCLOAK_CAP_ADD")

    def test_bootstrap_cap_add_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak-bootstrap"].update({"cap_add": ["NET_ADMIN"]}), "STATIC_HELPER_CAP_ADD")

    def test_verifier_cap_add_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak-verify"].update({"cap_add": ["NET_ADMIN"]}), "STATIC_HELPER_CAP_ADD")

    def test_keycloak_docker_socket_rejected(self):
        self.assert_static_failure(
            lambda c: c["services"]["keycloak"]["volumes"].append(
                {"type": "bind", "source": "/var/run/docker.sock", "target": "/var/run/docker.sock", "read_only": False}
            ),
            "STATIC_KEYCLOAK_DOCKER_SOCKET",
        )

    def test_keycloak_start_dev_command_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak"].update({"command": ["start-dev"]}), "STATIC_KEYCLOAK_COMMAND")

    def test_keycloak_health_missing_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak"]["environment"].pop("KC_HEALTH_ENABLED"), "STATIC_KEYCLOAK_ENV_ALLOWLIST")

    def test_keycloak_health_false_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak"]["environment"].update({"KC_HEALTH_ENABLED": "false"}), "STATIC_KEYCLOAK_HEALTH")

    def test_keycloak_https_port_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak"]["environment"].update({"KC_HTTPS_PORT": "9443"}), "STATIC_KEYCLOAK_HTTPS_PORT")

    def test_backend_keycloak_dependency_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["backend"].update({"depends_on": {"keycloak": {"condition": "service_healthy"}}}), "STATIC_BACKEND_DEPENDENCY")

    def test_verifier_requires_external_risk_fixture(self):
        self.assert_static_failure(
            lambda c: c["services"]["keycloak-verify"]["depends_on"].pop("external-risk-mock"),
            "STATIC_DEPENDENCY",
        )

    def test_realm_disabled_rejected(self):
        realm = valid_realm()
        realm["enabled"] = False
        with self.assertRaisesRegex(verify_e2e.VerificationError, "STATIC_REALM_CONTRACT"):
            verify_e2e.validate_static(valid_config(), realm)

    def test_user_refresh_tokens_missing_or_true_rejected(self):
        for action in ("missing", "true"):
            realm = valid_realm()
            frontend = next(client for client in realm["clients"] if client["clientId"] == "finguardops-frontend")
            if action == "missing":
                frontend["attributes"].pop("use.refresh.tokens")
            else:
                frontend["attributes"]["use.refresh.tokens"] = "true"
            with self.subTest(action=action), self.assertRaisesRegex(
                verify_e2e.VerificationError, "STATIC_USER_CLIENT_CONTRACT"
            ):
                verify_e2e.validate_static(valid_config(), realm)

    def test_user_client_secret_and_extra_attribute_rejected(self):
        for key, value in (("secret", "not-a-real-secret"), ("unexpected.attribute", "true")):
            realm = valid_realm()
            frontend = next(client for client in realm["clients"] if client["clientId"] == "finguardops-frontend")
            if key == "secret":
                frontend[key] = value
                expected = "STATIC_REALM_SECRET_PRESENT"
            else:
                frontend["attributes"][key] = value
                expected = "STATIC_USER_CLIENT_CONTRACT"
            with self.subTest(key=key), self.assertRaisesRegex(verify_e2e.VerificationError, expected):
                verify_e2e.validate_static(valid_config(), realm)

    def test_user_optional_scope_requires_exact_profile_and_rejects_default_or_extra_scope(self):
        mutations = (
            lambda client: client.update({"optionalClientScopes": []}),
            lambda client: client.update({"optionalClientScopes": ["profile", "email"]}),
            lambda client: client.update(
                {
                    "defaultClientScopes": client["defaultClientScopes"] + ["profile"],
                    "optionalClientScopes": [],
                }
            ),
            lambda client: client.update({"optionalClientScopes": ["openid"]}),
        )
        for mutate in mutations:
            realm = valid_realm()
            frontend = next(
                client for client in realm["clients"] if client["clientId"] == "finguardops-frontend"
            )
            mutate(frontend)
            with self.subTest(mutate=mutate), self.assertRaisesRegex(
                verify_e2e.VerificationError, "STATIC_USER_CLIENT_CONTRACT"
            ):
                verify_e2e.validate_static(valid_config(), realm)

    def test_openid_or_profile_scope_object_is_rejected(self):
        for name in ("openid", "profile"):
            realm = valid_realm()
            realm["clientScopes"].append({"name": name, "protocol": "openid-connect"})
            with self.subTest(name=name), self.assertRaisesRegex(
                verify_e2e.VerificationError, "STATIC_CLIENT_SCOPE_OBJECTS"
            ):
                verify_e2e.validate_static(valid_config(), realm)

    def test_stock_profile_scope_mapper_mutations_are_rejected(self):
        mutations = (
            lambda scope: scope["attributes"].update({"include.in.token.scope": "false"}),
            lambda scope: scope["protocolMappers"].pop(),
            lambda scope: next(
                mapper for mapper in scope["protocolMappers"] if mapper["name"] == "username"
            )["config"].update({"claim.name": "username"}),
        )
        for mutate in mutations:
            realm = valid_realm()
            profile = next(scope for scope in realm["clientScopes"] if scope["name"] == "profile")
            mutate(profile)
            with self.subTest(mutate=mutate), self.assertRaisesRegex(
                verify_e2e.VerificationError, "STATIC_STOCK_PROFILE_SCOPE"
            ):
                verify_e2e.validate_static(valid_config(), realm)

    def test_user_subject_mapper_missing_or_changed_is_rejected(self):
        mutations = (
            lambda mappers: mappers.pop(0),
            lambda mappers: mappers[0].update({"protocolMapper": "oidc-hardcoded-claim-mapper"}),
            lambda mappers: mappers[0]["config"].update({"id.token.claim": "true"}),
        )
        for mutate in mutations:
            realm = valid_realm()
            scope = next(
                item for item in realm["clientScopes"] if item["name"] == "finguardops-user-claims"
            )
            mutate(scope["protocolMappers"])
            with self.subTest(mutate=mutate), self.assertRaisesRegex(
                verify_e2e.VerificationError, "STATIC_USER_SUBJECT_MAPPER"
            ):
                verify_e2e.validate_static(valid_config(), realm)

    def test_service_scope_audience_role_and_refresh_contract_mutations_are_rejected(self):
        mutations = (
            lambda client: client.update({"defaultClientScopes": ["finguardops-backend-audience"]}),
            lambda client: client.update({"optionalClientScopes": ["profile"]}),
            lambda client: client["attributes"].update({"use.refresh.tokens": "false"}),
        )
        for client_id in verify_e2e.SERVICE_CLIENT_SCOPES:
            for mutate in mutations:
                realm = valid_realm()
                client = next(item for item in realm["clients"] if item["clientId"] == client_id)
                mutate(client)
                with self.subTest(client_id=client_id, mutate=mutate), self.assertRaisesRegex(
                    verify_e2e.VerificationError, "STATIC_SERVICE_CLIENT_CONTRACT"
                ):
                    verify_e2e.validate_static(valid_config(), realm)

    def test_service_refresh_token_response_is_rejected(self):
        with mock.patch.object(
            verify_e2e,
            "http_json",
            return_value=(200, {"access_token": "header.payload.signature", "refresh_token": "sentinel"}),
        ), self.assertRaisesRegex(verify_e2e.VerificationError, "SERVICE_REFRESH_TOKEN_PRESENT"):
            verify_e2e.token_for("finguardops-transaction-ingestor", "x" * 32)

    def test_user_uuid_role_and_import_credential_mutations_rejected(self):
        mutations = (
            lambda user: user.update({"id": "581f76f8-64bd-4bda-99fb-2c338e96d92a"}),
            lambda user: user.update({"realmRoles": ["FDS_ANALYST", "FDS_VIEWER"]}),
            lambda user: user.update({"credentials": [{"type": "password"}]}),
            lambda user: user.update({"requiredActions": ["UPDATE_PASSWORD"]}),
            lambda user: user.update({"email": "missing.invalid-profile"}),
        )
        for mutate in mutations:
            realm = valid_realm()
            mutate(realm["users"][0])
            with self.subTest(mutate=mutate), self.assertRaisesRegex(
                verify_e2e.VerificationError, "STATIC_USER_CONTRACT"
            ):
                verify_e2e.validate_static(valid_config(), realm)

    def test_bootstrap_source_writable_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak-bootstrap"]["volumes"][0].update({"read_only": False}), "STATIC_KEYCLOAK_BOOTSTRAP_MOUNT")

    def test_verifier_source_writable_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak-verify"]["volumes"][0].update({"read_only": False}), "STATIC_KEYCLOAK_VERIFY_MOUNT")

    def test_keycloak_wrapper_source_writable_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak"]["volumes"][2].update({"read_only": False}), "STATIC_KEYCLOAK_MOUNT")

    def test_realm_source_writable_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak"]["volumes"][1].update({"read_only": False}), "STATIC_KEYCLOAK_MOUNT")

    def test_unknown_keycloak_environment_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak"]["environment"].update({"KC_PROXY_HEADERS": "xforwarded"}), "STATIC_KEYCLOAK_ENV_ALLOWLIST")

    def test_static_bootstrap_secret_environment_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak"]["environment"].update({"KC_BOOTSTRAP_ADMIN_CLIENT_SECRET": "not-a-real-secret"}), "STATIC_KEYCLOAK_ENV_ALLOWLIST")

    def test_keycloak_entrypoint_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak"].update({"entrypoint": ["/opt/keycloak/bin/kc.sh"]}), "STATIC_KEYCLOAK_ENTRYPOINT")

    def test_keycloak_extra_command_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak"].update({"command": ["--https-port=9443"]}), "STATIC_KEYCLOAK_COMMAND")

    def test_helper_extra_command_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak-bootstrap"].update({"command": ["reconcile", "--verbose"]}), "STATIC_KEYCLOAK_BOOTSTRAP_COMMAND")

    def test_helper_read_only_false_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak-bootstrap"].update({"read_only": False}), "STATIC_HELPER_READ_ONLY")

    def test_helper_user_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak-bootstrap"].update({"user": "0:0"}), "STATIC_HELPER_USER")

    def test_helper_cap_drop_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak-bootstrap"].update({"cap_drop": []}), "STATIC_HELPER_CAPABILITIES")

    def test_helper_no_new_privileges_rejected(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak-bootstrap"].update({"security_opt": []}), "STATIC_HELPER_PRIVILEGES")

    def test_windows_docker_named_pipe_rejected(self):
        self.assert_static_failure(
            lambda c: c["services"]["keycloak"]["volumes"].append(
                {"type": "bind", "source": "//./pipe/docker_engine", "target": "//./pipe/docker_engine", "read_only": False}
            ),
            "STATIC_KEYCLOAK_DOCKER_SOCKET",
        )

    def test_host_port_mutation(self):
        self.assert_static_failure(lambda c: c["services"]["backend"].update({"ports": [{"host_ip": "0.0.0.0", "published": 8443, "target": 8443}]}), "STATIC_HOST_PORT")

    def test_management_bind_mutation(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak"]["environment"].update({"KC_HTTP_MANAGEMENT_HOST": "0.0.0.0"}), "STATIC_KEYCLOAK_ENV_VALUE")

    def test_http_listener_host_must_support_public_publish(self):
        for value in ("127.0.0.1", "", "localhost"):
            with self.subTest(value=value):
                self.assert_static_failure(
                    lambda c, value=value: c["services"]["keycloak"]["environment"].update({"KC_HTTP_HOST": value}),
                    "STATIC_KEYCLOAK_ENV_VALUE",
                )
        self.assert_static_failure(
            lambda c: c["services"]["keycloak"]["environment"].pop("KC_HTTP_HOST"),
            "STATIC_KEYCLOAK_ENV_ALLOWLIST",
        )

    def test_internal_ports_must_not_be_published(self):
        for port in (8082, 9000):
            with self.subTest(port=port):
                self.assert_static_failure(
                    lambda c, port=port: c["services"]["backend"]["ports"].append(
                        {"host_ip": "127.0.0.1", "published": port, "target": port}
                    ),
                    "STATIC_HOST_PORT|STATIC_INTERNAL_PORT_PUBLISHED",
                )

    def test_backend_jwk_must_use_namespace_loopback(self):
        self.assert_static_failure(
            lambda c: c["services"]["backend"]["environment"].update(
                {"FINGUARDOPS_SECURITY_JWK_SET_URI": "http://backend:8082/realms/finguardops-local/protocol/openid-connect/certs"}
            ),
            "STATIC_ISSUER_JWK_MIXED",
        )

    def test_bootstrap_admin_must_use_namespace_loopback(self):
        self.assert_static_failure(
            lambda c: c["services"]["keycloak-bootstrap"]["environment"].update(
                {"KEYCLOAK_ADMIN_BASE_URL": "http://backend:8082"}
            ),
            "STATIC_ADMIN_BASE_URL",
        )

    def test_verifier_internal_urls_must_use_namespace_loopback(self):
        self.assert_static_failure(
            lambda c: c["services"]["keycloak-verify"]["environment"].update(
                {"KEYCLOAK_INTERNAL_BASE_URL": "http://backend:8082"}
            ),
            "STATIC_VERIFIER_BASE_URL",
        )

    def test_unapproved_named_volumes_are_rejected(self):
        for name in ("keycloak-public", "another-volume"):
            with self.subTest(name=name):
                self.assert_static_failure(
                    lambda c, name=name: c["volumes"].update({name: {}}),
                    "STATIC_NAMED_VOLUME_SET",
                )

    def test_helper_named_or_shared_storage_is_rejected(self):
        self.assert_static_failure(
            lambda c: c["services"]["keycloak-verify"]["volumes"].append(
                {"type": "volume", "source": "keycloak-data", "target": "/state"}
            ),
            "STATIC_KEYCLOAK_VERIFY_MOUNT|STATIC_HELPER_NAMED_VOLUME",
        )
        self.assert_static_failure(
            lambda c: c["services"]["keycloak-verify"]["volumes"].append("./bootstrap.py:/shared:ro"),
            "STATIC_KEYCLOAK_VERIFY_MOUNT|STATIC_HELPER_SHARED_STORAGE",
        )

    def test_proxy_or_unexpected_service_is_rejected(self):
        self.assert_static_failure(
            lambda c: c["services"].update({"tls-proxy": {"image": "unexpected"}}),
            "STATIC_SERVICE_SET",
        )

    def test_missing_secret_mount(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak-verify"]["secrets"].pop(), "STATIC_SECRET_BOUNDARY")

    def test_excessive_secret_mount(self):
        self.assert_static_failure(
            lambda c: c["services"]["keycloak-verify"]["secrets"].append(
                {"source": "keycloak_bootstrap_admin_secret", "target": "keycloak_bootstrap_admin_secret", "mode": 256}
            ),
            "STATIC_SECRET_BOUNDARY",
        )

    def test_user_password_mount_is_bootstrap_only_and_read_only(self):
        self.assert_static_failure(
            lambda c: c["services"]["keycloak-bootstrap"]["secrets"].remove(
                next(item for item in c["services"]["keycloak-bootstrap"]["secrets"] if item["source"] == "user_password")
            ),
            "STATIC_SECRET_BOUNDARY",
        )
        for service_name in ("keycloak", "keycloak-verify"):
            self.assert_static_failure(
                lambda c, service_name=service_name: c["services"][service_name]["secrets"].append(
                    {"source": "user_password", "target": "user_password", "mode": 256}
                ),
                "STATIC_SECRET_BOUNDARY",
            )
        self.assert_static_failure(
            lambda c: next(
                item for item in c["services"]["keycloak-bootstrap"]["secrets"] if item["source"] == "user_password"
            ).update({"mode": 292}),
            "STATIC_SECRET_BOUNDARY",
        )

    def test_user_password_secret_definition_is_exact(self):
        self.assert_static_failure(lambda c: c["secrets"].pop("user_password"), "STATIC_SECRET_DEFINITION")
        self.assert_static_failure(
            lambda c: c["secrets"]["user_password"].update({"file": "../outside/user-password"}),
            "STATIC_SECRET_DEFINITION",
        )

    def test_privilege_mutation(self):
        self.assert_static_failure(lambda c: c["services"]["keycloak-bootstrap"].update({"privileged": True}), "STATIC_HELPER_PRIVILEGED")

    def test_raw_string_audience_succeeds(self):
        verify_e2e.validate_token(token(), "TRANSACTION_INGESTOR", {"kid-1"}, current_time=500)

    def test_singleton_array_is_backend_compatible(self):
        verify_e2e.validate_token(token({"aud": [verify_e2e.AUDIENCE]}), "TRANSACTION_INGESTOR", {"kid-1"}, current_time=500, require_raw_string_audience=False)

    def test_additional_duplicate_and_malformed_audience_rejected(self):
        invalid = [
            [verify_e2e.AUDIENCE, "other"],
            [verify_e2e.AUDIENCE, verify_e2e.AUDIENCE],
            [],
            {"value": verify_e2e.AUDIENCE},
            " " + verify_e2e.AUDIENCE,
        ]
        for audience in invalid:
            with self.assertRaises(verify_e2e.VerificationError):
                verify_e2e.normalize_audience(audience)

    def test_role_mixing_rejected(self):
        with self.assertRaisesRegex(verify_e2e.VerificationError, "TOKEN_ROLES_INVALID"):
            verify_e2e.validate_token(token({"roles": ["TRANSACTION_INGESTOR", "FDS_VIEWER"]}), "TRANSACTION_INGESTOR", {"kid-1"}, current_time=500)

    def test_uuid_issuer_alg_kid_and_time_counterexamples(self):
        mutations = [
            ({"sub": "32A6A5DB-71E4-4E58-8B3F-EC8C2C07B69A"}, {}, "TOKEN_SUBJECT_INVALID"),
            ({"iss": verify_e2e.ISSUER + "/"}, {}, "TOKEN_ISSUER_INVALID"),
            ({}, {"alg": "HS256"}, "TOKEN_HEADER_INVALID"),
            ({}, {"kid": ""}, "TOKEN_HEADER_INVALID"),
            ({"exp": 1001}, {}, "TOKEN_TIME_LIFETIME_INVALID"),
            ({"iat": True}, {}, "TOKEN_TIME_TYPE_INVALID"),
        ]
        for payload_change, header_change, code in mutations:
            with self.subTest(code=code), self.assertRaisesRegex(
                verify_e2e.VerificationError, "^" + code + "$"
            ):
                verify_e2e.validate_token(token(payload_change, header_change), "TRANSACTION_INGESTOR", {"kid-1"}, current_time=500)

    def test_token_time_boundaries_and_stale_now_regression(self):
        verify_e2e.validate_token(token({"iat": 500, "exp": 1400, "nbf": 500}), "TRANSACTION_INGESTOR", {"kid-1"}, current_time=500)
        verify_e2e.validate_token(token({"iat": 100, "exp": 501}), "TRANSACTION_INGESTOR", {"kid-1"}, current_time=500)
        invalid = [
            ({"iat": 501, "exp": 1000}, 500, "TOKEN_TIME_IAT_FUTURE"),
            ({"iat": 100, "exp": 500}, 500, "TOKEN_TIME_EXPIRED"),
            ({"iat": 100, "exp": 1000, "nbf": 501}, 500, "TOKEN_TIME_NBF_FUTURE"),
        ]
        for payload, now, code in invalid:
            with self.subTest(code=code), self.assertRaisesRegex(verify_e2e.VerificationError, code):
                verify_e2e.validate_token(token(payload), "TRANSACTION_INGESTOR", {"kid-1"}, current_time=now)
        with self.assertRaisesRegex(verify_e2e.VerificationError, "TOKEN_TIME_IAT_FUTURE"):
            verify_e2e.validate_token(token({"iat": 101, "exp": 1000}), "TRANSACTION_INGESTOR", {"kid-1"}, current_time=100)
        verify_e2e.validate_token(token({"iat": 101, "exp": 1000}), "TRANSACTION_INGESTOR", {"kid-1"}, current_time=101)

    def test_exp_not_after_iat_is_its_own_fixed_identity(self):
        for exp in (500, 499, 0):
            with self.subTest(exp=exp), self.assertRaisesRegex(
                verify_e2e.VerificationError, "^TOKEN_TIME_ORDER_INVALID$"
            ):
                verify_e2e.validate_token(
                    token({"iat": 500, "exp": exp}), "TRANSACTION_INGESTOR", {"kid-1"},
                    current_time=500,
                )

    def test_lifetime_upper_bound_is_exact_and_inclusive(self):
        with self.assertRaisesRegex(
            verify_e2e.VerificationError, "^TOKEN_TIME_LIFETIME_INVALID$"
        ):
            verify_e2e.validate_token(
                token({"iat": 100, "exp": 1001}), "TRANSACTION_INGESTOR", {"kid-1"},
                current_time=500,
            )
        for exp in (1000, 999):
            with self.subTest(exp=exp):
                verify_e2e.validate_token(
                    token({"iat": 100, "exp": exp}), "TRANSACTION_INGESTOR", {"kid-1"},
                    current_time=500,
                )

    def test_remaining_token_time_identities_are_unchanged(self):
        type_invalid = (
            {"iat": None},
            {"exp": None},
            {"iat": True},
            {"exp": False},
            {"iat": 100.0},
            {"exp": "1000"},
        )
        for payload in type_invalid:
            with self.subTest(payload=tuple(payload)), self.assertRaisesRegex(
                verify_e2e.VerificationError, "^TOKEN_TIME_TYPE_INVALID$"
            ):
                verify_e2e.validate_token(
                    token(payload), "TRANSACTION_INGESTOR", {"kid-1"}, current_time=500
                )
        preserved = (
            ({"iat": 501, "exp": 1000}, 500, "TOKEN_TIME_IAT_FUTURE"),
            ({"iat": 100, "exp": 500}, 500, "TOKEN_TIME_EXPIRED"),
            ({"iat": 100, "exp": 1000, "nbf": 1001}, 500, "TOKEN_TIME_NBF_INVALID"),
            ({"iat": 100, "exp": 1000, "nbf": True}, 500, "TOKEN_TIME_NBF_INVALID"),
            ({"iat": 100, "exp": 1000, "nbf": "100"}, 500, "TOKEN_TIME_NBF_INVALID"),
            ({"iat": 100, "exp": 1000, "nbf": 501}, 500, "TOKEN_TIME_NBF_FUTURE"),
        )
        for payload, now, code in preserved:
            with self.subTest(code=code), self.assertRaisesRegex(
                verify_e2e.VerificationError, "^" + code + "$"
            ):
                verify_e2e.validate_token(
                    token(payload), "TRANSACTION_INGESTOR", {"kid-1"}, current_time=now
                )

    def test_realm_lifespan_and_verifier_maximum_do_not_drift(self):
        # Keycloak reads the clock twice while building a token: iat comes from
        # JsonWebToken.issuedNow() and exp from getTokenExpiration(), so an
        # issued token measures configured + D seconds where D is the number of
        # whole-second boundaries crossed between the two reads. The configured
        # value therefore sits exactly one second below the verifier maximum, so
        # the ordinary D=1 crossing still lands on the maximum. Nothing here
        # claims D can never exceed 1; D>=2 must stay a verifier failure.
        configured = valid_realm()["accessTokenLifespan"]
        verifier_maximum = 900
        self.assertEqual(899, configured)
        self.assertEqual(verifier_maximum, configured + 1)
        for lifetime in (configured, configured + 1):
            with self.subTest(lifetime=lifetime):
                verify_e2e.validate_token(
                    token({"iat": 100, "exp": 100 + lifetime}),
                    "TRANSACTION_INGESTOR", {"kid-1"}, current_time=500,
                )
        with self.assertRaisesRegex(
            verify_e2e.VerificationError, "^TOKEN_TIME_LIFETIME_INVALID$"
        ):
            verify_e2e.validate_token(
                token({"iat": 100, "exp": 100 + configured + 2}),
                "TRANSACTION_INGESTOR", {"kid-1"}, current_time=500,
            )

    def test_static_realm_contract_accepts_the_configured_margin(self):
        configured = valid_realm()["accessTokenLifespan"]
        self.assertEqual(899, configured)
        verify_e2e.validate_static(valid_config(), valid_realm())
        for rejected in (0, 901):
            realm = valid_realm()
            realm["accessTokenLifespan"] = rejected
            with self.subTest(lifespan=rejected), self.assertRaisesRegex(
                verify_e2e.VerificationError, "^STATIC_REALM_CONTRACT$"
            ):
                verify_e2e.validate_static(valid_config(), realm)
        for accepted in (configured, 900):
            realm = valid_realm()
            realm["accessTokenLifespan"] = accepted
            with self.subTest(lifespan=accepted):
                verify_e2e.validate_static(valid_config(), realm)

    def test_issued_lifetime_bound_holds_for_the_configured_margin(self):
        # exp - iat == configured + D, derived from Keycloak 26.7.3:
        #   iat = floor(M1 / 1000)                       (Time.currentTime())
        #   exp = floor((M2 + 1000 * L) / 1000)          (Time.currentTimeMillis())
        # D = floor(M2/1000) - floor(M1/1000) >= 0 and is NOT bounded by source.
        configured = valid_realm()["accessTokenLifespan"]
        accepted_boundaries = (0, 1)
        rejected_boundaries = (2, 3)
        for boundaries in accepted_boundaries:
            with self.subTest(accepted_boundaries=boundaries):
                verify_e2e.validate_token(
                    token({"iat": 100, "exp": 100 + configured + boundaries}),
                    "TRANSACTION_INGESTOR", {"kid-1"}, current_time=500,
                )
        for boundaries in rejected_boundaries:
            with self.subTest(rejected_boundaries=boundaries), self.assertRaisesRegex(
                verify_e2e.VerificationError, "^TOKEN_TIME_LIFETIME_INVALID$"
            ):
                verify_e2e.validate_token(
                    token({"iat": 100, "exp": 100 + configured + boundaries}),
                    "TRANSACTION_INGESTOR", {"kid-1"}, current_time=500,
                )
        with self.assertRaisesRegex(
            verify_e2e.VerificationError, "^TOKEN_TIME_ORDER_INVALID$"
        ):
            verify_e2e.validate_token(
                token({"iat": 500, "exp": 500}), "TRANSACTION_INGESTOR",
                {"kid-1"}, current_time=500,
            )

    def test_http_error_redacts_body(self):
        error = urllib.error.HTTPError("http://example.invalid", 401, "bad", {}, io.BytesIO(b'{"token":"NeverPrintToken"}'))
        with mock.patch("urllib.request.urlopen", side_effect=error):
            with self.assertRaises(verify_e2e.VerificationError) as raised:
                verify_e2e.http_json("http://example.invalid", expected=(200,))
        self.assertNotIn("NeverPrintToken", str(raised.exception))

    def test_ingestion_plan_allowlist_and_validation(self):
        self.assertEqual(verify_e2e.validate_plan(valid_plan()), valid_plan())
        mutations = (
            lambda plan: plan.update({"extra": "value"}),
            lambda plan: plan.update({"transactionId": str(__import__("uuid").uuid1())}),
            lambda plan: plan.update({"idempotencyKey": "unsafe key"}),
            lambda plan: plan.update({"customerRef": "actual-customer"}),
            lambda plan: plan.update({"transactionOccurredAt": "not-an-instant"}),
            lambda plan: plan.update(
                {"transferLimitOccurredAt": plan["passwordOccurredAt"]}
            ),
        )
        for mutate in mutations:
            plan = valid_plan()
            mutate(plan)
            with self.subTest(mutate=mutate), self.assertRaisesRegex(
                verify_e2e.VerificationError, "INGESTION_PLAN_INVALID"
            ):
                verify_e2e.validate_plan(plan)

    def test_metric_totals_sum_only_exact_counter_series(self):
        scrape = """# HELP ignored ignored
finguardops_external_risk_outcomes_total{result=\"matched\"} 1.0
finguardops_external_risk_outcomes_total{result=\"unmatched\"} 2
finguardops_rule_analysis_outcomes_total{result=\"completed\"} 1.0
finguardops_rule_analysis_outcomes_created 99
"""
        with mock.patch.object(verify_e2e, "http_text", return_value=scrape):
            self.assertEqual(verify_e2e.metric_totals(), (3.0, 1.0))

    def test_dependency_hits_count_only_fixed_marker_and_exact_rule_v2_access_line(self):
        external = "\n".join(
            (
                "FINGUARDOPS_EXTERNAL_RISK_LOOKUP_RECEIVED",
                "prefix FINGUARDOPS_EXTERNAL_RISK_LOOKUP_RECEIVED",
                "FINGUARDOPS_EXTERNAL_RISK_LOOKUP_RECEIVED suffix",
            )
        )
        rule = "\n".join(
            (
                'INFO:     172.20.0.4:48100 - "POST /api/v2/rule-analysis HTTP/1.1" 200 OK',
                'INFO:     172.20.0.4:48101 - "POST /api/v1/rules/analyze HTTP/1.1" 200 OK',
                'INFO:     172.20.0.4:48102 - "POST /api/v2/rule-analysis HTTP/1.1" 422 Unprocessable Entity',
                'prefix INFO:     172.20.0.4:48103 - "POST /api/v2/rule-analysis HTTP/1.1" 200 OK',
            )
        )
        with mock.patch.object(
            verify_e2e, "service_logs", side_effect=[external, rule]
        ):
            self.assertEqual(verify_e2e.dependency_hit_counts(object()), (1, 1))

    def test_external_risk_marker_is_fixed_once_before_body_parsing(self):
        fixture_path = Path(__file__).resolve().parents[2] / "external-risk-mock" / "app.py"
        spec = importlib.util.spec_from_file_location("external_risk_mock_fixture", fixture_path)
        self.assertIsNotNone(spec)
        self.assertIsNotNone(spec.loader)
        fixture = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fixture)
        handler = object.__new__(fixture.Handler)
        handler.path = fixture.LOOKUP_PATH
        events = []
        handler._request_json = mock.Mock(side_effect=lambda: events.append("parse") or None)
        handler._json = mock.Mock()
        with mock.patch(
            "builtins.print",
            side_effect=lambda *args, **kwargs: events.append((args, kwargs)),
        ):
            handler.do_POST()
        self.assertEqual(events[0], ((fixture.LOOKUP_RECEIVED_MARKER,), {"flush": True}))
        self.assertEqual(events[1], "parse")
        self.assertEqual(events.count(((fixture.LOOKUP_RECEIVED_MARKER,), {"flush": True})), 1)
        self.assertEqual(
            fixture.LOOKUP_RECEIVED_MARKER,
            "FINGUARDOPS_EXTERNAL_RISK_LOOKUP_RECEIVED",
        )
        self.assertNotRegex(
            fixture.LOOKUP_RECEIVED_MARKER.lower(),
            r"payload|token|trace|customer|account|transaction|reference",
        )

    def test_snapshot_queries_are_literal_canonical_and_primary_key_ordered(self):
        self.assertEqual(tuple(verify_e2e.SNAPSHOT_QUERIES), verify_e2e.BUSINESS_TABLES)
        for table, query in verify_e2e.SNAPSHOT_QUERIES.items():
            self.assertEqual(
                query,
                "SELECT to_jsonb(snapshot_row)::text FROM public."
                + table
                + " AS snapshot_row ORDER BY snapshot_row.id ASC;",
            )
        script = verify_e2e.snapshot_sql().decode("ascii")
        self.assertEqual(
            script.count("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;"), 1
        )
        self.assertEqual(script.count("COMMIT;"), 1)
        self.assertEqual(script.count("SET LOCAL TIME ZONE 'UTC';"), 1)
        self.assertEqual(script.count("to_jsonb(snapshot_row)::text"), 12)
        self.assertEqual(script.count("ORDER BY snapshot_row.id ASC"), 12)

    def test_snapshot_is_deterministic_for_empty_and_canonical_typed_json(self):
        canonical = (
            b'{"at": "2026-09-05T01:02:03+00:00", "data": {"a": null, '
            b'"nested": [1, 2]}, "id": 1, "numeric": 12000000.0000}'
        )
        first = verify_e2e.parse_database_snapshot(
            snapshot_output({"financial_transaction": (canonical,)})
        )
        second = verify_e2e.parse_database_snapshot(
            snapshot_output({"financial_transaction": (canonical,)})
        )
        self.assertEqual(first, second)
        self.assertEqual(first["financial_transaction"].count, 1)
        self.assertEqual(first["investigation_note"].count, 0)
        self.assertEqual(repr(first["financial_transaction"]), "TableSnapshot(count=1)")

    def test_snapshot_parser_never_exposes_raw_rows_or_hashes(self):
        raw = b'{"id":1,"external_customer_ref":"kc241-sensitive-ref"'
        malformed = (
            verify_e2e.SNAPSHOT_BEGIN_PREFIX + b"audit_log\n" + raw + b"\n"
        )
        with self.assertRaises(verify_e2e.VerificationError) as raised:
            verify_e2e.parse_database_snapshot(malformed)
        rendered = str(raised.exception)
        self.assertEqual(rendered, "DATABASE_GLOBAL_SNAPSHOT_INVALID")
        self.assertNotIn("kc241-sensitive-ref", rendered)
        self.assertNotRegex(rendered, r"[0-9a-f]{64}")

    def test_global_delta_allows_only_exact_ordered_appends(self):
        before = snapshot_fixture()
        after = snapshot_fixture(
            {
                "behavior_event": (
                    b'{"id":1,"event_type":"PASSWORD_CHANGED"}',
                    b'{"id":2,"event_type":"TRANSFER_LIMIT_CHANGED"}',
                )
            }
        )
        verify_e2e.assert_global_delta(before, after, {"behavior_event": 2})

        unexpected = snapshot_fixture(
            {
                "behavior_event": (
                    b'{"id":1,"event_type":"PASSWORD_CHANGED"}',
                    b'{"id":2,"event_type":"TRANSFER_LIMIT_CHANGED"}',
                ),
                "investigation_note": (b'{"id":1,"content":"unexpected"}',),
            }
        )
        with self.assertRaisesRegex(
            verify_e2e.VerificationError, "DATABASE_GLOBAL_DELTA_INVALID"
        ):
            verify_e2e.assert_global_delta(before, unexpected, {"behavior_event": 2})

    def test_count_preserving_updates_are_rejected_for_all_risk_tables(self):
        tables = (
            "detection_evidence",
            "audit_log",
            "behavior_event",
            "investigation_note",
            "fraud_rule",
            "rule_version",
        )
        for table in tables:
            before = snapshot_fixture({table: (b'{"id":1,"value":"before"}',)})
            after = snapshot_fixture({table: (b'{"id":1,"value":"after"}',)})
            with self.subTest(table=table), self.assertRaisesRegex(
                verify_e2e.VerificationError, "DATABASE_GLOBAL_DELTA_INVALID"
            ):
                verify_e2e.assert_global_delta(before, after, {})

    def test_append_rejects_existing_row_replacement_or_deletion(self):
        before = snapshot_fixture(
            {
                "idempotency_record": (
                    b'{"id":1,"status":"COMPLETED"}',
                    b'{"id":2,"status":"COMPLETED"}',
                )
            }
        )
        valid = snapshot_fixture(
            {
                "idempotency_record": (
                    b'{"id":1,"status":"COMPLETED"}',
                    b'{"id":2,"status":"COMPLETED"}',
                    b'{"id":3,"status":"FAILED"}',
                )
            }
        )
        verify_e2e.assert_global_delta(before, valid, {"idempotency_record": 1})
        invalid_rows = (
            (
                b'{"id":1,"status":"FAILED"}',
                b'{"id":2,"status":"COMPLETED"}',
                b'{"id":3,"status":"FAILED"}',
            ),
            (
                b'{"id":2,"status":"COMPLETED"}',
                b'{"id":3,"status":"FAILED"}',
                b'{"id":4,"status":"FAILED"}',
            ),
        )
        for rows in invalid_rows:
            with self.subTest(rows=len(rows)), self.assertRaisesRegex(
                verify_e2e.VerificationError, "DATABASE_GLOBAL_DELTA_INVALID"
            ):
                verify_e2e.assert_global_delta(
                    before,
                    snapshot_fixture({"idempotency_record": rows}),
                    {"idempotency_record": 1},
                )

    def test_snapshot_integrity_rejects_count_only_or_forged_fingerprint(self):
        before = snapshot_fixture()
        after = dict(before)
        after["audit_log"] = verify_e2e.TableSnapshot(
            count=0, row_hashes=(), fingerprint=b"\x00" * 32
        )
        with self.assertRaisesRegex(
            verify_e2e.VerificationError, "DATABASE_GLOBAL_SNAPSHOT_INVALID"
        ):
            verify_e2e.assert_global_delta(before, after, {})

    def test_transaction_cardinality_contract_includes_high_case_and_four_actions(self):
        self.assertEqual(
            verify_e2e.expected_transaction_cardinality(True, True, True),
            (1, 1, 1, 1, 1, 1, 2, 1, 1, 4, 1, 1, 1, 1),
        )

    def test_step_rejects_dependency_hit_delta_independently_from_metrics(self):
        before = snapshot_fixture()
        ctx = mock.Mock()
        ctx.execute.return_value = b"ingestion step completed: auth-denial\n"
        with mock.patch.object(
            verify_e2e, "database_snapshot", side_effect=[before, before]
        ), mock.patch.object(
            verify_e2e, "dependency_hit_counts", side_effect=[(0, 0), (1, 0)]
        ), mock.patch.object(
            verify_e2e, "backend_metric_totals", side_effect=[(0.0, 0.0), (0.0, 0.0)]
        ), mock.patch.object(
            verify_e2e, "transaction_cardinality", return_value=(0,) * 14
        ), self.assertRaisesRegex(
            verify_e2e.VerificationError, "DEPENDENCY_HIT_DELTA_INVALID"
        ):
            verify_e2e.run_ingestion_step(
                ctx, valid_plan(), "auth-denial", {}, (0,) * 14, (0, 0), (0.0, 0.0)
            )

    def test_step_rejects_backend_metric_delta_independently_from_hits(self):
        before = snapshot_fixture()
        ctx = mock.Mock()
        ctx.execute.return_value = b"ingestion step completed: auth-denial\n"
        with mock.patch.object(
            verify_e2e, "database_snapshot", side_effect=[before, before]
        ), mock.patch.object(
            verify_e2e, "dependency_hit_counts", side_effect=[(0, 0), (0, 0)]
        ), mock.patch.object(
            verify_e2e, "backend_metric_totals", side_effect=[(0.0, 0.0), (1.0, 0.0)]
        ), mock.patch.object(
            verify_e2e, "transaction_cardinality", return_value=(0,) * 14
        ), self.assertRaisesRegex(
            verify_e2e.VerificationError, "BACKEND_OUTCOME_METRIC_DELTA_INVALID"
        ):
            verify_e2e.run_ingestion_step(
                ctx, valid_plan(), "auth-denial", {}, (0,) * 14, (0, 0), (0.0, 0.0)
            )

    def test_ingestion_runtime_exact_stage_status_matrix(self):
        plan = valid_plan()
        requests = []

        def request(endpoint, payload, status, **kwargs):
            requests.append(
                (
                    endpoint,
                    payload.get("eventType"),
                    status,
                    kwargs.get("expected_code"),
                    kwargs.get("idempotency_key"),
                )
            )
            if endpoint.endswith("behavior-events") and status in (200, 201):
                return {"eventId": payload["eventId"]}
            if endpoint.endswith("transactions") and status == 201:
                return {
                    "transactionId": plan["transactionId"],
                    "processingStatus": "ADDITIONAL_AUTH_REQUIRED",
                    "riskLevel": "HIGH",
                    "riskResponseOutcome": "ADDITIONAL_AUTH_REQUIRED",
                    "adoptedDetectionResultId": "89101309-432c-451a-92e6-84ec0c3045e5",
                    "caseId": "efecb97d-9f15-4072-8f71-e27f12b5ec2c",
                    "createdAt": "2026-09-05T01:02:03Z",
                    "traceId": "synthetic-trace",
                }
            return {"code": kwargs.get("expected_code")}

        output = io.StringIO()
        for step in verify_e2e.INGESTION_STEPS:
            stdin = types.SimpleNamespace(
                buffer=io.BytesIO(json.dumps(plan).encode("utf-8"))
            )
            with mock.patch.object(verify_e2e.sys, "stdin", stdin), mock.patch.object(
                verify_e2e, "read_secret", side_effect=["a" * 32, "b" * 32]
            ), mock.patch.object(
                verify_e2e, "token_for", side_effect=["transaction-token", "behavior-token"]
            ), mock.patch.object(
                verify_e2e, "validate_actual_service_tokens"
            ), mock.patch.object(
                verify_e2e, "assert_cross_secret_rejected"
            ), mock.patch.object(
                verify_e2e, "request_backend", side_effect=request
            ), contextlib.redirect_stdout(output):
                verify_e2e.ingestion_runtime(step)
        self.assertEqual(
            [(endpoint, status) for endpoint, _, status, _, _ in requests],
            [
                ("/api/v1/transactions", 403),
                ("/api/v1/behavior-events", 403),
                ("/api/v1/transactions", 401),
                ("/api/v1/behavior-events", 401),
                ("/api/v1/transactions", 401),
                ("/api/v1/behavior-events", 401),
                ("/api/v1/behavior-events", 201),
                ("/api/v1/behavior-events", 201),
                ("/api/v1/behavior-events", 200),
                ("/api/v1/behavior-events", 409),
                ("/api/v1/transactions", 201),
                ("/api/v1/transactions", 201),
                ("/api/v1/transactions", 409),
                ("/api/v1/transactions", 409),
                ("/api/v1/transactions", 409),
            ],
        )
        duplicate_requests = [
            request for request in requests
            if request[4] == plan["duplicateIdempotencyKey"]
        ]
        self.assertEqual(
            [(request[2], request[3]) for request in duplicate_requests],
            [(409, "DUPLICATE_TRANSACTION"), (409, "DUPLICATE_TRANSACTION")],
        )
        for step in verify_e2e.INGESTION_STEPS:
            self.assertIn("ingestion step completed: " + step, output.getvalue())

    def test_main_redacts_unexpected_exception(self):
        stderr = io.StringIO()
        with mock.patch.object(verify_e2e, "runtime", side_effect=RuntimeError("NeverPrintSecret")):
            with contextlib.redirect_stderr(stderr):
                result = verify_e2e.main(["runtime"])
        self.assertEqual(result, 1)
        self.assertNotIn("NeverPrintSecret", stderr.getvalue())

    def test_subprocess_only_propagates_exact_safe_child_code(self):
        completed = verify_e2e.NativeCommandCapture(
            1, b"", b"compose prefix\nverification failed: METRIC_INVALID\ncompose suffix\n"
        )
        with mock.patch.object(verify_e2e, "capture_native_command", return_value=completed), self.assertRaisesRegex(
            verify_e2e.VerificationError, "CHILD_METRIC_INVALID"
        ):
            verify_e2e.run_command(
                ["child"], timeout=1, cwd=Path.cwd(), environment={}
            )
        leaked = verify_e2e.NativeCommandCapture(
            1, b"", b"verification failed: TOKEN NeverPrintSecret\n"
        )
        with mock.patch.object(verify_e2e, "capture_native_command", return_value=leaked), self.assertRaisesRegex(
            verify_e2e.VerificationError, "^SUBPROCESS_FAILED$"
        ) as raised:
            verify_e2e.run_command(
                ["child"], timeout=1, cwd=Path.cwd(), environment={}
            )
        self.assertNotIn("NeverPrintSecret", str(raised.exception))

    def test_before_native_stages_map_every_failure_type_without_reflection(self):
        empty_snapshot = b"".join(
            verify_e2e.SNAPSHOT_BEGIN_PREFIX + table.encode("ascii") + b"\n"
            + verify_e2e.SNAPSHOT_END_PREFIX + table.encode("ascii") + b"\n"
            for table in verify_e2e.BUSINESS_TABLES
        )
        valid = {
            "RULE_PUBLISHED_STATE": b"4\n",
            "RULE_ACTIVE_STATE": b"4\n",
            "RULE_PUBLICATION_COMMAND": publication_success_output(),
            "RULE_ACTIVATION_POLL": b"4\n",
            "TRANSACTION_CARDINALITY_SNAPSHOT": ("|".join(["0"] * 14) + "\n").encode(),
            "DATABASE_GLOBAL_SNAPSHOT": empty_snapshot,
            "EXTERNAL_RISK_LOG_SNAPSHOT": b"",
            "RULE_V2_LOG_SNAPSHOT": b"",
            "BACKEND_METRIC_SNAPSHOT": b"[0,0]\n",
        }
        malformed = {
            "RULE_PUBLISHED_STATE": b"raw-sentinel",
            "RULE_ACTIVE_STATE": b"raw-sentinel",
            "RULE_PUBLICATION_COMMAND": b"\xff",
            "RULE_ACTIVATION_POLL": b"raw-sentinel",
            "TRANSACTION_CARDINALITY_SNAPSHOT": b"0|raw-sentinel",
            "DATABASE_GLOBAL_SNAPSHOT": b"raw-sentinel",
            "EXTERNAL_RISK_LOG_SNAPSHOT": b"\xff",
            "RULE_V2_LOG_SNAPSHOT": b"\xff",
            "BACKEND_METRIC_SNAPSHOT": b'{"raw":"sentinel"}',
        }
        sentinel = b"NeverPrintRawPathSqlCommandCredentialToken"
        for stage, codes in verify_e2e.BEFORE_NATIVE_FAILURE_CODES.items():
            cases = {
                "start": verify_e2e.NativeCommandCapture(None, b"", b"", start_failed=True),
                "timeout": verify_e2e.NativeCommandCapture(None, b"", b"", timed_out=True),
                "exit": verify_e2e.NativeCommandCapture(23, sentinel, sentinel),
                "malformed": verify_e2e.NativeCommandCapture(0, malformed[stage], b""),
                "stderr": verify_e2e.NativeCommandCapture(0, valid[stage], sentinel),
                "oversize": verify_e2e.NativeCommandCapture(
                    0, valid[stage], b"", stdout_overflow=True
                ),
                "cleanup": verify_e2e.NativeCommandCapture(
                    None, b"", b"", cleanup_failed=True
                ),
            }
            expected = {
                "start": codes["start"],
                "timeout": codes["timeout"],
                "exit": codes["exit"],
                "malformed": codes["output"],
                "stderr": codes["output"],
                "oversize": codes["output"],
                "cleanup": codes["cleanup"],
            }
            if stage == "RULE_PUBLICATION_COMMAND":
                expected["malformed"] = verify_e2e.RULE_PUBLICATION_STDOUT_INVALID
                expected["stderr"] = verify_e2e.RULE_PUBLICATION_STDERR_INVALID
            for name, capture in cases.items():
                with self.subTest(stage=stage, failure=name), mock.patch.object(
                    verify_e2e, "capture_native_command", return_value=capture
                ), self.assertRaises(verify_e2e.VerificationError) as raised:
                    verify_e2e.run_command(
                        ["fixed-executable", "fixed-argument"],
                        timeout=1,
                        cwd=Path.cwd(),
                        environment={},
                        before_stage=stage,
                    )
                self.assertEqual(str(raised.exception), expected[name])
                self.assertNotIn("NeverPrint", str(raised.exception))
                self.assertNotEqual(str(raised.exception), "SUBPROCESS_FAILED")
            with self.subTest(stage=stage, failure="success"), mock.patch.object(
                verify_e2e,
                "capture_native_command",
                return_value=verify_e2e.NativeCommandCapture(0, valid[stage], b""),
            ):
                self.assertEqual(
                    verify_e2e.run_command(
                        ["fixed-executable", "fixed-argument"],
                        timeout=1,
                        cwd=Path.cwd(),
                        environment={},
                        before_stage=stage,
                    ),
                    valid[stage],
                )

    def test_rule_publication_compose_run_benign_stderr_is_not_the_result(self):
        stdout = publication_success_output()
        capture = verify_e2e.NativeCommandCapture(
            0, stdout, b"compose emitted a bounded benign diagnostic\n"
        )
        with mock.patch.object(
            verify_e2e, "capture_native_command", return_value=capture
        ):
            self.assertEqual(
                verify_e2e.run_command(
                    ["fixed-executable"], timeout=1, cwd=Path.cwd(), environment={},
                    before_stage="RULE_PUBLICATION_COMMAND",
                ),
                stdout,
            )

    def test_rule_publication_semantic_stderr_accepts_empty_lf_and_crlf(self):
        stdout = publication_success_output()
        for name, stderr in {
            "empty": b"",
            "lf": b"compose emitted a bounded benign diagnostic\n",
            "crlf": b"compose emitted a bounded benign diagnostic\r\n",
        }.items():
            capture = verify_e2e.NativeCommandCapture(0, stdout, stderr)
            with self.subTest(case=name), mock.patch.object(
                verify_e2e, "capture_native_command", return_value=capture
            ):
                self.assertEqual(
                    verify_e2e.run_command(
                        ["fixed-executable"], timeout=1, cwd=Path.cwd(), environment={},
                        before_stage="RULE_PUBLICATION_COMMAND",
                    ),
                    stdout,
                )

    def test_rule_publication_semantic_output_rejects_hostile_evidence(self):
        success = publication_success_output()
        prefix = verify_e2e.RULE_PUBLICATION_FAILURE_WIRE_PREFIX
        codes = sorted(verify_e2e.RULE_PUBLICATION_AUTHORITATIVE_FAILURE_CODES)
        approved = next(iter(verify_e2e.RULE_PUBLICATION_RUNNER_FAILURE_LINES.values()))[0]
        marker = verify_e2e.RULE_PUBLICATION_SUCCESS_MARKER_INVALID
        evidence = verify_e2e.RULE_PUBLICATION_FAILURE_EVIDENCE_INVALID
        shape = verify_e2e.RULE_PUBLICATION_STDERR_INVALID
        overflow_code = "RULE_PUBLICATION_COMMAND_OUTPUT_INVALID"
        hostile = {
            "missing-success": (b"ordinary Spring Boot output\n", b"", False, marker),
            "noncanonical-success": (
                (verify_e2e.RULE_PUBLICATION_RUNNER_SUCCESS_EVIDENCE + "\n").encode(),
                b"",
                False,
                marker,
            ),
            "duplicate-success": (success + success, b"", False, marker),
            "authoritative": (
                success, (prefix + codes[0] + "\n").encode(), False, evidence
            ),
            "approved-java": (success, (approved + "\n").encode(), False, evidence),
            "exception": (
                success, b"java.lang.IllegalStateException: hidden\n", False, evidence
            ),
            "stack-frame": (
                success, b"\tat com.example.Type.run(Type.java:1)\n", False, evidence
            ),
            "invalid-utf8": (success, b"\xff\n", False, shape),
            "bom": (success, b"\xef\xbb\xbfdiagnostic\n", False, shape),
            "nul": (success, b"diagnostic\x00\n", False, shape),
            "c0": (success, b"diagnostic\x01\n", False, shape),
            "c1": (success, "diagnostic\u0085\n".encode(), False, shape),
            "cf": (success, "diagnostic\u200b\n".encode(), False, shape),
            "bare-cr": (success, b"diagnostic\r", False, shape),
            "unterminated": (success, b"diagnostic", False, shape),
            "mixed-newline": (success, b"first\r\nsecond\n", False, shape),
            "too-many-lines": (
                success,
                b"x\n" * (verify_e2e.SEMANTIC_STDERR_MAX_LINES + 1),
                False,
                shape,
            ),
            "long-line": (
                success,
                ("x" * (verify_e2e.SEMANTIC_STDERR_MAX_LINE_LENGTH + 1) + "\n").encode(),
                False,
                shape,
            ),
            "overflow": (success, b"bounded\n", True, overflow_code),
            "duplicate-marker": (
                success,
                ((prefix + codes[0] + "\n") * 2).encode(),
                False,
                evidence,
            ),
            "conflicting-marker": (
                success,
                (prefix + codes[0] + "\n" + prefix + codes[1] + "\n").encode(),
                False,
                evidence,
            ),
            "malformed-marker": (
                success, (prefix + "UNKNOWN\n").encode(), False, evidence
            ),
        }
        for name, (stdout, stderr, overflow, expected_code) in hostile.items():
            capture = verify_e2e.NativeCommandCapture(
                0, stdout, stderr, stderr_overflow=overflow
            )
            with self.subTest(case=name), mock.patch.object(
                verify_e2e, "capture_native_command", return_value=capture
            ), self.assertRaisesRegex(
                verify_e2e.VerificationError,
                "^" + expected_code + "$",
            ) as raised:
                verify_e2e.run_command(
                    ["fixed-executable"], timeout=1, cwd=Path.cwd(), environment={},
                    before_stage="RULE_PUBLICATION_COMMAND",
                )
            self.assertNotIn("hidden", str(raised.exception))

    def assert_publication_code(self, stdout, stderr, code, **capture_flags):
        capture = verify_e2e.NativeCommandCapture(0, stdout, stderr, **capture_flags)
        with mock.patch.object(
            verify_e2e, "capture_native_command", return_value=capture
        ), self.assertRaisesRegex(
            verify_e2e.VerificationError, "^" + code + "$"
        ) as raised:
            verify_e2e.run_command(
                ["fixed-executable"], timeout=1, cwd=Path.cwd(), environment={},
                before_stage="RULE_PUBLICATION_COMMAND",
            )
        self.assertNotIn("NeverPrint", str(raised.exception))
        self.assertNotIn("hidden", str(raised.exception))

    def test_publication_production_equivalent_success_is_accepted(self):
        for newline in ("\n", "\r\n"):
            stdout = publication_production_success_output(newline)
            for name, stderr in {
                "empty": b"",
                "benign-lf": b" Container fgo-backend-run-x  Created\n",
                "benign-crlf": b" Container fgo-backend-run-x  Created\r\n",
                "benign-multi": (
                    b" Container fgo-backend-run-x  Created\n"
                    b" Container fgo-backend-run-x  Started\n"
                ),
            }.items():
                capture = verify_e2e.NativeCommandCapture(0, stdout, stderr)
                with self.subTest(newline=repr(newline), stderr=name), mock.patch.object(
                    verify_e2e, "capture_native_command", return_value=capture
                ):
                    self.assertEqual(
                        verify_e2e.run_command(
                            ["fixed-executable"], timeout=1, cwd=Path.cwd(),
                            environment={}, before_stage="RULE_PUBLICATION_COMMAND",
                        ),
                        stdout,
                    )

    def test_publication_stderr_step_has_its_own_identity(self):
        success = publication_production_success_output()
        cases = {
            "invalid-utf8": b"\xff\n",
            "unterminated": b"NeverPrintDiagnostic",
            "bare-cr": b"NeverPrintDiagnostic\r",
            "mixed-newline": b"first\r\nsecond\n",
            "nul": b"NeverPrintDiagnostic\x00\n",
            "c0": b"NeverPrintDiagnostic\x01\n",
            "c1": "NeverPrintDiagnostic\u0085\n".encode(),
            "cf": "NeverPrintDiagnostic\u200b\n".encode(),
            "bom": b"\xef\xbb\xbfNeverPrintDiagnostic\n",
            "too-many-lines": b"x\n" * (verify_e2e.SEMANTIC_STDERR_MAX_LINES + 1),
            "long-line": (
                "x" * (verify_e2e.SEMANTIC_STDERR_MAX_LINE_LENGTH + 1) + "\n"
            ).encode(),
        }
        for name, stderr in cases.items():
            with self.subTest(case=name):
                self.assert_publication_code(
                    success, stderr, verify_e2e.RULE_PUBLICATION_STDERR_INVALID
                )

    def test_publication_failure_evidence_step_has_its_own_identity(self):
        success = publication_production_success_output()
        prefix = verify_e2e.RULE_PUBLICATION_FAILURE_WIRE_PREFIX
        codes = sorted(verify_e2e.RULE_PUBLICATION_AUTHORITATIVE_FAILURE_CODES)
        approved = next(
            iter(verify_e2e.RULE_PUBLICATION_RUNNER_FAILURE_LINES.values())
        )[0]
        cases = {
            "authoritative": (success, (prefix + codes[0] + "\n").encode()),
            "malformed-marker": (success, (prefix + "UNKNOWN\n").encode()),
            "duplicate-marker": (success, ((prefix + codes[0] + "\n") * 2).encode()),
            "conflicting-marker": (
                success,
                (prefix + codes[0] + "\n" + prefix + codes[1] + "\n").encode(),
            ),
            "stdout-marker": (success + (prefix + codes[0] + "\n").encode(), b""),
            "legacy-java": (success, (approved + "\n").encode()),
            "stdout-legacy-java": (success + (approved + "\n").encode(), b""),
            "exception-headline": (
                success, b"java.lang.IllegalStateException: hidden\n"
            ),
            "stack-frame": (success, b"\tat com.example.Type.run(Type.java:1)\n"),
        }
        for name, (stdout, stderr) in cases.items():
            with self.subTest(case=name):
                self.assert_publication_code(
                    stdout,
                    stderr,
                    verify_e2e.RULE_PUBLICATION_FAILURE_EVIDENCE_INVALID,
                )

    def test_publication_stdout_step_has_its_own_identity(self):
        success = publication_production_success_output()
        cases = {
            "invalid-utf8": success + b"\xff\n",
            "unterminated": success.rstrip(b"\n"),
            "bare-cr": success + b"NeverPrintTail\r",
            "mixed-newline": success.replace(b"\n", b"\r\n", 1),
            "nul": success + b"NeverPrintTail\x00\n",
            "c0": success + b"NeverPrintTail\x01\n",
            "c1": success + "NeverPrintTail\u0085\n".encode(),
            "cf": success + "NeverPrintTail\u200b\n".encode(),
        }
        for name, stdout in cases.items():
            with self.subTest(case=name):
                self.assert_publication_code(
                    stdout, b"", verify_e2e.RULE_PUBLICATION_STDOUT_INVALID
                )

    def test_publication_success_marker_step_has_its_own_identity(self):
        success = publication_production_success_output()
        evidence = verify_e2e.RULE_PUBLICATION_RUNNER_SUCCESS_EVIDENCE
        cases = {
            "missing": (b"ordinary Spring Boot output\n", b""),
            "duplicate-stdout": (success + success, b""),
            "stderr-only": (b"ordinary Spring Boot output\n", (evidence + "\n").encode()),
            "stdout-and-stderr": (success, (evidence + "\n").encode()),
            "not-canonical": ((evidence + "\n").encode(), b""),
            "no-log-prefix": (
                (production_success_message() + "\n").encode(), b""
            ),
        }
        for name, (stdout, stderr) in cases.items():
            with self.subTest(case=name):
                self.assert_publication_code(
                    stdout, stderr, verify_e2e.RULE_PUBLICATION_SUCCESS_MARKER_INVALID
                )

    def test_publication_output_invalid_fallback_is_preserved(self):
        success = publication_production_success_output()
        fallback = verify_e2e.BEFORE_NATIVE_FAILURE_CODES[
            "RULE_PUBLICATION_COMMAND"
        ]["output"]
        self.assertEqual("RULE_PUBLICATION_COMMAND_OUTPUT_INVALID", fallback)
        self.assert_publication_code(
            success, b"", fallback, stdout_overflow=True
        )
        self.assert_publication_code(
            success, b"bounded\n", fallback, stderr_overflow=True
        )
        unclassified = {
            "unknown-literal": ValueError("RULE_PUBLICATION_COMMAND_MADE_UP"),
            "free-text": ValueError("something unexpected NeverPrint"),
            "two-args": ValueError(
                verify_e2e.RULE_PUBLICATION_STDERR_INVALID, "extra"
            ),
            "non-string": ValueError(17),
            "subclass": UnicodeDecodeError("utf-8", b"\xff", 0, 1, "invalid"),
        }
        for name, error in unclassified.items():
            with self.subTest(case=name), mock.patch.object(
                verify_e2e,
                "validate_semantic_compose_run_output",
                side_effect=error,
            ):
                self.assert_publication_code(success, b"", fallback)
        with mock.patch.object(
            verify_e2e,
            "validate_semantic_compose_run_output",
            side_effect=ValueError(verify_e2e.RULE_PUBLICATION_STDERR_INVALID),
        ):
            self.assert_publication_code(
                success, b"", verify_e2e.RULE_PUBLICATION_STDERR_INVALID
            )

    def test_publication_semantic_codes_are_fixed_literals(self):
        self.assertEqual(
            (
                "RULE_PUBLICATION_COMMAND_STDERR_INVALID",
                "RULE_PUBLICATION_COMMAND_FAILURE_EVIDENCE_INVALID",
                "RULE_PUBLICATION_COMMAND_STDOUT_INVALID",
                "RULE_PUBLICATION_COMMAND_SUCCESS_MARKER_INVALID",
            ),
            verify_e2e.RULE_PUBLICATION_SEMANTIC_FAILURE_CODES,
        )
        for stage in sorted(verify_e2e.BEFORE_NATIVE_FAILURE_CODES):
            if stage == "RULE_PUBLICATION_COMMAND":
                continue
            with self.subTest(stage=stage):
                self.assertEqual(
                    verify_e2e.BEFORE_NATIVE_FAILURE_CODES[stage]["output"],
                    verify_e2e.semantic_output_failure_code(
                        stage,
                        ValueError(verify_e2e.RULE_PUBLICATION_STDERR_INVALID),
                    ),
                )

    def test_backend_metric_semantic_stage_identity_is_unchanged(self):
        fallback = verify_e2e.BEFORE_NATIVE_FAILURE_CODES[
            "BACKEND_METRIC_SNAPSHOT"
        ]["output"]
        cases = {
            "stderr-unterminated": (b"[0,0]\n", b"NeverPrintDiagnostic"),
            "stderr-invalid-utf8": (b"[0,0]\n", b"\xff\n"),
            "stdout-invalid-utf8": (b"\xff", b""),
            "stdout-not-json": (b"NeverPrintOutput\n", b""),
            "stdout-wrong-shape": (b'{"raw":"NeverPrint"}', b""),
            "failure-evidence": (
                b"[0,0]\n", b"java.lang.IllegalStateException: hidden\n"
            ),
        }
        for name, (stdout, stderr) in cases.items():
            capture = verify_e2e.NativeCommandCapture(0, stdout, stderr)
            with self.subTest(case=name), mock.patch.object(
                verify_e2e, "capture_native_command", return_value=capture
            ), self.assertRaisesRegex(
                verify_e2e.VerificationError, "^" + fallback + "$"
            ) as raised:
                verify_e2e.run_command(
                    ["fixed-executable"], timeout=1, cwd=Path.cwd(), environment={},
                    before_stage="BACKEND_METRIC_SNAPSHOT",
                )
            self.assertNotIn("NeverPrint", str(raised.exception))
            self.assertNotIn("hidden", str(raised.exception))

    def test_backend_metric_compose_run_benign_stderr_is_not_the_result(self):
        stdout = b"[0,0]\n"
        capture = verify_e2e.NativeCommandCapture(
            0, stdout, b"compose emitted a bounded benign diagnostic\n"
        )
        with mock.patch.object(
            verify_e2e, "capture_native_command", return_value=capture
        ):
            self.assertEqual(
                verify_e2e.run_command(
                    ["fixed-executable"], timeout=1, cwd=Path.cwd(), environment={},
                    before_stage="BACKEND_METRIC_SNAPSHOT",
                ),
                stdout,
            )

    def test_backend_metric_semantic_output_remains_parser_owned_and_fail_closed(self):
        prefix = verify_e2e.RULE_PUBLICATION_FAILURE_WIRE_PREFIX
        code = sorted(verify_e2e.RULE_PUBLICATION_AUTHORITATIVE_FAILURE_CODES)[0]
        hostile = {
            "malformed": (b"{", b"benign\n"),
            "missing-metric": (b"[0]\n", b"benign\n"),
            "duplicate-metric": (b"[0,0,0]\n", b"benign\n"),
            "unknown-category": (b'["unknown",0]\n', b"benign\n"),
            "unknown-counter": (b'{"unknown":0}\n', b"benign\n"),
            "authoritative": (b"[0,0]\n", (prefix + code + "\n").encode()),
            "exception": (b"[0,0]\n", b"java.lang.RuntimeException: hidden\n"),
            "stack": (b"[0,0]\n", b"\tat com.example.Type.run(Type.java:1)\n"),
            "invalid-utf8": (b"[0,0]\n", b"\xff\n"),
            "control": (b"[0,0]\n", b"diagnostic\x01\n"),
            "mixed-newline": (b"[0,0]\n", b"first\r\nsecond\n"),
        }
        for name, (stdout, stderr) in hostile.items():
            capture = verify_e2e.NativeCommandCapture(0, stdout, stderr)
            with self.subTest(case=name), mock.patch.object(
                verify_e2e, "capture_native_command", return_value=capture
            ), self.assertRaisesRegex(
                verify_e2e.VerificationError,
                "^BACKEND_METRIC_SNAPSHOT_OUTPUT_INVALID$",
            ) as raised:
                verify_e2e.run_command(
                    ["fixed-executable"], timeout=1, cwd=Path.cwd(), environment={},
                    before_stage="BACKEND_METRIC_SNAPSHOT",
                )
            self.assertNotIn("hidden", str(raised.exception))

    def test_non_semantic_stages_keep_the_empty_stderr_contract(self):
        valid = {
            "RULE_PUBLISHED_STATE": b"4\n",
            "RULE_ACTIVE_STATE": b"4\n",
            "RULE_ACTIVATION_POLL": b"4\n",
            "TRANSACTION_CARDINALITY_SNAPSHOT": ("|".join(["0"] * 14) + "\n").encode(),
            "DATABASE_GLOBAL_SNAPSHOT": b"".join(
                verify_e2e.SNAPSHOT_BEGIN_PREFIX + table.encode("ascii") + b"\n"
                + verify_e2e.SNAPSHOT_END_PREFIX + table.encode("ascii") + b"\n"
                for table in verify_e2e.BUSINESS_TABLES
            ),
            "EXTERNAL_RISK_LOG_SNAPSHOT": b"",
            "RULE_V2_LOG_SNAPSHOT": b"",
        }
        self.assertEqual(
            set(valid),
            set(verify_e2e.BEFORE_NATIVE_FAILURE_CODES) - verify_e2e.SEMANTIC_STDERR_STAGES,
        )
        for stage, stdout in valid.items():
            capture = verify_e2e.NativeCommandCapture(0, stdout, b"benign\n")
            with self.subTest(stage=stage), mock.patch.object(
                verify_e2e, "capture_native_command", return_value=capture
            ), self.assertRaisesRegex(
                verify_e2e.VerificationError,
                "^" + verify_e2e.BEFORE_NATIVE_FAILURE_CODES[stage]["output"] + "$",
            ):
                verify_e2e.run_command(
                    ["fixed-executable"], timeout=1, cwd=Path.cwd(), environment={},
                    before_stage=stage,
                )

    def test_rule_publication_nonzero_uses_only_exact_controlled_runner_lines(self):
        fallback = "RULE_PUBLICATION_COMMAND_EXIT_NONZERO"
        for code, approved_lines in verify_e2e.RULE_PUBLICATION_RUNNER_FAILURE_LINES.items():
            for approved_line in approved_lines:
                capture = verify_e2e.NativeCommandCapture(
                    1,
                    (
                        "ordinary startup\n"
                        + verify_e2e.RULE_PUBLICATION_RUNNER_NEUTRAL_LINES[0]
                        + "\n" + approved_line + "\n"
                    ).encode(),
                    b"",
                )
                with self.subTest(code=code, line=approved_line), mock.patch.object(
                    verify_e2e, "capture_native_command", return_value=capture
                ), self.assertRaises(verify_e2e.VerificationError) as raised:
                    verify_e2e.run_command(
                        ["fixed-executable", "fixed-argument"],
                        timeout=1,
                        cwd=Path.cwd(),
                        environment={},
                        before_stage="RULE_PUBLICATION_COMMAND",
                    )
                self.assertEqual(str(raised.exception), code)

        first_lines = [
            lines[0]
            for lines in verify_e2e.RULE_PUBLICATION_RUNNER_FAILURE_LINES.values()
        ]
        first_direct, first_caused = next(iter(
            verify_e2e.RULE_PUBLICATION_RUNNER_FAILURE_LINES.values()
        ))[:2]
        production_stack = verify_e2e.NativeCommandCapture(
            1,
            (
                verify_e2e.RULE_PUBLICATION_RUNNER_NEUTRAL_LINES[0]
                + "\n\tat org.springframework.boot.SpringApplication.callRunner(SpringApplication.java:789) ~[spring-boot-3.5.16.jar!/:3.5.16]"
                + "\n" + first_caused
                + "\n\tat com.aifds.backend.rule.service.RuleV1DefaultRuleSetPublicationService.publish(RuleV1DefaultRuleSetPublicationService.java:135)"
                + "\n\tat com.aifds.backend.rule.service.RuleV1DefaultRuleSetPublicationService$$SpringCGLIB$$0.publish(<generated>) ~[!/:0.0.1-SNAPSHOT]"
                + "\n\t... 12 more\n"
            ).encode(),
            b"",
        )
        with mock.patch.object(
            verify_e2e, "capture_native_command", return_value=production_stack
        ), self.assertRaisesRegex(
            verify_e2e.VerificationError,
            "^RULE_PUBLICATION_RUNNER_PRODUCTION_PROFILE_REJECTED$",
        ):
            verify_e2e.run_command(
                ["fixed-executable"], timeout=1, cwd=Path.cwd(), environment={},
                before_stage="RULE_PUBLICATION_COMMAND",
            )

        sentinel = "NeverPrintRawPathSqlCommandCredentialToken"
        fallback_cases = {
            "unknown": b"java.lang.IllegalStateException: unknown runner marker\n",
            "statically-unreachable-threshold-range": (
                b"java.lang.IllegalArgumentException: amountThreshold exceeds "
                b"NUMERIC(19,4) integer range\n"
            ),
            "statically-unreachable-condition-nesting": (
                b"java.lang.IllegalArgumentException: conditionDefinition "
                b"contains an invalid nested value\n"
            ),
            "database-rejected-condition-object": (
                b"java.lang.IllegalArgumentException: conditionDefinition "
                b"must be a non-empty JSON object\n"
            ),
            "raw-prefix": (sentinel + first_lines[0] + "\n").encode(),
            "raw-suffix": (first_lines[0] + sentinel + "\n").encode(),
            "lowercase": (first_lines[0].lower() + "\n").encode(),
            "case-variant": (first_lines[0].replace("Rule", "rule", 1) + "\n").encode(),
            "partial": (first_lines[0][:-1] + "\n").encode(),
            "multiple": (first_lines[0] + "\n" + first_lines[1] + "\n").encode(),
            "duplicate": (first_lines[0] + "\n" + first_lines[0] + "\n").encode(),
            "direct-caused-duplicate": (
                first_direct + "\n" + first_caused + "\n"
            ).encode(),
            "mixed-newline": (first_lines[0] + "\r\nordinary startup\n").encode(),
            "lone-cr": (first_lines[0] + "\r").encode(),
            "noisy-exception": (
                first_lines[0]
                + "\njava.lang.IllegalStateException: unknown runner noise\n"
            ).encode(),
            "noisy-runtime-exception": (
                first_lines[0]
                + "\nCaused by: java.lang.RuntimeException: unknown runner noise\n"
            ).encode(),
            "noisy-database-exception": (
                first_lines[0]
                + "\nCaused by: org.postgresql.util.PSQLException: unknown runner noise\n"
            ).encode(),
            "noisy-custom-exception-class": (
                first_lines[0]
                + "\nCaused by: com.example.PublicationFailure: unknown runner noise\n"
            ).encode(),
            "noisy-suppressed-exception": (
                first_lines[0]
                + "\nSuppressed: com.example.HiddenFailure: unknown runner noise\n"
            ).encode(),
            "noisy-spaced-suppressed-exception": (
                first_lines[0]
                + "\n  Suppressed: com.example.HiddenFailure: unknown runner noise\n"
            ).encode(),
            "noisy-unqualified-failure": (
                first_lines[0] + "\nPublicationFailure: unknown runner noise\n"
            ).encode(),
            "noisy-bare-exception": (
                first_lines[0] + "\nException: unknown runner noise\n"
            ).encode(),
            "noisy-bare-error": (
                first_lines[0] + "\nError: unknown runner noise\n"
            ).encode(),
            "noisy-bare-failure": (
                first_lines[0] + "\nFailure: unknown runner noise\n"
            ).encode(),
            "noisy-bare-throwable": (
                first_lines[0] + "\nThrowable: unknown runner noise\n"
            ).encode(),
            "noisy-caused-bare-exception": (
                first_lines[0] + "\nCaused by: Exception: unknown runner noise\n"
            ).encode(),
            "noisy-suppressed-bare-error": (
                first_lines[0] + "\nSuppressed: Error: unknown runner noise\n"
            ).encode(),
            "noisy-tab-suppressed-exception": (
                first_lines[0]
                + "\n\tSuppressed: com.example.HiddenFailure: unknown runner noise\n"
            ).encode(),
            "noisy-tab": (first_lines[0] + "\n\tunknown runner noise\n").encode(),
            "noisy-tab-generated": (
                first_lines[0] + "\n\tat <generated>\n"
            ).encode(),
            "noisy-tab-packaging-data": (
                first_lines[0]
                + "\n\tat com.example.Type.method(File.java:1) ~[raw sentinel:path]"
            ).encode(),
            "noisy-thread-error": (
                first_lines[0]
                + '\nException in thread "main" java.lang.AssertionError: unknown runner noise\n'
            ).encode(),
            "success-marker": b"event=rule_v1_default_rule_set_publication outcome=PUBLISHED\n",
            "success-marker-with-failure": (
                "event=rule_v1_default_rule_set_publication outcome=PUBLISHED\n"
                + first_lines[0] + "\n"
            ).encode(),
            "empty": b"",
            "invalid-utf8": b"\xff",
            "control": (first_lines[0] + "\x01\n").encode(),
            "c1": (first_lines[0] + "\x85\n").encode(),
            "cf": (first_lines[0] + "\u200b\n").encode(),
        }
        for name, output in fallback_cases.items():
            capture = verify_e2e.NativeCommandCapture(1, output, b"")
            with self.subTest(case=name), mock.patch.object(
                verify_e2e, "capture_native_command", return_value=capture
            ), self.assertRaises(verify_e2e.VerificationError) as raised:
                verify_e2e.run_command(
                    ["fixed-executable", "fixed-argument"],
                    timeout=1,
                    cwd=Path.cwd(),
                    environment={},
                    before_stage="RULE_PUBLICATION_COMMAND",
                )
            self.assertEqual(str(raised.exception), fallback)
            self.assertNotIn(sentinel, str(raised.exception))

        stream_ambiguities = {
            "same-marker": (first_lines[0], first_lines[0]),
            "different-markers": (first_lines[0], first_lines[1]),
        }
        for name, (stdout_line, stderr_line) in stream_ambiguities.items():
            capture = verify_e2e.NativeCommandCapture(
                1, (stdout_line + "\n").encode(), (stderr_line + "\n").encode()
            )
            with self.subTest(case="stream-" + name), mock.patch.object(
                verify_e2e, "capture_native_command", return_value=capture
            ), self.assertRaisesRegex(
                verify_e2e.VerificationError, "^" + fallback + "$"
            ):
                verify_e2e.run_command(
                    ["fixed-executable"], timeout=1, cwd=Path.cwd(), environment={},
                    before_stage="RULE_PUBLICATION_COMMAND",
                )

        overflow = verify_e2e.NativeCommandCapture(
            1, first_lines[0].encode(), b"", stdout_overflow=True
        )
        with mock.patch.object(
            verify_e2e, "capture_native_command", return_value=overflow
        ), self.assertRaisesRegex(verify_e2e.VerificationError, "^" + fallback + "$"):
            verify_e2e.run_command(
                ["fixed-executable"], timeout=1, cwd=Path.cwd(), environment={},
                before_stage="RULE_PUBLICATION_COMMAND",
            )

        false_success = verify_e2e.NativeCommandCapture(
            0, (first_lines[0] + "\n").encode(), b""
        )
        with mock.patch.object(
            verify_e2e, "capture_native_command", return_value=false_success
        ), self.assertRaisesRegex(
            verify_e2e.VerificationError,
            "^" + verify_e2e.RULE_PUBLICATION_FAILURE_EVIDENCE_INVALID + "$",
        ):
            verify_e2e.run_command(
                ["fixed-executable"], timeout=1, cwd=Path.cwd(), environment={},
                before_stage="RULE_PUBLICATION_COMMAND",
            )

        for name, output in {
            "success-plus-failure": (
                verify_e2e.RULE_PUBLICATION_RUNNER_SUCCESS_MARKER
                + "PUBLISHED\n" + first_lines[0] + "\n"
            ),
            "failure-plus-unknown-exception": (
                first_lines[0]
                + "\njava.lang.IllegalStateException: unknown runner noise\n"
            ),
            "failure-plus-mixed-newline": (
                first_lines[0] + "\r\nordinary startup\n"
            ),
        }.items():
            capture = verify_e2e.NativeCommandCapture(0, output.encode(), b"")
            with self.subTest(case="exit-zero-" + name), mock.patch.object(
                verify_e2e, "capture_native_command", return_value=capture
            ), self.assertRaisesRegex(
                verify_e2e.VerificationError,
                "^" + verify_e2e.RULE_PUBLICATION_FAILURE_EVIDENCE_INVALID + "$",
            ):
                verify_e2e.run_command(
                    ["fixed-executable"], timeout=1, cwd=Path.cwd(), environment={},
                    before_stage="RULE_PUBLICATION_COMMAND",
                )

        success_only = publication_success_output()
        with mock.patch.object(
            verify_e2e,
            "capture_native_command",
            return_value=verify_e2e.NativeCommandCapture(0, success_only, b""),
        ):
            self.assertEqual(
                verify_e2e.run_command(
                    ["fixed-executable"], timeout=1, cwd=Path.cwd(), environment={},
                    before_stage="RULE_PUBLICATION_COMMAND",
                ),
                success_only,
            )

    def test_authoritative_rule_publication_markers_precede_legacy_safely(self):
        fallback = "RULE_PUBLICATION_COMMAND_EXIT_NONZERO"
        prefix = verify_e2e.RULE_PUBLICATION_FAILURE_WIRE_PREFIX
        legacy_by_code = {
            code: lines[0]
            for code, lines in
            verify_e2e.RULE_PUBLICATION_RUNNER_FAILURE_LINES.items()
        }
        self.assertEqual(
            verify_e2e.RULE_PUBLICATION_AUTHORITATIVE_FAILURE_CODES,
            frozenset({
                "RULE_PUBLICATION_BACKEND_STARTUP_FAILED",
                "RULE_PUBLICATION_CONTEXT_REFRESH_FAILED",
                "RULE_PUBLICATION_PRE_RUNNER_FAILED",
                "RULE_PUBLICATION_RUNNER_CONFIGURATION_FAILED",
                "RULE_PUBLICATION_SERVICE_EXECUTION_FAILED",
                *verify_e2e.RULE_PUBLICATION_RUNNER_FAILURE_LINES,
            }),
        )

        for code in verify_e2e.RULE_PUBLICATION_AUTHORITATIVE_FAILURE_CODES:
            stdout = b"ordinary startup\n"
            if code in legacy_by_code:
                stdout += (legacy_by_code[code] + "\n").encode()
            capture = verify_e2e.NativeCommandCapture(
                1, stdout, (prefix + code + "\n").encode()
            )
            with self.subTest(code=code):
                self.assertEqual(
                    verify_e2e.classify_rule_publication_nonzero(capture),
                    code,
                )

        known = "RULE_PUBLICATION_SERVICE_EXECUTION_FAILED"
        other = "RULE_PUBLICATION_RUNNER_CONFIGURATION_FAILED"
        first_legacy = next(iter(legacy_by_code.values()))
        hostile = {
            "unknown": (b"", (prefix + "UNKNOWN_CODE\n").encode()),
            "duplicate": (
                b"", (prefix + known + "\n" + prefix + known + "\n").encode()
            ),
            "different": (
                b"", (prefix + known + "\n" + prefix + other + "\n").encode()
            ),
            "valid-malformed": (
                b"", (prefix + known + "\n" + prefix + known + "-raw\n").encode()
            ),
            "conflicting-legacy": (
                (first_legacy + "\n").encode(), (prefix + known + "\n").encode()
            ),
            "prefix": (b"", ("raw" + prefix + known + "\n").encode()),
            "suffix": (b"", (prefix + known + "raw\n").encode()),
            "leading-space": (b"", (" " + prefix + known + "\n").encode()),
            "trailing-space": (b"", (prefix + known + " \n").encode()),
            "lowercase": (b"", (prefix + known).lower().encode() + b"\n"),
            "case-variant": (
                b"", (prefix + known.replace("RULE", "Rule", 1) + "\n").encode()
            ),
            "mixed-newline": (
                b"ordinary\r\n", (prefix + known + "\n").encode()
            ),
            "lone-cr": (b"", (prefix + known + "\r").encode()),
            "c0": (b"", (prefix + known + "\x01\n").encode()),
            "c1": (b"", (prefix + known + "\x85\n").encode()),
            "cf": (b"", (prefix + known + "\u200b\n").encode()),
            "invalid-utf8": (b"", (prefix + known).encode() + b"\xff\n"),
            "partial": (b"", (prefix + known[:-1]).encode()),
            "unterminated": (b"", (prefix + known).encode()),
            "success": (
                (verify_e2e.RULE_PUBLICATION_RUNNER_SUCCESS_MARKER
                 + "PUBLISHED\n").encode(),
                (prefix + known + "\n").encode(),
            ),
            "stdout-marker": ((prefix + known + "\n").encode(), b""),
        }
        for name, (stdout, stderr) in hostile.items():
            with self.subTest(case=name):
                self.assertEqual(
                    verify_e2e.classify_rule_publication_nonzero(
                        verify_e2e.NativeCommandCapture(1, stdout, stderr)
                    ),
                    fallback,
                )

        raw = (
            "raw sentinel path SQL credential token exception body\n"
        ).encode()
        observed = verify_e2e.classify_rule_publication_nonzero(
            verify_e2e.NativeCommandCapture(
                1, raw, (prefix + known + "\n").encode()
            )
        )
        self.assertEqual(observed, known)
        self.assertNotIn("sentinel", observed)

        for stream_name in ("stdout", "stderr"):
            capture = verify_e2e.NativeCommandCapture(
                1,
                b"" if stream_name == "stderr" else (prefix + known).encode(),
                b"" if stream_name == "stdout" else (prefix + known).encode(),
                stdout_overflow=stream_name == "stdout",
                stderr_overflow=stream_name == "stderr",
            )
            self.assertEqual(
                verify_e2e.classify_rule_publication_nonzero(capture),
                fallback,
            )

        for stream_name in ("stdout", "stderr"):
            stdout = (prefix + known + "\n").encode() if stream_name == "stdout" else b""
            stderr = (prefix + known + "\n").encode() if stream_name == "stderr" else b""
            capture = verify_e2e.NativeCommandCapture(0, stdout, stderr)
            with self.subTest(exit_zero_stream=stream_name), mock.patch.object(
                verify_e2e, "capture_native_command", return_value=capture
            ), self.assertRaisesRegex(
                verify_e2e.VerificationError,
                "^" + verify_e2e.RULE_PUBLICATION_FAILURE_EVIDENCE_INVALID + "$",
            ):
                verify_e2e.run_command(
                    ["fixed-executable"], timeout=1, cwd=Path.cwd(), environment={},
                    before_stage="RULE_PUBLICATION_COMMAND",
                )

    def test_backend_authoritative_marker_literals_are_source_owned(self):
        source = (
            Path(__file__).resolve().parents[3]
            / "backend/src/main/java/com/aifds/backend/rule/operation/"
            "RuleV1DefaultRuleSetPublicationDiagnosticBoundary.java"
        ).read_text(encoding="utf-8")
        self.assertEqual(
            source.count('"FINGUARDOPS_RULE_PUBLICATION_FAILURE="'), 1
        )
        for code in verify_e2e.RULE_PUBLICATION_AUTHORITATIVE_FAILURE_CODES:
            with self.subTest(code=code):
                self.assertGreaterEqual(source.count('"' + code + '"'), 1)

    def test_rule_publication_service_lines_are_authoritative_and_unique(self):
        source_root = (
            Path(__file__).resolve().parents[3]
            / "backend/src/main/java"
        )
        source = "\n".join(
            path.read_text(encoding="utf-8")
            for path in source_root.rglob("*.java")
        )
        service_codes = {
            code: lines
            for code, lines in verify_e2e.RULE_PUBLICATION_RUNNER_FAILURE_LINES.items()
            if code.startswith("RULE_PUBLICATION_SERVICE_")
        }
        self.assertEqual(
            set(service_codes),
            {
                "RULE_PUBLICATION_SERVICE_DEFAULT_SET_INCOMPLETE",
                "RULE_PUBLICATION_SERVICE_IDENTITY_MISMATCH",
                "RULE_PUBLICATION_SERVICE_FRAUD_RULE_INACTIVE",
                "RULE_PUBLICATION_SERVICE_VERSION_PERIOD_INVALID",
                "RULE_PUBLICATION_SERVICE_VERSION_STATUS_INVALID",
                "RULE_PUBLICATION_SERVICE_DRAFT_METADATA_INVALID",
                "RULE_PUBLICATION_SERVICE_EFFECTIVE_FROM_EXPIRED",
                "RULE_PUBLICATION_SERVICE_AMOUNT_THRESHOLD_FORMAT_INVALID",
            },
        )
        for code, lines in service_codes.items():
            with self.subTest(code=code):
                self.assertEqual(len(lines), 2)
                direct, caused = lines
                self.assertTrue(direct.startswith("java.lang."))
                self.assertEqual(caused, "Caused by: " + direct)
                message = direct.split(": ", 1)[1]
                if code == "RULE_PUBLICATION_SERVICE_AMOUNT_THRESHOLD_FORMAT_INVALID":
                    self.assertEqual(source.count(
                        '"amountThreshold must be a positive canonical integer "'
                    ), 1)
                    self.assertEqual(source.count(
                        '"string within NUMERIC(19,4) integer range"'
                    ), 1)
                else:
                    self.assertEqual(source.count('"' + message + '"'), 1)

    def test_rule_publication_command_contract_flow_and_idempotency(self):
        effective = "2026-09-23T14:00:00Z"
        self.assertEqual(
            verify_e2e.rule_publication_arguments(effective),
            [
                "run", "--rm", "--no-deps", "--pull", "never", "-T",
                "-e", "SPRING_PROFILES_ACTIVE=local,rule-v1-default-publication",
                "-e", "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false",
                "backend", "--spring.main.web-application-type=none",
                "--finguardops.rule-v1-default-publication.enabled=true",
                "--finguardops.rule-v1-default-publication.confirmation=PUBLISH_RULE_V1_DEFAULT_V1",
                "--finguardops.rule-v1-default-publication.effective-from=" + effective,
            ],
        )

        class Context:
            def __init__(self, outputs):
                self.outputs = list(outputs)
                self.calls = []

            def execute(self, arguments, *, input_bytes=None, timeout=None, before_stage=None):
                self.calls.append((arguments, timeout, before_stage))
                return self.outputs.pop(0)

        publication = Context([b"0\n", b"0\n", b"runner output\n", b"4\n"])
        with mock.patch.object(verify_e2e.time, "sleep") as sleeper:
            verify_e2e.publish_rules(publication, before_diagnostics=True)
        self.assertEqual(len(publication.calls), 4)
        self.assertEqual(
            [call[2] for call in publication.calls],
            [
                "RULE_PUBLISHED_STATE",
                "RULE_ACTIVE_STATE",
                "RULE_PUBLICATION_COMMAND",
                "RULE_ACTIVATION_POLL",
            ],
        )
        command, timeout, _ = publication.calls[2]
        self.assertEqual(timeout, 240)
        self.assertEqual(command[:-1], verify_e2e.rule_publication_arguments(effective)[:-1])
        self.assertRegex(
            command[-1],
            r"\A--finguardops\.rule-v1-default-publication\.effective-from="
            r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z\Z",
        )
        sleeper.assert_not_called()

        existing = Context([b"4\n", b"4\n"])
        verify_e2e.publish_rules(existing, before_diagnostics=True)
        self.assertEqual(len(existing.calls), 2)

        partial = Context([b"1\n", b"0\n"])
        with self.assertRaisesRegex(
            verify_e2e.VerificationError, "^RULE_PUBLICATION_STATE_INVALID$"
        ):
            verify_e2e.publish_rules(partial, before_diagnostics=True)
        self.assertEqual(len(partial.calls), 2)

    def test_native_capture_bounds_pipes_timeout_and_start_failure(self):
        success = verify_e2e.capture_native_command(
            [sys.executable, "-c", "import sys;sys.stdout.buffer.write(b'ok')"],
            timeout=5,
            cwd=Path.cwd(),
            environment={},
            stdout_limit=16,
            stderr_limit=16,
        )
        self.assertEqual(success.returncode, 0)
        self.assertEqual(success.stdout, b"ok")
        self.assertEqual(success.stderr, b"")
        self.assertFalse(success.cleanup_failed)
        overflow = verify_e2e.capture_native_command(
            [
                sys.executable,
                "-c",
                "import sys;sys.stdout.buffer.write(b'A'*200000);sys.stderr.buffer.write(b'B'*200000)",
            ],
            timeout=5,
            cwd=Path.cwd(),
            environment={},
            stdout_limit=64,
            stderr_limit=64,
        )
        self.assertEqual(overflow.returncode, 0)
        self.assertTrue(overflow.stdout_overflow)
        self.assertTrue(overflow.stderr_overflow)
        self.assertLessEqual(len(overflow.stdout), 65)
        self.assertLessEqual(len(overflow.stderr), 65)
        timeout = verify_e2e.capture_native_command(
            [sys.executable, "-c", "import time;time.sleep(30)"],
            timeout=0.05,
            cwd=Path.cwd(),
            environment={},
            stdout_limit=16,
            stderr_limit=16,
        )
        self.assertTrue(timeout.timed_out)
        self.assertFalse(timeout.cleanup_failed)
        missing = verify_e2e.capture_native_command(
            ["missing-" + "a" * 32 + ".exe"],
            timeout=0.05,
            cwd=Path.cwd(),
            environment={},
            stdout_limit=16,
            stderr_limit=16,
        )
        self.assertTrue(missing.start_failed)
        self.assertFalse(missing.cleanup_failed)

    def test_run_fixture_before_reaches_each_native_stage_in_exact_order(self):
        environment = valid_run_fixture_environment()
        contract = verify_e2e.load_owner_contract(environment)
        empty_snapshot = b"".join(
            verify_e2e.SNAPSHOT_BEGIN_PREFIX + table.encode("ascii") + b"\n"
            + verify_e2e.SNAPSHOT_END_PREFIX + table.encode("ascii") + b"\n"
            for table in verify_e2e.BUSINESS_TABLES
        )
        outputs = {
            "RULE_PUBLISHED_STATE": b"0\n",
            "RULE_ACTIVE_STATE": b"0\n",
            "RULE_PUBLICATION_COMMAND": publication_success_output(),
            "RULE_ACTIVATION_POLL": b"4\n",
            "TRANSACTION_CARDINALITY_SNAPSHOT": ("|".join(["0"] * 14) + "\n").encode(),
            "DATABASE_GLOBAL_SNAPSHOT": empty_snapshot,
            "EXTERNAL_RISK_LOG_SNAPSHOT": b"",
            "RULE_V2_LOG_SNAPSHOT": b"",
            "BACKEND_METRIC_SNAPSHOT": b"[0,0]\n",
        }

        class Context:
            project = verify_e2e.RUN_FIXTURE_PROJECT

            def __init__(self):
                self.contract = contract
                self.stages = []

            def execute(self, arguments, *, input_bytes=None, timeout=None, before_stage=None):
                self.stages.append(before_stage)
                return outputs[before_stage]

        context = Context()
        with tempfile.TemporaryDirectory() as parent:
            directory = Path(parent) / (
                "finguardops-keycloak-e2e-fixture-" + contract.run_id
            )
            directory.mkdir()
            state = verify_e2e.run_fixture_before(context, directory)
        self.assertEqual(state["composeProject"], verify_e2e.RUN_FIXTURE_PROJECT)
        self.assertEqual(
            context.stages,
            [
                "RULE_PUBLISHED_STATE",
                "RULE_ACTIVE_STATE",
                "RULE_PUBLICATION_COMMAND",
                "RULE_ACTIVATION_POLL",
                "TRANSACTION_CARDINALITY_SNAPSHOT",
                "DATABASE_GLOBAL_SNAPSHOT",
                "EXTERNAL_RISK_LOG_SNAPSHOT",
                "RULE_V2_LOG_SNAPSHOT",
                "BACKEND_METRIC_SNAPSHOT",
            ],
        )

    def test_bounded_poll_stops_at_limit(self):
        attempts = []
        with mock.patch.object(time, "sleep"):
            with self.assertRaisesRegex(verify_e2e.VerificationError, "READINESS_TIMEOUT"):
                verify_e2e.bounded_poll(lambda: attempts.append(1) and False, attempts=3, interval=0)
        self.assertEqual(len(attempts), 3)

    def test_invalid_poll_bounds_rejected(self):
        with self.assertRaisesRegex(verify_e2e.VerificationError, "POLL_BOUNDS_INVALID"):
            verify_e2e.bounded_poll(lambda: True, attempts=0)

    def test_owner_contract_accepts_only_consistent_validated_environment(self):
        environment = valid_owner_environment()
        contract = verify_e2e.load_owner_contract(environment)
        self.assertEqual(contract.backend_image, environment["FINGUARDOPS_E2E_BACKEND_IMAGE"])
        self.assertEqual(contract.ai_service_image, environment["FINGUARDOPS_E2E_AI_SERVICE_IMAGE"])
        self.assertEqual(contract.commit_sha, environment["FINGUARDOPS_E2E_REVISION"])
        self.assertEqual(contract.tree_sha, environment["FINGUARDOPS_E2E_SOURCE_TREE"])
        self.assertEqual(contract.run_id, environment["FINGUARDOPS_E2E_RUN_ID"])
        self.assertEqual(contract.repository_id, environment["FINGUARDOPS_E2E_REPOSITORY_ID"])

        mutations = []
        for key in environment:
            changed = dict(environment)
            changed.pop(key)
            mutations.append(changed)
        changed = dict(environment)
        changed["FINGUARDOPS_E2E_RUN_ID"] = "f" * 32
        mutations.append(changed)
        changed = dict(environment)
        changed["FINGUARDOPS_E2E_BACKEND_IMAGE"] = "finguardops-backend:local"
        mutations.append(changed)
        changed = dict(environment)
        changed["FINGUARDOPS_E2E_REVISION"] = "B" * 40
        mutations.append(changed)
        changed = dict(environment)
        changed["FINGUARDOPS_E2E_SOURCE_TREE"] = "c" * 39
        mutations.append(changed)
        for candidate in mutations:
            with self.subTest(candidate=candidate), self.assertRaisesRegex(
                verify_e2e.VerificationError, "OWNER_CONTRACT_INVALID"
            ):
                verify_e2e.load_owner_contract(candidate)

    def test_host_context_receives_contract_without_git_or_receipt_access(self):
        contract = verify_e2e.load_owner_contract(valid_owner_environment())
        with tempfile.TemporaryDirectory(prefix="finguardops-owner-contract-") as directory:
            repo = Path(directory)
            context = verify_e2e.HostContext(
                repo,
                "finguardops-kc241-e2e-unit01",
                1,
                10,
                contract,
            )
        for key, value in valid_owner_environment().items():
            self.assertEqual(context.environment[key], value)
        self.assertEqual(
            context.environment[verify_e2e.COMPOSE_PROJECT_ENVIRONMENT],
            verify_e2e.RUN_FIXTURE_PROJECT,
        )
        self.assertNotIn("--build", context.compose)

    def test_publication_host_context_uses_project_credential_source_contract(self):
        contract = verify_e2e.load_owner_contract(valid_owner_environment())
        repo = Path.cwd().resolve()
        canonical_env_file = str(repo / "infra" / ".env.example")
        ambient_states = (
            ("absent", None),
            ("present-nonempty", "ambient-nonempty"),
            ("present-empty", ""),
        )
        projects = (
            verify_e2e.RUN_FIXTURE_PROJECT,
            "finguardops-kc241-e2e-unit01",
        )

        def is_aligned(context):
            if "--env-file" not in context.compose:
                return False
            env_file_index = context.compose.index("--env-file")
            return (
                context.compose[env_file_index + 1] == canonical_env_file
                and env_file_index < context.compose.index("-f")
                and "POSTGRES_PASSWORD" not in context.environment
        )

        for project in projects:
            for ambient_state, ambient in ambient_states:
                with self.subTest(project=project, ambient_state=ambient_state):
                    with mock.patch.dict(os.environ, {}, clear=False):
                        if ambient is None:
                            os.environ.pop("POSTGRES_PASSWORD", None)
                        else:
                            os.environ["POSTGRES_PASSWORD"] = ambient
                        context = verify_e2e.HostContext(repo, project, 1, 10, contract)
                        effective_environment = os.environ.copy()
                        effective_environment.update(context.environment)
                        self.assertEqual(
                            "POSTGRES_PASSWORD" in effective_environment,
                            ambient is not None,
                        )
                        if ambient is not None:
                            self.assertEqual(
                                effective_environment["POSTGRES_PASSWORD"] == "",
                                ambient == "",
                            )
                    self.assertTrue(is_aligned(context))

                    head_equivalent = types.SimpleNamespace(
                        compose=[
                            item for index, item in enumerate(context.compose)
                            if item != "--env-file"
                            and not (
                                index > 0
                                and context.compose[index - 1] == "--env-file"
                            )
                        ],
                        environment={**context.environment, "POSTGRES_PASSWORD": object()},
                    )
                    self.assertFalse(is_aligned(head_equivalent))

                    without_env_file = types.SimpleNamespace(
                        compose=head_equivalent.compose,
                        environment=dict(context.environment),
                    )
                    with_fixed_override = types.SimpleNamespace(
                        compose=list(context.compose),
                        environment={**context.environment, "POSTGRES_PASSWORD": object()},
                    )
                    self.assertFalse(is_aligned(without_env_file))
                    self.assertFalse(is_aligned(with_fixed_override))

        compose_source = (repo / "infra" / "compose.yml").read_text(encoding="utf-8")
        self.assertEqual(
            compose_source.count("${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"),
            2,
        )
        self.assertIn("${POSTGRES_DB:-finguardops}", compose_source)
        self.assertIn("${POSTGRES_USER:-finguardops}", compose_source)

    def test_all_runtime_uses_no_build_pull_never_and_never_cleans_up(self):
        environment = valid_run_fixture_environment()
        contract = verify_e2e.load_owner_contract(environment)
        context = types.SimpleNamespace(
            repo=Path.cwd(),
            project="finguardops-kc241-e2e-unit01",
            cli_timeout=1,
            contract=contract,
            environment=environment,
        )
        calls = []
        child_environments = []

        def execute(arguments, **_kwargs):
            calls.append(arguments)
            child_environments.append(dict(context.environment))
            if arguments[:2] == ["config", "--format"]:
                config = valid_config()
                config["services"]["backend"]["image"] = contract.backend_image
                for name in ("ai-service", "external-risk-mock", "alertmanager-webhook"):
                    config["services"][name]["image"] = contract.ai_service_image
                return json.dumps(config).encode()
            return b""

        context.execute = execute
        empty = {kind: () for kind in verify_e2e.PROJECT_RESOURCE_KINDS}
        with mock.patch.object(verify_e2e, "project_resources", return_value=empty) as resources, \
             mock.patch.object(verify_e2e, "run_command") as run_command, \
             mock.patch.object(verify_e2e, "validate_static"), \
             mock.patch.object(verify_e2e, "wait_container"), \
             mock.patch.object(verify_e2e, "host_runtime"), \
             mock.patch.object(verify_e2e, "publish_rules"), \
             mock.patch.object(verify_e2e, "run_ingestion_phase"), \
             mock.patch.object(verify_e2e, "existing_volume_phase"):
            verify_e2e.all_runtime(context)
        resources.assert_called_once_with(
            context.project,
            timeout=context.cli_timeout,
            repo=context.repo,
            environment=environment,
        )
        self.assertIn(
            [
                "up", "-d", "--no-build", "--pull", "never",
                "external-risk-mock", "keycloak-bootstrap",
            ],
            calls,
        )
        self.assertFalse(any("--build" in command for command in calls))
        self.assertFalse(any(command and command[0] == "down" for command in calls))
        self.assertTrue(child_environments)
        self.assertTrue(all(child_environment == environment for child_environment in child_environments))
        run_command.assert_not_called()

    def test_all_mode_does_not_require_git_metadata(self):
        environment = valid_owner_environment()
        with tempfile.TemporaryDirectory(prefix="finguardops-no-git-") as directory, \
             mock.patch.dict(verify_e2e.os.environ, environment, clear=True), \
             mock.patch.object(verify_e2e, "all_runtime") as runtime:
            result = verify_e2e.main(
                [
                    "all",
                    "--repo-root", directory,
                    "--project", "finguardops-kc241-e2e-unit01",
                ]
            )
        self.assertEqual(result, 0)
        runtime.assert_called_once()

    def test_service_project_contract_remains_dynamic_and_rejects_run_project(self):
        valid = "finguardops-kc241-e2e-unit01"
        self.assertEqual(verify_e2e.validate_service_project(valid), valid)
        invalid = (
            verify_e2e.RUN_FIXTURE_PROJECT,
            "finguardops-kc241-e2e-short",
            "finguardops-kc241-e2e-" + "a" * 34,
            "finguardops-kc241-e2e-Unit01",
            "finguardops-kc241-e2e--unit01",
            "finguardops-kc241-e2e-unit01\n",
            "finguardops-kc241-e2e-unit\u200b01",
        )
        for project in invalid:
            with self.subTest(project=repr(project)), self.assertRaisesRegex(
                verify_e2e.VerificationError, "HOST_ARGUMENT_INVALID"
            ):
                verify_e2e.validate_service_project(project)
        with tempfile.TemporaryDirectory(prefix="service-project-") as directory:
            for project in invalid:
                stdout, stderr = io.StringIO(), io.StringIO()
                with self.subTest(cli_project=repr(project)), \
                     mock.patch.dict(verify_e2e.os.environ, valid_owner_environment(), clear=True), \
                     mock.patch.object(verify_e2e, "all_runtime") as runtime, \
                     contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    result = verify_e2e.main([
                        "all", "--repo-root", directory, "--project", project
                    ])
                self.assertEqual(result, 1)
                self.assertEqual(stdout.getvalue(), "")
                self.assertEqual(stderr.getvalue(), "verification failed: HOST_ARGUMENT_INVALID\n")
                runtime.assert_not_called()
        for project in (None, 1, b"finguardops-kc241-e2e-unit01"):
            with self.subTest(project=repr(project)), self.assertRaisesRegex(
                verify_e2e.VerificationError, "HOST_ARGUMENT_INVALID"
            ):
                verify_e2e.validate_service_project(project)

    def test_run_fixture_project_contract_is_fixed_exact_literal(self):
        fixed = verify_e2e.RUN_FIXTURE_PROJECT
        self.assertEqual(verify_e2e.validate_run_fixture_project(fixed), fixed)
        invalid = (
            "x" + fixed,
            fixed + "-x",
            fixed.swapcase(),
            " " + fixed,
            fixed + " ",
            fixed + "\r",
            fixed + "\n",
            fixed + "\x00",
            fixed + "\x85",
            fixed + "\u200b",
            "finguardops-kc241-e2e-unit01",
            "finguardops-keycloаk-browser-e2e",
            "",
        )
        for project in invalid:
            with self.subTest(project=repr(project)), self.assertRaisesRegex(
                verify_e2e.VerificationError, "HOST_ARGUMENT_INVALID"
            ):
                verify_e2e.validate_run_fixture_project(project)
        for project in (None, 1, [fixed]):
            with self.subTest(project=repr(project)), self.assertRaisesRegex(
                verify_e2e.VerificationError, "HOST_ARGUMENT_INVALID"
            ):
                verify_e2e.validate_run_fixture_project(project)

    def test_run_fixture_project_fail_fast_prevents_all_fixture_mutation(self):
        environment = valid_run_fixture_environment()
        fixed = verify_e2e.RUN_FIXTURE_PROJECT
        invalid = (
            fixed + "-suffix",
            fixed + "-",
            "prefix-" + fixed,
            "F" + fixed[1:],
            fixed + " ",
            fixed + "\n",
            fixed + "\u200b",
            "finguardops-kc241-e2e-unit01",
        )
        with tempfile.TemporaryDirectory(prefix="finguardops-keycloak-e2e-fixture-") as parent:
            directory = Path(parent) / (
                "finguardops-keycloak-e2e-fixture-"
                + environment["FINGUARDOPS_E2E_RUN_ID"]
            )
            directory.mkdir()
            for mode in ("run-fixture-before", "run-fixture-after"):
                for project in invalid:
                    stdout, stderr = io.StringIO(), io.StringIO()
                    stdin = types.SimpleNamespace(buffer=io.BytesIO(b"candidate-state-must-not-read"))
                    with self.subTest(mode=mode, project=repr(project)), \
                         mock.patch.dict(verify_e2e.os.environ, environment, clear=True), \
                         mock.patch.object(verify_e2e.sys, "stdin", stdin), \
                         mock.patch.object(verify_e2e, "run_fixture_before") as before, \
                         mock.patch.object(verify_e2e, "run_fixture_after") as after, \
                         contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                        result = verify_e2e.main([
                            mode, "--repo-root", parent,
                            "--project", project,
                            "--fixture-directory", str(directory),
                        ])
                    self.assertEqual(result, 1)
                    self.assertEqual(stdout.getvalue(), "")
                    self.assertEqual(
                        stderr.getvalue(), "verification failed: HOST_ARGUMENT_INVALID\n"
                    )
                    before.assert_not_called()
                    after.assert_not_called()
                    self.assertEqual(tuple(directory.iterdir()), ())

    def test_run_fixture_worker_rejects_noncanonical_project_before_api_or_manifest(self):
        plan_value = base64.b64encode(
            json.dumps(valid_plan(), separators=(",", ":")).encode("utf-8")
        ).decode("ascii")
        for project in (
            "finguardops-kc241-e2e-unit01",
            verify_e2e.RUN_FIXTURE_PROJECT + "-x",
            verify_e2e.RUN_FIXTURE_PROJECT + "\u200b",
            None,
        ):
            environment = valid_owner_environment() | {
                verify_e2e.FIXTURE_PLAN_ENVIRONMENT: plan_value,
            }
            if project is not None:
                environment[verify_e2e.COMPOSE_PROJECT_ENVIRONMENT] = project
            stdout, stderr = io.StringIO(), io.StringIO()
            with self.subTest(project=repr(project)), \
                 mock.patch.dict(verify_e2e.os.environ, environment, clear=True), \
                 mock.patch.object(verify_e2e, "create_run_fixture") as create, \
                 mock.patch.object(verify_e2e, "write_fixture_manifest") as writer, \
                 contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                result = verify_e2e.main(["run-fixture"])
            self.assertEqual(result, 1)
            self.assertEqual(stdout.getvalue(), "")
            self.assertEqual(stderr.getvalue(), "verification failed: HOST_ARGUMENT_INVALID\n")
            create.assert_not_called()
            writer.assert_not_called()

    def test_run_fixture_manifest_canonical_round_trip_and_schema_rejections(self):
        identity = valid_fixture_identity()
        canonical = verify_e2e.fixture_manifest_bytes(identity)
        self.assertLessEqual(len(canonical), 1024)
        self.assertFalse(canonical.startswith(b"\xef\xbb\xbf"))
        self.assertTrue(canonical.endswith(b"\n"))
        self.assertNotIn(b"\r", canonical)
        self.assertEqual(verify_e2e.parse_fixture_manifest_bytes(canonical), identity)
        self.assertEqual(identity["composeProject"], verify_e2e.RUN_FIXTURE_PROJECT)
        foreign = dict(identity)
        foreign["composeProject"] = "finguardops-kc241-e2e-unit01"
        with self.assertRaisesRegex(
            verify_e2e.VerificationError, "FIXTURE_MANIFEST_IDENTITY_INVALID"
        ):
            verify_e2e.fixture_manifest_bytes(foreign)
        with self.assertRaises(verify_e2e.VerificationError):
            verify_e2e.parse_fixture_manifest_bytes(
                canonical.replace(
                    verify_e2e.RUN_FIXTURE_PROJECT.encode(),
                    b"finguardops-keycloak-browser-e2x",
                )
            )
        text = canonical.decode("utf-8")
        malformed = {
            "duplicate": text.replace('"schemaVersion":1,', '"schemaVersion":1,"schemaVersion":1,', 1),
            "unknown": text[:-2] + ',"extra":"x"}\n',
            "reordered": text.replace('{"schemaVersion":1,"runId":', '{"runId":').replace(
                ',"repositoryId":', ',"schemaVersion":1,"repositoryId":', 1
            ),
            "missing": text.replace(',"caseId":"' + identity["caseId"] + '"', "", 1),
            "schema-float": text.replace('"schemaVersion":1,', '"schemaVersion":1.0,', 1),
            "wrong-risk": text.replace('"HIGH"', '"LOW"'),
            "wrong-status": text.replace('"OPEN"', '"IN_REVIEW"'),
            "uppercase-uuid": text.replace(identity["transactionId"], identity["transactionId"].upper()),
        }
        for name, candidate in malformed.items():
            with self.subTest(name=name), self.assertRaises(verify_e2e.VerificationError):
                verify_e2e.parse_fixture_manifest_bytes(candidate.encode("utf-8"))

    def test_run_fixture_manifest_atomic_write_and_collision_boundaries(self):
        identity = valid_fixture_identity()
        with tempfile.TemporaryDirectory(prefix="fixture-writer-") as directory:
            root = Path(directory)
            path = verify_e2e.write_fixture_manifest(root, identity)
            self.assertEqual(path.name, verify_e2e.FIXTURE_MANIFEST_NAME)
            self.assertEqual(tuple(item.name for item in root.iterdir()), (verify_e2e.FIXTURE_MANIFEST_NAME,))
            self.assertEqual(path.read_bytes(), verify_e2e.fixture_manifest_bytes(identity))
            with self.assertRaisesRegex(verify_e2e.VerificationError, "FIXTURE_MANIFEST_FINAL_EXISTS"):
                verify_e2e.write_fixture_manifest(root, identity)
        with tempfile.TemporaryDirectory(prefix="fixture-writer-") as directory, \
             mock.patch.object(verify_e2e, "rename_noreplace", side_effect=verify_e2e.VerificationError("FIXTURE_MANIFEST_RENAME_FAILED")):
            with self.assertRaisesRegex(verify_e2e.VerificationError, "FIXTURE_MANIFEST_RENAME_FAILED"):
                verify_e2e.write_fixture_manifest(Path(directory), identity)
            self.assertEqual(tuple(Path(directory).iterdir()), ())

    def test_run_fixture_manifest_create_write_and_flush_failures_are_fixed_and_clean(self):
        identity = valid_fixture_identity()
        with tempfile.TemporaryDirectory(prefix="fixture-writer-") as directory, \
             mock.patch.object(verify_e2e.os, "open", side_effect=OSError("NeverReflect secret path")):
            with self.assertRaisesRegex(verify_e2e.VerificationError, "FIXTURE_MANIFEST_TEMP_CREATE_FAILED"):
                verify_e2e.write_fixture_manifest(Path(directory), identity)
            self.assertEqual(tuple(Path(directory).iterdir()), ())

        class BrokenStream:
            def __init__(self, descriptor, fail_at):
                self.descriptor = descriptor
                self.fail_at = fail_at
            def __enter__(self):
                return self
            def __exit__(self, *_args):
                verify_e2e.os.close(self.descriptor)
            def write(self, _value):
                if self.fail_at == "write":
                    raise OSError("NeverReflect token")
            def flush(self):
                if self.fail_at == "flush":
                    raise OSError("NeverReflect credential")
            def fileno(self):
                return self.descriptor

        real_fdopen = verify_e2e.os.fdopen
        for stage in ("write", "flush"):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory(prefix="fixture-writer-") as directory, \
                 mock.patch.object(verify_e2e.os, "fdopen", side_effect=lambda descriptor, *_args, value=stage, **_kwargs: BrokenStream(descriptor, value)):
                with self.assertRaisesRegex(verify_e2e.VerificationError, "FIXTURE_MANIFEST_WRITE_FAILED") as error:
                    verify_e2e.write_fixture_manifest(Path(directory), identity)
                self.assertNotIn("NeverReflect", str(error.exception))
                self.assertEqual(tuple(Path(directory).iterdir()), ())
        self.assertTrue(callable(real_fdopen))

    def test_run_fixture_public_api_flow_uses_exact_response_identity(self):
        plan = valid_plan()
        transaction_response = {
            "transactionId": plan["transactionId"],
            "processingStatus": "ADDITIONAL_AUTH_REQUIRED",
            "riskLevel": "HIGH",
            "riskResponseOutcome": "ADDITIONAL_AUTH_REQUIRED",
            "adoptedDetectionResultId": "b81ade9f-d451-43e2-b97b-d7b24e9a0988",
            "caseId": valid_fixture_identity()["caseId"],
            "createdAt": "2026-09-20T00:00:00Z",
            "traceId": "not-exported",
        }
        responses = [
            {"eventId": plan["passwordEventId"]},
            {"eventId": plan["transferLimitEventId"]},
            transaction_response,
        ]
        with mock.patch.object(verify_e2e, "service_tokens", return_value=("tx-token", "behavior-token")), \
             mock.patch.object(verify_e2e, "request_backend", side_effect=responses) as request:
            result = verify_e2e.create_run_fixture(plan)
        self.assertEqual(result, {"transactionId": plan["transactionId"], "caseId": transaction_response["caseId"]})
        self.assertEqual(request.call_count, 3)
        for field, value in (("transactionId", valid_fixture_identity()["caseId"]), ("caseId", "not-a-uuid"), ("riskLevel", "LOW"), ("riskResponseOutcome", "ALLOW"), ("processingStatus", "COMPLETED"), ("createdAt", None), ("traceId", [])):
            broken = dict(transaction_response)
            broken[field] = value
            with self.subTest(field=field), \
                 mock.patch.object(verify_e2e, "service_tokens", return_value=("tx-token", "behavior-token")), \
                 mock.patch.object(verify_e2e, "request_backend", side_effect=[responses[0], responses[1], broken]), \
                 self.assertRaisesRegex(verify_e2e.VerificationError, "RUN_FIXTURE_TRANSACTION_RESPONSE_INVALID"):
                verify_e2e.create_run_fixture(plan)
        with mock.patch.object(verify_e2e, "service_tokens", side_effect=verify_e2e.VerificationError("SERVICE_AUTH_FAILED")), \
             self.assertRaisesRegex(verify_e2e.VerificationError, "SERVICE_AUTH_FAILED"):
            verify_e2e.create_run_fixture(plan)

    def test_run_fixture_before_after_preserve_authoritative_exact_validation(self):
        environment = valid_run_fixture_environment()
        contract = verify_e2e.load_owner_contract(environment)
        plan = valid_plan()
        with tempfile.TemporaryDirectory(prefix="fixture-host-") as parent:
            directory = Path(parent) / ("finguardops-keycloak-e2e-fixture-" + contract.run_id)
            directory.mkdir()
            identity = valid_fixture_identity()
            context = types.SimpleNamespace(
                contract=contract,
                environment=environment,
                project=verify_e2e.RUN_FIXTURE_PROJECT,
            )
            before_snapshot = snapshot_fixture()
            with mock.patch.object(verify_e2e, "create_plan", return_value=plan), \
                 mock.patch.object(verify_e2e, "publish_rules") as publish, \
                 mock.patch.object(verify_e2e, "transaction_cardinality", return_value=verify_e2e.expected_transaction_cardinality(False, False, False)), \
                 mock.patch.object(verify_e2e, "database_snapshot", return_value=before_snapshot), \
                 mock.patch.object(verify_e2e, "dependency_hit_counts", return_value=(0, 0)), \
                 mock.patch.object(verify_e2e, "backend_metric_totals", return_value=(0.0, 0.0)):
                state = verify_e2e.run_fixture_before(context, directory)
            publish.assert_called_once_with(context, before_diagnostics=True)
            encoded = verify_e2e.run_fixture_state_bytes(state)
            self.assertEqual(verify_e2e.parse_run_fixture_state(encoded), state)
            (directory / verify_e2e.FIXTURE_MANIFEST_NAME).write_bytes(
                verify_e2e.fixture_manifest_bytes(identity)
            )
            with mock.patch.object(verify_e2e, "transaction_cardinality", return_value=verify_e2e.expected_transaction_cardinality(True, True, False)), \
                 mock.patch.object(verify_e2e, "database_snapshot", return_value=snapshot_fixture()), \
                 mock.patch.object(verify_e2e, "assert_global_delta") as delta, \
                 mock.patch.object(verify_e2e, "dependency_hit_counts", return_value=(1, 1)), \
                 mock.patch.object(verify_e2e, "backend_metric_totals", return_value=(1.0, 1.0)), \
                 mock.patch.object(verify_e2e, "transaction_case_id", return_value=identity["caseId"]):
                verify_e2e.run_fixture_after(context, directory, state)
            delta.assert_called_once()

    def test_run_fixture_worker_writes_authoritative_identity_and_never_reflects_raw_failure(self):
        plan = valid_plan()
        response = {"transactionId": plan["transactionId"], "caseId": valid_fixture_identity()["caseId"]}
        plan_value = base64.b64encode(json.dumps(plan, separators=(",", ":")).encode("utf-8")).decode("ascii")
        output = io.StringIO()
        environment = valid_run_fixture_environment() | {
            verify_e2e.FIXTURE_PLAN_ENVIRONMENT: plan_value
        }
        with mock.patch.dict(verify_e2e.os.environ, environment, clear=True), \
             mock.patch.object(verify_e2e, "create_run_fixture", return_value=response), \
             mock.patch.object(verify_e2e, "write_fixture_manifest") as writer, \
             contextlib.redirect_stdout(output):
            verify_e2e.run_fixture_worker()
        identity = writer.call_args.args[1]
        self.assertEqual(identity["transactionId"], plan["transactionId"])
        self.assertEqual(identity["caseId"], response["caseId"])
        self.assertEqual(identity["composeProject"], verify_e2e.RUN_FIXTURE_PROJECT)
        self.assertNotIn("traceId", identity)
        self.assertEqual(output.getvalue(), "run fixture completed: risk=HIGH outcome=ADDITIONAL_AUTH_REQUIRED case=OPEN\n")

        stderr = io.StringIO()
        with mock.patch.dict(verify_e2e.os.environ, environment, clear=True), \
             mock.patch.object(verify_e2e, "create_run_fixture", side_effect=RuntimeError("NeverReflectRawToken")), \
             contextlib.redirect_stderr(stderr):
            result = verify_e2e.main(["run-fixture"])
        self.assertEqual(result, 1)
        self.assertEqual(stderr.getvalue(), "verification failed: UNEXPECTED_ERROR\n")

    def test_run_fixture_before_after_cli_uses_canonical_stdin_state(self):
        state = valid_run_fixture_state()
        environment = valid_run_fixture_environment()
        encoded = base64.b64encode(verify_e2e.run_fixture_state_bytes(state)).decode("ascii")
        with tempfile.TemporaryDirectory(prefix="finguardops-keycloak-e2e-fixture-") as parent:
            directory = Path(parent) / ("finguardops-keycloak-e2e-fixture-" + environment["FINGUARDOPS_E2E_RUN_ID"])
            directory.mkdir()
            before_output = io.StringIO()
            with mock.patch.dict(verify_e2e.os.environ, environment, clear=True), \
                 mock.patch.object(verify_e2e, "run_fixture_before", return_value=state) as before, \
                 contextlib.redirect_stdout(before_output):
                result = verify_e2e.main([
                    "run-fixture-before", "--repo-root", parent,
                    "--project", verify_e2e.RUN_FIXTURE_PROJECT,
                    "--fixture-directory", str(directory),
                ])
            self.assertEqual(result, 0)
            self.assertEqual(before_output.getvalue(), encoded + "\n")
            before.assert_called_once()

            stdin = types.SimpleNamespace(buffer=io.BytesIO((encoded + "\r\n").encode("ascii")))
            after_output = io.StringIO()
            with mock.patch.dict(verify_e2e.os.environ, environment, clear=True), \
                 mock.patch.object(verify_e2e.sys, "stdin", stdin), \
                 mock.patch.object(verify_e2e, "run_fixture_after") as after, \
                 contextlib.redirect_stdout(after_output):
                result = verify_e2e.main([
                    "run-fixture-after", "--repo-root", parent,
                    "--project", verify_e2e.RUN_FIXTURE_PROJECT,
                    "--fixture-directory", str(directory),
                ])
            self.assertEqual(result, 0)
            self.assertEqual(after_output.getvalue(), "")
            after.assert_called_once()

    def test_run_fixture_before_cli_emits_only_fixed_single_line_failures(self):
        environment = valid_run_fixture_environment()
        fixed_codes = tuple(
            code
            for stage in verify_e2e.BEFORE_NATIVE_FAILURE_CODES.values()
            for code in stage.values()
        ) + (
            "DATABASE_TRANSACTION_CARDINALITY_INVALID",
            "FIXTURE_DIRECTORY_INVALID",
            "INGESTION_PLAN_INVALID",
            "OVERALL_DEADLINE_EXCEEDED",
            "OWNER_CONTRACT_INVALID",
            "RULE_ACTIVATION_TIMEOUT",
            "RULE_PUBLICATION_STATE_INVALID",
            "RUN_FIXTURE_STATE_IDENTITY_INVALID",
            "RUN_FIXTURE_STATE_INVALID",
            "RUN_FIXTURE_STATE_TOO_LARGE",
        )
        with tempfile.TemporaryDirectory(prefix="finguardops-keycloak-e2e-fixture-") as parent:
            directory = Path(parent) / (
                "finguardops-keycloak-e2e-fixture-"
                + environment["FINGUARDOPS_E2E_RUN_ID"]
            )
            directory.mkdir()
            argv = [
                "run-fixture-before", "--repo-root", parent,
                "--project", verify_e2e.RUN_FIXTURE_PROJECT,
                "--fixture-directory", str(directory),
            ]
            for code in fixed_codes:
                stdout, stderr = io.StringIO(), io.StringIO()
                with self.subTest(code=code), \
                     mock.patch.dict(verify_e2e.os.environ, environment, clear=True), \
                     mock.patch.object(
                         verify_e2e, "run_fixture_before",
                         side_effect=verify_e2e.VerificationError(code),
                     ), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    result = verify_e2e.main(argv)
                self.assertEqual(result, 1)
                self.assertEqual(stdout.getvalue(), "")
                self.assertEqual(stderr.getvalue(), "verification failed: " + code + "\n")

            for failure, expected, exit_code in (
                (OSError("raw-path-must-not-print"), "INPUT_INVALID", 2),
                (RuntimeError("raw-token-must-not-print"), "UNEXPECTED_ERROR", 1),
            ):
                stdout, stderr = io.StringIO(), io.StringIO()
                with mock.patch.dict(verify_e2e.os.environ, environment, clear=True), \
                     mock.patch.object(verify_e2e, "run_fixture_before", side_effect=failure), \
                     contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    result = verify_e2e.main(argv)
                self.assertEqual(result, exit_code)
                self.assertEqual(stdout.getvalue(), "")
                self.assertEqual(stderr.getvalue(), "verification failed: " + expected + "\n")
                self.assertNotIn("raw-", stderr.getvalue())

    def test_run_fixture_state_binds_every_owner_identity_before_after(self):
        state = valid_run_fixture_state()
        canonical = verify_e2e.run_fixture_state_bytes(state)
        self.assertEqual(verify_e2e.parse_run_fixture_state(canonical), state)
        context = types.SimpleNamespace(
            contract=verify_e2e.load_owner_contract(valid_owner_environment()),
            environment=valid_run_fixture_environment(),
            project=state["composeProject"],
        )
        for key, replacement in (
            ("runId", "f" * 32),
            ("repositoryId", "f" * 64),
            ("commitSha", "d" * 40),
            ("treeSha", "e" * 40),
        ):
            candidate = dict(state)
            candidate[key] = replacement
            parsed = verify_e2e.parse_run_fixture_state(
                verify_e2e.run_fixture_state_bytes(candidate)
            )
            with self.subTest(key=key), tempfile.TemporaryDirectory() as parent:
                directory = Path(parent) / (
                    "finguardops-keycloak-e2e-fixture-" + context.contract.run_id
                )
                directory.mkdir()
                with self.assertRaisesRegex(
                    verify_e2e.VerificationError, "RUN_FIXTURE_STATE_IDENTITY_INVALID"
                ), mock.patch.object(verify_e2e, "database_snapshot") as database:
                    verify_e2e.run_fixture_after(context, directory, parsed)
                database.assert_not_called()

        candidate = dict(state)
        candidate["composeProject"] = "finguardops-kc241-e2e-unit01"
        with self.assertRaisesRegex(
            verify_e2e.VerificationError, "RUN_FIXTURE_STATE_IDENTITY_INVALID"
        ):
            verify_e2e.run_fixture_state_bytes(candidate)

    def test_run_fixture_state_rejects_noncanonical_duplicate_and_control_identity(self):
        state = valid_run_fixture_state()
        canonical = verify_e2e.run_fixture_state_bytes(state)
        candidates = (
            canonical.replace(b'{"schemaVersion":1,', b'{ "schemaVersion":1,', 1),
            canonical.replace(b'{"schemaVersion":1,', b'{"schemaVersion":1,"schemaVersion":1,', 1),
            canonical.replace(b'{"schemaVersion":1,', b'{"schemaVersion":1.0,', 1),
            canonical.replace(state["composeProject"].encode(), (state["composeProject"] + "\u200b").encode()),
            b"\xef\xbb\xbf" + canonical,
        )
        for candidate in candidates:
            with self.assertRaises(verify_e2e.VerificationError):
                verify_e2e.parse_run_fixture_state(candidate)


if __name__ == "__main__":
    unittest.main()

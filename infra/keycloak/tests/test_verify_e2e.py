import base64
import contextlib
import copy
import io
import importlib.util
import json
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


def snapshot_fixture(rows_by_table=None):
    rows_by_table = rows_by_table or {}
    return {
        table: verify_e2e.table_snapshot(table, tuple(rows_by_table.get(table, ())))
        for table in verify_e2e.BUSINESS_TABLES
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
            ({"sub": "32A6A5DB-71E4-4E58-8B3F-EC8C2C07B69A"}, {}),
            ({"iss": verify_e2e.ISSUER + "/"}, {}),
            ({}, {"alg": "HS256"}),
            ({}, {"kid": ""}),
            ({"exp": 1001}, {}),
            ({"iat": True}, {}),
        ]
        for payload_change, header_change in mutations:
            with self.assertRaises(verify_e2e.VerificationError):
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
        completed = subprocess.CompletedProcess(
            ["child"], 1, stdout=b"", stderr=b"compose prefix\nverification failed: METRIC_INVALID\ncompose suffix\n"
        )
        with mock.patch("subprocess.run", return_value=completed), self.assertRaisesRegex(
            verify_e2e.VerificationError, "CHILD_METRIC_INVALID"
        ):
            verify_e2e.run_command(
                ["child"], timeout=1, cwd=Path.cwd(), environment={}
            )
        leaked = subprocess.CompletedProcess(
            ["child"], 1, stdout=b"", stderr=b"verification failed: TOKEN NeverPrintSecret\n"
        )
        with mock.patch("subprocess.run", return_value=leaked), self.assertRaisesRegex(
            verify_e2e.VerificationError, "^SUBPROCESS_FAILED$"
        ) as raised:
            verify_e2e.run_command(
                ["child"], timeout=1, cwd=Path.cwd(), environment={}
            )
        self.assertNotIn("NeverPrintSecret", str(raised.exception))

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
        self.assertNotIn("--build", context.compose)

    def test_all_runtime_uses_no_build_pull_never_and_never_cleans_up(self):
        environment = valid_owner_environment()
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


if __name__ == "__main__":
    unittest.main()

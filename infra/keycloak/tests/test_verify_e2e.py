import base64
import contextlib
import copy
import io
import json
import re
import shutil
import subprocess
import sys
import tempfile
import time
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
        "secrets": [{"source": name, "target": name} for name in secrets],
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
        "secrets": [{"source": name, "target": name} for name in verify_e2e.EXPECTED_SECRETS["keycloak"]],
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
    verifier["depends_on"] = {"keycloak-bootstrap": {"condition": "service_completed_successfully"}}
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
    return {
        "services": services,
        "networks": {"application": {"internal": True}, "observability": {"internal": True}, "prometheus-ui": {}},
        "volumes": {name: {} for name in verify_e2e.EXPECTED_MERGED_NAMED_VOLUMES},
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


class VerifyTests(unittest.TestCase):
    def assert_static_failure(self, mutate, code):
        config = valid_config()
        mutate(config)
        with self.assertRaisesRegex(verify_e2e.VerificationError, code):
            verify_e2e.validate_static(config, valid_realm())

    def test_static_valid_configuration(self):
        verify_e2e.validate_static(valid_config(), valid_realm())

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

    def test_realm_disabled_rejected(self):
        realm = valid_realm()
        realm["enabled"] = False
        with self.assertRaisesRegex(verify_e2e.VerificationError, "STATIC_REALM_CONTRACT"):
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
        self.assert_static_failure(lambda c: c["services"]["keycloak-verify"]["secrets"].append({"source": "keycloak_bootstrap_admin_secret"}), "STATIC_SECRET_BOUNDARY")

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

    def test_main_redacts_unexpected_exception(self):
        stderr = io.StringIO()
        with mock.patch.object(verify_e2e, "runtime", side_effect=RuntimeError("NeverPrintSecret")):
            with contextlib.redirect_stderr(stderr):
                result = verify_e2e.main(["runtime"])
        self.assertEqual(result, 1)
        self.assertNotIn("NeverPrintSecret", stderr.getvalue())

    def test_bounded_poll_stops_at_limit(self):
        attempts = []
        with mock.patch.object(time, "sleep"):
            with self.assertRaisesRegex(verify_e2e.VerificationError, "READINESS_TIMEOUT"):
                verify_e2e.bounded_poll(lambda: attempts.append(1) and False, attempts=3, interval=0)
        self.assertEqual(len(attempts), 3)

    def test_invalid_poll_bounds_rejected(self):
        with self.assertRaisesRegex(verify_e2e.VerificationError, "POLL_BOUNDS_INVALID"):
            verify_e2e.bounded_poll(lambda: True, attempts=0)

    def test_cleanup_target_must_be_exact(self):
        verify_e2e.validate_cleanup_target("finguardops-kc235-fresh", "finguardops-kc235-fresh")
        for value in ("finguardops-kc235", "../finguardops-kc235-fresh", "FINGUARDOPS-KC235-FRESH"):
            with self.assertRaises(verify_e2e.VerificationError):
                verify_e2e.validate_cleanup_target(value, "finguardops-kc235-fresh")

    def test_static_source_mutations_are_killed(self):
        verifier_source = Path(verify_e2e.__file__).read_text(encoding="utf-8")
        test_source = Path(__file__).read_text(encoding="utf-8")
        realm_source = (Path(__file__).resolve().parents[1] / "realm/finguardops-local-realm.json").read_text(encoding="utf-8")
        mutations = [
            ("privileged", 'if service.get("privileged") is True:', "if False:", "test_keycloak_privileged_rejected"),
            ("cap-add", 'if service.get("cap_add") not in (None, []):', "if False:", "test_keycloak_cap_add_rejected"),
            ("docker-socket", "if is_docker_socket_path(source) or is_docker_socket_path(target):", "if False:", "test_keycloak_docker_socket_rejected"),
            ("command", 'if service.get("command") != EXPECTED_COMMANDS[service_name]:', "if False:", "test_keycloak_start_dev_command_rejected"),
            ("health", 'if keycloak_env.get("KC_HEALTH_ENABLED") != "true":', "if False:", "test_keycloak_health_false_rejected"),
            ("https-port", 'if keycloak_env.get("KC_HTTPS_PORT") != "8443":', "if False:", "test_keycloak_https_port_rejected"),
            ("backend-dependency", 'if {"keycloak", "keycloak-bootstrap", "keycloak-verify"}.intersection(dependencies):', "if False:", "test_backend_keycloak_dependency_rejected"),
            ("realm-enabled", 'or realm.get("enabled") is not True', "or False", "test_realm_disabled_rejected"),
            ("bootstrap-source-read-only", 'or bool(mount.get("read_only", False)) is not read_only', "or False", "test_bootstrap_source_writable_rejected"),
            ("verifier-source-read-only", 'or bool(mount.get("read_only", False)) is not read_only', "or False", "test_verifier_source_writable_rejected"),
        ]
        for label, old, new, test_name in mutations:
            with self.subTest(mutation=label), tempfile.TemporaryDirectory(prefix="finguardops-keycloak-mutation-") as directory:
                root = Path(directory)
                tests = root / "tests"
                realm = root / "realm"
                tests.mkdir()
                realm.mkdir()
                self.assertEqual(verifier_source.count(old), 1, "mutation target must be unique: " + label)
                (tests / "test_verify_e2e.py").write_text(test_source, encoding="utf-8", newline="\n")
                (realm / "finguardops-local-realm.json").write_text(realm_source, encoding="utf-8", newline="\n")
                verifier_copy = root / "verify_e2e.py"
                verifier_copy.write_text(verifier_source, encoding="utf-8", newline="\n")
                command = [sys.executable, "-B", str(tests / "test_verify_e2e.py"), "VerifyTests." + test_name]
                baseline = subprocess.run(
                    command,
                    cwd=root,
                    capture_output=True,
                    text=True,
                    timeout=30,
                    check=False,
                )
                self.assertEqual(baseline.returncode, 0, "mutation baseline failed: " + label)
                verifier_copy.write_text(verifier_source.replace(old, new, 1), encoding="utf-8", newline="\n")
                result = subprocess.run(
                    command,
                    cwd=root,
                    capture_output=True,
                    text=True,
                    timeout=30,
                    check=False,
                )
                rendered = result.stdout + result.stderr
                self.assertNotEqual(result.returncode, 0, "source mutation survived: " + label)
                self.assertIsNone(
                    re.search(r"[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}", rendered),
                    "mutation output exposed a JWT-like value",
                )


if __name__ == "__main__":
    unittest.main()

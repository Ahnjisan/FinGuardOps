#!/usr/bin/env python3
"""Static and runtime verifier for the local Keycloak authentication boundary."""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import socket
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any, Callable

KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.7.3@sha256:ff4257d0d64efbe99ed1ddfaf07765cc3c36dc7518bf8324d41961327f441c54"
HELPER_IMAGE = "python:3.12.11-slim-bookworm@sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7"
ISSUER = "https://localhost:8443/realms/finguardops-local"
JWK_SET_URI = "http://127.0.0.1:8082/realms/finguardops-local/protocol/openid-connect/certs"
PUBLIC_JWK_SET_URI = ISSUER + "/protocol/openid-connect/certs"
INTERNAL_BASE_URL = "http://127.0.0.1:8082"
MANAGEMENT_BASE_URL = "http://127.0.0.1:9000"
FIXTURE_ISSUER = "https://local-jwt.fixture.finguardops.invalid"
FIXTURE_JWK = "http://127.0.0.1:8002/oauth2/jwks"
AUDIENCE = "finguardops-backend-api"
REALM = "finguardops-local"
CERTIFICATE = Path("/run/secrets/keycloak_tls_certificate")
TRANSACTION_SECRET = Path("/run/secrets/transaction_service_client_secret")
BEHAVIOR_SECRET = Path("/run/secrets/behavior_service_client_secret")
SECRET_PATTERN = re.compile(rb"[A-Za-z0-9_-]{32,128}\Z")
UUID4_PATTERN = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\Z"
)
ALL_ROLES = {
    "FDS_VIEWER",
    "FDS_ANALYST",
    "FDS_APPROVER",
    "RULE_OPERATOR",
    "RECOVERY_OPERATOR",
    "PLATFORM_ADMIN",
    "TRANSACTION_INGESTOR",
    "BEHAVIOR_INGESTOR",
}
EXPECTED_SECRETS = {
    "keycloak": {
        "keycloak_bootstrap_admin_secret",
        "keycloak_tls_certificate",
        "keycloak_tls_private_key",
    },
    "keycloak-bootstrap": {
        "keycloak_bootstrap_admin_secret",
        "transaction_service_client_secret",
        "behavior_service_client_secret",
    },
    "keycloak-verify": {
        "transaction_service_client_secret",
        "behavior_service_client_secret",
        "keycloak_tls_certificate",
    },
}
EXPECTED_KEYCLOAK_SERVICES = {
    "ai-service",
    "alertmanager",
    "alertmanager-webhook",
    "backend",
    "external-risk-mock",
    "grafana",
    "keycloak",
    "keycloak-bootstrap",
    "keycloak-verify",
    "postgresql",
    "prometheus",
}
EXPECTED_MERGED_NAMED_VOLUMES = {
    "prometheus-data",
    "alertmanager-data",
    "grafana-data",
    "keycloak-data",
}
EXPECTED_KEYCLOAK_ENV = {
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
}
EXPECTED_ENTRYPOINTS = {
    "keycloak": ["bash", "/opt/finguardops/start-keycloak.sh"],
    "keycloak-bootstrap": ["python", "-B", "/opt/finguardops/bootstrap.py"],
    "keycloak-verify": ["python", "-B", "/opt/finguardops/verify_e2e.py"],
}
EXPECTED_COMMANDS = {
    "keycloak": [],
    "keycloak-bootstrap": ["reconcile"],
    "keycloak-verify": ["runtime"],
}
EXPECTED_VOLUME_MOUNTS = {
    "keycloak": {
        "/opt/keycloak/data": ("volume", "keycloak-data", False),
        "/opt/keycloak/data/import/finguardops-local-realm.json": (
            "bind",
            "infra/keycloak/realm/finguardops-local-realm.json",
            True,
        ),
        "/opt/finguardops/start-keycloak.sh": (
            "bind",
            "infra/keycloak/start-keycloak.sh",
            True,
        ),
    },
    "keycloak-bootstrap": {
        "/opt/finguardops/bootstrap.py": ("bind", "infra/keycloak/bootstrap.py", True),
    },
    "keycloak-verify": {
        "/opt/finguardops/verify_e2e.py": ("bind", "infra/keycloak/verify_e2e.py", True),
    },
}


class VerificationError(RuntimeError):
    """Contains a fixed safe code only."""


def fail(code: str) -> None:
    raise VerificationError(code)


def environment(service: dict[str, Any]) -> dict[str, str]:
    raw = service.get("environment", {})
    if isinstance(raw, dict):
        return {str(key): str(value) for key, value in raw.items()}
    if isinstance(raw, list):
        result = {}
        for item in raw:
            if isinstance(item, str) and "=" in item:
                key, value = item.split("=", 1)
                result[key] = value
        return result
    fail("STATIC_ENVIRONMENT_INVALID")


def dependency_condition(service: dict[str, Any], dependency: str) -> str | None:
    raw = service.get("depends_on", {})
    if not isinstance(raw, dict) or dependency not in raw:
        return None
    value = raw[dependency]
    return value.get("condition") if isinstance(value, dict) else None


def secret_sources(service: dict[str, Any]) -> set[str]:
    result: set[str] = set()
    raw = service.get("secrets", [])
    if not isinstance(raw, list):
        fail("STATIC_SECRET_MOUNT_INVALID")
    for item in raw:
        if isinstance(item, str):
            result.add(item)
        elif isinstance(item, dict) and isinstance(item.get("source"), str):
            result.add(item["source"])
        else:
            fail("STATIC_SECRET_MOUNT_INVALID")
    return result


def validate_secret_mounts(service: dict[str, Any], expected: set[str]) -> None:
    raw = service.get("secrets", [])
    if not isinstance(raw, list) or len(raw) != len(expected):
        fail("STATIC_SECRET_BOUNDARY")
    found = []
    for item in raw:
        if not isinstance(item, dict):
            fail("STATIC_SECRET_BOUNDARY")
        source = item.get("source")
        target = item.get("target")
        if not isinstance(source, str) or target != source:
            fail("STATIC_SECRET_BOUNDARY")
        found.append(source)
    if len(found) != len(set(found)) or set(found) != expected:
        fail("STATIC_SECRET_BOUNDARY")


def normalized_path(value: Any) -> str:
    if not isinstance(value, str):
        return ""
    result = value.strip().lower().replace("\\", "/")
    while "///" in result:
        result = result.replace("///", "//")
    return result.rstrip("/")


def is_docker_socket_path(value: Any) -> bool:
    path = normalized_path(value)
    return (
        path.endswith("/docker.sock")
        or path.endswith("docker.sock")
        or "//./pipe/docker_engine" in path
        or path.endswith("/pipe/docker_engine")
        or path.endswith("docker_engine")
    )


def validate_no_privilege_escape(service: dict[str, Any], code: str) -> None:
    if service.get("privileged") is True:
        fail(code + "_PRIVILEGED")
    if service.get("cap_add") not in (None, []):
        fail(code + "_CAP_ADD")
    if service.get("devices") not in (None, []) or service.get("device_cgroup_rules") not in (None, []):
        fail(code + "_DEVICE")
    if service.get("pid") is not None or service.get("ipc") is not None:
        fail(code + "_NAMESPACE")
    for mount in service.get("volumes", []) or []:
        if isinstance(mount, dict):
            source, target = mount.get("source"), mount.get("target")
        elif isinstance(mount, str):
            parts = mount.split(":")
            source = parts[0] if parts else ""
            target = parts[1] if len(parts) > 1 else ""
        else:
            fail(code + "_MOUNT")
        if is_docker_socket_path(source) or is_docker_socket_path(target):
            fail(code + "_DOCKER_SOCKET")


def source_matches(actual: Any, expected: str, mount_type: str) -> bool:
    if mount_type == "volume":
        return actual == expected
    path = normalized_path(actual)
    suffix = normalized_path(expected)
    return path == suffix or path.endswith("/" + suffix)


def validate_volume_mounts(service_name: str, service: dict[str, Any]) -> None:
    raw = service.get("volumes", [])
    expected = EXPECTED_VOLUME_MOUNTS[service_name]
    if not isinstance(raw, list) or len(raw) != len(expected):
        fail("STATIC_" + service_name.upper().replace("-", "_") + "_MOUNT")
    found: dict[str, dict[str, Any]] = {}
    for mount in raw:
        if not isinstance(mount, dict) or not isinstance(mount.get("target"), str):
            fail("STATIC_" + service_name.upper().replace("-", "_") + "_MOUNT")
        target = mount["target"]
        if target in found:
            fail("STATIC_" + service_name.upper().replace("-", "_") + "_MOUNT")
        found[target] = mount
    if set(found) != set(expected):
        fail("STATIC_" + service_name.upper().replace("-", "_") + "_MOUNT")
    for target, (mount_type, source, read_only) in expected.items():
        mount = found[target]
        if (
            mount.get("type") != mount_type
            or not source_matches(mount.get("source"), source, mount_type)
            or bool(mount.get("read_only", False)) is not read_only
        ):
            fail("STATIC_" + service_name.upper().replace("-", "_") + "_MOUNT")


def validate_process_contract(service_name: str, service: dict[str, Any]) -> None:
    if service.get("entrypoint") != EXPECTED_ENTRYPOINTS[service_name]:
        fail("STATIC_" + service_name.upper().replace("-", "_") + "_ENTRYPOINT")
    if service.get("command") != EXPECTED_COMMANDS[service_name]:
        fail("STATIC_" + service_name.upper().replace("-", "_") + "_COMMAND")


def validate_keycloak_environment(keycloak_env: dict[str, str]) -> None:
    kc_keys = {key for key in keycloak_env if key.startswith("KC_")}
    if kc_keys != set(EXPECTED_KEYCLOAK_ENV):
        fail("STATIC_KEYCLOAK_ENV_ALLOWLIST")
    if keycloak_env.get("KC_HEALTH_ENABLED") != "true":
        fail("STATIC_KEYCLOAK_HEALTH")
    if keycloak_env.get("KC_HTTPS_PORT") != "8443":
        fail("STATIC_KEYCLOAK_HTTPS_PORT")
    for key, value in EXPECTED_KEYCLOAK_ENV.items():
        if key not in {"KC_HEALTH_ENABLED", "KC_HTTPS_PORT"} and keycloak_env.get(key) != value:
            fail("STATIC_KEYCLOAK_ENV_VALUE")


def validate_backend_dependencies(backend: dict[str, Any]) -> None:
    dependencies = backend.get("depends_on", {})
    if not isinstance(dependencies, dict):
        fail("STATIC_BACKEND_DEPENDENCY")
    if {"keycloak", "keycloak-bootstrap", "keycloak-verify"}.intersection(dependencies):
        fail("STATIC_BACKEND_DEPENDENCY")


def published_ports(service: dict[str, Any]) -> list[tuple[str, str, str]]:
    result = []
    for item in service.get("ports", []) or []:
        if isinstance(item, str):
            parts = item.split(":")
            if len(parts) == 3:
                result.append((parts[0], parts[1], parts[2]))
        elif isinstance(item, dict):
            result.append(
                (
                    str(item.get("host_ip", "")),
                    str(item.get("published", "")),
                    str(item.get("target", "")),
                )
            )
    return result


def named_volume_sources(service: dict[str, Any]) -> set[str]:
    result: set[str] = set()
    for item in service.get("volumes", []) or []:
        if isinstance(item, dict) and item.get("type") == "volume" and isinstance(item.get("source"), str):
            result.add(item["source"])
        elif isinstance(item, str):
            source = item.split(":", 1)[0]
            if source and not source.startswith((".", "/")):
                result.add(source)
    return result


def mount_sources(service: dict[str, Any]) -> set[str]:
    result: set[str] = set()
    for item in service.get("volumes", []) or []:
        if isinstance(item, dict) and isinstance(item.get("source"), str):
            result.add(item["source"])
        elif isinstance(item, str):
            result.add(item.split(":", 1)[0])
    return result


def validate_realm(realm: dict[str, Any]) -> None:
    serialized = json.dumps(realm, separators=(",", ":"))
    forbidden_keys = {"secret", "password", "privateKey", "private_key"}

    def walk(value: Any) -> None:
        if isinstance(value, dict):
            if forbidden_keys.intersection(value):
                fail("STATIC_REALM_SECRET_PRESENT")
            for nested in value.values():
                walk(nested)
        elif isinstance(value, list):
            for nested in value:
                walk(nested)

    walk(realm)
    if "*" in serialized:
        fail("STATIC_REALM_WILDCARD")
    lifespan = realm.get("accessTokenLifespan")
    if (
        realm.get("realm") != REALM
        or realm.get("enabled") is not True
        or realm.get("registrationAllowed") is not False
        or isinstance(lifespan, bool)
        or not isinstance(lifespan, int)
        or lifespan < 1
        or lifespan > 900
    ):
        fail("STATIC_REALM_CONTRACT")
    if realm.get("defaultSignatureAlgorithm") != "RS256":
        fail("STATIC_REALM_ALGORITHM")
    role_names = [role.get("name") for role in realm.get("roles", {}).get("realm", [])]
    if len(role_names) != 8 or set(role_names) != ALL_ROLES:
        fail("STATIC_REALM_ROLES")
    clients = {client.get("clientId"): client for client in realm.get("clients", [])}
    if set(clients) != {
        "finguardops-frontend",
        "finguardops-transaction-ingestor",
        "finguardops-behavior-ingestor",
    }:
        fail("STATIC_REALM_CLIENTS")
    frontend = clients["finguardops-frontend"]
    if (
        frontend.get("publicClient") is not True
        or frontend.get("standardFlowEnabled") is not True
        or frontend.get("implicitFlowEnabled") is not False
        or frontend.get("directAccessGrantsEnabled") is not False
        or frontend.get("serviceAccountsEnabled") is not False
        or frontend.get("fullScopeAllowed") is not False
        or frontend.get("redirectUris") != ["http://localhost:5173/auth/callback"]
        or frontend.get("webOrigins") != ["http://localhost:5173"]
        or frontend.get("attributes", {}).get("pkce.code.challenge.method") != "S256"
        or frontend.get("attributes", {}).get("post.logout.redirect.uris") != "http://localhost:5173/"
        or frontend.get("attributes", {}).get("oauth2.device.authorization.grant.enabled") != "false"
        or frontend.get("attributes", {}).get("oidc.ciba.grant.enabled") != "false"
    ):
        fail("STATIC_USER_CLIENT_CONTRACT")
    for client_id in ("finguardops-transaction-ingestor", "finguardops-behavior-ingestor"):
        client = clients[client_id]
        if (
            client.get("publicClient") is not False
            or client.get("serviceAccountsEnabled") is not True
            or client.get("standardFlowEnabled") is not False
            or client.get("implicitFlowEnabled") is not False
            or client.get("directAccessGrantsEnabled") is not False
            or client.get("fullScopeAllowed") is not False
            or client.get("attributes", {}).get("oauth2.device.authorization.grant.enabled") != "false"
            or client.get("attributes", {}).get("oidc.ciba.grant.enabled") != "false"
        ):
            fail("STATIC_SERVICE_CLIENT_CONTRACT")
    for client in clients.values():
        scopes = client.get("defaultClientScopes", []) + client.get("optionalClientScopes", [])
        if "offline_access" in scopes or "offline" in scopes or "roles" in scopes:
            fail("STATIC_FORBIDDEN_SCOPE")


def validate_static(config: dict[str, Any], realm: dict[str, Any] | None = None) -> None:
    services = config.get("services")
    if not isinstance(services, dict) or "backend" not in services:
        fail("STATIC_SERVICE_SET")
    has_keycloak = "keycloak" in services
    has_fixture = "local-jwt-fixture" in services
    if has_keycloak and has_fixture:
        fail("STATIC_MULTIPLE_ISSUERS")
    backend_env = environment(services["backend"])
    issuer = backend_env.get("FINGUARDOPS_SECURITY_ISSUER")
    jwk = backend_env.get("FINGUARDOPS_SECURITY_JWK_SET_URI")
    if has_fixture:
        if issuer != FIXTURE_ISSUER or jwk != FIXTURE_JWK:
            fail("STATIC_ISSUER_JWK_MIXED")
        return
    if not has_keycloak or not {"keycloak-bootstrap", "keycloak-verify"}.issubset(services):
        fail("STATIC_SERVICE_SET")
    if set(services) != EXPECTED_KEYCLOAK_SERVICES:
        fail("STATIC_SERVICE_SET")
    if issuer != ISSUER or jwk != JWK_SET_URI:
        fail("STATIC_ISSUER_JWK_MIXED")
    if backend_env.get("FINGUARDOPS_SECURITY_INSECURE_LOOPBACK_JWK_ALLOWED") != "true":
        fail("STATIC_LOOPBACK_OPT_IN")
    validate_backend_dependencies(services["backend"])
    if services["keycloak"].get("image") != KEYCLOAK_IMAGE:
        fail("STATIC_KEYCLOAK_IMAGE")
    for name in ("keycloak-bootstrap", "keycloak-verify"):
        service = services[name]
        if service.get("image") != HELPER_IMAGE:
            fail("STATIC_HELPER_IMAGE")
        validate_process_contract(name, service)
        validate_no_privilege_escape(service, "STATIC_HELPER")
        validate_volume_mounts(name, service)
        if str(service.get("user")) != "10001:10001":
            fail("STATIC_HELPER_USER")
        if service.get("read_only") is not True:
            fail("STATIC_HELPER_READ_ONLY")
        if set(service.get("cap_drop", [])) != {"ALL"}:
            fail("STATIC_HELPER_CAPABILITIES")
        if "no-new-privileges:true" not in service.get("security_opt", []):
            fail("STATIC_HELPER_PRIVILEGES")
        if any(key in service for key in ("ports", "expose", "networks")):
            fail("STATIC_HELPER_ISOLATION")
        if service.get("network_mode") != "service:backend":
            fail("STATIC_NETWORK_MODE")
        if any(isinstance(volume, dict) and volume.get("type") == "volume" for volume in service.get("volumes", [])):
            fail("STATIC_HELPER_NAMED_VOLUME")
        if not any(all(flag in str(item) for flag in ("nosuid", "nodev", "noexec")) for item in service.get("tmpfs", [])):
            fail("STATIC_HELPER_TMPFS")
    keycloak = services["keycloak"]
    validate_process_contract("keycloak", keycloak)
    validate_no_privilege_escape(keycloak, "STATIC_KEYCLOAK")
    validate_volume_mounts("keycloak", keycloak)
    if keycloak.get("network_mode") != "service:backend":
        fail("STATIC_NETWORK_MODE")
    if any(key in keycloak for key in ("ports", "expose", "networks")):
        fail("STATIC_KEYCLOAK_NETWORK_DECLARATION")
    keycloak_env = environment(keycloak)
    validate_keycloak_environment(keycloak_env)
    if published_ports(services["backend"]) != [("127.0.0.1", "8443", "8443")]:
        fail("STATIC_HOST_PORT")
    if any(port[2] in {"8082", "9000"} for candidate in services.values() for port in published_ports(candidate)):
        fail("STATIC_INTERNAL_PORT_PUBLISHED")
    backend_networks = services["backend"].get("networks", {})
    if isinstance(backend_networks, list):
        backend_network_names = set(backend_networks)
    elif isinstance(backend_networks, dict):
        backend_network_names = set(backend_networks)
    else:
        fail("STATIC_PUBLIC_NETWORK")
    networks = config.get("networks", {})
    if "keycloak-public" in networks:
        fail("STATIC_PUBLIC_NETWORK")
    if not any(
        name in networks and isinstance(networks[name], dict) and networks[name].get("internal") is not True
        for name in backend_network_names
    ):
        fail("STATIC_PUBLIC_NETWORK")
    if set(config.get("volumes", {})) != EXPECTED_MERGED_NAMED_VOLUMES:
        fail("STATIC_NAMED_VOLUME_SET")
    if named_volume_sources(keycloak) != {"keycloak-data"}:
        fail("STATIC_KEYCLOAK_NAMED_VOLUME")
    bootstrap = services["keycloak-bootstrap"]
    verifier = services["keycloak-verify"]
    if named_volume_sources(bootstrap) or named_volume_sources(verifier):
        fail("STATIC_HELPER_NAMED_VOLUME")
    if mount_sources(bootstrap).intersection(mount_sources(verifier)):
        fail("STATIC_HELPER_SHARED_STORAGE")
    bootstrap_env = environment(bootstrap)
    verifier_env = environment(verifier)
    if bootstrap_env.get("KEYCLOAK_ADMIN_BASE_URL") != INTERNAL_BASE_URL:
        fail("STATIC_ADMIN_BASE_URL")
    if (
        verifier_env.get("KEYCLOAK_INTERNAL_BASE_URL") != INTERNAL_BASE_URL
        or verifier_env.get("KEYCLOAK_MANAGEMENT_BASE_URL") != MANAGEMENT_BASE_URL
    ):
        fail("STATIC_VERIFIER_BASE_URL")
    if dependency_condition(keycloak, "backend") != "service_healthy":
        fail("STATIC_DEPENDENCY")
    if dependency_condition(services["keycloak-bootstrap"], "keycloak") != "service_healthy":
        fail("STATIC_DEPENDENCY")
    if dependency_condition(services["keycloak-verify"], "keycloak-bootstrap") != "service_completed_successfully":
        fail("STATIC_DEPENDENCY")
    for service_name, expected in EXPECTED_SECRETS.items():
        validate_secret_mounts(services[service_name], expected)
    if realm is not None:
        validate_realm(realm)


def read_secret(path: Path) -> str:
    try:
        if path.is_symlink() or not path.is_file():
            fail("RUNTIME_SECRET_FILE")
        value = path.read_bytes()
    except OSError:
        fail("RUNTIME_SECRET_FILE")
    if not SECRET_PATTERN.fullmatch(value):
        fail("RUNTIME_SECRET_CONTENT")
    return value.decode("ascii")


def is_canonical_uuid4(value: Any) -> bool:
    if not isinstance(value, str) or UUID4_PATTERN.fullmatch(value) is None:
        return False
    try:
        parsed = uuid.UUID(value)
    except ValueError:
        return False
    return parsed.version == 4 and str(parsed) == value


def normalize_audience(raw: Any) -> list[str]:
    if isinstance(raw, str):
        if raw != AUDIENCE:
            fail("TOKEN_AUDIENCE_INVALID")
        return [raw]
    if isinstance(raw, list):
        if raw != [AUDIENCE]:
            fail("TOKEN_AUDIENCE_INVALID")
        return list(raw)
    fail("TOKEN_AUDIENCE_INVALID")


def decode_segment(segment: str) -> Any:
    if not segment or re.fullmatch(r"[A-Za-z0-9_-]+", segment) is None:
        fail("TOKEN_COMPACT_INVALID")
    try:
        raw = base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4))
        return json.loads(raw)
    except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
        fail("TOKEN_COMPACT_INVALID")


def decode_token(token: str) -> tuple[dict[str, Any], dict[str, Any]]:
    parts = token.split(".")
    if len(parts) != 3 or not all(parts):
        fail("TOKEN_COMPACT_INVALID")
    header = decode_segment(parts[0])
    payload = decode_segment(parts[1])
    if not isinstance(header, dict) or not isinstance(payload, dict):
        fail("TOKEN_COMPACT_INVALID")
    return header, payload


def validate_token(
    token: str,
    expected_role: str,
    signing_kids: set[str],
    *,
    current_time: int,
    require_raw_string_audience: bool = True,
) -> None:
    header, payload = decode_token(token)
    kid = header.get("kid")
    if header.get("alg") != "RS256" or not isinstance(kid, str) or not kid or kid not in signing_kids:
        fail("TOKEN_HEADER_INVALID")
    if payload.get("iss") != ISSUER:
        fail("TOKEN_ISSUER_INVALID")
    raw_audience = payload.get("aud")
    normalized = normalize_audience(raw_audience)
    if normalized != [AUDIENCE] or (require_raw_string_audience and not isinstance(raw_audience, str)):
        fail("TOKEN_AUDIENCE_REPRESENTATION")
    subject = payload.get("sub")
    if not is_canonical_uuid4(subject):
        fail("TOKEN_SUBJECT_INVALID")
    if payload.get("principal_type") != "SERVICE":
        fail("TOKEN_PRINCIPAL_INVALID")
    roles = payload.get("roles")
    if not isinstance(roles, list) or roles != [expected_role] or len(set(roles)) != len(roles):
        fail("TOKEN_ROLES_INVALID")
    if any(not isinstance(role, str) or role not in ALL_ROLES for role in roles):
        fail("TOKEN_ROLES_INVALID")
    iat = payload.get("iat")
    exp = payload.get("exp")
    if isinstance(iat, bool) or isinstance(exp, bool) or not isinstance(iat, int) or not isinstance(exp, int):
        fail("TOKEN_TIME_TYPE_INVALID")
    if exp <= iat or exp - iat > 900:
        fail("TOKEN_TIME_LIFETIME_INVALID")
    if iat > current_time:
        fail("TOKEN_TIME_IAT_FUTURE")
    if exp <= current_time:
        fail("TOKEN_TIME_EXPIRED")
    nbf = payload.get("nbf")
    if nbf is not None and (isinstance(nbf, bool) or not isinstance(nbf, int) or nbf > exp):
        fail("TOKEN_TIME_NBF_INVALID")
    if nbf is not None and nbf > current_time:
        fail("TOKEN_TIME_NBF_FUTURE")


def http_json(
    url: str,
    *,
    context: ssl.SSLContext | None = None,
    method: str = "GET",
    data: bytes | None = None,
    headers: dict[str, str] | None = None,
    expected: tuple[int, ...] = (200,),
) -> tuple[int, dict[str, Any]]:
    request = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    try:
        with urllib.request.urlopen(request, timeout=5, context=context) as response:
            status = response.status
            body = response.read()
    except urllib.error.HTTPError as error:
        status = error.code
        body = error.read()
    except (urllib.error.URLError, TimeoutError, OSError):
        fail("HTTP_TRANSPORT_FAILED")
    if status not in expected:
        fail("HTTP_STATUS_UNEXPECTED")
    try:
        parsed = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail("HTTP_JSON_INVALID")
    if not isinstance(parsed, dict):
        fail("HTTP_JSON_INVALID")
    return status, parsed


def bounded_poll(check: Callable[[], bool], attempts: int = 20, interval: float = 1.0) -> None:
    if attempts < 1 or attempts > 60 or interval < 0 or interval > 5:
        fail("POLL_BOUNDS_INVALID")
    for attempt in range(attempts):
        try:
            if check():
                return
        except VerificationError:
            pass
        if attempt + 1 < attempts:
            time.sleep(interval)
    fail("READINESS_TIMEOUT")


def token_for(client_id: str, secret: str) -> str:
    data = urllib.parse.urlencode(
        {"grant_type": "client_credentials", "client_id": client_id, "client_secret": secret}
    ).encode("ascii")
    _, response = http_json(
        "http://127.0.0.1:8082/realms/finguardops-local/protocol/openid-connect/token",
        method="POST",
        data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    token = response.get("access_token")
    if not isinstance(token, str) or not token:
        fail("TOKEN_RESPONSE_INVALID")
    return token


def assert_cross_secret_rejected(client_id: str, wrong_secret: str) -> None:
    data = urllib.parse.urlencode(
        {"grant_type": "client_credentials", "client_id": client_id, "client_secret": wrong_secret}
    ).encode("ascii")
    http_json(
        "http://127.0.0.1:8082/realms/finguardops-local/protocol/openid-connect/token",
        method="POST",
        data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        expected=(400, 401),
    )


def backend_boundary(token: str, endpoint: str, expected_status: int, expected_code: str) -> None:
    status, body = http_json(
        "http://127.0.0.1:8080" + endpoint,
        method="POST",
        data=b"{}",
        headers={
            "Authorization": "Bearer " + token,
            "Content-Type": "application/json",
            "Idempotency-Key": "keycloak-e2e-safe-invalid-request",
        },
        expected=(expected_status,),
    )
    if status != expected_status or body.get("code") != expected_code:
        fail("BACKEND_BOUNDARY_CLASSIFICATION")


def validate_cleanup_target(project_name: str, expected_project_name: str) -> None:
    if (
        project_name != expected_project_name
        or re.fullmatch(r"[a-z0-9][a-z0-9_-]{2,62}", project_name) is None
    ):
        fail("CLEANUP_TARGET_INVALID")


class RejectRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def host_runtime(certificate: Path) -> None:
    try:
        if certificate.is_symlink() or not certificate.is_file():
            fail("HOST_TLS_CERTIFICATE_INVALID")
        context = ssl.create_default_context(cafile=str(certificate))
        context.hostname_checks_common_name = False
    except (OSError, ssl.SSLError):
        fail("HOST_TLS_CERTIFICATE_INVALID")
    discovery_url = ISSUER + "/.well-known/openid-configuration"
    opener = urllib.request.build_opener(
        urllib.request.HTTPSHandler(context=context),
        RejectRedirect(),
    )
    request = urllib.request.Request(discovery_url, headers={"Accept": "application/json"})
    try:
        with opener.open(request, timeout=5) as response:
            status = response.status
            final_url = response.geturl()
            body = response.read()
    except urllib.error.HTTPError as error:
        error.close()
        fail("HOST_DISCOVERY_STATUS")
    except (urllib.error.URLError, TimeoutError, OSError):
        fail("HOST_DISCOVERY_TRANSPORT")
    if status != 200 or final_url != discovery_url:
        fail("HOST_DISCOVERY_STATUS")
    try:
        discovery = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail("HOST_DISCOVERY_JSON")
    if not isinstance(discovery, dict):
        fail("HOST_DISCOVERY_JSON")
    if discovery.get("issuer") != ISSUER or discovery.get("jwks_uri") != PUBLIC_JWK_SET_URI:
        fail("HOST_DISCOVERY_CONTRACT")
    for port in (8082, 9000):
        try:
            connection = socket.create_connection(("127.0.0.1", port), timeout=2)
        except OSError:
            continue
        connection.close()
        fail("HOST_INTERNAL_PORT_REACHABLE")
    print("host verification completed: public HTTPS and unpublished ports passed")


def runtime() -> None:
    if (
        os.environ.get("KEYCLOAK_INTERNAL_BASE_URL") != INTERNAL_BASE_URL
        or os.environ.get("KEYCLOAK_MANAGEMENT_BASE_URL") != MANAGEMENT_BASE_URL
    ):
        fail("RUNTIME_BASE_URL_INVALID")
    transaction_secret = read_secret(TRANSACTION_SECRET)
    behavior_secret = read_secret(BEHAVIOR_SECRET)
    if transaction_secret == behavior_secret:
        fail("SERVICE_SECRETS_NOT_DISTINCT")
    try:
        if CERTIFICATE.is_symlink() or not CERTIFICATE.is_file():
            fail("TLS_CERTIFICATE_INVALID")
        tls_context = ssl.create_default_context(cafile=str(CERTIFICATE))
        tls_context.hostname_checks_common_name = False
    except (OSError, ssl.SSLError):
        fail("TLS_CERTIFICATE_INVALID")

    def ready() -> bool:
        _, health = http_json("http://127.0.0.1:9000/health/ready")
        return health.get("status") == "UP"

    bounded_poll(ready)
    _, discovery = http_json(ISSUER + "/.well-known/openid-configuration", context=tls_context)
    if urllib.parse.urlparse(discovery.get("issuer", "")).hostname != "localhost":
        fail("DISCOVERY_HOSTNAME_INVALID")
    if discovery.get("issuer") != ISSUER:
        fail("DISCOVERY_ISSUER_INVALID")
    _, jwks = http_json(JWK_SET_URI)
    keys = jwks.get("keys")
    if not isinstance(keys, list) or not keys:
        fail("JWKS_INVALID")
    signing_keys = [key for key in keys if key.get("kty") == "RSA" and key.get("use") in (None, "sig")]
    signing_kids = {key.get("kid") for key in signing_keys if isinstance(key.get("kid"), str) and key.get("kid")}
    if not any(key.get("alg") == "RS256" for key in signing_keys) or len(signing_kids) != len(signing_keys):
        fail("JWKS_SIGNING_KEY_INVALID")

    transaction_token = token_for("finguardops-transaction-ingestor", transaction_secret)
    transaction_now = int(time.time())
    validate_token(
        transaction_token,
        "TRANSACTION_INGESTOR",
        signing_kids,
        current_time=transaction_now,
    )
    behavior_token = token_for("finguardops-behavior-ingestor", behavior_secret)
    behavior_now = int(time.time())
    validate_token(
        behavior_token,
        "BEHAVIOR_INGESTOR",
        signing_kids,
        current_time=behavior_now,
    )
    assert_cross_secret_rejected("finguardops-transaction-ingestor", behavior_secret)
    assert_cross_secret_rejected("finguardops-behavior-ingestor", transaction_secret)

    backend_boundary(transaction_token, "/api/v1/transactions", 400, "VALIDATION_ERROR")
    backend_boundary(behavior_token, "/api/v1/behavior-events", 400, "VALIDATION_ERROR")
    backend_boundary(transaction_token, "/api/v1/behavior-events", 403, "ACCESS_DENIED")
    backend_boundary(behavior_token, "/api/v1/transactions", 403, "ACCESS_DENIED")
    print("runtime verification completed: Keycloak and Backend boundaries passed")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(add_help=True)
    subparsers = parser.add_subparsers(dest="mode", required=True)
    static_parser = subparsers.add_parser("static")
    static_parser.add_argument("--config", required=True, type=Path)
    static_parser.add_argument("--realm", required=True, type=Path)
    subparsers.add_parser("runtime")
    host_parser = subparsers.add_parser("host")
    host_parser.add_argument("--certificate", required=True, type=Path)
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    try:
        args = parse_args(argv)
        if args.mode == "static":
            config = json.loads(args.config.read_text(encoding="utf-8"))
            realm = json.loads(args.realm.read_text(encoding="utf-8"))
            validate_static(config, realm)
            print("static verification completed: Compose and realm contracts passed")
        elif args.mode == "runtime":
            runtime()
        else:
            host_runtime(args.certificate)
        return 0
    except VerificationError as error:
        print("verification failed: " + str(error), file=sys.stderr)
        return 1
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, SystemExit):
        print("verification failed: INPUT_INVALID", file=sys.stderr)
        return 2
    except BaseException:
        print("verification failed: UNEXPECTED_ERROR", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

#!/usr/bin/env python3
"""Static and runtime verifier for the local Keycloak authentication boundary."""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import re
import socket
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
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
USER_DEFAULT_SCOPES = ["finguardops-backend-audience", "finguardops-user-claims"]
USER_OPTIONAL_SCOPES = ["profile"]
CUSTOM_CLIENT_SCOPES = {
    "finguardops-backend-audience",
    "finguardops-user-claims",
    "finguardops-transaction-service-claims",
    "finguardops-behavior-service-claims",
}
STOCK_PROFILE_MAPPER_CONTRACT = {
    "family name": ("oidc-usermodel-property-mapper", "lastName", "family_name"),
    "username": ("oidc-usermodel-property-mapper", "username", "preferred_username"),
    "updated at": ("oidc-usermodel-attribute-mapper", "updatedAt", "updated_at"),
    "given name": ("oidc-usermodel-property-mapper", "firstName", "given_name"),
    "middle name": ("oidc-usermodel-attribute-mapper", "middleName", "middle_name"),
    "gender": ("oidc-usermodel-attribute-mapper", "gender", "gender"),
    "zoneinfo": ("oidc-usermodel-attribute-mapper", "zoneinfo", "zoneinfo"),
    "nickname": ("oidc-usermodel-attribute-mapper", "nickname", "nickname"),
    "profile": ("oidc-usermodel-attribute-mapper", "profile", "profile"),
    "website": ("oidc-usermodel-attribute-mapper", "website", "website"),
    "birthdate": ("oidc-usermodel-attribute-mapper", "birthdate", "birthdate"),
    "picture": ("oidc-usermodel-attribute-mapper", "picture", "picture"),
    "locale": ("oidc-usermodel-attribute-mapper", "locale", "locale"),
}
SERVICE_CLIENT_SCOPES = {
    "finguardops-transaction-ingestor": [
        "finguardops-backend-audience",
        "finguardops-transaction-service-claims",
    ],
    "finguardops-behavior-ingestor": [
        "finguardops-backend-audience",
        "finguardops-behavior-service-claims",
    ],
}
REALM = "finguardops-local"
CERTIFICATE = Path("/run/secrets/keycloak_tls_certificate")
TRANSACTION_SECRET = Path("/run/secrets/transaction_service_client_secret")
BEHAVIOR_SECRET = Path("/run/secrets/behavior_service_client_secret")
SECRET_PATTERN = re.compile(rb"[A-Za-z0-9_-]{32,128}\Z")
PROJECT_PATTERN = re.compile(r"finguardops-kc241-e2e-[a-z0-9][a-z0-9-]{5,32}\Z")
PLAN_KEY_PATTERN = re.compile(r"kc241-[a-f0-9]{32}\Z")
PLAN_REF_PATTERN = re.compile(r"kc241-[a-z]+-[a-f0-9]{12}\Z")
PROJECT_RESOURCE_KINDS = ("container", "network", "volume")
RULE_VERSION_IDS = (
    "20000000-0000-4000-8000-000000000001",
    "20000000-0000-4000-8000-000000000002",
    "20000000-0000-4000-8000-000000000003",
    "20000000-0000-4000-8000-000000000004",
)
METRIC_NAMES = (
    "finguardops_external_risk_outcomes_total",
    "finguardops_rule_analysis_outcomes_total",
)
EXTERNAL_RISK_MARKER = "FINGUARDOPS_EXTERNAL_RISK_LOOKUP_RECEIVED"
RULE_V2_ACCESS_PATTERN = re.compile(
    r'^INFO:\s+[0-9a-fA-F:.]+:\d+ - "POST /api/v2/rule-analysis HTTP/1\.1" 200 OK$'
)
EXPECTED_AI_SERVICE_COMMAND = [
    "--host",
    "0.0.0.0",
    "--port",
    "8000",
    "--access-log",
]
BUSINESS_TABLES = (
    "audit_log",
    "behavior_event",
    "case_transaction",
    "detection_evidence",
    "detection_result",
    "financial_transaction",
    "fraud_case",
    "fraud_rule",
    "idempotency_record",
    "idempotency_recovery_audit_log",
    "investigation_note",
    "rule_version",
)
SNAPSHOT_QUERIES = {
    "audit_log": "SELECT to_jsonb(snapshot_row)::text FROM public.audit_log AS snapshot_row ORDER BY snapshot_row.id ASC;",
    "behavior_event": "SELECT to_jsonb(snapshot_row)::text FROM public.behavior_event AS snapshot_row ORDER BY snapshot_row.id ASC;",
    "case_transaction": "SELECT to_jsonb(snapshot_row)::text FROM public.case_transaction AS snapshot_row ORDER BY snapshot_row.id ASC;",
    "detection_evidence": "SELECT to_jsonb(snapshot_row)::text FROM public.detection_evidence AS snapshot_row ORDER BY snapshot_row.id ASC;",
    "detection_result": "SELECT to_jsonb(snapshot_row)::text FROM public.detection_result AS snapshot_row ORDER BY snapshot_row.id ASC;",
    "financial_transaction": "SELECT to_jsonb(snapshot_row)::text FROM public.financial_transaction AS snapshot_row ORDER BY snapshot_row.id ASC;",
    "fraud_case": "SELECT to_jsonb(snapshot_row)::text FROM public.fraud_case AS snapshot_row ORDER BY snapshot_row.id ASC;",
    "fraud_rule": "SELECT to_jsonb(snapshot_row)::text FROM public.fraud_rule AS snapshot_row ORDER BY snapshot_row.id ASC;",
    "idempotency_record": "SELECT to_jsonb(snapshot_row)::text FROM public.idempotency_record AS snapshot_row ORDER BY snapshot_row.id ASC;",
    "idempotency_recovery_audit_log": "SELECT to_jsonb(snapshot_row)::text FROM public.idempotency_recovery_audit_log AS snapshot_row ORDER BY snapshot_row.id ASC;",
    "investigation_note": "SELECT to_jsonb(snapshot_row)::text FROM public.investigation_note AS snapshot_row ORDER BY snapshot_row.id ASC;",
    "rule_version": "SELECT to_jsonb(snapshot_row)::text FROM public.rule_version AS snapshot_row ORDER BY snapshot_row.id ASC;",
}
SNAPSHOT_BEGIN_PREFIX = b"FINGUARDOPS_SNAPSHOT_BEGIN:"
SNAPSHOT_END_PREFIX = b"FINGUARDOPS_SNAPSHOT_END:"
INGESTION_STEPS = (
    "auth-denial",
    "behavior-create",
    "behavior-replay-conflict",
    "transaction-create",
    "transaction-replay-key-conflict",
    "duplicate-first",
    "duplicate-replay",
)


@dataclass(frozen=True)
class TableSnapshot:
    count: int
    row_hashes: tuple[bytes, ...] = field(repr=False)
    fingerprint: bytes = field(repr=False)
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
        "user_password",
    },
    "keycloak-verify": {
        "transaction_service_client_secret",
        "behavior_service_client_secret",
        "keycloak_tls_certificate",
    },
}
EXPECTED_SECRET_FILES = {
    "keycloak_bootstrap_admin_secret": "infra/keycloak/.local/secrets/bootstrap-admin-client-secret",
    "transaction_service_client_secret": "infra/keycloak/.local/secrets/transaction-service-client-secret",
    "behavior_service_client_secret": "infra/keycloak/.local/secrets/behavior-service-client-secret",
    "user_password": "infra/keycloak/.local/secrets/user-password",
    "keycloak_tls_certificate": "infra/keycloak/.local/tls/localhost.crt",
    "keycloak_tls_private_key": "infra/keycloak/.local/tls/localhost.key",
}
EXPECTED_SECRET_MODES = {
    name: 292 if name == "keycloak_tls_certificate" else 256
    for name in EXPECTED_SECRET_FILES
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
        mode = item.get("mode")
        expected_mode = EXPECTED_SECRET_MODES.get(source)
        accepted_modes = {expected_mode, "0" + format(expected_mode, "o")}
        if not isinstance(source, str) or target != source or mode not in accepted_modes:
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
    client_scopes = realm.get("clientScopes")
    expected_client_scopes = CUSTOM_CLIENT_SCOPES | {"profile"}
    if (
        not isinstance(client_scopes, list)
        or len(client_scopes) != len(expected_client_scopes)
        or {scope.get("name") for scope in client_scopes if isinstance(scope, dict)}
        != expected_client_scopes
    ):
        fail("STATIC_CLIENT_SCOPE_OBJECTS")
    profile_scope = next(scope for scope in client_scopes if scope.get("name") == "profile")
    if (
        profile_scope.get("description") != "OpenID Connect built-in scope: profile"
        or profile_scope.get("protocol") != "openid-connect"
        or profile_scope.get("attributes")
        != {
            "include.in.token.scope": "true",
            "display.on.consent.screen": "true",
            "consent.screen.text": "${profileScopeConsentText}",
        }
    ):
        fail("STATIC_STOCK_PROFILE_SCOPE")
    profile_mappers = profile_scope.get("protocolMappers")
    if not isinstance(profile_mappers, list) or len(profile_mappers) != 14:
        fail("STATIC_STOCK_PROFILE_SCOPE")
    mappers_by_name = {
        mapper.get("name"): mapper for mapper in profile_mappers if isinstance(mapper, dict)
    }
    if set(mappers_by_name) != set(STOCK_PROFILE_MAPPER_CONTRACT) | {"full name"}:
        fail("STATIC_STOCK_PROFILE_SCOPE")
    full_name = mappers_by_name["full name"]
    if (
        full_name.get("protocol") != "openid-connect"
        or full_name.get("protocolMapper") != "oidc-full-name-mapper"
        or full_name.get("consentRequired") is not False
        or full_name.get("config")
        != {
            "id.token.claim": "true",
            "access.token.claim": "true",
            "userinfo.token.claim": "true",
        }
    ):
        fail("STATIC_STOCK_PROFILE_SCOPE")
    for name, (mapper_type, user_attribute, claim_name) in STOCK_PROFILE_MAPPER_CONTRACT.items():
        mapper = mappers_by_name[name]
        if (
            mapper.get("protocol") != "openid-connect"
            or mapper.get("protocolMapper") != mapper_type
            or mapper.get("consentRequired") is not False
            or mapper.get("config")
            != {
                "userinfo.token.claim": "true",
                "user.attribute": user_attribute,
                "id.token.claim": "true",
                "access.token.claim": "true",
                "claim.name": claim_name,
                "jsonType.label": "String",
            }
        ):
            fail("STATIC_STOCK_PROFILE_SCOPE")
    user_claims_scope = next(
        scope for scope in client_scopes if scope.get("name") == "finguardops-user-claims"
    )
    user_claim_mappers = user_claims_scope.get("protocolMappers")
    if not isinstance(user_claim_mappers, list) or len(user_claim_mappers) != 3:
        fail("STATIC_USER_SUBJECT_MAPPER")
    user_claim_mappers_by_name = {
        mapper.get("name"): mapper for mapper in user_claim_mappers if isinstance(mapper, dict)
    }
    if set(user_claim_mappers_by_name) != {
        "finguardops-user-subject",
        "finguardops-user-principal-type",
        "finguardops-user-roles",
    }:
        fail("STATIC_USER_SUBJECT_MAPPER")
    if user_claim_mappers_by_name["finguardops-user-subject"] != {
        "name": "finguardops-user-subject",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-sub-mapper",
        "consentRequired": False,
        "config": {
            "access.token.claim": "true",
            "introspection.token.claim": "true",
        },
    }:
        fail("STATIC_USER_SUBJECT_MAPPER")
    clients = {client.get("clientId"): client for client in realm.get("clients", [])}
    if set(clients) != {
        "finguardops-frontend",
        "finguardops-transaction-ingestor",
        "finguardops-behavior-ingestor",
    }:
        fail("STATIC_REALM_CLIENTS")
    frontend = clients["finguardops-frontend"]
    frontend_attributes = frontend.get("attributes")
    expected_frontend_attributes = {
        "pkce.code.challenge.method": "S256",
        "post.logout.redirect.uris": "http://localhost:5173/",
        "oauth2.device.authorization.grant.enabled": "false",
        "oidc.ciba.grant.enabled": "false",
        "use.refresh.tokens": "false",
    }
    if (
        frontend.get("publicClient") is not True
        or frontend.get("standardFlowEnabled") is not True
        or frontend.get("implicitFlowEnabled") is not False
        or frontend.get("directAccessGrantsEnabled") is not False
        or frontend.get("serviceAccountsEnabled") is not False
        or frontend.get("fullScopeAllowed") is not False
        or frontend.get("redirectUris") != ["http://localhost:5173/auth/callback"]
        or frontend.get("webOrigins") != ["http://localhost:5173"]
        or frontend.get("defaultClientScopes") != USER_DEFAULT_SCOPES
        or frontend.get("optionalClientScopes") != USER_OPTIONAL_SCOPES
        or frontend_attributes != expected_frontend_attributes
        or "secret" in frontend
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
            or client.get("defaultClientScopes") != SERVICE_CLIENT_SCOPES[client_id]
            or client.get("optionalClientScopes") != []
            or client.get("attributes", {}).get("oauth2.device.authorization.grant.enabled") != "false"
            or client.get("attributes", {}).get("oidc.ciba.grant.enabled") != "false"
            or "use.refresh.tokens" in client.get("attributes", {})
        ):
            fail("STATIC_SERVICE_CLIENT_CONTRACT")
    for client in clients.values():
        scopes = client.get("defaultClientScopes", []) + client.get("optionalClientScopes", [])
        if "offline_access" in scopes or "offline" in scopes or "roles" in scopes:
            fail("STATIC_FORBIDDEN_SCOPE")
    users = realm.get("users")
    if not isinstance(users, list) or len(users) != 1:
        fail("STATIC_USER_CONTRACT")
    user = users[0]
    if (
        not isinstance(user, dict)
        or user.get("id") != "32a6a5db-71e4-4e58-8b3f-ec8c2c07b69a"
        or user.get("username") != "local-fds-analyst"
        or user.get("firstName") != "Local"
        or user.get("lastName") != "Analyst"
        or user.get("email") != "local-fds-analyst@finguardops.invalid"
        or user.get("enabled") is not True
        or user.get("emailVerified") is not False
        or user.get("requiredActions") != []
        or user.get("credentials") != []
        or user.get("realmRoles") != ["FDS_ANALYST"]
    ):
        fail("STATIC_USER_CONTRACT")


def validate_secret_definitions(config: dict[str, Any]) -> None:
    definitions = config.get("secrets")
    if not isinstance(definitions, dict) or set(definitions) != set(EXPECTED_SECRET_FILES):
        fail("STATIC_SECRET_DEFINITION")
    for name, suffix in EXPECTED_SECRET_FILES.items():
        definition = definitions[name]
        if not isinstance(definition, dict) or not source_matches(definition.get("file"), suffix, "bind"):
            fail("STATIC_SECRET_DEFINITION")


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
    if services["ai-service"].get("command") != EXPECTED_AI_SERVICE_COMMAND:
        fail("STATIC_RULE_ACCESS_LOG")
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
    validate_secret_definitions(config)
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
    if dependency_condition(services["keycloak-verify"], "external-risk-mock") != "service_healthy":
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
    if "refresh_token" in response:
        fail("SERVICE_REFRESH_TOKEN_PRESENT")
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


def validate_actual_service_tokens(
    transaction_token: str, behavior_token: str
) -> None:
    _, jwks = http_json(JWK_SET_URI)
    keys = jwks.get("keys")
    if not isinstance(keys, list) or not keys:
        fail("JWKS_INVALID")
    signing_keys = [
        key
        for key in keys
        if isinstance(key, dict)
        and key.get("kty") == "RSA"
        and key.get("use") in (None, "sig")
    ]
    signing_kids = {
        key.get("kid")
        for key in signing_keys
        if isinstance(key.get("kid"), str) and key.get("kid")
    }
    if (
        not any(key.get("alg") == "RS256" for key in signing_keys)
        or len(signing_kids) != len(signing_keys)
    ):
        fail("JWKS_SIGNING_KEY_INVALID")
    validate_token(
        transaction_token,
        "TRANSACTION_INGESTOR",
        signing_kids,
        current_time=int(time.time()),
    )
    validate_token(
        behavior_token,
        "BEHAVIOR_INGESTOR",
        signing_kids,
        current_time=int(time.time()),
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


def validate_plan(raw: Any) -> dict[str, str]:
    expected = {
        "transactionId",
        "passwordEventId",
        "transferLimitEventId",
        "idempotencyKey",
        "duplicateIdempotencyKey",
        "customerRef",
        "senderRef",
        "recipientRef",
        "passwordOccurredAt",
        "transferLimitOccurredAt",
        "transactionOccurredAt",
    }
    if not isinstance(raw, dict) or set(raw) != expected:
        fail("INGESTION_PLAN_INVALID")
    plan = {key: value for key, value in raw.items() if isinstance(value, str)}
    if len(plan) != len(expected):
        fail("INGESTION_PLAN_INVALID")
    if not all(
        is_canonical_uuid4(plan[key])
        for key in ("transactionId", "passwordEventId", "transferLimitEventId")
    ):
        fail("INGESTION_PLAN_INVALID")
    if not all(
        PLAN_KEY_PATTERN.fullmatch(plan[key]) is not None
        for key in ("idempotencyKey", "duplicateIdempotencyKey")
    ) or plan["idempotencyKey"] == plan["duplicateIdempotencyKey"]:
        fail("INGESTION_PLAN_INVALID")
    if not all(
        PLAN_REF_PATTERN.fullmatch(plan[key]) is not None
        for key in ("customerRef", "senderRef", "recipientRef")
    ):
        fail("INGESTION_PLAN_INVALID")
    instants = []
    try:
        for key in (
            "passwordOccurredAt",
            "transferLimitOccurredAt",
            "transactionOccurredAt",
        ):
            instants.append(dt.datetime.fromisoformat(plan[key].replace("Z", "+00:00")))
    except ValueError:
        fail("INGESTION_PLAN_INVALID")
    if any(value.tzinfo is None for value in instants) or not (
        instants[0] < instants[1] < instants[2]
    ):
        fail("INGESTION_PLAN_INVALID")
    return plan


def ingestion_payloads(
    plan: dict[str, str],
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    transaction = {
        "transactionId": plan["transactionId"],
        "transactionType": "ACCOUNT_TRANSFER",
        "amount": "12000000",
        "currencyCode": "KRW",
        "occurredAt": plan["transactionOccurredAt"],
        "externalCustomerRef": plan["customerRef"],
        "senderAccountRef": plan["senderRef"],
        "recipientAccountRef": plan["recipientRef"],
        "channel": "MOBILE_BANKING",
    }
    password_event = {
        "eventId": plan["passwordEventId"],
        "eventType": "PASSWORD_CHANGED",
        "occurredAt": plan["passwordOccurredAt"],
        "externalCustomerRef": plan["customerRef"],
    }
    transfer_limit_event = {
        "eventId": plan["transferLimitEventId"],
        "eventType": "TRANSFER_LIMIT_CHANGED",
        "occurredAt": plan["transferLimitOccurredAt"],
        "externalCustomerRef": plan["customerRef"],
        "accountRef": plan["senderRef"],
    }
    return transaction, password_event, transfer_limit_event


def request_backend(
    endpoint: str,
    payload: dict[str, Any],
    expected_status: int,
    *,
    token: str | None = None,
    idempotency_key: str | None = None,
    expected_code: str | None = None,
    failure_code: str = "INGESTION_HTTP_STATUS_INVALID",
) -> dict[str, Any]:
    headers = {"Content-Type": "application/json"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    if idempotency_key is not None:
        headers["Idempotency-Key"] = idempotency_key
    if re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", failure_code) is None:
        fail("INGESTION_FAILURE_CODE_INVALID")
    try:
        status, body = http_json(
            "http://127.0.0.1:8080" + endpoint,
            method="POST",
            data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
            headers=headers,
            expected=(200, 201, 400, 401, 403, 409, 422, 500, 503),
        )
    except VerificationError as error:
        if str(error) == "HTTP_STATUS_UNEXPECTED":
            fail(failure_code)
        raise
    if status != expected_status:
        fail(failure_code + "_" + str(status))
    if expected_code is not None and body.get("code") != expected_code:
        fail("INGESTION_ERROR_CLASSIFICATION")
    return body


def http_text(url: str) -> str:
    try:
        with urllib.request.urlopen(url, timeout=5) as response:
            if response.status != 200:
                fail("METRIC_STATUS_INVALID")
            body = response.read(1_048_577)
    except (urllib.error.URLError, TimeoutError, OSError):
        fail("METRIC_TRANSPORT_FAILED")
    if len(body) > 1_048_576:
        fail("METRIC_BODY_TOO_LARGE")
    try:
        return body.decode("utf-8")
    except UnicodeDecodeError:
        fail("METRIC_BODY_INVALID")


def metric_totals() -> tuple[float, float]:
    scrape = http_text("http://127.0.0.1:8081/actuator/prometheus")
    totals: list[float] = []
    for metric in METRIC_NAMES:
        total = 0.0
        matched = False
        pattern = re.compile(
            r"^" + re.escape(metric) + r"(?:\{[^\r\n]*\})?\s+"
            r"([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)$"
        )
        for line in scrape.splitlines():
            match = pattern.fullmatch(line)
            if match is not None:
                matched = True
                total += float(match.group(1))
        totals.append(total if matched else 0.0)
    return totals[0], totals[1]


def assert_metrics(actual: tuple[float, float], expected: tuple[float, float]) -> None:
    if actual != expected:
        fail("PROCESSING_COUNT_CHANGED")


def ingestion_runtime(step: str) -> None:
    if step not in INGESTION_STEPS:
        fail("INGESTION_STEP_INVALID")
    try:
        raw = sys.stdin.buffer.read(8193)
    except OSError:
        fail("INGESTION_PLAN_INVALID")
    if len(raw) > 8192:
        fail("INGESTION_PLAN_INVALID")
    try:
        plan = validate_plan(json.loads(raw))
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail("INGESTION_PLAN_INVALID")

    transaction_secret = read_secret(TRANSACTION_SECRET)
    behavior_secret = read_secret(BEHAVIOR_SECRET)
    if transaction_secret == behavior_secret:
        fail("SERVICE_SECRETS_NOT_DISTINCT")
    transaction_token = token_for(
        "finguardops-transaction-ingestor", transaction_secret
    )
    behavior_token = token_for("finguardops-behavior-ingestor", behavior_secret)
    validate_actual_service_tokens(transaction_token, behavior_token)
    assert_cross_secret_rejected(
        "finguardops-transaction-ingestor", behavior_secret
    )
    assert_cross_secret_rejected(
        "finguardops-behavior-ingestor", transaction_secret
    )

    transaction, password_event, transfer_limit_event = ingestion_payloads(plan)
    if step == "auth-denial":
        denial_cases = (
            ("/api/v1/transactions", transaction, behavior_token, plan["idempotencyKey"], 403, "ACCESS_DENIED", "TX_OPPOSITE_SERVICE_STATUS"),
            ("/api/v1/behavior-events", password_event, transaction_token, None, 403, "ACCESS_DENIED", "BEHAVIOR_OPPOSITE_SERVICE_STATUS"),
            ("/api/v1/transactions", transaction, None, plan["idempotencyKey"], 401, "UNAUTHORIZED", "TX_MISSING_CREDENTIAL_STATUS"),
            ("/api/v1/behavior-events", password_event, None, None, 401, "UNAUTHORIZED", "BEHAVIOR_MISSING_CREDENTIAL_STATUS"),
            ("/api/v1/transactions", transaction, "damaged-token", plan["idempotencyKey"], 401, "UNAUTHORIZED", "TX_DAMAGED_CREDENTIAL_STATUS"),
            ("/api/v1/behavior-events", password_event, "damaged-token", None, 401, "UNAUTHORIZED", "BEHAVIOR_DAMAGED_CREDENTIAL_STATUS"),
        )
        for endpoint, payload, credential, key, status, code, failure_code in denial_cases:
            request_backend(
                endpoint, payload, status, token=credential, idempotency_key=key,
                expected_code=code, failure_code=failure_code,
            )
    elif step == "behavior-create":
        for payload, identifier in (
            (password_event, plan["passwordEventId"]),
            (transfer_limit_event, plan["transferLimitEventId"]),
        ):
            response = request_backend(
                "/api/v1/behavior-events", payload, 201, token=behavior_token,
                failure_code="BEHAVIOR_CREATE_STATUS",
            )
            if response.get("eventId") != identifier:
                fail("BEHAVIOR_RESPONSE_INVALID")
    elif step == "behavior-replay-conflict":
        replay = request_backend(
            "/api/v1/behavior-events", password_event, 200, token=behavior_token,
            failure_code="BEHAVIOR_REPLAY_STATUS",
        )
        if replay.get("eventId") != plan["passwordEventId"]:
            fail("BEHAVIOR_RESPONSE_INVALID")
        conflict = dict(password_event)
        conflict["externalCustomerRef"] = plan["senderRef"]
        request_backend(
            "/api/v1/behavior-events", conflict, 409, token=behavior_token,
            expected_code="DUPLICATE_EVENT", failure_code="BEHAVIOR_CONFLICT_STATUS",
        )
    elif step == "transaction-create":
        response = request_backend(
            "/api/v1/transactions", transaction, 201, token=transaction_token,
            idempotency_key=plan["idempotencyKey"], failure_code="TX_CREATE_STATUS",
        )
        if (
            set(response) != {
                "transactionId", "processingStatus", "riskLevel",
                "riskResponseOutcome", "adoptedDetectionResultId", "caseId",
                "createdAt", "traceId",
            }
            or response.get("transactionId") != plan["transactionId"]
            or response.get("processingStatus") != "ADDITIONAL_AUTH_REQUIRED"
            or response.get("riskLevel") != "HIGH"
            or response.get("riskResponseOutcome") != "ADDITIONAL_AUTH_REQUIRED"
            or not is_canonical_uuid4(response.get("adoptedDetectionResultId"))
            or not is_canonical_uuid4(response.get("caseId"))
        ):
            fail("TRANSACTION_RESPONSE_INVALID")
    elif step == "transaction-replay-key-conflict":
        replay = request_backend(
            "/api/v1/transactions", transaction, 201, token=transaction_token,
            idempotency_key=plan["idempotencyKey"], failure_code="TX_REPLAY_STATUS",
        )
        if (
            replay.get("transactionId") != plan["transactionId"]
            or replay.get("processingStatus") != "ADDITIONAL_AUTH_REQUIRED"
            or replay.get("riskLevel") != "HIGH"
            or replay.get("riskResponseOutcome") != "ADDITIONAL_AUTH_REQUIRED"
        ):
            fail("TRANSACTION_RESPONSE_INVALID")
        conflict = dict(transaction)
        conflict["amount"] = "12000001"
        request_backend(
            "/api/v1/transactions", conflict, 409, token=transaction_token,
            idempotency_key=plan["idempotencyKey"],
            expected_code="IDEMPOTENCY_KEY_CONFLICT",
            failure_code="TX_KEY_CONFLICT_STATUS",
        )
    else:
        request_backend(
            "/api/v1/transactions", transaction, 409, token=transaction_token,
            idempotency_key=plan["duplicateIdempotencyKey"],
            expected_code="DUPLICATE_TRANSACTION",
            failure_code=(
                "TX_DUPLICATE_FIRST_STATUS" if step == "duplicate-first"
                else "TX_DUPLICATE_REPLAY_STATUS"
            ),
        )
    print("ingestion step completed: " + step)


def validate_cleanup_target(project_name: str, expected_project_name: str) -> None:
    if (
        project_name != expected_project_name
        or re.fullmatch(r"[a-z0-9][a-z0-9_-]{2,62}", project_name) is None
    ):
        fail("CLEANUP_TARGET_INVALID")


def run_command(
    argv: list[str],
    *,
    timeout: float,
    cwd: Path,
    environment: dict[str, str],
    input_bytes: bytes | None = None,
) -> bytes:
    if timeout <= 0:
        fail("SUBPROCESS_TIMEOUT_INVALID")
    merged = os.environ.copy()
    merged.update(environment)
    merged.update({"MSYS_NO_PATHCONV": "1", "MSYS2_ARG_CONV_EXCL": "*"})
    try:
        result = subprocess.run(
            argv,
            input=input_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=cwd,
            env=merged,
            shell=False,
            check=False,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired):
        fail("SUBPROCESS_FAILED")
    if result.returncode != 0:
        safe_child = re.search(
            rb"(?:^|\n)verification failed: ([A-Z][A-Z0-9_]{0,63})(?:\r?\n|$)",
            result.stderr,
        )
        if safe_child is not None:
            fail("CHILD_" + safe_child.group(1).decode("ascii"))
        fail("SUBPROCESS_FAILED")
    return result.stdout


class HostContext:
    def __init__(
        self,
        repo: Path,
        project: str,
        cli_timeout: float,
        deadline_seconds: float,
    ) -> None:
        self.repo = repo.resolve()
        self.project = project
        self.cli_timeout = cli_timeout
        self.deadline = time.monotonic() + deadline_seconds
        self.compose = [
            "docker",
            "compose",
            "-p",
            project,
            "-f",
            str(self.repo / "infra" / "compose.yml"),
            "-f",
            str(self.repo / "infra" / "compose.keycloak-local-e2e.yml"),
        ]
        self.environment = {
            "POSTGRES_PASSWORD": "local-kc241-placeholder-not-production",
            "GRAFANA_ADMIN_USER": "local-kc241-admin",
            "GRAFANA_ADMIN_PASSWORD": "local-kc241-placeholder-not-production",
        }

    def remaining(self) -> float:
        value = self.deadline - time.monotonic()
        if value <= 0:
            fail("OVERALL_DEADLINE_EXCEEDED")
        return value

    def execute(
        self,
        arguments: list[str],
        *,
        input_bytes: bytes | None = None,
        timeout: float | None = None,
    ) -> bytes:
        limit = self.cli_timeout if timeout is None else timeout
        return run_command(
            self.compose + arguments,
            timeout=min(limit, self.remaining()),
            cwd=self.repo,
            environment=self.environment,
            input_bytes=input_bytes,
        )


def project_resources(
    project: str,
    *,
    timeout: float,
    repo: Path,
    environment: dict[str, str],
) -> dict[str, tuple[str, ...]]:
    commands = {
        "container": [
            "docker", "ps", "-aq", "--filter",
            "label=com.docker.compose.project=" + project,
        ],
        "network": [
            "docker", "network", "ls", "-q", "--filter",
            "label=com.docker.compose.project=" + project,
        ],
        "volume": [
            "docker", "volume", "ls", "-q", "--filter",
            "label=com.docker.compose.project=" + project,
        ],
    }
    result: dict[str, tuple[str, ...]] = {}
    for kind in PROJECT_RESOURCE_KINDS:
        output = run_command(
            commands[kind], timeout=timeout, cwd=repo, environment=environment
        )
        result[kind] = tuple(
            line for line in output.decode("ascii", "strict").splitlines() if line
        )
    return result


def assert_resources_empty(resources: dict[str, tuple[str, ...]]) -> None:
    if set(resources) != set(PROJECT_RESOURCE_KINDS):
        fail("RESOURCE_INVENTORY_INVALID")
    if any(resources[kind] for kind in PROJECT_RESOURCE_KINDS):
        fail("PROJECT_RESOURCE_REMAINS")


def wait_container(ctx: HostContext, service: str, expected: str) -> None:
    for _ in range(120):
        container_id = ctx.execute(["ps", "-aq", service]).decode("ascii").strip()
        if container_id:
            template = (
                "{{if .State.Health}}{{.State.Health.Status}}"
                "{{else}}{{.State.Status}}{{end}}|{{.State.ExitCode}}"
            )
            state = run_command(
                ["docker", "inspect", container_id, "--format", template],
                timeout=min(ctx.cli_timeout, ctx.remaining()),
                cwd=ctx.repo,
                environment=ctx.environment,
            ).decode("ascii").strip()
            status, _, exit_code = state.partition("|")
            if expected == "healthy" and status == "healthy":
                return
            if expected == "completed" and status == "exited" and exit_code == "0":
                return
            if status in {"dead", "exited"} and expected != "completed":
                fail("CONTAINER_TERMINATED")
            if status == "exited" and exit_code != "0":
                fail("CONTAINER_TERMINATED")
        time.sleep(1)
    fail("CONTAINER_READINESS_TIMEOUT")


def sql_scalar(ctx: HostContext, query: str) -> str:
    output = ctx.execute(
        [
            "exec", "-T", "postgresql", "psql", "-X", "-v", "ON_ERROR_STOP=1",
            "-U", "finguardops", "-d", "finguardops", "-tAc", query,
        ]
    )
    return output.decode("utf-8", "strict").strip()


def publish_rules(ctx: HostContext) -> None:
    identifiers = ",".join("'%s'" % item for item in RULE_VERSION_IDS)
    published_query = (
        "select count(*) from rule_version where status='PUBLISHED' "
        "and rule_version_id in (" + identifiers + ")"
    )
    active_query = published_query + " and effective_from <= current_timestamp"
    published = sql_scalar(ctx, published_query)
    active = sql_scalar(ctx, active_query)
    if published == "4" and active == "4":
        return
    if published != "0":
        fail("RULE_PUBLICATION_STATE_INVALID")
    effective = (
        dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=60)
    ).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    ctx.execute(
        [
            "run", "--rm", "--no-deps", "-T",
            "-e", "SPRING_PROFILES_ACTIVE=local,rule-v1-default-publication",
            "-e", "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false",
            "backend", "--spring.main.web-application-type=none",
            "--finguardops.rule-v1-default-publication.enabled=true",
            "--finguardops.rule-v1-default-publication.confirmation=PUBLISH_RULE_V1_DEFAULT_V1",
            "--finguardops.rule-v1-default-publication.effective-from=" + effective,
        ],
        timeout=240,
    )
    for _ in range(90):
        if sql_scalar(ctx, active_query) == "4":
            return
        time.sleep(1)
    fail("RULE_ACTIVATION_TIMEOUT")


def create_plan() -> dict[str, str]:
    suffix = uuid.uuid4().hex[:12]
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    return {
        "transactionId": str(uuid.uuid4()),
        "passwordEventId": str(uuid.uuid4()),
        "transferLimitEventId": str(uuid.uuid4()),
        "idempotencyKey": "kc241-" + uuid.uuid4().hex,
        "duplicateIdempotencyKey": "kc241-" + uuid.uuid4().hex,
        "customerRef": "kc241-customer-" + suffix,
        "senderRef": "kc241-sender-" + suffix,
        "recipientRef": "kc241-recipient-" + suffix,
        "passwordOccurredAt": (now - dt.timedelta(seconds=120)).isoformat().replace("+00:00", "Z"),
        "transferLimitOccurredAt": (now - dt.timedelta(seconds=60)).isoformat().replace("+00:00", "Z"),
        "transactionOccurredAt": now.isoformat().replace("+00:00", "Z"),
    }


def snapshot_sql() -> bytes:
    if tuple(SNAPSHOT_QUERIES) != BUSINESS_TABLES:
        fail("DATABASE_GLOBAL_SNAPSHOT_INVALID")
    statements = [
        "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;",
        "SET LOCAL TIME ZONE 'UTC';",
        "SET LOCAL DateStyle = 'ISO, YMD';",
        "SET LOCAL extra_float_digits = 3;",
        "SET LOCAL bytea_output = 'hex';",
    ]
    for table, query in SNAPSHOT_QUERIES.items():
        statements.extend(
            (
                "SELECT 'FINGUARDOPS_SNAPSHOT_BEGIN:" + table + "';",
                query,
                "SELECT 'FINGUARDOPS_SNAPSHOT_END:" + table + "';",
            )
        )
    statements.append("COMMIT;")
    return ("\n".join(statements) + "\n").encode("ascii")


def aggregate_fingerprint(table: str, row_hashes: tuple[bytes, ...]) -> bytes:
    if table not in BUSINESS_TABLES or any(len(value) != 32 for value in row_hashes):
        fail("DATABASE_GLOBAL_SNAPSHOT_INVALID")
    name = table.encode("ascii")
    digest = hashlib.sha256()
    digest.update(b"FINGUARDOPS_TABLE_SNAPSHOT_V1\x00")
    digest.update(len(name).to_bytes(2, "big"))
    digest.update(name)
    digest.update(len(row_hashes).to_bytes(8, "big"))
    for row_hash in row_hashes:
        digest.update(len(row_hash).to_bytes(2, "big"))
        digest.update(row_hash)
    return digest.digest()


def table_snapshot(table: str, canonical_rows: tuple[bytes, ...]) -> TableSnapshot:
    if table not in BUSINESS_TABLES:
        fail("DATABASE_GLOBAL_SNAPSHOT_INVALID")
    row_hashes = tuple(hashlib.sha256(row).digest() for row in canonical_rows)
    return TableSnapshot(
        count=len(row_hashes),
        row_hashes=row_hashes,
        fingerprint=aggregate_fingerprint(table, row_hashes),
    )


def parse_database_snapshot(output: bytes) -> dict[str, TableSnapshot]:
    try:
        lines = output.splitlines()
        cursor = 0
        snapshots: dict[str, TableSnapshot] = {}
        for table in BUSINESS_TABLES:
            name = table.encode("ascii")
            if cursor >= len(lines) or lines[cursor] != SNAPSHOT_BEGIN_PREFIX + name:
                fail("DATABASE_GLOBAL_SNAPSHOT_INVALID")
            cursor += 1
            rows: list[bytes] = []
            end = SNAPSHOT_END_PREFIX + name
            while cursor < len(lines) and lines[cursor] != end:
                row = lines[cursor]
                parsed = json.loads(row)
                if not isinstance(parsed, dict) or not row.startswith(b"{") or not row.endswith(b"}"):
                    fail("DATABASE_GLOBAL_SNAPSHOT_INVALID")
                rows.append(row)
                cursor += 1
            if cursor >= len(lines):
                fail("DATABASE_GLOBAL_SNAPSHOT_INVALID")
            snapshots[table] = table_snapshot(table, tuple(rows))
            cursor += 1
        if cursor != len(lines):
            fail("DATABASE_GLOBAL_SNAPSHOT_INVALID")
        return snapshots
    except (UnicodeDecodeError, json.JSONDecodeError, OverflowError):
        fail("DATABASE_GLOBAL_SNAPSHOT_INVALID")


def database_snapshot(ctx: HostContext) -> dict[str, TableSnapshot]:
    output = ctx.execute(
        [
            "exec", "-T", "postgresql", "psql", "-X", "-qAt",
            "-v", "ON_ERROR_STOP=1", "-U", "finguardops", "-d", "finguardops",
            "-f", "-",
        ],
        input_bytes=snapshot_sql(),
    )
    return parse_database_snapshot(output)


def transaction_cardinality(
    ctx: HostContext, plan: dict[str, str]
) -> tuple[int, ...]:
    valid = validate_plan(plan)
    transaction_id = valid["transactionId"]
    password_event_id = valid["passwordEventId"]
    transfer_limit_event_id = valid["transferLimitEventId"]
    original_key = valid["idempotencyKey"]
    duplicate_key = valid["duplicateIdempotencyKey"]
    query = "select concat_ws('|'," + ",".join(
        (
            "(select count(*) from behavior_event where event_id='%s' and event_type='PASSWORD_CHANGED')" % password_event_id,
            "(select count(*) from behavior_event where event_id='%s' and event_type='TRANSFER_LIMIT_CHANGED' and account_ref='%s')" % (transfer_limit_event_id, valid["senderRef"]),
            "(select count(*) from financial_transaction where transaction_id='%s' and processing_status='ADDITIONAL_AUTH_REQUIRED' and risk_level='HIGH' and risk_response_outcome='ADDITIONAL_AUTH_REQUIRED')" % transaction_id,
            "(select count(*) from idempotency_record i join financial_transaction f on f.id=i.financial_transaction_id where f.transaction_id='%s' and i.idempotency_key='%s' and i.processing_status='COMPLETED' and i.response_snapshot->>'httpStatus'='201')" % (transaction_id, original_key),
            "(select count(*) from idempotency_record where idempotency_key='%s' and processing_status='FAILED' and failure_code='DUPLICATE_TRANSACTION' and financial_transaction_id is null)" % duplicate_key,
            "(select count(*) from detection_result d join financial_transaction f on f.id=d.financial_transaction_id where f.transaction_id='%s' and d.analysis_status='COMPLETED' and d.risk_score=55 and d.risk_level='HIGH' and f.adopted_detection_result_id=d.id)" % transaction_id,
            "(select count(*) from detection_evidence e join detection_result d on d.id=e.detection_result_id join financial_transaction f on f.id=d.financial_transaction_id where f.transaction_id='%s')" % transaction_id,
            "(select count(distinct c.id) from fraud_case c join case_transaction ct on ct.fraud_case_id=c.id join financial_transaction f on f.id=ct.financial_transaction_id where f.transaction_id='%s' and c.case_status='OPEN')" % transaction_id,
            "(select count(*) from case_transaction ct join financial_transaction f on f.id=ct.financial_transaction_id where f.transaction_id='%s')" % transaction_id,
            "(select count(*) from audit_log where transaction_id='%s')" % transaction_id,
            "(select count(*) from audit_log where transaction_id='%s' and action='CASE_CREATED')" % transaction_id,
            "(select count(*) from audit_log where transaction_id='%s' and action='CASE_TRANSACTION_LINKED')" % transaction_id,
            "(select count(*) from audit_log where transaction_id='%s' and action='TRANSACTION_RISK_RESPONSE_APPLIED')" % transaction_id,
            "(select count(*) from audit_log where transaction_id='%s' and action='TRANSACTION_STATUS_CHANGED')" % transaction_id,
        )
    ) + ")"
    raw = sql_scalar(ctx, query).split("|")
    if len(raw) != 14 or any(re.fullmatch(r"\d+", value) is None for value in raw):
        fail("DATABASE_TRANSACTION_SNAPSHOT_INVALID")
    return tuple(int(value) for value in raw)


def expected_transaction_cardinality(
    behavior_created: bool, transaction_created: bool, duplicate_created: bool
) -> tuple[int, ...]:
    behavior = 1 if behavior_created else 0
    transaction = 1 if transaction_created else 0
    duplicate = 1 if duplicate_created else 0
    return (
        behavior,
        behavior,
        transaction,
        transaction,
        duplicate,
        transaction,
        2 * transaction,
        transaction,
        transaction,
        4 * transaction,
        transaction,
        transaction,
        transaction,
        transaction,
    )


def service_logs(ctx: HostContext, service: str) -> str:
    if service not in {"external-risk-mock", "ai-service"}:
        fail("DEPENDENCY_SERVICE_INVALID")
    return ctx.execute(
        ["logs", "--no-color", "--no-log-prefix", service]
    ).decode("utf-8", "strict")


def dependency_hit_counts(ctx: HostContext) -> tuple[int, int]:
    external_lines = service_logs(ctx, "external-risk-mock").splitlines()
    rule_lines = service_logs(ctx, "ai-service").splitlines()
    external = sum(line == EXTERNAL_RISK_MARKER for line in external_lines)
    rule = sum(RULE_V2_ACCESS_PATTERN.fullmatch(line) is not None for line in rule_lines)
    return external, rule


def backend_metric_totals(ctx: HostContext) -> tuple[float, float]:
    output = ctx.execute(
        ["run", "--rm", "--no-deps", "-T", "keycloak-verify", "metric-runtime"],
        timeout=60,
    )
    try:
        parsed = json.loads(output)
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail("BACKEND_METRIC_SNAPSHOT_INVALID")
    if (
        not isinstance(parsed, list)
        or len(parsed) != 2
        or any(isinstance(value, bool) or not isinstance(value, (int, float)) for value in parsed)
    ):
        fail("BACKEND_METRIC_SNAPSHOT_INVALID")
    return float(parsed[0]), float(parsed[1])


def validate_table_snapshot(table: str, snapshot: TableSnapshot) -> None:
    if (
        table not in BUSINESS_TABLES
        or isinstance(snapshot.count, bool)
        or snapshot.count < 0
        or snapshot.count != len(snapshot.row_hashes)
        or snapshot.fingerprint != aggregate_fingerprint(table, snapshot.row_hashes)
    ):
        fail("DATABASE_GLOBAL_SNAPSHOT_INVALID")


def assert_global_delta(
    before: dict[str, TableSnapshot],
    after: dict[str, TableSnapshot],
    expected: dict[str, int],
) -> None:
    if (
        tuple(before) != BUSINESS_TABLES
        or tuple(after) != BUSINESS_TABLES
        or set(expected) - set(BUSINESS_TABLES)
        or any(
            isinstance(value, bool) or not isinstance(value, int) or value < 0
            for value in expected.values()
        )
    ):
        fail("DATABASE_GLOBAL_EXPECTATION_INVALID")
    for table in BUSINESS_TABLES:
        before_table = before[table]
        after_table = after[table]
        validate_table_snapshot(table, before_table)
        validate_table_snapshot(table, after_table)
        expected_delta = expected.get(table, 0)
        if (
            after_table.count != before_table.count + expected_delta
            or after_table.row_hashes[:before_table.count] != before_table.row_hashes
            or (expected_delta == 0 and after_table != before_table)
        ):
            fail("DATABASE_GLOBAL_DELTA_INVALID")


def run_ingestion_step(
    ctx: HostContext,
    plan: dict[str, str],
    step: str,
    expected_global_delta: dict[str, int],
    expected_cardinality: tuple[int, ...],
    expected_dependency_delta: tuple[int, int],
    expected_metric_delta: tuple[float, float],
) -> None:
    before_database = database_snapshot(ctx)
    before_dependencies = dependency_hit_counts(ctx)
    before_metrics = backend_metric_totals(ctx)
    output = ctx.execute(
        [
            "run", "--rm", "--no-deps", "-T", "keycloak-verify",
            "ingestion-runtime", "--step", step,
        ],
        input_bytes=json.dumps(plan, separators=(",", ":")).encode("utf-8"),
        timeout=180,
    ).decode("utf-8", "strict")
    if output.strip() != "ingestion step completed: " + step:
        fail("INGESTION_OUTPUT_INVALID")
    after_database = database_snapshot(ctx)
    after_dependencies = dependency_hit_counts(ctx)
    after_metrics = backend_metric_totals(ctx)
    assert_global_delta(before_database, after_database, expected_global_delta)
    if tuple(
        after_dependencies[index] - before_dependencies[index] for index in range(2)
    ) != expected_dependency_delta:
        fail("DEPENDENCY_HIT_DELTA_INVALID")
    metric_delta = tuple(
        after_metrics[index] - before_metrics[index] for index in range(2)
    )
    if metric_delta != expected_metric_delta:
        fail("BACKEND_OUTCOME_METRIC_DELTA_INVALID")
    if transaction_cardinality(ctx, plan) != expected_cardinality:
        fail("DATABASE_TRANSACTION_CARDINALITY_INVALID")
    print("stage: " + step + "-verified")


def run_ingestion_phase(ctx: HostContext) -> dict[str, str]:
    plan = create_plan()
    empty = expected_transaction_cardinality(False, False, False)
    if transaction_cardinality(ctx, plan) != empty:
        fail("DATABASE_TRANSACTION_CARDINALITY_INVALID")
    scenarios = (
        ("auth-denial", {}, empty, (0, 0), (0.0, 0.0)),
        (
            "behavior-create",
            {"behavior_event": 2},
            expected_transaction_cardinality(True, False, False),
            (0, 0),
            (0.0, 0.0),
        ),
        (
            "behavior-replay-conflict",
            {},
            expected_transaction_cardinality(True, False, False),
            (0, 0),
            (0.0, 0.0),
        ),
        (
            "transaction-create",
            {
                "audit_log": 4,
                "case_transaction": 1,
                "detection_evidence": 2,
                "detection_result": 1,
                "financial_transaction": 1,
                "fraud_case": 1,
                "idempotency_record": 1,
            },
            expected_transaction_cardinality(True, True, False),
            (1, 1),
            (1.0, 1.0),
        ),
        (
            "transaction-replay-key-conflict",
            {},
            expected_transaction_cardinality(True, True, False),
            (0, 0),
            (0.0, 0.0),
        ),
        (
            "duplicate-first",
            {"idempotency_record": 1},
            expected_transaction_cardinality(True, True, True),
            (0, 0),
            (0.0, 0.0),
        ),
        (
            "duplicate-replay",
            {},
            expected_transaction_cardinality(True, True, True),
            (0, 0),
            (0.0, 0.0),
        ),
    )
    for step, global_delta, cardinality, dependency_delta, metric_delta in scenarios:
        run_ingestion_step(
            ctx, plan, step, global_delta, cardinality, dependency_delta, metric_delta
        )
    print(
        "ingestion phase completed: risk=55/HIGH action=ADDITIONAL_AUTH_REQUIRED "
        "case=1 link=1 audit=4 external-risk=1 rule-v2=1 metrics=1/1"
    )
    return plan


def existing_volume_phase(ctx: HostContext) -> None:
    print("stage: existing-volume-restart")
    ctx.execute(["up", "-d", "--no-deps", "--force-recreate", "keycloak"], timeout=240)
    wait_container(ctx, "keycloak", "healthy")
    ctx.execute(
        ["run", "--rm", "--no-deps", "-T", "keycloak-bootstrap", "reconcile"],
        timeout=120,
    )
    host_runtime(ctx.repo / "infra" / "keycloak" / ".local" / "tls" / "localhost.crt")
    publish_rules(ctx)
    run_ingestion_phase(ctx)
    print("stage: existing-volume-ingestion-complete")


def cleanup_project(ctx: HostContext, original: BaseException | None) -> None:
    cleanup_error: BaseException | None = None
    try:
        run_command(
            ctx.compose + ["down", "--volumes", "--remove-orphans", "--timeout", "20"],
            timeout=240,
            cwd=ctx.repo,
            environment=ctx.environment,
        )
    except BaseException:
        # A Docker CLI timeout/non-zero result is not residual state evidence.
        # The exact postcondition inventory below remains authoritative.
        pass
    try:
        resources = project_resources(
            ctx.project,
            timeout=max(10, ctx.cli_timeout),
            repo=ctx.repo,
            environment=ctx.environment,
        )
        assert_resources_empty(resources)
    except BaseException as error:
        cleanup_error = error
    if original is not None:
        if cleanup_error is not None and hasattr(original, "add_note"):
            original.add_note("dedicated resource cleanup also failed")
        raise original
    if cleanup_error is not None:
        raise cleanup_error


def all_runtime(ctx: HostContext) -> None:
    resources = project_resources(
        ctx.project,
        timeout=ctx.cli_timeout,
        repo=ctx.repo,
        environment=ctx.environment,
    )
    assert_resources_empty(resources)
    config = json.loads(ctx.execute(["config", "--format", "json"]))
    realm_path = ctx.repo / "infra" / "keycloak" / "realm" / "finguardops-local-realm.json"
    validate_static(config, json.loads(realm_path.read_text(encoding="utf-8")))
    print("stage: static-complete")
    original: BaseException | None = None
    try:
        ctx.execute(
            ["up", "-d", "--build", "external-risk-mock", "keycloak-bootstrap"],
            timeout=900,
        )
        wait_container(ctx, "external-risk-mock", "healthy")
        wait_container(ctx, "keycloak-bootstrap", "completed")
        print("stage: fresh-runtime-ready")
        host_runtime(ctx.repo / "infra" / "keycloak" / ".local" / "tls" / "localhost.crt")
        publish_rules(ctx)
        print("stage: rules-active")
        run_ingestion_phase(ctx)
        print("stage: fresh-ingestion-complete")
        existing_volume_phase(ctx)
    except BaseException as error:
        original = error
    cleanup_project(ctx, original)
    print("all verification completed: fresh=passed existing-volume=passed cleanup=complete")


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
    ingestion_parser = subparsers.add_parser("ingestion-runtime")
    ingestion_parser.add_argument("--step", required=True, choices=INGESTION_STEPS)
    subparsers.add_parser("metric-runtime")
    host_parser = subparsers.add_parser("host")
    host_parser.add_argument("--certificate", required=True, type=Path)
    all_parser = subparsers.add_parser("all")
    all_parser.add_argument(
        "--repo-root", default=str(Path(__file__).resolve().parents[2]), type=Path
    )
    all_parser.add_argument(
        "--project", default="finguardops-kc241-e2e-manual"
    )
    all_parser.add_argument("--cli-timeout", default=30.0, type=float)
    all_parser.add_argument("--deadline-seconds", default=1800.0, type=float)
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
        elif args.mode == "ingestion-runtime":
            ingestion_runtime(args.step)
        elif args.mode == "metric-runtime":
            print(json.dumps(metric_totals(), separators=(",", ":")))
        elif args.mode == "host":
            host_runtime(args.certificate)
        else:
            repo = args.repo_root.resolve()
            if (
                not repo.is_absolute()
                or not (repo / ".git").exists()
                or PROJECT_PATTERN.fullmatch(args.project) is None
                or args.cli_timeout <= 0
                or args.deadline_seconds <= 0
            ):
                fail("HOST_ARGUMENT_INVALID")
            validate_cleanup_target(args.project, args.project)
            all_runtime(
                HostContext(
                    repo,
                    args.project,
                    args.cli_timeout,
                    args.deadline_seconds,
                )
            )
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

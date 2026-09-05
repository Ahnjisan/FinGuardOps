#!/usr/bin/env python3
"""Idempotently reconciles the non-production FinGuardOps Keycloak realm."""

from __future__ import annotations

import base64
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any

BASE_URL = "http://127.0.0.1:8082"
REALM = "finguardops-local"
ADMIN_CLIENT_ID = "temp-admin"
USER_NAME = "local-fds-analyst"
USER_ROLE = "FDS_ANALYST"
AUDIENCE = "finguardops-backend-api"
SECRET_PATTERN = re.compile(rb"[A-Za-z0-9_-]{32,128}\Z")
UUID4_PATTERN = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\Z"
)

ROLES = (
    "FDS_VIEWER",
    "FDS_ANALYST",
    "FDS_APPROVER",
    "RULE_OPERATOR",
    "RECOVERY_OPERATOR",
    "PLATFORM_ADMIN",
    "TRANSACTION_INGESTOR",
    "BEHAVIOR_INGESTOR",
)
USER_ROLES = ROLES[:6]
SERVICE_CLIENTS = {
    "finguardops-transaction-ingestor": {
        "scope": "finguardops-transaction-service-claims",
        "role": "TRANSACTION_INGESTOR",
        "secret": Path("/run/secrets/transaction_service_client_secret"),
    },
    "finguardops-behavior-ingestor": {
        "scope": "finguardops-behavior-service-claims",
        "role": "BEHAVIOR_INGESTOR",
        "secret": Path("/run/secrets/behavior_service_client_secret"),
    },
}
ADMIN_SECRET = Path("/run/secrets/keycloak_bootstrap_admin_secret")
USER_PASSWORD = Path("/run/secrets/user_password")
USER_DEFAULT_SCOPES = ("finguardops-backend-audience", "finguardops-user-claims")
USER_OPTIONAL_SCOPES = ("profile",)


class ReconcileError(RuntimeError):
    """Carries only a stable, non-sensitive error code."""


def fail(code: str) -> None:
    raise ReconcileError(code)


def read_secret(path: Path) -> str:
    try:
        if path.is_symlink() or not path.is_file():
            fail("SECRET_FILE_INVALID")
        value = path.read_bytes()
    except OSError:
        fail("SECRET_FILE_UNREADABLE")
    if not SECRET_PATTERN.fullmatch(value):
        fail("SECRET_CONTENT_INVALID")
    return value.decode("ascii")


def is_canonical_uuid4(value: Any) -> bool:
    if not isinstance(value, str) or UUID4_PATTERN.fullmatch(value) is None:
        return False
    try:
        parsed = uuid.UUID(value)
    except ValueError:
        return False
    return parsed.version == 4 and str(parsed) == value


def exact_one(items: Any, field: str, expected: str, resource: str) -> dict[str, Any] | None:
    if not isinstance(items, list):
        fail(f"{resource}_LOOKUP_INVALID")
    matches = [item for item in items if isinstance(item, dict) and item.get(field) == expected]
    if len(matches) > 1:
        fail(f"{resource}_AMBIGUOUS")
    return matches[0] if matches else None


class AdminClient:
    def __init__(self, token: str | None = None, opener: Any = None):
        self.token = token
        self.opener = opener or urllib.request.urlopen

    def request(
        self,
        method: str,
        path: str,
        payload: Any = None,
        *,
        form: bool = False,
        authorized: bool = True,
        expected: tuple[int, ...] = (200,),
    ) -> Any:
        headers = {"Accept": "application/json"}
        if self.token and authorized:
            headers["Authorization"] = "Bearer " + self.token
        data = None
        if payload is not None:
            if form:
                data = urllib.parse.urlencode(payload).encode("ascii")
                headers["Content-Type"] = "application/x-www-form-urlencoded"
            else:
                data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
                headers["Content-Type"] = "application/json"
        request = urllib.request.Request(BASE_URL + path, data=data, headers=headers, method=method)
        try:
            with self.opener(request, timeout=5) as response:
                status = response.status
                body = response.read()
                location = response.headers.get("Location")
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            if status == 404:
                fail("ADMIN_HTTP_NOT_FOUND")
            fail("ADMIN_HTTP_FAILED")
        except (urllib.error.URLError, TimeoutError, OSError):
            fail("ADMIN_HTTP_FAILED")
        if status not in expected:
            fail("ADMIN_HTTP_STATUS")
        if status == 204 or not body:
            return {"location": location} if location else None
        try:
            return json.loads(body)
        except (UnicodeDecodeError, json.JSONDecodeError):
            fail("ADMIN_RESPONSE_INVALID")

    def authenticate(self, secret: str) -> None:
        response = self.request(
            "POST",
            "/realms/master/protocol/openid-connect/token",
            {
                "grant_type": "client_credentials",
                "client_id": ADMIN_CLIENT_ID,
                "client_secret": secret,
            },
            form=True,
        )
        token = response.get("access_token") if isinstance(response, dict) else None
        if not isinstance(token, str) or not token:
            fail("ADMIN_TOKEN_INVALID")
        self.token = token


def q(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def token_subject(token: Any) -> str:
    if not isinstance(token, str):
        fail("SERVICE_TOKEN_INVALID")
    parts = token.split(".")
    if len(parts) != 3 or not all(parts):
        fail("SERVICE_TOKEN_INVALID")
    try:
        payload = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
    except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
        fail("SERVICE_TOKEN_INVALID")
    subject = payload.get("sub") if isinstance(payload, dict) else None
    if not is_canonical_uuid4(subject):
        fail("SERVICE_TOKEN_SUBJECT_INVALID")
    return subject


def role_representation(name: str) -> dict[str, Any]:
    return {"name": name, "description": "FinGuardOps application role", "composite": False}


def audience_mapper() -> dict[str, Any]:
    return {
        "name": "finguardops-backend-audience",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-audience-mapper",
        "consentRequired": False,
        "config": {
            "included.client.audience": AUDIENCE,
            "access.token.claim": "true",
            "id.token.claim": "false",
            "introspection.token.claim": "true",
        },
    }


def principal_mapper(name: str, principal_type: str, id_token: bool) -> dict[str, Any]:
    return {
        "name": name,
        "protocol": "openid-connect",
        "protocolMapper": "oidc-hardcoded-claim-mapper",
        "consentRequired": False,
        "config": {
            "claim.name": "principal_type",
            "claim.value": principal_type,
            "jsonType.label": "String",
            "access.token.claim": "true",
            "id.token.claim": str(id_token).lower(),
            "userinfo.token.claim": "false",
            "introspection.token.claim": "true",
        },
    }


def roles_mapper(name: str, id_token: bool) -> dict[str, Any]:
    return {
        "name": name,
        "protocol": "openid-connect",
        "protocolMapper": "oidc-usermodel-realm-role-mapper",
        "consentRequired": False,
        "config": {
            "claim.name": "roles",
            "jsonType.label": "String",
            "multivalued": "true",
            "access.token.claim": "true",
            "id.token.claim": str(id_token).lower(),
            "userinfo.token.claim": "false",
            "introspection.token.claim": "true",
        },
    }


def user_subject_mapper() -> dict[str, Any]:
    return {
        "name": "finguardops-user-subject",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-sub-mapper",
        "consentRequired": False,
        "config": {
            "access.token.claim": "true",
            "introspection.token.claim": "true",
        },
    }


def stock_profile_attribute_mapper(
    name: str, user_attribute: str, claim_name: str, *, property_mapper: bool = False
) -> dict[str, Any]:
    return {
        "name": name,
        "protocol": "openid-connect",
        "protocolMapper": (
            "oidc-usermodel-property-mapper" if property_mapper else "oidc-usermodel-attribute-mapper"
        ),
        "consentRequired": False,
        "config": {
            "userinfo.token.claim": "true",
            "user.attribute": user_attribute,
            "id.token.claim": "true",
            "access.token.claim": "true",
            "claim.name": claim_name,
            "jsonType.label": "String",
        },
    }


def stock_profile_mappers() -> list[dict[str, Any]]:
    return [
        stock_profile_attribute_mapper("family name", "lastName", "family_name", property_mapper=True),
        stock_profile_attribute_mapper("username", "username", "preferred_username", property_mapper=True),
        stock_profile_attribute_mapper("updated at", "updatedAt", "updated_at"),
        {
            "name": "full name",
            "protocol": "openid-connect",
            "protocolMapper": "oidc-full-name-mapper",
            "consentRequired": False,
            "config": {
                "id.token.claim": "true",
                "access.token.claim": "true",
                "userinfo.token.claim": "true",
            },
        },
        stock_profile_attribute_mapper("given name", "firstName", "given_name", property_mapper=True),
        stock_profile_attribute_mapper("middle name", "middleName", "middle_name"),
        stock_profile_attribute_mapper("gender", "gender", "gender"),
        stock_profile_attribute_mapper("zoneinfo", "zoneinfo", "zoneinfo"),
        stock_profile_attribute_mapper("nickname", "nickname", "nickname"),
        stock_profile_attribute_mapper("profile", "profile", "profile"),
        stock_profile_attribute_mapper("website", "website", "website"),
        stock_profile_attribute_mapper("birthdate", "birthdate", "birthdate"),
        stock_profile_attribute_mapper("picture", "picture", "picture"),
        stock_profile_attribute_mapper("locale", "locale", "locale"),
    ]


SCOPES = {
    "profile": stock_profile_mappers(),
    "finguardops-backend-audience": [audience_mapper()],
    "finguardops-user-claims": [
        user_subject_mapper(),
        principal_mapper("finguardops-user-principal-type", "USER", True),
        roles_mapper("finguardops-user-roles", True),
    ],
    "finguardops-transaction-service-claims": [
        principal_mapper("finguardops-transaction-service-principal-type", "SERVICE", False),
        roles_mapper("finguardops-transaction-service-roles", False),
    ],
    "finguardops-behavior-service-claims": [
        principal_mapper("finguardops-behavior-service-principal-type", "SERVICE", False),
        roles_mapper("finguardops-behavior-service-roles", False),
    ],
}
SCOPE_ROLES = {
    "profile": (),
    "finguardops-backend-audience": (),
    "finguardops-user-claims": USER_ROLES,
    "finguardops-transaction-service-claims": ("TRANSACTION_INGESTOR",),
    "finguardops-behavior-service-claims": ("BEHAVIOR_INGESTOR",),
}


def client_representation(client_id: str, secret: str | None = None) -> dict[str, Any]:
    if client_id == "finguardops-frontend":
        return {
            "clientId": client_id,
            "name": "FinGuardOps local frontend",
            "enabled": True,
            "protocol": "openid-connect",
            "publicClient": True,
            "clientAuthenticatorType": "client-secret",
            "standardFlowEnabled": True,
            "implicitFlowEnabled": False,
            "directAccessGrantsEnabled": False,
            "serviceAccountsEnabled": False,
            "fullScopeAllowed": False,
            "redirectUris": ["http://localhost:5173/auth/callback"],
            "webOrigins": ["http://localhost:5173"],
            "defaultClientScopes": list(USER_DEFAULT_SCOPES),
            "optionalClientScopes": list(USER_OPTIONAL_SCOPES),
            "attributes": {
                "pkce.code.challenge.method": "S256",
                "post.logout.redirect.uris": "http://localhost:5173/",
                "oauth2.device.authorization.grant.enabled": "false",
                "oidc.ciba.grant.enabled": "false",
                "use.refresh.tokens": "false",
            },
        }
    scope = SERVICE_CLIENTS[client_id]["scope"]
    result = {
        "clientId": client_id,
        "name": "FinGuardOps " + ("transaction" if "transaction" in client_id else "behavior") + " ingestor",
        "enabled": True,
        "protocol": "openid-connect",
        "publicClient": False,
        "clientAuthenticatorType": "client-secret",
        "standardFlowEnabled": False,
        "implicitFlowEnabled": False,
        "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": True,
        "fullScopeAllowed": False,
        "redirectUris": [],
        "webOrigins": [],
        "defaultClientScopes": ["finguardops-backend-audience", scope],
        "optionalClientScopes": [],
        "attributes": {
            "oauth2.device.authorization.grant.enabled": "false",
            "oidc.ciba.grant.enabled": "false",
        },
    }
    if secret is not None:
        result["secret"] = secret
    return result


class Reconciler:
    def __init__(self, admin: AdminClient):
        self.admin = admin

    @property
    def root(self) -> str:
        return "/admin/realms/" + q(REALM)

    def reconcile_realm(self) -> None:
        desired = {
            "realm": REALM,
            "enabled": True,
            "registrationAllowed": False,
            "rememberMe": False,
            "accessTokenLifespan": 900,
            "defaultSignatureAlgorithm": "RS256",
        }
        try:
            current = self.admin.request("GET", self.root)
        except ReconcileError as error:
            if str(error) != "ADMIN_HTTP_NOT_FOUND":
                raise
            self.admin.request("POST", "/admin/realms", desired, expected=(201, 204))
            return
        merged = dict(current)
        merged.update(desired)
        self.admin.request("PUT", self.root, merged, expected=(204,))

    def exact_roles(self) -> dict[str, dict[str, Any]]:
        items = self.admin.request("GET", self.root + "/roles?briefRepresentation=false")
        result: dict[str, dict[str, Any]] = {}
        for name in ROLES:
            item = exact_one(items, "name", name, "ROLE")
            if item:
                result[name] = item
        return result

    def reconcile_roles(self) -> dict[str, dict[str, Any]]:
        existing = self.exact_roles()
        for name in ROLES:
            desired = role_representation(name)
            if name not in existing:
                self.admin.request("POST", self.root + "/roles", desired, expected=(201, 204))
            else:
                desired["id"] = existing[name].get("id")
                self.admin.request("PUT", self.root + "/roles/" + q(name), desired, expected=(204,))
        result = self.exact_roles()
        if set(result) != set(ROLES):
            fail("ROLE_RECONCILE_INCOMPLETE")
        return result

    def list_scopes(self) -> list[dict[str, Any]]:
        return self.admin.request("GET", self.root + "/client-scopes")

    def reconcile_scope(self, name: str, mappers: list[dict[str, Any]]) -> str:
        current = exact_one(self.list_scopes(), "name", name, "SCOPE")
        if name == "profile":
            desired = {
                "name": name,
                "description": "OpenID Connect built-in scope: profile",
                "protocol": "openid-connect",
                "attributes": {
                    "include.in.token.scope": "true",
                    "display.on.consent.screen": "true",
                    "consent.screen.text": "${profileScopeConsentText}",
                },
            }
        else:
            desired = {
                "name": name,
                "protocol": "openid-connect",
                "attributes": {"include.in.token.scope": "false"},
            }
        if current is None:
            self.admin.request("POST", self.root + "/client-scopes", desired, expected=(201,))
            current = exact_one(self.list_scopes(), "name", name, "SCOPE")
        if current is None or not isinstance(current.get("id"), str):
            fail("SCOPE_ID_MISSING")
        scope_id = current["id"]
        updated = dict(current)
        updated.update(desired)
        self.admin.request("PUT", self.root + "/client-scopes/" + q(scope_id), updated, expected=(204,))
        self.reconcile_mappers(scope_id, mappers)
        return scope_id

    def reconcile_mappers(self, scope_id: str, desired_mappers: list[dict[str, Any]]) -> None:
        path = self.root + "/client-scopes/" + q(scope_id) + "/protocol-mappers/models"
        current = self.admin.request("GET", path)
        desired_names = {mapper["name"] for mapper in desired_mappers}
        for mapper in desired_mappers:
            existing = exact_one(current, "name", mapper["name"], "MAPPER")
            if existing is None:
                self.admin.request("POST", path, mapper, expected=(201, 204))
            else:
                mapper_id = existing.get("id")
                if not isinstance(mapper_id, str) or not mapper_id:
                    fail("MAPPER_ID_MISSING")
                updated = dict(mapper)
                updated["id"] = mapper_id
                self.admin.request("PUT", path + "/" + q(mapper_id), updated, expected=(204,))
        after = self.admin.request("GET", path)
        for name in desired_names:
            exact_one(after, "name", name, "MAPPER") or fail("MAPPER_RECONCILE_INCOMPLETE")

    def reconcile_scope_roles(
        self, scope_id: str, desired_names: tuple[str, ...], roles: dict[str, dict[str, Any]]
    ) -> None:
        path = self.root + "/client-scopes/" + q(scope_id) + "/scope-mappings/realm"
        current = self.admin.request("GET", path)
        app_current = [item for item in current if item.get("name") in ROLES]
        unwanted = [item for item in app_current if item.get("name") not in desired_names]
        missing = [roles[name] for name in desired_names if name not in {item.get("name") for item in app_current}]
        if unwanted:
            self.admin.request("DELETE", path, unwanted, expected=(204,))
        if missing:
            self.admin.request("POST", path, missing, expected=(204,))

    def list_clients(self, client_id: str) -> list[dict[str, Any]]:
        path = self.root + "/clients?clientId=" + q(client_id) + "&search=true"
        return self.admin.request("GET", path)

    def reconcile_client(self, client_id: str, secret: str | None = None) -> str:
        current = exact_one(self.list_clients(client_id), "clientId", client_id, "CLIENT")
        desired = client_representation(client_id, secret)
        if current is None:
            self.admin.request("POST", self.root + "/clients", desired, expected=(201,))
            current = exact_one(self.list_clients(client_id), "clientId", client_id, "CLIENT")
        if current is None or not isinstance(current.get("id"), str):
            fail("CLIENT_ID_MISSING")
        client_uuid = current["id"]
        updated = dict(current)
        updated.update(desired)
        self.admin.request("PUT", self.root + "/clients/" + q(client_uuid), updated, expected=(204,))
        return client_uuid

    def list_users(self) -> list[dict[str, Any]]:
        return self.admin.request("GET", self.root + "/users?username=" + q(USER_NAME) + "&exact=true")

    def reconcile_user(self, password: str) -> str:
        current = exact_one(self.list_users(), "username", USER_NAME, "USER")
        desired = {
            "username": USER_NAME,
            "firstName": "Local",
            "lastName": "Analyst",
            "email": "local-fds-analyst@finguardops.invalid",
            "enabled": True,
            "emailVerified": False,
            "requiredActions": [],
        }
        if current is None:
            desired["id"] = str(uuid.uuid4())
            self.admin.request("POST", self.root + "/users", desired, expected=(201,))
            current = exact_one(self.list_users(), "username", USER_NAME, "USER")
        if current is None or not is_canonical_uuid4(current.get("id")):
            fail("USER_UUID_INVALID")
        user_id = current["id"]
        updated = dict(current)
        updated.update(desired)
        updated["id"] = user_id
        self.admin.request("PUT", self.root + "/users/" + q(user_id), updated, expected=(204,))
        credentials = self.admin.request("GET", self.root + "/users/" + q(user_id) + "/credentials")
        if not isinstance(credentials, list):
            fail("USER_CREDENTIAL_METADATA_INVALID")
        for credential in credentials:
            if not isinstance(credential, dict):
                fail("USER_CREDENTIAL_METADATA_INVALID")
            credential_id = credential.get("id")
            if not isinstance(credential_id, str) or not credential_id:
                fail("USER_CREDENTIAL_ID_INVALID")
            self.admin.request(
                "DELETE",
                self.root + "/users/" + q(user_id) + "/credentials/" + q(credential_id),
                expected=(204,),
            )
        self.admin.request(
            "PUT",
            self.root + "/users/" + q(user_id) + "/reset-password",
            {"type": "password", "value": password, "temporary": False},
            expected=(204,),
        )
        self.reconcile_user_roles(user_id, (USER_ROLE,))
        credential_metadata = self.admin.request(
            "GET", self.root + "/users/" + q(user_id) + "/credentials"
        )
        if (
            not isinstance(credential_metadata, list)
            or len(credential_metadata) != 1
            or not isinstance(credential_metadata[0], dict)
            or credential_metadata[0].get("type") != "password"
            or not isinstance(credential_metadata[0].get("id"), str)
            or not credential_metadata[0]["id"]
        ):
            fail("USER_PASSWORD_CREDENTIAL_INVALID")
        return user_id

    def reconcile_user_roles(self, user_id: str, desired_names: tuple[str, ...]) -> None:
        path = self.root + "/users/" + q(user_id) + "/role-mappings/realm"
        current = self.admin.request("GET", path)
        existing_names = {item.get("name") for item in current if item.get("name") in ROLES}
        role_map = self.exact_roles()
        unwanted = [item for item in current if item.get("name") not in desired_names]
        missing = [role_map[name] for name in desired_names if name not in existing_names]
        if unwanted:
            self.admin.request("DELETE", path, unwanted, expected=(204,))
        if missing:
            self.admin.request("POST", path, missing, expected=(204,))
        after = self.admin.request("GET", path)
        final_names = {item.get("name") for item in after}
        if final_names != set(desired_names):
            fail("USER_ROLE_RECONCILE_INCOMPLETE")

    def service_account_id(self, client_uuid: str, role: str) -> str:
        service_user = self.admin.request("GET", self.root + "/clients/" + q(client_uuid) + "/service-account-user")
        user_id = service_user.get("id") if isinstance(service_user, dict) else None
        if not is_canonical_uuid4(user_id):
            fail("SERVICE_ACCOUNT_UUID_INVALID")
        self.reconcile_user_roles(user_id, (role,))
        return user_id

    def validate_service_token_subject(self, client_id: str, secret: str, expected_id: str) -> None:
        response = self.admin.request(
            "POST",
            "/realms/" + q(REALM) + "/protocol/openid-connect/token",
            {
                "grant_type": "client_credentials",
                "client_id": client_id,
                "client_secret": secret,
            },
            form=True,
            authorized=False,
        )
        token = response.get("access_token") if isinstance(response, dict) else None
        if token_subject(token) != expected_id:
            fail("SERVICE_TOKEN_SUBJECT_MISMATCH")

    def run(self, secrets: dict[str, str], user_password: str) -> None:
        self.reconcile_realm()
        roles = self.reconcile_roles()
        scope_ids: dict[str, str] = {}
        for name, mappers in SCOPES.items():
            scope_id = self.reconcile_scope(name, mappers)
            scope_ids[name] = scope_id
            self.reconcile_scope_roles(scope_id, SCOPE_ROLES[name], roles)

        self.reconcile_client("finguardops-frontend")
        for client_id, spec in SERVICE_CLIENTS.items():
            client_uuid = self.reconcile_client(client_id, secrets[client_id])
            service_account_id = self.service_account_id(client_uuid, spec["role"])
            self.validate_service_token_subject(client_id, secrets[client_id], service_account_id)
        self.reconcile_user(user_password)

        # Re-query exact names so a duplicate or incomplete result cannot produce a completion marker.
        self.reconcile_roles()
        for name in SCOPES:
            exact_one(self.list_scopes(), "name", name, "SCOPE") or fail("SCOPE_RECHECK_FAILED")
        for client_id in ("finguardops-frontend", *SERVICE_CLIENTS):
            exact_one(self.list_clients(client_id), "clientId", client_id, "CLIENT") or fail("CLIENT_RECHECK_FAILED")
def main(argv: list[str]) -> int:
    if argv != ["reconcile"]:
        print("bootstrap failed: ARGUMENT_INVALID", file=sys.stderr)
        return 2
    try:
        if os.environ.get("KEYCLOAK_ADMIN_BASE_URL") != BASE_URL:
            fail("ADMIN_BASE_URL_INVALID")
        admin_secret = read_secret(ADMIN_SECRET)
        service_secrets = {
            client_id: read_secret(spec["secret"]) for client_id, spec in SERVICE_CLIENTS.items()
        }
        user_password = read_secret(USER_PASSWORD)
        if len({admin_secret, *service_secrets.values(), user_password}) != 4:
            fail("LOCAL_CREDENTIALS_NOT_DISTINCT")
        admin = AdminClient()
        admin.authenticate(admin_secret)
        del admin_secret
        Reconciler(admin).run(service_secrets, user_password)
        service_secrets.clear()
        del user_password
        print("bootstrap completed: desired state reconciled")
        return 0
    except ReconcileError as error:
        print("bootstrap failed: " + str(error), file=sys.stderr)
        return 1
    except BaseException:
        print("bootstrap failed: UNEXPECTED_ERROR", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

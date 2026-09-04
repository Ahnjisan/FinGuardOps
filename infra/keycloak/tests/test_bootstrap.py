import contextlib
import base64
import io
import json
import os
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bootstrap


class FakeResponse:
    def __init__(self, status=200, body=b"{}", headers=None):
        self.status = status
        self._body = body
        self.headers = headers or {}

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return self._body


class RecordingAdmin:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def request(self, method, path, payload=None, **kwargs):
        self.calls.append((method, path, payload, kwargs))
        if not self.responses:
            raise AssertionError("unexpected request")
        response = self.responses.pop(0)
        if isinstance(response, BaseException):
            raise response
        return response


def service_token(subject):
    header = base64.urlsafe_b64encode(b'{"alg":"RS256"}').rstrip(b"=").decode()
    payload = base64.urlsafe_b64encode(json.dumps({"sub": subject}).encode()).rstrip(b"=").decode()
    return header + "." + payload + ".signature"


class BootstrapTests(unittest.TestCase):
    def test_exact_lookup_zero(self):
        self.assertIsNone(bootstrap.exact_one([], "name", "wanted", "ROLE"))

    def test_exact_lookup_one_ignores_partial(self):
        result = bootstrap.exact_one(
            [{"name": "wanted-extra"}, {"name": "wanted"}], "name", "wanted", "ROLE"
        )
        self.assertEqual(result, {"name": "wanted"})

    def test_exact_lookup_multiple_fails(self):
        with self.assertRaisesRegex(bootstrap.ReconcileError, "ROLE_AMBIGUOUS"):
            bootstrap.exact_one([{"name": "x"}, {"name": "x"}], "name", "x", "ROLE")

    def test_role_reconcile_creates_missing_and_updates_existing(self):
        existing = [{"id": "role-1", "name": "FDS_VIEWER"}]
        final = [{"id": f"role-{index}", "name": name} for index, name in enumerate(bootstrap.ROLES)]
        admin = RecordingAdmin([existing] + [None] * 8 + [final])
        result = bootstrap.Reconciler(admin).reconcile_roles()
        self.assertEqual(set(result), set(bootstrap.ROLES))
        methods = [call[0] for call in admin.calls[1:-1]]
        self.assertEqual(methods.count("PUT"), 1)
        self.assertEqual(methods.count("POST"), 7)

    def test_mapper_update_uses_existing_mapper_id(self):
        desired = bootstrap.principal_mapper("mapper-name", "SERVICE", False)
        admin = RecordingAdmin(
            [[{"id": "mapper-id", "name": "mapper-name"}], None, [{"id": "mapper-id", "name": "mapper-name"}]]
        )
        bootstrap.Reconciler(admin).reconcile_mappers("scope-id", [desired])
        update = admin.calls[1]
        self.assertEqual(update[0], "PUT")
        self.assertTrue(update[1].endswith("/mapper-id"))
        self.assertEqual(update[2]["id"], "mapper-id")

    def test_mapper_duplicate_is_rejected(self):
        desired = bootstrap.roles_mapper("roles-mapper", False)
        admin = RecordingAdmin(
            [[{"id": "a", "name": "roles-mapper"}, {"id": "b", "name": "roles-mapper"}]]
        )
        with self.assertRaisesRegex(bootstrap.ReconcileError, "MAPPER_AMBIGUOUS"):
            bootstrap.Reconciler(admin).reconcile_mappers("scope-id", [desired])

    def test_secret_reader_accepts_exact_ascii_without_newline(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "secret")
            path.write_bytes(b"A_0123456789-bcdefghijklmnopqrstuvwxyzABCDE")
            self.assertEqual(bootstrap.read_secret(path), path.read_text("ascii"))

    def test_secret_reader_rejects_empty_crlf_bom_whitespace_and_nul(self):
        invalid = [b"", b"A" * 32 + b"\r\n", b"\xef\xbb\xbf" + b"A" * 32, b"A" * 31 + b" ", b"A" * 31 + b"\x00"]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "secret")
            for value in invalid:
                path.write_bytes(value)
                with self.assertRaises(bootstrap.ReconcileError):
                    bootstrap.read_secret(path)

    def test_http_error_does_not_expose_body_or_secret(self):
        sentinel_secret = "NeverPrintThisSecret_12345678901234567890"
        sentinel_body = b'{"error":"NeverPrintThisBody"}'

        def opener(request, timeout):
            raise urllib.error.HTTPError(request.full_url, 401, "bad", {}, io.BytesIO(sentinel_body))

        client = bootstrap.AdminClient(opener=opener)
        with self.assertRaises(bootstrap.ReconcileError) as raised:
            client.authenticate(sentinel_secret)
        rendered = str(raised.exception)
        self.assertNotIn(sentinel_secret, rendered)
        self.assertNotIn("NeverPrintThisBody", rendered)

    def test_partial_failure_returns_nonzero_without_exception_detail(self):
        stderr = io.StringIO()
        with mock.patch.dict(os.environ, {"KEYCLOAK_ADMIN_BASE_URL": bootstrap.BASE_URL}), mock.patch.object(
            bootstrap, "read_secret", side_effect=bootstrap.ReconcileError("SAFE_CODE")
        ):
            with contextlib.redirect_stderr(stderr):
                result = bootstrap.main(["reconcile"])
        self.assertEqual(result, 1)
        self.assertEqual(stderr.getvalue(), "bootstrap failed: SAFE_CODE\n")

    def test_uuid_v4_is_exact_and_not_normalized(self):
        self.assertTrue(bootstrap.is_canonical_uuid4("32a6a5db-71e4-4e58-8b3f-ec8c2c07b69a"))
        self.assertFalse(bootstrap.is_canonical_uuid4("32A6A5DB-71E4-4E58-8B3F-EC8C2C07B69A"))
        self.assertFalse(bootstrap.is_canonical_uuid4("32a6a5db-71e4-1e58-8b3f-ec8c2c07b69a"))

    def test_service_clients_have_separate_scopes_and_no_user_role(self):
        transaction = bootstrap.client_representation("finguardops-transaction-ingestor", "x" * 32)
        behavior = bootstrap.client_representation("finguardops-behavior-ingestor", "y" * 32)
        self.assertIn("finguardops-transaction-service-claims", transaction["defaultClientScopes"])
        self.assertNotIn("finguardops-behavior-service-claims", transaction["defaultClientScopes"])
        self.assertIn("finguardops-behavior-service-claims", behavior["defaultClientScopes"])
        self.assertNotIn("finguardops-user-claims", behavior["defaultClientScopes"])

    def test_service_account_role_reconcile_removes_user_and_internal_roles(self):
        current = [
            {"id": "default-id", "name": "default-roles-finguardops-local"},
            {"id": "user-id", "name": "FDS_VIEWER"},
            {"id": "service-id", "name": "TRANSACTION_INGESTOR"},
        ]
        final = [{"id": "service-id", "name": "TRANSACTION_INGESTOR"}]
        admin = RecordingAdmin([current, None, final])
        reconciler = bootstrap.Reconciler(admin)
        with mock.patch.object(reconciler, "exact_roles", return_value={"TRANSACTION_INGESTOR": final[0]}):
            reconciler.reconcile_user_roles("service-account-id", ("TRANSACTION_INGESTOR",))
        deleted = admin.calls[1][2]
        self.assertEqual({item["name"] for item in deleted}, {"default-roles-finguardops-local", "FDS_VIEWER"})

    def test_service_secret_is_applied_exactly_on_create_and_update(self):
        secret = "Exact_Secret-012345678901234567890123456"
        admin = RecordingAdmin([[], None, [{"id": "client-id", "clientId": "finguardops-transaction-ingestor"}], None])
        bootstrap.Reconciler(admin).reconcile_client("finguardops-transaction-ingestor", secret)
        create_payload = admin.calls[1][2]
        update_payload = admin.calls[3][2]
        self.assertEqual(create_payload["secret"], secret)
        self.assertEqual(update_payload["secret"], secret)

    def test_user_reconcile_removes_all_credentials(self):
        user_id = "32a6a5db-71e4-4e58-8b3f-ec8c2c07b69a"
        admin = RecordingAdmin(
            [
                [{"id": user_id, "username": bootstrap.USER_NAME}],
                None,
                [{"id": "credential-id"}],
                None,
                [],
                None,
                [{"id": "role-id", "name": bootstrap.USER_ROLE}],
                [],
            ]
        )
        reconciler = bootstrap.Reconciler(admin)
        with mock.patch.object(reconciler, "exact_roles", return_value={bootstrap.USER_ROLE: {"id": "role-id", "name": bootstrap.USER_ROLE}}):
            self.assertEqual(reconciler.reconcile_user(), user_id)
        delete_paths = [call[1] for call in admin.calls if call[0] == "DELETE"]
        self.assertTrue(any(path.endswith("/credentials/credential-id") for path in delete_paths))

    def test_service_token_subject_matches_admin_service_account_id(self):
        subject = "32a6a5db-71e4-4e58-8b3f-ec8c2c07b69a"
        admin = RecordingAdmin([{"access_token": service_token(subject)}])
        bootstrap.Reconciler(admin).validate_service_token_subject(
            "finguardops-transaction-ingestor", "x" * 32, subject
        )
        request = admin.calls[0]
        self.assertFalse(request[3]["authorized"])
        self.assertEqual(request[2]["client_secret"], "x" * 32)

    def test_service_token_subject_mismatch_and_malformed_fail_closed(self):
        expected = "32a6a5db-71e4-4e58-8b3f-ec8c2c07b69a"
        other = "581f76f8-64bd-4bda-99fb-2c338e96d92a"
        for response, code in (
            ({"access_token": service_token(other)}, "SERVICE_TOKEN_SUBJECT_MISMATCH"),
            ({"access_token": "not-a-token"}, "SERVICE_TOKEN_INVALID"),
        ):
            with self.subTest(code=code), self.assertRaisesRegex(bootstrap.ReconcileError, code):
                bootstrap.Reconciler(RecordingAdmin([response])).validate_service_token_subject(
                    "finguardops-transaction-ingestor", "x" * 32, expected
                )


if __name__ == "__main__":
    unittest.main()

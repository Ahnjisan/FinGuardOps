import base64
import importlib.util
import io
import json
import os
import socketserver
import stat
import subprocess
import sys
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock


if not hasattr(socketserver, "ThreadingUnixStreamServer"):
    # Windows Python 3.9 cannot create the fixture UDS, but these unit tests do
    # not instantiate ControlServer. Runtime UDS coverage runs in Linux 3.12.
    socketserver.ThreadingUnixStreamServer = socketserver.ThreadingTCPServer


MODULE_PATH = Path(__file__).resolve().parents[1] / "fixture.py"
SPEC = importlib.util.spec_from_file_location("local_jwt_fixture", MODULE_PATH)
fixture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixture)
VERIFIER_PATH = Path(__file__).resolve().parents[1] / "verify_e2e.py"
VERIFIER_SPEC = importlib.util.spec_from_file_location("local_jwt_verifier", VERIFIER_PATH)
verifier = importlib.util.module_from_spec(VERIFIER_SPEC)
VERIFIER_SPEC.loader.exec_module(verifier)


def decode_segment(value):
    return json.loads(base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)))


class FixtureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.old_state_dir = fixture.STATE_DIR
        self.old_socket = fixture.CONTROL_SOCKET
        fixture.STATE_DIR = Path(self.temp.name)
        fixture.CONTROL_SOCKET = fixture.STATE_DIR / "control.sock"

    def tearDown(self):
        fixture.STATE_DIR = self.old_state_dir
        fixture.CONTROL_SOCKET = self.old_socket
        self.temp.cleanup()

    def test_identity_allowlist_has_exact_public_contract(self):
        expected = {
            "service-transaction-ingestor": ("9d0edbde-f833-43e2-822a-43a1c38d82ec", "SERVICE", ("TRANSACTION_INGESTOR",)),
            "service-behavior-ingestor": ("a0dc7e4b-1260-4888-9e14-54867c9f2293", "SERVICE", ("BEHAVIOR_INGESTOR",)),
            "user-viewer": ("3d005f9e-f48e-45e9-98f1-5f9c407d2021", "USER", ("FDS_VIEWER",)),
            "user-analyst": ("8fbcd138-76f7-44a8-85f1-3afcf118f1c6", "USER", ("FDS_ANALYST",)),
            "user-approver": ("f5b2501d-0c30-462b-b699-8cbb7aa6f3f2", "USER", ("FDS_APPROVER",)),
            "user-analyst-approver": ("35b78471-c387-48a8-af51-3490c8718216", "USER", ("FDS_ANALYST", "FDS_APPROVER")),
            "user-platform-admin": ("edaa43d7-c04f-4195-a7f7-82ee7f1a0de1", "USER", ("PLATFORM_ADMIN",)),
        }
        actual = {name: (item.sub, item.principal_type, item.roles)
                  for name, item in fixture.IDENTITIES.items()}
        self.assertEqual(expected, actual)
        self.assertEqual(840, fixture.TOKEN_LIFETIME_SECONDS)
        for identity in fixture.IDENTITIES.values():
            import uuid
            parsed = uuid.UUID(identity.sub)
            self.assertEqual(4, parsed.version)
            self.assertEqual(str(parsed), identity.sub)
            self.assertIn(identity.principal_type, {"USER", "SERVICE"})
            self.assertEqual(len(identity.roles), len(set(identity.roles)))
            if identity.principal_type == "USER":
                self.assertTrue(all(role.startswith("FDS_") or role == "PLATFORM_ADMIN" for role in identity.roles))
            else:
                self.assertTrue(all(role.endswith("_INGESTOR") for role in identity.roles))

    def test_generated_rsa_jwk_and_permissions(self):
        key = fixture.generate_key("test")
        self.assertGreaterEqual(len(base64.urlsafe_b64decode(key.jwk["n"] + "==")), 256)
        self.assertEqual("AQAB", key.jwk["e"])
        self.assertEqual({"alg", "e", "kid", "kty", "n", "use"}, set(key.jwk))
        self.assertEqual(("RSA", "sig", "RS256"), (key.jwk["kty"], key.jwk["use"], key.jwk["alg"]))
        self.assertTrue(key.kid)
        self.assertEqual(0o600, stat.S_IMODE(key.path.stat().st_mode))

    def test_normal_token_claims_are_exact(self):
        state = fixture.FixtureState()
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            token = state.mint("user-analyst-approver")
        self.assertEqual("", stdout.getvalue())
        header, claims, _signature = token.split(".")
        header = decode_segment(header)
        claims = decode_segment(claims)
        self.assertEqual({"alg": "RS256", "kid": state.active.kid, "typ": "JWT"}, header)
        self.assertEqual("https://local-jwt.fixture.finguardops.invalid", claims["iss"])
        self.assertEqual(["finguardops-backend-api"], claims["aud"])
        self.assertEqual("USER", claims["principal_type"])
        self.assertEqual(["FDS_ANALYST", "FDS_APPROVER"], claims["roles"])
        self.assertLessEqual(claims["exp"] - claims["iat"], 900)
        self.assertNotIn("scope", claims)
        self.assertNotIn("authorities", claims)
        self.assertNotIn("jku", header)
        self.assertNotIn("x5u", header)

    def test_all_negative_variants_are_fixed_and_non_arbitrary(self):
        state = fixture.FixtureState()
        state.stage()
        for variant in sorted(fixture.NEGATIVE_VARIANTS):
            token = state.mint("service-transaction-ingestor", variant)
            self.assertEqual(2, token.count("."), variant)
        with self.assertRaises(KeyError):
            state.mint("arbitrary-user")
        with self.assertRaises(ValueError):
            fixture.execute_command(state, {"command": "mint", "identity": "user-viewer",
                                            "variant": "normal", "lifetime": 999999})

    def test_rotation_overlap_and_unpublished_key(self):
        state = fixture.FixtureState()
        first = state.active.kid
        state.rotate_overlap()
        second = state.active.kid
        staged = state.stage()
        self.assertNotEqual(first, second)
        self.assertNotIn(staged, [item["kid"] for item in state.jwks()["keys"]])
        self.assertEqual(2, len(state.jwks()["keys"]))
        header = decode_segment(state.mint("user-viewer", "unpublished-key").split(".")[0])
        self.assertEqual(staged, header["kid"])

    def test_concurrent_mint_and_rotation_always_match_kid_and_signing_key(self):
        state = fixture.FixtureState()
        initial_kid = state.active.kid
        tokens = []
        token_lock = threading.Lock()

        def mint_many():
            produced = [state.mint("user-viewer") for _ in range(12)]
            with token_lock:
                tokens.extend(produced)

        def rotate_many():
            for _ in range(6):
                state.rotate_overlap()

        with ThreadPoolExecutor(max_workers=5) as pool:
            futures = [pool.submit(mint_many) for _ in range(4)]
            futures.append(pool.submit(rotate_many))
            for future in futures:
                future.result(timeout=60)

        materials = {item.kid: item for item in state.published}
        self.assertEqual(48, len(tokens))
        self.assertIn(initial_kid, materials)
        self.assertEqual(state.active.kid, state.jwks()["keys"][-1]["kid"])
        self.assertNotEqual(initial_kid, state.active.kid)
        verified = 0
        for index, token in enumerate(tokens):
            header, claims, signature = token.split(".")
            kid = decode_segment(header)["kid"]
            self.assertIn(kid, materials)
            public_path = Path(self.temp.name) / ("public-%d.pem" % index)
            signature_path = Path(self.temp.name) / ("signature-%d.bin" % index)
            public_path.write_bytes(fixture.run_openssl([
                "openssl", "pkey", "-in", str(materials[kid].path), "-pubout"
            ]))
            signature_path.write_bytes(base64.urlsafe_b64decode(
                signature + "=" * (-len(signature) % 4)
            ))
            result = subprocess.run(
                ["openssl", "dgst", "-sha256", "-verify", str(public_path),
                 "-signature", str(signature_path)],
                input=(header + "." + claims).encode("ascii"),
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
                shell=False, timeout=fixture.OPENSSL_TIMEOUT_SECONDS,
            )
            self.assertEqual(0, result.returncode)
            verified += 1
        self.assertEqual(48, verified)

    def test_control_protocol_rejects_unknown_duplicate_and_extra_fields(self):
        state = mock.Mock()
        rejected = [
            {"command": "unknown"},
            {"command": "status", "extra": True},
            {"command": "mint", "identity": "user-viewer", "variant": "normal", "roles": []},
        ]
        for request in rejected:
            with self.assertRaises(ValueError):
                fixture.execute_command(state, request)
        with self.assertRaises(ValueError):
            fixture.strict_json(b'{"command":"status","command":"status"}\n')

    def test_actual_cli_rejects_arbitrary_identity_role_and_lifetime_without_token(self):
        attempts = [
            ["machine", "mint", "arbitrary-user"],
            ["machine", "mint", "user-viewer", "--role", "FDS_APPROVER"],
            ["machine", "mint", "user-viewer", "--lifetime", "99999"],
        ]
        for arguments in attempts:
            result = subprocess.run(
                [sys.executable, "-B", str(MODULE_PATH), *arguments],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
                shell=False, timeout=10,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIsNone(verifier.JWT_RE.search(result.stdout.decode("utf-8", "replace")))
            self.assertIsNone(verifier.JWT_RE.search(result.stderr.decode("utf-8", "replace")))

    def test_openssl_uses_shell_false_timeout_and_checks_exit(self):
        completed = subprocess_result = mock.Mock(returncode=0, stdout=b"ok", stderr=b"")
        with mock.patch.object(fixture.subprocess, "run", return_value=completed) as run:
            self.assertEqual(b"ok", fixture.run_openssl(["openssl", "version"]))
            self.assertFalse(run.call_args.kwargs["shell"])
            self.assertEqual(fixture.OPENSSL_TIMEOUT_SECONDS, run.call_args.kwargs["timeout"])
            self.assertFalse(run.call_args.kwargs["check"])
        subprocess_result.returncode = 1
        with mock.patch.object(fixture.subprocess, "run", return_value=subprocess_result):
            with self.assertRaisesRegex(RuntimeError, "OpenSSL operation failed"):
                fixture.run_openssl(["openssl", "version"])

    def test_http_surface_and_no_cors(self):
        state = fixture.FixtureState()
        server = fixture.ThreadingHttpServer(("127.0.0.1", 0), fixture.FixtureHttpHandler)
        server.fixture_state = state
        import threading
        import urllib.error
        import urllib.request
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = "http://127.0.0.1:%d" % server.server_address[1]
        try:
            with urllib.request.urlopen(base + fixture.READY_PATH) as response:
                self.assertEqual(200, response.status)
                self.assertIsNone(response.headers.get("Access-Control-Allow-Origin"))
            with urllib.request.urlopen(base + fixture.JWKS_PATH) as response:
                self.assertEqual(1, len(json.load(response)["keys"]))
            with self.assertRaises(urllib.error.HTTPError) as missing:
                urllib.request.urlopen(base + "/token")
            self.assertEqual(404, missing.exception.code)
            request = urllib.request.Request(base + fixture.JWKS_PATH, data=b"x", method="POST")
            with self.assertRaises(urllib.error.HTTPError) as method:
                urllib.request.urlopen(request)
            self.assertEqual(405, method.exception.code)
            request = urllib.request.Request(base + fixture.JWKS_PATH, method="TRACE")
            with self.assertRaises(urllib.error.HTTPError) as method:
                urllib.request.urlopen(request)
            self.assertEqual(405, method.exception.code)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)

    def test_machine_and_show_output_boundaries(self):
        token = "header.claim.signature"
        with mock.patch.object(fixture, "socket_request", return_value={"ok": True, "token": token}):
            stdout, stderr = io.StringIO(), io.StringIO()
            with redirect_stdout(stdout), redirect_stderr(stderr):
                self.assertEqual(0, fixture.cli(["machine", "mint", "user-viewer"]))
            self.assertEqual(token + "\n", stdout.getvalue())
            self.assertEqual("", stderr.getvalue())
            stdout, stderr = io.StringIO(), io.StringIO()
            with redirect_stdout(stdout), redirect_stderr(stderr):
                self.assertEqual(0, fixture.cli(["show", "mint", "user-viewer"]))
            self.assertEqual(token + "\n", stdout.getvalue())
            self.assertNotIn(token, stderr.getvalue())
            self.assertIn("sensitive", stderr.getvalue())

    def test_failure_output_does_not_include_captured_token(self):
        sentinel = "header.secret.signature"
        with mock.patch.object(fixture, "socket_request", side_effect=RuntimeError("failed " + sentinel)):
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                try:
                    fixture.cli(["machine", "mint", "user-viewer"])
                except RuntimeError as exc:
                    self.assertNotIn(sentinel, type(exc).__name__)
            self.assertNotIn(sentinel, stderr.getvalue())

    def test_fresh_project_checks_exact_label_for_all_resource_kinds(self):
        class Context:
            project = "finguardops-jwt-e2e-unit01"
            def remaining(self):
                return 10

        calls = []
        def runner(argv, *, timeout):
            calls.append(argv)
            return b""
        verifier.ensure_fresh_project(
            Context(),
            lambda project, timeout: verifier.project_resources(
                project, timeout=timeout, command_runner=runner
            ),
        )
        self.assertEqual(3, len(calls))
        for argv in calls:
            self.assertIn("label=com.docker.compose.project=finguardops-jwt-e2e-unit01", argv)
        for kind in verifier.PROJECT_RESOURCE_KINDS:
            resources = {item: () for item in verifier.PROJECT_RESOURCE_KINDS}
            resources[kind] = ("owned-resource",)
            with self.assertRaises(verifier.VerificationError):
                verifier.ensure_fresh_project(
                    Context(), lambda _project, timeout, value=resources: value
                )

    def test_cleanup_normal_intermediate_failure_down_failure_and_residuals(self):
        class Context:
            project = "finguardops-jwt-e2e-unit02"
            timeout = 10
            end = float("inf")
            def __init__(self, down_error=None):
                self.down_error = down_error
                self.calls = []
            def compose_run(self, args, **_kwargs):
                self.calls.append(args)
                if self.down_error:
                    raise self.down_error

        empty = {kind: () for kind in verifier.PROJECT_RESOURCE_KINDS}
        reader = lambda _project, timeout: empty
        normal = Context()
        verifier.finalize_project(
            normal, resource_reader=reader,
            down_action=lambda: normal.compose_run(["down"]),
        )
        self.assertEqual(["down"], normal.calls[0])

        primary = RuntimeError("primary E2E failure")
        with self.assertRaises(RuntimeError) as raised:
            context = Context()
            verifier.finalize_project(
                context, primary, reader,
                down_action=lambda: context.compose_run(["down"]),
            )
        self.assertIs(primary, raised.exception)

        with self.assertRaisesRegex(verifier.VerificationError, "compose down failed"):
            context = Context(verifier.VerificationError("down"))
            verifier.finalize_project(
                context, resource_reader=reader,
                down_action=lambda: context.compose_run(["down"]),
            )

        for kind in verifier.PROJECT_RESOURCE_KINDS:
            residual = dict(empty); residual[kind] = ("residual",)
            with self.assertRaisesRegex(verifier.VerificationError, kind):
                context = Context()
                verifier.finalize_project(
                    context,
                    resource_reader=lambda _project, timeout, value=residual: value,
                    down_action=lambda: context.compose_run(["down"]),
                )

        primary_with_cleanup = RuntimeError("primary remains authoritative")
        with self.assertRaises(RuntimeError) as raised:
            context = Context(verifier.VerificationError("down"))
            verifier.finalize_project(
                context,
                primary_with_cleanup,
                reader,
                down_action=lambda: context.compose_run(["down"]),
            )
        self.assertIs(primary_with_cleanup, raised.exception)

    def test_non_target_restart_guard_detects_identity_or_restart_change(self):
        before = {"backend": "id-a|started-a|0", "postgresql": "id-b|started-b|0"}
        verifier.assert_snapshots_unchanged(before, dict(before), "unit")
        after = dict(before); after["postgresql"] = "id-b|started-b|1"
        with self.assertRaisesRegex(verifier.VerificationError, "postgresql"):
            verifier.assert_snapshots_unchanged(before, after, "unit")


if __name__ == "__main__":
    unittest.main()

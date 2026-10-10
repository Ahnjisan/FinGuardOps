#!/usr/bin/env python3
"""Static and runtime verifier for the local Keycloak authentication boundary."""

from __future__ import annotations

import argparse
import base64
import ctypes
import datetime as dt
import errno
import hashlib
import http.client
import json
import math
import os
import re
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import unicodedata
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
SERVICE_PROJECT_PATTERN = re.compile(
    r"finguardops-kc241-e2e-[a-z0-9][a-z0-9-]{5,32}\Z"
)
RUN_FIXTURE_PROJECT = "finguardops-keycloak-browser-e2e"
COMPOSE_PROJECT_ENVIRONMENT = "FINGUARDOPS_E2E_COMPOSE_PROJECT"
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
BEFORE_NATIVE_FAILURE_CODES = {
    "RULE_PUBLISHED_STATE": {
        "start": "RULE_PUBLISHED_STATE_PROCESS_START_FAILED",
        "timeout": "RULE_PUBLISHED_STATE_TIMEOUT",
        "exit": "RULE_PUBLISHED_STATE_EXIT_NONZERO",
        "output": "RULE_PUBLISHED_STATE_OUTPUT_INVALID",
        "cleanup": "RULE_PUBLISHED_STATE_CLEANUP_FAILED",
    },
    "RULE_ACTIVE_STATE": {
        "start": "RULE_ACTIVE_STATE_PROCESS_START_FAILED",
        "timeout": "RULE_ACTIVE_STATE_TIMEOUT",
        "exit": "RULE_ACTIVE_STATE_EXIT_NONZERO",
        "output": "RULE_ACTIVE_STATE_OUTPUT_INVALID",
        "cleanup": "RULE_ACTIVE_STATE_CLEANUP_FAILED",
    },
    "RULE_PUBLICATION_COMMAND": {
        "start": "RULE_PUBLICATION_COMMAND_PROCESS_START_FAILED",
        "timeout": "RULE_PUBLICATION_COMMAND_TIMEOUT",
        "exit": "RULE_PUBLICATION_COMMAND_EXIT_NONZERO",
        "output": "RULE_PUBLICATION_COMMAND_OUTPUT_INVALID",
        "cleanup": "RULE_PUBLICATION_COMMAND_CLEANUP_FAILED",
    },
    "RULE_PUBLICATION_ONEOFF_CHECK": {
        "start": "RULE_PUBLICATION_ONEOFF_CHECK_PROCESS_START_FAILED",
        "timeout": "RULE_PUBLICATION_ONEOFF_CHECK_TIMEOUT",
        "exit": "RULE_PUBLICATION_ONEOFF_CHECK_EXIT_NONZERO",
        "output": "RULE_PUBLICATION_ONEOFF_CHECK_OUTPUT_INVALID",
        "cleanup": "RULE_PUBLICATION_ONEOFF_CHECK_CLEANUP_FAILED",
    },
    "RULE_ACTIVATION_POLL": {
        "start": "RULE_ACTIVATION_POLL_PROCESS_START_FAILED",
        "timeout": "RULE_ACTIVATION_POLL_TIMEOUT",
        "exit": "RULE_ACTIVATION_POLL_EXIT_NONZERO",
        "output": "RULE_ACTIVATION_POLL_OUTPUT_INVALID",
        "cleanup": "RULE_ACTIVATION_POLL_CLEANUP_FAILED",
    },
    "TRANSACTION_CARDINALITY_SNAPSHOT": {
        "start": "TRANSACTION_CARDINALITY_SNAPSHOT_PROCESS_START_FAILED",
        "timeout": "TRANSACTION_CARDINALITY_SNAPSHOT_TIMEOUT",
        "exit": "TRANSACTION_CARDINALITY_SNAPSHOT_EXIT_NONZERO",
        "output": "TRANSACTION_CARDINALITY_SNAPSHOT_OUTPUT_INVALID",
        "cleanup": "TRANSACTION_CARDINALITY_SNAPSHOT_CLEANUP_FAILED",
    },
    "DATABASE_GLOBAL_SNAPSHOT": {
        "start": "DATABASE_GLOBAL_SNAPSHOT_PROCESS_START_FAILED",
        "timeout": "DATABASE_GLOBAL_SNAPSHOT_TIMEOUT",
        "exit": "DATABASE_GLOBAL_SNAPSHOT_EXIT_NONZERO",
        "output": "DATABASE_GLOBAL_SNAPSHOT_OUTPUT_INVALID",
        "cleanup": "DATABASE_GLOBAL_SNAPSHOT_CLEANUP_FAILED",
    },
    "EXTERNAL_RISK_LOG_SNAPSHOT": {
        "start": "EXTERNAL_RISK_LOG_SNAPSHOT_PROCESS_START_FAILED",
        "timeout": "EXTERNAL_RISK_LOG_SNAPSHOT_TIMEOUT",
        "exit": "EXTERNAL_RISK_LOG_SNAPSHOT_EXIT_NONZERO",
        "output": "EXTERNAL_RISK_LOG_SNAPSHOT_OUTPUT_INVALID",
        "cleanup": "EXTERNAL_RISK_LOG_SNAPSHOT_CLEANUP_FAILED",
    },
    "RULE_V2_LOG_SNAPSHOT": {
        "start": "RULE_V2_LOG_SNAPSHOT_PROCESS_START_FAILED",
        "timeout": "RULE_V2_LOG_SNAPSHOT_TIMEOUT",
        "exit": "RULE_V2_LOG_SNAPSHOT_EXIT_NONZERO",
        "output": "RULE_V2_LOG_SNAPSHOT_OUTPUT_INVALID",
        "cleanup": "RULE_V2_LOG_SNAPSHOT_CLEANUP_FAILED",
    },
    "BACKEND_METRIC_SNAPSHOT": {
        "start": "BACKEND_METRIC_SNAPSHOT_PROCESS_START_FAILED",
        "timeout": "BACKEND_METRIC_SNAPSHOT_TIMEOUT",
        "exit": "BACKEND_METRIC_SNAPSHOT_EXIT_NONZERO",
        "output": "BACKEND_METRIC_SNAPSHOT_OUTPUT_INVALID",
        "cleanup": "BACKEND_METRIC_SNAPSHOT_CLEANUP_FAILED",
    },
}
BEFORE_NATIVE_OUTPUT_LIMITS = {
    "RULE_PUBLISHED_STATE": (64, 4096),
    "RULE_ACTIVE_STATE": (64, 4096),
    "RULE_PUBLICATION_COMMAND": (4_194_304, 65_536),
    "RULE_PUBLICATION_ONEOFF_CHECK": (4096, 4096),
    "RULE_ACTIVATION_POLL": (64, 4096),
    "TRANSACTION_CARDINALITY_SNAPSHOT": (2048, 4096),
    "DATABASE_GLOBAL_SNAPSHOT": (16_777_216, 4096),
    "EXTERNAL_RISK_LOG_SNAPSHOT": (16_777_216, 4096),
    "RULE_V2_LOG_SNAPSHOT": (16_777_216, 4096),
    "BACKEND_METRIC_SNAPSHOT": (4096, 4096),
}
SEMANTIC_STDERR_STAGES = frozenset({
    "RULE_PUBLICATION_COMMAND",
    "BACKEND_METRIC_SNAPSHOT",
})
SEMANTIC_STDERR_MAX_LINES = 128
SEMANTIC_STDERR_MAX_LINE_LENGTH = 4096
RULE_PUBLICATION_RUNNER_FAILURE_LINES = {
    "RULE_PUBLICATION_RUNNER_PRODUCTION_PROFILE_REJECTED": (
        "java.lang.IllegalStateException: Rule v1 default publication is forbidden in production",
        "Caused by: java.lang.IllegalStateException: Rule v1 default publication is forbidden in production",
    ),
    "RULE_PUBLICATION_RUNNER_APPROVED_PROFILE_REQUIRED": (
        "java.lang.IllegalStateException: Rule v1 default publication requires its operation profile and a local, dev, or test profile",
        "Caused by: java.lang.IllegalStateException: Rule v1 default publication requires its operation profile and a local, dev, or test profile",
    ),
    "RULE_PUBLICATION_RUNNER_NON_WEB_MODE_REQUIRED": (
        "java.lang.IllegalStateException: Rule v1 default publication requires spring.main.web-application-type=none",
        "Caused by: java.lang.IllegalStateException: Rule v1 default publication requires spring.main.web-application-type=none",
    ),
    "RULE_PUBLICATION_RUNNER_CONFIRMATION_REJECTED": (
        "java.lang.IllegalStateException: Rule v1 default publication confirmation does not match",
        "Caused by: java.lang.IllegalStateException: Rule v1 default publication confirmation does not match",
    ),
    "RULE_PUBLICATION_RUNNER_EFFECTIVE_FROM_FORMAT_REJECTED": (
        "java.lang.IllegalArgumentException: Rule v1 default effectiveFrom must be a canonical UTC Instant",
        "Caused by: java.lang.IllegalArgumentException: Rule v1 default effectiveFrom must be a canonical UTC Instant",
        "java.lang.IllegalArgumentException: Rule v1 default effectiveFrom must be canonical UTC with at most microsecond precision",
        "Caused by: java.lang.IllegalArgumentException: Rule v1 default effectiveFrom must be canonical UTC with at most microsecond precision",
    ),
    "RULE_PUBLICATION_RUNNER_EFFECTIVE_FROM_NOT_FUTURE": (
        "java.lang.IllegalArgumentException: Rule v1 default effectiveFrom must be in the future",
        "Caused by: java.lang.IllegalArgumentException: Rule v1 default effectiveFrom must be in the future",
    ),
    "RULE_PUBLICATION_SERVICE_DEFAULT_SET_INCOMPLETE": (
        "java.lang.IllegalStateException: The complete V5 default Rule v1 set does not exist",
        "Caused by: java.lang.IllegalStateException: The complete V5 default Rule v1 set does not exist",
    ),
    "RULE_PUBLICATION_SERVICE_IDENTITY_MISMATCH": (
        "java.lang.IllegalStateException: Default Rule v1 identity does not match the V5 contract",
        "Caused by: java.lang.IllegalStateException: Default Rule v1 identity does not match the V5 contract",
    ),
    "RULE_PUBLICATION_SERVICE_FRAUD_RULE_INACTIVE": (
        "java.lang.IllegalStateException: Default Rule v1 FraudRules must be ACTIVE",
        "Caused by: java.lang.IllegalStateException: Default Rule v1 FraudRules must be ACTIVE",
    ),
    "RULE_PUBLICATION_SERVICE_VERSION_PERIOD_INVALID": (
        "java.lang.IllegalStateException: Default Rule v1 versions must be open-ended",
        "Caused by: java.lang.IllegalStateException: Default Rule v1 versions must be open-ended",
    ),
    "RULE_PUBLICATION_SERVICE_VERSION_STATUS_INVALID": (
        "java.lang.IllegalStateException: Default Rule v1 versions must be all DRAFT or all PUBLISHED",
        "Caused by: java.lang.IllegalStateException: Default Rule v1 versions must be all DRAFT or all PUBLISHED",
    ),
    "RULE_PUBLICATION_SERVICE_DRAFT_METADATA_INVALID": (
        "java.lang.IllegalStateException: Default Rule v1 DRAFT period metadata must be unset",
        "Caused by: java.lang.IllegalStateException: Default Rule v1 DRAFT period metadata must be unset",
    ),
    "RULE_PUBLICATION_SERVICE_EFFECTIVE_FROM_EXPIRED": (
        "java.lang.IllegalArgumentException: effectiveFrom must be later than the publication time",
        "Caused by: java.lang.IllegalArgumentException: effectiveFrom must be later than the publication time",
    ),
    "RULE_PUBLICATION_SERVICE_AMOUNT_THRESHOLD_FORMAT_INVALID": (
        "java.lang.IllegalArgumentException: amountThreshold must be a positive canonical integer string within NUMERIC(19,4) integer range",
        "Caused by: java.lang.IllegalArgumentException: amountThreshold must be a positive canonical integer string within NUMERIC(19,4) integer range",
    ),
}
RULE_PUBLICATION_AUTHORITATIVE_FAILURE_CODES = frozenset({
    "RULE_PUBLICATION_BACKEND_STARTUP_FAILED",
    "RULE_PUBLICATION_CONTEXT_REFRESH_FAILED",
    "RULE_PUBLICATION_PRE_RUNNER_FAILED",
    "RULE_PUBLICATION_RUNNER_CONFIGURATION_FAILED",
    "RULE_PUBLICATION_SERVICE_EXECUTION_FAILED",
    *RULE_PUBLICATION_RUNNER_FAILURE_LINES,
})
RULE_PUBLICATION_FAILURE_WIRE_PREFIX = "FINGUARDOPS_RULE_PUBLICATION_FAILURE="
RULE_PUBLICATION_AUTHORITATIVE_FAILURE_LINES = {
    RULE_PUBLICATION_FAILURE_WIRE_PREFIX + code: code
    for code in RULE_PUBLICATION_AUTHORITATIVE_FAILURE_CODES
}
RULE_PUBLICATION_RUNNER_EXCEPTION_HEADLINE = re.compile(
    r' *(?:(?:Caused by|Suppressed): |Exception in thread "[^"\r\n]+" )?'
    r'(?:(?:[A-Za-z_$][A-Za-z0-9_$]*\.)+[A-Za-z_$][A-Za-z0-9_$]*'
    r'|(?:[A-Za-z_$][A-Za-z0-9_$]*)?'
    r'(?:Exception|Error|Failure|Throwable))(?::.*)?\Z'
)
RULE_PUBLICATION_RUNNER_STACK_FRAME = re.compile(
    r'\tat (?:[A-Za-z0-9_.@-]+/)?[A-Za-z_$][A-Za-z0-9_.$]*\.'
    r'[A-Za-z_$<>][A-Za-z0-9_$<>]*'
    r'\((?:[A-Za-z0-9_.$-]+:\d+|Unknown Source|Native Method|<generated>)\)'
    r'(?: ~\[[A-Za-z0-9_.!/@+-]+:[A-Za-z0-9_.+-]+\])?\Z'
)
RULE_PUBLICATION_RUNNER_STACK_ELISION = re.compile(
    r'\t\.\.\. [0-9]+ (?:more|common frames omitted)\Z'
)
RULE_PUBLICATION_RUNNER_NEUTRAL_LINES = (
    "java.lang.IllegalStateException: Failed to execute ApplicationRunner",
)
RULE_PUBLICATION_RUNNER_SUCCESS_MARKER = (
    "event=rule_v1_default_rule_set_publication outcome="
)
RULE_PUBLICATION_RUNNER_SUCCESS_EVIDENCE = (
    RULE_PUBLICATION_RUNNER_SUCCESS_MARKER + "PUBLISHED"
)
RULE_PUBLICATION_RUNNER_SUCCESS_LOG_LINE = re.compile(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?"
    r"(?:Z|[+-]\d{2}:\d{2}) +INFO +[0-9]+ +--- +\[[^\r\n]+\] +"
    r"[^\s\r\n]+ +: +.*"
    + re.escape(RULE_PUBLICATION_RUNNER_SUCCESS_EVIDENCE)
    + r"(?: +[^\r\n]+)?\Z"
)
RULE_PUBLICATION_STDERR_INVALID = "RULE_PUBLICATION_COMMAND_STDERR_INVALID"
RULE_PUBLICATION_FAILURE_EVIDENCE_INVALID = (
    "RULE_PUBLICATION_COMMAND_FAILURE_EVIDENCE_INVALID"
)
RULE_PUBLICATION_STDOUT_INVALID = "RULE_PUBLICATION_COMMAND_STDOUT_INVALID"
RULE_PUBLICATION_SUCCESS_MARKER_INVALID = (
    "RULE_PUBLICATION_COMMAND_SUCCESS_MARKER_INVALID"
)
RULE_PUBLICATION_STDOUT_ENCODING_INVALID = (
    "RULE_PUBLICATION_COMMAND_STDOUT_ENCODING_INVALID"
)
RULE_PUBLICATION_STDOUT_FINAL_NEWLINE_INVALID = (
    "RULE_PUBLICATION_COMMAND_STDOUT_FINAL_NEWLINE_INVALID"
)
RULE_PUBLICATION_STDOUT_BARE_CR_INVALID = (
    "RULE_PUBLICATION_COMMAND_STDOUT_BARE_CR_INVALID"
)
RULE_PUBLICATION_STDOUT_MIXED_NEWLINE_INVALID = (
    "RULE_PUBLICATION_COMMAND_STDOUT_MIXED_NEWLINE_INVALID"
)
RULE_PUBLICATION_STDOUT_NUL_INVALID = "RULE_PUBLICATION_COMMAND_STDOUT_NUL_INVALID"
RULE_PUBLICATION_STDOUT_TAB_INVALID = "RULE_PUBLICATION_COMMAND_STDOUT_TAB_INVALID"
RULE_PUBLICATION_STDOUT_ESCAPE_INVALID = (
    "RULE_PUBLICATION_COMMAND_STDOUT_ESCAPE_INVALID"
)
RULE_PUBLICATION_STDOUT_C0_INVALID = "RULE_PUBLICATION_COMMAND_STDOUT_C0_INVALID"
RULE_PUBLICATION_STDOUT_C1_INVALID = "RULE_PUBLICATION_COMMAND_STDOUT_C1_INVALID"
RULE_PUBLICATION_STDOUT_FORMAT_INVALID = (
    "RULE_PUBLICATION_COMMAND_STDOUT_FORMAT_INVALID"
)
# Fixed evaluation order. A capture that breaks several rules is named by the
# first entry only, so one capture always yields exactly one identity.
RULE_PUBLICATION_STDOUT_PREDICATE_CODES = (
    RULE_PUBLICATION_STDOUT_ENCODING_INVALID,
    RULE_PUBLICATION_STDOUT_FINAL_NEWLINE_INVALID,
    RULE_PUBLICATION_STDOUT_BARE_CR_INVALID,
    RULE_PUBLICATION_STDOUT_MIXED_NEWLINE_INVALID,
    RULE_PUBLICATION_STDOUT_NUL_INVALID,
    RULE_PUBLICATION_STDOUT_TAB_INVALID,
    RULE_PUBLICATION_STDOUT_ESCAPE_INVALID,
    RULE_PUBLICATION_STDOUT_C0_INVALID,
    RULE_PUBLICATION_STDOUT_C1_INVALID,
    RULE_PUBLICATION_STDOUT_FORMAT_INVALID,
)
RULE_PUBLICATION_SEMANTIC_FAILURE_CODES = (
    RULE_PUBLICATION_STDERR_INVALID,
    RULE_PUBLICATION_FAILURE_EVIDENCE_INVALID,
    RULE_PUBLICATION_STDOUT_INVALID,
    RULE_PUBLICATION_SUCCESS_MARKER_INVALID,
) + RULE_PUBLICATION_STDOUT_PREDICATE_CODES
INGESTION_STEPS = (
    "auth-denial",
    "behavior-create",
    "behavior-replay-conflict",
    "transaction-create",
    "transaction-replay-key-conflict",
    "duplicate-first",
    "duplicate-replay",
)
FIXTURE_MANIFEST_NAME = "fixture-identity.json"
FIXTURE_MANIFEST_DIRECTORY = Path("/finguardops/fixture")
FIXTURE_PLAN_ENVIRONMENT = "FINGUARDOPS_E2E_FIXTURE_PLAN"
FIXTURE_MANIFEST_KEYS = (
    "schemaVersion",
    "runId",
    "repositoryId",
    "commitSha",
    "treeSha",
    "composeProject",
    "transactionId",
    "caseId",
    "expectedRiskLevel",
    "expectedResponseOutcome",
    "expectedInitialCaseStatus",
)
RUN_FIXTURE_GLOBAL_DELTA = {
    "audit_log": 4,
    "behavior_event": 2,
    "case_transaction": 1,
    "detection_evidence": 3,
    "detection_result": 1,
    "financial_transaction": 1,
    "fraud_case": 1,
    "idempotency_record": 1,
}
RUN_FIXTURE_STATE_KEYS = (
    "schemaVersion",
    "runId",
    "repositoryId",
    "commitSha",
    "treeSha",
    "composeProject",
    "plan",
    "database",
    "dependencies",
    "metrics",
)
# The three shared HTTP identities say how a call failed but not which call.
# Inside the run-fixture worker each call site rewrites them to the literal for
# its own stage; every other mode keeps the shared identity unchanged. Nothing
# below is assembled from a response, a status, a URL or an exception.
RUN_FIXTURE_GENERIC_HTTP_FAILURES = (
    "HTTP_TRANSPORT_FAILED",
    "HTTP_STATUS_UNEXPECTED",
    "HTTP_JSON_INVALID",
)
RUN_FIXTURE_STAGE_FAILURE_CODES = {
    "TRANSACTION_TOKEN": {
        "HTTP_TRANSPORT_FAILED": "RUN_FIXTURE_TRANSACTION_TOKEN_TRANSPORT_FAILED",
        "HTTP_STATUS_UNEXPECTED": "RUN_FIXTURE_TRANSACTION_TOKEN_STATUS_UNEXPECTED",
        "HTTP_JSON_INVALID": "RUN_FIXTURE_TRANSACTION_TOKEN_JSON_INVALID",
        "RESPONSE_READ": "RUN_FIXTURE_TRANSACTION_TOKEN_RESPONSE_READ_FAILED",
    },
    "BEHAVIOR_TOKEN": {
        "HTTP_TRANSPORT_FAILED": "RUN_FIXTURE_BEHAVIOR_TOKEN_TRANSPORT_FAILED",
        "HTTP_STATUS_UNEXPECTED": "RUN_FIXTURE_BEHAVIOR_TOKEN_STATUS_UNEXPECTED",
        "HTTP_JSON_INVALID": "RUN_FIXTURE_BEHAVIOR_TOKEN_JSON_INVALID",
        "RESPONSE_READ": "RUN_FIXTURE_BEHAVIOR_TOKEN_RESPONSE_READ_FAILED",
    },
    "JWKS": {
        "HTTP_TRANSPORT_FAILED": "RUN_FIXTURE_JWKS_TRANSPORT_FAILED",
        "HTTP_STATUS_UNEXPECTED": "RUN_FIXTURE_JWKS_STATUS_UNEXPECTED",
        "HTTP_JSON_INVALID": "RUN_FIXTURE_JWKS_JSON_INVALID",
        "RESPONSE_READ": "RUN_FIXTURE_JWKS_RESPONSE_READ_FAILED",
    },
    "CROSS_SECRET": {
        "HTTP_TRANSPORT_FAILED": "RUN_FIXTURE_CROSS_SECRET_TRANSPORT_FAILED",
        "HTTP_STATUS_UNEXPECTED": "RUN_FIXTURE_CROSS_SECRET_STATUS_UNEXPECTED",
        "HTTP_JSON_INVALID": "RUN_FIXTURE_CROSS_SECRET_JSON_INVALID",
        "RESPONSE_READ": "RUN_FIXTURE_CROSS_SECRET_RESPONSE_READ_FAILED",
    },
    # `request_backend` already turns an unexpected status into the caller's
    # own status literal, so the three ingestion stages carry that literal and
    # a response literal instead of a generic status entry.
    "PASSWORD_EVENT": {
        "HTTP_TRANSPORT_FAILED": "RUN_FIXTURE_PASSWORD_EVENT_TRANSPORT_FAILED",
        "HTTP_JSON_INVALID": "RUN_FIXTURE_PASSWORD_EVENT_JSON_INVALID",
        "RESPONSE_READ": "RUN_FIXTURE_PASSWORD_EVENT_RESPONSE_READ_FAILED",
        "STATUS": "RUN_FIXTURE_PASSWORD_EVENT_STATUS",
        "RESPONSE_INVALID": "RUN_FIXTURE_PASSWORD_EVENT_RESPONSE_INVALID",
    },
    "TRANSFER_LIMIT_EVENT": {
        "HTTP_TRANSPORT_FAILED": "RUN_FIXTURE_TRANSFER_LIMIT_EVENT_TRANSPORT_FAILED",
        "HTTP_JSON_INVALID": "RUN_FIXTURE_TRANSFER_LIMIT_EVENT_JSON_INVALID",
        "RESPONSE_READ": "RUN_FIXTURE_TRANSFER_LIMIT_EVENT_RESPONSE_READ_FAILED",
        "STATUS": "RUN_FIXTURE_TRANSFER_LIMIT_EVENT_STATUS",
        "RESPONSE_INVALID": "RUN_FIXTURE_TRANSFER_LIMIT_EVENT_RESPONSE_INVALID",
    },
    "TRANSACTION": {
        "HTTP_TRANSPORT_FAILED": "RUN_FIXTURE_TRANSACTION_TRANSPORT_FAILED",
        "HTTP_JSON_INVALID": "RUN_FIXTURE_TRANSACTION_JSON_INVALID",
        "RESPONSE_READ": "RUN_FIXTURE_TRANSACTION_RESPONSE_READ_FAILED",
        "STATUS": "RUN_FIXTURE_TRANSACTION_STATUS",
        "RESPONSE_INVALID": "RUN_FIXTURE_TRANSACTION_RESPONSE_INVALID",
    },
}
# The statuses `request_backend` accepts from the Backend other than the 201 a
# run fixture expects. Each one becomes a `<STATUS literal>_<status>` identity.
RUN_FIXTURE_BACKEND_REJECTED_STATUSES = (200, 400, 401, 403, 409, 422, 500, 503)
# Every identity the run-fixture worker can end on. The PowerShell runner
# forwards a container marker only when it is one of these, so this tuple and
# the runner allowlist are the same set and a test holds them together.
RUN_FIXTURE_WORKER_FAILURE_CODES = tuple(
    [
        "HOST_ARGUMENT_INVALID",
        "RUN_FIXTURE_PLAN_INVALID",
        "INGESTION_PLAN_INVALID",
        "RUNTIME_SECRET_FILE",
        "RUNTIME_SECRET_CONTENT",
        "SERVICE_SECRETS_NOT_DISTINCT",
        "SERVICE_REFRESH_TOKEN_PRESENT",
        "TOKEN_RESPONSE_INVALID",
        "JWKS_INVALID",
        "JWKS_SIGNING_KEY_INVALID",
        "TOKEN_COMPACT_INVALID",
        "TOKEN_HEADER_INVALID",
        "TOKEN_ISSUER_INVALID",
        "TOKEN_AUDIENCE_INVALID",
        "TOKEN_AUDIENCE_REPRESENTATION",
        "TOKEN_SUBJECT_INVALID",
        "TOKEN_PRINCIPAL_INVALID",
        "TOKEN_ROLES_INVALID",
        "TOKEN_TIME_TYPE_INVALID",
        "TOKEN_TIME_ORDER_INVALID",
        "TOKEN_TIME_LIFETIME_INVALID",
        "TOKEN_TIME_IAT_FUTURE",
        "TOKEN_TIME_EXPIRED",
        "TOKEN_TIME_NBF_INVALID",
        "TOKEN_TIME_NBF_FUTURE",
    ]
    + [
        code
        for stage in RUN_FIXTURE_STAGE_FAILURE_CODES.values()
        for code in stage.values()
    ]
    + [
        stage["STATUS"] + "_" + str(status)
        for stage in RUN_FIXTURE_STAGE_FAILURE_CODES.values()
        if "STATUS" in stage
        for status in RUN_FIXTURE_BACKEND_REJECTED_STATUSES
    ]
    + [
        "FIXTURE_OWNER_IDENTITY_INVALID",
        "FIXTURE_DIRECTORY_INVALID",
        "FIXTURE_DIRECTORY_NOT_EMPTY",
        "FIXTURE_MANIFEST_DIRECTORY_IO_FAILED",
        "FIXTURE_MANIFEST_FINAL_EXISTS",
        "FIXTURE_MANIFEST_SCHEMA_INVALID",
        "FIXTURE_MANIFEST_IDENTITY_INVALID",
        "FIXTURE_MANIFEST_BYTES_INVALID",
        "FIXTURE_MANIFEST_TEMP_CREATE_FAILED",
        "FIXTURE_MANIFEST_WRITE_FAILED",
        "FIXTURE_MANIFEST_RENAME_FAILED",
        "FIXTURE_MANIFEST_RENAME_UNAVAILABLE",
        "FIXTURE_MANIFEST_RENAME_DENIED",
        "FIXTURE_MANIFEST_RENAME_IO_FAILED",
        "FIXTURE_MANIFEST_LINK_DENIED",
        "FIXTURE_MANIFEST_LINK_FAILED",
        "FIXTURE_MANIFEST_TEMP_UNLINK_FAILED",
        "FIXTURE_MANIFEST_CARDINALITY_INVALID",
        "FIXTURE_MANIFEST_READ_FAILED",
        "FIXTURE_MANIFEST_FINAL_INVALID",
        "INPUT_INVALID",
        "UNEXPECTED_ERROR",
    ]
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
    "keycloak-run-fixture": {
        "transaction_service_client_secret",
        "behavior_service_client_secret",
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
    "keycloak-run-fixture",
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
    "keycloak-run-fixture": ["python", "-B", "/opt/finguardops/verify_e2e.py"],
}
EXPECTED_COMMANDS = {
    "keycloak": [],
    "keycloak-bootstrap": ["reconcile"],
    "keycloak-verify": ["runtime"],
    "keycloak-run-fixture": ["run-fixture"],
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
    "keycloak-run-fixture": {
        "/opt/finguardops/verify_e2e.py": ("bind", "infra/keycloak/verify_e2e.py", True),
        "/finguardops/fixture": ("bind", None, False),
    },
}


class VerificationError(RuntimeError):
    """Contains a fixed safe code only."""


def fail(code: str) -> None:
    raise VerificationError(code)


def validate_service_project(project: Any) -> str:
    if not isinstance(project, str) or SERVICE_PROJECT_PATTERN.fullmatch(project) is None:
        fail("HOST_ARGUMENT_INVALID")
    return project


def validate_run_fixture_project(project: Any) -> str:
    if not isinstance(project, str) or project != RUN_FIXTURE_PROJECT:
        fail("HOST_ARGUMENT_INVALID")
    return project


@dataclass(frozen=True)
class OwnerContract:
    backend_image: str
    ai_service_image: str
    commit_sha: str
    tree_sha: str
    run_id: str
    repository_id: str


def load_owner_contract(environment: dict[str, str]) -> OwnerContract:
    names = {
        "backend_image": "FINGUARDOPS_E2E_BACKEND_IMAGE",
        "ai_service_image": "FINGUARDOPS_E2E_AI_SERVICE_IMAGE",
        "commit_sha": "FINGUARDOPS_E2E_REVISION",
        "tree_sha": "FINGUARDOPS_E2E_SOURCE_TREE",
        "run_id": "FINGUARDOPS_E2E_RUN_ID",
        "repository_id": "FINGUARDOPS_E2E_REPOSITORY_ID",
    }
    try:
        values = {field_name: environment[name] for field_name, name in names.items()}
    except (KeyError, TypeError):
        fail("OWNER_CONTRACT_INVALID")
    object_id = r"(?:[0-9a-f]{40}|[0-9a-f]{64})"
    if (
        re.fullmatch(object_id, values["commit_sha"]) is None
        or re.fullmatch(object_id, values["tree_sha"]) is None
        or re.fullmatch(r"[0-9a-f]{32}", values["run_id"]) is None
        or re.fullmatch(r"[0-9a-f]{64}", values["repository_id"]) is None
    ):
        fail("OWNER_CONTRACT_INVALID")
    suffix = "e2e-" + values["commit_sha"][:12] + "-" + values["run_id"]
    if (
        values["backend_image"] != "finguardops-backend:" + suffix
        or values["ai_service_image"] != "finguardops-ai-service:" + suffix
    ):
        fail("OWNER_CONTRACT_INVALID")
    return OwnerContract(**values)


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


def source_matches(actual: Any, expected: str | None, mount_type: str) -> bool:
    if mount_type == "volume":
        return actual == expected
    path = normalized_path(actual)
    if expected is None:
        return (
            bool(path)
            and (path.startswith("/") or re.match(r"^[a-z]:/", path) is not None)
            and re.search(
                r"/finguardops-keycloak-e2e-fixture-[0-9a-f]{32}$", path
            )
            is not None
        )
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
    if {"keycloak", "keycloak-bootstrap", "keycloak-verify", "keycloak-run-fixture"}.intersection(dependencies):
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
    if not isinstance(users, list) or len(users) != 4:
        fail("STATIC_USER_CONTRACT")
    expected_users = (
        ("local-fds-analyst", "FDS_ANALYST", "Analyst", "32a6a5db-71e4-4e58-8b3f-ec8c2c07b69a"),
        ("local-fds-viewer", "FDS_VIEWER", "Viewer", "32a6a5db-71e4-4e58-8b3f-ec8c2c07b69b"),
        ("local-fds-approver", "FDS_APPROVER", "Approver", "32a6a5db-71e4-4e58-8b3f-ec8c2c07b69c"),
        ("local-platform-admin", "PLATFORM_ADMIN", "Admin", "32a6a5db-71e4-4e58-8b3f-ec8c2c07b69d"),
    )
    for user, (username, role, last_name, subject) in zip(users, expected_users, strict=True):
        if (
            not isinstance(user, dict)
            or user.get("id") != subject
            or user.get("username") != username
            or user.get("firstName") != "Local"
            or user.get("lastName") != last_name
            or user.get("email") != username + "@finguardops.invalid"
            or user.get("enabled") is not True
            or user.get("emailVerified") is not False
            or user.get("requiredActions") != []
            or user.get("credentials") != []
            or user.get("realmRoles") != [role]
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
    if not has_keycloak or not {
        "keycloak-bootstrap", "keycloak-verify", "keycloak-run-fixture"
    }.issubset(services):
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
    for name in ("keycloak-bootstrap", "keycloak-verify", "keycloak-run-fixture"):
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
    run_fixture = services["keycloak-run-fixture"]
    if (
        named_volume_sources(bootstrap)
        or named_volume_sources(verifier)
        or named_volume_sources(run_fixture)
    ):
        fail("STATIC_HELPER_NAMED_VOLUME")
    if mount_sources(bootstrap).intersection(mount_sources(verifier)):
        fail("STATIC_HELPER_SHARED_STORAGE")
    bootstrap_env = environment(bootstrap)
    verifier_env = environment(verifier)
    run_fixture_env = environment(run_fixture)
    if bootstrap_env.get("KEYCLOAK_ADMIN_BASE_URL") != INTERNAL_BASE_URL:
        fail("STATIC_ADMIN_BASE_URL")
    if (
        verifier_env.get("KEYCLOAK_INTERNAL_BASE_URL") != INTERNAL_BASE_URL
        or verifier_env.get("KEYCLOAK_MANAGEMENT_BASE_URL") != MANAGEMENT_BASE_URL
    ):
        fail("STATIC_VERIFIER_BASE_URL")
    if set(run_fixture_env) != {
        "PYTHONDONTWRITEBYTECODE",
        "KEYCLOAK_INTERNAL_BASE_URL",
        "KEYCLOAK_MANAGEMENT_BASE_URL",
        "FINGUARDOPS_E2E_RUN_ID",
        "FINGUARDOPS_E2E_REPOSITORY_ID",
        "FINGUARDOPS_E2E_REVISION",
        "FINGUARDOPS_E2E_SOURCE_TREE",
        COMPOSE_PROJECT_ENVIRONMENT,
        FIXTURE_PLAN_ENVIRONMENT,
    } or (
        run_fixture_env.get("KEYCLOAK_INTERNAL_BASE_URL") != INTERNAL_BASE_URL
        or run_fixture_env.get("KEYCLOAK_MANAGEMENT_BASE_URL") != MANAGEMENT_BASE_URL
    ):
        fail("STATIC_RUN_FIXTURE_ENVIRONMENT")
    fixture_identity_from_environment(run_fixture_env)
    if dependency_condition(keycloak, "backend") != "service_healthy":
        fail("STATIC_DEPENDENCY")
    if dependency_condition(services["keycloak-bootstrap"], "keycloak") != "service_healthy":
        fail("STATIC_DEPENDENCY")
    if dependency_condition(services["keycloak-verify"], "keycloak-bootstrap") != "service_completed_successfully":
        fail("STATIC_DEPENDENCY")
    if dependency_condition(services["keycloak-verify"], "external-risk-mock") != "service_healthy":
        fail("STATIC_DEPENDENCY")
    run_fixture_dependencies = services["keycloak-run-fixture"].get("depends_on")
    if (
        not isinstance(run_fixture_dependencies, dict)
        or set(run_fixture_dependencies) != {"backend"}
        or dependency_condition(services["keycloak-run-fixture"], "backend") != "service_started"
    ):
        fail("STATIC_DEPENDENCY")
    for service_name, expected in EXPECTED_SECRETS.items():
        validate_secret_mounts(services[service_name], expected)
    if realm is not None:
        validate_realm(realm)


def validate_owner_images(config: dict[str, Any], contract: OwnerContract) -> None:
    services = config.get("services")
    if not isinstance(services, dict):
        fail("OWNER_IMAGE_CONTRACT_INVALID")
    expected = {
        "backend": contract.backend_image,
        "ai-service": contract.ai_service_image,
        "external-risk-mock": contract.ai_service_image,
        "alertmanager-webhook": contract.ai_service_image,
    }
    if any(
        not isinstance(services.get(name), dict)
        or services[name].get("image") != image
        for name, image in expected.items()
    ):
        fail("OWNER_IMAGE_CONTRACT_INVALID")


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
    if exp <= iat:
        fail("TOKEN_TIME_ORDER_INVALID")
    if exp - iat > 900:
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

    transaction_token, behavior_token = service_tokens()

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


@dataclass(frozen=True)
class NativeCommandCapture:
    returncode: int | None
    stdout: bytes
    stderr: bytes
    stdout_overflow: bool = False
    stderr_overflow: bool = False
    start_failed: bool = False
    timed_out: bool = False
    cleanup_failed: bool = False
    output_limit_killed: bool = False


def capture_native_command(
    argv: list[str],
    *,
    timeout: float,
    cwd: Path,
    environment: dict[str, str],
    input_bytes: bytes | None = None,
    stdout_limit: int | None = None,
    stderr_limit: int | None = None,
    file_backed_output: bool = False,
) -> NativeCommandCapture:
    merged = os.environ.copy()
    merged.update(environment)
    merged.update({"MSYS_NO_PATHCONV": "1", "MSYS2_ARG_CONV_EXCL": "*"})
    if file_backed_output:
        # Docker Compose can exit while a descendant still holds its inherited
        # stdout/stderr pipe handles. Files let the direct command finish without
        # mistaking that pipe EOF delay for a failed process cleanup.
        if input_bytes is not None or stdout_limit is None or stderr_limit is None:
            raise ValueError("file-backed capture requires bounded output and no input")
        try:
            with tempfile.TemporaryFile() as stdout_file, tempfile.TemporaryFile() as stderr_file:
                try:
                    process = subprocess.Popen(
                        argv, stdin=subprocess.DEVNULL, stdout=stdout_file,
                        stderr=stderr_file, cwd=cwd, env=merged, shell=False,
                    )
                except OSError:
                    return NativeCommandCapture(None, b"", b"", start_failed=True)
                timed_out = False
                cleanup_failed = False
                output_limit_killed = False
                deadline = time.monotonic() + timeout
                try:
                    while True:
                        if (os.fstat(stdout_file.fileno()).st_size > stdout_limit or
                                os.fstat(stderr_file.fileno()).st_size > stderr_limit):
                            output_limit_killed = process.poll() is None
                            cleanup_failed = not reap_native_process(process)
                            break
                        remaining = deadline - time.monotonic()
                        if remaining <= 0:
                            timed_out = True
                            cleanup_failed = not reap_native_process(process)
                            break
                        try:
                            process.wait(timeout=min(0.05, remaining))
                            break
                        except subprocess.TimeoutExpired:
                            continue
                except BaseException:
                    if not reap_native_process(process):
                        return NativeCommandCapture(None, b"", b"", cleanup_failed=True)
                    raise
                stdout_file.seek(0)
                stderr_file.seek(0)
                stdout = stdout_file.read(stdout_limit + 1)
                stderr = stderr_file.read(stderr_limit + 1)
                return NativeCommandCapture(
                    process.returncode, stdout, stderr,
                    stdout_overflow=len(stdout) > stdout_limit,
                    stderr_overflow=len(stderr) > stderr_limit,
                    timed_out=timed_out, cleanup_failed=cleanup_failed,
                    output_limit_killed=output_limit_killed,
                )
        except (OSError, ValueError):
            # Includes temporary-file allocation, capture and close failures.
            return NativeCommandCapture(None, b"", b"", cleanup_failed=True)
    try:
        process = subprocess.Popen(
            argv,
            stdin=subprocess.PIPE if input_bytes is not None else subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=cwd,
            env=merged,
            shell=False,
        )
    except OSError:
        return NativeCommandCapture(None, b"", b"", start_failed=True)
    streams = {
        "stdout": {"bytes": bytearray(), "overflow": False, "failed": False},
        "stderr": {"bytes": bytearray(), "overflow": False, "failed": False},
    }

    def drain(name: str, pipe: Any, limit: int | None) -> None:
        state = streams[name]
        try:
            while True:
                chunk = pipe.read(65_536)
                if not chunk:
                    break
                if limit is None:
                    state["bytes"].extend(chunk)
                else:
                    remaining = limit + 1 - len(state["bytes"])
                    if remaining > 0:
                        state["bytes"].extend(chunk[:remaining])
                    if len(chunk) > remaining or len(state["bytes"]) > limit:
                        state["overflow"] = True
        except (OSError, ValueError):
            state["failed"] = True
        finally:
            try:
                pipe.close()
            except (OSError, ValueError):
                state["failed"] = True

    writer_failed = [False]

    def write_input() -> None:
        try:
            if input_bytes is not None and process.stdin is not None:
                process.stdin.write(input_bytes)
                process.stdin.flush()
        except BrokenPipeError:
            pass
        except (OSError, ValueError):
            writer_failed[0] = True
        finally:
            if process.stdin is not None:
                try:
                    process.stdin.close()
                except (OSError, ValueError):
                    writer_failed[0] = True

    threads = [
        threading.Thread(
            target=drain, args=("stdout", process.stdout, stdout_limit), daemon=True
        ),
        threading.Thread(
            target=drain, args=("stderr", process.stderr, stderr_limit), daemon=True
        ),
    ]
    if input_bytes is not None:
        threads.append(threading.Thread(target=write_input, daemon=True))
    for thread in threads:
        thread.start()
    timed_out = False
    cleanup_failed = False
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        cleanup_failed = not reap_native_process(process)
    except BaseException:
        cleanup_failed = not reap_native_process(process)
        for thread in threads:
            thread.join(10)
        if cleanup_failed:
            return NativeCommandCapture(None, b"", b"", cleanup_failed=True)
        raise
    for thread in threads:
        thread.join(10)
        if thread.is_alive():
            cleanup_failed = True
    cleanup_failed = cleanup_failed or writer_failed[0] or any(
        state["failed"] for state in streams.values()
    )
    return NativeCommandCapture(
        process.returncode,
        bytes(streams["stdout"]["bytes"]),
        bytes(streams["stderr"]["bytes"]),
        stdout_overflow=bool(streams["stdout"]["overflow"]),
        stderr_overflow=bool(streams["stderr"]["overflow"]),
        timed_out=timed_out,
        cleanup_failed=cleanup_failed,
    )


def reap_native_process(process: subprocess.Popen[Any]) -> bool:
    try:
        if process.poll() is None:
            process.kill()
        process.wait(timeout=10)
    except (OSError, subprocess.SubprocessError):
        # The target can exit between poll/kill/wait (notably on Windows).
        # A failed cleanup call is harmless only when the exact process is gone.
        try:
            return process.poll() is not None
        except (OSError, subprocess.SubprocessError):
            return False
    return True


def validate_before_native_output(stage: str, output: bytes) -> None:
    if stage == "RULE_PUBLICATION_ONEOFF_CHECK":
        if output and re.fullmatch(rb"[0-9a-f]{64}(?:\r?\n[0-9a-f]{64})*\r?\n?", output) is None:
            raise ValueError("invalid one-off inventory")
        return
    if stage in {"RULE_PUBLISHED_STATE", "RULE_ACTIVE_STATE", "RULE_ACTIVATION_POLL"}:
        if re.fullmatch(rb"[0-9]+\n?", output) is None:
            raise ValueError("invalid rule-state scalar")
        return
    if stage == "RULE_PUBLICATION_COMMAND":
        text = output.decode("utf-8", "strict")
        if (
            RULE_PUBLICATION_FAILURE_WIRE_PREFIX.casefold() in text.casefold()
            or rule_publication_approved_failure_codes(output, b"")
        ):
            raise ValueError("runner failure marker on successful exit")
        return
    if stage == "TRANSACTION_CARDINALITY_SNAPSHOT":
        if re.fullmatch(rb"[0-9]+(?:\|[0-9]+){13}\n?", output) is None:
            raise ValueError("invalid transaction cardinality")
        return
    if stage == "DATABASE_GLOBAL_SNAPSHOT":
        try:
            parse_database_snapshot(output)
        except VerificationError as error:
            raise ValueError("invalid global snapshot") from error
        return
    if stage in {"EXTERNAL_RISK_LOG_SNAPSHOT", "RULE_V2_LOG_SNAPSHOT"}:
        output.decode("utf-8", "strict")
        return
    if stage == "BACKEND_METRIC_SNAPSHOT":
        parsed = json.loads(output)
        if (
            not isinstance(parsed, list)
            or len(parsed) != 2
            or any(
                isinstance(value, bool)
                or not isinstance(value, (int, float))
                or not math.isfinite(float(value))
                for value in parsed
            )
        ):
            raise ValueError("invalid metric snapshot")
        return
    raise ValueError("unknown before stage")


def rule_publication_approved_failure_codes(
    stdout: bytes, stderr: bytes
) -> tuple[str, ...]:
    approved = {
        line: code
        for code, approved_lines in RULE_PUBLICATION_RUNNER_FAILURE_LINES.items()
        for line in approved_lines
    }
    matches: list[str] = []
    for stream in (stdout, stderr):
        text = stream.decode("utf-8", "strict")
        for raw_line in text.split("\n"):
            line = raw_line[:-1] if raw_line.endswith("\r") else raw_line
            if line in approved:
                matches.append(approved[line])
    return tuple(matches)


def rule_publication_runner_failure_matches(
    stdout: bytes, stderr: bytes
) -> tuple[str, ...]:
    lines: list[str] = []
    newline_styles: set[str] = set()
    for stream in (stdout, stderr):
        text = stream.decode("utf-8", "strict")
        if any(
            (ord(character) < 0x20 and character not in "\t\r\n")
            or 0x7F <= ord(character) <= 0x9F
            or unicodedata.category(character) == "Cf"
            for character in text
        ):
            return ()
        if "\r" in text:
            if re.search(r"\r(?!\n)", text):
                return ()
            newline_styles.add("crlf")
        if re.search(r"(?<!\r)\n", text):
            newline_styles.add("lf")
        if len(newline_styles) > 1:
            return ()
        for raw_line in text.split("\n"):
            lines.append(raw_line[:-1] if raw_line.endswith("\r") else raw_line)
    matches: list[str] = []
    approved = {
        line: code
        for code, approved_lines in RULE_PUBLICATION_RUNNER_FAILURE_LINES.items()
        for line in approved_lines
    }
    for line in lines:
        if "\t" in line:
            if (
                RULE_PUBLICATION_RUNNER_STACK_FRAME.fullmatch(line)
                or RULE_PUBLICATION_RUNNER_STACK_ELISION.fullmatch(line)
            ):
                continue
            return ()
        if RULE_PUBLICATION_RUNNER_SUCCESS_MARKER in line:
            return ()
        if line in approved:
            matches.append(approved[line])
            continue
        if line in RULE_PUBLICATION_RUNNER_NEUTRAL_LINES:
            continue
        if RULE_PUBLICATION_RUNNER_EXCEPTION_HEADLINE.fullmatch(line):
            return ()
    return tuple(matches)


def authoritative_rule_publication_failure_code(
    stdout: bytes, stderr: bytes
) -> tuple[bool, str | None]:
    decoded: list[tuple[str, str]] = []
    newline_styles: set[str] = set()
    marker_like = False
    invalid_marker = False
    markers: list[str] = []
    prefix_folded = RULE_PUBLICATION_FAILURE_WIRE_PREFIX.casefold()
    for stream_name, stream in (("stdout", stdout), ("stderr", stderr)):
        text = stream.decode("utf-8", "strict")
        decoded.append((stream_name, text))
        if any(
            (ord(character) < 0x20 and character not in "\t\r\n")
            or 0x7F <= ord(character) <= 0x9F
            or unicodedata.category(character) == "Cf"
            for character in text
        ):
            return True, None
        if "\r" in text:
            if re.search(r"\r(?!\n)", text):
                return True, None
            newline_styles.add("crlf")
        if re.search(r"(?<!\r)\n", text):
            newline_styles.add("lf")
        if len(newline_styles) > 1:
            return True, None
        raw_lines = text.split("\n")
        for line_index, raw_line in enumerate(raw_lines):
            line = raw_line[:-1] if raw_line.endswith("\r") else raw_line
            if prefix_folded not in line.casefold():
                continue
            marker_like = True
            if line_index == len(raw_lines) - 1 and not text.endswith("\n"):
                invalid_marker = True
                continue
            if any(
                ord(character) < 0x20
                or 0x7F <= ord(character) <= 0x9F
                or unicodedata.category(character) == "Cf"
                for character in line
            ):
                invalid_marker = True
                continue
            if stream_name != "stderr":
                invalid_marker = True
                continue
            code = RULE_PUBLICATION_AUTHORITATIVE_FAILURE_LINES.get(line)
            if code is None:
                invalid_marker = True
                continue
            markers.append(code)
    if not marker_like:
        return False, None
    if invalid_marker or len(markers) != 1:
        return True, None
    if any(
        RULE_PUBLICATION_RUNNER_SUCCESS_MARKER in text
        for _, text in decoded
    ):
        return True, None
    legacy = rule_publication_approved_failure_codes(stdout, stderr)
    if any(code != markers[0] for code in legacy):
        return True, None
    return True, markers[0]


def classify_rule_publication_nonzero(capture: NativeCommandCapture) -> str:
    fallback = BEFORE_NATIVE_FAILURE_CODES["RULE_PUBLICATION_COMMAND"]["exit"]
    if capture.stdout_overflow or capture.stderr_overflow:
        return fallback
    try:
        authoritative, code = authoritative_rule_publication_failure_code(
            capture.stdout, capture.stderr
        )
        if authoritative:
            return code if code is not None else fallback
        matches = rule_publication_runner_failure_matches(
            capture.stdout, capture.stderr
        )
    except UnicodeDecodeError:
        return fallback
    return matches[0] if len(matches) == 1 else fallback


def decode_strict_utf8(output: bytes) -> str:
    return output.decode("utf-8", "strict")


def semantic_text_lines(
    output: bytes,
    *,
    max_lines: int | None = None,
    max_line_length: int | None = None,
) -> tuple[str, tuple[str, ...]]:
    text = output.decode("utf-8", "strict")
    if text == "":
        return text, ()
    if not text.endswith("\n") or re.search(r"\r(?!\n)", text):
        raise ValueError("invalid semantic output newline")
    has_crlf = "\r\n" in text
    has_lf = re.search(r"(?<!\r)\n", text) is not None
    if has_crlf and has_lf:
        raise ValueError("mixed semantic output newlines")
    if any(
        (ord(character) < 0x20 and character not in "\r\n")
        or 0x7F <= ord(character) <= 0x9F
        or unicodedata.category(character) == "Cf"
        for character in text
    ):
        raise ValueError("invalid semantic output character")
    lines = tuple(
        raw_line[:-1] if raw_line.endswith("\r") else raw_line
        for raw_line in text[:-1].split("\n")
    )
    if max_lines is not None and len(lines) > max_lines:
        raise ValueError("too many semantic output lines")
    if max_line_length is not None and any(
        len(line) > max_line_length for line in lines
    ):
        raise ValueError("semantic output line is too long")
    return text, lines


# Names the first structural violation of a publication stdout capture using the
# fixed order of RULE_PUBLICATION_STDOUT_PREDICATE_CODES. It subdivides exactly
# the rules semantic_text_lines already enforces and accepts nothing new: a
# capture this returns None for is one semantic_text_lines also accepts. The
# return value is always a compile-time literal, never a rejected character,
# code point, line or any other part of the candidate.
def classify_semantic_stdout_violation(stdout: bytes) -> str | None:
    try:
        text = decode_strict_utf8(stdout)
    except UnicodeError:
        return RULE_PUBLICATION_STDOUT_ENCODING_INVALID
    if text == "":
        return None
    if not text.endswith("\n"):
        return RULE_PUBLICATION_STDOUT_FINAL_NEWLINE_INVALID
    if re.search(r"\r(?!\n)", text) is not None:
        return RULE_PUBLICATION_STDOUT_BARE_CR_INVALID
    if "\r\n" in text and re.search(r"(?<!\r)\n", text) is not None:
        return RULE_PUBLICATION_STDOUT_MIXED_NEWLINE_INVALID
    if "\x00" in text:
        return RULE_PUBLICATION_STDOUT_NUL_INVALID
    if "\t" in text:
        return RULE_PUBLICATION_STDOUT_TAB_INVALID
    if "\x1b" in text:
        return RULE_PUBLICATION_STDOUT_ESCAPE_INVALID
    if any(
        ord(character) < 0x20 and character not in "\t\n\r\x1b"
        for character in text
    ):
        return RULE_PUBLICATION_STDOUT_C0_INVALID
    if any(0x7F <= ord(character) <= 0x9F for character in text):
        return RULE_PUBLICATION_STDOUT_C1_INVALID
    if any(unicodedata.category(character) == "Cf" for character in text):
        return RULE_PUBLICATION_STDOUT_FORMAT_INVALID
    return None


def validate_compose_run_stderr(stderr: bytes) -> tuple[str, ...]:
    _, lines = semantic_text_lines(
        stderr,
        max_lines=SEMANTIC_STDERR_MAX_LINES,
        max_line_length=SEMANTIC_STDERR_MAX_LINE_LENGTH,
    )
    return lines


def has_rule_publication_failure_evidence(stdout: bytes, stderr: bytes) -> bool:
    prefix = RULE_PUBLICATION_FAILURE_WIRE_PREFIX.casefold()
    decoded = tuple(stream.decode("utf-8", "strict") for stream in (stdout, stderr))
    if any(prefix in text.casefold() for text in decoded):
        authoritative, _ = authoritative_rule_publication_failure_code(stdout, stderr)
        if authoritative:
            return True
    if rule_publication_approved_failure_codes(stdout, stderr):
        return True
    for text in decoded:
        lines = tuple(
            raw_line[:-1] if raw_line.endswith("\r") else raw_line
            for raw_line in text.split("\n")
        )
        if any(
            RULE_PUBLICATION_RUNNER_EXCEPTION_HEADLINE.fullmatch(line)
            or RULE_PUBLICATION_RUNNER_STACK_FRAME.fullmatch(line)
            for line in lines
        ):
            return True
    return False


# The publication stage owns four fixed identities, one per validation step, so
# that a successful exit which still fails output validation says which step
# rejected it. Only compile-time literals from
# RULE_PUBLICATION_SEMANTIC_FAILURE_CODES travel outwards; every other
# rejection keeps the existing OUTPUT_INVALID fallback, and no candidate byte,
# line, path or exception text is ever reflected.
# A factory rather than a raiser: every caller spells `raise`, so there is no
# shape in which a step failure can fall through as a falsy return value.
def semantic_failure(publication: bool, code: str) -> ValueError:
    if publication:
        for approved in RULE_PUBLICATION_SEMANTIC_FAILURE_CODES:
            if code == approved:
                return ValueError(approved)
    return ValueError("semantic compose run output rejected")


def run_semantic_step(publication: bool, code: str, step, *arguments):
    try:
        return step(*arguments)
    except (UnicodeError, ValueError) as error:
        raise semantic_failure(publication, code) from error


def semantic_output_failure_code(stage: str, error: BaseException) -> str:
    fallback = BEFORE_NATIVE_FAILURE_CODES[stage]["output"]
    if stage != "RULE_PUBLICATION_COMMAND" or type(error) is not ValueError:
        return fallback
    if len(error.args) != 1 or not isinstance(error.args[0], str):
        return fallback
    for approved in RULE_PUBLICATION_SEMANTIC_FAILURE_CODES:
        if error.args[0] == approved:
            return approved
    return fallback


def validate_semantic_compose_run_output(
    stage: str, stdout: bytes, stderr: bytes
) -> None:
    if stage == "RULE_PUBLICATION_COMMAND":
        publication = True
    elif stage == "BACKEND_METRIC_SNAPSHOT":
        publication = False
    else:
        raise ValueError("unknown semantic stderr stage")
    # Each stream is decoded first, so an undecodable stream is named by its own
    # identity. Failure evidence is judged next, before the structural line rules,
    # because an exception headline or stack frame is evidence rather than a shape
    # violation. Every check HEAD performed still runs, in a superset.
    run_semantic_step(
        publication, RULE_PUBLICATION_STDERR_INVALID, decode_strict_utf8, stderr
    )
    run_semantic_step(
        publication,
        RULE_PUBLICATION_STDOUT_ENCODING_INVALID,
        decode_strict_utf8,
        stdout,
    )
    if run_semantic_step(
        publication,
        RULE_PUBLICATION_FAILURE_EVIDENCE_INVALID,
        has_rule_publication_failure_evidence,
        stdout,
        stderr,
    ):
        raise semantic_failure(
            publication, RULE_PUBLICATION_FAILURE_EVIDENCE_INVALID
        )
    stderr_lines = run_semantic_step(
        publication,
        RULE_PUBLICATION_STDERR_INVALID,
        validate_compose_run_stderr,
        stderr,
    )
    if publication:
        # The named predicate runs first so a rejection says which rule broke.
        # semantic_text_lines still runs afterwards as the final authority, and
        # anything it rejects that the classifier did not name keeps the existing
        # RULE_PUBLICATION_COMMAND_STDOUT_INVALID fail-closed fallback.
        stdout_violation = classify_semantic_stdout_violation(stdout)
        if stdout_violation is not None:
            raise semantic_failure(publication, stdout_violation)
        stdout_text, stdout_lines = run_semantic_step(
            publication,
            RULE_PUBLICATION_STDOUT_INVALID,
            semantic_text_lines,
            stdout,
        )
        marker_count = stdout_text.count(RULE_PUBLICATION_RUNNER_SUCCESS_MARKER)
        marker_count += sum(
            line.count(RULE_PUBLICATION_RUNNER_SUCCESS_MARKER)
            for line in stderr_lines
        )
        matching_lines = tuple(
            line for line in stdout_lines
            if RULE_PUBLICATION_RUNNER_SUCCESS_MARKER in line
        )
        if (
            marker_count != 1
            or len(matching_lines) != 1
            or RULE_PUBLICATION_RUNNER_SUCCESS_LOG_LINE.fullmatch(
                matching_lines[0]
            ) is None
        ):
            raise semantic_failure(
                publication, RULE_PUBLICATION_SUCCESS_MARKER_INVALID
            )
    run_semantic_step(
        publication,
        RULE_PUBLICATION_FAILURE_EVIDENCE_INVALID,
        validate_before_native_output,
        stage,
        stdout,
    )


def run_command(
    argv: list[str],
    *,
    timeout: float,
    cwd: Path,
    environment: dict[str, str],
    input_bytes: bytes | None = None,
    before_stage: str | None = None,
) -> bytes:
    if timeout <= 0:
        fail("SUBPROCESS_TIMEOUT_INVALID")
    limits = BEFORE_NATIVE_OUTPUT_LIMITS.get(before_stage) if before_stage is not None else None
    if before_stage is not None and limits is None:
        fail("SUBPROCESS_FAILED")
    capture = capture_native_command(
        argv,
        timeout=timeout,
        cwd=cwd,
        environment=environment,
        input_bytes=input_bytes,
        stdout_limit=limits[0] if limits is not None else None,
        stderr_limit=limits[1] if limits is not None else None,
        file_backed_output=before_stage == "RULE_PUBLICATION_COMMAND",
    )
    if before_stage is not None:
        codes = BEFORE_NATIVE_FAILURE_CODES[before_stage]
        if capture.cleanup_failed:
            fail(codes["cleanup"])
        if capture.start_failed:
            fail(codes["start"])
        if capture.timed_out:
            fail(codes["timeout"])
        if before_stage == "RULE_PUBLICATION_COMMAND" and capture.output_limit_killed:
            fail(codes["output"])
        if capture.returncode != 0:
            if before_stage == "RULE_PUBLICATION_COMMAND":
                fail(classify_rule_publication_nonzero(capture))
            fail(codes["exit"])
        if capture.stdout_overflow or capture.stderr_overflow:
            fail(codes["output"])
        try:
            if before_stage in SEMANTIC_STDERR_STAGES:
                validate_semantic_compose_run_output(
                    before_stage, capture.stdout, capture.stderr
                )
            else:
                if capture.stderr:
                    fail(codes["output"])
                validate_before_native_output(before_stage, capture.stdout)
        except (UnicodeError, json.JSONDecodeError, ValueError) as error:
            fail(semantic_output_failure_code(before_stage, error))
        return capture.stdout
    if capture.cleanup_failed or capture.start_failed or capture.timed_out:
        fail("SUBPROCESS_FAILED")
    if capture.returncode != 0:
        safe_child = re.search(
            rb"(?:^|\n)verification failed: ([A-Z][A-Z0-9_]{0,63})(?:\r?\n|$)",
            capture.stderr,
        )
        if safe_child is not None:
            fail("CHILD_" + safe_child.group(1).decode("ascii"))
        fail("SUBPROCESS_FAILED")
    return capture.stdout


class HostContext:
    def __init__(
        self,
        repo: Path,
        project: str,
        cli_timeout: float,
        deadline_seconds: float,
        contract: OwnerContract,
    ) -> None:
        self.repo = repo.resolve()
        self.project = project
        self.cli_timeout = cli_timeout
        self.contract = contract
        self.deadline = time.monotonic() + deadline_seconds
        self.compose = [
            "docker",
            "compose",
            "-p",
            project,
            "--env-file",
            str(self.repo / "infra" / ".env.example"),
            "-f",
            str(self.repo / "infra" / "compose.yml"),
            "-f",
            str(self.repo / "infra" / "compose.keycloak-local-e2e.yml"),
        ]
        self.environment = {
            "FINGUARDOPS_E2E_BACKEND_IMAGE": contract.backend_image,
            "FINGUARDOPS_E2E_AI_SERVICE_IMAGE": contract.ai_service_image,
            "FINGUARDOPS_E2E_REVISION": contract.commit_sha,
            "FINGUARDOPS_E2E_SOURCE_TREE": contract.tree_sha,
            "FINGUARDOPS_E2E_RUN_ID": contract.run_id,
            "FINGUARDOPS_E2E_REPOSITORY_ID": contract.repository_id,
            COMPOSE_PROJECT_ENVIRONMENT: RUN_FIXTURE_PROJECT,
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
        before_stage: str | None = None,
    ) -> bytes:
        limit = self.cli_timeout if timeout is None else timeout
        return run_command(
            self.compose + arguments,
            timeout=min(limit, self.remaining()),
            cwd=self.repo,
            environment=self.environment,
            input_bytes=input_bytes,
            before_stage=before_stage,
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


def sql_scalar(ctx: HostContext, query: str, before_stage: str | None = None) -> str:
    output = ctx.execute(
        [
            "exec", "-T", "postgresql", "psql", "-X", "-v", "ON_ERROR_STOP=1",
            "-U", "finguardops", "-d", "finguardops", "-tAc", query,
        ],
        before_stage=before_stage,
    )
    return output.decode("utf-8", "strict").strip()


# The publication one-shot runs with the Spring Boot default root level, so
# Hibernate's org.hibernate.orm.connections.pooling logger emits HHH10001005
# ("Database info:") at INFO, and DatabaseConnectionInfoImpl.toInfoString()
# renders its seven continuation lines with a leading TAB. That is ordinary
# healthy output, not failure evidence, but the publication stdout contract
# rejects TAB because Java stack frames also start with one. The producer is
# silenced for that single logger only: every WARN and ERROR on it stays
# visible, the TAB rule and the stack-frame backstop are unchanged, and the
# property binds at run time so no backend image rebuild is needed.
def rule_publication_arguments(effective: str) -> list[str]:
    return [
        "run", "--rm", "--no-deps", "--pull", "never", "-T",
        "-e", "SPRING_PROFILES_ACTIVE=local,rule-v1-default-publication",
        "-e", "FINGUARDOPS_EXTERNAL_RISK_HTTP_ENABLED=false",
        "backend", "--spring.main.web-application-type=none",
        "--logging.level.org.hibernate.orm.connections.pooling=WARN",
        "--finguardops.rule-v1-default-publication.enabled=true",
        "--finguardops.rule-v1-default-publication.confirmation=PUBLISH_RULE_V1_DEFAULT_V1",
        "--finguardops.rule-v1-default-publication.effective-from=" + effective,
    ]


def publish_rules(
    ctx: HostContext, *, before_diagnostics: bool = False,
    verify_oneoff_lifetime: bool = False,
) -> None:
    identifiers = ",".join("'%s'" % item for item in RULE_VERSION_IDS)
    published_query = (
        "select count(*) from rule_version where status='PUBLISHED' "
        "and rule_version_id in (" + identifiers + ")"
    )
    active_query = published_query + " and effective_from <= current_timestamp"
    published = sql_scalar(
        ctx, published_query,
        "RULE_PUBLISHED_STATE" if before_diagnostics else None,
    )
    active = sql_scalar(
        ctx, active_query,
        "RULE_ACTIVE_STATE" if before_diagnostics else None,
    )
    if published == "4" and active == "4":
        return
    if published != "0":
        fail("RULE_PUBLICATION_STATE_INVALID")
    effective = (
        dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=60)
    ).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    ctx.execute(
        rule_publication_arguments(effective),
        timeout=240,
        before_stage="RULE_PUBLICATION_COMMAND" if before_diagnostics else None,
    )
    # Compose --rm must have removed its publication one-off before a successful
    # command is allowed to advance. A failed command keeps its stage-specific
    # failure; the receipt-owned host cleanup handles any surviving container.
    if verify_oneoff_lifetime:
        remaining = run_command([
            "docker", "ps", "-aq", "--no-trunc",
            "--filter", "label=com.docker.compose.project=" + ctx.project,
            "--filter", "label=com.docker.compose.service=backend",
            "--filter", "label=com.docker.compose.oneoff=True",
        ], timeout=min(ctx.cli_timeout, ctx.remaining()), cwd=ctx.repo,
            environment=ctx.environment, before_stage="RULE_PUBLICATION_ONEOFF_CHECK")
        if remaining.strip():
            fail("RULE_PUBLICATION_ONEOFF_REMAINS")
    for _ in range(90):
        if sql_scalar(
            ctx, active_query,
            "RULE_ACTIVATION_POLL" if before_diagnostics else None,
        ) == "4":
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


def fixture_identity_from_environment(environment: dict[str, str]) -> dict[str, Any]:
    names = (
        ("runId", "FINGUARDOPS_E2E_RUN_ID", r"[0-9a-f]{32}"),
        ("repositoryId", "FINGUARDOPS_E2E_REPOSITORY_ID", r"[0-9a-f]{64}"),
        ("commitSha", "FINGUARDOPS_E2E_REVISION", r"(?:[0-9a-f]{40}|[0-9a-f]{64})"),
        ("treeSha", "FINGUARDOPS_E2E_SOURCE_TREE", r"(?:[0-9a-f]{40}|[0-9a-f]{64})"),
    )
    identity: dict[str, Any] = {"schemaVersion": 1}
    for key, variable, pattern in names:
        value = environment.get(variable)
        if not isinstance(value, str) or re.fullmatch(pattern, value) is None:
            fail("FIXTURE_OWNER_IDENTITY_INVALID")
        identity[key] = value
    project = environment.get(COMPOSE_PROJECT_ENVIRONMENT)
    if not isinstance(project, str) or project != RUN_FIXTURE_PROJECT:
        fail("FIXTURE_OWNER_IDENTITY_INVALID")
    identity["composeProject"] = RUN_FIXTURE_PROJECT
    return identity


def validate_fixture_manifest_object(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict) or tuple(raw) != FIXTURE_MANIFEST_KEYS:
        fail("FIXTURE_MANIFEST_SCHEMA_INVALID")
    if type(raw.get("schemaVersion")) is not int or raw.get("schemaVersion") != 1:
        fail("FIXTURE_MANIFEST_SCHEMA_INVALID")
    for key in FIXTURE_MANIFEST_KEYS[1:]:
        value = raw.get(key)
        if not isinstance(value, str) or not value:
            fail("FIXTURE_MANIFEST_SCHEMA_INVALID")
        if any(ord(character) <= 0x1F or 0x7F <= ord(character) <= 0x9F
               or unicodedata.category(character) == "Cf" for character in value):
            fail("FIXTURE_MANIFEST_SCHEMA_INVALID")
    if (
        re.fullmatch(r"[0-9a-f]{32}", raw["runId"]) is None
        or re.fullmatch(r"[0-9a-f]{64}", raw["repositoryId"]) is None
        or re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", raw["commitSha"]) is None
        or re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", raw["treeSha"]) is None
        or raw["composeProject"] != RUN_FIXTURE_PROJECT
        or not is_canonical_uuid4(raw["transactionId"])
        or not is_canonical_uuid4(raw["caseId"])
        or raw["expectedRiskLevel"] != "HIGH"
        or raw["expectedResponseOutcome"] != "ADDITIONAL_AUTH_REQUIRED"
        or raw["expectedInitialCaseStatus"] != "OPEN"
    ):
        fail("FIXTURE_MANIFEST_IDENTITY_INVALID")
    return raw


def fixture_manifest_bytes(identity: dict[str, Any]) -> bytes:
    valid = validate_fixture_manifest_object(identity)
    encoded = (json.dumps(valid, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")
    if len(encoded) > 1024 or encoded.startswith(b"\xef\xbb\xbf") or b"\r" in encoded:
        fail("FIXTURE_MANIFEST_BYTES_INVALID")
    return encoded


def parse_fixture_manifest_bytes(raw: bytes) -> dict[str, Any]:
    if len(raw) > 1024 or not raw.endswith(b"\n") or raw.endswith(b"\n\n") or b"\r" in raw:
        fail("FIXTURE_MANIFEST_BYTES_INVALID")
    try:
        text = raw.decode("utf-8", "strict")
        pairs = json.loads(text, object_pairs_hook=lambda values: values)
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail("FIXTURE_MANIFEST_BYTES_INVALID")
    if not isinstance(pairs, list) or any(
        not isinstance(pair, tuple) or len(pair) != 2 for pair in pairs
    ):
        fail("FIXTURE_MANIFEST_SCHEMA_INVALID")
    keys = tuple(pair[0] for pair in pairs)
    if keys != FIXTURE_MANIFEST_KEYS or len(set(keys)) != len(keys):
        fail("FIXTURE_MANIFEST_SCHEMA_INVALID")
    identity = validate_fixture_manifest_object(dict(pairs))
    if fixture_manifest_bytes(identity) != raw:
        fail("FIXTURE_MANIFEST_BYTES_INVALID")
    return identity


# The only errno values that mean "this filesystem or kernel cannot do a
# no-replace rename", as renameat2(2) documents them: EINVAL when the filesystem
# does not support a flag that was passed, ENOSYS when the kernel has no such
# call, and EOPNOTSUPP/ENOTSUP, which some filesystems answer instead. The call
# below passes one flag and two names in the same directory, so EINVAL has no
# other documented cause here. Nothing outside this set reaches the link
# fallback: an existing final name, a refusal and an I/O failure are answers
# about this file, not about whether the operation exists.
RENAME_NOREPLACE_UNSUPPORTED_ERRNOS = frozenset(
    {errno.EINVAL, errno.ENOSYS, errno.EOPNOTSUPP, errno.ENOTSUP}
)
MANIFEST_PUBLISH_DENIED_ERRNOS = frozenset({errno.EACCES, errno.EPERM, errno.EROFS})
MANIFEST_PUBLISH_IO_ERRNOS = frozenset({errno.EIO, errno.ENOSPC, errno.EDQUOT})


def link_noreplace(source: Path, destination: Path) -> None:
    # Publication without renameat2: create the final name as a second link to
    # the temporary file, then remove the temporary name. `link` never replaces
    # an existing name, so the final name appears atomically and only if it was
    # absent. The two steps together are not one atomic rename: between them
    # both names exist, and a failure of the second step is a failure.
    try:
        os.link(source, destination)
    except FileExistsError:
        fail("FIXTURE_MANIFEST_FINAL_EXISTS")
    except OSError as error:
        if error.errno in MANIFEST_PUBLISH_DENIED_ERRNOS:
            fail("FIXTURE_MANIFEST_LINK_DENIED")
        fail("FIXTURE_MANIFEST_LINK_FAILED")
    try:
        os.unlink(source)
    except OSError:
        # The final name was created a moment ago by the link above. It is
        # withdrawn only while it still is that same file, so a name that is no
        # longer this run's link is never removed. The caller's own cleanup
        # then deals with the temporary name.
        try:
            if os.path.samestat(os.lstat(source), os.lstat(destination)):
                os.unlink(destination)
        except OSError:
            pass
        fail("FIXTURE_MANIFEST_TEMP_UNLINK_FAILED")


def rename_noreplace_posix(source: Path, destination: Path) -> None:
    try:
        libc = ctypes.CDLL(None, use_errno=True)
        renameat2 = libc.renameat2
        renameat2.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        renameat2.restype = ctypes.c_int
        result = renameat2(
            -100, os.fsencode(source), -100, os.fsencode(destination), 1
        )
    except (AttributeError, OSError):
        fail("FIXTURE_MANIFEST_RENAME_UNAVAILABLE")
    if result == 0:
        return
    error_number = ctypes.get_errno()
    if error_number == errno.EEXIST:
        fail("FIXTURE_MANIFEST_FINAL_EXISTS")
    if error_number in RENAME_NOREPLACE_UNSUPPORTED_ERRNOS:
        link_noreplace(source, destination)
        return
    if error_number in MANIFEST_PUBLISH_DENIED_ERRNOS:
        fail("FIXTURE_MANIFEST_RENAME_DENIED")
    if error_number in MANIFEST_PUBLISH_IO_ERRNOS:
        fail("FIXTURE_MANIFEST_RENAME_IO_FAILED")
    fail("FIXTURE_MANIFEST_RENAME_FAILED")


def rename_noreplace(source: Path, destination: Path) -> None:
    if os.name == "nt":
        try:
            os.rename(source, destination)
        except OSError:
            fail("FIXTURE_MANIFEST_RENAME_FAILED")
        return
    rename_noreplace_posix(source, destination)


def write_fixture_manifest(directory: Path, identity: dict[str, Any]) -> Path:
    final = directory / FIXTURE_MANIFEST_NAME
    temporary = directory / (FIXTURE_MANIFEST_NAME + ".tmp")
    created_temporary = False
    try:
        try:
            if directory.is_symlink() or not directory.is_dir():
                fail("FIXTURE_DIRECTORY_INVALID")
            if final.exists() or final.is_symlink():
                fail("FIXTURE_MANIFEST_FINAL_EXISTS")
            if tuple(directory.iterdir()):
                fail("FIXTURE_DIRECTORY_NOT_EMPTY")
        except OSError:
            fail("FIXTURE_MANIFEST_DIRECTORY_IO_FAILED")
        canonical = fixture_manifest_bytes(identity)
        try:
            descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            created_temporary = True
        except OSError:
            fail("FIXTURE_MANIFEST_TEMP_CREATE_FAILED")
        try:
            with os.fdopen(descriptor, "wb", closefd=True) as stream:
                stream.write(canonical)
                stream.flush()
                os.fsync(stream.fileno())
        except OSError:
            fail("FIXTURE_MANIFEST_WRITE_FAILED")
        rename_noreplace(temporary, final)
        created_temporary = False
        try:
            cardinality_invalid = temporary.exists() or tuple(
                path.name for path in directory.iterdir()
            ) != (FIXTURE_MANIFEST_NAME,)
        except OSError:
            fail("FIXTURE_MANIFEST_DIRECTORY_IO_FAILED")
        if cardinality_invalid:
            fail("FIXTURE_MANIFEST_CARDINALITY_INVALID")
        try:
            observed = final.read_bytes()
        except OSError:
            fail("FIXTURE_MANIFEST_READ_FAILED")
        if observed != canonical or parse_fixture_manifest_bytes(observed) != identity:
            fail("FIXTURE_MANIFEST_FINAL_INVALID")
        return final
    except VerificationError:
        if created_temporary:
            try:
                if temporary.is_file() and not temporary.is_symlink():
                    temporary.unlink()
            except OSError:
                pass
        raise


def service_tokens(
    stage: Callable[[str, Callable[[], Any]], Any] | None = None,
) -> tuple[str, str]:
    # `stage` is how the run-fixture worker names each call. Every other caller
    # passes nothing, and then each call below runs exactly as it always did.
    def staged(name: str, call: Callable[[], Any]) -> Any:
        return call() if stage is None else stage(name, call)

    transaction_secret = read_secret(TRANSACTION_SECRET)
    behavior_secret = read_secret(BEHAVIOR_SECRET)
    if transaction_secret == behavior_secret:
        fail("SERVICE_SECRETS_NOT_DISTINCT")
    transaction_token = staged(
        "TRANSACTION_TOKEN",
        lambda: token_for("finguardops-transaction-ingestor", transaction_secret),
    )
    behavior_token = staged(
        "BEHAVIOR_TOKEN",
        lambda: token_for("finguardops-behavior-ingestor", behavior_secret),
    )
    staged(
        "JWKS",
        lambda: validate_actual_service_tokens(transaction_token, behavior_token),
    )
    staged(
        "CROSS_SECRET",
        lambda: assert_cross_secret_rejected("finguardops-transaction-ingestor", behavior_secret),
    )
    staged(
        "CROSS_SECRET",
        lambda: assert_cross_secret_rejected("finguardops-behavior-ingestor", transaction_secret),
    )
    return transaction_token, behavior_token


def run_fixture_stage(stage: str, call: Callable[[], Any]) -> Any:
    # One run-fixture HTTP call, failing as its own stage. Only the three shared
    # HTTP identities are rewritten; every other identity the call raises is
    # already specific and passes through untouched. A response whose body
    # cannot be read - the error body of an HTTP error included - never reached
    # a fixed identity before and ended as a generic input failure.
    codes = RUN_FIXTURE_STAGE_FAILURE_CODES[stage]
    try:
        return call()
    except VerificationError as error:
        code = str(error)
        if code not in RUN_FIXTURE_GENERIC_HTTP_FAILURES or code not in codes:
            raise
        fail(codes[code])
    except (OSError, http.client.HTTPException):
        fail(codes["RESPONSE_READ"])


def create_run_fixture(plan: dict[str, str]) -> dict[str, str]:
    valid = validate_plan(plan)
    transaction_token, behavior_token = service_tokens(run_fixture_stage)
    transaction, password_event, transfer_limit_event = ingestion_payloads(valid)
    for payload, identifier, stage in (
        (password_event, valid["passwordEventId"], "PASSWORD_EVENT"),
        (transfer_limit_event, valid["transferLimitEventId"], "TRANSFER_LIMIT_EVENT"),
    ):
        codes = RUN_FIXTURE_STAGE_FAILURE_CODES[stage]
        response = run_fixture_stage(
            stage,
            lambda: request_backend(
                "/api/v1/behavior-events", payload, 201, token=behavior_token,
                failure_code=codes["STATUS"],
            ),
        )
        if response.get("eventId") != identifier:
            fail(codes["RESPONSE_INVALID"])
    response = run_fixture_stage(
        "TRANSACTION",
        lambda: request_backend(
            "/api/v1/transactions", transaction, 201, token=transaction_token,
            idempotency_key=valid["idempotencyKey"],
            failure_code=RUN_FIXTURE_STAGE_FAILURE_CODES["TRANSACTION"]["STATUS"],
        ),
    )
    if (
        set(response) != {
            "transactionId", "processingStatus", "riskLevel",
            "riskResponseOutcome", "adoptedDetectionResultId", "caseId",
            "createdAt", "traceId",
        }
        or response.get("transactionId") != valid["transactionId"]
        or response.get("processingStatus") != "ADDITIONAL_AUTH_REQUIRED"
        or response.get("riskLevel") != "HIGH"
        or response.get("riskResponseOutcome") != "ADDITIONAL_AUTH_REQUIRED"
        or not is_canonical_uuid4(response.get("adoptedDetectionResultId"))
        or not is_canonical_uuid4(response.get("caseId"))
        or not isinstance(response.get("createdAt"), str)
        or not response.get("createdAt")
        or not isinstance(response.get("traceId"), str)
        or not response.get("traceId")
    ):
        fail("RUN_FIXTURE_TRANSACTION_RESPONSE_INVALID")
    return {
        "transactionId": valid["transactionId"],
        "caseId": response["caseId"],
    }


def run_fixture_worker() -> None:
    validate_run_fixture_project(os.environ.get(COMPOSE_PROJECT_ENVIRONMENT))
    encoded = os.environ.get(FIXTURE_PLAN_ENVIRONMENT)
    if (
        not isinstance(encoded, str)
        or len(encoded) > 10_924
        or re.fullmatch(r"[A-Za-z0-9+/]+={0,2}", encoded) is None
    ):
        fail("RUN_FIXTURE_PLAN_INVALID")
    try:
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) > 8192 or raw.startswith(b"\xef\xbb\xbf") or b"\r" in raw:
            fail("RUN_FIXTURE_PLAN_INVALID")
        plan = validate_plan(json.loads(raw.decode("utf-8", "strict")))
        if json.dumps(plan, separators=(",", ":")).encode("utf-8") != raw:
            fail("RUN_FIXTURE_PLAN_INVALID")
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
        fail("RUN_FIXTURE_PLAN_INVALID")
    response_identity = create_run_fixture(plan)
    identity = fixture_identity_from_environment(dict(os.environ))
    identity.update(response_identity)
    identity.update({
        "expectedRiskLevel": "HIGH",
        "expectedResponseOutcome": "ADDITIONAL_AUTH_REQUIRED",
        "expectedInitialCaseStatus": "OPEN",
    })
    write_fixture_manifest(FIXTURE_MANIFEST_DIRECTORY, identity)
    print("run fixture completed: risk=HIGH outcome=ADDITIONAL_AUTH_REQUIRED case=OPEN")


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


def database_snapshot(
    ctx: HostContext, before_stage: str | None = None
) -> dict[str, TableSnapshot]:
    output = ctx.execute(
        [
            "exec", "-T", "postgresql", "psql", "-X", "-qAt",
            "-v", "ON_ERROR_STOP=1", "-U", "finguardops", "-d", "finguardops",
            "-f", "-",
        ],
        input_bytes=snapshot_sql(),
        before_stage=before_stage,
    )
    return parse_database_snapshot(output)


def transaction_cardinality(
    ctx: HostContext, plan: dict[str, str], before_stage: str | None = None
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
    raw = sql_scalar(ctx, query, before_stage).split("|")
    if len(raw) != 14 or any(re.fullmatch(r"\d+", value) is None for value in raw):
        fail("DATABASE_TRANSACTION_SNAPSHOT_INVALID")
    return tuple(int(value) for value in raw)


def transaction_case_id(ctx: HostContext, plan: dict[str, str]) -> str:
    transaction_id = validate_plan(plan)["transactionId"]
    raw = sql_scalar(
        ctx,
        "select c.case_id from fraud_case c "
        "join case_transaction ct on ct.fraud_case_id=c.id "
        "join financial_transaction f on f.id=ct.financial_transaction_id "
        "where f.transaction_id='%s' and c.case_status='OPEN'" % transaction_id,
    )
    if not is_canonical_uuid4(raw):
        fail("DATABASE_CASE_IDENTITY_INVALID")
    return raw


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
        3 * transaction,
        transaction,
        transaction,
        4 * transaction,
        transaction,
        transaction,
        transaction,
        transaction,
    )


def service_logs(
    ctx: HostContext, service: str, before_stage: str | None = None
) -> str:
    if service not in {"external-risk-mock", "ai-service"}:
        fail("DEPENDENCY_SERVICE_INVALID")
    return ctx.execute(
        ["logs", "--no-color", "--no-log-prefix", service],
        before_stage=before_stage,
    ).decode("utf-8", "strict")


def dependency_hit_counts(
    ctx: HostContext, before_stages: tuple[str, str] | None = None
) -> tuple[int, int]:
    external_stage, rule_stage = before_stages or (None, None)
    external_lines = service_logs(
        ctx, "external-risk-mock", external_stage
    ).splitlines()
    rule_lines = service_logs(ctx, "ai-service", rule_stage).splitlines()
    external = sum(line == EXTERNAL_RISK_MARKER for line in external_lines)
    rule = sum(RULE_V2_ACCESS_PATTERN.fullmatch(line) is not None for line in rule_lines)
    return external, rule


def backend_metric_totals(
    ctx: HostContext, before_stage: str | None = None
) -> tuple[float, float]:
    output = ctx.execute(
        ["run", "--rm", "--no-deps", "--pull", "never", "-T", "keycloak-verify", "metric-runtime"],
        timeout=60,
        before_stage=before_stage,
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
            "run", "--rm", "--no-deps", "--pull", "never", "-T", "keycloak-verify",
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
                "detection_evidence": 3,
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


def run_fixture_state_bytes(state: dict[str, Any]) -> bytes:
    if tuple(state) != RUN_FIXTURE_STATE_KEYS:
        fail("RUN_FIXTURE_STATE_INVALID")
    if type(state["schemaVersion"]) is not int or state["schemaVersion"] != 1:
        fail("RUN_FIXTURE_STATE_INVALID")
    owner_patterns = {
        "runId": r"[0-9a-f]{32}",
        "repositoryId": r"[0-9a-f]{64}",
        "commitSha": r"(?:[0-9a-f]{40}|[0-9a-f]{64})",
        "treeSha": r"(?:[0-9a-f]{40}|[0-9a-f]{64})",
    }
    for key, pattern in owner_patterns.items():
        value = state[key]
        if (
            not isinstance(value, str)
            or re.fullmatch(pattern, value) is None
            or any(
                ord(character) <= 0x1F
                or 0x7F <= ord(character) <= 0x9F
                or unicodedata.category(character) == "Cf"
                for character in value
            )
        ):
            fail("RUN_FIXTURE_STATE_IDENTITY_INVALID")
    if not isinstance(state["composeProject"], str) or state["composeProject"] != RUN_FIXTURE_PROJECT:
        fail("RUN_FIXTURE_STATE_IDENTITY_INVALID")
    plan = validate_plan(state["plan"])
    if tuple(plan) != (
        "transactionId", "passwordEventId", "transferLimitEventId", "idempotencyKey",
        "duplicateIdempotencyKey", "customerRef", "senderRef", "recipientRef",
        "passwordOccurredAt", "transferLimitOccurredAt", "transactionOccurredAt",
    ):
        fail("RUN_FIXTURE_STATE_INVALID")
    database = state["database"]
    if not isinstance(database, dict) or tuple(database) != BUSINESS_TABLES:
        fail("RUN_FIXTURE_STATE_INVALID")
    for table in BUSINESS_TABLES:
        value = database[table]
        if not isinstance(value, dict) or tuple(value) != ("count", "rowHashes", "fingerprint"):
            fail("RUN_FIXTURE_STATE_INVALID")
        if (
            isinstance(value["count"], bool)
            or not isinstance(value["count"], int)
            or value["count"] < 0
            or not isinstance(value["rowHashes"], list)
            or len(value["rowHashes"]) != value["count"]
            or any(not isinstance(item, str) or re.fullmatch(r"[0-9a-f]{64}", item) is None for item in value["rowHashes"])
            or not isinstance(value["fingerprint"], str)
            or re.fullmatch(r"[0-9a-f]{64}", value["fingerprint"]) is None
        ):
            fail("RUN_FIXTURE_STATE_INVALID")
        snapshot = TableSnapshot(
            value["count"],
            tuple(bytes.fromhex(item) for item in value["rowHashes"]),
            bytes.fromhex(value["fingerprint"]),
        )
        validate_table_snapshot(table, snapshot)
    dependencies = state["dependencies"]
    metrics = state["metrics"]
    if (
        not isinstance(dependencies, list)
        or len(dependencies) != 2
        or any(isinstance(item, bool) or not isinstance(item, int) or item < 0 for item in dependencies)
        or not isinstance(metrics, list)
        or len(metrics) != 2
        or any(
            isinstance(item, bool)
            or not isinstance(item, (int, float))
            or not math.isfinite(float(item))
            or item < 0
            for item in metrics
        )
    ):
        fail("RUN_FIXTURE_STATE_INVALID")
    encoded = (json.dumps(state, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")
    if len(encoded) > 16_777_216:
        fail("RUN_FIXTURE_STATE_TOO_LARGE")
    return encoded


def parse_run_fixture_state(raw: bytes) -> dict[str, Any]:
    if (
        len(raw) > 16_777_216
        or raw.startswith(b"\xef\xbb\xbf")
        or not raw.endswith(b"\n")
        or raw.endswith(b"\n\n")
        or b"\r" in raw
    ):
        fail("RUN_FIXTURE_STATE_INVALID")

    def exact_object(values: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in values:
            if key in result:
                fail("RUN_FIXTURE_STATE_INVALID")
            result[key] = value
        return result

    try:
        state = json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=exact_object)
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail("RUN_FIXTURE_STATE_INVALID")
    canonical = run_fixture_state_bytes(state)
    if canonical != raw:
        fail("RUN_FIXTURE_STATE_INVALID")
    return state


def snapshot_to_state(database: dict[str, TableSnapshot]) -> dict[str, Any]:
    if tuple(database) != BUSINESS_TABLES:
        fail("RUN_FIXTURE_STATE_INVALID")
    result: dict[str, Any] = {}
    for table in BUSINESS_TABLES:
        snapshot = database[table]
        validate_table_snapshot(table, snapshot)
        result[table] = {
            "count": snapshot.count,
            "rowHashes": [item.hex() for item in snapshot.row_hashes],
            "fingerprint": snapshot.fingerprint.hex(),
        }
    return result


def state_to_snapshot(state: dict[str, Any]) -> dict[str, TableSnapshot]:
    run_fixture_state_bytes(state)
    return {
        table: TableSnapshot(
            state["database"][table]["count"],
            tuple(bytes.fromhex(item) for item in state["database"][table]["rowHashes"]),
            bytes.fromhex(state["database"][table]["fingerprint"]),
        )
        for table in BUSINESS_TABLES
    }


def run_fixture_before(ctx: HostContext, fixture_directory: Path) -> dict[str, Any]:
    validate_run_fixture_project(ctx.project)
    directory = fixture_directory.resolve(strict=True)
    if (
        fixture_directory.is_symlink()
        or not directory.is_dir()
        or directory.name != "finguardops-keycloak-e2e-fixture-" + ctx.contract.run_id
        or tuple(directory.iterdir())
    ):
        fail("FIXTURE_DIRECTORY_INVALID")
    publish_rules(ctx, before_diagnostics=True)
    plan = create_plan()
    if transaction_cardinality(
        ctx, plan, "TRANSACTION_CARDINALITY_SNAPSHOT"
    ) != expected_transaction_cardinality(False, False, False):
        fail("DATABASE_TRANSACTION_CARDINALITY_INVALID")
    before_database = database_snapshot(ctx, "DATABASE_GLOBAL_SNAPSHOT")
    before_dependencies = dependency_hit_counts(
        ctx, ("EXTERNAL_RISK_LOG_SNAPSHOT", "RULE_V2_LOG_SNAPSHOT")
    )
    before_metrics = backend_metric_totals(ctx, "BACKEND_METRIC_SNAPSHOT")
    state = {
        "schemaVersion": 1,
        "runId": ctx.contract.run_id,
        "repositoryId": ctx.contract.repository_id,
        "commitSha": ctx.contract.commit_sha,
        "treeSha": ctx.contract.tree_sha,
        "composeProject": RUN_FIXTURE_PROJECT,
        "plan": plan,
        "database": snapshot_to_state(before_database),
        "dependencies": list(before_dependencies),
        "metrics": list(before_metrics),
    }
    run_fixture_state_bytes(state)
    return state


def run_fixture_after(ctx: HostContext, fixture_directory: Path, state: dict[str, Any]) -> None:
    validate_run_fixture_project(ctx.project)
    directory = fixture_directory.resolve(strict=True)
    if (
        fixture_directory.is_symlink()
        or not directory.is_dir()
        or directory.name != "finguardops-keycloak-e2e-fixture-" + ctx.contract.run_id
    ):
        fail("FIXTURE_DIRECTORY_INVALID")
    expected_identity = {
        "runId": ctx.contract.run_id,
        "repositoryId": ctx.contract.repository_id,
        "commitSha": ctx.contract.commit_sha,
        "treeSha": ctx.contract.tree_sha,
        "composeProject": RUN_FIXTURE_PROJECT,
    }
    run_fixture_state_bytes(state)
    if any(state[key] != value for key, value in expected_identity.items()):
        fail("RUN_FIXTURE_STATE_IDENTITY_INVALID")
    before_database = state_to_snapshot(state)
    before_dependencies = tuple(state["dependencies"])
    before_metrics = tuple(float(value) for value in state["metrics"])
    plan = validate_plan(state["plan"])
    after_database = database_snapshot(ctx)
    after_dependencies = dependency_hit_counts(ctx)
    after_metrics = backend_metric_totals(ctx)
    assert_global_delta(before_database, after_database, RUN_FIXTURE_GLOBAL_DELTA)
    if tuple(
        after_dependencies[index] - before_dependencies[index] for index in range(2)
    ) != (1, 1):
        fail("DEPENDENCY_HIT_DELTA_INVALID")
    if tuple(after_metrics[index] - before_metrics[index] for index in range(2)) != (1.0, 1.0):
        fail("BACKEND_OUTCOME_METRIC_DELTA_INVALID")
    if transaction_cardinality(ctx, plan) != expected_transaction_cardinality(True, True, False):
        fail("DATABASE_TRANSACTION_CARDINALITY_INVALID")
    case_id = transaction_case_id(ctx, plan)
    entries = tuple(directory.iterdir())
    if len(entries) != 1 or entries[0].name != FIXTURE_MANIFEST_NAME or entries[0].is_symlink():
        fail("FIXTURE_MANIFEST_CARDINALITY_INVALID")
    try:
        identity = parse_fixture_manifest_bytes(entries[0].read_bytes())
    except OSError:
        fail("FIXTURE_MANIFEST_READ_FAILED")
    expected = fixture_identity_from_environment(ctx.environment)
    expected.update({
        "transactionId": plan["transactionId"],
        "caseId": case_id,
        "expectedRiskLevel": "HIGH",
        "expectedResponseOutcome": "ADDITIONAL_AUTH_REQUIRED",
        "expectedInitialCaseStatus": "OPEN",
    })
    if identity != expected:
        fail("FIXTURE_MANIFEST_IDENTITY_INVALID")
    print("run fixture orchestration completed: exact delta and manifest passed")


def existing_volume_phase(ctx: HostContext) -> None:
    print("stage: existing-volume-restart")
    ctx.execute(
        [
            "up", "-d", "--no-build", "--pull", "never", "--no-deps",
            "--force-recreate", "keycloak",
        ],
        timeout=240,
    )
    wait_container(ctx, "keycloak", "healthy")
    ctx.execute(
        [
            "run", "--rm", "--no-deps", "--pull", "never", "-T",
            "keycloak-bootstrap", "reconcile",
        ],
        timeout=120,
    )
    host_runtime(ctx.repo / "infra" / "keycloak" / ".local" / "tls" / "localhost.crt")
    publish_rules(ctx, before_diagnostics=True, verify_oneoff_lifetime=True)
    run_ingestion_phase(ctx)
    print("stage: existing-volume-ingestion-complete")


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
    validate_owner_images(config, ctx.contract)
    print("stage: static-complete")
    ctx.execute(
        [
            "up", "-d", "--no-build", "--pull", "never",
            "external-risk-mock", "keycloak-bootstrap",
        ],
        timeout=900,
    )
    wait_container(ctx, "external-risk-mock", "healthy")
    wait_container(ctx, "keycloak-bootstrap", "completed")
    print("stage: fresh-runtime-ready")
    host_runtime(ctx.repo / "infra" / "keycloak" / ".local" / "tls" / "localhost.crt")
    publish_rules(ctx, before_diagnostics=True, verify_oneoff_lifetime=True)
    print("stage: rules-active")
    run_ingestion_phase(ctx)
    print("stage: fresh-ingestion-complete")
    existing_volume_phase(ctx)
    print("all verification completed: fresh=passed existing-volume=passed")


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
    subparsers.add_parser("run-fixture")
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
    for mode in ("run-fixture-before", "run-fixture-after"):
        fixture_host_parser = subparsers.add_parser(mode)
        fixture_host_parser.add_argument(
            "--repo-root", default=str(Path(__file__).resolve().parents[2]), type=Path
        )
        fixture_host_parser.add_argument("--project", required=True)
        fixture_host_parser.add_argument("--fixture-directory", required=True, type=Path)
        fixture_host_parser.add_argument("--cli-timeout", default=30.0, type=float)
        fixture_host_parser.add_argument("--deadline-seconds", default=600.0, type=float)
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
        elif args.mode == "run-fixture":
            run_fixture_worker()
        elif args.mode == "ingestion-runtime":
            ingestion_runtime(args.step)
        elif args.mode == "metric-runtime":
            print(json.dumps(metric_totals(), separators=(",", ":")))
        elif args.mode == "host":
            host_runtime(args.certificate)
        elif args.mode in {"all", "run-fixture-before", "run-fixture-after"}:
            repo = args.repo_root.resolve()
            if (
                not repo.is_absolute()
                or args.cli_timeout <= 0
                or args.deadline_seconds <= 0
            ):
                fail("HOST_ARGUMENT_INVALID")
            if args.mode == "all":
                validate_service_project(args.project)
            else:
                validate_run_fixture_project(args.project)
            contract = load_owner_contract(dict(os.environ))
            context = HostContext(
                repo,
                args.project,
                args.cli_timeout,
                args.deadline_seconds,
                contract,
            )
            if args.mode == "all":
                all_runtime(context)
            elif args.mode == "run-fixture-before":
                if not args.fixture_directory.is_absolute():
                    fail("FIXTURE_DIRECTORY_INVALID")
                state = run_fixture_before(context, args.fixture_directory)
                sys.stdout.write(base64.b64encode(run_fixture_state_bytes(state)).decode("ascii") + "\n")
            else:
                if not args.fixture_directory.is_absolute():
                    fail("FIXTURE_DIRECTORY_INVALID")
                try:
                    encoded = sys.stdin.buffer.read(22_369_625).strip()
                    if len(encoded) > 22_369_624:
                        fail("RUN_FIXTURE_STATE_TOO_LARGE")
                    raw_state = base64.b64decode(encoded, validate=True)
                except (OSError, ValueError):
                    fail("RUN_FIXTURE_STATE_INVALID")
                run_fixture_after(
                    context, args.fixture_directory, parse_run_fixture_state(raw_state)
                )
        else:
            fail("COMMAND_INVALID")
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

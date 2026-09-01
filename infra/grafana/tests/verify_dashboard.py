#!/usr/bin/env python3
"""Validate the provisioned FinGuardOps local Grafana dashboard."""

from __future__ import annotations

import argparse
import base64
import copy
import json
import math
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from pathlib import Path
from typing import Any

DATASOURCE_UID = "finguardops-prometheus"
DASHBOARD_UID = "finguardops-local-overview"
DASHBOARD_TITLE = "FinGuardOps Local Observability"
FOLDER_TITLE = "FinGuardOps Local"
SERVICE = "spring-backend"

PANEL_TEXT_CONTRACT = (
    (
        "Transaction Intake Rate",
        "Public transaction intake outcomes per second over the last 5 minutes.",
    ),
    (
        "Transactions Received Rate",
        "New transactions recorded as RECEIVED per second over the last 5 minutes.",
    ),
    (
        "Transaction Terminal Rate",
        "Terminal transaction outcomes per second over the last 5 minutes.",
    ),
    (
        "Transaction Terminal Rate by Status",
        "Terminal transaction outcomes per second by status over the last 5 minutes.",
    ),
    (
        "Transaction Terminal Failure Ratio",
        "FAILED terminal outcomes divided by all terminal outcomes over the last 5 minutes.",
    ),
    (
        "Transaction Processing Duration",
        "Average transaction processing duration in seconds over the last 5 minutes.",
    ),
    (
        "Duplicate Requests by Result",
        "Duplicate transaction requests per second by result over the last 5 minutes.",
    ),
    (
        "Idempotency Conflict Rate",
        "Idempotency conflict requests per second over the last 5 minutes.",
    ),
    (
        "External Risk Outcomes by Result",
        "External Risk attempts per second by result over the last 5 minutes.",
    ),
    (
        "External Risk Failure Ratio",
        "Failed External Risk outcomes divided by all External Risk outcomes over the last 5 minutes.",
    ),
    (
        "External Risk Duration",
        "Average External Risk attempt duration in seconds over the last 5 minutes.",
    ),
    (
        "Rule Analysis Outcomes by Result",
        "Rule Analysis attempts per second by result over the last 5 minutes.",
    ),
    (
        "Rule Analysis Failure Ratio",
        "Failed Rule Analysis outcomes divided by all Rule Analysis outcomes over the last 5 minutes.",
    ),
    (
        "Rule Analysis Duration",
        "Average Rule Analysis attempt duration in seconds over the last 5 minutes.",
    ),
    (
        "Backend Prometheus Target",
        "Current Prometheus scrape availability for the FinGuardOps Backend target.",
    ),
    (
        "FinGuardOps Alert States",
        "Pending and firing states for the six approved FinGuardOps alert rules; inactive alerts have no series.",
    ),
)

RECORDING_RULES = (
    "finguardops:transaction_intake:rate5m",
    "finguardops:transactions_received:rate5m",
    "finguardops:transaction_terminal:rate5m",
    "finguardops:transaction_terminal_by_status:rate5m",
    "finguardops:transaction_terminal_failure:ratio5m",
    "finguardops:transaction_processing_duration:avg5m",
    "finguardops:http_duplicate_by_result:rate5m",
    "finguardops:http_idempotency_conflict:rate5m",
    "finguardops:external_risk_by_result:rate5m",
    "finguardops:external_risk_failure:ratio5m",
    "finguardops:external_risk_duration:avg5m",
    "finguardops:rule_analysis_by_result:rate5m",
    "finguardops:rule_analysis_failure:ratio5m",
    "finguardops:rule_analysis_duration:avg5m",
)

ALERT_NAMES = (
    "FinGuardOpsTransactionTerminalFailureRatioWarning",
    "FinGuardOpsTransactionTerminalFailureRatioCritical",
    "FinGuardOpsExternalRiskFailureRatioWarning",
    "FinGuardOpsExternalRiskFailureRatioCritical",
    "FinGuardOpsRuleAnalysisFailureRatioWarning",
    "FinGuardOpsRuleAnalysisFailureRatioCritical",
)

RECORDING_QUERIES = tuple(
    f'{rule}{{service="{SERVICE}"}}' for rule in RECORDING_RULES
)
TARGET_QUERY = 'up{job="finguardops-backend"}'
ALERT_QUERY = (
    'ALERTS{alertname=~"'
    + "|".join(ALERT_NAMES)
    + '",alertstate=~"pending|firing",service="spring-backend"}'
)
QUERY_ALLOWLIST = (*RECORDING_QUERIES, TARGET_QUERY, ALERT_QUERY)

EXPECTED_UNITS = {
    RECORDING_QUERIES[0]: "reqps",
    RECORDING_QUERIES[1]: "ops",
    RECORDING_QUERIES[2]: "ops",
    RECORDING_QUERIES[3]: "ops",
    RECORDING_QUERIES[4]: "percentunit",
    RECORDING_QUERIES[5]: "s",
    RECORDING_QUERIES[6]: "reqps",
    RECORDING_QUERIES[7]: "reqps",
    RECORDING_QUERIES[8]: "ops",
    RECORDING_QUERIES[9]: "percentunit",
    RECORDING_QUERIES[10]: "s",
    RECORDING_QUERIES[11]: "ops",
    RECORDING_QUERIES[12]: "percentunit",
    RECORDING_QUERIES[13]: "s",
    TARGET_QUERY: "short",
    ALERT_QUERY: "short",
}

EXPECTED_LEGENDS = {
    RECORDING_QUERIES[3]: "{{status}}",
    RECORDING_QUERIES[6]: "{{result}}",
    RECORDING_QUERIES[8]: "{{result}}",
    RECORDING_QUERIES[11]: "{{result}}",
    TARGET_QUERY: "finguardops-backend",
    ALERT_QUERY: "{{alertname}} {{alertstate}}",
}

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_DASHBOARD = REPO_ROOT / "infra/grafana/dashboards/finguardops-local-overview.json"
DEFAULT_DATASOURCE = REPO_ROOT / "infra/grafana/provisioning/datasources/prometheus.yml"
DEFAULT_PROVIDER = REPO_ROOT / "infra/grafana/provisioning/dashboards/finguardops.yml"


class ValidationError(RuntimeError):
    """A deterministic contract assertion failed."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise ValidationError(f"cannot read UTF-8 file {path}: {error}") from error


YamlNode = Any
YamlToken = tuple[int, str, int]
YAML_KEY = re.compile(r"^[A-Za-z_][A-Za-z0-9_-]*$")


def validate_yaml_scalar(value: str, source: Path, line_number: int) -> str:
    require(value != "", f"{source}:{line_number}: empty scalar is unsupported")
    require(
        value[0] not in "[{",
        f"{source}:{line_number}: flow-style YAML is unsupported",
    )
    require(
        value[0] not in "&*!",
        f"{source}:{line_number}: anchor, alias, or tag YAML is unsupported",
    )
    require(
        value[0] not in "|>",
        f"{source}:{line_number}: block scalar YAML is unsupported",
    )
    require(
        re.search(r"(?:^|\s)[&*!][A-Za-z0-9_-]+", value) is None,
        f"{source}:{line_number}: anchor, alias, or tag YAML is unsupported",
    )
    return value


def yaml_tokens(text: str, source: Path) -> list[YamlToken]:
    require("\x00" not in text, f"{source}: NUL is not allowed")
    tokens: list[YamlToken] = []
    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        require("\t" not in raw_line, f"{source}:{line_number}: tab indentation is not allowed")
        line = raw_line.rstrip()
        content = line.lstrip(" ")
        if not content or content.startswith("#"):
            continue
        require(
            content not in {"---", "..."},
            f"{source}:{line_number}: multi-document YAML is unsupported",
        )
        indentation = len(line) - len(content)
        if content.startswith("- "):
            item = content[2:].strip()
            key_value = item.split(":", maxsplit=1)
            if len(key_value) == 2 and YAML_KEY.fullmatch(key_value[0]):
                tokens.append((indentation, "-", line_number))
                tokens.append((indentation + 2, item, line_number))
                continue
        tokens.append((indentation, content, line_number))
    return tokens


def parse_yaml_mapping(
    tokens: list[YamlToken],
    index: int,
    indentation: int,
    source: Path,
) -> tuple[dict[str, YamlNode], int]:
    result: dict[str, YamlNode] = {}
    while index < len(tokens):
        current_indent, content, line_number = tokens[index]
        if current_indent < indentation:
            break
        require(
            current_indent == indentation,
            f"{source}:{line_number}: malformed mapping indentation",
        )
        require(not content.startswith("-"), f"{source}:{line_number}: mixed mapping and sequence")
        key_value = content.split(":", maxsplit=1)
        require(len(key_value) == 2, f"{source}:{line_number}: mapping entry must contain ':'")
        key, value = key_value[0], key_value[1].strip()
        require(YAML_KEY.fullmatch(key) is not None, f"{source}:{line_number}: unsupported mapping key")
        require(key not in result, f"{source}:{line_number}: duplicate mapping key: {key}")
        index += 1
        if value:
            result[key] = validate_yaml_scalar(value, source, line_number)
            if index < len(tokens) and tokens[index][0] > indentation:
                raise ValidationError(
                    f"{source}:{tokens[index][2]}: scalar mapping value cannot have nested content"
                )
            continue
        require(index < len(tokens), f"{source}:{line_number}: mapping key has no block value")
        child_indent = tokens[index][0]
        require(
            child_indent > indentation,
            f"{source}:{tokens[index][2]}: mapping block must be indented",
        )
        result[key], index = parse_yaml_block(tokens, index, child_indent, source)
    return result, index


def parse_yaml_sequence(
    tokens: list[YamlToken],
    index: int,
    indentation: int,
    source: Path,
) -> tuple[list[YamlNode], int]:
    result: list[YamlNode] = []
    while index < len(tokens):
        current_indent, content, line_number = tokens[index]
        if current_indent < indentation:
            break
        require(
            current_indent == indentation,
            f"{source}:{line_number}: inconsistent sequence item indentation",
        )
        require(content.startswith("-"), f"{source}:{line_number}: mixed sequence and mapping")
        if content != "-":
            require(content.startswith("- "), f"{source}:{line_number}: malformed sequence item")
            result.append(validate_yaml_scalar(content[2:].strip(), source, line_number))
            index += 1
            if index < len(tokens) and tokens[index][0] > indentation:
                raise ValidationError(
                    f"{source}:{tokens[index][2]}: scalar sequence item cannot have nested content"
                )
            continue
        index += 1
        require(index < len(tokens), f"{source}:{line_number}: sequence item has no value")
        child_indent = tokens[index][0]
        require(
            child_indent > indentation,
            f"{source}:{tokens[index][2]}: sequence item value must be indented",
        )
        child, index = parse_yaml_block(tokens, index, child_indent, source)
        result.append(child)
    return result, index


def parse_yaml_block(
    tokens: list[YamlToken],
    index: int,
    indentation: int,
    source: Path,
) -> tuple[YamlNode, int]:
    require(index < len(tokens), f"{source}: YAML block is empty")
    require(
        tokens[index][0] == indentation,
        f"{source}:{tokens[index][2]}: malformed block indentation",
    )
    if tokens[index][1].startswith("-"):
        return parse_yaml_sequence(tokens, index, indentation, source)
    return parse_yaml_mapping(tokens, index, indentation, source)


def parse_limited_yaml(text: str, source: Path) -> dict[str, YamlNode]:
    tokens = yaml_tokens(text, source)
    require(tokens, f"{source}: YAML document is empty")
    require(tokens[0][0] == 0, f"{source}:{tokens[0][2]}: root mapping must not be indented")
    root, index = parse_yaml_block(tokens, 0, 0, source)
    if index != len(tokens):
        raise ValidationError(f"{source}:{tokens[index][2]}: YAML document was not fully parsed")
    require(isinstance(root, dict), f"{source}: YAML root must be a mapping")
    return root


def yaml_objects(text: str, key: str, source: Path) -> list[dict[str, YamlNode]]:
    root = parse_limited_yaml(text, source)
    require(key in root, f"{source}: expected exactly one {key} root key")
    objects = root[key]
    require(isinstance(objects, list), f"{source}: {key} must be a block sequence")
    require(
        all(isinstance(item, dict) for item in objects),
        f"{source}: every {key} sequence item must be an object",
    )
    return [item for item in objects if isinstance(item, dict)]


def yaml_object_count(text: str, key: str, source: Path) -> int:
    return len(yaml_objects(text, key, source))


def recursive_files(root: Path, suffixes: set[str]) -> list[Path]:
    require(root.is_dir(), f"{root}: search root must be a directory")
    root_resolved = root.resolve(strict=True)
    candidates: list[Path] = []
    for current, directories, filenames in os.walk(root, followlinks=False):
        current_path = Path(current)
        for name in [*directories, *filenames]:
            candidate = current_path / name
            require(not candidate.is_symlink(), f"{candidate}: symlinks are not allowed")
            resolved = candidate.resolve(strict=True)
            require(
                resolved == root_resolved or root_resolved in resolved.parents,
                f"{candidate}: path escapes search root",
            )
        for filename in filenames:
            candidate = current_path / filename
            if candidate.suffix.lower() not in suffixes:
                continue
            require(
                candidate.suffix in suffixes,
                f"{candidate}: provisioning and dashboard extensions must be lowercase",
            )
            candidates.append(candidate)
    return sorted(candidates, key=lambda item: item.relative_to(root).as_posix())


def provisioning_files(path: Path) -> list[Path]:
    return recursive_files(path.parent, {".yml", ".yaml"})


def validate_datasource(path: Path) -> None:
    files = provisioning_files(path)
    require(path in files, f"{path}: datasource provisioning file was not found")
    documents = [
        (source, parse_limited_yaml(text, source), yaml_objects(text, "datasources", source))
        for source in files
        for text in [read_text(source)]
    ]
    count = sum(len(objects) for _, _, objects in documents)
    require(count == 1, f"datasource provisioning must contain exactly one object, got {count}")
    uids = [item.get("uid") for _, _, objects in documents for item in objects]
    require(uids == [DATASOURCE_UID], "datasource UID must be unique and exact")
    root = next(root for source, root, _ in documents if source == path)
    objects = next(objects for source, _, objects in documents if source == path)
    require(root.get("apiVersion") == "1", f"{path}: apiVersion must be 1")
    require(len(objects) == 1, f"{path}: expected exactly one datasource object")
    datasource = objects[0]
    required = {
        "name": "FinGuardOps Prometheus",
        "uid": DATASOURCE_UID,
        "type": "prometheus",
        "access": "proxy",
        "url": "http://prometheus:9090",
        "isDefault": "true",
        "editable": "false",
        "version": "1",
    }
    for key, value in required.items():
        require(datasource.get(key) == value, f"{path}: expected datasource {key}: {value}")


def validate_provider(path: Path) -> None:
    files = provisioning_files(path)
    require(path in files, f"{path}: dashboard provider file was not found")
    documents = [
        (source, parse_limited_yaml(text, source), yaml_objects(text, "providers", source))
        for source in files
        for text in [read_text(source)]
    ]
    count = sum(len(objects) for _, _, objects in documents)
    require(count == 1, f"dashboard provisioning must contain exactly one provider, got {count}")
    root = next(root for source, root, _ in documents if source == path)
    objects = next(objects for source, _, objects in documents if source == path)
    require(root.get("apiVersion") == "1", f"{path}: apiVersion must be 1")
    require(len(objects) == 1, f"{path}: expected exactly one provider object")
    provider = objects[0]
    required = {
        "name": FOLDER_TITLE,
        "orgId": "1",
        "folder": FOLDER_TITLE,
        "type": "file",
        "disableDeletion": "false",
        "allowUiUpdates": "false",
        "updateIntervalSeconds": "10",
    }
    for key, value in required.items():
        require(provider.get(key) == value, f"{path}: expected provider {key}: {value}")
    options = provider.get("options")
    require(isinstance(options, dict), f"{path}: provider options must be a mapping")
    require(options.get("path") == "/etc/grafana/dashboards", f"{path}: provider path is wrong")
    require(
        options.get("foldersFromFilesStructure") == "false",
        f"{path}: foldersFromFilesStructure must be false",
    )


def load_dashboard(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(read_text(path))
    except json.JSONDecodeError as error:
        raise ValidationError(f"{path}: invalid JSON: {error}") from error
    require(isinstance(data, dict), f"{path}: dashboard root must be an object")
    return data


def panel_contract(panel: dict[str, Any], index: int) -> tuple[str, str, str]:
    panel_id = panel.get("id")
    require(
        isinstance(panel_id, int) and not isinstance(panel_id, bool) and panel_id > 0,
        f"panel {index}: id must be a positive integer",
    )
    expected_datasource = {"type": "prometheus", "uid": DATASOURCE_UID}
    require(
        panel.get("datasource") == expected_datasource,
        f"panel {panel_id}: datasource must use {DATASOURCE_UID}",
    )
    targets = panel.get("targets")
    require(
        isinstance(targets, list) and len(targets) == 1,
        f"panel {panel_id}: exactly one target is required",
    )
    target = targets[0]
    require(isinstance(target, dict), f"panel {panel_id}: target must be an object")
    require(
        target.get("datasource") == expected_datasource,
        f"panel {panel_id}: target datasource must use {DATASOURCE_UID}",
    )
    expression = target.get("expr")
    require(isinstance(expression, str), f"panel {panel_id}: PromQL must be a string")
    unit = panel.get("fieldConfig", {}).get("defaults", {}).get("unit")
    legend = target.get("legendFormat")
    require(isinstance(unit, str), f"panel {panel_id}: unit is required")
    require(isinstance(legend, str), f"panel {panel_id}: legendFormat is required")
    return expression, unit, legend


def validate_dashboard_data(data: dict[str, Any], *, source: bool) -> None:
    require(data.get("uid") == DASHBOARD_UID, "dashboard UID is not exact")
    require(data.get("title") == DASHBOARD_TITLE, "dashboard title is not exact")
    require(data.get("refresh") == "30s", "dashboard refresh must be 30s")
    require(
        data.get("time") == {"from": "now-30m", "to": "now"},
        "dashboard time range must be now-30m through now",
    )
    require(data.get("editable") is False, "dashboard must not be UI-editable")

    if source:
        forbidden_metadata = {
            "id",
            "meta",
            "created",
            "createdBy",
            "updated",
            "updatedBy",
        }
        present = sorted(forbidden_metadata.intersection(data))
        require(not present, f"dashboard contains runtime metadata: {present}")

    panels = data.get("panels")
    require(isinstance(panels, list), "dashboard panels must be an array")
    require(len(panels) == 16, f"dashboard must contain exactly 16 panels, got {len(panels)}")

    ids: list[int] = []
    expressions: list[str] = []
    for index, panel in enumerate(panels, start=1):
        require(isinstance(panel, dict), f"panel {index}: must be an object")
        expected_title, expected_description = PANEL_TEXT_CONTRACT[index - 1]
        require(
            panel.get("title") == expected_title,
            f"panel {index}: title must be {expected_title!r}",
        )
        require(
            panel.get("description") == expected_description,
            f"panel {index}: description must be {expected_description!r}",
        )
        expression, unit, legend = panel_contract(panel, index)
        ids.append(panel["id"])
        expressions.append(expression)
        require(expression in QUERY_ALLOWLIST, f"panel {panel['id']}: PromQL is not allowed")
        require(
            unit == EXPECTED_UNITS[expression],
            f"panel {panel['id']}: unit {unit!r} is invalid for {expression}",
        )
        expected_legend = EXPECTED_LEGENDS.get(expression, SERVICE)
        require(
            legend == expected_legend,
            f"panel {panel['id']}: legend {legend!r} must be {expected_legend!r}",
        )

    require(len(ids) == len(set(ids)), "panel IDs must be unique")
    require(
        sorted(expressions) == sorted(QUERY_ALLOWLIST),
        "dashboard PromQL does not exactly match the 16-query allowlist",
    )

    recording = [expression for expression in expressions if expression.startswith("finguardops:")]
    require(len(recording) == 14, "dashboard must contain exactly 14 recording-rule panels")
    require(len(recording) == len(set(recording)), "recording-rule query is duplicated")
    for expression in recording:
        require(
            f'service="{SERVICE}"' in expression,
            f"recording query is not scoped to {SERVICE}: {expression}",
        )

    joined_queries = "\n".join(expressions)
    lowered_queries = joined_queries.lower()
    require(
        re.search(r"\bfinguardops_[a-z0-9_:]*", lowered_queries) is None,
        "raw finguardops Meter must not be queried",
    )
    for forbidden in ("vector(0)", "clamp_min", "or vector"):
        require(forbidden not in lowered_queries, f"zero synthesis is forbidden: {forbidden}")

    forbidden_labels = (
        "transactionid",
        "transaction_id",
        "caseid",
        "case_id",
        "eventid",
        "event_id",
        "accountid",
        "account_id",
        "customerid",
        "customer_id",
        "userid",
        "user_id",
        "credential",
        "password",
        "token",
    )
    for label in forbidden_labels:
        pattern = rf"\b{re.escape(label)}\s*(?:=|=~|!=|!~)"
        require(
            re.search(pattern, lowered_queries) is None,
            f"forbidden query label used: {label}",
        )

    alert_expression = expressions[-1] if expressions[-1] == ALERT_QUERY else ALERT_QUERY
    names_in_query = re.findall(r"FinGuardOps[A-Za-z]+(?:Warning|Critical)", alert_expression)
    require(
        tuple(names_in_query) == ALERT_NAMES,
        "alert query must contain the exact six alert names",
    )

    if source:
        serialized = json.dumps(data, ensure_ascii=False).lower()
        forbidden_strings = (
            "authorization",
            "api key",
            "api_key",
            "credential",
            "password",
            "customerid",
            "customer_id",
            "accountid",
            "account_id",
            "transactionid",
            "transaction_id",
        )
        for value in forbidden_strings:
            require(value not in serialized, f"dashboard contains forbidden string: {value}")


def validate_static_paths(dashboard: Path, datasource: Path, provider: Path) -> None:
    dashboard_files = recursive_files(dashboard.parent, {".json"})
    require(dashboard in dashboard_files, f"{dashboard}: dashboard JSON was not found")
    require(
        len(dashboard_files) == 1,
        f"dashboard directory must contain exactly one JSON file, got {len(dashboard_files)}",
    )
    dashboards = [load_dashboard(path) for path in dashboard_files]
    require(len(dashboards) == 1, f"exactly one dashboard is required, got {len(dashboards)}")
    dashboard_uids = [item.get("uid") for item in dashboards]
    require(dashboard_uids == [DASHBOARD_UID], "dashboard UID must be unique and exact")
    validate_datasource(datasource)
    validate_provider(provider)
    validate_dashboard_data(dashboards[0], source=True)


class GrafanaClient:
    def __init__(self, base_url: str, username: str, password: str, timeout: float) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        credentials = base64.b64encode(f"{username}:{password}".encode()).decode("ascii")
        self.authorization = f"Basic {credentials}"

    def request_json(
        self,
        path: str,
        *,
        authenticated: bool = True,
        payload: dict[str, Any] | None = None,
    ) -> Any:
        headers = {"Accept": "application/json"}
        if authenticated:
            headers["Authorization"] = self.authorization
        data = None
        method = "GET"
        if payload is not None:
            data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"
            method = "POST"
        request = urllib.request.Request(
            self.base_url + path,
            data=data,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise ValidationError(f"Grafana returned HTTP {error.code} for {path}") from None
        except urllib.error.URLError as error:
            raise ValidationError(f"Grafana request failed for {path}: {error.reason}") from None
        except OSError as error:
            raise ValidationError(f"Grafana request failed for {path}: {error}") from None
        except json.JSONDecodeError as error:
            raise ValidationError(f"Grafana returned invalid JSON for {path}: {error}") from error

    def assert_anonymous_denied(self) -> None:
        request = urllib.request.Request(
            self.base_url + "/api/search?type=dash-db",
            headers={"Accept": "application/json"},
            method="GET",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout):
                pass
        except urllib.error.HTTPError as error:
            require(error.code == 401, f"anonymous API returned HTTP {error.code}, expected 401")
            return
        except urllib.error.URLError as error:
            raise ValidationError(f"anonymous access check failed: {error.reason}") from None
        raise ValidationError("anonymous Grafana API access was not rejected")

    def query(self, expression: str) -> dict[str, Any]:
        now_ms = int(time.time() * 1000)
        payload = {
            "from": str(now_ms - 300_000),
            "to": str(now_ms),
            "queries": [
                {
                    "datasource": {"type": "prometheus", "uid": DATASOURCE_UID},
                    "editorMode": "code",
                    "expr": expression,
                    "format": "time_series",
                    "instant": True,
                    "intervalMs": 15_000,
                    "maxDataPoints": 1_000,
                    "range": False,
                    "refId": "A",
                }
            ],
        }
        response = self.request_json("/api/ds/query", payload=payload)
        require(isinstance(response, dict), "datasource query response must be an object")
        result = response.get("results", {}).get("A")
        require(isinstance(result, dict), "datasource query response has no result A")
        require(not result.get("error"), "datasource query returned an error")
        return result


def result_has_samples(result: dict[str, Any]) -> bool:
    frames = result.get("frames")
    require(isinstance(frames, list), "datasource query frames must be an array")
    has_numeric_sample = False
    for frame_index, frame in enumerate(frames):
        require(isinstance(frame, dict), f"datasource query frame {frame_index} must be an object")
        schema = frame.get("schema")
        data = frame.get("data")
        require(isinstance(schema, dict), f"datasource query frame {frame_index} schema must be an object")
        require(isinstance(data, dict), f"datasource query frame {frame_index} data must be an object")
        fields = schema.get("fields")
        values = data.get("values")
        require(isinstance(fields, list), f"datasource query frame {frame_index} fields must be an array")
        require(isinstance(values, list), f"datasource query frame {frame_index} values must be an array")
        require(
            len(fields) == len(values),
            f"datasource query frame {frame_index} field/value count mismatch",
        )
        for field_index, (field, series) in enumerate(zip(fields, values)):
            require(
                isinstance(field, dict),
                f"datasource query frame {frame_index} field {field_index} must be an object",
            )
            require(
                isinstance(series, list),
                f"datasource query frame {frame_index} values {field_index} must be an array",
            )
            if field.get("type") != "number":
                continue
            if any(
                isinstance(value, (int, float))
                and not isinstance(value, bool)
                and math.isfinite(value)
                for value in series
            ):
                has_numeric_sample = True
    return has_numeric_sample


def result_numeric_values(result: dict[str, Any]) -> list[float]:
    numbers: list[float] = []
    frames = result.get("frames")
    if not isinstance(frames, list):
        return numbers
    for frame in frames:
        if not isinstance(frame, dict):
            return []
        fields = frame.get("schema", {}).get("fields")
        values = frame.get("data", {}).get("values")
        if not isinstance(fields, list) or not isinstance(values, list):
            return []
        if len(fields) != len(values):
            return []
        for field, series in zip(fields, values):
            if not isinstance(field, dict) or not isinstance(series, list):
                return []
            if field.get("type") != "number":
                continue
            numbers.extend(
                float(value)
                for value in series
                if isinstance(value, (int, float))
                and not isinstance(value, bool)
                and math.isfinite(value)
            )
    return numbers


def validate_runtime_inventory(
    datasources: Any,
    dashboards: Any,
    folders: Any,
) -> None:
    require(isinstance(datasources, list), "runtime datasource inventory must be an array")
    require(len(datasources) == 1, f"runtime must contain exactly one datasource, got {len(datasources)}")
    require(datasources[0].get("uid") == DATASOURCE_UID, "runtime datasource UID is wrong")
    require(isinstance(dashboards, list), "runtime dashboard inventory must be an array")
    require(len(dashboards) == 1, f"runtime must contain exactly one dashboard, got {len(dashboards)}")
    require(
        dashboards[0].get("uid") == DASHBOARD_UID
        and dashboards[0].get("type") == "dash-db",
        "runtime dashboard inventory is not exact",
    )
    require(isinstance(folders, list), "runtime folder inventory must be an array")
    require(len(folders) == 1, f"runtime must contain exactly one folder, got {len(folders)}")
    require(
        folders[0].get("title") == FOLDER_TITLE
        and folders[0].get("type") == "dash-folder",
        "runtime folder inventory is not exact",
    )


def object_contains_label(value: Any, label: str, expected: str) -> bool:
    if isinstance(value, dict):
        if value.get(label) == expected:
            return True
        return any(object_contains_label(item, label, expected) for item in value.values())
    if isinstance(value, list):
        return any(object_contains_label(item, label, expected) for item in value)
    return False


def wait_for(assertion: Callable[[], None], deadline_seconds: float, description: str) -> None:
    deadline = time.monotonic() + deadline_seconds
    last_error: ValidationError | None = None
    while time.monotonic() < deadline:
        try:
            assertion()
            return
        except ValidationError as error:
            last_error = error
            time.sleep(2)
    detail = f": {last_error}" if last_error is not None else ""
    raise ValidationError(f"timed out waiting for {description}{detail}")


def validate_runtime(args: argparse.Namespace) -> None:
    username = os.environ.get("GRAFANA_ADMIN_USER")
    password = os.environ.get("GRAFANA_ADMIN_PASSWORD")
    require(bool(username), "GRAFANA_ADMIN_USER is required")
    require(bool(password), "GRAFANA_ADMIN_PASSWORD is required")
    source_dashboard = load_dashboard(args.dashboard)
    client = GrafanaClient(args.grafana_url, username or "", password or "", args.http_timeout)

    def provisioning_assertion() -> None:
        health = client.request_json("/api/health", authenticated=False)
        require(health.get("database") == "ok", "Grafana database health is not ok")
        client.assert_anonymous_denied()

        datasources = client.request_json("/api/datasources")
        dashboards = client.request_json("/api/search?type=dash-db")
        folders = client.request_json("/api/search?type=dash-folder")
        validate_runtime_inventory(datasources, dashboards, folders)

        datasource = client.request_json(f"/api/datasources/uid/{DATASOURCE_UID}")
        require(datasource.get("uid") == DATASOURCE_UID, "runtime datasource UID is wrong")
        require(datasource.get("type") == "prometheus", "runtime datasource type is wrong")
        require(
            datasource.get("url") == "http://prometheus:9090",
            "runtime datasource URL is wrong",
        )
        require(datasource.get("isDefault") is True, "runtime datasource is not default")
        require(datasource.get("readOnly") is True, "runtime datasource is editable")
        if "editable" in datasource:
            require(datasource.get("editable") is False, "runtime datasource editable is not false")

        datasource_health = client.request_json(
            f"/api/datasources/uid/{DATASOURCE_UID}/health"
        )
        require(
            str(datasource_health.get("status", "")).upper() == "OK",
            "runtime datasource health is not OK",
        )

        dashboard_response = client.request_json(f"/api/dashboards/uid/{DASHBOARD_UID}")
        require(
            dashboard_response.get("meta", {}).get("folderTitle") == FOLDER_TITLE,
            "dashboard is not in the expected folder",
        )
        runtime_dashboard = dashboard_response.get("dashboard")
        require(isinstance(runtime_dashboard, dict), "dashboard API payload is missing")
        validate_dashboard_data(runtime_dashboard, source=False)
        for key in ("uid", "title", "refresh", "time", "panels"):
            require(
                runtime_dashboard.get(key) == source_dashboard.get(key),
                f"runtime dashboard differs from source for {key}",
            )

    wait_for(provisioning_assertion, args.deadline_seconds, "Grafana provisioning")

    def target_assertion() -> None:
        target_result = client.query(TARGET_QUERY)
        require(result_has_samples(target_result), "target panel query returned no series")
        require(1.0 in result_numeric_values(target_result), "target panel does not report UP=1")

    wait_for(target_assertion, args.deadline_seconds, "target panel to report UP=1")

    client.query(RECORDING_QUERIES[0])

    if not args.allow_empty_recording:
        def recording_assertion() -> None:
            empty = [
                query
                for query in RECORDING_QUERIES
                if not result_has_samples(client.query(query))
            ]
            require(not empty, f"recording panel queries returned no series: {len(empty)}")

        wait_for(
            recording_assertion,
            args.deadline_seconds,
            "all 14 recording panel queries to return data",
        )

    def alert_assertion() -> None:
        alert_result = client.query(ALERT_QUERY)
        if args.require_alert_state == "inactive":
            require(
                not result_has_samples(alert_result),
                "inactive alerts unexpectedly have ALERTS series",
            )
        elif args.require_alert_state is not None:
            require(result_has_samples(alert_result), "active alert query returned no series")
            require(
                object_contains_label(alert_result, "alertstate", args.require_alert_state),
                f"alert query did not contain state {args.require_alert_state}",
            )

    wait_for(
        alert_assertion,
        args.deadline_seconds,
        f"alert state {args.require_alert_state or 'query success'}",
    )


def write_dashboard(path: Path, dashboard: dict[str, Any]) -> None:
    path.write_text(json.dumps(dashboard, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def expect_invalid(
    name: str,
    assertion: Callable[[], Any],
    expected_message: str | None = None,
) -> None:
    try:
        assertion()
    except ValidationError as error:
        message = str(error)
        require(message != "", f"mutation returned an empty validation error: {name}")
        if expected_message is not None:
            require(
                expected_message in message,
                f"mutation {name} returned wrong error: {message}",
            )
        print(f"mutation detected: {name}: {message}")
        return
    raise ValidationError(f"mutation was not detected: {name}")


def normalize_grafana_url(value: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"invalid Grafana URL: {error}") from error
    if parsed.scheme != "http":
        raise argparse.ArgumentTypeError("Grafana URL scheme must be http")
    if parsed.username is not None or parsed.password is not None:
        raise argparse.ArgumentTypeError("Grafana URL must not contain userinfo")
    if parsed.hostname != "127.0.0.1":
        raise argparse.ArgumentTypeError("Grafana URL hostname must be 127.0.0.1")
    if port is None or not 1 <= port <= 65535:
        raise argparse.ArgumentTypeError("Grafana URL requires a port from 1 through 65535")
    if parsed.query or parsed.fragment:
        raise argparse.ArgumentTypeError("Grafana URL must not contain a query or fragment")
    if parsed.path not in ("", "/"):
        raise argparse.ArgumentTypeError("Grafana URL path must be empty or /")
    return f"http://127.0.0.1:{port}"


def bounded_positive_float(value: str, *, name: str, maximum: float) -> float:
    try:
        number = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"{name} must be a number") from error
    if not math.isfinite(number) or not 0 < number <= maximum:
        raise argparse.ArgumentTypeError(f"{name} must be finite and in (0, {maximum:g}]")
    return number


def http_timeout(value: str) -> float:
    return bounded_positive_float(value, name="--http-timeout", maximum=60.0)


def deadline_seconds(value: str) -> float:
    return bounded_positive_float(value, name="--deadline-seconds", maximum=1800.0)


def validate_mutations(args: argparse.Namespace) -> None:
    source_dashboard = load_dashboard(args.dashboard)
    datasource_text = read_text(args.datasource)
    provider_text = read_text(args.provider)

    def expect_yaml_count(name: str, text: str, key: str, expected: int) -> None:
        actual = yaml_object_count(text, key, Path(f"{name}.yml"))
        require(actual == expected, f"YAML object count regression failed: {name}: {actual}")
        print(f"YAML parser regression passed: {name}: objects={actual}")

    expect_yaml_count(
        "two-space datasource",
        "apiVersion: 1\ndatasources:\n  - name: One\n    uid: one\n",
        "datasources",
        1,
    )
    expect_yaml_count(
        "four-space datasource",
        "apiVersion: 1\ndatasources:\n    - name: One\n      uid: one\n",
        "datasources",
        1,
    )
    expect_yaml_count(
        "four-space two datasources",
        "apiVersion: 1\ndatasources:\n    - name: One\n    - name: Two\n",
        "datasources",
        2,
    )
    expect_yaml_count(
        "four-space two providers",
        "apiVersion: 1\nproviders:\n    - name: One\n    - name: Two\n",
        "providers",
        2,
    )
    expect_yaml_count(
        "six-space two datasources",
        "apiVersion: 1\ndatasources:\n      - name: One\n      - name: Two\n",
        "datasources",
        2,
    )
    expect_yaml_count(
        "six-space two providers",
        "apiVersion: 1\nproviders:\n      - name: One\n      - name: Two\n",
        "providers",
        2,
    )
    expect_yaml_count(
        "datasource nested sequence",
        (
            "apiVersion: 1\ndatasources:\n    - name: One\n"
            "      nested:\n        - name: Child One\n        - name: Child Two\n"
        ),
        "datasources",
        1,
    )
    expect_yaml_count(
        "provider nested sequence",
        (
            "apiVersion: 1\nproviders:\n      - name: One\n"
            "        nested:\n          - value-one\n          - value-two\n"
        ),
        "providers",
        1,
    )
    expect_yaml_count(
        "comments before middle after",
        (
            "# before\napiVersion: 1\ndatasources:\n"
            "    # middle\n    - name: One\n      uid: one\n# after\n"
        ),
        "datasources",
        1,
    )

    invalid_yaml_matrix = (
        (
            "duplicate datasource root key",
            "apiVersion: 1\ndatasources:\n  - name: One\ndatasources:\n  - name: Two\n",
            "datasources",
            "duplicate mapping key",
        ),
        (
            "duplicate provider root key",
            "apiVersion: 1\nproviders:\n  - name: One\nproviders:\n  - name: Two\n",
            "providers",
            "duplicate mapping key",
        ),
        (
            "tab indentation",
            "apiVersion: 1\ndatasources:\n\t- name: One\n",
            "datasources",
            "tab indentation",
        ),
        (
            "inconsistent sibling indentation",
            "apiVersion: 1\ndatasources:\n    - name: One\n     - name: Two\n",
            "datasources",
            "inconsistent sequence item indentation",
        ),
        (
            "scalar datasource item",
            "apiVersion: 1\ndatasources:\n  - scalar\n",
            "datasources",
            "every datasources sequence item must be an object",
        ),
        (
            "flow sequence",
            "apiVersion: 1\ndatasources: [{name: One}]\n",
            "datasources",
            "flow-style YAML is unsupported",
        ),
        (
            "multi document",
            "apiVersion: 1\ndatasources:\n  - name: One\n---\ndatasources:\n  - name: Two\n",
            "datasources",
            "multi-document YAML is unsupported",
        ),
        (
            "valid then malformed object",
            "apiVersion: 1\ndatasources:\n  - name: One\n  - scalar\n",
            "datasources",
            "every datasources sequence item must be an object",
        ),
        (
            "malformed then valid object",
            "apiVersion: 1\ndatasources:\n  - scalar\n  - name: One\n",
            "datasources",
            "every datasources sequence item must be an object",
        ),
        (
            "anchor",
            "apiVersion: 1\ndatasources:\n  - name: &shared One\n",
            "datasources",
            "anchor, alias, or tag YAML is unsupported",
        ),
        (
            "alias",
            "apiVersion: 1\ndatasources:\n  - name: *shared\n",
            "datasources",
            "anchor, alias, or tag YAML is unsupported",
        ),
        (
            "tag",
            "apiVersion: 1\ndatasources:\n  - name: !custom One\n",
            "datasources",
            "anchor, alias, or tag YAML is unsupported",
        ),
    )
    for name, text, key, expected_message in invalid_yaml_matrix:
        expect_invalid(
            name,
            lambda text=text, key=key, name=name: yaml_object_count(
                text,
                key,
                Path(f"{name}.yml"),
            ),
            expected_message,
        )

    with tempfile.TemporaryDirectory(prefix="finguardops-grafana-mutation-") as directory:
        root = Path(directory)
        dashboard_dir = root / "dashboards"
        datasource_dir = root / "provisioning" / "datasources"
        provider_dir = root / "provisioning" / "dashboards"
        dashboard_dir.mkdir(parents=True)
        datasource_dir.mkdir(parents=True)
        provider_dir.mkdir(parents=True)
        dashboard_path = dashboard_dir / "dashboard.json"
        datasource_path = datasource_dir / "datasource.yml"
        provider_path = provider_dir / "provider.yml"
        datasource_path.write_text(datasource_text, encoding="utf-8")
        provider_path.write_text(provider_text, encoding="utf-8")
        write_dashboard(dashboard_path, source_dashboard)

        wrong_url = datasource_text.replace(
            "http://prometheus:9090",
            "http://missing-prometheus:9090",
        )
        datasource_path.write_text(wrong_url, encoding="utf-8")
        expect_invalid("wrong datasource URL", lambda: validate_datasource(datasource_path))
        datasource_path.write_text(datasource_text, encoding="utf-8")

        nonexistent_rule = copy.deepcopy(source_dashboard)
        nonexistent_rule["panels"][0]["targets"][0]["expr"] = (
            'finguardops:missing_recording_rule:rate5m{service="spring-backend"}'
        )
        write_dashboard(dashboard_path, nonexistent_rule)
        expect_invalid(
            "nonexistent recording rule",
            lambda: validate_static_paths(dashboard_path, datasource_path, provider_path),
        )

        missing_panel = copy.deepcopy(source_dashboard)
        missing_panel["panels"].pop()
        write_dashboard(dashboard_path, missing_panel)
        expect_invalid(
            "missing panel",
            lambda: validate_static_paths(dashboard_path, datasource_path, provider_path),
        )

        wrong_unit = copy.deepcopy(source_dashboard)
        wrong_unit["panels"][4]["fieldConfig"]["defaults"]["unit"] = "short"
        write_dashboard(dashboard_path, wrong_unit)
        expect_invalid(
            "wrong panel unit",
            lambda: validate_static_paths(dashboard_path, datasource_path, provider_path),
        )

        wrong_uid = copy.deepcopy(source_dashboard)
        wrong_uid["uid"] = "temporary-dashboard"
        write_dashboard(dashboard_path, wrong_uid)
        expect_invalid(
            "wrong dashboard UID",
            lambda: validate_static_paths(dashboard_path, datasource_path, provider_path),
        )

        write_dashboard(dashboard_path, source_dashboard)
        extra_datasource = datasource_text + "\n  - name: Unauthorized\n    uid: unauthorized\n"
        datasource_path.write_text(extra_datasource, encoding="utf-8")
        expect_invalid(
            "unauthorized extra datasource",
            lambda: validate_static_paths(dashboard_path, datasource_path, provider_path),
        )
        datasource_path.write_text(datasource_text, encoding="utf-8")

        wrong_title = copy.deepcopy(source_dashboard)
        wrong_title["panels"][0]["title"] = "Wrong title"
        write_dashboard(dashboard_path, wrong_title)
        expect_invalid(
            "wrong panel title",
            lambda: validate_static_paths(dashboard_path, datasource_path, provider_path),
        )

        missing_description = copy.deepcopy(source_dashboard)
        missing_description["panels"][0].pop("description", None)
        write_dashboard(dashboard_path, missing_description)
        expect_invalid(
            "missing panel description",
            lambda: validate_static_paths(dashboard_path, datasource_path, provider_path),
        )

        duplicate_panel_id = copy.deepcopy(source_dashboard)
        duplicate_panel_id["panels"][1]["id"] = duplicate_panel_id["panels"][0]["id"]
        write_dashboard(dashboard_path, duplicate_panel_id)
        expect_invalid(
            "duplicate panel ID",
            lambda: validate_static_paths(dashboard_path, datasource_path, provider_path),
        )

        write_dashboard(dashboard_path, source_dashboard)
        write_dashboard(dashboard_dir / "unauthorized.json", source_dashboard)
        expect_invalid(
            "unauthorized extra dashboard",
            lambda: validate_static_paths(dashboard_path, datasource_path, provider_path),
        )
        (dashboard_dir / "unauthorized.json").unlink()

        four_space_datasources = (
            "apiVersion: 1\ndatasources:\n"
            "    - name: FinGuardOps Prometheus\n"
            f"      uid: {DATASOURCE_UID}\n"
            "      type: prometheus\n"
            "      access: proxy\n"
            "      url: http://prometheus:9090\n"
            "      isDefault: true\n"
            "      editable: false\n"
            "      version: 1\n"
            "    - name: Unauthorized\n"
            "      type: prometheus\n"
        )
        datasource_path.write_text(four_space_datasources, encoding="utf-8")
        expect_invalid(
            "four-space two datasource exact count",
            lambda: validate_datasource(datasource_path),
            "exactly one object, got 2",
        )
        datasource_path.write_text(datasource_text, encoding="utf-8")

        six_space_providers = (
            "apiVersion: 1\nproviders:\n"
            "      - name: FinGuardOps Local\n"
            "        orgId: 1\n"
            "        folder: FinGuardOps Local\n"
            "        type: file\n"
            "        disableDeletion: false\n"
            "        allowUiUpdates: false\n"
            "        updateIntervalSeconds: 10\n"
            "        options:\n"
            "          path: /etc/grafana/dashboards\n"
            "          foldersFromFilesStructure: false\n"
            "      - name: Unauthorized\n"
            "        type: file\n"
        )
        provider_path.write_text(six_space_providers, encoding="utf-8")
        expect_invalid(
            "six-space two provider exact count",
            lambda: validate_provider(provider_path),
            "exactly one provider, got 2",
        )
        provider_path.write_text(provider_text, encoding="utf-8")

        nested_datasource_dir = datasource_dir / "nested"
        nested_provider_dir = provider_dir / "nested"
        nested_dashboard_dir = dashboard_dir / "nested"
        nested_datasource_dir.mkdir()
        nested_provider_dir.mkdir()
        nested_dashboard_dir.mkdir()
        extra_datasource_path = nested_datasource_dir / "unauthorized.yml"
        extra_datasource_path.write_text(
            "apiVersion: 1\ndatasources:\n    - name: Unauthorized\n      type: prometheus\n",
            encoding="utf-8",
        )
        expect_invalid(
            "nested extra datasource YAML",
            lambda: validate_datasource(datasource_path),
            "exactly one object, got 2",
        )
        extra_datasource_path.unlink()

        extra_datasource_yaml_path = nested_datasource_dir / "unauthorized.yaml"
        extra_datasource_yaml_path.write_text(
            "apiVersion: 1\ndatasources:\n      - name: Unauthorized\n        type: prometheus\n",
            encoding="utf-8",
        )
        expect_invalid(
            "nested extra datasource .yaml",
            lambda: validate_datasource(datasource_path),
            "exactly one object, got 2",
        )
        extra_datasource_yaml_path.unlink()

        extra_provider_path = nested_provider_dir / "unauthorized.yml"
        extra_provider_path.write_text(
            "apiVersion: 1\nproviders:\n    - name: Unauthorized\n      type: file\n",
            encoding="utf-8",
        )
        expect_invalid(
            "nested extra provider YAML",
            lambda: validate_provider(provider_path),
            "exactly one provider, got 2",
        )
        extra_provider_path.unlink()

        uppercase_datasource_path = nested_datasource_dir / "unauthorized.YML"
        uppercase_datasource_path.write_text(
            "apiVersion: 1\ndatasources:\n  - name: Unauthorized\n",
            encoding="utf-8",
        )
        expect_invalid(
            "uppercase provisioning extension",
            lambda: validate_datasource(datasource_path),
            "extensions must be lowercase",
        )
        uppercase_datasource_path.unlink()

        extra_dashboard_path = nested_dashboard_dir / "unauthorized.json"
        write_dashboard(extra_dashboard_path, source_dashboard)
        expect_invalid(
            "nested extra dashboard JSON",
            lambda: validate_static_paths(dashboard_path, datasource_path, provider_path),
            "exactly one JSON file, got 2",
        )
        extra_dashboard_path.unlink()

        dashboard_path.unlink()
        expect_invalid(
            "zero dashboard JSON",
            lambda: validate_static_paths(dashboard_path, datasource_path, provider_path),
            "dashboard JSON was not found",
        )
        write_dashboard(dashboard_path, source_dashboard)
        validate_static_paths(dashboard_path, datasource_path, provider_path)
        print("recursive dashboard regression passed: exactly one dashboard JSON")

    valid_datasources = [{"uid": DATASOURCE_UID}]
    valid_dashboards = [{"uid": DASHBOARD_UID, "type": "dash-db"}]
    valid_folders = [{"title": FOLDER_TITLE, "type": "dash-folder"}]
    validate_runtime_inventory(valid_datasources, valid_dashboards, valid_folders)
    expect_invalid(
        "runtime extra datasource",
        lambda: validate_runtime_inventory(
            [*valid_datasources, {"uid": "unauthorized"}],
            valid_dashboards,
            valid_folders,
        ),
    )
    expect_invalid(
        "runtime extra dashboard",
        lambda: validate_runtime_inventory(
            valid_datasources,
            [*valid_dashboards, {"uid": "unauthorized", "type": "dash-db"}],
            valid_folders,
        ),
    )

    time_field = {"name": "Time", "type": "time"}
    number_field = {"name": "Value", "type": "number"}
    string_field = {"name": "Text", "type": "string"}

    def frame(fields: list[Any], values: list[Any]) -> dict[str, Any]:
        return {"schema": {"fields": fields}, "data": {"values": values}}

    def result(*frames: Any) -> dict[str, Any]:
        return {"frames": list(frames)}

    empty_frame = frame([], [])
    empty_number_frame = frame([time_field, number_field], [[1], []])
    numeric_frame = frame([time_field, number_field], [[1], [2.5]])
    mismatch_frame = frame([time_field, number_field], [[1]])
    numeric_matrix = (
        ("all frames empty", result(empty_frame, empty_number_frame), False),
        ("timestamp only", result(frame([time_field], [[1]])), False),
        ("timestamp and null", result(frame([time_field, number_field], [[1], [None]])), False),
        (
            "timestamp and numeric string",
            result(frame([time_field, number_field], [[1], ["1"]])),
            False,
        ),
        ("timestamp and NaN", result(frame([time_field, number_field], [[1], [math.nan]])), False),
        (
            "timestamp and Infinity",
            result(frame([time_field, number_field], [[1], [math.inf]])),
            False,
        ),
        ("timestamp and bool", result(frame([time_field, number_field], [[1], [True]])), False),
        ("zero", result(frame([time_field, number_field], [[1], [0.0]])), True),
        ("positive", result(numeric_frame), True),
        ("valid numeric then valid empty", result(numeric_frame, empty_frame), True),
        ("valid empty then valid numeric", result(empty_frame, numeric_frame), True),
        (
            "one finite numeric among valid frames",
            result(
                empty_number_frame,
                frame([time_field, string_field], [[2], ["x"]]),
                numeric_frame,
            ),
            True,
        ),
        ("time and string only", result(frame([time_field, string_field], [[1], ["x"]])), False),
    )
    for name, payload, expected in numeric_matrix:
        require(result_has_samples(payload) is expected, f"numeric sample regression failed: {name}")
        print(f"numeric sample regression passed: {name}")

    malformed_matrix = (
        ("valid numeric then field/value mismatch", result(numeric_frame, mismatch_frame)),
        ("valid numeric then non-object frame", result(numeric_frame, "invalid")),
        ("field/value mismatch then valid numeric", result(mismatch_frame, numeric_frame)),
        ("non-object frame then valid numeric", result("invalid", numeric_frame)),
        ("missing schema", result({"data": {"values": []}})),
        ("missing data", result({"schema": {"fields": []}})),
        ("non-array fields", result({"schema": {"fields": {}}, "data": {"values": []}})),
        ("non-array values", result({"schema": {"fields": []}, "data": {"values": {}}})),
        ("non-object field", result(frame(["invalid"], [[]]))),
        ("non-array field values", result(frame([number_field], [0.0]))),
    )
    for name, payload in malformed_matrix:
        expect_invalid(name, lambda payload=payload: result_has_samples(payload))

    invalid_urls = (
        "https://127.0.0.1:3000",
        "ftp://127.0.0.1:3000",
        "http://localhost:3000",
        "http://0.0.0.0:3000",
        "http://192.0.2.1:3000",
        "http://example.com:3000",
        "http://[::1]:3000",
        "http://user:password@127.0.0.1:3000",
        "http://127.0.0.1:3000?query=1",
        "http://127.0.0.1:3000#fragment",
        "http://127.0.0.1:3000/grafana",
        "http://127.0.0.1",
        "http://127.0.0.1:0",
        "http://127.0.0.1:65536",
    )
    for value in invalid_urls:
        try:
            normalize_grafana_url(value)
        except argparse.ArgumentTypeError:
            print(f"CLI boundary rejected before HTTP: {value}")
        else:
            raise ValidationError(f"CLI URL boundary accepted: {value}")
    require(
        normalize_grafana_url("http://127.0.0.1:3000/") == "http://127.0.0.1:3000",
        "Grafana URL normalization failed",
    )
    for parser, values in (
        (http_timeout, ("0", "-1", "61", "nan", "inf")),
        (deadline_seconds, ("0", "-1", "1801", "nan", "inf")),
    ):
        for value in values:
            try:
                parser(value)
            except argparse.ArgumentTypeError:
                print(f"CLI timeout boundary rejected before HTTP: {value}")
            else:
                raise ValidationError(f"CLI timeout boundary accepted: {value}")


def common_paths(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--dashboard", type=Path, default=DEFAULT_DASHBOARD)
    parser.add_argument("--datasource", type=Path, default=DEFAULT_DATASOURCE)
    parser.add_argument("--provider", type=Path, default=DEFAULT_PROVIDER)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="mode", required=True)

    static_parser = subparsers.add_parser("static", help="validate source contracts")
    common_paths(static_parser)

    runtime_parser = subparsers.add_parser("runtime", help="validate a running Grafana")
    common_paths(runtime_parser)
    runtime_parser.add_argument(
        "--grafana-url",
        type=normalize_grafana_url,
        default="http://127.0.0.1:3000",
    )
    runtime_parser.add_argument("--http-timeout", type=http_timeout, default=5.0)
    runtime_parser.add_argument("--deadline-seconds", type=deadline_seconds, default=120.0)
    runtime_parser.add_argument(
        "--allow-empty-recording",
        action="store_true",
        help="permit empty recording queries before test traffic has been generated",
    )
    runtime_parser.add_argument(
        "--require-alert-state",
        choices=("pending", "firing", "inactive"),
    )

    mutation_parser = subparsers.add_parser("mutations", help="prove failure boundaries")
    common_paths(mutation_parser)
    args = parser.parse_args()
    if args.mode == "runtime" and args.deadline_seconds < args.http_timeout:
        runtime_parser.error("--deadline-seconds must be greater than or equal to --http-timeout")
    return args


def main() -> int:
    args = parse_args()
    try:
        if args.mode == "static":
            validate_static_paths(args.dashboard, args.datasource, args.provider)
            print("static validation passed: datasource, provider, 16 dashboard panels")
        elif args.mode == "runtime":
            validate_static_paths(args.dashboard, args.datasource, args.provider)
            validate_runtime(args)
            print("runtime validation passed: provisioning, datasource query, dashboard")
        else:
            validate_static_paths(args.dashboard, args.datasource, args.provider)
            validate_mutations(args)
            print("mutation validation passed: static, runtime, numeric, and CLI boundaries")
    except (ValidationError, OSError, TimeoutError) as error:
        print(f"validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

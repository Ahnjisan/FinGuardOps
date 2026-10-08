#!/usr/bin/env python3
"""Read three bounded counters without exposing request data."""
import json
import re
import urllib.request

body = urllib.request.urlopen("http://127.0.0.1:8081/actuator/prometheus", timeout=10).read().decode()
series = {"kafkaStarts": ("finguardops_ai_report_starts_total", "source", "kafka"),
          "pollingStarts": ("finguardops_ai_report_starts_total", "source", "polling"),
          "kafkaRecordsStarted": ("finguardops_kafka_records_total", "result", "started")}
result = {}
for key, (name, label, value) in series.items():
    pattern = re.compile(r"^" + re.escape(name) + r"\{" + label + r'="' + value + r'"\}\s+([^\s]+)')
    matches = [float(match.group(1)) for line in body.splitlines()
               if (match := pattern.match(line))]
    if len(matches) > 1:
        raise RuntimeError("ambiguous metric series")
    result[key] = matches[0] if matches else 0.0
print(json.dumps(result, separators=(",", ":")))

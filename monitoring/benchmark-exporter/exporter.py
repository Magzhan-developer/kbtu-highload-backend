"""Publishes benchmark result files as Prometheus metrics, for Grafana.

The CSV files stay the source of truth. This process never writes to them, and it re-reads
them on every scrape, so a new run appears by itself and nothing is lost if Prometheus is
wiped. Each run adds a few hundred series at most: results are per operation, not per request.

Uses only the Python standard library.
"""

import csv
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

RESULTS_DIRECTORY = Path(os.environ.get("RESULTS_DIRECTORY", "/results"))
PORT = int(os.environ.get("PORT", "9188"))

REST = "REST"
GRPC = "gRPC"

SUMMARY_METRICS = {
    "throughput_rps": "median_throughput_rps",
    "throughput_min_rps": "min_throughput_rps",
    "throughput_max_rps": "max_throughput_rps",
    "avg_ms": "median_avg_ms",
    "p50_ms": "median_p50_ms",
    "p95_ms": "median_p95_ms",
    "p99_ms": "median_p99_ms",
    "p99_min_ms": "min_p99_ms",
    "p99_max_ms": "max_p99_ms",
    "error_rate": "error_rate",
    "failures": "total_failures",
    "retries": "total_retries",
    "requests": "total_requests",
    "request_bytes": "avg_request_payload_bytes",
    "response_bytes": "avg_response_payload_bytes",
    "total_bytes": "avg_total_payload_bytes",
}

ITERATION_METRICS = {
    "throughput_rps": "throughput_rps",
    "p50_ms": "p50_ms",
    "p95_ms": "p95_ms",
    "p99_ms": "p99_ms",
    "max_ms": "max_ms",
    "failures": "failures",
    "retries": "retries",
}

RUN_NUMBERS = ("users", "posts", "comments", "read_requests", "write_requests",
               "warmup_requests", "iterations", "sample_size", "page_limit", "cores")

INCIDENT_NUMBERS = ("client_failures", "client_retries", "committed_despite_client_failure",
                    "resent_from_journal", "new_rows_after_resend", "duplicate_rows")


def escape(value):
    return str(value).replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def sample(name, labels, value):
    rendered = ",".join(f'{key}="{escape(val)}"' for key, val in labels.items())
    return f"{name}{{{rendered}}} {value}"


def read_csv(path):
    if not path.exists():
        return []
    with path.open(newline="", encoding="utf-8") as file:
        return list(csv.DictReader(file))


def read_json(path):
    if not path.exists():
        return {}
    with path.open(encoding="utf-8") as file:
        return json.load(file)


def number(value):
    """Returns a float, or None for a value that is missing rather than zero."""
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def scenario(row):
    return f'{row["operation"]} c{row["concurrency"]}'


def run_lines(run_id, metadata):
    labels = {
        "run_id": run_id,
        "label": metadata.get("label", ""),
        "started_at": metadata.get("started_at", "unavailable"),
        "finished_at": metadata.get("finished_at", "unavailable"),
        "concurrency_levels": ",".join(str(c) for c in metadata.get("concurrency_levels", [])),
    }
    yield sample("benchmark_run_info", labels, 1)
    for field in RUN_NUMBERS:
        value = number(metadata.get(field))
        if value is not None:
            yield sample(f"benchmark_run_{field}", {"run_id": run_id}, value)


def summary_lines(run_id, rows):
    for row in rows:
        labels = {"run_id": run_id, "protocol": row["protocol"], "operation": row["operation"],
                  "category": row["category"], "concurrency": row["concurrency"], "scenario": scenario(row)}
        for metric, column in SUMMARY_METRICS.items():
            value = number(row.get(column))
            if value is not None:
                yield sample(f"benchmark_summary_{metric}", labels, value)


def iteration_lines(run_id, rows):
    for row in rows:
        labels = {"run_id": run_id, "protocol": row["protocol"], "operation": row["operation"],
                  "concurrency": row["concurrency"], "iteration": f'it{row["iteration"]}', "scenario": scenario(row)}
        for metric, column in ITERATION_METRICS.items():
            value = number(row.get(column))
            if value is not None:
                yield sample(f"benchmark_iteration_{metric}", labels, value)


def percent_change(value, baseline):
    if value is None or not baseline:
        return None
    return (value - baseline) / baseline * 100


def comparison_lines(run_id, rows):
    """gRPC relative to REST. Positive always favours gRPC, for easier reading on charts."""
    by_scenario = {}
    for row in rows:
        by_scenario.setdefault((row["operation"], row["concurrency"]), {})[row["protocol"]] = row
    for (operation, concurrency), protocols in by_scenario.items():
        rest, grpc = protocols.get(REST), protocols.get(GRPC)
        if rest is None or grpc is None:
            continue
        labels = {"run_id": run_id, "operation": operation, "concurrency": concurrency, "scenario": scenario(rest)}
        gain = percent_change(number(grpc["median_throughput_rps"]), number(rest["median_throughput_rps"]))
        if gain is not None:
            yield sample("benchmark_comparison_throughput_gain_percent", labels, gain)
        for metric, column in (("p50", "median_p50_ms"), ("p95", "median_p95_ms"), ("p99", "median_p99_ms"),
                               ("response_bytes", "avg_response_payload_bytes")):
            change = percent_change(number(grpc[column]), number(rest[column]))
            if change is not None:
                yield sample(f"benchmark_comparison_{metric}_reduction_percent", labels, -change)


def serialization_lines(run_id, rows):
    by_payload = {}
    for row in rows:
        labels = {"run_id": run_id, "format": row["format"], "payload": row["payload"]}
        yield sample("benchmark_serialization_bytes", labels, row["bytes"])
        yield sample("benchmark_serialization_serialize_micros", labels, row["serialize_micros"])
        yield sample("benchmark_serialization_parse_micros", labels, row["parse_micros"])
        by_payload.setdefault(row["payload"], {})[row["format"]] = row
    for payload, formats in by_payload.items():
        json_row, protobuf_row = formats.get("JSON"), formats.get("Protobuf")
        if json_row is None or protobuf_row is None:
            continue
        for step, column in (("serialize", "serialize_micros"), ("parse", "parse_micros")):
            baseline = number(protobuf_row[column])
            if baseline:
                labels = {"run_id": run_id, "payload": payload, "step": step}
                yield sample("benchmark_serialization_speedup_ratio", labels, number(json_row[column]) / baseline)


def incident_lines(run_id, incident):
    observed = incident.get("observed", {})
    for field in INCIDENT_NUMBERS:
        value = number(observed.get(field))
        if value is not None:
            yield sample(f"benchmark_incident_{field}", {"run_id": run_id}, value)


def render(root=RESULTS_DIRECTORY):
    lines = ["# Generated from benchmark result files. The CSV files are the source of truth."]
    if not root.exists():
        return "\n".join(lines) + "\n"
    run_count = 0
    for directory in sorted(path for path in root.iterdir() if path.is_dir()):
        summary = read_csv(directory / "summary.csv")
        if not summary:
            continue  # Unfinished: summary.csv is written last, when a run has complete results.
        run_id = directory.name
        run_count += 1
        lines.extend(run_lines(run_id, read_json(directory / "run-metadata.json")))
        lines.extend(summary_lines(run_id, summary))
        lines.extend(iteration_lines(run_id, read_csv(directory / "raw-runs.csv")))
        lines.extend(comparison_lines(run_id, summary))
        lines.extend(serialization_lines(run_id, read_csv(directory / "serialization-probe.csv")))
        lines.extend(incident_lines(run_id, read_json(directory / "incident.json")))
    lines.append(f"benchmark_runs_total {run_count}")
    return "\n".join(lines) + "\n"


class Handler(BaseHTTPRequestHandler):

    def do_GET(self):
        if self.path == "/metrics":
            self.respond(200, render(), "text/plain; version=0.0.4; charset=utf-8")
        elif self.path == "/health":
            self.respond(200, "ok\n", "text/plain")
        else:
            self.respond(404, "not found\n", "text/plain")

    def respond(self, status, body, content_type):
        encoded = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("", PORT), Handler).serve_forever()

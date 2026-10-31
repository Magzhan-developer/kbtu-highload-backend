"""Generates dashboards/rest-vs-grpc.json. Edit this file, then run it: python generate_dashboard.py"""

import json
from pathlib import Path

OUTPUT = Path(__file__).parent / "dashboards" / "rest-vs-grpc.json"
DATASOURCE = {"type": "prometheus", "uid": "prometheus"}

REST_COLOR = "#FF9830"
GRPC_COLOR = "#5794F2"
GOOD_COLOR = "#73BF69"
BAD_COLOR = "#F2495C"
NEUTRAL_COLOR = "#B877D9"

SCOPE = 'run_id=~"$run",concurrency=~"$concurrency",operation=~"$operation",protocol=~"$protocol"'
COMPARISON_SCOPE = 'run_id=~"$run",concurrency=~"$concurrency",operation=~"$operation"'
FOCUS = 'run_id=~"$run",concurrency=~"$concurrency",operation="$focus_operation",protocol=~"$protocol"'
API_URI = 'uri=~"/api/.*"'


class Layout:
    """Places panels left to right, wrapping into new lines of the 24-column grid."""

    def __init__(self):
        self.panels = []
        self.x = 0
        self.y = 0
        self.line_height = 0
        self.next_id = 1

    def add(self, panel, width, height):
        if self.x + width > 24:
            self.y += self.line_height
            self.x = 0
            self.line_height = 0
        panel["id"] = self.next_id
        self.next_id += 1
        panel["gridPos"] = {"x": self.x, "y": self.y, "w": width, "h": height}
        self.panels.append(panel)
        self.x += width
        self.line_height = max(self.line_height, height)

    def row(self, title):
        self.y += self.line_height
        self.x = 0
        self.line_height = 0
        self.add({"type": "row", "title": title, "collapsed": False, "panels": []}, 24, 1)
        self.y += 1
        self.x = 0
        self.line_height = 0


def target(expr, legend="", ref="A", instant=False, table=False, heatmap=False):
    item = {"datasource": DATASOURCE, "expr": expr, "refId": ref, "legendFormat": legend or "__auto",
            "instant": instant, "range": not instant, "editorMode": "code"}
    if table:
        item["format"] = "table"
    if heatmap:
        item["format"] = "heatmap"
    return item


def color_override(name, color):
    return {"matcher": {"id": "byName", "options": name},
            "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": color}}]}


PROTOCOL_COLORS = [color_override("REST", REST_COLOR), color_override("gRPC", GRPC_COLOR)]
FORMAT_COLORS = [color_override("JSON", REST_COLOR), color_override("Protobuf", GRPC_COLOR)]


def text(content, title=""):
    return {"type": "text", "title": title, "transparent": not title,
            "options": {"mode": "markdown", "content": content}}


def stat(title, expr, unit="short", color=GRPC_COLOR, decimals=0, description="", no_value="No data"):
    return {"type": "stat", "title": title, "description": description, "datasource": DATASOURCE,
            "targets": [target(expr, instant=True)],
            "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                        "colorMode": "value", "graphMode": "none", "justifyMode": "center",
                        "textMode": "value", "orientation": "auto"},
            "fieldConfig": {"defaults": {"unit": unit, "decimals": decimals, "noValue": no_value,
                                         "color": {"mode": "fixed", "fixedColor": color}},
                            "overrides": []}}


def bars(title, expr, row, column, unit="short", decimals=1, description="", overrides=None, rotate=-25,
         signed=False, soft_max=None):
    """A grouped bar chart from an instant query: one group per row label, one bar per column label."""
    defaults = {"unit": unit, "decimals": decimals, "custom": {"fillOpacity": 85, "lineWidth": 0}}
    if soft_max is not None:
        # All-zero data would otherwise get a meaningless automatic axis.
        defaults["custom"]["axisSoftMax"] = soft_max
    if signed:
        defaults["custom"]["axisCenteredZero"] = False
        defaults["custom"]["thresholdsStyle"] = {"mode": "dashed"}
        defaults["thresholds"] = {"mode": "absolute", "steps": [{"color": "text", "value": None}, {"color": "text", "value": 0}]}
    return {"type": "barchart", "title": title, "description": description, "datasource": DATASOURCE,
            "targets": [target(expr, instant=True, table=True)],
            "transformations": [{"id": "groupingToMatrix",
                                 "options": {"columnField": column, "rowField": row, "valueField": "Value",
                                             "emptyValue": "null"}}],
            "options": {"orientation": "vertical", "xTickLabelRotation": rotate, "xTickLabelSpacing": 0,
                        "showValue": "auto", "stacking": "none", "groupWidth": 0.72, "barWidth": 0.92,
                        "barRadius": 0.03, "legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
                        "tooltip": {"mode": "multi", "sort": "none"}},
            "fieldConfig": {"defaults": defaults, "overrides": overrides or []}}


def joined(metric, scope=SCOPE, labels=("scenario", "run_id")):
    label_list = ", ".join(f'"{label}"' for label in labels)
    return f'label_join({metric}{{{scope}}}, "series", " · ", {label_list})'


def protocol_bars(title, metric, unit, decimals=1, description="", scope=SCOPE, labels=("scenario", "run_id"),
                  soft_max=None):
    return bars(title, joined(metric, scope, labels), "series", "protocol", unit, decimals, description, PROTOCOL_COLORS,
                soft_max=soft_max)


def timeseries(title, targets, unit="short", decimals=None, description="", overrides=None, minimum=None):
    defaults = {"unit": unit, "custom": {"lineWidth": 2, "fillOpacity": 12, "showPoints": "never",
                                         "spanNulls": False}}
    if decimals is not None:
        defaults["decimals"] = decimals
    if minimum is not None:
        defaults["min"] = minimum
    return {"type": "timeseries", "title": title, "description": description, "datasource": DATASOURCE,
            "targets": targets,
            "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
                        "tooltip": {"mode": "multi", "sort": "desc"}},
            "fieldConfig": {"defaults": defaults, "overrides": overrides or []}}


def heatmap(title, expr, description=""):
    return {"type": "heatmap", "title": title, "description": description, "datasource": DATASOURCE,
            "targets": [target(expr, "{{le}}", heatmap=True)],
            "options": {"calculate": False, "cellGap": 1, "color": {"scheme": "Spectral", "mode": "scheme", "steps": 64},
                        "yAxis": {"unit": "s"}, "tooltip": {"mode": "single", "yHistogram": False},
                        "legend": {"show": True}},
            "fieldConfig": {"defaults": {}, "overrides": []}}


def run_details_table():
    columns = [("posts", "Posts"), ("comments", "Comments"), ("users", "Users"), ("read_requests", "Reads per run"),
               ("write_requests", "Writes per run"), ("warmup_requests", "Warm-up"), ("iterations", "Iterations")]
    targets = [target('benchmark_run_info{run_id=~"$run"}', instant=True, table=True, ref="A")]
    rename = {"run_id": "Run", "label": "Note", "started_at": "Started (UTC)", "concurrency_levels": "Clients"}
    for index, (metric, name) in enumerate(columns):
        ref = chr(ord("B") + index)
        targets.append(target(f'benchmark_run_{metric}{{run_id=~"$run"}}', instant=True, table=True, ref=ref))
        rename[f"Value #{ref}"] = name
    return {"type": "table", "title": "Benchmark runs (exact values from run-metadata.json)", "datasource": DATASOURCE,
            "targets": targets,
            "transformations": [{"id": "joinByField", "options": {"byField": "run_id", "mode": "outer"}},
                                {"id": "filterFieldsByName", "options": {"include": {
                                    "pattern": "^(run_id|label|started_at|concurrency_levels|Value #[B-H])$"}}},
                                {"id": "organize",
                                 "options": {"excludeByName": {},
                                             "indexByName": {"run_id": 0, "label": 1, "started_at": 2, "Value #B": 3,
                                                             "Value #C": 4, "Value #D": 5, "concurrency_levels": 6,
                                                             "Value #E": 7, "Value #F": 8, "Value #G": 9, "Value #H": 10},
                                             "renameByName": rename}}],
            "options": {"showHeader": True, "cellHeight": "sm"},
            "fieldConfig": {"defaults": {"unit": "locale", "decimals": 0, "custom": {"align": "auto"}},
                            "overrides": [
                                {"matcher": {"id": "byName", "options": "Note"},
                                 "properties": [{"id": "custom.width", "value": 270}]},
                                {"matcher": {"id": "byName", "options": "Started (UTC)"},
                                 "properties": [{"id": "custom.width", "value": 250}]}]}}


def overview(layout):
    layout.add(text(
        "### What this dashboard compares\n"
        "Every benchmark value here comes from the CSV files in `benchmark-results/`. The two sides differ in "
        "**transport** (HTTP/2 multiplexed vs HTTP/1.1), **serialization** (Protobuf vs JSON) and **framework** "
        "(grpc-java on Netty vs Spring MVC on Tomcat). These results measure the combination, so a gap cannot be "
        "credited to Protobuf alone. Client, server and PostgreSQL shared one 8-core laptop, so this is not a "
        "capacity test."), 24, 4)
    layout.add(run_details_table(), 24, 5)

    def mean(metric, protocol):
        return f'avg({metric}{{run_id=~"$run",concurrency=~"$concurrency",operation=~"$operation",protocol="{protocol}"}})'

    def total(metric, protocol):
        return f'sum({metric}{{run_id=~"$run",concurrency=~"$concurrency",operation=~"$operation",protocol="{protocol}"}})'

    note = "Mean of the median values for the selected runs, operations and client counts."
    throughput = "benchmark_summary_throughput_rps"
    p99 = "benchmark_summary_p99_ms"
    response = "benchmark_summary_response_bytes"
    # A fixed suffix, because "reqps" would round the headline numbers to "2K req/s".
    layout.add(stat("REST throughput", mean(throughput, "REST"), "suffix: req/s", REST_COLOR, 0, note), 4, 5)
    layout.add(stat("gRPC throughput", mean(throughput, "gRPC"), "suffix: req/s", GRPC_COLOR, 0, note), 4, 5)
    layout.add(stat("REST p99 latency", mean(p99, "REST"), "ms", REST_COLOR, 1, note), 4, 5)
    layout.add(stat("gRPC p99 latency", mean(p99, "gRPC"), "ms", GRPC_COLOR, 1, note), 4, 5)
    layout.add(stat("Throughput improvement", f'100 * ({mean(throughput, "gRPC")} / {mean(throughput, "REST")} - 1)',
                    "percent", GOOD_COLOR, 1, "gRPC relative to REST on the mean values shown beside this panel."), 4, 5)
    layout.add(stat("Payload size reduction", f'100 * (1 - {total(response, "gRPC")} / {total(response, "REST")})',
                    "percent", GOOD_COLOR, 1,
                    "Response bodies only, not network traffic. Excludes headers and framing."), 4, 5)


def throughput_section(layout):
    layout.add(protocol_bars("Throughput by operation and client count", "benchmark_summary_throughput_rps", "suffix: req/s", 0,
                             "Median across iterations."), 24, 9)
    layout.add(protocol_bars("16 clients vs 64 clients: $focus_operation", "benchmark_summary_throughput_rps", "suffix: req/s", 0,
                             "Choose the operation with the Focus operation variable.", FOCUS), 8, 9)
    layout.add(protocol_bars("Every iteration: $focus_operation", "benchmark_iteration_throughput_rps", "suffix: req/s", 0,
                             "One bar per iteration. Compare the spread within a protocol with the gap between protocols.",
                             FOCUS, ("scenario", "iteration", "run_id")), 16, 9)
    layout.add(timeseries("Live requests per second (only while a benchmark or traffic is running)", [
        target(f"sum(rate(http_server_requests_seconds_count{{{API_URI}}}[30s]))", "REST"),
        target("sum(rate(grpc_server_requests_seconds_count[30s]))", "gRPC", "B"),
    ], "reqps", 0, "Measured on the server by Prometheus. Runs 1 and 2 predate this instrumentation and are not shown here.",
        PROTOCOL_COLORS), 24, 8)
    layout.add(bars("Throughput gain of gRPC over REST", joined("benchmark_comparison_throughput_gain_percent", COMPARISON_SCOPE,
                                                                ("scenario",)),
                    "series", "run_id", "percent", 1, "Positive favours gRPC. Compare across runs before trusting one bar.",
                    signed=True), 24, 8)


def latency_section(layout):
    for percentile in ("p50", "p95", "p99"):
        layout.add(protocol_bars(f"{percentile} latency by operation", f"benchmark_summary_{percentile}_ms", "ms", 1,
                                 "Median across iterations. Lower is better."), 12, 8)
    layout.add(bars("Latency reduction with gRPC (p50)", joined("benchmark_comparison_p50_reduction_percent", COMPARISON_SCOPE,
                                                                ("scenario",)),
                    "series", "run_id", "percent", 1, "Positive means gRPC is faster.", signed=True), 12, 8)
    layout.add(bars("Latency reduction with gRPC (p99)", joined("benchmark_comparison_p99_reduction_percent", COMPARISON_SCOPE,
                                                                ("scenario",)),
                    "series", "run_id", "percent", 1, "Positive means gRPC is faster. Negative bars are real: p99 was mixed.",
                    signed=True), 12, 8)
    layout.add(text(
        "### Latency distribution\n"
        "**Runs 1 and 2: unavailable.** The benchmark saved percentiles, not individual samples, so their distribution "
        "cannot be drawn. The percentile charts above show what was recorded.\n\n"
        "**New traffic:** the heatmaps below use server-side histograms and fill in while requests run."), 12, 8)
    layout.add(heatmap("Live REST latency distribution",
                       f"sum by (le) (increase(http_server_requests_seconds_bucket{{{API_URI}}}[30s]))"), 12, 8)
    layout.add(heatmap("Live gRPC latency distribution",
                       "sum by (le) (increase(grpc_server_requests_seconds_bucket[30s]))"), 12, 8)
    layout.add(timeseries("Live latency percentiles", [
        target(f"1000 * histogram_quantile({q}, sum by (le) (rate(http_server_requests_seconds_bucket{{{API_URI}}}[30s])))",
               f"REST p{int(q * 100)}", ref) for q, ref in ((0.5, "A"), (0.95, "B"), (0.99, "C"))
    ] + [
        target(f"1000 * histogram_quantile({q}, sum by (le) (rate(grpc_server_requests_seconds_bucket[30s])))",
               f"gRPC p{int(q * 100)}", ref) for q, ref in ((0.5, "D"), (0.95, "E"), (0.99, "F"))
    ], "ms", 1, "Estimated from histogram buckets on the server. Benchmark values above are measured on the client, so "
                "the two will not match exactly."), 24, 8)


def payload_section(layout):
    layout.add(protocol_bars("Average response size", "benchmark_summary_response_bytes", "bytes", 0,
                             "Serialized body only. Headers, gRPC framing, HTTP/2 frames and TCP are excluded. Network "
                             "bytes were not measured."), 16, 8)
    layout.add(bars("Response size reduction", joined("benchmark_comparison_response_bytes_reduction_percent",
                                                      COMPARISON_SCOPE, ("scenario",)),
                    "series", "run_id", "percent", 1, "Protobuf saves on field names and numbers. Text costs the same in both."),
               8, 8)
    run_scope = 'run_id=~"$run"'
    layout.add(bars("Serialize time, JSON vs Protobuf",
                    f'label_join(benchmark_serialization_serialize_micros{{{run_scope}}}, "series", " · ", "payload", "run_id")',
                    "series", "format", "µs", 1, "Isolated timing loop with no network, server or database. Indicative only.",
                    FORMAT_COLORS, 0), 8, 8)
    layout.add(bars("Parse time, JSON vs Protobuf",
                    f'label_join(benchmark_serialization_parse_micros{{{run_scope}}}, "series", " · ", "payload", "run_id")',
                    "series", "format", "µs", 1, "Isolated timing loop with no network, server or database. Indicative only.",
                    FORMAT_COLORS, 0), 8, 8)
    layout.add(bars("Protobuf speedup over JSON",
                    f'label_join(benchmark_serialization_speedup_ratio{{{run_scope}}}, "series", " · ", "payload", "run_id")',
                    "series", "step", "suffix: ×", 1, "How many times faster than JSON in the same timing loop.",
                    rotate=0), 8, 8)
    layout.add(text(
        "### Reading these together\n"
        "Protobuf was about 3-5× faster to serialize and 4-9× faster to parse, yet that saves only microseconds. In the "
        "published analysis, it accounts for about 0.4-13% of the median latency gap in the two operations checked. "
        "That estimate is rough. The rest of the gap is unattributed between transport and framework."), 24, 3)


def reliability_section(layout):
    run1 = 'run_id="run-1"'
    layout.add(stat("Client-side failures", f'benchmark_incident_client_failures{{{run1}}}', color=BAD_COLOR,
                    description="Run 1. Writes the client recorded as failed after all retries."), 4, 4)
    layout.add(stat("Committed despite failure", f'benchmark_incident_committed_despite_client_failure{{{run1}}}',
                    color=NEUTRAL_COLOR, description="Run 1. Found in idempotency_records before anything was resent."), 4, 4)
    layout.add(stat("Retry attempts", f'benchmark_incident_client_retries{{{run1}}}', color=REST_COLOR,
                    description="Run 1. Across both protocols in CreateComment with 64 clients."), 4, 4)
    layout.add(stat("Resent from journal", f'benchmark_incident_resent_from_journal{{{run1}}}', color=GRPC_COLOR,
                    description="Run 1. Using the original idempotency keys."), 4, 4)
    layout.add(stat("New rows after resend", f'benchmark_incident_new_rows_after_resend{{{run1}}}', color=GOOD_COLOR,
                    description="Run 1. 200 resent minus 143 already committed."), 4, 4)
    layout.add(stat("Duplicate rows", f'benchmark_incident_duplicate_rows{{{run1}}}', color=GOOD_COLOR, no_value="0",
                    description="Run 1. Row counts matched attempted writes exactly."), 4, 4)
    layout.add(text(
        "### Run 1: database stall during CreateComment with 64 clients\n"
        "**Observed facts**\n"
        "- A slowdown crossed about four consecutive measured runs, on **both** protocols, then recovered.\n"
        "- The server logged connection pool timeouts, all within about 2 seconds.\n"
        "- The client saw 200 failures, all gRPC, because gRPC happened to run first when the stall began.\n"
        "- 143 of those 200 writes had already been committed by the server.\n"
        "- Resending all 200 with their original keys added exactly 57 comments, with no duplicates.\n\n"
        "**Suspected, not proven**\n"
        "- Autovacuum and a checkpoint were active around the stall. This is a correlation only.\n"
        "- The stall did not repeat in run 2."), 12, 11)
    layout.add(bars("The stall, iteration by iteration", 'label_join(benchmark_iteration_throughput_rps'
                    '{run_id="run-1",operation="CreateComment",concurrency="64"}, "series", " · ", "iteration")',
                    "series", "protocol", "suffix: req/s", 0, "Run 1, CreateComment with 64 clients. Both protocols dropped.",
                    PROTOCOL_COLORS, 0), 12, 11)
    layout.add(protocol_bars("Error rate", "benchmark_summary_error_rate", "percentunit", 2,
                             "Failed logical requests after retries.", soft_max=0.01), 8, 9)
    layout.add(protocol_bars("Failed requests", "benchmark_summary_failures", "short", 0,
                             "Total across iterations, for the selected runs.", soft_max=10), 8, 9)
    layout.add(protocol_bars("Retry attempts", "benchmark_summary_retries", "short", 0,
                             "Total across iterations, for the selected runs.", soft_max=10), 8, 9)
    layout.add(text(
        "### Not available\n"
        "**Successful retries:** the benchmark counts retry attempts, not which of them eventually succeeded, so this "
        "number was never measured.\n\n"
        "**Idempotency outcomes for runs 1 and 2:** live counters did not exist yet. The run 1 figures above were counted "
        "from the database and journal."), 8, 8)
    layout.add(timeseries("Live idempotency outcomes (per second)", [
        target("sum by (outcome) (rate(blog_idempotency_outcomes_total[30s]))", "{{outcome}}"),
    ], "ops", 2, "created: new resource. replayed: same key, stored result returned. conflict: same key, different data."), 8, 8)
    layout.add(timeseries("Live error rate", [
        target(f'sum(rate(http_server_requests_seconds_count{{{API_URI},outcome!="SUCCESS"}}[30s])) or vector(0)', "REST"),
        target('sum(rate(grpc_server_requests_seconds_count{outcome="ERROR"}[30s])) or vector(0)', "gRPC", "B"),
    ], "reqps", 2, "Failed requests per second, seen by the server.", PROTOCOL_COLORS, 0), 8, 8)


def endpoints_section(layout):
    layout.add(timeseries("Live REST requests by endpoint", [
        target(f"sum by (method, uri) (rate(http_server_requests_seconds_count{{{API_URI}}}[30s]))", "{{method}} {{uri}}"),
    ], "reqps", 0), 12, 8)
    layout.add(timeseries("Live gRPC calls by method", [
        target("sum by (method) (rate(grpc_server_requests_seconds_count[30s]))", "{{method}}"),
    ], "reqps", 0), 12, 8)
    layout.add(timeseries("Live HTTP status codes", [
        target(f"sum by (status) (rate(http_server_requests_seconds_count{{{API_URI}}}[30s]))", "{{status}}"),
    ], "reqps", 0), 12, 7)
    layout.add(timeseries("Live gRPC status codes", [
        target("sum by (status) (rate(grpc_server_requests_seconds_count[30s]))", "{{status}}"),
    ], "reqps", 0), 12, 7)


def infrastructure_section(layout):
    layout.add(text("These panels show the application and database **now**. Use them during a benchmark to tell a protocol "
                    "difference from a bottleneck such as a saturated connection pool, garbage collection or full CPU."), 24, 2)
    layout.add(timeseries("JVM heap", [
        target('sum(jvm_memory_used_bytes{area="heap"})', "used"),
        target('sum(jvm_memory_committed_bytes{area="heap"})', "committed", "B"),
    ], "bytes"), 8, 7)
    layout.add(timeseries("JVM non-heap", [target('sum(jvm_memory_used_bytes{area="nonheap"})', "used")], "bytes"), 8, 7)
    layout.add(timeseries("CPU usage", [
        target("process_cpu_usage", "application"),
        target("system_cpu_usage", "whole machine", "B"),
    ], "percentunit", 0, minimum=0), 8, 7)
    layout.add(timeseries("Garbage collection pauses per second", [
        target("sum(rate(jvm_gc_pause_seconds_count[1m])) or vector(0)", "collections"),
    ], "ops", 2), 8, 7)
    layout.add(timeseries("Time paused in garbage collection", [
        target("sum(rate(jvm_gc_pause_seconds_sum[1m])) or vector(0)", "paused"),
    ], "percentunit", 2, "Share of time the JVM was paused."), 8, 7)
    layout.add(timeseries("JVM threads", [target("jvm_threads_live_threads", "live")], "short", 0), 4, 7)
    layout.add(stat("JVM uptime", "process_uptime_seconds", "s", NEUTRAL_COLOR, 0, no_value="Application not running"), 4, 7)
    layout.add(timeseries("Application connection pool", [
        target("hikaricp_connections_active", "active"),
        target("hikaricp_connections_idle", "idle", "B"),
        target("hikaricp_connections_pending", "waiting", "C"),
    ], "short", 0, "Waiting above zero means the pool is the bottleneck."), 8, 7)
    layout.add(timeseries("PostgreSQL connections", [
        target('sum by (state) (pg_stat_activity_count{datname="blog"})', "{{state}}"),
    ], "short", 0), 8, 7)
    layout.add(timeseries("PostgreSQL transactions per second", [
        target('rate(pg_stat_database_xact_commit{datname="blog"}[1m])', "commits"),
        target('rate(pg_stat_database_xact_rollback{datname="blog"}[1m])', "rollbacks", "B"),
    ], "ops", 0), 8, 7)
    layout.add(timeseries("PostgreSQL cache hit ratio", [
        target('rate(pg_stat_database_blks_hit{datname="blog"}[1m]) / (rate(pg_stat_database_blks_hit{datname="blog"}[1m]) '
               '+ rate(pg_stat_database_blks_read{datname="blog"}[1m]))', "hit ratio"),
    ], "percentunit", 1), 8, 7)
    layout.add(timeseries("PostgreSQL rows", [
        target('rate(pg_stat_database_tup_fetched{datname="blog"}[1m])', "fetched"),
        target('rate(pg_stat_database_tup_inserted{datname="blog"}[1m])', "inserted", "B"),
        target('rate(pg_stat_database_tup_updated{datname="blog"}[1m])', "updated", "C"),
    ], "ops", 0), 8, 7)
    layout.add(timeseries("PostgreSQL locks by mode", [
        target('sum by (mode) (pg_locks_count{datname="blog"})', "{{mode}}"),
    ], "short", 0), 8, 7)
    layout.add(stat("Database size", 'pg_database_size_bytes{datname="blog"}', "bytes", NEUTRAL_COLOR, 1), 4, 4)
    layout.add(stat("Deadlocks", 'pg_stat_database_deadlocks{datname="blog"}', "short", GOOD_COLOR, 0), 4, 4)
    container = 'name=~"blog-platform-.*"'
    layout.add(stat("Containers running", f"count(time() - container_last_seen{{{container}}} < 30)", "short", GOOD_COLOR, 0,
                    "Containers cAdvisor saw in the last 30 seconds."), 4, 4)
    layout.add(text("**Query performance: unavailable.** Per-query statistics need the `pg_stat_statements` extension, "
                    "which requires restarting PostgreSQL with a changed configuration. It was left off to keep the benchmark "
                    "database unchanged."), 12, 4)
    layout.add(timeseries("Container CPU (cores)", [
        target(f"sum by (name) (rate(container_cpu_usage_seconds_total{{{container}}}[1m]))", "{{name}}"),
    ], "short", 2), 8, 7)
    layout.add(timeseries("Container memory", [
        target(f"sum by (name) (container_memory_working_set_bytes{{{container}}})", "{{name}}"),
    ], "bytes"), 8, 7)
    layout.add(timeseries("Container network received", [
        target(f"sum by (name) (rate(container_network_receive_bytes_total{{{container}}}[1m]))", "{{name}}"),
    ], "Bps"), 8, 7)


def variable(name, label, query, multi=True, include_all=True, current=None):
    item = {"name": name, "label": label, "type": "query", "datasource": DATASOURCE,
            "query": {"query": query, "refId": name, "qryType": 1}, "definition": query,
            "refresh": 2, "sort": 1, "multi": multi, "includeAll": include_all, "options": [], "hide": 0}
    if include_all:
        item["allValue"] = ".*"
    item["current"] = current or {"selected": True, "text": ["All"], "value": ["$__all"]}
    return item


def build():
    layout = Layout()
    sections = [
        ("A. Executive overview", overview),
        ("B. Throughput comparison", throughput_section),
        ("C. Latency analysis", latency_section),
        ("D. Payload and serialization", payload_section),
        ("E. Reliability", reliability_section),
        ("Live traffic by endpoint and status", endpoints_section),
        ("F. Infrastructure", infrastructure_section),
    ]
    for title, section in sections:
        layout.row(title)
        section(layout)

    return {
        "uid": "blog-rest-vs-grpc",
        "title": "Blog Platform — REST vs gRPC Performance Benchmark",
        "description": "Measured REST and gRPC results from benchmark-results, with live application and database metrics.",
        "tags": ["benchmark", "grpc", "rest", "sis-2"],
        "schemaVersion": 41,
        "version": 1,
        "editable": True,
        "graphTooltip": 1,
        "style": "dark",
        "time": {"from": "now-30m", "to": "now"},
        "refresh": "10s",
        "timezone": "browser",
        "annotations": {"list": []},
        "links": [],
        "templating": {"list": [
            variable("run", "Run", "label_values(benchmark_run_info, run_id)", include_all=False,
                     current={"selected": True, "text": ["run-2"], "value": ["run-2"]}),
            variable("concurrency", "Clients", "label_values(benchmark_summary_throughput_rps, concurrency)"),
            variable("operation", "Operation", "label_values(benchmark_summary_throughput_rps, operation)"),
            variable("protocol", "Protocol", "label_values(benchmark_summary_throughput_rps, protocol)"),
            variable("focus_operation", "Focus operation", "label_values(benchmark_summary_throughput_rps, operation)",
                     multi=False, include_all=False, current={"selected": True, "text": "GetPost", "value": "GetPost"}),
        ]},
        "panels": layout.panels,
    }


if __name__ == "__main__":
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(build(), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {OUTPUT} with {len(build()['panels'])} panels")

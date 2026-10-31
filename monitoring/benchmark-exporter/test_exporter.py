"""Run with: python -m unittest discover -s monitoring/benchmark-exporter"""

import csv
import hashlib
import json
import re
import tempfile
import unittest
from pathlib import Path

import exporter

REPOSITORY = Path(__file__).resolve().parents[2]
RESULTS = REPOSITORY / "benchmark-results"


def value_of(metrics, name, **labels):
    for line in metrics.splitlines():
        if line.startswith(name + "{") and all(f'{key}="{val}"' in line for key, val in labels.items()):
            return float(line.rsplit(" ", 1)[1])
    raise AssertionError(f"{name} {labels} not found")


class RealResultsTest(unittest.TestCase):
    """Checks published values against the real CSV files, which are the source of truth."""

    @classmethod
    def setUpClass(cls):
        cls.metrics = exporter.render(RESULTS)

    def test_every_summary_value_matches_the_csv_exactly(self):
        for run in ("run-1", "run-2"):
            with (RESULTS / run / "summary.csv").open(newline="", encoding="utf-8") as file:
                for row in csv.DictReader(file):
                    labels = dict(run_id=run, protocol=row["protocol"], operation=row["operation"],
                                  concurrency=row["concurrency"])
                    for metric, column in exporter.SUMMARY_METRICS.items():
                        self.assertEqual(value_of(self.metrics, f"benchmark_summary_{metric}", **labels),
                                         float(row[column]), (run, labels, metric))

    def test_comparisons_match_the_published_analysis(self):
        gain = value_of(self.metrics, "benchmark_comparison_throughput_gain_percent",
                        run_id="run-2", operation="GetPost", concurrency="16")
        self.assertAlmostEqual(gain, 12.8, places=1)
        reduction = value_of(self.metrics, "benchmark_comparison_p50_reduction_percent",
                             run_id="run-2", operation="GetPost", concurrency="16")
        self.assertAlmostEqual(reduction, 11.0, places=1)

    def test_a_latency_increase_is_negative_so_it_cannot_look_like_a_win(self):
        reduction = value_of(self.metrics, "benchmark_comparison_p99_reduction_percent",
                             run_id="run-1", operation="GetAuthorPosts", concurrency="64")
        self.assertAlmostEqual(reduction, -24.9, places=1)

    def test_run_metadata_shows_the_actual_dataset(self):
        self.assertEqual(value_of(self.metrics, "benchmark_run_posts", run_id="run-1"), 1_005_600)
        self.assertEqual(value_of(self.metrics, "benchmark_run_comments", run_id="run-2"), 3_117_600)

    def test_incident_facts_are_published_only_for_the_run_that_had_one(self):
        self.assertEqual(value_of(self.metrics, "benchmark_incident_committed_despite_client_failure",
                                  run_id="run-1"), 143)
        self.assertNotIn('benchmark_incident_client_failures{run_id="run-2"}', self.metrics)

    def test_speedup_is_how_many_times_faster_than_json(self):
        ratio = value_of(self.metrics, "benchmark_serialization_speedup_ratio",
                         run_id="run-2", payload="20 posts", step="parse")
        self.assertAlmostEqual(ratio, 49.50 / 5.37, places=3)

    def test_output_is_valid_prometheus_text(self):
        pattern = re.compile(r'^[a-zA-Z_:][a-zA-Z0-9_:]*(\{.*\})? -?[0-9.e+-]+$')
        for line in self.metrics.splitlines():
            if not line.startswith("#"):
                self.assertRegex(line, pattern)

    def test_exporter_does_not_change_the_result_files(self):
        def fingerprint():
            return {path: hashlib.sha256(path.read_bytes()).hexdigest()
                    for path in RESULTS.rglob("*.csv")}

        before = fingerprint()
        exporter.render(RESULTS)
        self.assertEqual(fingerprint(), before)


class EdgeCaseTest(unittest.TestCase):

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def write_summary(self, run, rest="100", grpc="110"):
        folder = self.root / run
        folder.mkdir()
        fields = ["protocol", "operation", "category", "concurrency"] + list(exporter.SUMMARY_METRICS.values())
        with (folder / "summary.csv").open("w", newline="", encoding="utf-8") as file:
            writer = csv.DictWriter(file, fields)
            writer.writeheader()
            for protocol, throughput in ((exporter.REST, rest), (exporter.GRPC, grpc)):
                row = dict.fromkeys(fields, "1")
                row.update(protocol=protocol, operation="GetPost", category="read", concurrency="16",
                           median_throughput_rps=throughput)
                writer.writerow(row)
        return folder

    def test_missing_folder_gives_empty_output(self):
        self.assertNotIn("benchmark_summary", exporter.render(self.root / "absent"))

    def test_unfinished_run_is_skipped(self):
        (self.root / "partial").mkdir()
        (self.root / "partial" / "raw-runs.csv").write_text("protocol\nREST\n", encoding="utf-8")

        self.assertIn("benchmark_runs_total 0", exporter.render(self.root))

    def test_new_run_appears_without_a_restart(self):
        self.assertIn("benchmark_runs_total 0", exporter.render(self.root))
        self.write_summary("run-3")

        metrics = exporter.render(self.root)

        self.assertIn("benchmark_runs_total 1", metrics)
        self.assertEqual(value_of(metrics, "benchmark_comparison_throughput_gain_percent", run_id="run-3"), 10)

    def test_run_without_metadata_is_marked_unavailable_rather_than_zero(self):
        self.write_summary("run-3")

        metrics = exporter.render(self.root)

        self.assertIn('started_at="unavailable"', metrics)
        self.assertNotIn('benchmark_run_posts{run_id="run-3"}', metrics)

    def test_missing_value_is_left_out_rather_than_published_as_zero(self):
        self.write_summary("run-3", rest="", grpc="110")

        metrics = exporter.render(self.root)

        self.assertNotIn("benchmark_comparison_throughput_gain_percent", metrics)

    def test_label_text_is_escaped(self):
        folder = self.write_summary("run-3")
        (folder / "run-metadata.json").write_text(json.dumps({"label": 'say "hi"\\now'}), encoding="utf-8")

        self.assertIn('label="say \\"hi\\"\\\\now"', exporter.render(self.root))


if __name__ == "__main__":
    unittest.main()

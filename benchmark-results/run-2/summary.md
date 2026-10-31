# Benchmark summary: gRPC vs REST

All numbers in this file were measured by the benchmark runner. It is generated: edit nothing here.

> **What this comparison means.** The two sides differ in transport (HTTP/2 over one multiplexed
> connection vs HTTP/1.1 with one connection per concurrent request) **and** in serialization
> (Protobuf vs JSON), and in framework (grpc-java on Netty vs Spring MVC on Tomcat). These results
> measure the combination. They do not show how much of any gap comes from Protobuf alone.

## Environment

| Item | Value |
|---|---|
| Started / finished | 2026-10-31T05:44:48.720648700Z / 2026-10-31T05:55:19.337147300Z |
| Client Java / cores | 21.0.9 / 8 |
| Dataset at start | 1003 users, 1117600 posts, 3117600 comments |
| Requests per run | 20000 reads, 5000 writes |
| Warm-up per run | 2000 requests, not recorded |
| Iterations | 4, with protocol order alternating |
| Concurrent clients | [16, 64] |
| Sampled ids | 5000 per kind, seed 42 |
| Page size | 20 |
| Retry policy | 3 attempts, 500 ms per attempt, 1500 ms overall |

## Latency and throughput

Medians across iterations. The difference is gRPC relative to REST: a negative latency change and a
positive throughput change favour gRPC. Compare a difference with the min-max range before trusting it.

### 16 concurrent clients

| Operation | Protocol | Throughput (req/s) | Range | Avg (ms) | p50 (ms) | p95 (ms) | p99 (ms) | p99 range |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| GetPost | REST | 3759 | 3342-3896 | 4.24 | 4.06 | 6.00 | 7.76 | 6.80-8.16 |
| GetPost | gRPC | 4241 | 3651-4338 | 3.75 | 3.61 | 5.21 | 7.00 | 5.89-8.41 |
| | **Difference** | **+12.8%** | | -11.5% | -11.0% | -13.2% | -9.8% | |
| GetAuthorPosts | REST | 2166 | 2081-2353 | 7.35 | 7.11 | 10.46 | 13.09 | 12.19-13.56 |
| GetAuthorPosts | gRPC | 2531 | 2361-2725 | 6.30 | 6.10 | 8.90 | 11.08 | 10.06-12.12 |
| | **Difference** | **+16.9%** | | -14.3% | -14.2% | -14.9% | -15.4% | |
| GetComments | REST | 2980 | 2944-3034 | 5.35 | 5.15 | 7.45 | 9.74 | 9.43-9.95 |
| GetComments | gRPC | 3439 | 3313-3468 | 4.62 | 4.47 | 6.44 | 8.22 | 7.61-8.85 |
| | **Difference** | **+15.4%** | | -13.5% | -13.1% | -13.6% | -15.7% | |
| CreatePost | REST | 1113 | 632-1154 | 14.17 | 13.64 | 19.13 | 24.43 | 22.37-58.70 |
| CreatePost | gRPC | 1200 | 1148-1231 | 13.11 | 12.67 | 17.28 | 22.07 | 20.61-25.52 |
| | **Difference** | **+7.8%** | | -7.5% | -7.1% | -9.7% | -9.7% | |
| CreateComment | REST | 1046 | 871-1055 | 15.10 | 14.44 | 21.40 | 28.85 | 25.51-79.97 |
| CreateComment | gRPC | 1150 | 1077-1195 | 13.73 | 13.24 | 17.84 | 22.87 | 22.24-25.98 |
| | **Difference** | **+9.9%** | | -9.1% | -8.3% | -16.6% | -20.7% | |

### 64 concurrent clients

| Operation | Protocol | Throughput (req/s) | Range | Avg (ms) | p50 (ms) | p95 (ms) | p99 (ms) | p99 range |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| GetPost | REST | 3683 | 3550-3825 | 17.24 | 16.84 | 24.85 | 30.65 | 28.78-32.55 |
| GetPost | gRPC | 4127 | 4056-4238 | 15.37 | 14.81 | 23.53 | 29.03 | 27.89-31.01 |
| | **Difference** | **+12.1%** | | -10.9% | -12.0% | -5.3% | -5.3% | |
| GetAuthorPosts | REST | 2177 | 1930-2194 | 29.21 | 28.65 | 40.58 | 50.98 | 49.03-91.60 |
| GetAuthorPosts | gRPC | 2524 | 2418-2589 | 25.05 | 24.56 | 36.91 | 47.14 | 45.76-48.50 |
| | **Difference** | **+16.0%** | | -14.2% | -14.3% | -9.0% | -7.5% | |
| GetComments | REST | 3014 | 2860-3226 | 21.11 | 20.26 | 30.73 | 38.24 | 33.03-51.10 |
| GetComments | gRPC | 3435 | 2182-3718 | 18.54 | 17.70 | 28.57 | 41.44 | 32.67-106.70 |
| | **Difference** | **+14.0%** | | -12.2% | -12.6% | -7.0% | +8.4% | |
| CreatePost | REST | 1014 | 995-1178 | 62.04 | 60.68 | 87.26 | 101.70 | 83.10-127.09 |
| CreatePost | gRPC | 1136 | 870-1229 | 55.81 | 54.30 | 81.80 | 103.05 | 80.66-141.72 |
| | **Difference** | **+12.0%** | | -10.0% | -10.5% | -6.3% | +1.3% | |
| CreateComment | REST | 1141 | 1095-1250 | 55.06 | 54.64 | 72.85 | 88.45 | 84.89-95.29 |
| CreateComment | gRPC | 1216 | 1162-1287 | 51.64 | 50.79 | 72.06 | 84.29 | 79.92-102.63 |
| | **Difference** | **+6.5%** | | -6.2% | -7.1% | -1.1% | -4.7% | |

## Application payload size

Average serialized message bytes only: JSON bodies for REST, Protobuf messages for gRPC. This is **not**
total network traffic. It excludes HTTP headers, the 5-byte gRPC message prefix, HTTP/2 frames and TCP.
Network bytes were not measured.

The request columns are not like for like: REST sends ids in the URL and the idempotency key in a header,
neither of which counts as body bytes, while gRPC carries them inside the Protobuf message.

| Operation | Request REST | Request gRPC | Response REST | Response gRPC | Response change | Total REST | Total gRPC |
|---|---:|---:|---:|---:|---:|---:|---:|
| GetPost | 0 | 4 | 1156 | 1055 | -8.7% | 1156 | 1059 |
| GetAuthorPosts | 0 | 5 | 23257 | 21240 | -8.7% | 23257 | 21245 |
| GetComments | 0 | 6 | 6738 | 4745 | -29.6% | 6738 | 4751 |
| CreatePost | 1043 | 1061 | 1159 | 1057 | -8.8% | 2202 | 2118 |
| CreateComment | 214 | 245 | 347 | 242 | -30.1% | 561 | 487 |

## Errors and retries

| Concurrency | Operation | Protocol | Requests | Failures | Error rate | Retries |
|---:|---|---|---:|---:|---:|---:|
| 16 | GetPost | REST | 80000 | 0 | 0.000% | 0 |
| 16 | GetPost | gRPC | 80000 | 0 | 0.000% | 0 |
| 16 | GetAuthorPosts | REST | 80000 | 0 | 0.000% | 0 |
| 16 | GetAuthorPosts | gRPC | 80000 | 0 | 0.000% | 0 |
| 16 | GetComments | REST | 80000 | 0 | 0.000% | 0 |
| 16 | GetComments | gRPC | 80000 | 0 | 0.000% | 0 |
| 16 | CreatePost | REST | 20000 | 0 | 0.000% | 0 |
| 16 | CreatePost | gRPC | 20000 | 0 | 0.000% | 0 |
| 16 | CreateComment | REST | 20000 | 0 | 0.000% | 0 |
| 16 | CreateComment | gRPC | 20000 | 0 | 0.000% | 0 |
| 64 | GetPost | REST | 80000 | 0 | 0.000% | 0 |
| 64 | GetPost | gRPC | 80000 | 0 | 0.000% | 0 |
| 64 | GetAuthorPosts | REST | 80000 | 0 | 0.000% | 0 |
| 64 | GetAuthorPosts | gRPC | 80000 | 0 | 0.000% | 0 |
| 64 | GetComments | REST | 80000 | 0 | 0.000% | 0 |
| 64 | GetComments | gRPC | 80000 | 0 | 0.000% | 0 |
| 64 | CreatePost | REST | 20000 | 0 | 0.000% | 0 |
| 64 | CreatePost | gRPC | 20000 | 0 | 0.000% | 0 |
| 64 | CreateComment | REST | 20000 | 0 | 0.000% | 0 |
| 64 | CreateComment | gRPC | 20000 | 0 | 0.000% | 0 |

## Serialization alone

JSON and Protobuf timed in one process with no network, server or database, on identical data. This is a
simple timing loop rather than JMH, so treat it as indicative. It shows how much of an end-to-end gap
serialization could account for, on one side of one call.

| Payload | Format | Bytes | Serialize (µs) | Parse (µs) |
|---|---|---:|---:|---:|
| 1 post | JSON | 1211 | 5.33 | 3.64 |
| 1 post | Protobuf | 1085 | 1.00 | 0.76 |
| 20 posts | JSON | 23128 | 78.64 | 49.50 |
| 20 posts | Protobuf | 21083 | 16.13 | 5.37 |

Raw data: `raw-runs.csv` has one row per run, `summary.csv` has these tables.

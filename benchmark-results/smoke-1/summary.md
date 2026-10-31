# Benchmark summary: gRPC vs REST

All numbers in this file were measured by the benchmark runner. It is generated: edit nothing here.

> **What this comparison means.** The two sides differ in transport (HTTP/2 over one multiplexed
> connection vs HTTP/1.1 with one connection per concurrent request) **and** in serialization
> (Protobuf vs JSON), and in framework (grpc-java on Netty vs Spring MVC on Tomcat). These results
> measure the combination. They do not show how much of any gap comes from Protobuf alone.

## Environment

| Item | Value |
|---|---|
| Started / finished | 2026-10-31T06:51:02.215118900Z / 2026-10-31T06:51:59.678687300Z |
| Client Java / cores | 21.0.9 / 8 |
| Dataset at start | 1003 users, 1229600 posts, 3229600 comments |
| Requests per run | 2000 reads, 200 writes |
| Warm-up per run | 200 requests, not recorded |
| Iterations | 2, with protocol order alternating |
| Concurrent clients | [16] |
| Sampled ids | 500 per kind, seed 42 |
| Page size | 20 |
| Retry policy | 3 attempts, 500 ms per attempt, 1500 ms overall |

## Latency and throughput

Medians across iterations. The difference is gRPC relative to REST: a negative latency change and a
positive throughput change favour gRPC. Compare a difference with the min-max range before trusting it.

### 16 concurrent clients

| Operation | Protocol | Throughput (req/s) | Range | Avg (ms) | p50 (ms) | p95 (ms) | p99 (ms) | p99 range |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| GetPost | REST | 998 | 905-1091 | 15.32 | 13.41 | 29.94 | 45.56 | 42.07-49.05 |
| GetPost | gRPC | 987 | 939-1036 | 15.49 | 13.08 | 30.75 | 57.83 | 52.04-63.62 |
| | **Difference** | **-1.0%** | | +1.1% | -2.4% | +2.7% | +26.9% | |
| GetAuthorPosts | REST | 1050 | 683-1416 | 17.03 | 14.62 | 35.39 | 49.12 | 26.69-71.56 |
| GetAuthorPosts | gRPC | 1255 | 1168-1341 | 12.65 | 11.07 | 25.80 | 36.94 | 34.85-39.02 |
| | **Difference** | **+19.5%** | | -25.7% | -24.3% | -27.1% | -24.8% | |
| GetComments | REST | 2317 | 2286-2348 | 6.78 | 6.48 | 10.36 | 13.18 | 12.37-13.99 |
| GetComments | gRPC | 2654 | 2594-2715 | 5.92 | 5.73 | 8.78 | 11.91 | 9.65-14.16 |
| | **Difference** | **+14.6%** | | -12.6% | -11.4% | -15.3% | -9.7% | |
| CreatePost | REST | 404 | 338-469 | 38.55 | 37.84 | 57.13 | 66.16 | 60.40-71.92 |
| CreatePost | gRPC | 486 | 444-528 | 31.14 | 30.50 | 44.22 | 49.73 | 46.49-52.98 |
| | **Difference** | **+20.4%** | | -19.2% | -19.4% | -22.6% | -24.8% | |
| CreateComment | REST | 496 | 454-537 | 30.52 | 28.40 | 48.82 | 65.10 | 64.83-65.37 |
| CreateComment | gRPC | 572 | 550-593 | 26.40 | 25.35 | 39.74 | 44.90 | 40.37-49.42 |
| | **Difference** | **+15.3%** | | -13.5% | -10.8% | -18.6% | -31.0% | |

## Application payload size

Average serialized message bytes only: JSON bodies for REST, Protobuf messages for gRPC. This is **not**
total network traffic. It excludes HTTP headers, the 5-byte gRPC message prefix, HTTP/2 frames and TCP.
Network bytes were not measured.

The request columns are not like for like: REST sends ids in the URL and the idempotency key in a header,
neither of which counts as body bytes, while gRPC carries them inside the Protobuf message.

| Operation | Request REST | Request gRPC | Response REST | Response gRPC | Response change | Total REST | Total gRPC |
|---|---:|---:|---:|---:|---:|---:|---:|
| GetPost | 0 | 4 | 1156 | 1055 | -8.7% | 1156 | 1059 |
| GetAuthorPosts | 0 | 5 | 23257 | 21241 | -8.7% | 23257 | 21246 |
| GetComments | 0 | 6 | 6927 | 4877 | -29.6% | 6927 | 4883 |
| CreatePost | 1043 | 1061 | 1159 | 1058 | -8.7% | 2202 | 2119 |
| CreateComment | 214 | 245 | 347 | 242 | -30.1% | 561 | 487 |

## Errors and retries

| Concurrency | Operation | Protocol | Requests | Failures | Error rate | Retries |
|---:|---|---|---:|---:|---:|---:|
| 16 | GetPost | REST | 4000 | 0 | 0.000% | 0 |
| 16 | GetPost | gRPC | 4000 | 0 | 0.000% | 0 |
| 16 | GetAuthorPosts | REST | 4000 | 0 | 0.000% | 0 |
| 16 | GetAuthorPosts | gRPC | 4000 | 0 | 0.000% | 0 |
| 16 | GetComments | REST | 4000 | 0 | 0.000% | 0 |
| 16 | GetComments | gRPC | 4000 | 0 | 0.000% | 0 |
| 16 | CreatePost | REST | 400 | 0 | 0.000% | 0 |
| 16 | CreatePost | gRPC | 400 | 0 | 0.000% | 0 |
| 16 | CreateComment | REST | 400 | 0 | 0.000% | 0 |
| 16 | CreateComment | gRPC | 400 | 0 | 0.000% | 0 |

## Serialization alone

JSON and Protobuf timed in one process with no network, server or database, on identical data. This is a
simple timing loop rather than JMH, so treat it as indicative. It shows how much of an end-to-end gap
serialization could account for, on one side of one call.

| Payload | Format | Bytes | Serialize (µs) | Parse (µs) |
|---|---|---:|---:|---:|
| 1 post | JSON | 1211 | 6.14 | 4.68 |
| 1 post | Protobuf | 1085 | 1.61 | 1.48 |
| 20 posts | JSON | 23128 | 82.30 | 63.78 |
| 20 posts | Protobuf | 21083 | 20.27 | 6.77 |

Raw data: `raw-runs.csv` has one row per run, `summary.csv` has these tables.

# Benchmark summary: gRPC vs REST

All numbers in this file were measured by the benchmark runner. It is generated: edit nothing here.

> **What this comparison means.** The two sides differ in transport (HTTP/2 over one multiplexed
> connection vs HTTP/1.1 with one connection per concurrent request) **and** in serialization
> (Protobuf vs JSON), and in framework (grpc-java on Netty vs Spring MVC on Tomcat). These results
> measure the combination. They do not show how much of any gap comes from Protobuf alone.

## Environment

| Item | Value |
|---|---|
| Started / finished | 2026-10-31T05:04:07.176476600Z / 2026-10-31T05:16:09.901140800Z |
| Client Java / cores | 21.0.9 / 8 |
| Dataset at start | 1003 users, 1005600 posts, 3005600 comments |
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
| GetPost | REST | 2988 | 2627-3147 | 5.33 | 5.07 | 7.80 | 10.81 | 10.40-13.50 |
| GetPost | gRPC | 3233 | 3032-3360 | 4.92 | 4.59 | 7.50 | 11.64 | 9.85-15.09 |
| | **Difference** | **+8.2%** | | -7.7% | -9.5% | -3.9% | +7.7% | |
| GetAuthorPosts | REST | 1810 | 1778-1858 | 8.77 | 8.26 | 13.12 | 18.07 | 16.72-18.69 |
| GetAuthorPosts | gRPC | 2094 | 1872-2114 | 7.60 | 7.24 | 11.43 | 15.55 | 13.59-38.80 |
| | **Difference** | **+15.7%** | | -13.3% | -12.4% | -12.9% | -13.9% | |
| GetComments | REST | 3037 | 2680-3116 | 5.24 | 4.98 | 7.79 | 10.54 | 9.61-11.39 |
| GetComments | gRPC | 3509 | 3308-3589 | 4.54 | 4.28 | 6.48 | 9.41 | 8.01-11.26 |
| | **Difference** | **+15.5%** | | -13.4% | -14.0% | -16.8% | -10.7% | |
| CreatePost | REST | 872 | 855-1035 | 18.09 | 15.91 | 28.96 | 43.11 | 37.69-49.27 |
| CreatePost | gRPC | 1021 | 808-1146 | 15.67 | 14.64 | 22.89 | 31.90 | 21.61-69.22 |
| | **Difference** | **+17.2%** | | -13.4% | -7.9% | -20.9% | -26.0% | |
| CreateComment | REST | 978 | 780-987 | 16.19 | 15.48 | 22.13 | 29.26 | 26.10-55.58 |
| CreateComment | gRPC | 988 | 658-1071 | 16.08 | 15.32 | 22.34 | 32.06 | 24.44-60.69 |
| | **Difference** | **+1.0%** | | -0.6% | -1.0% | +0.9% | +9.6% | |

### 64 concurrent clients

| Operation | Protocol | Throughput (req/s) | Range | Avg (ms) | p50 (ms) | p95 (ms) | p99 (ms) | p99 range |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| GetPost | REST | 3291 | 3233-3400 | 19.25 | 18.34 | 28.20 | 43.38 | 32.64-47.76 |
| GetPost | gRPC | 3753 | 3707-3865 | 16.88 | 16.36 | 25.70 | 33.05 | 30.79-34.08 |
| | **Difference** | **+14.1%** | | -12.3% | -10.8% | -8.9% | -23.8% | |
| GetAuthorPosts | REST | 2065 | 1948-2081 | 30.71 | 30.06 | 44.02 | 55.32 | 52.03-61.08 |
| GetAuthorPosts | gRPC | 2205 | 1816-2352 | 28.77 | 27.12 | 46.30 | 69.11 | 49.39-102.05 |
| | **Difference** | **+6.8%** | | -6.3% | -9.8% | +5.2% | +24.9% | |
| GetComments | REST | 2807 | 2658-2930 | 22.59 | 21.62 | 34.18 | 46.04 | 39.55-56.51 |
| GetComments | gRPC | 3085 | 2757-3362 | 20.63 | 19.54 | 32.18 | 40.01 | 34.46-51.07 |
| | **Difference** | **+9.9%** | | -8.7% | -9.6% | -5.9% | -13.1% | |
| CreatePost | REST | 1023 | 1000-1065 | 61.57 | 59.71 | 85.44 | 100.21 | 96.63-141.48 |
| CreatePost | gRPC | 1115 | 1036-1144 | 56.30 | 55.10 | 77.39 | 98.53 | 87.19-115.81 |
| | **Difference** | **+9.0%** | | -8.6% | -7.7% | -9.4% | -1.7% | |
| CreateComment | REST | 756 | 545-1015 | 84.30 | 73.87 | 151.11 | 215.29 | 99.81-304.01 |
| CreateComment | gRPC | 877 | 260-1087 | 72.65 | 66.84 | 125.12 | 173.85 | 104.21-1200.83 |
| | **Difference** | **+16.0%** | | -13.8% | -9.5% | -17.2% | -19.2% | |

## Application payload size

Average serialized message bytes only: JSON bodies for REST, Protobuf messages for gRPC. This is **not**
total network traffic. It excludes HTTP headers, the 5-byte gRPC message prefix, HTTP/2 frames and TCP.
Network bytes were not measured.

The request columns are not like for like: REST sends ids in the URL and the idempotency key in a header,
neither of which counts as body bytes, while gRPC carries them inside the Protobuf message.

| Operation | Request REST | Request gRPC | Response REST | Response gRPC | Response change | Total REST | Total gRPC |
|---|---:|---:|---:|---:|---:|---:|---:|
| GetPost | 0 | 4 | 1155 | 1055 | -8.7% | 1155 | 1059 |
| GetAuthorPosts | 0 | 5 | 23209 | 21208 | -8.6% | 23209 | 21213 |
| GetComments | 0 | 6 | 1444 | 1009 | -30.1% | 1444 | 1015 |
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
| 64 | CreateComment | REST | 20000 | 0 | 0.000% | 3 |
| 64 | CreateComment | gRPC | 20000 | 200 | 1.000% | 615 |

## Serialization alone

JSON and Protobuf timed in one process with no network, server or database, on identical data. This is a
simple timing loop rather than JMH, so treat it as indicative. It shows how much of an end-to-end gap
serialization could account for, on one side of one call.

| Payload | Format | Bytes | Serialize (µs) | Parse (µs) |
|---|---|---:|---:|---:|
| 1 post | JSON | 1211 | 4.97 | 5.11 |
| 1 post | Protobuf | 1085 | 1.36 | 1.24 |
| 20 posts | JSON | 23128 | 96.54 | 67.43 |
| 20 posts | Protobuf | 21083 | 24.82 | 8.52 |

Raw data: `raw-runs.csv` has one row per run, `summary.csv` has these tables.

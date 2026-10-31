# gRPC vs REST: benchmark analysis

This analysis interprets two complete benchmark runs. Every number comes from the files in
[run-1/](run-1/) and [run-2/](run-2/). Each folder holds `summary.md` (readable tables), `summary.csv`,
`raw-runs.csv` (one row per run) and `serialization-probe.csv`.

## Conclusion

On this machine, gRPC + HTTP/2 + Protobuf was moderately faster than REST + HTTP/1.1 + JSON. Its median
latency was lower and its throughput higher in all 20 comparisons, by about 1-17%. Tail latency (p99) was
less consistent: gRPC was worse in 5 of 20 comparisons.

Protobuf alone cannot explain the speed difference. Timed in isolation, serialization accounts for only
about 0.4-13% of the median latency gap in the two operations checked. The rest is unattributed: it lies
somewhere in transport and framework differences, which this benchmark cannot separate.

## What is being compared

The two sides differ in three ways at once:

| Layer | REST side | gRPC side |
|---|---|---|
| Transport | HTTP/1.1, one connection per concurrent request | HTTP/2, one multiplexed connection |
| Serialization | JSON (Jackson) | Protobuf |
| Framework | Spring MVC on Tomcat | grpc-java on Netty |

Both use plaintext, no compression, the same application services, the same database and the same
200 worker threads and 32 database connections. A difference between the columns cannot be assigned to a
single layer. Isolating them would need a third setup, such as JSON over HTTP/2.

## Method

- **Dataset:** seeded with 1,000,000 posts and 3,000,000 comments. SIS #1 targets 5 million posts, which
  was not tested because of disk space on the test machine.
- **Machine:** one 8-core laptop with 15.7 GB of RAM, running client, server and PostgreSQL 17 (in Docker,
  `shared_buffers=512MB`) together.
- **Load:** closed loop. 16 or 64 clients each send a request, wait for the response, then send the next.
- **Volume:** 20,000 reads or 5,000 writes per run, repeated for 4 iterations per protocol and operation.
- **Warm-up:** every sampled row is requested through both protocols before measuring, plus 2,000
  unrecorded requests before each run.
- **Fairness:** both protocols receive the same random targets, and the protocol that goes first
  alternates each iteration. Reads run before writes.
- **Retries:** both use 3 attempts, 500 ms per attempt and 1500 ms overall. Latency covers the whole
  logical call, including retries.
- **Summary figures:** medians across iterations.

## Latency and throughput

gRPC relative to REST. For throughput, positive favours gRPC. For latency, negative favours gRPC.
"Clear" means the throughput ranges of all iterations did not overlap, so that row's difference exceeds the
run's own noise. The other rows point the same way but stay within that noise.

| Clients | Operation | Run 1 throughput | Run 1 p50 | Run 1 p99 | Run 2 throughput | Run 2 p50 | Run 2 p99 | Clear in |
|---:|---|---:|---:|---:|---:|---:|---:|---|
| 16 | GetPost | +8.2% | -9.5% | +7.7% | +12.8% | -11.0% | -9.8% | Neither |
| 16 | GetAuthorPosts | +15.7% | -12.4% | -13.9% | +16.9% | -14.2% | -15.4% | Both |
| 16 | GetComments | +15.5% | -14.0% | -10.7% | +15.4% | -13.1% | -15.7% | Both |
| 16 | CreatePost | +17.2% | -7.9% | -26.0% | +7.8% | -7.1% | -9.7% | Neither |
| 16 | CreateComment | +1.0% | -1.0% | +9.6% | +9.9% | -8.3% | -20.7% | Run 2 |
| 64 | GetPost | +14.1% | -10.8% | -23.8% | +12.1% | -12.0% | -5.3% | Both |
| 64 | GetAuthorPosts | +6.8% | -9.8% | +24.9% | +16.0% | -14.3% | -7.5% | Run 2 |
| 64 | GetComments | +9.9% | -9.6% | -13.1% | +14.0% | -12.6% | +8.4% | Neither |
| 64 | CreatePost | +9.0% | -7.7% | -1.7% | +12.0% | -10.5% | +1.3% | Neither |
| 64 | CreateComment | +16.0% | -9.5% | -19.2% | +6.5% | -7.1% | -4.7% | Neither |

- gRPC had higher throughput and lower p50 in 20 of 20 comparisons.
- The throughput difference was clear in 8 of 20 comparisons, always in gRPC's favour.
- gRPC had worse p99 in 5 of 20 comparisons. p99 rests on few samples and moved more between runs, so
  these results do not show a reliable p99 advantage either way.
- The absolute gaps are small: for example, GetPost with 16 clients in run 2 had a p50 of 4.06 ms on REST
  and 3.61 ms on gRPC.

Run 2 measured a p50 of 3.6-28.7 ms for reads and 12.7-60.7 ms for writes. Writes are slower on both
protocols mainly because of the extra database work, which is why write differences are harder to see.

## How much is serialization?

This table compares the median latency gap with the time a separate probe measured for serializing and
parsing the same data, with no network, server or database. The saving assumes one serialize and one parse
per call.

| Run | Operation | Clients | p50 gap | Serialization saving | Share of gap |
|---|---|---:|---:|---:|---:|
| 1 | GetPost | 16 | 483 µs | 7.5 µs | 1.5% |
| 2 | GetPost | 16 | 447 µs | 7.2 µs | 1.6% |
| 1 | GetAuthorPosts | 16 | 1,022 µs | 130.6 µs | 12.8% |
| 2 | GetAuthorPosts | 16 | 1,011 µs | 106.6 µs | 10.5% |
| 1 | GetPost | 64 | 1,984 µs | 7.5 µs | 0.4% |
| 2 | GetPost | 64 | 2,027 µs | 7.2 µs | 0.4% |
| 1 | GetAuthorPosts | 64 | 2,947 µs | 130.6 µs | 4.4% |
| 2 | GetAuthorPosts | 64 | 4,095 µs | 106.6 µs | 2.6% |

Protobuf itself was about 3-5 times faster than Jackson to serialize and about 4-9 times faster to parse.
That is a real advantage, but a call that spends milliseconds elsewhere has little serialization time to
save. This share is a rough estimate:

- The probe is a simple timing loop rather than JMH, and it ran single-threaded.
- It leaves out effects such as garbage collection and CPU contention, so the true share may be higher.
- Queueing at 64 clients enlarges the latency gap, which is why the share shrinks there.

## Payload size

Application payload only: JSON bodies versus Protobuf messages. **This is not network traffic.** It
excludes HTTP headers, the 5-byte gRPC message prefix, HTTP/2 frames and TCP. Network bytes were not
measured. Run 2 responses:

| Operation | REST response (bytes) | gRPC response (bytes) | Change |
|---|---:|---:|---:|
| GetPost | 1,156 | 1,055 | -8.7% |
| GetAuthorPosts (20 posts) | 23,257 | 21,240 | -8.7% |
| GetComments | 6,738 | 4,745 | -29.6% |
| CreatePost | 1,159 | 1,057 | -8.8% |
| CreateComment | 347 | 242 | -30.3% |

Protobuf saves on field names, numbers and timestamps, but text is stored as the same bytes in both formats.
The saving is therefore about 9% for posts with 1,000 characters of content, and about 30% for comments with
200 characters. Less text gives Protobuf more of the total to shrink.

Request sizes are not like for like: REST sends ids in the URL and the idempotency key in a header, outside
the JSON body, while gRPC carries them in the message.

## Reliability

Run 2 had no failures and no retries. Run 1 had a stall near the end of the final operation, CreateComment
with 64 clients:

| Evidence | Detail |
|---|---|
| Effect | 200 gRPC failures and 618 retries (615 gRPC, 3 REST) |
| Timing | Slowdown covered about four consecutive runs on both protocols, then recovered |
| Server log | Database connection pool timeouts, all within about 2 seconds |
| Possible cause | Autovacuum and analyze finished around then, and a checkpoint was running. This is a correlation, not proof |
| Repeated in run 2? | No |

The failure count falls on gRPC mainly because gRPC happened to go first when the stall began, so **it should
not be read as gRPC being less reliable**. REST also dropped to 545 req/s in the next run.

The stall did test idempotency under real conditions:

- **143 of the 200 writes the client saw fail had been committed by the server.**
- The benchmark's journal kept their keys. On restart it resent all 200, which added exactly 57 comments.
- Row counts after each run matched attempted writes exactly, with no duplicates and nothing lost.

Without idempotency keys, resending those writes would have created 143 duplicate comments.

Each create also leaves a dead row in `idempotency_records`, because the design inserts a record and then
updates it. That adds autovacuum work, and retention of old records is not implemented.

## SIS #1 targets

| Target | Observed on this machine | Reading |
|---|---|---|
| 4,000 read RPS | GetPost reached about 3,700-4,200 req/s in run 2, GetAuthorPosts about 2,200-2,500 | Not shown. Only some single-operation runs came near the target |
| 200 write RPS | Writes reached about 1,000-1,200 req/s in run 2 | Exceeded in single-operation runs |
| p99 under 120 ms | Run 2 medians were 7-51 ms for reads and 22-103 ms for writes, but single iterations reached 142 ms. In the run 1 stall, CreateComment medians were 215 ms on REST and 174 ms on gRPC, and the worst single iterations were 304 ms and 1,201 ms | Median p99 was met in clean conditions. Not met in every iteration, and not during the stall |

No mixed read and write workload was tested, and the dataset was smaller than the 5 million post target, so
this is not a capacity result. Client, server and database also shared one laptop, whereas SIS #1 assumes
several server instances behind a load balancer.

## Limitations

- **One shared laptop:** the client competes with the server and database for CPU, and absolute numbers would
  differ on separate machines.
- **Closed-loop load:** clients slow down when the server does, which can understate tail latency compared
  with a fixed arrival rate.
- **Mostly cached reads:** a sample of 5,000 ids per kind was warmed first, so these results say little about
  cold-disk reads.
- **Two runs:** the runs agree on direction, but two runs cannot give precise percentages.
- **Runs are not comparable on every operation:** run 2 started with 1,117,600 posts and run 1 with 1,005,600.
  Run 1's writes also changed the sampled posts, so GetComments responses were about 6,700 bytes in run 2 and
  about 1,400 in run 1. Compare protocols within a run, not GetComments across runs.
- **An abandoned attempt:** a first attempt at run 2 was stopped because the laptop entered standby for 16
  minutes, and its data was deleted. The reported run 2 had no standby events and no server warnings.
- **Plaintext only:** TLS was not used and would add cost to both sides.

## Possible improvements

- Run JSON over HTTP/2 as a third setup, to separate transport from serialization directly.
- Put the client, server and database on separate machines.
- Use a fixed-rate load generator, to measure tail latency under a set arrival rate.
- Test a mixed workload at the full 5 million posts.
- Capture real network bytes with a packet capture.
- Add a retention job for idempotency records, and investigate the run 1 stall by logging autovacuum.

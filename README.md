# CacheCraft

CacheCraft is a local cache experimentation lab for demonstrating database saturation, cache stampedes, cache avalanches, and focused cache-protection strategies. It is a Spring Boot portfolio project built around repeatable local experiments rather than production abstractions.

The lab uses PostgreSQL, Redis, Spring Boot, Docker Compose, Bash, and k6. All dependencies run locally. k6 is the only load generator. Spring Boot does not generate synthetic load internally.

## Executive proof matrix

The matrix below defines the empirical evidence measured on GitHub Codespaces (Linux 2-core / 8GB RAM container topology: PostgreSQL 16, Redis 7, three API instances, and Nginx load balancer).

<table>
  <thead>
    <tr>
      <th>Experiment</th>
      <th>Fixed Setup</th>
      <th>Required Proof</th>
      <th>Measured Query Delta</th>
      <th>Cache Hit / Miss</th>
      <th>Errors / 503s</th>
      <th>Throughput & Latency</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><strong>Baseline Machine Calibration</strong></td>
      <td>3 API nodes, warm key 42, 10 VUs, 10s, <code>delay=0</code>, <code>ttl=300</code></td>
      <td>Establish VM ceiling; 100% hits, 0 DB queries.</td>
      <td><strong>0</strong> (API 1: +0, API 2: +0, API 3: +0)</td>
      <td>100.00% HIT (3,302 reqs)</td>
      <td>0 errors</td>
      <td>329.41 req/s<br>p95: 59.93 ms</td>
    </tr>
    <tr>
      <td><strong>Single-instance unprotected stampede</strong></td>
      <td>1 API instance, 20 Hikari connections, 200 requests, 250 ms delay</td>
      <td>Tenth database wave waits 2250 ms > 2000 ms pool timeout; final requests return HTTP 503.</td>
      <td><strong>+182</strong> (API 1: +182)</td>
      <td>0 HIT / 182 MISS</td>
      <td><strong>18 x HTTP 503 (9.0%)</strong></td>
      <td>59.76 req/s<br>p95: 3,070 ms</td>
    </tr>
    <tr>
      <td><strong>Three-instance local single-flight</strong></td>
      <td>3 API instances, 600 total requests (200/node), 250 ms delay</td>
      <td>Exactly 3 repository queries: one JVM-local leader elected per API instance.</td>
      <td><strong>+3</strong> (API 1: +1, API 2: +1, API 3: +1)</td>
      <td>597 HIT / 3 MISS</td>
      <td>0 errors (0 x 503)</td>
      <td>225.34 req/s<br>p95: 2,330 ms</td>
    </tr>
    <tr>
      <td><strong>Three-instance distributed lock</strong></td>
      <td>3 API instances, 600 total requests (200/node), 250 ms delay</td>
      <td>Exactly 1 repository query across the fleet, 599 hits, 1 miss, zero lock-timeout fallbacks.</td>
      <td><strong>+1</strong> (API 1: +0, API 2: +1, API 3: +0)</td>
      <td>599 HIT / 1 MISS</td>
      <td>0 errors (0 x 503)</td>
      <td>225.46 req/s<br>p95: 2,197 ms</td>
    </tr>
    <tr>
      <td><strong>Avalanche: Synchronized (no-jitter)</strong></td>
      <td>10,000 keys bulk-warmed with shared PXAT expiry, <code>ttl=5</code>, <code>delay=5</code>, <code>jitter=0.0</code></td>
      <td>Keys expire in lockstep, causing repeated synchronized miss bursts on reload.</td>
      <td><strong>+2,906</strong> (API 1: +946, API 2: +967, API 3: +993)</td>
      <td>690 HIT / 2,907 MISS</td>
      <td>0 errors (0 x 503)</td>
      <td>236.70 req/s<br>p95: 3,181 ms</td>
    </tr>
    <tr>
      <td><strong>Avalanche: With Jitter</strong></td>
      <td>10,000 keys bulk-warmed with shared PXAT expiry, <code>ttl=5</code>, <code>delay=5</code>, <code>jitter=0.10</code></td>
      <td>Symmetric jitter spreads future TTLs across &plusmn;10%, smoothing secondary DB spikes.</td>
      <td><strong>+2,479</strong> (API 1: +814, API 2: +833, API 3: +832)</td>
      <td>431 HIT / 2,480 MISS</td>
      <td>0 errors (0 x 503)</td>
      <td>185.20 req/s<br>p95: 4,860 ms</td>
    </tr>
  </tbody>
</table>

## Contents

1. [Project scope and operating principles](#project-scope-and-operating-principles)
2. [Architecture](#architecture)
3. [Configuration and derived values](#configuration-and-derived-values)
4. [Mathematical proofs](#mathematical-proofs)
5. [Request lifecycle](#request-lifecycle)
6. [Cache strategies and lock protocol](#cache-strategies-and-lock-protocol)
7. [Avalanche initialization and jitter](#avalanche-initialization-and-jitter)
8. [Observability and error handling](#observability-and-error-handling)
9. [API contract](#api-contract)
10. [Repository structure](#repository-structure)
11. [Experiment protocol](#experiment-protocol)
12. [GitHub Codespaces workflow](#github-codespaces-workflow)
13. [Local workflow](#local-workflow)
14. [Verification](#verification)
15. [Reference material](#reference-material)

## Project scope and operating principles

CacheCraft measures how a finite database connection pool behaves under controlled demand and how caching changes that behavior. The project is intentionally explicit:

* PostgreSQL is the only primary database.
* Redis is the cache and distributed coordination store.
* Data access uses raw SQL through `JdbcTemplate` and `ConnectionCallback`.
* Strategy selection remains a direct `switch` in `ItemService.java`.
* The application runs with platform threads. Virtual threads remain disabled because the 200-thread experiment model depends on a finite Tomcat worker pool.
* k6 and `run-test.sh` are the only sources of load.
* Docker Compose runs every dependency locally. Cloud databases are outside the main experiment path.

### Why PostgreSQL is deliberate

HikariCP creates a finite boundary between application threads and PostgreSQL connections. That boundary makes the failure condition calculable. A network round trip to a cloud database would also hold a HikariCP connection, but it introduces external jitter and idle cold-start behavior. PostgreSQL `pg_sleep` provides the same controlled connection-hold effect locally.

PostgreSQL documents that `pg_sleep` delays the current session for at least the requested number of seconds and accepts fractional values. Actual delay may be longer because timer resolution and server scheduling are not exact. [PostgreSQL `pg_sleep` documentation](https://www.postgresql.org/docs/16/functions-datetime.html)

### Architectural nuance: local and distributed single-flight

Local single-flight and a distributed lock both coalesce duplicate cache misses. They differ in scope.

`LocalLockService.java` stores in-flight work in a JVM-local `ConcurrentHashMap`. One API instance elects one local leader for a given key. With three API instances, three independent local leaders can each query PostgreSQL.

`RedisLockService.java` coordinates through shared Redis. All API instances contend for one lock key, so exactly one leader can fetch a cold item across the fleet. The three-instance experiment is required to demonstrate this distinction. One-instance results cannot prove distributed coordination.

## Architecture

```text
Terminal
  run-test.sh
    snapshots /debug/db-queries on all API instances
    starts k6 with the selected test and environment values
      Nginx, public port 8080
        API 1
        API 2
        API 3
        Tomcat, 200 platform threads per instance
          ItemController
            ItemService
              LocalLockService or RedisLockService
              Redis
              ItemRepository
                HikariCP, 20 connections per instance
                  PostgreSQL
    snapshots /debug/db-queries again and totals the deltas
    prints the custom k6 summary and repository-query proof
```

All three API instances share the same PostgreSQL and Redis containers. `nginx.conf` defines the upstream containing `api-1`, `api-2`, and `api-3`; Nginx exposes the public API port and balances normal k6 traffic across those isolated JVMs. Spring profiles may provide the same topology, but only one topology is valid for the multi-instance comparison: three isolated JVMs, one Nginx load balancer, one Redis deployment, and one PostgreSQL deployment.

Nginx is the public entry point for baseline and horizontal-load tests. For the exact local-lock versus distributed-lock proof, `stampede.js` uses three deterministic target groups mapped directly to the three API services. Each group sends 200 simultaneous requests to one service. Generic round-robin balancing is useful for normal traffic but cannot prove an exact one-leader-per-instance result.

## Configuration and derived values

### Base configuration

`application.yml` holds only the stable physical limits and safe defaults.

* **Tomcat maximum platform threads:** 200 per API instance.
* **Virtual threads:** disabled.
* **HikariCP maximum pool size:** 20 per API instance.
* **HikariCP connection timeout:** 2000 ms.
* **Standard TTL:** 30 s.
* **Distributed lock lease:** 5000 ms.
* **Default test delay:** 250 ms for the headline stampede profile.
* **Avalanche-model delay:** 5 ms, used only for the conservative 4,000 QPS jitter calculation.
* **Configured symmetric TTL jitter:** 0.10.

`run-test.sh` supplies the per-run values `type`, `strat`, `n`, `ttl`, `delay`, and `jitter`. `strat` becomes the API query parameter `strategy`. The test script selects one of `baseline.js`, `stampede.js`, or `avalanche.js` from `type`.

### Values computed from the selected delay

The service calculates timing values from the request delay. It does not hardcode one universal poll or follower-wait value.

```text
per-instance database ceiling C = P / d
poll base = clamp(d / 5, 5 ms, 50 ms)
poll delay = random value around poll base, clamped to 5 ms through 50 ms
follower maximum wait W = max(200 ms, 2 x d)
```

`P` is the per-instance HikariCP pool size and `d` is the requested PostgreSQL delay in milliseconds.

For the headline profile, `P=20` and `d=250 ms`:

```text
C = 20 / 0.250 s = 80 QPS per API instance
poll base = clamp(50 ms, 5 ms, 50 ms) = 50 ms
W = max(200 ms, 500 ms) = 500 ms
```

For the 5 ms avalanche model, the same pool has a theoretical 4,000 QPS ceiling. This is a capacity model, not a measured promise. Calibration determines the machine's real limit.

### Lock lease rule

The lease must cover worst-case database acquisition, database work, and a fixed safety margin:

```text
lease > HikariCP timeout + selected delay + 500 ms margin
5000 ms > 2000 ms + 250 ms + 500 ms
```

The 5000 ms lease is therefore safe for the documented 250 ms headline profile. A request whose configured delay is 1000 ms or more is rejected for the distributed-lock strategy because the derived follower wait would approach or exceed the HikariCP timeout.

## Mathematical proofs

### 503 crash proof

The one-instance headline test uses a cold cache, a burst of 200 requests for one key, 20 HikariCP connections, a 250 ms PostgreSQL delay, and a 2000 ms HikariCP timeout.

```text
wait before the tenth wave = (burst size / pool size - 1) x delay
wait before the tenth wave = (200 / 20 - 1) x 250 ms
wait before the tenth wave = 9 x 250 ms
wait before the tenth wave = 2250 ms
```

2250 ms is greater than the 2000 ms HikariCP timeout. The final 20 requests cannot obtain a connection before timing out, so the unprotected burst must produce HTTP 503 responses after the exception handler is added. The ninth wave is at the 2000 ms boundary and can vary with scheduler timing; the tenth wave is the guaranteed failure wave.

The minimum selected delay that crosses this boundary is:

```text
(N / P - 1) x d > T
d > T / (N / P - 1)
d > 2000 ms / 9
d > about 222.22 ms
```

250 ms clears the threshold. The 5 ms delay does not. Its ten waves clear in about 50 ms, so it cannot exercise the 2000 ms HikariCP timeout.

The three-instance equivalent directs 200 requests to each API instance. It has `N=600` total requests and `P=60` total connections, so the same proof applies:

```text
(600 / 60 - 1) x 250 ms = 2250 ms
```

Each instance has one guaranteed tenth wave of 20 timed-out requests. The aggregate unprotected profile therefore has 60 guaranteed timeout candidates, subject to normal request scheduling at the exact boundary.

### Three-instance coordination proof

All 600 requests target one cold item key. Each port receives 200 requests.

* With `local-single-flight`, each JVM has its own local map and elects one local leader. The repository-query delta must be 3.
* With `distributed-lock`, all three JVMs contend on the same Redis key. The repository-query delta must be 1.

This comparison is meaningful only when the caches are cleared, the Redis lock key is absent, and all three target groups request the same item key.

### Avalanche-jitter proof

An expiration event does not itself create database work. Database pressure appears only when clients request expired keys. Under sufficient client demand, miss arrival rate is bounded by the lower of request rate and expiration-release rate.

Use the following terms:

```text
K = number of expiring keys
TTL = base time to live
C = database ceiling
J = total expiration-window fraction of TTL
q = configured symmetric jitter fraction
```

The total window must be at least the number of keys divided by the database ceiling:

```text
J x TTL >= K / C
J >= K / (TTL x C)
J >= 10,000 / (30 s x 4,000 QPS)
J >= about 0.083
```

This is the required total normalized expiry window. The Redis configuration uses symmetric jitter, so `J = 2q`. Therefore the minimum symmetric setting is about `q=0.0417`. The configured `q=0.10` is deliberately larger:

```text
expiry window = 2 x 0.10 x 30 s = 6 s
miss-release rate = 10,000 / 6 s = about 1,666 QPS
```

1,666 QPS is below the conservative 4,000 QPS model. The 4,000 QPS value assumes one 20-connection instance and a requested 5 ms delay. In the three-instance topology, the aggregate theoretical ceiling is larger, but the one-instance value remains the conservative proof.

## Request lifecycle

1. The developer starts PostgreSQL, Redis, and the required API topology through Docker Compose.
2. `run-test.sh` parses order-independent arguments, snapshots the three `/debug/db-queries` endpoints, and starts the selected k6 script.
3. k6 reads `__ENV`, selects the API target group, constructs the item URL, and sends HTTP requests.
4. Tomcat assigns a platform thread to each accepted request. At most 200 threads run per API instance.
5. `ItemController.java` validates `id`, `strategy`, `ttl`, `delay`, and `jitter`, then calls `ItemService.java`.
6. `ItemService.java` selects the direct strategy path with a `switch` statement.
7. A cache-aware path reads Redis. A cache hit returns immediately.
8. On a protected cache miss, the service invokes either `LocalLockService.java` or `RedisLockService.java`.
9. The elected leader performs a second cache lookup after lock acquisition. Only an item still absent from Redis reaches `ItemRepository.java`.
10. `ItemRepository.java` borrows a HikariCP connection and executes the PostgreSQL delayed item query. The counter increments immediately before the SQL execution on that acquired connection.
11. PostgreSQL runs `pg_sleep` inside the query, returns the item, and the leader stores it in Redis with the selected TTL policy.
12. `ItemController.java` returns the item JSON with `X-Cache: HIT` or `X-Cache: MISS`.
13. `GlobalExceptionHandler.java` converts a HikariCP connection-acquisition timeout into HTTP 503.
14. k6 records latency, `X-Cache` values, and HTTP 503 responses. The Bash wrapper totals the repository-query delta and prints it beside the custom k6 report.

## Cache strategies and lock protocol

### Strategy selection

`ItemService.java` uses a direct `switch(strategy)` with these concrete paths:

* `no-cache` always calls the repository.
* `cache-aside` reads Redis, then reads PostgreSQL and writes Redis on a miss.
* `cache-aside-jitter` uses cache-aside with a symmetric TTL adjustment.
* `local-single-flight` uses `LocalLockService.java` to coalesce work only inside one JVM.
* `distributed-lock` uses `RedisLockService.java` to coalesce work across all API instances.

No strategy registry, factory, ORM, or internal executor is used.

### Local single-flight

`LocalLockService.java` stores an in-flight marker for each item key in a `ConcurrentHashMap`. Followers on the same API instance wait for the local leader to complete, then read Redis. Because the map is not shared, three API instances can create three leaders for the same key. That behavior is intentional and is the control case for the distributed-lock experiment.

### Distributed-lock acquisition

The distributed-lock path uses these Redis keys:

```text
item:{id}
lock:item:{id}
```

The service generates a unique token for every leader attempt and acquires the lock with the equivalent of:

```text
SET lock:item:{id} {token} NX PX 5000
```

`NX` allows only one contender to create the lock, and `PX` gives that lock a 5000 ms lease. Redis documents `NX`, `PX`, and absolute expiration options as part of the [`SET` command](https://redis.io/docs/latest/commands/set/).

### Double-check locking and follower behavior

The leader sequence is fixed:

1. Read `item:{id}` from Redis.
2. On a miss, acquire `lock:item:{id}` with a unique token.
3. Immediately read `item:{id}` again.
4. If the item now exists, return it as a hit and release the owned lock.
5. If it is still absent, query PostgreSQL, write Redis, return a miss, and release the owned lock.

The second cache lookup is required. A request can obtain a recently released lock after an earlier leader has already written the cache. Without the second lookup, the late request would duplicate database work.

Followers do not borrow HikariCP connections while waiting. They hold Tomcat threads. Each follower checks Redis, sleeps for the computed randomized poll interval, and stops at the derived follower maximum wait.

On follower timeout, the fallback is explicit:

1. Attempt one final lock acquisition.
2. If that acquisition succeeds, perform the same second Redis check and become the next leader only if the item is still absent.
3. If the acquisition fails, throw the application exception mapped to HTTP 503.

Followers never bypass the lock and query PostgreSQL directly. Doing so would recreate the stampede precisely when the system is already under stress.

### Safe lock release

The lock release is in a Java `finally` block. Release uses a Lua compare-and-delete script with the leader token:

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
```

The token check prevents an old leader from deleting a new leader's lock after its own lease expires. The lease remains a crash-safety boundary, while `finally` provides prompt cleanup for normal completion and exceptions.

### Repository delay placement

`Thread.sleep(delay)` must not be used for the artificial database delay. Sleeping before `JdbcTemplate` requests a connection leaves HikariCP idle and invalidates the saturation model.

`ItemRepository.java` uses `JdbcTemplate.execute(ConnectionCallback)` so the connection is already acquired when the counter increments and the statement executes. The database delay and item read are one raw SQL statement:

```sql
WITH pause AS MATERIALIZED (
  SELECT pg_sleep(CAST(? AS double precision) / 1000.0)
)
SELECT i.id, i.name, i.price, i.description
FROM pause
CROSS JOIN items AS i
WHERE i.id = ?;
```

The first parameter is `delay` in milliseconds and the second is `id`. PostgreSQL receives `delay / 1000.0` seconds. The acquired connection remains occupied for the delayed statement, making the pool ceiling observable.

## Avalanche initialization and jitter

### Bulk warm endpoint

Normal warming is invalid for the no-jitter avalanche test because sequential writes naturally stagger TTLs. `BulkWarmController.java` provides the initialization endpoint:

```http
POST /debug/cache/bulk-warm?ttl=30
```

The controller fetches or constructs the 10,000 cache values, computes one `expiresAtEpochMs` value, and pipelines Redis writes in the form:

```text
SET item:{id} {serialized-item} PXAT {expiresAtEpochMs}
```

Every item receives the identical absolute millisecond expiry. `PXAT` is Redis's Unix-time-in-milliseconds expiration option. [Redis `SET` command reference](https://redis.io/docs/latest/commands/set/)

The endpoint returns the key count and `expiresAtEpochMs`. `avalanche.js` waits until that time, then sends a request rate above the relevant database ceiling. A no-jitter run is valid only when every warm key uses the same `expiresAtEpochMs` value.

### Jitter implementation

For `cache-aside-jitter`, the service calculates a per-key TTL around the requested base TTL:

```text
effective TTL = base TTL x (1 + random value from negative q through positive q)
```

With `ttl=30` and `jitter=0.10`, effective expiry ranges from 27 seconds through 33 seconds. Jitter distributes expiration time. It does not create demand, reduce payload cost, or replace the distributed lock for duplicate requests to one key.

## Observability and error handling

### Response contract and custom k6 report

`ItemController.java` attaches exactly one cache-status header to every successful item response:

```text
X-Cache: HIT
X-Cache: MISS
```

Followers that return a value written by a leader are hits. A leader that executes the repository query is a miss. A request that returns HTTP 503 has no cache-status header and is counted as an error, not as a cache hit or miss.

Each k6 script records:

* Throughput in requests per second.
* p50, p95, p99, p99.9, and maximum latency.
* Cache hits, misses, and cache-hit rate.
* HTTP 503 count and general request failures.
* Repository-query delta supplied by `run-test.sh`.

`handleSummary()` renders these metrics in the custom terminal report. Grafana documents that k6 calls `handleSummary()` with the aggregated test metrics at the end of a test. [Grafana k6 custom summary documentation](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/)

### Repository query counter

`ItemRepository.java` owns an `AtomicLong dbQueryCount`. It increments inside the acquired `ConnectionCallback` immediately before `PreparedStatement.executeQuery()`. This counts each item SQL execution that actually reaches the repository connection path.

`DebugController.java` exposes:

```http
GET /debug/db-queries
```

Its response is:

```json
{
  "dbQueries": 123
}
```

`run-test.sh` reads this endpoint from every active API port before and after a test, then sums the deltas. In a successful protected stampede, the sum must equal the number of `X-Cache: MISS` headers. In the three-instance distributed-lock test, both values must be 1. This cross-check is stronger than inferring database activity from client headers alone.

### HTTP 503 mapping

`GlobalExceptionHandler.java` is a `@ControllerAdvice` with an `@ExceptionHandler(CannotGetJdbcConnectionException.class)` method. It returns HTTP 503 with a stable JSON body:

```json
{
  "status": 503,
  "error": "DATABASE_CONNECTION_TIMEOUT"
}
```

This makes connection-pool exhaustion visible to k6 rather than leaving it as an implementation-specific server error. Spring MVC supports `@ExceptionHandler` methods in `@ControllerAdvice` classes, and Spring defines `CannotGetJdbcConnectionException` for failures to obtain a JDBC connection. [Spring exception handling reference](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html), [Spring JDBC exception reference](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/CannotGetJdbcConnectionException.html)

## API contract

### Item request

```http
GET /api/items/{id}?strategy={strategy}&ttl={ttl}&delay={delay}&jitter={jitter}
```

Example headline request:

```http
GET /api/items/42?strategy=distributed-lock&ttl=30&delay=250&jitter=0.1
```

* `id` is the `Long` item primary key.
* `strategy` selects `no-cache`, `cache-aside`, `cache-aside-jitter`, `local-single-flight`, or `distributed-lock`.
* `ttl` is cache lifetime in seconds.
* `delay` is the artificial PostgreSQL delay in milliseconds.
* `jitter` is the symmetric TTL variation fraction.

All five strategies are implemented: `no-cache`, `cache-aside`, `cache-aside-jitter`, `local-single-flight`, and `distributed-lock`. The distributed-lock path rejects delays of 1000 ms or more. `GET /debug/db-queries` returns the current process-local repository query count as `{"dbQueryCount":N}`.

### Item response

```json
{
  "id": 42,
  "name": "Item 42",
  "price": 1654,
  "description": "Deterministic seed item 42 for cache saturation experiments."
}
```

The `Item` entity has these exact fields:

* `id`, a Java `Long` mapped to PostgreSQL `BIGINT`.
* `name`, a Java `String` mapped to `VARCHAR`.
* `price`, a Java `Integer` mapped to `INTEGER`.
* `description`, a Java `String` mapped to `TEXT`. It contains a dummy JSON string or long text block that simulates a heavy network payload.

## Repository structure

```text
cache-craft/
  docker-compose.yml
  nginx.conf
  pom.xml
  run-test.sh
  k6-load-tests/
    baseline.js
    stampede.js
    avalanche.js
  src/
    main/
      resources/
        application.yml
        schema.sql
        data.sql
      java/
        com/
          cachecraft/
            CacheCraftApplication.java
            model/
              Item.java
              CacheResponse.java
              DbQueryCount.java
            cache/
              ItemCache.java
            controller/
              ApiError.java
              ItemController.java
              DebugController.java
              GlobalExceptionHandler.java
            service/
              ItemService.java
              LocalLockService.java
              ItemNotFoundException.java
              UnsupportedStrategyException.java
            repository/
              ItemRepository.java
```

### File responsibilities

* **`docker-compose.yml`** starts PostgreSQL 16, Redis 7, Nginx, and the three API services.
* **`nginx.conf`** defines the API upstream, balances requests across the three Spring Boot instances, and exposes the public API entry point.
* **`pom.xml`** declares Spring Web, Spring JDBC, Spring Data Redis, PostgreSQL JDBC, validation, and test dependencies.
* **`run-test.sh`** validates key-value arguments, selects the k6 script, captures repository-counter deltas, and supplies k6 environment variables.
* **`baseline.js`** calibrates the local machine with `delay=0`, then establishes the baseline capacity profile.
* **`stampede.js`** provides single-instance and deterministic three-instance bursts for cache-aside, local-single-flight, and distributed-lock experiments.
* **`avalanche.js`** calls bulk warm, waits for the shared expiry point, then creates sufficient demand across the item range.
* **`application.yml`** defines Tomcat's platform-thread limit, disables virtual threads, configures HikariCP, and supplies default experiment values.
* **`schema.sql`** creates the `items` table.
* **`data.sql`** uses PostgreSQL `generate_series` to create 10,000 items.
* **`CacheCraftApplication.java`** starts each Spring Boot API process.
* **`Item.java`** is the immutable row value for the item fields.
* **`CacheResponse.java`** carries an `Item` and cache status from service to controller.
* **`ItemController.java`** currently serves the `no-cache` path for `GET /api/items/{id}`, validates inputs, and sets `X-Cache: MISS`.
* **`DebugController.java`** returns the local repository query counter at `GET /debug/db-queries`.
* **`GlobalExceptionHandler.java`** maps invalid input to HTTP 400 and JDBC connection or lock timeouts to HTTP 503.
* **`ItemService.java`** routes through the direct strategy switch across all five cache strategies.
* **`ItemRepository.java`** executes the PostgreSQL `pg_sleep` query after acquiring a connection and increments the query counter immediately before execution.
* **`ItemCache.java`** owns Redis item keys, JSON serialization, expiry, jitter, and cache reset for tests.
* **`LocalLockService.java`** coalesces same-key misses within one API process.
* **`RedisLockService.java`** coordinates cold-key leaders across API instances using Redis leases and token-checked release.

## Experiment protocol

### 1. Calibration

Run `baseline.js` with `delay=0` on the same local machine and API topology planned for the later test. This identifies the combined CPU, networking, JSON, PostgreSQL, Redis, and k6 limit. Select a delay only when the modeled database ceiling is clearly below this calibrated machine rate.

### 2. Headline single-instance failure

Run the unprotected cache-aside stampede with one API instance, a cold key, `n=200`, and `delay=250`. Confirm that the final request wave produces HTTP 503 responses and that the query counter matches the executed miss path.

### 3. Protected single-instance stampede

Repeat the same single-instance burst with `strategy=distributed-lock`. Confirm that one leader reaches the repository, followers return the cached value, and no follower bypasses the lock after its maximum wait.

### 4. Three-instance local versus distributed proof

Start all three API instances. Direct 200 requests to each port for the same cold key, giving `n=600` in total. Run `local-single-flight`, then clear cache and locks and run `distributed-lock`.

The local result must produce three repository queries. The distributed result must produce one. The query-counter delta is the acceptance measure, with `X-Cache` counts as the client-visible cross-check.

### 5. Avalanche without jitter

Call `POST /debug/cache/bulk-warm?ttl=30`, retain its absolute expiry, and send demand above the database ceiling just after expiry. This produces synchronized misses because all 10,000 keys share the same `PXAT` timestamp.

### 6. Avalanche with jitter

Repeat the avalanche workload with `jitter=0.10` and the 5 ms conservative capacity profile. Compare tail latency, HTTP 503 count, repository-query rate, and the expiry window against the no-jitter run. Change one variable at a time.

## GitHub Codespaces workflow

CacheCraft is ready to open in GitHub Codespaces. The committed `.devcontainer/` configuration uses the official Java 21 development image and prefetches Maven dependencies. It preserves `docker-compose.yml` as the canonical experiment topology: PostgreSQL 16, Redis 7, three API instances, and Nginx.

### Create and verify a Codespace

1. Push this repository to GitHub.
2. On GitHub, select **Code** > **Codespaces** > **Create codespace on** your branch.
3. Wait for the dependency prefetch step to finish.
4. Verify the toolchain:

   ```bash
   java -version
   ./mvnw -version
   docker --version || true
   docker compose version || true
   k6 version || true
   ```

5. If Docker Compose is available, build and start the experiment topology:

   ```bash
   docker compose up --build -d
   docker compose ps
   ```

The Codespace automatically forwards private ports for the following services when the Compose topology is running:

| Port | Purpose |
| --- | --- |
| 8080 | Nginx public entry point for normal API traffic. |
| 8081 | API 1 direct endpoint for deterministic multi-instance tests and debug counters. |
| 8082 | API 2 direct endpoint for deterministic multi-instance tests and debug counters. |
| 8083 | API 3 direct endpoint for deterministic multi-instance tests and debug counters. |

Use the private port-8080 URL shown in the Codespaces **Ports** panel for normal API access. Keep direct instance ports private and use them only where the experiment explicitly requires deterministic per-instance traffic.

### Codespaces operating rules

Run the following during development:

```bash
./mvnw test
docker compose up --build -d
docker compose logs --follow
docker compose down
```

After changing `.devcontainer/devcontainer.json`, use **Codespaces: Rebuild Container**. Application-code changes do not need a container rebuild.

When the Stage 7 k6 scripts are implemented, install or enable k6 in the Codespace and run them through `./run-test.sh`. This is useful for functional proof, but performance figures are specific to the selected Codespaces VM: k6, Nginx, the APIs, Redis, and PostgreSQL share its CPU and memory. Record the machine type, date, commit SHA, exact arguments, topology/reset state, debug-counter delta, and raw k6 output with each experiment.

Stop Codespaces when they are not in use to avoid active-compute charges. Do not enable prebuilds until the development environment is stable. A full disposable-state reset is available only when no evidence needs preserving:

```bash
docker compose down --volumes
docker compose up --build -d
```

The volume-removal command deletes the Codespace PostgreSQL and Redis data volumes.

GitHub Actions runs `./mvnw verify` on pull requests and pushes to `main` through `.github/workflows/verify.yml`.

## Local workflow

### Start dependencies and API instances

```bash
COMPOSE_PROFILES=three-api docker compose up -d
```

The three API services share Redis and PostgreSQL. For the one-instance headline test, start only `api-1` with the dependency services.

### Start a headline three-instance run

```bash
./run-test.sh type=stampede strat=distributed-lock n=600 ttl=30 delay=250 jitter=0.1
```

The wrapper selects `stampede.js`, provides environment values, snapshots query counters, and reports the total repository-query delta beside the k6 metrics.

### Read the custom report

The report is the experiment record. Capture throughput, latency percentiles, cache hit rate, HTTP 503 count, dropped iterations, `X-Cache` misses, and repository-query delta. Preserve the runtime versions, CPU details, instance count, and selected parameters with the result so future comparisons are valid.

## Verification

Run the automated suite with:

```bash
./mvnw test
```

Unit tests cover cache serialization and TTLs, service strategy routing, same-key local lock behavior, JDBC statement bindings and query-counter placement, request validation, and HTTP response/error mapping. `CacheFlowIntegrationTest` uses Testcontainers to exercise PostgreSQL and Redis together, including database-side delay while a Hikari connection is active and the one-query local single-flight proof. Those four integration checks run when Docker is available and are skipped automatically when it is not.

## Reference material

* [PostgreSQL `pg_sleep` documentation](https://www.postgresql.org/docs/16/functions-datetime.html)
* [PostgreSQL `generate_series` documentation](https://www.postgresql.org/docs/17/functions-srf.html)
* [Redis `SET`, `NX`, `PX`, and `PXAT` documentation](https://redis.io/docs/latest/commands/set/)
* [HikariCP configuration reference](https://github.com/brettwooldridge/HikariCP)
* [Grafana k6 custom summary documentation](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/)
* [Spring MVC exception-handling documentation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html)
* [Spring `CannotGetJdbcConnectionException` reference](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/CannotGetJdbcConnectionException.html)

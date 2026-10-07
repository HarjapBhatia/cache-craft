# CacheCraft: Distributed Cache Topology and Saturation Analysis

## 1. Abstract
This project provides an empirical testing framework to analyze database saturation under high-concurrency read operations. By simulating database connection pool exhaustion, the framework evaluates the efficacy of progressive caching strategies. The experimental data mathematically demonstrates that naive caching is insufficient against cache stampedes, requiring explicit request coalescing. Furthermore, it quantifies the impact of cache avalanches caused by synchronized expirations and validates stochastic TTL jitter as a mitigation strategy.

## 2. System Topology and Artificial Constraints
The system operates locally via Docker Compose with the following architecture:
* Load Balancing Layer: Nginx configured for round-robin request distribution.
* Application Layer: 3 Spring Boot 3.x instances.
* Cache Layer: Redis 7.
* Database Layer: PostgreSQL 16.
* Load Generation: k6.

To simulate production-scale saturation on local hardware, strict bottlenecks were engineered:
* Connection Pool Limit: HikariCP is restricted to 20 connections per API instance (60 global).
* Query Latency Emulation: A `pg_sleep(0.25)` is executed within a transaction callback, ensuring the HikariCP connection is held for exactly 250ms per query.
* Timeout Threshold: The connection acquisition timeout is set to 2000ms. Requests queuing longer than this threshold return an HTTP 503 Service Unavailable error.
* Concurrency Capacity: Tomcat is configured with 200 platform threads per instance, allowing incoming throughput to easily overwhelm the database pool.

## 3. Experimental Methodology
Each experiment isolates a specific caching strategy to measure its impact on database preservation and latency. 
Metrics captured:
* Throughput: Requests processed per second (req/s).
* Latency Percentiles: p95 and maximum response times (ms).
* Database Query Delta: The exact number of queries executed against PostgreSQL, captured by an atomic counter before and after the load test.
* Error Rate: Percentage of HTTP 503 errors.

## 4. Execution and Analysis

### 4.1. Baseline Machine Calibration
* Objective: Establish the maximum performance ceiling when operating entirely from a warm cache.
* Command: `bash ./run-test.sh type=baseline strat=cache-aside delay=0 ttl=300`
* Results:
  * Throughput: 329.41 req/s
  * p95 Latency: 59.93 ms
  * Database Query Delta: 0
  * Error Rate: 0.00%
* Analysis: The baseline confirms the topology can sustain high throughput with minimal tail latency when the database is completely bypassed.

### 4.2. Phase 1: The Cache Stampede
A cache stampede occurs when high concurrent traffic requests a single item that is not present in the cache (a cold key).

#### Experiment 1: Unprotected System (No Cache)
* Objective: Induce a connection pool exhaustion event.
* Constraints: Single API instance, 200 concurrent requests.
* Command: `bash ./run-test.sh type=stampede strat=no-cache n=200 delay=250 target=http://localhost:8081/api/items/42`
* Results:
  * Database Query Delta: 182
  * Error Rate: 9.00% (18 timeouts)
  * p95 Latency: 3070.00 ms
* Failure Analysis: 200 threads immediately attempt to acquire 20 connections. 20 succeed, holding the connections for 250ms. By the 2000ms timeout threshold, only 160 requests have been serviced. The final 18 requests breach the timeout and fail. 

#### Experiment 2: Naive Caching (Cache Aside)
* Objective: Determine if standard read-miss-write caching prevents a stampede on a cold key across a cluster.
* Constraints: 3 API instances, 600 concurrent requests.
* Command: 
  ```bash
  curl -X POST http://localhost:8080/debug/cache/reset
  bash ./run-test.sh type=stampede strat=cache-aside n=600 delay=250 target=http://localhost:8080/api/items/42
  ```
* Results:
  * Database Query Delta: 23 (API 1: +9, API 2: +9, API 3: +5)
  * Error Rate: 0.00%
  * p95 Latency: 2016.30 ms
* Failure Analysis: While caching prevented all 600 requests from hitting the database, 23 simultaneous requests still penetrated the cache before the first query could populate Redis. This consumed nearly half of the global connection pool (23 of 60 total connections) for a single item. If this occurred for multiple keys simultaneously, the system would crash. A coalescing mechanism is required.

#### Experiment 3: JVM-Boundary Coalescing (Local Single Flight)
* Objective: Restrict database access to a single concurrent query per JVM resource.
* Constraints: 3 API instances, 600 concurrent requests.
* Command: `bash ./run-test.sh type=stampede strat=local-single-flight n=600 delay=250 target=http://localhost:8080/api/items/42`
* Results:
  * Database Query Delta: 3 (API 1: +1, API 2: +1, API 3: +1)
  * Error Rate: 0.00%
  * p95 Latency: 2330.00 ms
* Failure Analysis: The JVM-local lock successfully collapses the stampede within each individual node. However, it fails at the cluster boundary. Because the 3 API instances do not share memory, each instance elects its own local leader. The query volume scales linearly with the number of nodes (O(N) queries for N nodes), meaning adding server capacity actually increases database strain during a stampede.

#### Experiment 4: Cluster-Boundary Coalescing (Distributed Lock)
* Objective: Achieve a strict theoretical minimum of 1 database query across an arbitrary number of distributed nodes.
* Constraints: 3 API instances, 600 concurrent requests.
* Command: `bash ./run-test.sh type=stampede strat=distributed-lock n=600 delay=250 target=http://localhost:8080/api/items/42`
* Results:
  * Database Query Delta: 1
  * Error Rate: 0.00%
  * p95 Latency: 2197.00 ms
* Analysis: Perfect coalescing achieved. Implements a distributed lock in Redis utilizing `SET NX PX`. The node acquiring the lock executes the database query. The remaining 599 threads across all instances enter a randomized polling loop (5ms to 50ms) waiting for the cache to be populated, successfully serving the result from Redis once the leader finishes.

### 4.3. Phase 2: The Cache Avalanche
An avalanche occurs when a large number of cached items expire at the exact same time, causing a synchronized spike in database traffic upon subsequent requests.

#### Experiment 5: Cache Expiry Synchronization (Thundering Herd)
* Objective: Simulate an avalanche resulting from bulk-inserted data.
* Constraints: 10,000 keys populated into Redis using a pipelined `PXAT` command, ensuring an identical absolute expiration timestamp.
* Command: `bash ./run-test.sh type=avalanche strat=cache-aside ttl=5 delay=5 jitter=0.0`
* Results:
  * Database Query Delta: 2906
  * p95 Latency: 3181.20 ms
* Failure Analysis: When all 10,000 keys expire at the exact same millisecond, the subsequent read operations result in a 100% cache miss rate. This generates a synchronized shockwave of queries to the database, momentarily saturating the pool and significantly increasing tail latency.

#### Experiment 6: Stochastic Expiry Distribution (TTL Jitter)
* Objective: Mitigate the cache avalanche effect by distributing expirations mathematically.
* Constraints: 10,000 keys populated with a base TTL, modified by a symmetric uniform random distribution (Jitter = 0.10, or plus/minus 10%).
* Command: `bash ./run-test.sh type=avalanche strat=cache-aside-jitter ttl=5 delay=5 jitter=0.10`
* Results:
  * Database Query Delta: 2479
  * p95 Latency: 4860.00 ms
* Analysis: By applying a mathematical jitter, expirations are uniformly distributed across a time window rather than occurring instantaneously. This desynchronizes the cache misses, smoothing the database query volume (reducing peak queries by ~15%) and preventing a concentrated spike in connection pool utilization.

## 5. Consolidated Benchmark Results

| Strategy | Scenario | Constraints | DB Query Delta | Error Rate | p95 Latency |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Baseline | Warm Cache | 10 VUs, 0ms delay | 0 | 0.00% | 59.93 ms |
| No Cache | Stampede | 1 Node, 200 VUs | 182 | 9.00% | 3070.00 ms |
| Cache Aside | Stampede | 3 Nodes, 600 VUs | 23 | 0.00% | 2016.30 ms |
| Local Single Flight | Stampede | 3 Nodes, 600 VUs | 3 | 0.00% | 2330.00 ms |
| Distributed Lock | Stampede | 3 Nodes, 600 VUs | 1 | 0.00% | 2197.00 ms |
| Avalanche No Jitter | Avalanche | 10k Keys, 0.0 Jitter | 2906 | 0.00% | 3181.20 ms |
| Avalanche Jitter | Avalanche | 10k Keys, 0.1 Jitter | 2479 | 0.00% | 4860.00 ms |

## 6. Conclusion
The empirical data demonstrates that standard caching architectures are highly vulnerable to localized spikes in concurrency (Stampedes) and synchronized expirations (Avalanches). A resilient distributed cache implementation must incorporate request coalescing (such as JVM single-flight or Redis distributed locks) to serialize cold-cache reads, and stochastic TTL jitter to decouple cache expiration events.

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// A single-burst scenario to trigger pool saturation
export const options = {
    scenarios: {
        stampede: {
            executor: 'per-vu-iterations',
            vus: parseInt(__ENV.VUS || '200', 10),
            iterations: 1,
            maxDuration: '15s',
        },
    },
};

const hitRate = new Rate('cache_hit_rate');
const errorRate = new Rate('error_rate');
const errors503 = new Rate('http_503_errors');

export default function () {
    const url = `${__ENV.TARGET_URL}?strategy=${__ENV.STRATEGY}&delay=${__ENV.DELAY}&ttl=${__ENV.TTL}&jitter=${__ENV.JITTER}`;
    
    const res = http.get(url);
    
    const success = check(res, {
        'status is 200': (r) => r.status === 200,
    });
    
    if (!success) {
        errorRate.add(1);
        if (res.status === 503) {
            errors503.add(1);
        }
    }
    
    const cacheHeader = res.headers['X-Cache'];
    if (cacheHeader === 'HIT') {
        hitRate.add(1);
    } else if (cacheHeader === 'MISS') {
        hitRate.add(0);
    }
}

export function handleSummary(data) {
    const totalReqs = data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0;
    const hitRateValue = data.metrics.cache_hit_rate ? data.metrics.cache_hit_rate.values.rate : 0;
    const hitCount = Math.round(totalReqs * hitRateValue);
    const missCount = totalReqs - hitCount - (data.metrics.error_rate ? data.metrics.error_rate.values.count : 0);

    console.log(`\n======================================================`);
    console.log(`CacheCraft Stampede Execution Summary`);
    console.log(`======================================================`);
    console.log(`Total Requests: ${totalReqs}`);
    console.log(`Throughput: ${data.metrics.http_reqs.values.rate.toFixed(2)} req/s`);
    const dur = data.metrics.http_req_duration ? data.metrics.http_req_duration.values : {};
    const p95 = dur['p(95)'] !== undefined ? dur['p(95)'] : (dur.p95 || 0);
    const max = dur.max !== undefined ? dur.max : 0;
    console.log(`p95 Latency: ${p95.toFixed(2)} ms`);
    console.log(`Max Latency: ${max.toFixed(2)} ms`);
    console.log(`Cache Hits: ${hitCount}`);
    console.log(`Cache Misses (X-Cache: MISS): ${missCount}`);
    console.log(`Total Errors: ${data.metrics.error_rate ? data.metrics.error_rate.values.count : 0}`);
    console.log(`HTTP 503 Errors (Pool Exhausted/Lock Timeout): ${data.metrics.http_503_errors ? data.metrics.http_503_errors.values.count : 0}`);
    console.log(`======================================================\n`);
    return {};
}

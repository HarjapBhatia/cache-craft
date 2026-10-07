import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

export const options = {
    scenarios: {
        baseline: {
            executor: 'constant-vus',
            vus: 10,
            duration: '10s',
        },
    },
};

const hitRate = new Rate('cache_hit_rate');
const errorRate = new Rate('error_rate');

export default function () {
    const url = `${__ENV.TARGET_URL}?strategy=${__ENV.STRATEGY}&delay=${__ENV.DELAY}&ttl=${__ENV.TTL}&jitter=${__ENV.JITTER}`;
    
    const res = http.get(url);
    
    const success = check(res, {
        'status is 200': (r) => r.status === 200,
    });
    
    if (!success) {
        errorRate.add(1);
    }
    
    const cacheHeader = res.headers['X-Cache'];
    if (cacheHeader === 'HIT') {
        hitRate.add(1);
    } else if (cacheHeader === 'MISS') {
        hitRate.add(0);
    }
}

export function handleSummary(data) {
    console.log(`\n======================================================`);
    console.log(`CacheCraft Baseline Execution Summary`);
    console.log(`======================================================`);
    console.log(`Throughput: ${data.metrics.http_reqs.values.rate.toFixed(2)} req/s`);
    console.log(`p95 Latency: ${data.metrics.http_req_duration.values.p95.toFixed(2)} ms`);
    console.log(`Max Latency: ${data.metrics.http_req_duration.values.max.toFixed(2)} ms`);
    console.log(`Cache Hit Rate: ${(data.metrics.cache_hit_rate ? data.metrics.cache_hit_rate.values.rate * 100 : 0).toFixed(2)}%`);
    console.log(`Errors: ${data.metrics.error_rate ? data.metrics.error_rate.values.count : 0}`);
    console.log(`======================================================\n`);
    return data;
}

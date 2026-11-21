import exec from 'k6/execution';
import http from 'k6/http';
import { check } from 'k6';

const rate = Number(__ENV.ORDER_RATE);
const actors = Number(__ENV.ORDER_ACTORS);

export const options = {
    scenarios: {
        confirm_order: {
            executor: 'constant-arrival-rate',
            rate,
            timeUnit: '1s',
            duration: __ENV.LOAD_DURATION,
            preAllocatedVUs: rate,
            maxVUs: rate * 5,
            gracefulStop: '5s',
        },
    },
    systemTags: ['scenario', 'name', 'status'],
};

export default function () {
    const iteration = exec.scenario.iterationInTest;
    const orderId = iteration + 1;
    const loginId = 'loaduser' + (iteration % actors + 1);
    const response = http.post(__ENV.ORDER_API_URL + '/api/v1/orders/' + orderId + '/confirm', null, {
        headers: { 'X-USER-ID': loginId },
        tags: { name: 'confirm_order' },
        timeout: '4s',
    });
    check(response, {
        'order confirmed': (result) => result.status === 200 && result.json('data.status') === 'CONFIRMED',
    });
}

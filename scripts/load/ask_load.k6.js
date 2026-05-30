// k6 부하 테스트(차수 17 #253). dev 엔드포인트 /dev/ask 에 동시 요청을 보내 라우팅·풀 처리량을 측정.
// 전제: central-server 가 CENTRAL_DEV_ENABLED=true(개발용)로 기동, 에이전트가 풀에 연결되어 있어야 함.
// 실행: k6 run -e BASE_URL=http://localhost:8080 -e GUILD=100 scripts/load/ask_load.k6.js
import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const GUILD = __ENV.GUILD || "100";

export const options = {
  scenarios: {
    ramp: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "30s", target: 10 },
        { duration: "1m", target: 25 },
        { duration: "30s", target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.10"], // 실패율 10% 미만
    http_req_duration: ["p(95)<5000"], // p95 5s 미만
  },
};

export default function () {
  const payload = JSON.stringify({ guildId: Number(GUILD), userId: Math.floor(__VU), prompt: "부하 테스트 질문" });
  const res = http.post(`${BASE_URL}/dev/ask`, payload, {
    headers: { "Content-Type": "application/json" },
  });
  check(res, {
    "status 2xx/4xx (서버 살아있음)": (r) => r.status > 0 && r.status < 500,
  });
  sleep(1);
}

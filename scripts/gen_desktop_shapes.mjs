// 데스크톱 앱 응답 shape 계약 SSOT 생성기.
//
// 목적: 프로토타입 mock 과 실 webui 응답의 **필드명 정합**을 강제하기 위한 계약 JSON 을 만든다.
//   - passthrough: adapter 가 mock 결과를 그대로 UI 에 주는 엔드포인트 → mock 의 키 = 실 webui 응답이
//     반드시 가져야 할 키(real ⊇ mock). adapter.js mock 에서 **자동 추출**(드리프트 불가).
//   - consumed: adapter 실 분기가 webui 응답을 **변환**할 때 읽는 raw 필드(서버목록·모델). mock 과 모양이
//     다르므로 여기서 명시(어댑터 변환이 바뀌면 같이 갱신).
//
// provider-agent tests/test_desktop_shapes.py 가 이 JSON 으로 실 webui 응답을 검증한다.
// `--check` 는 커밋된 JSON 이 현재 mock 과 동기인지 확인(드리프트 시 비정상 종료).
//
//   node scripts/gen_desktop_shapes.mjs          # 생성/갱신
//   node scripts/gen_desktop_shapes.mjs --check   # 드리프트 검사
import { api } from '../prototypes/desktop/adapter.js';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'prototypes', 'desktop', 'contract-shapes.json');

// passthrough: adapter 가 http(endpoint) 결과를 그대로 반환(변환 없음) → mock 키 == 실 응답 키.
const PASSTHROUGH = [
  ['/api/status', () => api.getStatus()],
  ['/api/settings', () => api.getSettings()],
  ['/api/update-info', () => api.getUpdateInfo()],
  ['/api/update-progress', () => api.getUpdateProgress()],
  ['/api/logs', () => api.getLogs()],
];

// consumed: adapter 실 분기가 webui 응답에서 읽는 raw 필드(변환 입력). 어댑터 변환과 함께 유지.
const CONSUMED = {
  '/api/servers': { array: ['guildId', 'guildName', 'connected', 'paused'] }, // getServers 가 읽음
  '/api/models': { object: ['models', 'selected', 'default'] },               // getModels 가 읽음
};

async function build() {
  const passthrough = {};
  for (const [ep, call] of PASSTHROUGH) {
    const r = await call();
    passthrough[ep] = Object.keys(r).sort();
  }
  return { passthrough, consumed: CONSUMED };
}

const data = await build();
const json = JSON.stringify(data, null, 2) + '\n';

if (process.argv.includes('--check')) {
  let current = '';
  try { current = readFileSync(OUT, 'utf-8'); } catch { /* 없음 */ }
  if (current !== json) {
    console.error('❌ contract-shapes.json 이 adapter mock 과 다릅니다 — `node scripts/gen_desktop_shapes.mjs` 로 재생성 후 커밋하세요.');
    process.exit(1);
  }
  console.log('✅ contract-shapes.json 동기(드리프트 없음)');
} else {
  writeFileSync(OUT, json);
  console.log('생성:', OUT);
}

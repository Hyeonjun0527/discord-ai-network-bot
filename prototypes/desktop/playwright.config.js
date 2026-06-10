// NEXA 데스크톱 프로토타입 — E2E 테스트 설정.
// 정적 서버(python http.server)를 자동 기동하고 시스템 Chrome 으로 검증한다(브라우저 다운로드 없음).
const { defineConfig, devices } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests',
  fullyParallel: true,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:8777',
    channel: 'chrome', // 시스템 Chrome 사용
    locale: 'ko-KR', // i18n 기본 언어를 한국어로 고정(결정성) — 앱은 OS 로케일 자동감지라 미설정 시 러너 로케일에 좌우됨
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chrome', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
  webServer: {
    command: 'python3 -m http.server 8777 --bind 127.0.0.1',
    url: 'http://127.0.0.1:8777/index.html',
    reuseExistingServer: true,
    timeout: 10_000,
  },
});

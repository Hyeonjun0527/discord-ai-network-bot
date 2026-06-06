// ════════════════════════════════════════════════════════════════════════
// 상태머신 SSOT (코드) — docs/NEXA_STATE_MACHINE.md 와 1:1.
// stage/authed/connectOrigin 의 단일 소스. 화면은 여기서 상태를 읽고 가드를 따른다.
// 불변식(I1~I5)을 코드로 강제한다. 위반은 console.warn 으로 드러낸다(조용히 넘어가지 않음).
// ════════════════════════════════════════════════════════════════════════

export const App = {
  // 프로토타입 기본: 온보딩을 마치고 사용 중인 유저 → 첫 화면은 main, 인증됨.
  authed: true,
  stage: 'main',                 // 'onboarding' | 'connect' | 'main'
  connectOrigin: 'onboarding',   // 'onboarding'(첫 인증) | 'main'(서버 추가)
};

const warn = (msg) => console.warn('[SSOT 위반] ' + msg + ' (docs/NEXA_STATE_MACHINE.md)');

// I2 — 'Discord 로그인' UI 노출은 미인증일 때만.
export function showLoginAllowed() { return !App.authed; }

// enterServerAdd 가드 — I3: 서버 추가(connect/main)는 인증 후에만.
export function canEnterServerAdd() {
  if (!App.authed) { warn('서버 추가(connect/main)는 authed 후에만 가능 — I3'); return false; }
  return true;
}

// setStage 진입 시 stage 별로 authed 보정 → I1 보장(main ⟹ authed). 위반 가능 경로는 warn.
export function applyStage(stage) {
  App.stage = stage;
  if (stage === 'onboarding') App.authed = false;       // 첫 설정 = 미인증
  else if (stage === 'main') App.authed = true;          // 사이드바 = 인증 완료(I1)
  // connect 는 connectOrigin 에 따라 authed 가 이미 결정됨(onboarding:false, main:true)
  if (stage === 'connect' && App.connectOrigin === 'main' && !App.authed) {
    warn('connect(origin=main)인데 미인증 — I3');
  }
  return App.stage;
}

if (typeof window !== 'undefined') window.App = App; // 일반 script 호환

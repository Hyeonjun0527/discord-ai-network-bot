// ════════════════════════════════════════════════════════════════════════
// 런타임 설치(C). 설치 "진행 표시"의 SSOT 는 토스트 프로그레스바(installRuntime).
//   · installRuntime(k, model) — 토스트로 진행률 표시(온보딩·홈 공용). 설치 진행 UI 는 여기 하나뿐.
//   · openInstall(k) — 홈 등에서 트리거. SD 는 모델 선택 모달 → 선택 후 installRuntime. Ollama 는 바로.
// 진행은 adapter.getSetupProgress(runtime) 폴링({ phase, percent, message, error }).
// ════════════════════════════════════════════════════════════════════════
import { api } from './adapter.js';
import { toast } from './toast.js';
import { t } from './i18n.js';

const RUNTIME_LABEL = { ollama: 'Ollama' }; // 이미지 엔진은 ComfyUI(로컬 실행 탭에서 설치/관리)

// ── 진행 폴링 공용 코어. installRuntime(설치 시작)·watchSetup(감시만)이 공유한다. ──
// startSetup=true 면 설치를 시작하고, false 면 이미 진행 중인 설치를 폴링만 한다(새로고침 복원용).
function _trackSetup(k, model, startSetup) {
  const name = (k === 'ollama' && model) ? model : (RUNTIME_LABEL[k] || k); // 모델 pull 이면 모델명 표시
  const id = 'inst-' + (model || k);
  let dismissed = false; // ✕ 로 알림을 닫으면 true — 설치는 계속, 토스트만 안 띄움
  return new Promise((resolve) => {
    toast(t('installInstalling').replace('{name}', name), { type: 'run', sticky: true, id, sub: t('installPreparing'), progress: 0, onClose: () => { dismissed = true; } });
    // 설치 시작 호출이 reject 돼도 폴링이 진행 상태(error phase)로 잡으므로, unhandled rejection 만 방지한다.
    if (startSetup) { const started = api.startSetup(k, model); if (started && typeof started.catch === 'function') started.catch((e) => console.error('설치 시작 호출 실패:', e)); }
    const timer = setInterval(async () => {
      // 폴링 콜백을 감싸 한 번의 진행 조회 실패가 unhandled rejection 이 되거나 폴링을 멈추지 않게 한다(예외 원칙 3).
      let p;
      try {
        p = await api.getSetupProgress(k);
      } catch (e) {
        console.warn('설치 진행 조회 실패(다음 폴링에서 재시도):', e);
        return;
      }
      if (p.phase === 'done') {
        clearInterval(timer);
        if (!dismissed) toast(t('installComplete').replace('{name}', name), { type: 'ok', id, sub: t('installReady'), progress: 100 });
        window.dispatchEvent(new CustomEvent('runtimeinstalled', { detail: k })); // 홈 카드가 받아 '실행 중'으로
        resolve(true);
      } else if (p.phase === 'error' || p.phase === 'cancelled') {
        clearInterval(timer);
        if (!dismissed) toast((p.phase === 'cancelled' ? t('installCancelled') : t('installFailed')).replace('{name}', name), { type: 'info', id, sub: String(p.error || p.message || '') });
        resolve(false);
      } else if (p.phase === 'idle' && !startSetup) {
        // 감시 모드인데 진행 상태가 아니면(이미 끝났거나 시작 안 됨) 토스트를 띄우지 않고 종료.
        clearInterval(timer);
        resolve(false);
      } else if (!dismissed) {
        toast(t('installInstalling').replace('{name}', name), { type: 'run', sticky: true, id, sub: (p.message || t('installInProgress')), progress: p.percent, onClose: () => { dismissed = true; } });
      }
    }, 450);
  });
}

// ── 설치 진행 = 토스트 프로그레스바(단일 소스). 온보딩 finishOnboarding·홈 설치 버튼이 모두 이걸 쓴다. ──
export function installRuntime(k, model) { return _trackSetup(k, model, true); }

// ── 이미 진행 중인 설치를 감시만(설치 시작 없이 폴링·진행 토스트 복원). 새로고침/부팅 시 복원용. ──
export function watchSetup(k) { return _trackSetup(k, undefined, false); }

// ── 설치 트리거(홈/온보딩). Ollama 만 — 이미지 엔진(ComfyUI)은 로컬 실행 탭에서 직접 관리. ──
export function openInstall(k) { installRuntime(k); }

window.openInstall = openInstall;     // PROTO·홈 호환
window.installRuntime = installRuntime;
window.watchSetup = watchSetup;

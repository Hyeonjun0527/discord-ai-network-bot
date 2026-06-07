// ════════════════════════════════════════════════════════════════════════
// 런타임 설치(C). 설치 "진행 표시"의 SSOT 는 토스트 프로그레스바(installRuntime).
//   · installRuntime(k, model) — 토스트로 진행률 표시(온보딩·홈 공용). 설치 진행 UI 는 여기 하나뿐.
//   · openInstall(k) — 홈 등에서 트리거. SD 는 모델 선택 모달 → 선택 후 installRuntime. Ollama 는 바로.
// 진행은 adapter.getSetupProgress(runtime) 폴링({ phase, percent, message, error }).
// ════════════════════════════════════════════════════════════════════════
import { api } from './adapter.js';
import { toast } from './toast.js';

const RUNTIME_LABEL = { ollama: 'Ollama', image: 'Stable Diffusion' };

// ── 진행 폴링 공용 코어. installRuntime(설치 시작)·watchSetup(감시만)이 공유한다. ──
// startSetup=true 면 설치를 시작하고, false 면 이미 진행 중인 설치를 폴링만 한다(새로고침 복원용).
function _trackSetup(k, model, startSetup) {
  const name = (k === 'ollama' && model) ? model : (RUNTIME_LABEL[k] || k); // 모델 pull 이면 모델명 표시
  const id = 'inst-' + (model || k);
  let dismissed = false; // ✕ 로 알림을 닫으면 true — 설치는 계속, 토스트만 안 띄움
  return new Promise((resolve) => {
    toast(name + ' 설치 중', { type: 'run', sticky: true, id, sub: '설치 준비 중', progress: 0, onClose: () => { dismissed = true; } });
    if (startSetup) api.startSetup(k, model);
    const timer = setInterval(async () => {
      const p = await api.getSetupProgress(k);
      if (p.phase === 'done') {
        clearInterval(timer);
        if (!dismissed) toast(name + ' 설치 완료', { type: 'ok', id, sub: '사용 준비됨', progress: 100 });
        window.dispatchEvent(new CustomEvent('runtimeinstalled', { detail: k })); // 홈 카드가 받아 '실행 중'으로
        resolve(true);
      } else if (p.phase === 'error' || p.phase === 'cancelled') {
        clearInterval(timer);
        if (!dismissed) toast(name + ' 설치 ' + (p.phase === 'cancelled' ? '취소됨' : '실패'), { type: 'info', id, sub: String(p.error || p.message || '') });
        resolve(false);
      } else if (p.phase === 'idle' && !startSetup) {
        // 감시 모드인데 진행 상태가 아니면(이미 끝났거나 시작 안 됨) 토스트를 띄우지 않고 종료.
        clearInterval(timer);
        resolve(false);
      } else if (!dismissed) {
        toast(name + ' 설치 중', { type: 'run', sticky: true, id, sub: (p.message || '진행 중'), progress: p.percent, onClose: () => { dismissed = true; } });
      }
    }, 450);
  });
}

// ── 설치 진행 = 토스트 프로그레스바(단일 소스). 온보딩 finishOnboarding·홈 설치 버튼이 모두 이걸 쓴다. ──
export function installRuntime(k, model) { return _trackSetup(k, model, true); }

// ── 이미 진행 중인 설치를 감시만(설치 시작 없이 폴링·진행 토스트 복원). 새로고침/부팅 시 복원용. ──
export function watchSetup(k) { return _trackSetup(k, undefined, false); }

// ── SD 모델 선택 모달(진행은 안 함, 선택만). Ollama 는 모달 없이 바로 설치. ──
let layer, card;
function ensureEls() { layer = document.getElementById('installModal'); card = document.getElementById('installCard'); }
function close() { if (layer) layer.hidden = true; }

export function openInstall(k) {
  if (k !== 'image') { installRuntime(k); return; } // Ollama: 모델 선택 불필요 → 바로 토스트 설치
  ensureEls();
  if (!layer) { installRuntime(k); return; }
  layer.hidden = false;
  api.sdModels().then((models) => {
    card.innerHTML =
      '<h3>이미지 생성 모델 선택</h3>' +
      '<p class="msub">Stable Diffusion 으로 받을 모델을 고르세요. 나중에 바꿀 수 있어요.</p>' +
      '<div class="wiz-list">' +
      models.map((m) => '<button class="wiz-opt" data-model="' + m.id + '">' +
        '<span class="ob"><b>' + m.name + '</b><p>' + m.desc + '</p></span>' +
        '<span class="osize">' + m.size + '</span></button>').join('') +
      '</div>' +
      '<div class="modal-foot"><button class="btn btn--md btn--secondary" data-act="cancel">취소</button></div>';
    card.querySelectorAll('[data-model]').forEach((el) => el.onclick = () => { close(); installRuntime('image', el.dataset.model); });
    card.querySelectorAll('[data-act]').forEach((el) => el.onclick = close);
  });
}

window.openInstall = openInstall;     // PROTO·홈 호환
window.installRuntime = installRuntime;
window.watchSetup = watchSetup;

// ════════════════════════════════════════════════════════════════════════
// 런타임 설치 마법사(C) — 진행률 모달. webui.py *-setup-progress 계약 미러.
//   openInstall('ollama')  → 바로 설치 진행
//   openInstall('image')   → SD 모델 선택 → 설치 진행
// 진행은 adapter.getSetupProgress(runtime) 폴링({ phase, percent, message, error }).
// ════════════════════════════════════════════════════════════════════════
import { api } from './adapter.js';
import { toast } from './toast.js';

const RUNTIME_LABEL = { ollama: 'Ollama', image: 'Stable Diffusion' };
let layer, card, poll = null;

function ensureEls() {
  layer = document.getElementById('installModal');
  card = document.getElementById('installCard');
}
function close() {
  if (poll) { clearInterval(poll); poll = null; }
  if (layer) layer.hidden = true;
}

function render(html) { card.innerHTML = html; wire(); }

// SD 모델 선택 화면
async function renderModelPick() {
  const models = await api.sdModels();
  render(
    '<h3>이미지 생성 모델 선택</h3>' +
    '<p class="msub">Stable Diffusion 으로 받을 모델을 고르세요. 나중에 바꿀 수 있어요.</p>' +
    '<div class="wiz-list">' +
    models.map((m) => '<button class="wiz-opt" data-model="' + m.id + '">' +
      '<span class="ob"><b>' + m.name + '</b><p>' + m.desc + '</p></span>' +
      '<span class="osize">' + m.size + '</span></button>').join('') +
    '</div>' +
    '<div class="modal-foot"><button class="btn btn--md btn--secondary" data-act="cancel">취소</button></div>',
  );
}

// 진행 화면
function renderProgress(runtime, model) {
  render(
    '<h3>' + RUNTIME_LABEL[runtime] + ' 설치 중</h3>' +
    '<p class="msub">설치가 끝나면 바로 제공에 사용할 수 있어요. 이 창을 닫아도 백그라운드로 계속됩니다.</p>' +
    '<div class="inst-bar"><i id="instFill"></i></div>' +
    '<div class="inst-phase"><span id="instMsg">설치 준비 중</span><span class="pct" id="instPct">0%</span></div>' +
    '<div class="modal-foot"><button class="btn btn--md btn--secondary" data-act="bg">백그라운드로</button></div>',
  );
  start(runtime, model);
}

function renderDone(runtime) {
  render(
    '<div class="inst-result"><div class="inst-big ok">' +
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg></div>' +
    '<h3>' + RUNTIME_LABEL[runtime] + ' 설치 완료</h3>' +
    '<p class="msub">이제 이 PC 에서 제공에 사용할 수 있어요.</p></div>' +
    '<div class="modal-foot"><button class="btn btn--md btn--primary" data-act="done">확인</button></div>',
  );
}

async function start(runtime, model) {
  await api.startSetup(runtime, model);
  poll = setInterval(async () => {
    const p = await api.getSetupProgress(runtime);
    const fill = document.getElementById('instFill');
    const msg = document.getElementById('instMsg');
    const pct = document.getElementById('instPct');
    if (fill && p.percent != null) fill.style.width = p.percent + '%';
    if (msg) msg.textContent = p.message || p.phase || '';
    if (pct) pct.textContent = (p.percent ?? 0) + '%';
    if (p.phase === 'error') {
      clearInterval(poll); poll = null;
      toast(RUNTIME_LABEL[runtime] + ' 설치 실패', { type: 'info', sub: String(p.error || p.message || '') });
    } else if (p.phase === 'done') {
      clearInterval(poll); poll = null;
      renderDone(runtime);
    }
  }, 600);
}

function wire() {
  card.querySelectorAll('[data-model]').forEach((el) => el.onclick = () => renderProgress('image', el.dataset.model));
  card.querySelectorAll('[data-act]').forEach((el) => el.onclick = () => {
    const a = el.dataset.act;
    if (a === 'bg') { toast(RUNTIME_LABEL[current] + ' 설치는 백그라운드에서 계속됩니다', { type: 'run', sticky: true, id: 'bg-' + current }); close(); }
    else close(); // cancel · done
  });
}

let current = 'ollama';
export function openInstall(runtime) {
  ensureEls();
  current = runtime;
  layer.hidden = false;
  if (runtime === 'image') renderModelPick();
  else renderProgress('ollama', null);
}
window.openInstall = openInstall; // PROTO 컨트롤러 호환

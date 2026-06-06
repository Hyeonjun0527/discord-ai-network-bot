// ════════════════════════════════════════════════════════════════════════
// Toast — 액션 피드백 알림(우상단). 모든 화면이 공유하는 단일 토스트 시스템.
// import { toast } from './toast.js'  또는  window.toast (일반 script 호환).
//   toast(msg, { type:'ok'|'info'|'run', sub, sticky, duration, id, replace })
//   · sticky: 자동 사라짐 없음(진행 중). · replace: 같은 id 토스트를 갱신(run→ok).
// ════════════════════════════════════════════════════════════════════════
const SPIN = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M21 12a9 9 0 1 1-6.2-8.5"/></svg>';
const ICO = { ok: '✓', info: 'i', run: SPIN };

// o.progress(0~100) 가 있으면 토스트 하단에 프로그레스바. 같은 id 로 호출하면 텍스트·바만
// 부분 갱신(전체 재생성 안 함) → 바 width 트랜지션·아이콘 스피너가 끊기지 않는다.
export function toast(msg, o = {}) {
  const c = document.getElementById('toast');
  if (!c) return null;
  const key = o.replace || o.id;
  let el = key ? c.querySelector('[data-id="' + key + '"]') : null;
  const fresh = !el;
  if (fresh) { el = document.createElement('div'); c.appendChild(el); el.innerHTML = '<span class="ti"></span><span class="tm"></span>'; }
  el.className = 'toast ' + (o.type || 'info');
  if (o.id) el.dataset.id = o.id;
  // 아이콘은 type 이 바뀔 때만 갱신(run 스피너가 매 갱신마다 재시작되지 않게)
  if (el.dataset.type !== (o.type || 'info')) { el.dataset.type = o.type || 'info'; el.querySelector('.ti').innerHTML = ICO[o.type] || ICO.info; }
  el.querySelector('.tm').innerHTML = msg + (o.sub ? '<small>' + o.sub + '</small>' : '');
  // 프로그레스바 — 같은 요소의 width 만 바꿔 트랜지션 유지
  let bar = el.querySelector('.toast-bar');
  if (o.progress != null) {
    if (!bar) { bar = document.createElement('div'); bar.className = 'toast-bar'; bar.innerHTML = '<i></i>'; el.appendChild(bar); }
    bar.querySelector('i').style.width = Math.max(0, Math.min(100, o.progress)) + '%';
  } else if (bar) { bar.remove(); }
  clearTimeout(el._t);
  if (!o.sticky) el._t = setTimeout(() => el.remove(), o.duration || 2800);
  return el;
}

// 일반 <script>(비모듈) 호출부 호환
if (typeof window !== 'undefined') window.toast = toast;

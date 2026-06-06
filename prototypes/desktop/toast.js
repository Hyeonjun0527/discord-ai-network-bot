// ════════════════════════════════════════════════════════════════════════
// Toast — 액션 피드백 알림(우상단). 모든 화면이 공유하는 단일 토스트 시스템.
// import { toast } from './toast.js'  또는  window.toast (일반 script 호환).
//   toast(msg, { type:'ok'|'info'|'run', sub, sticky, duration, id, replace })
//   · sticky: 자동 사라짐 없음(진행 중). · replace: 같은 id 토스트를 갱신(run→ok).
// ════════════════════════════════════════════════════════════════════════
const SPIN = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M21 12a9 9 0 1 1-6.2-8.5"/></svg>';
const ICO = { ok: '✓', info: 'i', run: SPIN };

export function toast(msg, o = {}) {
  const c = document.getElementById('toast');
  if (!c) return null;
  let el = o.replace ? c.querySelector('[data-id="' + o.replace + '"]') : null;
  if (!el) { el = document.createElement('div'); c.appendChild(el); }
  el.className = 'toast ' + (o.type || 'info');
  if (o.id) el.dataset.id = o.id; else delete el.dataset.id;
  el.innerHTML = '<span class="ti">' + (ICO[o.type] || ICO.info) + '</span>' +
    '<span class="tm">' + msg + (o.sub ? '<small>' + o.sub + '</small>' : '') + '</span>';
  clearTimeout(el._t);
  if (!o.sticky) el._t = setTimeout(() => el.remove(), o.duration || 2800);
  return el;
}

// 일반 <script>(비모듈) 호출부 호환
if (typeof window !== 'undefined') window.toast = toast;

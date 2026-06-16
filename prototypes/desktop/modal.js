// ════════════════════════════════════════════════════════════════════════
// Modal — 앱 자체 확인/입력 모달(OS 기본 confirm/prompt 대신). pywebview 이질감 해소.
// 화면 곳곳의 인라인 modal-layer 와 같은 마크업·동작(esc/✕/배경클릭으로 닫기)을 공유한다.
//   confirmModal({ title, desc, confirmLabel, cancelLabel, danger }, onConfirm)
//   promptModal({ title, desc, placeholder, value, confirmLabel, cancelLabel }) -> Promise<string|null>
// ════════════════════════════════════════════════════════════════════════
import { t } from './i18n.js';

const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

export function confirmModal(o, onConfirm) {
  const lay = document.createElement('div');
  lay.className = 'modal-layer';
  lay.setAttribute('role', 'dialog');
  lay.setAttribute('aria-modal', 'true');
  const confirmCls = o.danger ? 'btn--danger' : 'btn--primary';
  lay.innerHTML = '<div class="modal" style="width:min(420px,100%)">' +
    '<button class="modal-x" data-x aria-label="' + esc(t('toastCloseLabel', 'Close')) + '">✕</button>' +
    '<h3>' + esc(o.title || '') + '</h3>' +
    (o.desc ? '<p class="msub">' + esc(o.desc) + '</p>' : '') +
    '<div class="modal-foot"><button class="btn btn--md btn--secondary" data-x>' + esc(o.cancelLabel || t('serversDetailCancelButton', 'Cancel')) + '</button>' +
    '<button class="btn btn--md ' + confirmCls + '" data-go>' + esc(o.confirmLabel || t('serversDetailSaveButton', 'OK')) + '</button></div></div>';
  document.body.appendChild(lay);
  const close = () => lay.remove();
  lay.querySelectorAll('[data-x]').forEach((b) => { b.onclick = close; });
  lay.addEventListener('click', (e) => { if (e.target === lay) close(); });
  const go = lay.querySelector('[data-go]');
  go.onclick = () => { close(); onConfirm(); };
  go.focus();
  return lay;
}

export function promptModal(o) {
  return new Promise((resolve) => {
    const lay = document.createElement('div');
    lay.className = 'modal-layer';
    lay.setAttribute('role', 'dialog');
    lay.setAttribute('aria-modal', 'true');
    lay.innerHTML = '<div class="modal" style="width:min(480px,100%)">' +
      '<button class="modal-x" data-x aria-label="' + esc(t('toastCloseLabel', 'Close')) + '">✕</button>' +
      '<h3>' + esc(o.title || '') + '</h3>' +
      (o.desc ? '<p class="msub">' + esc(o.desc) + '</p>' : '') +
      '<div class="pform"><div class="pfield"><input class="cx-input" id="pmInput" type="text" value="' + esc(o.value || '') + '" placeholder="' + esc(o.placeholder || '') + '"></div></div>' +
      '<div class="modal-foot"><button class="btn btn--md btn--secondary" data-x>' + esc(o.cancelLabel || t('serversDetailCancelButton', 'Cancel')) + '</button>' +
      '<button class="btn btn--md btn--primary" data-go>' + esc(o.confirmLabel || t('serversDetailSaveButton', 'OK')) + '</button></div></div>';
    document.body.appendChild(lay);
    let done = false;
    const close = (val) => { if (done) return; done = true; lay.remove(); resolve(val); };
    lay.querySelectorAll('[data-x]').forEach((b) => { b.onclick = () => close(null); });
    lay.addEventListener('click', (e) => { if (e.target === lay) close(null); });
    const input = lay.querySelector('#pmInput');
    const submit = () => close((input.value || '').trim());
    lay.querySelector('[data-go]').onclick = submit;
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });
    input.focus();
  });
}

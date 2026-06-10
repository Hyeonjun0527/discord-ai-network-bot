// NEXA 데스크톱 UI 다국어(i18n). 단일 책임: 문구 조회·정적 라벨 적용·언어 전환 통지.
// SSOT = i18n/messages.json(desktop 섹션) → scripts/gen_i18n.py 가 i18n-agent.js(window.__I18N)로
// 생성·동기 주입한다(이 모듈보다 먼저 로드). 네트워크/어댑터에 의존하지 않는다(SoC).
// 언어 우선순위: 서버 주입(window.__LANG) > 저장(localStorage) > OS(navigator) > ko.

const SUPPORTED = ['ko', 'en', 'ja'];
const DICT = (typeof window !== 'undefined' && window.__I18N) || {};
const listeners = [];

function pickInitialLang() {
  if (typeof window !== 'undefined' && SUPPORTED.includes(window.__LANG)) return window.__LANG;
  try {
    const s = localStorage.getItem('nyaLang');
    if (SUPPORTED.includes(s)) return s;
  } catch (_) { /* localStorage 불가 환경 무시 */ }
  const nav = ((typeof navigator !== 'undefined' && navigator.language) || 'ko').slice(0, 2).toLowerCase();
  return SUPPORTED.includes(nav) ? nav : 'ko';
}

let LANG = pickInitialLang();

export const supportedLangs = () => SUPPORTED.slice();
export const currentLang = () => LANG;

/** 키 → 현재 언어 문구. 없으면 en→ko→fallback→key 순으로 폴백(절대 빈 화면 없음). */
export function t(key, fallback) {
  const e = DICT[key];
  if (!e) return fallback != null ? fallback : key;
  return e[LANG] || e.en || e.ko || (fallback != null ? fallback : key);
}

/** [data-i18n] 정적 요소의 textContent 를 현재 언어로 채운다(인라인 텍스트를 폴백으로). */
export function applyStatic(root) {
  (root || document).querySelectorAll('[data-i18n]').forEach((el) => {
    el.textContent = t(el.getAttribute('data-i18n'), el.textContent);
  });
}

/** 언어 전환 시 다시 그려야 하는 JS 렌더 화면이 등록한다(설정 등). */
export function onLangChange(cb) {
  if (typeof cb === 'function') listeners.push(cb);
}

/** 언어 전환: 저장은 호출자(설정 화면)가 서버에 별도 반영. 여기선 UI·localStorage·리스너만. */
export function setLang(lang) {
  if (!SUPPORTED.includes(lang) || lang === LANG) return;
  LANG = lang;
  try {
    localStorage.setItem('nyaLang', lang);
  } catch (_) { /* 저장 불가 무시 */ }
  if (typeof document !== 'undefined') document.documentElement.setAttribute('lang', lang);
  applyStatic();
  listeners.forEach((cb) => {
    try {
      cb(lang);
    } catch (_) { /* 한 리스너 실패가 다른 화면 갱신을 막지 않게 */ }
  });
}

// 모듈 로드 즉시 정적 라벨(네비 등)을 현재 언어로 적용.
if (typeof document !== 'undefined') document.documentElement.setAttribute('lang', LANG);
applyStatic();

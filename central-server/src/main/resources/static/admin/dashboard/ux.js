/*
 * 관리자 콘솔 UX 보강 레이어(app.js 무수정). 기존 동작은 그대로 두고 사용성만 덧입힌다:
 *  - 토스트: 결과 박스(.preview) 변경을 즉시 알림으로 표면화(성공/실패 색).
 *  - 모바일 상단 네비 → 사이드바 클릭 위임 + active 동기화.
 *  - 컨텍스트 바: 지금 보고 있는 서버/채널을 항상 표시.
 *  - 위험 작업(삭제/제거/비공개) 확인 다이얼로그.
 */
(function () {
  const $ = (id) => document.getElementById(id);

  // ── 토스트 ──────────────────────────────────────────────
  const wrap = $("toastWrap");
  function toast(msg, kind) {
    if (!wrap || !msg) return;
    const el = document.createElement("div");
    el.className = "toast" + (kind ? " " + kind : "");
    el.innerHTML = '<span class="tdot"></span><span class="tmsg"></span><button class="tx" aria-label="닫기">×</button>';
    el.querySelector(".tmsg").textContent = msg;
    el.querySelector(".tx").onclick = () => el.remove();
    wrap.appendChild(el);
    setTimeout(() => el.remove(), 5200);
    while (wrap.children.length > 4) wrap.firstChild.remove();
  }
  window.toast = toast;

  // ── 결과 박스(.preview) 변경 → 토스트(앱 로직 무수정) ──
  const FAIL = /(실패|오류|에러|error|숫자로 입력|먼저|필요합니다|없습니다)/i;
  document.querySelectorAll("pre.preview").forEach((box) => {
    let last = (box.textContent || "").trim();
    const mo = new MutationObserver(() => {
      const t = (box.textContent || "").trim();
      if (!t || t === last) return;
      last = t;
      // 단순 안내문(…하세요.)은 알림으로 띄우지 않음
      if (/하세요\.?$/.test(t) && t.length < 70) return;
      const first = t.split("\n")[0].slice(0, 140);
      toast(first, FAIL.test(first) ? "bad" : "ok");
    });
    mo.observe(box, { childList: true, characterData: true, subtree: true });
  });

  // ── 모바일 상단 네비 → 사이드바 위임 ──
  const sideLinks = [...document.querySelectorAll("nav.side a[data-page]")];
  const mobLinks = [...document.querySelectorAll(".mobile-nav a[data-page]")];
  mobLinks.forEach((m) => {
    m.addEventListener("click", () => {
      const page = m.getAttribute("data-page");
      const target = sideLinks.find((s) => s.getAttribute("data-page") === page);
      if (target) target.click();
    });
  });
  function syncMobileActive() {
    const active = sideLinks.find((s) => s.classList.contains("active"));
    const page = active ? active.getAttribute("data-page") : "overview";
    mobLinks.forEach((m) => m.classList.toggle("active", m.getAttribute("data-page") === page));
    const ch = $("channelCrumb");
    const mch = $("mobileChannelCrumb");
    if (mch && ch) mch.hidden = ch.hidden;
  }

  // ── 컨텍스트 바: 선택된 서버/채널 ──
  function updateCtx() {
    const bar = $("ctxBar");
    if (!bar) return;
    const picker = $("guildPicker");
    const gidInput = $("guildId");
    let server = "";
    if (picker && picker.value) {
      const opt = picker.options[picker.selectedIndex];
      server = opt ? opt.textContent.trim() : picker.value;
    } else if (gidInput && gidInput.value) {
      server = "서버 " + gidInput.value;
    }
    const chCrumb = $("channelCrumb");
    const chName = $("channelCrumbName");
    const channel = chCrumb && !chCrumb.hidden && chName ? chName.textContent.trim() : "";
    if ($("ctxServer")) $("ctxServer").textContent = server || "서버 미선택";
    bar.classList.toggle("empty", !server);
    const showCh = !!channel && channel !== "채널 상세";
    if ($("ctxSep")) $("ctxSep").hidden = !showCh;
    if ($("ctxChannel")) {
      $("ctxChannel").hidden = !showCh;
      if (showCh) $("ctxChannel").textContent = channel;
    }
  }

  setInterval(() => { syncMobileActive(); updateCtx(); }, 700);
  document.addEventListener("click", () => setTimeout(() => { syncMobileActive(); updateCtx(); }, 40), true);
  syncMobileActive();
  updateCtx();

  // ── 위험 작업 확인 ──
  document.addEventListener(
    "click",
    (e) => {
      const btn = e.target.closest && e.target.closest("button.danger");
      if (!btn) return;
      if (btn.dataset.confirmed === "1") { btn.dataset.confirmed = ""; return; }
      e.preventDefault();
      e.stopPropagation();
      const label = (btn.textContent || "이 작업").trim();
      if (window.confirm(`'${label}' 을(를) 진행할까요? 되돌릴 수 없을 수 있습니다.`)) {
        btn.dataset.confirmed = "1";
        btn.click();
      }
    },
    true,
  );
})();

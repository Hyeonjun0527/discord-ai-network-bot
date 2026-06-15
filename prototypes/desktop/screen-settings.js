// NEXA 데스크톱 — screen-settings.js (index.html 에서 분리, SoC/SRP). 동작 보존 verbatim.
    import { api } from './adapter.js';
    import { toast } from './toast.js';
    import { t, setLang, currentLang, supportedLangs, onLangChange } from './i18n.js';

    const view = document.querySelector('.view[data-view="settings"]');
    const body = document.getElementById('settingsBody');
    let _s = null, _upd = null, _lic = null;
    // 니아 전체 페르소나(전문) — 프로젝트 관리자만. null=미조회, {ok:false}=비관리자(카드 숨김), {ok:true,...}=열람.
    let _nia = null;

    // 토글 키(서버 설정 키 = data-toggle, 불변) → i18n 라벨 키.
    const TOGGLES = [
      { key: 'autostart', nameKey: 'toggleAutostart', descKey: 'toggleAutostartDesc' },
      { key: 'background', nameKey: 'toggleBackground', descKey: 'toggleBackgroundDesc' },
      { key: 'autoConnect', nameKey: 'toggleAutoConnect', descKey: 'toggleAutoConnectDesc' },
      { key: 'enableImage', nameKey: 'toggleEnableImage', descKey: 'toggleEnableImageDesc' },
    ];
    const sw = (key, on) => '<button class="switch ' + (on ? 'on' : '') + '" data-toggle="' + key + '" role="switch" aria-checked="' + !!on + '" aria-label="' + key + '"></button>';
    const row = (name, desc, right) => '<div class="set-row"><div class="sr-body"><div class="sr-name">' + name + '</div>' +
      (desc ? '<div class="sr-desc">' + desc + '</div>' : '') + '</div>' + right + '</div>';
    const group = (titleKey, inner) => '<div class="set-group"><div class="sg-title">' + t(titleKey) + '</div>' + inner + '</div>';
    const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
    let _promptedVersion = null;

    function versionLine(u) {
      if (!u) return t('setVerChecking');
      if (u.error) return '<span class="txt-error">' + t('setVerCheckFailed') + '</span> — ' + esc(u.error);
      if (u.outdated) return t('setVerCurrent') + ' v' + esc(u.current || '-') + ' · ' + t('setVerLatest') + ' v' + esc(u.latest || '-');
      return t('setVerCurrent') + ' v' + esc(u.current || '-') + ' · ' + t('setVerUpToDate');
    }

    function updateRight(u) {
      if (!u) return '<button class="btn btn--sm btn--secondary" id="setUpdateCheck" disabled>' + t('setUpdateChecking') + '</button>';
      if (u.outdated && u.supported && !u.error) return '<button class="btn btn--sm btn--primary" id="setUpdateApply">' + t('setUpdateApply') + '</button>';
      return '<button class="btn btn--sm btn--secondary" id="setUpdateCheck">' + t('setUpdateCheck') + '</button>';
    }

    function maybePromptUpdate(u, force) {
      if (!u || !u.outdated || u.error) return;
      if (!u.supported) {
        if (force) toast(t('setUpdatePromptUnsupported'), { type: 'error' });
        return;
      }
      const latest = String(u.latest || '');
      if (!force && latest && _promptedVersion === latest) return;
      if (latest) _promptedVersion = latest;
      openUpdatePrompt(u);
    }

    function openUpdatePrompt(u) {
      if (!u || !u.outdated || !u.supported || u.error) return;
      const old = document.getElementById('updateModal');
      if (old) old.remove();
      const lay = document.createElement('div');
      lay.className = 'modal-layer';
      lay.id = 'updateModal';
      lay.setAttribute('role', 'dialog');
      lay.setAttribute('aria-modal', 'true');
      lay.innerHTML = '<div class="modal">' +
        '<button class="modal-x" data-update-close aria-label="닫기">✕</button>' +
        '<h3>' + t('setUpdatePromptTitle') + '</h3>' +
        '<p class="msub">' + esc(t('setUpdatePromptDesc').replace('{latest}', u.latest || '-').replace('{current}', u.current || '-')) + '</p>' +
        '<div class="wiz-sum"><span class="si">↗</span><span class="sl">' + esc(t('setUpdatePromptRestart')) + '</span></div>' +
        '<div class="modal-foot">' +
          '<button class="btn btn--md btn--secondary" data-update-close>' + t('setUpdatePromptLater') + '</button>' +
          '<button class="btn btn--md btn--primary" data-update-confirm>' + t('setUpdatePromptConfirm') + '</button>' +
        '</div>' +
      '</div>';
      document.body.appendChild(lay);
      let pollTimer = null;
      let restarting = false;
      const close = () => { if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; } lay.remove(); };
      lay.querySelectorAll('[data-update-close]').forEach((b) => { b.onclick = close; });
      lay.addEventListener('click', (e) => { if (e.target === lay) close(); });
      const confirm = lay.querySelector('[data-update-confirm]');

      // 진행률 폴링 → 모달 내 진행바(/api/update-progress: phase/percent/message/error).
      // 이전엔 시작 토스트만 띄우고 폴링하지 않아 ① 진행바가 없고 ② 백그라운드 다운로드/교체 실패가
      // 사용자에게 전혀 안 보였다(“업데이트 눌러도 안 됨”의 정체). 이제 끝까지 진행/실패를 표시한다.
      const phaseLabel = (p) => ({
        downloading: t('setUpdatePhaseDownloading'),
        verifying: t('setUpdatePhaseVerifying'),
        installing: t('setUpdatePhaseInstalling'),
        restarting: t('setUpdatePhaseRestarting'),
      }[p] || t('setUpdatePhaseStarting'));

      const showProgress = () => {
        const modal = lay.querySelector('.modal');
        modal.innerHTML = '<h3>' + t('setUpdatePromptTitle') + '</h3>' +
          '<div class="inst-bar"><i data-upd-fill style="width:6%"></i></div>' +
          '<p class="msub" data-upd-status>' + esc(t('setUpdatePhaseStarting')) + '</p>';
        return { fill: modal.querySelector('[data-upd-fill]'), status: modal.querySelector('[data-upd-status]') };
      };

      const showError = (msg) => {
        if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
        const modal = lay.querySelector('.modal');
        modal.innerHTML = '<button class="modal-x" data-update-close aria-label="닫기">✕</button>' +
          '<h3>' + t('setUpdateFailedStatus') + '</h3>' +
          '<p class="msub">' + esc(msg || t('setUpdateFailedStatus')) + '</p>' +
          '<div class="modal-foot">' +
            '<button class="btn btn--md btn--secondary" data-update-close>' + t('setUpdatePromptLater') + '</button>' +
            '<button class="btn btn--md btn--primary" data-update-confirm>' + t('setUpdateRetryBtn') + '</button>' +
          '</div>';
        modal.querySelectorAll('[data-update-close]').forEach((b) => { b.onclick = close; });
        const retry = modal.querySelector('[data-update-confirm]');
        retry.onclick = startUpdate;
        retry.focus();
      };

      const poll = (ui) => {
        pollTimer = setTimeout(async () => {
          let pr;
          try { pr = await api.getUpdateProgress(); }
          catch (_e) {
            // 폴링 실패: 재시작 단계였다면 교체로 서버가 사라진 것 → 성공(곧 새 버전으로 열림).
            if (restarting) { ui.status.textContent = t('setUpdatePhaseRestarting'); return; }
            poll(ui); return;
          }
          if (pr && pr.phase === 'error') { showError(pr.error); return; }
          const pct = Math.max(0, Math.min(100, parseInt(pr && pr.percent, 10) || 0));
          if (pr && (pr.phase === 'restarting' || pr.phase === 'done')) {
            restarting = true; ui.fill.style.width = '100%'; ui.status.textContent = t('setUpdatePhaseRestarting'); poll(ui); return;
          }
          if (pct) ui.fill.style.width = pct + '%';
          ui.status.textContent = phaseLabel(pr && pr.phase) + (pct ? '  ' + pct + '%' : '');
          poll(ui);
        }, 700);
      };

      async function startUpdate() {
        if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
        restarting = false;
        const ui = showProgress();
        let r;
        try { r = await api.applyUpdate(); } // POST /api/update (백그라운드 시작)
        catch (_e) { showError(t('setUpdateStartRetryToast')); return; }
        if (r && r.ok === false) { showError(r.error || t('setUpdateStartFailedToast')); return; }
        poll(ui);
      }

      confirm.onclick = startUpdate;
      confirm.focus();
    }

    // 언어 선택기 — 현재 언어 선택 상태로 ko/en/ja 드롭다운. 변경 시 setLang(UI)+서버 저장.
    function langSelect() {
      const cur = currentLang();
      const NATIVE = { ko: '한국어', en: 'English', ja: '日本語' };
      const opts = supportedLangs().map((l) => '<option value="' + l + '"' + (l === cur ? ' selected' : '') + '>' + NATIVE[l] + '</option>').join('');
      return '<select id="langSel" class="set-input" aria-label="' + t('setLangLabel') + '">' + opts + '</select>';
    }

    function licenseStatusLabel(status) {
      return t('licenseStatus' + (status || 'FREE'), status || '-');
    }

    function licenseDescription(lic) {
      if (!lic) return t('licenseChecking');
      if (!lic.ok) return (lic.error || t('licenseConnectRequiredDesc'));
      const ent = lic.entitlement || {};
      const event = lic.event || {};
      const access = ent.hasPaidAccess ? t('licenseAccessOn') : t('licenseAccessOff');
      const trial = ent.trialEndsAt ? ' · ' + t('licenseTrialEndsAt').replace('{date}', ent.trialEndsAt.slice(0, 10)) : '';
      const eventLine = event.open ? t('licenseEventOpen').replace('{count}', event.granted ?? 0) : t('licenseEventClosed');
      return access + trial + ' · ' + eventLine;
    }

    function licenseButtons(lic) {
      if (!lic) return '<button class="btn btn--sm btn--secondary" disabled>' + t('licenseCheckingShort') + '</button>';
      if (!lic.ok) return '<button class="btn btn--sm btn--secondary" id="setLicenseRefresh">' + t('licenseRefresh') + '</button>';
      const ent = lic.entitlement || {};
      const event = lic.event || {};
      const buy = ent.hasPaidAccess ? '' : '<button class="btn btn--sm btn--primary" id="setLicenseBuy">' + t('licenseBuy') + '</button>';
      const claim = (!ent.hasPaidAccess && event.open) ? '<button class="btn btn--sm btn--secondary" id="setLicenseClaim">' + t('licenseClaim') + '</button>' : '';
      const refresh = '<button class="btn btn--sm btn--secondary" id="setLicenseRefresh">' + t('licenseRefresh') + '</button>';
      return [buy, claim, refresh].filter(Boolean).join('');
    }

    // 니아 전체 페르소나 카드 HTML. 비관리자(_nia.ok=false)·미조회(null)면 빈 문자열(카드 자체를 숨긴다 — 전문 노출 금지).
    // 성공이면 persona·fewshot 을 읽기 전용 <pre> 로 보여 주고 복사 버튼만 제공(편집 불가).
    function niaPersonaGroup() {
      if (!_nia || !_nia.ok) return '';
      const block = (label, text, copyId) =>
        '<div class="sr-body" style="width:100%"><div class="sr-name">' + label +
        '<button class="btn btn--sm btn--secondary" id="' + copyId + '" style="float:right">' + t('niaPersonaCopy', '복사') + '</button></div>' +
        '<pre class="set-input" style="white-space:pre-wrap;max-height:200px;overflow:auto;user-select:text">' + esc(text) + '</pre></div>';
      const inner = '<div class="set-row" style="flex-direction:column;align-items:stretch;gap:10px">' +
        '<div class="sr-desc">' + t('niaPersonaDesc', '프로젝트 관리자만 볼 수 있는 니아 기본 페르소나 전문이에요(읽기 전용).') + '</div>' +
        block(t('niaPersonaPersona', '페르소나'), _nia.persona || '', 'niaCopyPersona') +
        block(t('niaPersonaFewshot', 'few-shot 예시'), _nia.fewshot || '', 'niaCopyFewshot') +
        '</div>';
      return group('niaPersonaTitle', inner);
    }

    function render() {
      const s = _s || {}, u = _upd; // u=null 이면 아직 업데이트 확인 전(네트워크 진행 중)
      const exec = TOGGLES.map((g) => row(t(g.nameKey), t(g.descKey), sw(g.key, s[g.key]))).join('');
      const verLine = versionLine(u);
      const updRight = updateRight(u);
      const langGroup = row(t('setLangLabel'), t('setLangDesc'), langSelect());
      const updGroup = row(t('setVersion'), verLine, updRight) + row(t('setAutoUpdate'), t('setAutoUpdateDesc'), sw('autoUpdate', s.autoUpdate));
      // 중앙 서버·Ollama 주소 — 편집 가능(고급). 다른 포트/호스트의 Ollama 를 쓰는 유저가 앱에서 바꾼다.
      const connGroup =
        row(t('setCentralServer'), t('setCentralServerDesc'),
          '<input id="setRelay" type="text" value="' + (s.relayUrl || '') + '" placeholder="wss://discord-ai.yeon.world/agent" class="set-input"><button class="btn btn--sm btn--secondary" id="setRelaySave">' + t('setSave') + '</button>') +
        row(t('setOllamaAddr'), t('setOllamaAddrDesc'),
          '<input id="setOllama" type="text" value="' + (s.ollamaUrl || '') + '" placeholder="http://localhost:11434" class="set-input"><button class="btn btn--sm btn--secondary" id="setOllamaSave">' + t('setSave') + '</button>');
      const acct = row(s.hasToken ? t('setConnected') : t('setNotConnected'),
        s.hasToken ? t('setConnectedDesc') : t('setNotConnectedDesc'),
        s.hasToken ? '<button class="btn btn--sm btn--secondary" id="setLogout">' + t('setLogout') + '</button>' : '');
      const licTitle = _lic && _lic.ok ? t('licenseTitle') + ' · ' + licenseStatusLabel(_lic.entitlement && _lic.entitlement.status) : t('licenseTitle');
      const licGroup = row(licTitle, licenseDescription(_lic), licenseButtons(_lic));
      // 클라우드 AI(Gemini)·이미지 엔진(ComfyUI)은 '엔진' 관심사 → 로컬 실행 탭이 소유한다(설정엔 두지 않음).
      // 설정은 앱 동작만: 언어·실행 동작·업데이트·연결·계정. AI 백엔드 설정은 여기 없음(IA: 엔진→모델→서버).
      body.innerHTML =
        group('setGroupLang', langGroup) +
        group('setGroupExec', exec) +
        group('setGroupUpdate', updGroup) +
        group('setGroupConn', connGroup) +
        group('setGroupLicense', licGroup) +
        niaPersonaGroup() +
        group('setGroupAccount', acct);
      bind();
    }

    function bind() {
      body.querySelectorAll('[data-toggle]').forEach((b) => {
        b.onclick = async () => {
          const key = b.dataset.toggle, on = !b.classList.contains('on');
          b.classList.toggle('on', on); b.setAttribute('aria-checked', String(on)); // 일단 반영
          // 이미지 수신은 전용 라이브 토글(/api/image)로 — 모델 미변경·즉시 적용·ComfyUI 준비 상태 안내.
          if (key === 'enableImage') {
            try {
              const r = await api.setImageReceiving(on) || {};
              if (_s) _s[key] = on;
              if (on && !r.imageReady) toast(t('setImageOnNeedsComfyToast'), { type: 'info', sub: t('setImageOnNeedsComfySub') });
              else toast(t('setSavedToast'), { type: 'ok' });
            } catch (_e) {
              b.classList.toggle('on', !on); b.setAttribute('aria-checked', String(!on)); if (_s) _s[key] = !on;
              toast(t('setChangeFailedToast'), { type: 'error' });
            }
            return;
          }
          try {
            const r = await api.setSetting(key, on) || {};
            if (_s) _s[key] = on;
            if (r.serviceError) { // autostart 등 실제 적용 실패 — 정직하게 알리고 되돌림
              b.classList.toggle('on', !on); b.setAttribute('aria-checked', String(!on)); if (_s) _s[key] = !on;
              toast(t('setApplyFailedToast').replace('{error}', r.serviceError), { type: 'error' });
            } else if (r.needsRestart) { // 즉시 반영 안 되는 항목(이미지 수신 등) — '저장됨' 착시 금지
              toast(t('setSavedNeedsReconnectToast'), { type: 'info' });
            } else {
              toast(t('setSavedToast'), { type: 'ok' });
            }
          } catch (_e) { // 저장 자체 실패 → 토글 원복
            b.classList.toggle('on', !on); b.setAttribute('aria-checked', String(!on)); if (_s) _s[key] = !on;
            toast(t('setSaveFailedToast'), { type: 'error' });
          }
        };
      });
      const chk = document.getElementById('setUpdateCheck');
      if (chk) chk.onclick = async () => {
        _upd = await api.getUpdateInfo();
        render();
        if (_upd.error) toast(t('setUpdateCheckFailedToast').replace('{error}', _upd.error), { type: 'error' });
        else if (_upd.outdated && !_upd.supported) toast(t('setUpdatePromptUnsupported'), { type: 'error' });
        else if (_upd.outdated) { toast(t('setUpdateAvailableToast'), { type: 'info' }); maybePromptUpdate(_upd, true); }
        else toast(t('setUpToDateToast'), { type: 'info' });
      };
      const apply = document.getElementById('setUpdateApply');
      if (apply) apply.onclick = () => maybePromptUpdate(_upd, true);
      const logout = document.getElementById('setLogout');
      if (logout) logout.onclick = async () => {
        if (!confirm(t('setLogoutConfirm'))) return;
        await api.logout(); toast(t('setLogoutDoneToast'), { type: 'info' }); load();
      };
      const licenseRefresh = document.getElementById('setLicenseRefresh');
      if (licenseRefresh) licenseRefresh.onclick = async () => { await loadLicense(); toast(t('licenseReloadedToast'), { type: 'info' }); };
      const licenseBuy = document.getElementById('setLicenseBuy');
      if (licenseBuy) licenseBuy.onclick = async () => {
        licenseBuy.disabled = true;
        try {
          const r = await api.checkoutLicense();
          if (r && r.ok && r.url) { window.open(r.url, '_blank', 'noopener'); toast(t('licenseCheckoutOpenedToast'), { type: 'ok' }); }
          else toast((r && r.error) || t('licenseCheckoutFailedToast'), { type: 'error' });
        } catch (_e) { toast(t('licenseCheckoutFailedToast'), { type: 'error' }); }
        finally { licenseBuy.disabled = false; }
      };
      const licenseClaim = document.getElementById('setLicenseClaim');
      if (licenseClaim) licenseClaim.onclick = async () => {
        licenseClaim.disabled = true;
        try {
          const r = await api.claimLicenseEvent();
          if (r && r.ok) { toast(t('licenseClaimDoneToast').replace('{outcome}', r.outcome || 'OK'), { type: 'ok' }); await loadLicense(); }
          else toast((r && r.error) || t('licenseClaimFailedToast'), { type: 'error' });
        } catch (_e) { toast(t('licenseClaimFailedToast'), { type: 'error' }); }
        finally { licenseClaim.disabled = false; }
      };
      // 중앙 서버·Ollama 주소 저장(고급) — 다음 연결에 반영(needsRestart).
      const saveConn = (inputId, key, label) => {
        const b = document.getElementById(inputId + 'Save');
        if (!b) return;
        b.onclick = async () => {
          const url = (document.getElementById(inputId).value || '').trim();
          b.disabled = true;
          try { const r = await api.setSetting(key, url) || {}; if (_s) _s[key] = url; toast(r.needsRestart ? t('setConnSavedNeedsReconnectToast').replace('{label}', label) : t('setConnSavedToast').replace('{label}', label), { type: 'ok' }); }
          catch (_e) { toast(t('setSaveFailedToast'), { type: 'error' }); }
          finally { b.disabled = false; }
        };
      };
      saveConn('setRelay', 'relayUrl', t('setRelaySaveLabel'));
      saveConn('setOllama', 'ollamaUrl', t('setOllamaAddr'));
      // 언어 전환: setLang 이 UI(정적 라벨)+이 화면 재렌더를 처리하고, 서버에도 저장(재시작 후 유지).
      const ls = document.getElementById('langSel');
      if (ls) ls.onchange = () => { const v = ls.value; setLang(v); api.setSetting('lang', v).catch(() => {}); };
      // 니아 페르소나 복사(읽기 전용 카드) — 전문을 클립보드로. 카드가 없으면(비관리자) 버튼도 없다.
      const copyTo = async (text) => {
        try { await navigator.clipboard.writeText(text || ''); toast(t('niaPersonaCopiedToast', '복사했어요'), { type: 'ok' }); }
        catch (_e) { toast(t('niaPersonaCopyFailedToast', '복사하지 못했어요'), { type: 'error' }); }
      };
      const cp = document.getElementById('niaCopyPersona');
      if (cp) cp.onclick = () => copyTo(_nia && _nia.persona);
      const cf = document.getElementById('niaCopyFewshot');
      if (cf) cf.onclick = () => copyTo(_nia && _nia.fewshot);
    }

    // 언어가 바뀌면 설정 화면을 다시 그린다(JS 렌더 문구 갱신). 네비 등 정적 라벨은 i18n.applyStatic 이 처리.
    onLangChange(() => { if (_s) render(); });

    async function loadLicense() {
      try { _lic = await api.getLicense(); }
      catch (_e) { _lic = { ok: false, error: t('licenseLoadFailed') }; }
      if (isActive()) render();
    }

    // 니아 전체 페르소나 — 프로젝트 관리자만 성공. 비관리자(403)·실패면 _nia.ok=false 로 두어 카드를 숨긴다.
    // 전문은 응답 ok=true 일 때만 들어 있고, 비관리자에겐 절대 표시·번들되지 않는다.
    async function loadNiaPersona() {
      try { _nia = await api.getNiaPersona(); }
      catch (_e) { _nia = { ok: false }; }
      if (isActive()) render();
    }

    async function refreshUpdateInfo(prompt) {
      const u = await api.getUpdateInfo();
      _upd = u;
      if (isActive()) render();
      if (prompt && u && u.autoUpdate) maybePromptUpdate(u, false);
      return u;
    }

    async function load() {
      _s = await api.getSettings();  // 로컬·빠름 → 즉시 렌더(설정 본문이 네트워크에 안 막히게)
      _lic = null;
      _nia = null;  // 매 진입 시 재조회(권한은 매번 central 이 판정 — 캐시로 노출 금지)
      render();
      loadLicense();
      loadNiaPersona();
      // 업데이트 확인은 공개 업데이트 채널 조회(네트워크) — 느리거나 실패해도 본문 전체가 막히면 안 된다.
      // 본문은 위에서 이미 그렸고, 업데이트 줄만 도착 시 백그라운드로 채운다(_upd 는 캐시돼 재오픈 시 즉시 표시).
      refreshUpdateInfo(true).catch(() => {});
    }

    const isActive = () => view.classList.contains('active');
    document.querySelector('.nav-item[data-view="settings"]').addEventListener('click', load);
    if (isActive()) load();
    setTimeout(() => { refreshUpdateInfo(true).catch(() => {}); }, 1200);

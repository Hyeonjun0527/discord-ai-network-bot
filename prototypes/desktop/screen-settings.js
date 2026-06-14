// NEXA 데스크톱 — screen-settings.js (index.html 에서 분리, SoC/SRP). 동작 보존 verbatim.
    import { api } from './adapter.js';
    import { toast } from './toast.js';
    import { t, setLang, currentLang, supportedLangs, onLangChange } from './i18n.js';

    const view = document.querySelector('.view[data-view="settings"]');
    const body = document.getElementById('settingsBody');
    let _s = null, _upd = null, _lic = null;

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
      const close = () => lay.remove();
      lay.querySelectorAll('[data-update-close]').forEach((b) => { b.onclick = close; });
      lay.addEventListener('click', (e) => { if (e.target === lay) close(); });
      const confirm = lay.querySelector('[data-update-confirm]');
      confirm.onclick = async () => {
        confirm.disabled = true;
        toast(t('setUpdateStartingToast'), { type: 'run', sticky: true, id: 'upd' });
        try {
          const r = await api.applyUpdate(); // POST /api/update
          if (r && r.ok === false) { toast(r.error || t('setUpdateStartFailedToast'), { type: 'error', id: 'upd' }); return; }
          toast(t('setUpdateStartedToast'), { type: 'ok', id: 'upd' });
          close();
        } catch (_e) { toast(t('setUpdateStartRetryToast'), { type: 'error', id: 'upd' }); }
        finally { confirm.disabled = false; }
      };
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
    }

    // 언어가 바뀌면 설정 화면을 다시 그린다(JS 렌더 문구 갱신). 네비 등 정적 라벨은 i18n.applyStatic 이 처리.
    onLangChange(() => { if (_s) render(); });

    async function loadLicense() {
      try { _lic = await api.getLicense(); }
      catch (_e) { _lic = { ok: false, error: t('licenseLoadFailed') }; }
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
      render();
      loadLicense();
      // 업데이트 확인은 공개 업데이트 채널 조회(네트워크) — 느리거나 실패해도 본문 전체가 막히면 안 된다.
      // 본문은 위에서 이미 그렸고, 업데이트 줄만 도착 시 백그라운드로 채운다(_upd 는 캐시돼 재오픈 시 즉시 표시).
      refreshUpdateInfo(true).catch(() => {});
    }

    const isActive = () => view.classList.contains('active');
    document.querySelector('.nav-item[data-view="settings"]').addEventListener('click', load);
    if (isActive()) load();
    setTimeout(() => { refreshUpdateInfo(true).catch(() => {}); }, 1200);

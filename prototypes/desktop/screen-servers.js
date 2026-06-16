// NEXA 데스크톱 — screen-servers.js (index.html 에서 분리, SoC/SRP). 동작 보존 verbatim.
    import { api } from './adapter.js';
    import { toast } from './toast.js';
    import { presentServerState, presentServerMeta, presentRole } from './presenter.js';
    import { ProviderState, Role } from './contract.js';
    import { t, onLangChange } from './i18n.js';

    const TINT = ['--c-violet', '--c-blue', '--c-cyan', '--c-purple'];
    const ROLE_ICON = {
      admin: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2 4 5v6c0 5 3.5 8.4 8 9.5 4.5-1.1 8-4.5 8-9.5V5Z"/></svg>',
      provider: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>',
    };
    const initialOf = (n) => (n || '·').trim().charAt(0);
    const fmtMembers = (n) => (n >= 1000 ? (n / 1000).toFixed(n >= 10000 ? 0 : 1) + 'k' : String(n));
    // 디스코드 서버명에 <>& 가 흔해 마크업 주입/렌더 깨짐 방지(다른 입력과 동일하게 escape).
    const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

    const listEl = document.getElementById('serverList');
    const sumEl = document.getElementById('serverSummary');

    function render(servers) {
      listEl.innerHTML = servers.map((s, i) => {
        const tint = TINT[i % TINT.length];
        const avatarStyle = 'background: color-mix(in srgb, var(' + tint + ') 22%, transparent); border-color: color-mix(in srgb, var(' + tint + ') 40%, transparent);';
        const img = s.iconUrl ? '<img src="' + s.iconUrl + '" alt="" onerror="this.remove()">' : '';
        const st = presentServerState(s.state);
        const role = presentRole(s.role);
        return '<button class="srv-item" data-guild="' + s.guildId + '">' +
          '<span class="srv-avatar" style="' + avatarStyle + '">' + esc(initialOf(s.guildName)) + img + '</span>' +
          '<span class="srv-main">' +
            '<span class="srv-name"><span class="nm">' + esc(s.guildName) + '</span></span>' +
            '<span class="srv-meta"><span class="srv-st ' + st.dot + '"><span class="d"></span>' + st.label + '</span>' + (s.members != null ? ' · ' + t('serversMemberLabel') + ' ' + fmtMembers(s.members) : '') + ' · ' + presentServerMeta(s) + '</span>' +
          '</span>' +
          '<span class="srv-role ' + role.cls + '">' + ROLE_ICON[role.cls] + role.label + '</span>' +
          '<svg class="srv-go" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="m9 6 6 6-6 6"/></svg>' +
        '</button>';
      }).join('');
      // 집계
      const c = { ok: 0, paused: 0, pending: 0 };
      servers.forEach(s => {
        if (s.state === ProviderState.PAUSED) c.paused++;
        else if (s.state === ProviderState.PENDING) c.pending++;
        else c.ok++;
      });
      sumEl.innerHTML = t('serversConnected') + ' <b>' + c.ok + '</b> · ' + t('serversPaused') + ' <b>' + c.paused + '</b> · ' + t('serversPendingApproval') + ' <b>' + c.pending + '</b>';
      // 카드 클릭 → 서버 상세(기부자 관점) 진입
      listEl.querySelectorAll('.srv-item').forEach(el => el.addEventListener('click', () => openDetail(el.dataset.guild)));
    }

    // ── 서버 상세(기부자 관점) — docs/NEXA_DESKTOP_SCREENS.md 07 ──
    const wrapEl = document.getElementById('serverListWrap');
    const detailEl = document.getElementById('serverDetail');
    const manageEl = document.getElementById('serverManage');
    // 언어 전환 시 현재 보이는 서브뷰(목록/상세/관리)를 그 자리서 다시 그리려고 마지막 데이터를 캡처한다.
    let _lastDetailD = null, _lastManageD = null, _lastManageM = null;

    function backToList() { detailEl.hidden = true; manageEl.hidden = true; wrapEl.hidden = false; reload(); if (window.navTo) window.navTo('#/servers'); }

    async function openDetail(guildId) {
      const d = await api.getServerDetail(guildId);
      if (!d) return;
      renderDetail(d);
      // 한 화면만 보이도록 셋 다 명시 토글(관리 화면 잔류 방지 — 기부자 상세에 관리 탭이 새던 버그).
      wrapEl.hidden = true; detailEl.hidden = false; manageEl.hidden = true;
      if (window.navTo) window.navTo('#/servers/' + guildId);
    }

    function renderDetail(d) {
      _lastDetailD = d;
      const st = presentServerState(d.state);
      const role = presentRole(d.role);
      const isAdmin = d.role === Role.ADMIN;
      const pending = d.state === ProviderState.PENDING;
      const paused = d.state === ProviderState.PAUSED;
      // 내 모델 칩
      const modelChips = d.myModels.length
        ? d.myModels.map(m => '<span class="dchip"><span class="d"></span>' + m + '</span>').join('')
        : '<span class="dim">' + t('serversDetailNoModels') + '</span>';
      // 제공 토글 버튼(PENDING 이면 숨김)
      const provBlock = pending ? '' :
        '<div class="dcard"><div class="drow"><div><div class="dlabel">' + t('serversDetailMyProvisionForServer') + '</div>' +
        '<div class="dsub">' + (paused
          ? '<span class="srv-st paused"><span class="d"></span>' + t('serversPaused') + '</span> — ' + t('serversDetailNotProvidingDesc')
          : '<span class="srv-st ok"><span class="d"></span>' + t('serversProviding') + '</span> — ' + t('serversDetailProvidingDesc')) + '</div></div>' +
        '<button class="btn btn--md ' + (paused ? 'btn--primary' : 'btn--secondary') + '" id="dPauseBtn">' + (paused ? t('serversDetailResumeButton') : t('serversDetailPauseButton')) + '</button></div></div>';
      // 내 정책(PENDING 이면 숨김)
      const policyBlock = pending ? '' :
        '<div class="dcard"><div class="drow"><div class="dlabel">' + t('serversDetailPolicyLabel') + ' <span class="dim">· ' + t('serversDetailPolicyNote') + '</span></div>' +
        '<button class="btn btn--sm btn--secondary" id="dPolicyBtn">' + t('serversDetailChangePolicy') + '</button></div>' +
        '<div class="dpolicy">' +
          '<div><span>' + t('serversDetailDailyLimit') + '</span><b>' + (d.policy.dailyLimit === 0 ? t('serversDetailUnlimited') : t('serversDetailDailyCount').replace('{n}', d.policy.dailyLimit)) + '</b></div>' +
          '<div><span>' + t('serversDetailConcurrency') + '</span><b>' + d.policy.maxConcurrency + '</b></div>' +
          '<div><span>' + t('serversDetailMaxTime') + '</span><b>' + fmtSec(d.policy.maxSeconds) + '</b></div>' +
          '<div><span>' + t('serversDetailVisibility') + '</span><b>' + t('serversDetailVisibilityValue') + '</b></div>' +
        '</div></div>';
      // 이 서버에 제공할 AI(채팅 모델 칩 + 이미지) — '무엇을 제공'(모델)을 '얼마나·누구에게'(정책)와 분리.
      //   채팅: 켠 모델만 제공(전체 끄면 전체). 로컬/클라우드(Gemini)는 칩 배지로 구분 — 별도 토글 없음.
      //   목록은 async(getServerModels)로 채운다. PENDING 이면 숨김(승인 후).
      const modelsBlock = pending ? '' :
        '<div class="dcard" id="dModelsCard"><div class="dlabel">' + t('serversDetailModelsLabel') + ' <span class="dim">· ' + t('serversDetailModelsNote') + '</span></div>' +
        '<div class="dmrow"><span class="dmcap">' + t('serversDetailChat') + '</span><div id="dChatChips" class="dchips"><span class="dim">' + t('serversDetailLoading') + '</span></div></div>' +
        '<div class="dmrow"><span class="dmcap">' + t('serversDetailImage') + '</span><div id="dImgWrap" class="dchips"><span class="dim">' + t('serversDetailLoading') + '</span></div></div>' +
        '</div>';
      // PENDING 안내 — 승인 전이라 기여현황·관리·정책 모두 숨기고 이 안내만 노출.
      const pendingBlock = pending ?
        '<div class="dcard dwarn"><div class="dlabel">⚠ ' + t('serversDetailWaitingApproval') + '</div>' +
        '<div class="dsub">' + t('serversDetailApprovalDesc') + '</div></div>' : '';
      // 관리자/기부자 분기 — 관리자는 **이 앱에서 직접** 관리(웹 위임 폐기). PENDING 이면 둘 다 숨김.
      const manageBlock = pending ? '' : (isAdmin ?
        '<div class="dcard dmanage"><div class="drow"><div><div class="dlabel">🛡 ' + t('serversDetailManageLabel') + '</div>' +
        '<div class="dsub">' + t('serversDetailManageDesc') + '</div></div>' +
        '<button class="btn btn--md btn--primary" id="dManageBtn">' + t('serversDetailManageButton') + ' <span class="arrow">›</span></button></div></div>'
        :
        '<div class="dcard"><div class="dlabel">🔒 ' + t('serversDetailNoPermission') + '</div>' +
        '<div class="dsub">' + t('serversDetailNoPermissionDesc') + '</div></div>');
      // 기여 현황 카드 — PENDING 이면 숨김(아직 기여 시작 전).
      // 미추적 값(오늘 처리·평균 지연)은 가짜 0/0ms 대신 '—'. 제공 모델은 실제 광고 수.
      const fStat = (v, suf) => (v != null ? v + (suf || '') : '—');
      const contribBlock = pending ? '' :
        '<div class="dcard"><div class="dlabel">' + t('serversDetailContribution') + ' <span class="dim">· ' + t('serversDetailToday') + '</span></div>' +
          '<div class="dstats">' +
            '<div><b>' + fStat(d.today) + '</b><span>' + t('serversDetailRequests') + '</span></div>' +
            '<div><b>' + d.myModels.length + '</b><span>' + t('serversDetailProvidedModels') + '</span></div>' +
            '<div><b>' + fStat(d.avgMs, 'ms') + '</b><span>' + t('serversDetailAverage') + '</span></div>' +
          '</div>' +
          '<div class="dmodels">' + modelChips + '</div>' +
        '</div>';

      // 서버 설정(이름 변경·제공 그만두기) — 내 로컬 연결 관리. PENDING 이어도 이름변경/제거는 가능.
      const settingsBlock =
        '<div class="dcard"><div class="drow"><div><div class="dlabel">' + t('serversDetailSettings') + '</div>' +
        '<div class="dsub">' + t('serversDetailSettingsDesc') + '</div></div>' +
        '<div style="display:flex;gap:8px"><button class="btn btn--sm btn--secondary" id="dRenameBtn">' + t('serversDetailRenameButton') + '</button>' +
        '<button class="btn btn--sm btn--danger" id="dRemoveBtn">' + t('serversDetailRemoveButton') + '</button></div></div></div>';

      detailEl.innerHTML =
        '<div class="dhead">' +
          '<button class="dback" id="dBack">‹ ' + t('serversDetailBackButton') + '</button>' +
          '<div class="dtitle"><h1>' + esc(d.guildName) + '</h1>' +
            '<span class="srv-st ' + st.dot + '"><span class="d"></span>' + st.label + '</span></div>' +
          '<div class="dsubhead">' + (d.members != null ? t('serversMemberLabel') + ' ' + fmtMembers(d.members) + ' · ' : '') + t('serversDetailMyRole') + ' <span class="srv-role ' + role.cls + '">' + ROLE_ICON[role.cls] + role.label + '</span></div>' +
        '</div>' +
        pendingBlock + contribBlock + provBlock + modelsBlock + policyBlock + manageBlock + settingsBlock;

      // 액션 바인딩
      const back = detailEl.querySelector('#dBack'); if (back) back.onclick = backToList;
      const pb = detailEl.querySelector('#dPauseBtn');
      if (pb) pb.onclick = async () => {
        const willPause = !paused;
        await api.setServerPaused(d.guildId, willPause);
        toast(willPause ? t('serversPausedSuccess') : t('serversResumedSuccess'), { type: willPause ? 'info' : 'ok', sub: d.guildName });
        openDetail(d.guildId); // 재렌더
      };
      const mb = detailEl.querySelector('#dManageBtn');
      if (mb) mb.onclick = () => openManage(d);
      const polb = detailEl.querySelector('#dPolicyBtn');
      if (polb) polb.onclick = () => openPolicyModal(d);
      const rnb = detailEl.querySelector('#dRenameBtn');
      if (rnb) rnb.onclick = () => openRenameModal(d);
      const rmb = detailEl.querySelector('#dRemoveBtn');
      if (rmb) rmb.onclick = () => openRemoveModal(d);
      if (!pending) loadDetailModels(d);
    }

    // 이 서버에 제공할 AI — 채팅 칩(로컬/클라우드 배지) + 이미지 토글. 변경 시 즉시 저장(setServerModels).
    function loadDetailModels(d) {
      const chatEl = document.getElementById('dChatChips');
      const imgEl = document.getElementById('dImgWrap');
      if (!chatEl || !imgEl) return;
      const isCloud = (m) => /^gemini/i.test(m); // 클라우드(Gemini) vs 로컬 구분 — 이름 규칙
      let sm = null; // {available, chatModels, imageEnabled, imageReady}
      const save = async () => {
        const chips = [...chatEl.querySelectorAll('.dchip-tog')];
        const on = chips.filter(c => c.classList.contains('on')).map(c => c.dataset.m);
        const chatModels = (on.length === chips.length) ? [] : on; // 전부 켜짐 = 전체 제공(빈 배열)
        const imgBtn = imgEl.querySelector('#dImgTog');
        const imageEnabled = !!(imgBtn && imgBtn.classList.contains('on'));
        try { await api.setServerModels(d.guildId, chatModels, imageEnabled); toast(t('serversDetailSaveModels'), { type: 'ok', sub: d.guildName }); }
        catch (_e) { toast(t('serversDetailSaveModelsFailed'), { type: 'error' }); }
      };
      api.getServerModels(d.guildId).then((res) => {
        sm = res || {};
        const avail = sm.available || [];
        const sel = new Set(sm.chatModels || []); // 빈=전체
        const all = sel.size === 0;
        chatEl.innerHTML = avail.length
          ? avail.map(m => '<button class="dchip-tog' + ((all || sel.has(m)) ? ' on' : '') + '" data-m="' + m + '">' +
              (isCloud(m) ? '<span class="dchip-b cloud">☁ ' + t('serversDetailCloudBadge') + '</span>' : '<span class="dchip-b local">💻 ' + t('serversDetailLocalBadge') + '</span>') +
              '<span class="dchip-n">' + m + '</span></button>').join('') +
              '<div class="dchip-help">' + t('serversDetailChipHelp') + '</div>'
          : '<span class="dim">' + t('serversDetailNoAvailableModels') + '</span>';
        imgEl.innerHTML = sm.imageReady
          ? '<button class="dchip-tog' + (sm.imageEnabled ? ' on' : '') + '" id="dImgTog"><span class="dchip-n">🖼 ' + t('serversDetailImageGeneration') + '</span></button>' +
            '<div class="dchip-help">' + t('serversDetailImageHelp') + '</div>'
          : '<span class="dim">' + t('serversDetailNoImageEngine') + '</span>';
        chatEl.querySelectorAll('.dchip-tog').forEach(c => c.onclick = () => { c.classList.toggle('on'); save(); });
        const imgTog = imgEl.querySelector('#dImgTog');
        if (imgTog) imgTog.onclick = () => { imgTog.classList.toggle('on'); save(); };
      }).catch(() => { chatEl.innerHTML = '<span class="dim">' + t('serversDetailModelInfoFailed') + '</span>'; imgEl.innerHTML = ''; });
    }

    // 서버 표시 이름 변경 모달(텍스트 입력)
    function openRenameModal(d) {
      const lay = document.createElement('div');
      lay.className = 'modal-layer';
      lay.innerHTML = '<div class="modal" style="width:min(420px,100%)">' +
        '<button class="modal-x" data-x aria-label="' + t('serversCloseButton') + '">✕</button>' +
        '<h3>' + t('serversDetailRenameTitle') + '</h3><p class="msub">' + t('serversDetailRenameNote') + '</p>' +
        '<div class="pform"><div class="pfield"><label>' + t('serversDetailDisplayName') + '</label>' +
        '<input id="rnInput" type="text" maxlength="60" value="' + esc(d.guildName) + '" ' +
        'style="height:44px;padding:0 14px;border-radius:11px;border:1px solid var(--line);background:rgba(255,255,255,.03);color:var(--text);font:inherit;font-size:14px"></div></div>' +
        '<div class="modal-foot"><button class="btn btn--md btn--secondary" data-x>' + t('serversDetailCancelButton') + '</button>' +
        '<button class="btn btn--md btn--primary" id="rnSave">' + t('serversDetailSaveButton') + '</button></div></div>';
      document.body.appendChild(lay);
      lay.querySelectorAll('[data-x]').forEach(b => b.onclick = () => lay.remove());
      lay.querySelector('#rnSave').onclick = async () => {
        const name = lay.querySelector('#rnInput').value.trim();
        if (!name) { toast(t('serversDetailEnterName'), { type: 'error' }); return; }
        try { await api.renameServer(d.guildId, name); lay.remove(); toast(t('serversDetailRenameSuccess'), { type: 'ok', sub: name }); openDetail(d.guildId); }
        catch (_e) { toast(t('serversDetailRenameFailed'), { type: 'error' }); }
      };
    }

    // 이 서버 제공 그만두기(연결 제거) 확인 모달
    function openRemoveModal(d) {
      const lay = document.createElement('div');
      lay.className = 'modal-layer';
      lay.innerHTML = '<div class="modal" style="width:min(440px,100%)">' +
        '<button class="modal-x" data-x aria-label="' + t('serversCloseButton') + '">✕</button>' +
        '<h3>' + t('serversDetailRemoveTitle') + '</h3>' +
        '<p class="msub">' + t('serversDetailRemoveDesc').replace('{name}', '<b>' + esc(d.guildName || t('serversThisServerFallback')) + '</b>') + '</p>' +
        '<div class="modal-foot"><button class="btn btn--md btn--secondary" data-x>' + t('serversDetailCancelButton') + '</button>' +
        '<button class="btn btn--md btn--danger" id="rmGo">' + t('serversDetailRemoveConfirm') + '</button></div></div>';
      document.body.appendChild(lay);
      lay.querySelectorAll('[data-x]').forEach(b => b.onclick = () => lay.remove());
      lay.querySelector('#rmGo').onclick = async () => {
        try { await api.removeServer(d.guildId); lay.remove(); toast(t('serversDetailRemoveSuccess'), { type: 'ok', sub: d.guildName }); backToList(); }
        catch (_e) { toast(t('serversDetailRemoveFailed'), { type: 'error' }); }
      };
    }

    // 초 → "10분"·"1분 30초"·"30초" 사람친화 표기
    const fmtSec = (s) => { const m = Math.floor(s / 60), r = s % 60; return m && r ? t('serversFmtMinSec').replace('{m}', m).replace('{r}', r) : m ? t('serversFmtMin').replace('{m}', m) : t('serversFmtSec').replace('{s}', r); };

    // 내 self-service 정책 변경 모달 — 선택지 고정(자유 입력 X). 기본: 하루 50·동시 1·최대 10분·모두에게.
    function openPolicyModal(d) {
      const DAILY = [{ v: 10, t: t('serversDetailDailyCount').replace('{n}', 10) }, { v: 50, t: t('serversDetailDailyCount').replace('{n}', 50) }, { v: 100, t: t('serversDetailDailyCount').replace('{n}', 100) }, { v: 0, t: t('serversDetailUnlimited') }];
      const CONC = [1, 2, 3, 4, 5];
      const seg = (id, items, cur) => '<div class="pseg" id="' + id + '">' +
        items.map(o => { const v = typeof o === 'object' ? o.v : o, t = typeof o === 'object' ? o.t : o;
          return '<button type="button" data-v="' + v + '"' + (v === cur ? ' class="active"' : '') + '>' + t + '</button>'; }).join('') + '</div>';
      const lay = document.createElement('div');
      lay.className = 'modal-layer';
      lay.innerHTML = '<div class="modal" style="width:min(460px,100%)">' +
        '<button class="modal-x" data-x aria-label="' + t('serversCloseButton') + '">✕</button>' +
        '<h3>' + t('serversPolicyTitle') + '</h3><p class="msub">' + t('serversPolicyDesc').replace('{name}', '<b>' + t('serversPolicyDescSelf') + '</b>') + '</p>' +
        '<div class="pform">' +
          '<div class="pfield"><label>' + t('serversPolicyDailyLimitLabel') + '</label>' + seg('pDaily', DAILY, d.policy.dailyLimit) + '</div>' +
          '<div class="pfield"><label>' + t('serversPolicyConcurrencyLabel') + ' <span class="dim">· ' + t('serversPolicyConcurrencyNote') + '</span></label>' + seg('pConc', CONC, d.policy.maxConcurrency) + '</div>' +
          '<div class="pfield"><label>' + t('serversPolicyMaxTimeLabel') + ' <span class="dim">· ' + t('serversPolicyMaxTimeNote') + '</span></label>' +
            '<div class="pstep"><button type="button" data-step="-1" aria-label="' + t('serversPolicyReduceButton') + '">−</button>' +
            '<span id="pSecVal" data-sec="' + d.policy.maxSeconds + '">' + fmtSec(d.policy.maxSeconds) + '</span>' +
            '<button type="button" data-step="1" aria-label="' + t('serversPolicyIncreaseButton') + '">+</button></div></div>' +
          '<div class="pfield"><label>' + t('serversPolicyVisibilityLabel') + '</label>' +
            '<p class="dim" style="margin:2px 0 0">' + t('serversPolicyVisibilityNote') + '</p></div>' +
        '</div>' +
        '<div class="modal-foot"><button class="btn btn--md btn--secondary" data-x>' + t('serversDetailCancelButton') + '</button>' +
        '<button class="btn btn--md btn--primary" id="pSave">' + t('serversPolicySaveButton') + '</button></div></div>';
      document.body.appendChild(lay);
      lay.querySelectorAll('[data-x]').forEach(b => b.onclick = () => lay.remove());
      // 세그먼트(하루 한도·동시 처리) 단일 선택 토글
      lay.querySelectorAll('.pseg').forEach(seg => seg.querySelectorAll('button').forEach(b =>
        b.onclick = () => seg.querySelectorAll('button').forEach(x => x.classList.toggle('active', x === b))));
      // (모델 선택은 서버 상세의 '이 서버에 제공할 AI' 카드로 분리 — 정책 모달엔 한도/동시/시간만)
      // 최대 시간 스테퍼(30초 단위, 30초~30분)
      const secEl = lay.querySelector('#pSecVal');
      lay.querySelectorAll('[data-step]').forEach(b => b.onclick = () => {
        const s = Math.max(30, Math.min(1800, parseInt(secEl.dataset.sec, 10) + (+b.dataset.step) * 30));
        secEl.dataset.sec = s; secEl.textContent = fmtSec(s);
      });
      lay.querySelector('#pSave').onclick = async () => {
        const policy = {
          dailyLimit: +lay.querySelector('#pDaily button.active').dataset.v,
          maxConcurrency: +lay.querySelector('#pConc button.active').dataset.v,
          maxSeconds: +secEl.dataset.sec,
        };
        await api.setServerPolicy(d.guildId, policy);
        lay.remove();
        toast(t('serversPolicySaveSuccess'), { type: 'ok', sub: d.guildName });
        openDetail(d.guildId);
      };
    }

    // ── 서버 관리(관리자, 앱 내 — 웹 위임 폐기). 13 Provider 관리부터 구현, 나머지 탭은 단계적. ──
    // 탭 라벨은 i18n 키만 저장 → 렌더 시 t(key) 로 해석(모듈 로드 시 고정 = 언어전환 후 stale 방지).
    const MTABS = [
      { k: 'overview', tk: 'serversManageTabOverview' }, { k: 'profile', tk: 'serversManageTabProfile' }, { k: 'channels', tk: 'serversManageTabChannels' },
      { k: 'channelai', tk: 'serversManageTabChannelAI' }, { k: 'rag', tk: 'serversManageTabRAG' }, { k: 'preset', tk: 'serversManageTabPreset' },
      { k: 'provider', tk: 'serversManageTabProvider' }, { k: 'safety', tk: 'serversManageTabSafety' },
    ]; // v1: 역할별 정책·다중응답(multi)은 범위 외
    let _manageTab = 'provider';

    // 탭별 지연 로드 — 실 백엔드 manage 응답은 {ok,policy,pending,roster} 뿐이라 prompts 등은 탭 진입 시 별도 조회.
    //   (mock 은 manage 객체에 모두 포함하므로 이미 정의되어 재조회하지 않음.)
    async function ensureTabData(d, m, tab) {
      if (tab === 'profile' && m.prompts === undefined) {
        const r = await api.getPromptSets(d.guildId);
        m.prompts = (r && r.sets) || [];
      }
      if (tab === 'channels' && m.channels === undefined) {
        const r = await api.getChannels(d.guildId);
        // 실 응답은 플랫 채널 목록만(서버 기본값 별도) — 기본값은 비워두고 목록만 채운다.
        m.channels = { defaultModel: '', defaultLang: '', list: (r && r.channels) || [] };
      }
      // 읽기 전용 탭 — null = 기능 비활성/오류(안내), 배열 = 목록.
      if (tab === 'channelai' && m._channelAi === undefined) {
        const r = await api.getChannelAi(d.guildId);
        m._channelAi = (r && r.ok) ? (r.items || []) : null;
      }
      if (tab === 'rag' && m._rag === undefined) {
        const r = await api.getKnowledge(d.guildId);
        m._rag = (r && r.ok) ? (r.docs || []) : null;
      }
      if (tab === 'preset' && m._presets === undefined) {
        const r = await api.getPresets(d.guildId);
        m._presets = (r && r.ok) ? (r.presets || []) : null;
      }
    }

    async function openManage(d, tab) {
      // 관리는 관리자(role=ADMIN)만. 기부자/승인대기는 진입 불가(서버 관리 탭은 관리자 전용).
      if (d.role !== Role.ADMIN || d.state === ProviderState.PENDING) { openDetail(d.guildId); return; }
      _manageTab = MTABS.some(x => x.k === tab) ? tab : 'provider';
      const m = await api.getServerManage(d.guildId);
      // 권한은 central 이 JDA 로 최종 판정 — ok=false 면 비관리자(상세에서 진입했어도 방어).
      if (m && m.ok === false) { toast(t('serversManageAdminRequiredToast'), { type: 'info', sub: d.guildName }); openDetail(d.guildId); return; }
      detailEl.hidden = true; manageEl.hidden = false; wrapEl.hidden = true;
      await ensureTabData(d, m, _manageTab);
      renderManage(d, m);
      if (window.navTo) window.navTo('#/servers/' + d.guildId + '/manage' + (_manageTab === 'provider' ? '' : '/' + _manageTab));
    }
    function backToDetail(d) { manageEl.hidden = true; openDetail(d.guildId); }

    const TAB_RENDER = {
      provider: (m) => renderProviderTab(m),
      overview: (m, d) => renderOverviewTab(m, d),
      profile: (m) => renderProfileTab(m),
      channels: (m) => renderChannelsTab(m),
      channelai: (m) => renderChannelAiTab(m),
      rag: (m) => renderRagTab(m),
      preset: (m) => renderPresetTab(m),
      safety: (m) => renderSafetyTab(m),
    };

    function renderManage(d, m) {
      _lastManageD = d; _lastManageM = m;
      const tabs = MTABS.map(tab => '<button class="mtab' + (tab.k === _manageTab ? ' active' : '') + '" data-mtab="' + tab.k + '">' + t(tab.tk) +
        (tab.k === 'provider' && m.pending.length ? '<span class="badge">' + m.pending.length + '</span>' : '') + '</button>').join('');
      const body = (TAB_RENDER[_manageTab] || (() => ''))(m, d);
      manageEl.innerHTML =
        '<div class="dhead"><button class="dback" id="mBack">‹ ' + esc(d.guildName) + '</button>' +
          '<div class="dtitle"><h1>' + t('serversManageTitle') + '</h1></div>' +
          '<div class="dsubhead">' + esc(d.guildName) + ' · ' + t('serversManageAdminOnly') + '</div></div>' +
        '<div class="mtabs">' + tabs + '</div><div id="mBody">' + body + '</div>';
      manageEl.querySelector('#mBack').onclick = () => backToDetail(d);
      manageEl.querySelectorAll('[data-mtab]').forEach(b => b.onclick = async () => {
        _manageTab = b.dataset.mtab; await ensureTabData(d, m, _manageTab); renderManage(d, m);
        if (window.navTo) window.navTo('#/servers/' + d.guildId + '/manage' + (_manageTab === 'provider' ? '' : '/' + _manageTab));
      });
      if (_manageTab === 'provider') bindProviderActions(d);
      else bindTabActions(d, m);
    }

    // 상태 맵은 키만 저장(모듈 로드 시 t() 고정 = 언어전환 후 stale 방지) → 렌더 시 t() 로 해석. [cls, 접두기호, 키].
    const ST_MAP = { [ProviderState.ONLINE_IDLE]: ['ok', '●', 'serversProviding'], [ProviderState.ONLINE_BUSY]: ['ok', '●', 'serversResponding'], [ProviderState.PAUSED]: ['paused', '⏸', 'serversPaused'], [ProviderState.PENDING]: ['pending', '⚠', 'serversPendingApproval'] };
    const av = (n) => '<span class="prov-av">' + (n || '·').trim().charAt(0) + '</span>';

    function renderProviderTab(m) {
      let html = '';
      if (m.pending.length) {
        html += '<div class="msec-label">' + t('serversProviderWaitingApproval') + ' (' + m.pending.length + ')</div>';
        html += m.pending.map(p => '<div class="prov-row wait">' + av(p.name) +
          '<div class="prov-main"><div class="prov-nm">' + p.name + '</div>' +
          '<div class="prov-meta">' + t('serversProviderAvailableModels') + ' ' + t('serversProviderModelsCount').replace('{n}', p.models) + ' · ' + p.since + '</div></div>' +
          '<div class="prov-acts"><button class="btn btn--sm btn--secondary" data-reject="' + p.providerUserId + '">' + t('serversProviderRejectButton') + '</button>' +
          '<button class="btn btn--sm btn--primary" data-approve="' + p.providerUserId + '">' + t('serversProviderApproveButton') + '</button></div></div>').join('');
      }
      html += '<div class="msec-label">' + t('serversProviderConnected') + ' (' + m.roster.length + ')</div>';
      html += m.roster.map(p => { const s = ST_MAP[p.state] || ['paused', '', p.state];
        return '<div class="prov-row">' + av(p.name) +
          '<div class="prov-main"><div class="prov-nm">' + p.name + (p.isMe ? '<span class="me">' + t('serversProviderMe') + '</span>' : '') + '</div>' +
          '<div class="prov-meta"><span class="srv-st ' + s[0] + '"><span class="d"></span>' + (s[1] ? s[1] + ' ' : '') + t(s[2], s[2]) + '</span> · ' + t('serversProviderModels') + ' ' + p.models + ' · ' + t('serversProviderToday') + ' ' + t('serversProviderTodayCount').replace('{n}', p.today) + '</div></div>' +
          (p.isMe ? '' : '<div class="prov-acts"><button class="btn btn--sm btn--secondary" data-remove="' + p.providerUserId + '" data-name="' + p.name + '">' + t('serversProviderRemoveButton') + '</button></div>') + '</div>'; }).join('');
      // 서버 제공 정책 — 신규 자동 승인 토글
      const auto = m.policy.autoApprove;
      html += '<div class="msec-label">' + t('serversProviderPolicy') + '</div>' +
        '<div class="prov-row"><div class="prov-main"><div class="prov-nm">' + t('serversProviderAutoApprove') + '</div>' +
        '<div class="prov-meta">' + (auto ? t('serversProviderAutoApproveEnabled') : t('serversProviderAutoApproveDisabled')) + '</div></div>' +
        '<div class="prov-acts"><button class="btn btn--sm ' + (auto ? 'btn--primary' : 'btn--secondary') + '" data-autotoggle="' + (auto ? '0' : '1') + '">' + (auto ? t('serversProviderAutoApproveDisableButton') : t('serversProviderAutoApproveEnableButton')) + '</button></div></div>';
      return html;
    }

    function bindProviderActions(d) {
      const refresh = async () => renderManage(d, await api.getServerManage(d.guildId));
      // providerUserId 는 64bit Discord userId — 문자열로 그대로 전달(Number 화 금지, 정밀도 손실).
      manageEl.querySelectorAll('[data-approve]').forEach(b => b.onclick = async () => { await api.approveProvider(d.guildId, b.dataset.approve); toast(t('serversProviderApproveSuccess'), { type: 'ok' }); refresh(); });
      manageEl.querySelectorAll('[data-reject]').forEach(b => b.onclick = async () => { await api.rejectProvider(d.guildId, b.dataset.reject); toast(t('serversProviderRejectSuccess'), { type: 'info' }); refresh(); });
      // 제거는 위험 작업(디자인 정책 7) → 확인 모달 뒤로
      manageEl.querySelectorAll('[data-remove]').forEach(b => b.onclick = () => confirmRemoveProvider(d, b.dataset.remove, b.dataset.name, refresh));
      const at = manageEl.querySelector('[data-autotoggle]');
      if (at) at.onclick = async () => {
        const on = at.dataset.autotoggle === '1';
        await api.setManagePolicy(d.guildId, { autoApprove: on });
        toast(on ? t('serversProviderAutoApproveEnabledSuccess') : t('serversProviderAutoApproveDisabledSuccess'), { type: on ? 'ok' : 'info' });
        refresh();
      };
    }

    function confirmRemoveProvider(d, userId, name, done) {
      const lay = document.createElement('div');
      lay.className = 'modal-layer';
      lay.innerHTML = '<div class="modal" style="width:min(420px,100%)">' +
        '<button class="modal-x" data-x aria-label="' + t('serversCloseButton') + '">✕</button>' +
        '<h3>' + t('serversProviderRemoveConfirmTitle').replace('{name}', name) + '</h3>' +
        '<p class="msub">' + t('serversProviderRemoveConfirmDesc') + '</p>' +
        '<div class="modal-foot"><button class="btn btn--md btn--secondary" data-x>' + t('serversDetailCancelButton') + '</button>' +
        '<button class="btn btn--md btn--warn" id="rmYes">' + t('serversProviderRemoveConfirm') + '</button></div></div>';
      document.body.appendChild(lay);
      lay.querySelectorAll('[data-x]').forEach(b => b.onclick = () => lay.remove());
      lay.querySelector('#rmYes').onclick = async () => { await api.removeProvider(d.guildId, userId); lay.remove(); toast(t('serversProviderRemoveSuccess').replace('{name}', name), { type: 'info' }); done(); };
    }

    // 실 백엔드 미브리지(Gap-M) 탭의 정직한 안내 — 크래시 대신 안내. 채널/RAG/프리셋은 central 에 구현돼 있고
    //   앱 직접관리 브리지는 단계적 확장 중. 그 전까지는 Discord 슬래시 명령·웹 대시보드에서 관리.
    const MSOON_REAL = (label) => '<div class="msoon"><b>' + label + ' — ' + t('serversManagedFeaturesSoon') + '</b>' + t('serversManagedFeaturesSoonDesc') + '</div>';

    // ── 관리 탭 08~12 (채널/채널AI/RAG/프리셋/안전: 프로토타입 mock. 실연동은 Gap-M 채널 단계 확장) ──
    function renderOverviewTab(m, d) {
      const todaySum = m.roster.reduce((a, p) => a + (p.today || 0), 0);
      return '<div class="dcard"><div class="dlabel">' + t('serversOverviewLabel') + '</div>' +
        '<div class="dstats">' +
          '<div><b>' + (d.members != null ? fmtMembers(d.members) : '—') + '</b><span>' + t('serversMemberLabel') + '</span></div>' +
          '<div><b>' + m.roster.length + '</b><span>' + t('serversOverviewProvider') + '</span></div>' +
          '<div><b>' + todaySum + '</b><span>' + t('serversOverviewProcessed') + '</span></div>' +
        '</div></div>' +
        (m.pending.length ? '<div class="dcard dwarn"><div class="dlabel">⚠ ' + t('serversOverviewPendingApproval') + ' ' + t('serversOverviewPendingCount').replace('{n}', m.pending.length) + '</div><div class="dsub">' + t('serversOverviewPendingNote') + '</div></div>' : '') +
        (m.channels ? '<div class="dcard"><div class="dlabel">' + t('serversOverviewServerDefaults') + '</div><div class="dsub">' + t('serversOverviewDefaultModel') + ' ' + m.channels.defaultModel + ' · ' + t('serversOverviewDefaultLanguage') + ' ' + m.channels.defaultLang + '</div></div>' : '');
    }

    function renderProfileTab(m) {
      // builtin(NEXA 기본 페르소나)은 전문(content)을 클라이언트로 내리지 않는다 — preview 만 표시(전문 비공개).
      const prompts = (m.prompts || []).map(p => {
        const text = p.builtin ? (p.preview || '') : (p.content || '');
        const lock = p.builtin ? ' <span class="dim">🔒 ' + t('serversProfileBuiltinLock') + '</span>' : '';
        return '<div class="dcard"><div class="drow"><div style="min-width:0"><div class="dlabel">' + p.name +
          (p.isDefault ? ' <span class="me">' + t('serversProfileDefault') + '</span>' : '') + lock + '</div>' +
          '<div class="dsub">' + text.slice(0, 80) + (p.builtin || text.length > 80 ? '…' : '') + '</div></div>' +
          '<div class="prov-acts">' +
            (p.isDefault ? '' : '<button class="btn btn--sm btn--secondary" data-prompt-default="' + p.id + '">' + t('serversProfileSetDefaultButton') + '</button>') +
            (p.builtin ? '' : '<button class="btn btn--sm btn--secondary" data-prompt-del="' + p.id + '" data-name="' + p.name + '">' + t('serversProfileDeleteButton') + '</button>') +
          '</div></div></div>';
      }).join('');
      return '<div class="dsub" style="margin-bottom:16px">' + t('serversProfileDescription') + '</div>' +
        '<div class="drow"><div class="msec-label" style="margin:0">' + t('serversProfileGlobalPrompts') + ' <span class="dim">· ' + t('serversProfileGlobalPromptsNote') + '</span></div>' +
          '<button class="btn btn--sm btn--secondary" id="promptAdd">+ ' + t('serversProfileAddButton') + '</button></div>' + prompts;
    }

    function renderChannelsTab(m) {
      if (!m.channels) return MSOON_REAL(t('serversManageTabChannels'));
      const c = m.channels;
      const ch = c.list.length
        ? c.list.map(x => '<div class="mrow"><span class="mrow-nm"># ' + x.name + '</span>' +
            '<button class="mtoggle' + (x.aiAllowed ? ' on' : '') + '" data-ch-toggle="' + x.channelId + '">' + (x.aiAllowed ? t('serversChannelsAIAllowed') : t('serversChannelsAIDisabled')) + '</button></div>').join('')
        : '<span class="dim">' + t('serversChannelsNone') + '</span>';
      // 서버 기본값은 실 채널 응답에 없으므로 값이 있을 때만 표시(거짓 정보 방지).
      const defaults = c.defaultModel ? '<div class="dcard"><div class="dlabel">' + t('serversOverviewServerDefaults') + '</div><div class="dsub">' + t('serversOverviewDefaultModel') + ' ' + c.defaultModel + ' · ' + t('serversOverviewDefaultLanguage') + ' ' + c.defaultLang + '</div></div>' : '';
      return defaults +
        '<div class="msec-label">' + t('serversChannelsTitle') + ' <span class="dim">· ' + t('serversChannelsNote') + '</span></div><div class="dcard">' + ch + '</div>';
    }

    // 채널 AI 성격(말투·목적) 라벨 — central 도메인 값 → 한국어 표기(읽기 표시용).
    // 라벨은 i18n 키로 저장 → 렌더 시 t(키, 폴백) 로 해석(언어전환 후 stale 방지·미지정 enum 은 원문 폴백).
    const CAI_TONE = { friendly: 'serversCaiToneFriendly', formal: 'serversCaiToneFormal', concise: 'serversCaiToneConcise', playful: 'serversCaiTonePlayful', neutral: 'serversCaiToneNeutral' };
    const CAI_PURPOSE = { general_assistant: 'serversCaiPurposeGeneral', coding: 'serversCaiPurposeCoding', support: 'serversCaiPurposeSupport', moderation: 'serversCaiPurposeModeration' };
    const RAG_STATUS = { indexed: ['ok', 'serversRagStatusIndexed'], indexing: ['pending', 'serversRagStatusIndexing'], pending: ['pending', 'serversRagStatusPending'], blocked: ['error', 'serversRagStatusBlocked'], rejected: ['error', 'serversRagStatusRejected'] };
    const PRESET_STATUS = { active: ['ok', 'serversPresetStatusActive'], published: ['ok', 'serversPresetStatusPublished'], draft: ['paused', 'serversPresetStatusDraft'], archived: ['paused', 'serversPresetStatusArchived'] };
    const MANAGE_READONLY_NOTE = (what) => '<div class="dsub" style="margin-top:12px">' + what + ' ' + t('serversManageReadonlyNote') + '</div>';

    function renderChannelAiTab(m) {
      if (m._channelAi === null) return MSOON_REAL(t('serversManageTabChannelAI'));
      const items = m._channelAi || [];
      if (!items.length) return '<div class="msoon"><b>' + t('serversManageTabChannelAI') + '</b>' + t('serversChannelAIDescription') + '</div>' + MANAGE_READONLY_NOTE(t('serversManageTabChannelAI'));
      const cards = items.map(c => '<div class="dcard"><div class="drow"><div class="dlabel"># ' + c.name + '</div>' +
        '<span class="srv-st ok"><span class="d"></span>' + t('serversChannelAIConfigured') + '</span></div>' +
        '<div class="dsub">' + t('serversChannelAITone') + ' ' + t(CAI_TONE[c.tone], c.tone || '-') + ' · ' + t('serversChannelAIPurpose') + ' ' + t(CAI_PURPOSE[c.purpose], c.purpose || '-') + '</div></div>').join('');
      return cards + MANAGE_READONLY_NOTE(t('serversManageTabChannelAI'));
    }

    function renderRagTab(m) {
      if (m._rag === null) return MSOON_REAL(t('serversManageTabRAG'));
      const docs = m._rag || [];
      const rows = docs.length ? docs.map(x => { const s = RAG_STATUS[x.status] || ['pending', x.status];
        return '<div class="mrow"><span class="mrow-nm">📄 ' + x.title + '</span>' +
          '<span class="mrow-meta"><span class="srv-st ' + s[0] + '"><span class="d"></span>' + t(s[1], s[1]) + '</span>' + ((x.indexedAt || x.addedAt) ? ' · ' + (x.indexedAt || x.addedAt) : '') +
          ' <button class="btn btn--sm btn--danger" data-rag-del="' + x.id + '" data-name="' + (x.title || '').replace(/"/g, '&quot;') + '">' + t('serversRagDocDeleteButton') + '</button></span></div>'; }).join('')
        : '<span class="dim">' + t('serversRagNoDocuments') + '</span>';
      return '<div class="msec-label">' + t('serversRagLabel') + ' <span class="dim">· ' + t('serversRagDescription') + '</span></div><div class="dcard">' + rows + '</div>' +
        '<div class="dsub" style="margin-top:12px">' + t('serversRagContentResponsibility') + '</div>' +
        MANAGE_READONLY_NOTE(t('serversRagManageNote'));
    }

    function renderPresetTab(m) {
      if (m._presets === null) return MSOON_REAL(t('serversManageTabPreset'));
      const items = m._presets || [];
      const cards = items.length ? items.map(p => { const s = PRESET_STATUS[p.status] || ['paused', p.status];
        return '<div class="dcard"><div class="drow"><div style="min-width:0"><div class="dlabel">' + p.name + '</div>' +
          '<div class="dsub">' + (p.summary || p.category || '') + '</div></div>' +
          '<div style="display:flex;gap:8px;align-items:center"><span class="srv-st ' + s[0] + '"><span class="d"></span>' + t(s[1], s[1]) + '</span>' +
          '<button class="btn btn--sm btn--danger" data-preset-del="' + p.id + '" data-name="' + (p.name || '').replace(/"/g, '&quot;') + '">' + t('serversPresetDeleteButton') + '</button></div></div></div>'; }).join('')
        : '<div class="msoon"><b>' + t('serversPresetEmptyTitle') + '</b>' + t('serversPresetEmpty') + '</div>';
      return cards + MANAGE_READONLY_NOTE(t('serversPresetManageNote'));
    }

    function renderSafetyTab(m) {
      // 콘텐츠 정책 + 실제 신고·대응 경로(진짜 동작) — 항상 표시. 가짜 신고 목록·빈 '준비 중' 대신,
      // 이 서비스가 실제로 어떻게 안전을 다루는지 정직하게 안내한다(per-guild 신고 큐는 미제공).
      const policy = '<div class="dcard"><div class="dlabel">' + t('serversSafetyPolicy') + '</div>' +
        '<div class="dsub">' + t('serversSafetyPolicyDescription') + '</div></div>';
      const howto = '<div class="dcard"><div class="dlabel">' + t('serversSafetyReport') + '</div>' +
        '<div class="dsub">' + t('serversSafetyReportDescription') + '</div></div>';
      let proto = '';
      /* @proto-only */
      if (m.safety && Array.isArray(m.safety.reports)) {
        const open = m.safety.reports.filter(r => r.status === 'open');
        const reports = open.length
          ? open.map(r => '<div class="prov-row wait"><div class="prov-main"><div class="prov-nm">' + r.target + '</div>' +
              '<div class="prov-meta">' + r.reason + ' · ' + r.reporter + ' · ' + r.when + '</div></div>' +
              '<div class="prov-acts"><button class="btn btn--sm btn--secondary" data-report-dismiss="' + r.id + '">' + t('serversSafetyReportDismiss') + '</button>' +
              '<button class="btn btn--sm btn--warn" data-report-act="' + r.id + '">' + t('serversSafetyReportAction') + '</button></div></div>').join('')
          : '<div class="msoon"><b>' + t('serversSafetyNoReports') + '</b></div>';
        proto = '<div class="msec-label">' + t('serversSafetyReportsHeader') + ' (' + open.length + ')</div>' + reports;
      }
      /* @end-proto-only */
      return policy + howto + proto;
    }

    // 관리 탭 공통 인터랙션(mock 토글 + 미구현 액션 안내). 같은 m 을 수정해 즉시 재렌더.
    function bindTabActions(d, m) {
      const rerender = () => renderManage(d, m);
      manageEl.querySelectorAll('[data-soon]').forEach(b => b.onclick = () => toast(b.dataset.soon + t('serversManagedSoonToastSuffix'), { type: 'info' }));
      manageEl.querySelectorAll('[data-ch-toggle]').forEach(b => b.onclick = async () => {
        const id = b.dataset.chToggle;
        const x = m.channels.list.find(c => String(c.channelId) === String(id));
        if (!x) return;
        const r = await api.toggleChannel(d.guildId, id, !x.aiAllowed);
        if (r && r.ok === false) { toast(r.message || t('serversChangeFailedToast'), { type: 'info' }); return; }
        if (r && r.channels) m.channels.list = r.channels;
        rerender();
      });
      // 채널AI/RAG/프리셋은 읽기 전용 실연동(추가·편집은 Discord 명령·웹 대시보드) — 토글 액션 없음.
      // 전역 프롬프트셋 — 기본/삭제/추가 (실연동: webui → central /provider/admin/prompt-sets*)
      manageEl.querySelectorAll('[data-prompt-default]').forEach(b => b.onclick = async () => {
        const r = await api.setDefaultPromptSet(d.guildId, b.dataset.promptDefault);
        if (r && r.ok === false) { toast(r.message || t('serversChangeFailedToast'), { type: 'info' }); return; }
        if (r && r.sets) m.prompts = r.sets;
        toast(t('serversProfileSetDefaultSuccess'), { type: 'ok' }); rerender();
      });
      manageEl.querySelectorAll('[data-prompt-del]').forEach(b => b.onclick = () => confirmDeletePrompt(d, m, b.dataset.promptDel, b.dataset.name, rerender));
      // 프리셋 삭제(실연동: webui → central /provider/admin/presets/delete, 길드 소유권 가드)
      manageEl.querySelectorAll('[data-preset-del]').forEach(b => b.onclick = () => confirmDeletePreset(d, m, b.dataset.presetDel, b.dataset.name, rerender));
      // RAG 지식 소스 삭제(실연동: webui → central /provider/admin/knowledge/delete, 길드 소유권 가드)
      manageEl.querySelectorAll('[data-rag-del]').forEach(b => b.onclick = () => confirmDeleteSource(d, m, b.dataset.ragDel, b.dataset.name, rerender));
      const pa = manageEl.querySelector('#promptAdd');
      if (pa) pa.onclick = () => openPromptModal(d, m, rerender);
      // 안전 — 신고 처리(무시/숨김)
      manageEl.querySelectorAll('[data-report-dismiss]').forEach(b => b.onclick = () => { const r = m.safety.reports.find(x => x.id === b.dataset.reportDismiss); if (r) r.status = 'dismissed'; toast(t('serversSafetyDismissSuccess'), { type: 'info' }); rerender(); });
      manageEl.querySelectorAll('[data-report-act]').forEach(b => b.onclick = () => { const r = m.safety.reports.find(x => x.id === b.dataset.reportAct); if (r) r.status = 'resolved'; toast(t('serversSafetyActionSuccess'), { type: 'ok' }); rerender(); });
    }

    function confirmDeletePrompt(d, m, id, name, done) {
      const lay = document.createElement('div');
      lay.className = 'modal-layer';
      lay.innerHTML = '<div class="modal" style="width:min(420px,100%)">' +
        '<button class="modal-x" data-x aria-label="' + t('serversCloseButton') + '">✕</button>' +
        '<h3>' + t('serversProfileDeleteConfirmTitle').replace('{name}', name) + '</h3><p class="msub">' + t('serversProfileDeleteConfirmDesc') + '</p>' +
        '<div class="modal-foot"><button class="btn btn--md btn--secondary" data-x>' + t('serversDetailCancelButton') + '</button>' +
        '<button class="btn btn--md btn--warn" id="pdYes">' + t('serversProfileDeleteConfirm') + '</button></div></div>';
      document.body.appendChild(lay);
      lay.querySelectorAll('[data-x]').forEach(b => b.onclick = () => lay.remove());
      lay.querySelector('#pdYes').onclick = async () => {
        const r = await api.deletePromptSet(d.guildId, id);
        if (r && r.ok === false) { lay.remove(); toast(r.message || t('serversProfileDeleteFailed'), { type: 'info' }); return; }
        if (r && r.sets) m.prompts = r.sets; // central 이 기본 공백 방지(기본 셋 삭제 시 니아 복귀)까지 처리
        lay.remove(); toast(t('serversProfileDeleteSuccess').replace('{name}', name), { type: 'info' }); done();
      };
    }

    function confirmDeletePreset(d, m, id, name, done) {
      const lay = document.createElement('div');
      lay.className = 'modal-layer';
      lay.innerHTML = '<div class="modal" style="width:min(420px,100%)">' +
        '<button class="modal-x" data-x aria-label="' + t('serversCloseButton') + '">✕</button>' +
        '<h3>' + t('serversPresetDeleteConfirmTitle').replace('{name}', (name || t('serversManageTabPreset'))) + '</h3><p class="msub">' + t('serversPresetDeleteConfirmDesc') + '</p>' +
        '<div class="modal-foot"><button class="btn btn--md btn--secondary" data-x>' + t('serversDetailCancelButton') + '</button>' +
        '<button class="btn btn--md btn--danger" id="prdYes">' + t('serversPresetDeleteConfirm') + '</button></div></div>';
      document.body.appendChild(lay);
      lay.querySelectorAll('[data-x]').forEach(b => b.onclick = () => lay.remove());
      lay.querySelector('#prdYes').onclick = async () => {
        try {
          const r = await api.deletePreset(d.guildId, id);
          if (r && r.ok === false) { lay.remove(); toast(r.message || r.error || t('serversProfileDeleteFailed'), { type: 'info' }); return; }
          if (r && r.presets) m._presets = r.presets; else m._presets = (m._presets || []).filter(p => String(p.id) !== String(id));
          lay.remove(); toast(t('serversPresetDeleteSuccess').replace('{name}', (name || t('serversManageTabPreset'))), { type: 'ok' }); done();
        } catch (_e) { lay.remove(); toast(t('serversPresetDeleteFailed'), { type: 'error' }); }
      };
    }

    function confirmDeleteSource(d, m, id, name, done) {
      const lay = document.createElement('div');
      lay.className = 'modal-layer';
      lay.innerHTML = '<div class="modal" style="width:min(420px,100%)">' +
        '<button class="modal-x" data-x aria-label="' + t('serversCloseButton') + '">✕</button>' +
        '<h3>' + t('serversRagDeleteConfirmTitle').replace('{name}', (name || t('serversDocumentFallback'))) + '</h3><p class="msub">' + t('serversRagDeleteConfirmDesc') + '</p>' +
        '<div class="modal-foot"><button class="btn btn--md btn--secondary" data-x>' + t('serversDetailCancelButton') + '</button>' +
        '<button class="btn btn--md btn--danger" id="rsdYes">' + t('serversRagDeleteConfirm') + '</button></div></div>';
      document.body.appendChild(lay);
      lay.querySelectorAll('[data-x]').forEach(b => b.onclick = () => lay.remove());
      lay.querySelector('#rsdYes').onclick = async () => {
        try {
          const r = await api.deleteKnowledge(d.guildId, id);
          if (r && r.ok === false) { lay.remove(); toast(r.message || r.error || t('serversRagDeleteFailed'), { type: 'info' }); return; }
          if (r && r.docs) m._rag = r.docs; else m._rag = (m._rag || []).filter(x => String(x.id) !== String(id));
          lay.remove(); toast(t('serversRagDeleteSuccess').replace('{name}', (name || t('serversManageTabRAG'))), { type: 'ok' }); done();
        } catch (_e) { lay.remove(); toast(t('serversPresetDeleteFailed'), { type: 'error' }); }
      };
    }

    function openPromptModal(d, m, done) {
      const lay = document.createElement('div');
      lay.className = 'modal-layer';
      lay.innerHTML = '<div class="modal" style="width:min(480px,100%)">' +
        '<button class="modal-x" data-x aria-label="' + t('serversCloseButton') + '">✕</button>' +
        '<h3>' + t('serversProfileAddTitle') + '</h3><p class="msub">' + t('serversProfileAddDescription') + '</p>' +
        '<div class="pform"><div class="pfield"><label>' + t('serversProfileAddNameLabel') + '</label><input class="cx-input" id="npName" placeholder="' + t('serversProfileAddNamePlaceholder') + '"></div>' +
        '<div class="pfield"><label>' + t('serversProfileAddContentLabel') + '</label><textarea class="cx-input pta" id="npBody" placeholder="' + t('serversProfileAddContentPlaceholder') + '"></textarea></div></div>' +
        '<div class="modal-foot"><button class="btn btn--md btn--secondary" data-x>' + t('serversDetailCancelButton') + '</button>' +
        '<button class="btn btn--md btn--primary" id="npSave">' + t('serversProfileAddButton') + '</button></div></div>';
      document.body.appendChild(lay);
      lay.querySelectorAll('[data-x]').forEach(b => b.onclick = () => lay.remove());
      lay.querySelector('#npSave').onclick = async () => {
        const name = (lay.querySelector('#npName').value || '').trim();
        const body = (lay.querySelector('#npBody').value || '').trim();
        if (!name || !body) { toast(t('serversProfileAddFailed'), { type: 'info' }); return; }
        const r = await api.addPromptSet(d.guildId, name, body);
        if (r && r.ok === false) { toast(r.message || t('serversAddFailedToast'), { type: 'info' }); return; }
        if (r && r.sets) m.prompts = r.sets;
        lay.remove(); toast(t('serversProfileAddSuccess'), { type: 'ok', sub: name }); done();
      };
    }

    // 메인은 이미 인증된 상태 → '서버 추가'는 로그인 없이 후보 선택 + 토큰(연결 stage 가 origin=main 처리)
    document.getElementById('srvAddBtn').addEventListener('click', () => { if (window.enterServerAdd) window.enterServerAdd(); });

    let _firstLoad = true;
    async function reload() {
      // 최초 로드만 스켈레톤 — '비었음'과 '불러오는 중'을 구분(폴링 갱신 시엔 깜빡임 방지로 생략).
      if (_firstLoad) { listEl.innerHTML = '<div class="skel-row"></div><div class="skel-row"></div><div class="skel-row"></div>'; _firstLoad = false; }
      render(await api.getServers());
    }
    // 언어가 바뀌면 현재 보이는 서브뷰를 그 자리서 다시 그린다(목록=재조회, 상세/관리=캐시 데이터로 재렌더). render 밖 1회 등록.
    onLangChange(() => {
      if (!manageEl.hidden && _lastManageD && _lastManageM) renderManage(_lastManageD, _lastManageM);
      else if (!detailEl.hidden && _lastDetailD) renderDetail(_lastDetailD);
      else if (!wrapEl.hidden) reload();
    });
    // PROTO·테스트 훅: 서버 상세 직접 점프 / 관리 진입 / 목록 복귀
    window.openServerDetail = (g) => openDetail(g);
    window.openServerManage = async (g, tab) => { const d = await api.getServerDetail(g); if (d) openManage(d, tab); };
    window.backToServerList = backToList;

    reload();
    // 실 앱: 목록을 주기적으로 자동 갱신(연결/제공 모델 수 변화가 한 템포 늦지 않게). 상세·관리 화면 중엔 건너뜀.
    if (window.__SESSION_KEY) {
      const serversView = document.querySelector('.view[data-view="servers"]');
      setInterval(() => { if (serversView.classList.contains('active') && !wrapEl.hidden) reload(); }, 3000);
    }

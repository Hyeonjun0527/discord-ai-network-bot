// NEXA 데스크톱 — screen-local.js (index.html 에서 분리, SoC/SRP). 동작 보존 verbatim.
    import { api } from './adapter.js';
    import { toast } from './toast.js';
    import { promptModal } from './modal.js';
    import { t, onLangChange } from './i18n.js';

    const view = document.querySelector('.view[data-view="local"]');
    const runCard = document.getElementById('localRunCard');
    const rtWrap = document.getElementById('localRuntimes');
    let _comfy = null, _comfyProg = null, _comfyModels = null, _health = null; // 런타임 health·ComfyUI 상태·진행률·체크포인트 목록
    const toggleBtn = document.getElementById('localToggleBtn');
    let _st = null, _models = null;
    const DOTS = '<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/></svg>';
    const closeMenus = () => rtWrap.querySelectorAll('.rt-more .menu').forEach(m => { m.hidden = true; const b = m.previousElementSibling; if (b) b.classList.remove('open'); });
    // 런타임 ⋯ 메뉴 — 홈과 동일 액션을 로컬 실행에도(여기가 더 전문 관리 화면). 현재는 Ollama 만 이 메뉴를 쓴다
    // (ComfyUI 는 자체 data-comfy 액션 버튼으로 설치/시작/정지/웹UI·체크포인트를 관리).
    const rtMenu = () => '<div class="rt-more">' +
      '<button class="icon-btn rt-menu-btn" aria-label="' + t('localRuntimeCheckMenuBtn') + '" aria-haspopup="true">' + DOTS + '</button>' +
      '<div class="menu" hidden><button data-rtact="check">' + t('localRuntimeCheckAction') + '</button></div></div>';

    async function runtimeAction(act, name) {
      // 연결 점검 — 실제 status 로(런타임이 죽어 있으면 정직하게 '점검 필요'). 현재 호출자는 Ollama 뿐.
      toast(t('localRuntimeCheckingToast').replace('{name}', name), { type: 'run', sticky: true, id: 'lchk-' + name });
      try {
        const s = await api.getStatus();
        const n = (s.models && s.models.length) || 0;
        const ok = n > 0;
        const sub = ok ? t('localRuntimeModelProvidingStatus').replace('{n}', n) : t('localRuntimeNoModelsStatus');
        toast(name + (ok ? t('localRuntimeCheckOkStatus') : t('localRuntimeCheckFailedStatus')), { type: ok ? 'ok' : 'error', sub, replace: 'lchk-' + name });
      } catch (_e) { toast(t('localRuntimeCheckFailureToast').replace('{name}', name), { type: 'error', replace: 'lchk-' + name }); }
    }

    function render() {
      const s = _st || {};
      const running = !!s.running;
      const connTx = running ? (s.connected ? t('localRunningConnectionConnected') : t('localRunningConnectionConnecting')) : t('localRuntimeStopped');
      const bg = s.background ? t('localBackgroundResidenceOn') : t('localBackgroundResidenceOff'); // 설정값(홈 핀과 동일 출처), 런타임 아님
      const needReconnect = running && !s.connected; // 중앙 서버 연결 끊김/실패 → 재연결 제공
      runCard.className = 'run-card' + (running ? ' on' : '');
      runCard.innerHTML = '<span class="rc-dot"></span><div class="rc-main">' +
        '<div class="rc-title">' + (running ? t('localRunCardRunning') : t('localRuntimeStopped')) + ' — ' + connTx + '</div>' +
        '<div class="rc-meta">' + t('localRunCardMetaProcessed').replace('{n}', s.processed || 0) + ' · ' + bg + '</div>' +
        '<div class="rc-relay">' + (s.relayUrl || '') + '</div></div>' +
        (needReconnect ? '<button class="btn btn--sm btn--primary" id="localReconnect">' + t('localReconnectBtn') + '</button>' : '');
      toggleBtn.textContent = running ? t('localToggleStop') : t('localToggleStart');
      toggleBtn.className = 'btn btn--md ' + (running ? 'btn--secondary' : 'btn--primary');

      const oh = (_health && _health.ollama) || {};
      const advertisedModels = Array.isArray(oh.advertisedModels) ? oh.advertisedModels : (s.models || []);
      const installedTextModels = Array.isArray(oh.installedModels) ? oh.installedModels : ((_models && _models.models) ? _models.models.map((m) => m.name) : []);
      const ollamaReady = (oh.ready !== undefined) ? !!oh.ready : (_models ? (_models.ollamaReady ?? running) : running);
      const modelCount = running ? advertisedModels.length : installedTextModels.length;

      const ollamaCard = '<div class="rt-row' + (ollamaReady ? ' ready' : '') + '">' +
        '<span class="rt-dot"></span><div class="rt-body"><div class="rt-name">Ollama <span style="font-weight:500;color:var(--subtle)">' + t('localOllamaTextType') + '</span></div>' +
        '<div class="rt-state">' + (ollamaReady ? (t('localOllamaReadyStatus').replace('{n}', modelCount)) : t('localOllamaNotReadyStatus')) + '</div></div><div class="rt-actions">' +
        (ollamaReady ? '<button class="btn btn--sm btn--secondary" data-go="models">' + t('localOllamaModelsBtn') + '</button>' + rtMenu()
          : '<button class="btn btn--sm btn--primary" data-install="ollama">' + t('localOllamaInstallBtn') + '</button>') + '</div></div>';

      // (이미지 엔진은 ComfyUI 전용 — 레거시 SD.Next 는 완전히 제거됨. 카드는 아래 comfyCard 하나.)
      // (클라우드 텍스트 모델은 관리자 키로 무상 제공되므로 유저 PC 의 Gemini 키 입력 카드는 제거됨 — 키 입력 UI 불필요.)
      // ComfyUI = 이미지 엔진 — 앱이 직접 설치/시작/정지/웹UI 오픈. 유저별 로컬 인스턴스(SD.Next 제거됨).
      const c = _comfy || {}, cprog = _comfyProg || {};
      const cbusy = !!c.busy, cinst = !!c.installed, crun = !!c.running;
      let comfyState, comfyAction;
      if (cbusy) {
        comfyState = (cprog.message || t('localComfyInstallingStatus')) + (cprog.percent ? t('localComfyProgressPercent').replace('{percent}', cprog.percent) : '');
        comfyAction = '<span class="rt-recv">' + t('localComfyInstallingStatus') + '</span>';
      } else if (crun) {
        // 실행 중이면(앱 관리든, 유저가 직접 띄운 외부 인스턴스든 health 로 감지) 우선 표시.
        comfyState = (cinst ? t('localComfyRunningStatus') : t('localComfyRunningDetectedStatus'));
        comfyAction = '<button class="btn btn--sm btn--secondary" data-comfy="open">' + t('localComfyOpenWebUIBtn') + '</button>' +
          (cinst ? '<button class="btn btn--sm btn--secondary" data-comfy="stop">' + t('localComfyStopBtn') + '</button>' : '');
      } else if (!cinst) {
        comfyState = t('localComfyNotInstalledStatus');
        comfyAction = '<button class="btn btn--sm btn--primary" data-comfy="install">' + t('localComfyInstallBtn') + '</button>';
      } else {
        comfyState = t('localComfyInstalledOffStatus');
        comfyAction = '<button class="btn btn--sm btn--primary" data-comfy="start">' + t('localComfyStartBtn') + '</button>';
      }
      // 실행 중이면 체크포인트 선택기(폴더 스캔 = 아무 .safetensors) + '모델 폴더 열기'(.safetensors 넣기).
      const cm = _comfyModels || {};
      const comfyAddBtns = '<button class="rt-model-add" id="comfyUrlBtn" title="' + t('localComfyModelAddURLTitle') + '">+ URL</button>' +
        '<button class="rt-model-add" id="comfyFolderBtn" title="' + t('localComfyModelAddFolderTitle') + '">' + t('localComfyModelAddFolderBtn') + '</button>';
      const comfyModelSel = (crun && cm.models && cm.models.length) ?
        '<div class="rt-model"><label>' + t('localComfyModelLabel') + '</label><select id="comfyModelSelect" aria-label="' + t('localComfyModelSelectAriaLabel') + '">' +
          cm.models.map((m) => '<option value="' + m + '"' + (m === cm.active ? ' selected' : '') + '>' + m + '</option>').join('') +
        '</select>' + comfyAddBtns + '</div>'
        : (crun ? '<div class="rt-model"><span class="dim" style="font-size:12px">' + t('localComfyNoCheckpointStatus') + '</span>' + comfyAddBtns +
            '<div class="rt-model-hint">💡 ' + t('localComfyNoCheckpointHint') + '</div></div>' : '');
      const comfyCard = '<div class="rt-row rt-row--rec' + (crun ? ' ready' : '') + '">' +
        '<span class="rt-dot"></span><div class="rt-body"><div class="rt-name">ComfyUI <span style="font-weight:500;color:var(--c-violet)">' + t('localComfyUIImageType') + '</span></div>' +
        '<div class="rt-state">' + comfyState + '</div>' + comfyModelSel + '</div><div class="rt-actions">' + comfyAction + '</div></div>';
      // 폴링 재렌더(load 2.5~3s 주기)가 사용자가 펼친/접은 고급 패널 상태를 덮어쓰면 '열면 자동으로 닫힘'
      // 버그가 난다(#233). 직전 DOM 의 open 을 읽어 사용자 의도를 보존하고, 첫 렌더(이전 없음)에서만
      // 기본값(가이드=접힘, 외부=설정값 있으면 펼침)을 쓴다.
      const hfOn = !!s.hfConfigured;
      const prevGuide = rtWrap.querySelector('.rt-adv--guide');
      const prevExternal = rtWrap.querySelector('.rt-adv:not(.rt-adv--guide)');
      const guideOpen = prevGuide ? prevGuide.open : false;
      const externalOpen = prevExternal ? prevExternal.open : !!(s.comfyUrl || hfOn);
      const comfyNsfwGuide = '<details class="rt-adv rt-adv--guide"' + (guideOpen ? ' open' : '') + '>' +
        '<summary>' + t('localComfyNsfwGuideSummary') + '</summary>' +
        '<div class="rt-adv-body"><div class="rt-adv-desc">' + t('localComfyNsfwGuideBody') + '</div></div>' +
        '</details>';
      // 고급 · 직접 띄운 외부 ComfyUI 인스턴스에 연결(앱 관리 ComfyUI 대신). 비우면 앱 관리 ComfyUI 사용.
      const comfyExternal = '<details class="rt-adv"' + (externalOpen ? ' open' : '') + '>' +
        '<summary>' + t('localComfyAdvancedSummary') + '</summary>' +
        '<div class="rt-adv-body"><div class="rt-adv-desc">' + t('localComfyAdvancedDescription') + '</div>' +
        '<div class="rt-cloud"><input id="localComfy" type="text" value="' + (s.comfyUrl || '') + '" placeholder="' + t('localComfyExternalURLPlaceholder') + '" class="set-input"><button class="btn btn--sm btn--secondary" id="localComfySave">' + t('localComfyExternalConnectBtn') + '</button></div>' +
        '<div class="rt-cloud" style="margin-top:8px"><input id="localHfToken" type="password" placeholder="' + (hfOn ? t('localHFTokenPlaceholderSet') : t('localHFTokenPlaceholderNotSet')) + '" class="set-input"><button class="btn btn--sm btn--secondary" id="localHfSave">' + t('localHFTokenSaveBtn') + '</button></div>' +
        '</div></details>';
      rtWrap.innerHTML = ollamaCard + comfyCard + comfyNsfwGuide + comfyExternal;

      // (이미지 모델 전환은 ComfyUI 카드의 #comfyModelSelect 가 담당 — 아래 comfy 핸들러에서 처리. 레거시 SD 핫스왑 제거.)
      rtWrap.querySelectorAll('[data-go="models"]').forEach((b) => { b.onclick = () => { const n = document.querySelector('.nav-item[data-view="models"]'); if (n) n.click(); }; });
      rtWrap.querySelectorAll('[data-install]').forEach((b) => { b.onclick = () => { if (window.openInstall) window.openInstall(b.dataset.install); }; });
      // ⋯ 메뉴 토글 + 액션
      rtWrap.querySelectorAll('.rt-menu-btn').forEach((btn) => btn.onclick = (e) => {
        e.stopPropagation(); const menu = btn.nextElementSibling; const willOpen = menu.hidden; closeMenus(); menu.hidden = !willOpen; btn.classList.toggle('open', willOpen);
      });
      rtWrap.querySelectorAll('[data-rtact]').forEach((b) => b.onclick = async (e) => {
        e.stopPropagation(); closeMenus();
        const row = b.closest('.rt-row'); const name = row.querySelector('.rt-name').childNodes[0].textContent.trim();
        await runtimeAction(b.dataset.rtact, name);
      });
      // (ComfyUI 시작/정지는 ComfyUI 카드의 data-comfy="start|stop" 버튼이 담당 — 아래 comfy 핸들러. 레거시 SD 시작 제거.)
      // 중앙 서버 재연결(중지→시작)
      const rc = document.getElementById('localReconnect');
      if (rc) rc.onclick = async () => {
        rc.disabled = true;
        toast(t('localReconnectingToast'), { type: 'run', sticky: true, id: 'recon' });
        try { await api.stopAgent(); await api.startAgent(); await load(); toast(t('localReconnectSuccessToast'), { type: 'ok', id: 'recon' }); }
        catch (_e) { toast(t('localReconnectFailureToast'), { type: 'error', id: 'recon' }); }
        finally { rc.disabled = false; }
      };
      // (로컬 탭의 이미지 받기 토글은 홈 카드 스위치로 통합 — 레거시 #localImgSw 제거. setImageReceiving 은 홈에서 호출.)
      // (클라우드 텍스트 모델은 관리자 키로 무상 제공 — 유저 PC Gemini 키 입력/저장 UI 제거됨.)
      // 외부 ComfyUI 주소 저장 — 비우면 앱 관리 ComfyUI. 재연결 후 적용.
      const comfySave = document.getElementById('localComfySave');
      if (comfySave) comfySave.onclick = async () => {
        const url = (document.getElementById('localComfy').value || '').trim();
        comfySave.disabled = true;
        try {
          await api.setCloud({ comfyUrl: url });
          if (_st) _st.comfyUrl = url;
          toast(url ? t('localComfyExternalSetToast') : t('localComfyAppManagedResetToast'), { type: 'info' });
          await load();
        } catch (_e) { toast(t('localComfyExternalSaveFailureToast'), { type: 'error' }); }
        finally { comfySave.disabled = false; }
      };
      // HuggingFace 토큰 저장(gated 모델 다운로드용) — 입력했을 때만 전송.
      const hfSave = document.getElementById('localHfSave');
      if (hfSave) hfSave.onclick = async () => {
        const tok = (document.getElementById('localHfToken').value || '').trim();
        if (!tok) { toast(t('localHFTokenRequiredToast'), { type: 'info' }); return; }
        hfSave.disabled = true;
        try { await api.setCloud({ hfToken: tok }); if (_st) _st.hfConfigured = true; toast(t('localHFTokenSavedToast'), { type: 'ok' }); await load(); }
        catch (_e) { toast(t('localHFTokenSaveFailureToast'), { type: 'error' }); }
        finally { hfSave.disabled = false; }
      };
      // ComfyUI(1급 엔진) 라이프사이클 버튼 — 설치/시작/정지/웹UI 열기.
      rtWrap.querySelectorAll('[data-comfy]').forEach((b) => b.onclick = async () => {
        const act = b.dataset.comfy;
        if (act === 'install') {
          b.disabled = true;
          toast(t('localComfyInstallStartedToast'), { type: 'run', sticky: true, id: 'comfy' });
          try { const r = await api.installComfy(); if (r && r.ok === false) { toast(r.error || t('localComfyInstallFailureToast'), { type: 'error', id: 'comfy' }); return; } toast(t('localComfyInstallingProgressToast'), { type: 'info', id: 'comfy' }); await load(); }
          catch (_e) { toast(t('localComfyInstallNetworkFailureToast'), { type: 'error', id: 'comfy' }); }
          finally { b.disabled = false; }
        } else if (act === 'start') {
          b.disabled = true; toast(t('localComfyStartingToast'), { type: 'run', sticky: true, id: 'comfy' });
          try { const r = await api.comfyStart(); if (r && r.ok && _st) { _st.enableImage = true; _st.imageReady = !!r.imageReady; } toast(r && r.ok ? t('localComfyStartedToast') : ((r && r.error) || t('localComfyStartFailureToast')), { type: r && r.ok ? 'ok' : 'error', id: 'comfy' }); await load(); }
          catch (_e) { toast(t('localComfyStartNetworkFailureToast'), { type: 'error', id: 'comfy' }); } finally { b.disabled = false; }
        } else if (act === 'stop') {
          b.disabled = true;
          try { await api.comfyStop(); toast(t('localComfyStoppedToast'), { type: 'info', id: 'comfy' }); await load(); }
          catch (_e) { toast(t('localComfyStopNetworkFailureToast'), { type: 'error', id: 'comfy' }); } finally { b.disabled = false; }
        } else if (act === 'open') {
          try { const r = await api.comfyOpen(); if (r && r.ok === false) toast(r.error || t('localComfyWebUIOpenFailureToast'), { type: 'error' }); else toast(t('localComfyWebUIOpenedToast'), { type: 'ok' }); }
          catch (_e) { toast(t('localComfyWebUIOpenNetworkFailureToast'), { type: 'error' }); }
        }
      });
      // ComfyUI 체크포인트 전환(폴더 스캔된 모델 중 선택) — 즉시 적용.
      const comfySel = document.getElementById('comfyModelSelect');
      if (comfySel) comfySel.onchange = async () => {
        const model = comfySel.value, prev = (_comfyModels && _comfyModels.active);
        comfySel.disabled = true;
        try { const r = await api.comfySelectModel(model) || {}; if (r.ok === false) { toast(r.error || t('localComfyModelChangeFailureToast'), { type: 'error' }); comfySel.value = prev || model; } else { if (_comfyModels) _comfyModels.active = model; toast(t('localComfyModelChangedToast'), { type: 'ok' }); } }
        catch (_e) { toast(t('localComfyModelChangeNetworkFailureToast'), { type: 'error' }); comfySel.value = prev || model; }
        finally { comfySel.disabled = false; }
      };
      // 모델 폴더 열기 — 여기에 .safetensors 를 넣으면 ComfyUI 가 자동 인식(ComfyUI 식 '아무 모델이나').
      const comfyFolder = document.getElementById('comfyFolderBtn');
      if (comfyFolder) comfyFolder.onclick = async () => {
        try { const r = await api.openFolder('comfyModels'); toast(r && r.ok ? t('localComfyFolderOpenedToast') : (r && r.error) || t('localComfyFolderOpenFailureToast'), { type: r && r.ok ? 'ok' : 'error' }); }
        catch (_e) { toast(t('localComfyFolderOpenNetworkFailureToast'), { type: 'error' }); }
      };
      // URL 로 임의 모델 추가 — HuggingFace .safetensors 직접 링크(gated 는 설정 HF 토큰).
      const comfyUrl = document.getElementById('comfyUrlBtn');
      if (comfyUrl) comfyUrl.onclick = async () => {
        // OS 기본 prompt 대신 앱 모달(pywebview 이질감 해소·디자인 일관성).
        const url = (await promptModal({ title: t('localComfyModelAddURLTitle'), desc: t('localComfyModelSelectPrompt'), placeholder: 'https://huggingface.co/…/model.safetensors' }) || '').trim();
        if (!url) return;
        toast(t('localComfyModelDownloadingToast'), { type: 'run', sticky: true, id: 'cmdl' });
        try { const r = await api.installComfyModel(url) || {}; if (r.ok === false) { toast(r.error || t('localComfyModelAddFailureToast'), { type: 'error', id: 'cmdl' }); return; } toast(t('localComfyModelAddedToast'), { type: 'ok', id: 'cmdl' }); await load(); }
        catch (_e) { toast(t('localComfyModelAddNetworkFailureToast'), { type: 'error', id: 'cmdl' }); }
      };
    }

    async function load() {
      [_st, _models, _health] = await Promise.all([api.getStatus(), api.getModels(), api.getRuntimeHealth().catch(() => null)]);
      // (레거시 SD 모델 목록 로딩 제거 — 이미지 체크포인트는 아래 ComfyUI 폴더 스캔(comfyModels)이 단일 소스.)
      // ComfyUI(1급 엔진) 상태 — 설치/실행/바쁨. 설치 중이면 진행률도(3초 폴링으로 자동 갱신). 실패는 비치명적.
      const sd = (_health && _health.stableDiffusion) || null;
      if (sd) {
        _comfy = {
          installed: !!sd.installed,
          running: !!sd.ready,
          busy: !!sd.busy,
          enabled: !!sd.enabled,
          advertised: !!sd.advertised,
          needsReconnect: !!sd.needsReconnect,
        };
      } else {
        try { _comfy = await api.comfyStatus(); } catch (_e) { _comfy = null; }
      }
      if (_comfy && _comfy.busy) { try { _comfyProg = await api.comfySetupProgress(); } catch (_e) { _comfyProg = null; } }
      else _comfyProg = null;
      // ComfyUI 실행 중이면 체크포인트 목록(폴더 스캔)도 — 모델 선택기용. 실패는 비치명적.
      if (_comfy && _comfy.running) {
        if (sd && Array.isArray(sd.installedModels)) _comfyModels = { models: sd.installedModels, active: sd.selectedModel || sd.installedModels[0] || null };
        else { try { _comfyModels = await api.comfyModels(); } catch (_e) { _comfyModels = null; } }
      }
      else _comfyModels = null;
      render();
    }

    toggleBtn.addEventListener('click', async () => {
      toggleBtn.disabled = true;
      const wasRunning = _st && _st.running;
      try {
        if (wasRunning) { await api.stopAgent(); toast(t('localAgentStoppedToast'), { type: 'info' }); }
        else { await api.startAgent(); toast(t('localAgentStartedToast'), { type: 'ok' }); }
        await load();
      } finally { toggleBtn.disabled = false; }
    });

    document.addEventListener('click', closeMenus); // 바깥 클릭 시 ⋯ 메뉴 닫기
    const isActive = () => view.classList.contains('active');
    document.querySelector('.nav-item[data-view="local"]').addEventListener('click', load);
    // 언어가 바뀌면 로컬 실행 화면을 다시 그린다(JS 렌더 문구 갱신). render 밖에 한 번만 등록(리스너 누적 방지).
    onLangChange(() => { if (_st) render(); });
    if (isActive()) load();
    // 실 앱: 로컬 실행 화면 활성 중 주기 갱신(시작/연결/모델 변화가 한 템포 늦지 않게).
    if (window.__SESSION_KEY) setInterval(() => { if (isActive()) load(); }, 3000);

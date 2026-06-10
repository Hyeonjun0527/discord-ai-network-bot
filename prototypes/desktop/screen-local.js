// NEXA 데스크톱 — screen-local.js (index.html 에서 분리, SoC/SRP). 동작 보존 verbatim.
    import { api } from './adapter.js';
    import { toast } from './toast.js';

    const view = document.querySelector('.view[data-view="local"]');
    const runCard = document.getElementById('localRunCard');
    const rtWrap = document.getElementById('localRuntimes');
    let _comfy = null, _comfyProg = null, _comfyModels = null; // ComfyUI 상태·진행률·체크포인트 목록
    const toggleBtn = document.getElementById('localToggleBtn');
    let _st = null, _models = null;
    const DOTS = '<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/></svg>';
    const closeMenus = () => rtWrap.querySelectorAll('.rt-more .menu').forEach(m => { m.hidden = true; const b = m.previousElementSibling; if (b) b.classList.remove('open'); });
    // 런타임 ⋯ 메뉴 — 홈과 동일 액션을 로컬 실행에도(여기가 더 전문 관리 화면). 현재는 Ollama 만 이 메뉴를 쓴다
    // (ComfyUI 는 자체 data-comfy 액션 버튼으로 설치/시작/정지/웹UI·체크포인트를 관리).
    const rtMenu = () => '<div class="rt-more">' +
      '<button class="icon-btn rt-menu-btn" aria-label="더보기" aria-haspopup="true">' + DOTS + '</button>' +
      '<div class="menu" hidden><button data-rtact="check">연결 점검</button></div></div>';

    async function runtimeAction(act, name) {
      // 연결 점검 — 실제 status 로(런타임이 죽어 있으면 정직하게 '점검 필요'). 현재 호출자는 Ollama 뿐.
      toast(name + ' 연결 점검 중…', { type: 'run', sticky: true, id: 'lchk-' + name });
      try {
        const s = await api.getStatus();
        const n = (s.models && s.models.length) || 0;
        const ok = n > 0;
        const sub = ok ? (n + '개 모델 제공 중') : '모델 없음 또는 미연결';
        toast(name + (ok ? ' 정상 응답' : ' 점검 필요'), { type: ok ? 'ok' : 'error', sub, replace: 'lchk-' + name });
      } catch (_e) { toast(name + ' 점검 실패', { type: 'error', replace: 'lchk-' + name }); }
    }

    function render() {
      const s = _st || {};
      const running = !!s.running;
      const connTx = running ? (s.connected ? '중앙 서버 연결됨' : '연결 중…') : '중지됨';
      const bg = s.background ? '백그라운드 상주 켜짐' : '백그라운드 상주 꺼짐'; // 설정값(홈 핀과 동일 출처), 런타임 아님
      const needReconnect = running && !s.connected; // 중앙 서버 연결 끊김/실패 → 재연결 제공
      runCard.className = 'run-card' + (running ? ' on' : '');
      runCard.innerHTML = '<span class="rc-dot"></span><div class="rc-main">' +
        '<div class="rc-title">' + (running ? '실행 중' : '중지됨') + ' — ' + connTx + '</div>' +
        '<div class="rc-meta">처리 ' + (s.processed || 0) + '건(이 PC) · ' + bg + '</div>' +
        '<div class="rc-relay">' + (s.relayUrl || '') + '</div></div>' +
        (needReconnect ? '<button class="btn btn--sm btn--primary" id="localReconnect">재연결</button>' : '');
      toggleBtn.textContent = running ? '중지' : '시작';
      toggleBtn.className = 'btn btn--md ' + (running ? 'btn--secondary' : 'btn--primary');

      const ollamaReady = _models ? (_models.ollamaReady ?? running) : running;
      const modelCount = running ? (s.models ? s.models.length : 0) : (_models && _models.models ? _models.models.length : 0);

      const ollamaCard = '<div class="rt-row' + (ollamaReady ? ' ready' : '') + '">' +
        '<span class="rt-dot"></span><div class="rt-body"><div class="rt-name">Ollama <span style="font-weight:500;color:var(--subtle)">텍스트</span></div>' +
        '<div class="rt-state">' + (ollamaReady ? ('준비됨 · 모델 ' + modelCount + '개 제공') : '미설치 또는 중지됨') + '</div></div><div class="rt-actions">' +
        (ollamaReady ? '<button class="btn btn--sm btn--secondary" data-go="models">모델 관리</button>' + rtMenu()
          : '<button class="btn btn--sm btn--primary" data-install="ollama">설치</button>') + '</div></div>';

      // (이미지 엔진은 ComfyUI 전용 — 레거시 SD.Next 는 완전히 제거됨. 카드는 아래 comfyCard 하나.)

      // 클라우드 연결(Gemini) — '엔진' 관심사라 로컬 실행이 소유한다. 키는 이 PC 에만 저장.
      //   연결되면 모델 탭에 '클라우드 텍스트' 모델로 등장 → 다른 모델처럼 서버별로 켜고 끈다('서버 전체 무료' 프레이밍 폐기).
      const gemOn = !!s.geminiConfigured;
      const cloudCard = '<div class="rt-row' + (gemOn ? ' ready' : '') + '">' +
        '<span class="rt-dot"></span><div class="rt-body"><div class="rt-name">클라우드 연결 <span style="font-weight:500;color:var(--subtle)">Gemini · 텍스트</span></div>' +
        '<div class="rt-state">' + (gemOn
          ? '연결됨 · 이 PC 의 키로 클라우드 모델을 제공해요(모델 탭에서 서버별로 켜고 꺼요)'
          : '이 PC 의 API 키로 클라우드 AI를 모델로 추가해요 — 다른 모델처럼 서버별로 제공돼요 (aistudio.google.com/apikey)') + '</div>' +
        '<div class="rt-cloud"><input id="localGemini" type="password" placeholder="' + (gemOn ? '••••• 연결됨(바꾸려면 입력)' : 'AIza… 키 붙여넣기') + '" class="set-input"><button class="btn btn--sm btn--primary" id="localGeminiSave">' + (gemOn ? '변경' : '연결') + '</button></div></div></div>';
      // ComfyUI = 이미지 엔진 — 앱이 직접 설치/시작/정지/웹UI 오픈. 유저별 로컬 인스턴스(SD.Next 제거됨).
      const c = _comfy || {}, cprog = _comfyProg || {};
      const cbusy = !!c.busy, cinst = !!c.installed, crun = !!c.running;
      let comfyState, comfyAction;
      if (cbusy) {
        comfyState = (cprog.message || '설치 중…') + (cprog.percent ? ' · ' + cprog.percent + '%' : '');
        comfyAction = '<span class="rt-recv">설치 중…</span>';
      } else if (crun) {
        // 실행 중이면(앱 관리든, 유저가 직접 띄운 외부 인스턴스든 health 로 감지) 우선 표시.
        comfyState = (cinst ? '실행 중 · 이미지 생성 준비됨' : '실행 중(직접 띄운 ComfyUI 감지됨) · 이미지 생성 준비됨');
        comfyAction = '<button class="btn btn--sm btn--secondary" data-comfy="open">웹UI 열기</button>' +
          (cinst ? '<button class="btn btn--sm btn--secondary" data-comfy="stop">정지</button>' : '');
      } else if (!cinst) {
        comfyState = '미설치 — 활발히 유지보수되는 최신 엔진(Python 3.13). 설치를 권장해요.';
        comfyAction = '<button class="btn btn--sm btn--primary" data-comfy="install">설치</button>';
      } else {
        comfyState = '설치됨 · 꺼짐';
        comfyAction = '<button class="btn btn--sm btn--primary" data-comfy="start">시작</button>';
      }
      // 실행 중이면 체크포인트 선택기(폴더 스캔 = 아무 .safetensors) + '모델 폴더 열기'(.safetensors 넣기).
      const cm = _comfyModels || {};
      const comfyAddBtns = '<button class="rt-model-add" id="comfyUrlBtn" title="URL 로 모델 추가 — .safetensors 직접 링크(gated 는 설정의 HF 토큰)">+ URL</button>' +
        '<button class="rt-model-add" id="comfyFolderBtn" title="모델 폴더 열기 — 여기에 .safetensors 를 넣으면 자동 인식돼요">📂 폴더</button>';
      const comfyModelSel = (crun && cm.models && cm.models.length) ?
        '<div class="rt-model"><label>모델</label><select id="comfyModelSelect" aria-label="ComfyUI 이미지 모델 선택">' +
          cm.models.map((m) => '<option value="' + m + '"' + (m === cm.active ? ' selected' : '') + '>' + m + '</option>').join('') +
        '</select>' + comfyAddBtns + '</div>'
        : (crun ? '<div class="rt-model"><span class="dim" style="font-size:12px">체크포인트 없음 — </span>' + comfyAddBtns + '</div>' : '');
      const comfyCard = '<div class="rt-row rt-row--rec' + (crun ? ' ready' : '') + '">' +
        '<span class="rt-dot"></span><div class="rt-body"><div class="rt-name">ComfyUI <span style="font-weight:500;color:var(--c-violet)">이미지 · 권장</span></div>' +
        '<div class="rt-state">' + comfyState + '</div>' + comfyModelSel + '</div><div class="rt-actions">' + comfyAction + '</div></div>';
      // 고급 · 직접 띄운 외부 ComfyUI 인스턴스에 연결(앱 관리 ComfyUI 대신). 비우면 앱 관리 ComfyUI 사용.
      const hfOn = !!s.hfConfigured;
      const comfyExternal = '<details class="rt-adv"' + ((s.comfyUrl || hfOn) ? ' open' : '') + '>' +
        '<summary>고급 · 외부 ComfyUI · HuggingFace 토큰</summary>' +
        '<div class="rt-adv-body"><div class="rt-adv-desc">직접 띄운 ComfyUI 가 있으면 주소를 입력하세요(비우면 앱 관리 ComfyUI). gated/비공개 모델을 받으려면 HF 토큰을 넣으세요.</div>' +
        '<div class="rt-cloud"><input id="localComfy" type="text" value="' + (s.comfyUrl || '') + '" placeholder="외부 ComfyUI 주소(http://127.0.0.1:8188)" class="set-input"><button class="btn btn--sm btn--secondary" id="localComfySave">외부 연결</button></div>' +
        '<div class="rt-cloud" style="margin-top:8px"><input id="localHfToken" type="password" placeholder="' + (hfOn ? '••••• 설정됨(바꾸려면 입력)' : 'HuggingFace 토큰(gated 모델용, hf_…)') + '" class="set-input"><button class="btn btn--sm btn--secondary" id="localHfSave">토큰 저장</button></div>' +
        '</div></details>';
      rtWrap.innerHTML = ollamaCard + comfyCard + cloudCard + comfyExternal;

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
        toast('중앙 서버 재연결 중…', { type: 'run', sticky: true, id: 'recon' });
        try { await api.stopAgent(); await api.startAgent(); await load(); toast('재연결했어요', { type: 'ok', id: 'recon' }); }
        catch (_e) { toast('재연결 실패 — 네트워크를 확인하세요', { type: 'error', id: 'recon' }); }
        finally { rc.disabled = false; }
      };
      // (로컬 탭의 이미지 받기 토글은 홈 카드 스위치로 통합 — 레거시 #localImgSw 제거. setImageReceiving 은 홈에서 호출.)
      // 클라우드 연결(Gemini) 저장 — 입력했을 때만 전송(빈 입력은 무시). 연결되면 모델 탭에 클라우드 모델로 등장.
      const gemSave = document.getElementById('localGeminiSave');
      if (gemSave) gemSave.onclick = async () => {
        const key = (document.getElementById('localGemini').value || '').trim();
        if (!key) { toast('AIza… 키를 입력하세요', { type: 'info' }); return; }
        gemSave.disabled = true;
        try {
          const r = await api.setCloud({ geminiApiKey: key }) || {};
          if (_st) _st.geminiConfigured = true;
          toast(r.geminiValid === false ? '키 저장됨 — 다만 검증 실패(결제/유효성 확인)' : '클라우드 모델을 연결했어요 — 모델 탭에서 서버별로 켜세요', { type: r.geminiValid === false ? 'info' : 'ok' });
          await load();
        } catch (_e) { toast('연결 실패 — 네트워크를 확인하세요', { type: 'error' }); }
        finally { gemSave.disabled = false; }
      };
      // 외부 ComfyUI 주소 저장 — 비우면 앱 관리 ComfyUI. 재연결 후 적용.
      const comfySave = document.getElementById('localComfySave');
      if (comfySave) comfySave.onclick = async () => {
        const url = (document.getElementById('localComfy').value || '').trim();
        comfySave.disabled = true;
        try {
          await api.setCloud({ comfyUrl: url });
          if (_st) _st.comfyUrl = url;
          toast(url ? '외부 ComfyUI 를 이미지 엔진으로 설정했어요 — 재연결 후 적용' : '앱 관리 ComfyUI 로 되돌렸어요 — 재연결 후 적용', { type: 'info' });
          await load();
        } catch (_e) { toast('저장 실패 — 네트워크를 확인하세요', { type: 'error' }); }
        finally { comfySave.disabled = false; }
      };
      // HuggingFace 토큰 저장(gated 모델 다운로드용) — 입력했을 때만 전송.
      const hfSave = document.getElementById('localHfSave');
      if (hfSave) hfSave.onclick = async () => {
        const tok = (document.getElementById('localHfToken').value || '').trim();
        if (!tok) { toast('HuggingFace 토큰을 입력하세요(hf_…)', { type: 'info' }); return; }
        hfSave.disabled = true;
        try { await api.setCloud({ hfToken: tok }); if (_st) _st.hfConfigured = true; toast('HF 토큰을 저장했어요 — 이제 gated 모델도 받을 수 있어요', { type: 'ok' }); await load(); }
        catch (_e) { toast('저장 실패 — 네트워크를 확인하세요', { type: 'error' }); }
        finally { hfSave.disabled = false; }
      };
      // ComfyUI(1급 엔진) 라이프사이클 버튼 — 설치/시작/정지/웹UI 열기.
      rtWrap.querySelectorAll('[data-comfy]').forEach((b) => b.onclick = async () => {
        const act = b.dataset.comfy;
        if (act === 'install') {
          b.disabled = true;
          toast('ComfyUI 설치 시작 — 첫 설치는 수 분 걸려요(자동 진행돼요)', { type: 'run', sticky: true, id: 'comfy' });
          try { const r = await api.installComfy(); if (r && r.ok === false) { toast(r.error || '설치 시작 실패', { type: 'error', id: 'comfy' }); return; } toast('ComfyUI 설치 중… 진행 상황이 카드에 표시돼요', { type: 'info', id: 'comfy' }); await load(); }
          catch (_e) { toast('설치 시작 실패 — 네트워크를 확인하세요', { type: 'error', id: 'comfy' }); }
          finally { b.disabled = false; }
        } else if (act === 'start') {
          b.disabled = true; toast('ComfyUI 시작 중…', { type: 'run', sticky: true, id: 'comfy' });
          try { const r = await api.comfyStart(); toast(r && r.ok ? 'ComfyUI 시작됨' : ((r && r.error) || '시작 실패'), { type: r && r.ok ? 'ok' : 'error', id: 'comfy' }); await load(); }
          catch (_e) { toast('시작 실패 — 네트워크를 확인하세요', { type: 'error', id: 'comfy' }); } finally { b.disabled = false; }
        } else if (act === 'stop') {
          b.disabled = true;
          try { await api.comfyStop(); toast('ComfyUI 를 정지했어요', { type: 'info', id: 'comfy' }); await load(); }
          catch (_e) { toast('정지 실패 — 네트워크를 확인하세요', { type: 'error', id: 'comfy' }); } finally { b.disabled = false; }
        } else if (act === 'open') {
          try { const r = await api.comfyOpen(); if (r && r.ok === false) toast(r.error || '웹UI 를 열 수 없어요', { type: 'error' }); else toast('브라우저에서 ComfyUI 를 열었어요', { type: 'ok' }); }
          catch (_e) { toast('웹UI 열기 실패', { type: 'error' }); }
        }
      });
      // ComfyUI 체크포인트 전환(폴더 스캔된 모델 중 선택) — 즉시 적용.
      const comfySel = document.getElementById('comfyModelSelect');
      if (comfySel) comfySel.onchange = async () => {
        const model = comfySel.value, prev = (_comfyModels && _comfyModels.active);
        comfySel.disabled = true;
        try { const r = await api.comfySelectModel(model) || {}; if (r.ok === false) { toast(r.error || '모델 전환 실패', { type: 'error' }); comfySel.value = prev || model; } else { if (_comfyModels) _comfyModels.active = model; toast('이미지 모델을 전환했어요', { type: 'ok' }); } }
        catch (_e) { toast('모델 전환 실패 — 네트워크를 확인하세요', { type: 'error' }); comfySel.value = prev || model; }
        finally { comfySel.disabled = false; }
      };
      // 모델 폴더 열기 — 여기에 .safetensors 를 넣으면 ComfyUI 가 자동 인식(ComfyUI 식 '아무 모델이나').
      const comfyFolder = document.getElementById('comfyFolderBtn');
      if (comfyFolder) comfyFolder.onclick = async () => {
        try { const r = await api.openFolder('comfyModels'); toast(r && r.ok ? '모델 폴더를 열었어요 — .safetensors 를 넣고 새로고침하면 목록에 떠요' : (r && r.error) || '폴더를 열 수 없어요', { type: r && r.ok ? 'ok' : 'error' }); }
        catch (_e) { toast('폴더 열기 실패', { type: 'error' }); }
      };
      // URL 로 임의 모델 추가 — HuggingFace .safetensors 직접 링크(gated 는 설정 HF 토큰).
      const comfyUrl = document.getElementById('comfyUrlBtn');
      if (comfyUrl) comfyUrl.onclick = async () => {
        const url = (window.prompt('모델 URL(.safetensors 직접 링크) — 예: https://huggingface.co/…/model.safetensors') || '').trim();
        if (!url) return;
        toast('모델 내려받는 중… 용량에 따라 몇 분 걸려요', { type: 'run', sticky: true, id: 'cmdl' });
        try { const r = await api.installComfyModel(url) || {}; if (r.ok === false) { toast(r.error || '모델 추가 실패', { type: 'error', id: 'cmdl' }); return; } toast('모델을 추가했어요 — 목록에서 선택하세요', { type: 'ok', id: 'cmdl' }); await load(); }
        catch (_e) { toast('모델 추가 실패 — 네트워크를 확인하세요', { type: 'error', id: 'cmdl' }); }
      };
    }

    async function load() {
      [_st, _models] = await Promise.all([api.getStatus(), api.getModels()]);
      // (레거시 SD 모델 목록 로딩 제거 — 이미지 체크포인트는 아래 ComfyUI 폴더 스캔(comfyModels)이 단일 소스.)
      // ComfyUI(1급 엔진) 상태 — 설치/실행/바쁨. 설치 중이면 진행률도(3초 폴링으로 자동 갱신). 실패는 비치명적.
      try { _comfy = await api.comfyStatus(); } catch (_e) { _comfy = null; }
      if (_comfy && _comfy.busy) { try { _comfyProg = await api.comfySetupProgress(); } catch (_e) { _comfyProg = null; } }
      else _comfyProg = null;
      // ComfyUI 실행 중이면 체크포인트 목록(폴더 스캔)도 — 모델 선택기용. 실패는 비치명적.
      if (_comfy && _comfy.running) { try { _comfyModels = await api.comfyModels(); } catch (_e) { _comfyModels = null; } }
      else _comfyModels = null;
      render();
    }

    toggleBtn.addEventListener('click', async () => {
      toggleBtn.disabled = true;
      const wasRunning = _st && _st.running;
      try {
        if (wasRunning) { await api.stopAgent(); toast('에이전트를 중지했어요', { type: 'info' }); }
        else { await api.startAgent(); toast('에이전트를 시작했어요', { type: 'ok' }); }
        await load();
      } finally { toggleBtn.disabled = false; }
    });

    document.addEventListener('click', closeMenus); // 바깥 클릭 시 ⋯ 메뉴 닫기
    const isActive = () => view.classList.contains('active');
    document.querySelector('.nav-item[data-view="local"]').addEventListener('click', load);
    if (isActive()) load();
    // 실 앱: 로컬 실행 화면 활성 중 주기 갱신(시작/연결/모델 변화가 한 템포 늦지 않게).
    if (window.__SESSION_KEY) setInterval(() => { if (isActive()) load(); }, 3000);

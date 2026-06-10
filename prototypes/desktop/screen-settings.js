// NEXA 데스크톱 — screen-settings.js (index.html 에서 분리, SoC/SRP). 동작 보존 verbatim.
    import { api } from './adapter.js';
    import { toast } from './toast.js';

    const view = document.querySelector('.view[data-view="settings"]');
    const body = document.getElementById('settingsBody');
    let _s = null, _upd = null;

    const TOGGLES = [
      { key: 'autostart', name: '로그인 시 자동 시작', desc: '이 PC 에 로그인하면 Nexa 가 자동으로 켜져요.' },
      { key: 'background', name: '백그라운드 상주', desc: '창을 닫아도 트레이에서 계속 제공해요.' },
      { key: 'autoConnect', name: '자동 연결', desc: '시작하면 승인된 서버에 자동으로 연결해요.' },
      { key: 'enableImage', name: '이미지 요청 받기', desc: 'ComfyUI 로 이미지 생성 요청을 받아요.' },
      { key: 'comfyBroadcast', name: 'ComfyUI 웹 생성물 자동 전송', desc: 'ComfyUI 웹에서 직접 만든 이미지를 서버 관리자가 지정한 채널(/그림채널)로 자동 전송해요. 로컬 ComfyUI 전용.' },
    ];
    const sw = (key, on) => '<button class="switch ' + (on ? 'on' : '') + '" data-toggle="' + key + '" role="switch" aria-checked="' + !!on + '" aria-label="' + key + '"></button>';
    const row = (name, desc, right) => '<div class="set-row"><div class="sr-body"><div class="sr-name">' + name + '</div>' +
      (desc ? '<div class="sr-desc">' + desc + '</div>' : '') + '</div>' + right + '</div>';

    function render() {
      const s = _s || {}, u = _upd; // u=null 이면 아직 업데이트 확인 전(네트워크 진행 중)
      const exec = TOGGLES.map((t) => row(t.name, t.desc, sw(t.key, s[t.key]))).join('');
      const verLine = !u ? '버전 확인 중…' : (u.outdated ? ('현재 v' + u.current + ' · 최신 v' + u.latest) : ('현재 v' + (u.current || '-') + ' · 최신 상태'));
      const updRight = !u
        ? '<button class="btn btn--sm btn--secondary" id="setUpdateCheck" disabled>확인 중…</button>'
        : (u.outdated
          ? '<button class="btn btn--sm btn--primary" id="setUpdateApply">업데이트</button>'
          : '<button class="btn btn--sm btn--secondary" id="setUpdateCheck">확인</button>');
      const updGroup = row('버전', verLine, updRight) + row('자동 업데이트', '새 버전이 나오면 자동으로 설치해요.', sw('autoUpdate', s.autoUpdate));
      // 중앙 서버·Ollama 주소 — 편집 가능(고급). 다른 포트/호스트의 Ollama 를 쓰는 유저가 앱에서 바꾼다.
      const connGroup =
        row('중앙 서버', '서버 연결 주소(보통 그대로)',
          '<input id="setRelay" type="text" value="' + (s.relayUrl || '') + '" placeholder="wss://discord-ai.yeon.world/agent" class="set-input"><button class="btn btn--sm btn--secondary" id="setRelaySave">저장</button>') +
        row('Ollama 주소', '다른 포트/호스트의 Ollama 를 쓰면 여기서 바꿔요',
          '<input id="setOllama" type="text" value="' + (s.ollamaUrl || '') + '" placeholder="http://localhost:11434" class="set-input"><button class="btn btn--sm btn--secondary" id="setOllamaSave">저장</button>');
      const acct = row(s.hasToken ? '연결됨' : '연결 안 됨',
        s.hasToken ? '서버 연결 토큰을 보유하고 있어요.' : '온보딩에서 Discord 에 연결하세요.',
        s.hasToken ? '<button class="btn btn--sm btn--secondary" id="setLogout">연결 해제</button>' : '');
      // 클라우드 AI(Gemini)·이미지 엔진(ComfyUI)은 '엔진' 관심사 → 로컬 실행 탭이 소유한다(설정엔 두지 않음).
      // 설정은 앱 동작만: 실행 동작·업데이트·연결·계정. AI 백엔드 설정은 여기 없음(IA: 엔진→모델→서버).
      body.innerHTML =
        '<div class="set-group"><div class="sg-title">실행 동작</div>' + exec + '</div>' +
        '<div class="set-group"><div class="sg-title">업데이트</div>' + updGroup + '</div>' +
        '<div class="set-group"><div class="sg-title">연결</div>' + connGroup + '</div>' +
        '<div class="set-group"><div class="sg-title">계정</div>' + acct + '</div>';
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
              if (on && !r.imageReady) toast('이미지 받기 켜짐 — ComfyUI 준비가 필요해요', { type: 'info', sub: '로컬 실행 탭에서 ComfyUI 를 설치/시작하세요' });
              else toast('설정을 저장했어요', { type: 'ok' });
            } catch (_e) {
              b.classList.toggle('on', !on); b.setAttribute('aria-checked', String(!on)); if (_s) _s[key] = !on;
              toast('변경 실패 — 네트워크를 확인하세요', { type: 'error' });
            }
            return;
          }
          try {
            const r = await api.setSetting(key, on) || {};
            if (_s) _s[key] = on;
            if (r.serviceError) { // autostart 등 실제 적용 실패 — 정직하게 알리고 되돌림
              b.classList.toggle('on', !on); b.setAttribute('aria-checked', String(!on)); if (_s) _s[key] = !on;
              toast('적용 실패 — ' + r.serviceError, { type: 'error' });
            } else if (r.needsRestart) { // 즉시 반영 안 되는 항목(이미지 수신 등) — '저장됨' 착시 금지
              toast('저장했어요 — 다시 연결 후 적용돼요', { type: 'info' });
            } else {
              toast('설정을 저장했어요', { type: 'ok' });
            }
          } catch (_e) { // 저장 자체 실패 → 토글 원복
            b.classList.toggle('on', !on); b.setAttribute('aria-checked', String(!on)); if (_s) _s[key] = !on;
            toast('저장 실패 — 네트워크를 확인하세요', { type: 'error' });
          }
        };
      });
      const chk = document.getElementById('setUpdateCheck');
      if (chk) chk.onclick = async () => { _upd = await api.getUpdateInfo(); render(); toast(_upd.outdated ? '새 버전이 있어요' : '최신 버전이에요', { type: 'info' }); };
      const apply = document.getElementById('setUpdateApply');
      if (apply) apply.onclick = async () => {
        apply.disabled = true;
        toast('업데이트 시작 중…', { type: 'run', sticky: true, id: 'upd' });
        try {
          const r = await api.applyUpdate(); // POST /api/update
          if (r && r.ok === false) { toast(r.error || '업데이트를 시작할 수 없어요', { type: 'error', id: 'upd' }); return; }
          toast('업데이트를 시작했어요 — 다운로드 후 자동 적용·재시작돼요', { type: 'ok', id: 'upd' });
        } catch (_e) { toast('업데이트 시작 실패 — 다시 시도하세요', { type: 'error', id: 'upd' }); }
        finally { apply.disabled = false; }
      };
      const logout = document.getElementById('setLogout');
      if (logout) logout.onclick = async () => {
        if (!confirm('연결을 해제하면 모든 서버 제공이 중단돼요. 계속할까요?')) return;
        await api.logout(); toast('연결을 해제했어요', { type: 'info' }); load();
      };
      // 중앙 서버·Ollama 주소 저장(고급) — 다음 연결에 반영(needsRestart).
      const saveConn = (inputId, key, label) => {
        const b = document.getElementById(inputId + 'Save');
        if (!b) return;
        b.onclick = async () => {
          const url = (document.getElementById(inputId).value || '').trim();
          b.disabled = true;
          try { const r = await api.setSetting(key, url) || {}; if (_s) _s[key] = url; toast(r.needsRestart ? label + ' 저장 — 다시 연결하면 적용돼요' : label + ' 를 저장했어요', { type: 'ok' }); }
          catch (_e) { toast('저장 실패 — 네트워크를 확인하세요', { type: 'error' }); }
          finally { b.disabled = false; }
        };
      };
      saveConn('setRelay', 'relayUrl', '중앙 서버 주소');
      saveConn('setOllama', 'ollamaUrl', 'Ollama 주소');
    }

    async function load() {
      _s = await api.getSettings();  // 로컬·빠름 → 즉시 렌더(설정 본문이 네트워크에 안 막히게)
      render();
      // 업데이트 확인은 GitHub 새 버전 조회(네트워크) — 느리거나 rate-limit 이면 본문 전체가 안 뜨던 문제(실증).
      // 본문은 위에서 이미 그렸고, 업데이트 줄만 도착 시 백그라운드로 채운다(_upd 는 캐시돼 재오픈 시 즉시 표시).
      api.getUpdateInfo().then((u) => { _upd = u; if (isActive()) render(); }).catch(() => {});
    }

    const isActive = () => view.classList.contains('active');
    document.querySelector('.nav-item[data-view="settings"]').addEventListener('click', load);
    if (isActive()) load();

// NEXA 데스크톱 — stage-manager.js (index.html 에서 분리, SoC/SRP). 동작 보존 verbatim.
    import { toast } from './toast.js';
    import { api } from './adapter.js';
    import { ProviderState } from './contract.js';
    import { installRuntime, watchSetup } from './install.js';
    import { App, applyStage, canEnterServerAdd } from './state.js';
    import { t, onLangChange } from './i18n.js';
    (function stageManager() {
      const app = document.querySelector('.app');
      const onbLayer = document.getElementById('onboardingLayer');
      const connectLayer = document.getElementById('connectLayer');
      const wiz = document.getElementById('wiz');
      const connectWiz = document.getElementById('connectWiz');

      // 온보딩 선택 상태(webui.py ONB 와 동일 의미). 이미지 엔진(ComfyUI)은 온보딩에서 설치하지 않고
      // 로컬 실행 탭에서 관리한다 — 온보딩 2단계는 Ollama(텍스트)만.
      const ONB = { step: 1, ollama: true, none: false, autostart: true, autoconnect: true, imageRecv: true, background: true };

      const IC = {
        chat: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>',
        image: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="m21 15-5-5L5 21"/></svg>',
        shield: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M12 3 5 6v5c0 4.4 3 7.4 7 8.5 4-1.1 7-4.1 7-8.5V6Z"/></svg>',
        check: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>',
      };
      const feat = (ic, t, d) => '<div class="wiz-feat"><span class="fi">' + ic + '</span><div><b>' + t + '</b><p>' + d + '</p></div></div>';
      const opt = (k, name, tag, desc, size, on, cls = '') => '<button class="wiz-opt ' + (on ? 'sel' : '') + ' ' + cls + '" data-opt="' + k + '">' +
        '<span class="ck">' + IC.check + '</span>' +
        '<span class="ob"><b>' + name + (tag ? '<span class="tag">' + tag + '</span>' : '') + '</b><p>' + desc + '</p></span>' +
        (size ? '<span class="osize">' + size + '</span>' : '') + '</button>';
      const tog = (k, t, d) => '<div class="wiz-tog"><div class="tb"><b>' + t + '</b><p>' + d + '</p></div><button class="switch ' + (ONB[k] ? 'on' : '') + '" data-tog="' + k + '" role="switch" aria-checked="' + ONB[k] + '"></button></div>';
      const sum = (l, v, on) => '<div class="wiz-sum"><span class="si">' + IC.check + '</span><span class="sl">' + l + '</span><span class="sv ' + (on ? '' : 'off') + '">' + v + '</span></div>';
      const btn = (cls, go, label) => '<button class="btn btn--md btn--' + cls + '" data-go="' + go + '">' + label + '</button>';

      function renderWiz() {
        const s = ONB.step;
        const head = '<div class="wiz-head"><div class="wiz-logo"><img src="img/nexa-logo.png" alt=""></div>' +
          '<div class="wt"><b>NEXA</b><span>PROVIDER AGENT</span></div><span class="wstep">' + t('stageWizStep').replace('{n}', s) + '</span></div>';
        const bars = '<div class="wiz-bars">' + [1, 2, 3, 4].map(i => '<span class="b ' + (i < s ? 'done' : i === s ? 'cur' : '') + '"></span>').join('') + '</div>';
        let body = '', foot = '';
        if (s === 1) {
          body = '<div class="wiz-title">' + t('stageWelcomeTitle') + '</div>' +
            '<div class="wiz-sub">' + t('stageWelcomeSub') + '</div>' +
            '<div class="wiz-list">' +
            feat(IC.chat, t('stageFeatureTextTitle'), t('stageFeatureTextDesc')) +
            feat(IC.image, t('stageFeatureImageTitle'), t('stageFeatureImageDesc')) +
            feat(IC.shield, t('stageFeatureSecurityTitle'), t('stageFeatureSecurityDesc')) +
            '</div>';
          // 1단계 전체 skip('나중에')은 두지 않는다 — 온보딩은 첫 필수 설정이고, 런타임/연결 skip 은
          // 각 단계(2단계 '지금 설치 안 함' · 4단계 '메인 화면으로')에서 선택할 수 있다.
          foot = '<span class="spacer"></span>' + btn('primary', '2', t('stageStep1Start'));
        } else if (s === 2) {
          body = '<div class="wiz-title">' + t('stageStep2Title') + '</div>' +
            '<div class="wiz-sub">' + t('stageStep2Sub') + '</div>' +
            '<div class="wiz-list">' +
            opt('ollama', t('stageOllamaLabel'), t('stageOllamaTag'), t('stageOllamaDesc'), t('stageOllamaSize'), ONB.ollama) +
            opt('none', t('stageSkipInstall'), '', t('stageSkipInstallDesc'), '', ONB.none) +
            '</div>' +
            '<p class="wiz-sub" style="margin-top:8px">' + t('stageStep2ComfyNote') + '</p>';
          foot = btn('secondary', '1', t('stageButtonPrevious')) + '<span class="spacer"></span>' + btn('primary', '3', t('stageButtonNext'));
        } else if (s === 3) {
          body = '<div class="wiz-title">' + t('stageStep3Title') + '</div>' +
            '<div class="wiz-sub">' + t('stageStep3Sub') + '</div>' +
            '<div class="wiz-list">' +
            tog('autostart', t('stageAutoStartLabel'), t('stageAutoStartDesc')) +
            tog('autoconnect', t('stageAutoConnectLabel'), t('stageAutoConnectDesc')) +
            tog('imageRecv', t('stageImageRecvLabel'), t('stageImageRecvDesc')) +
            tog('background', t('stageBackgroundLabel'), t('stageBackgroundDesc')) +
            '</div>';
          foot = btn('secondary', '2', t('stageButtonPrevious')) + '<span class="spacer"></span>' + btn('primary', '4', t('stageButtonNext'));
        } else {
          body = '<div class="wiz-title">' + t('stageStep4Title') + '</div>' +
            '<div class="wiz-sub">' + t('stageStep4Sub') + '</div>' +
            '<div class="wiz-list">' +
            sum(t('stageSummaryTextModel'), ONB.ollama ? t('stageSummaryTextModelOn') : t('stageSummaryTextModelOff'), ONB.ollama) +
            sum(t('stageSummaryImageRecv'), ONB.imageRecv ? t('stageSummaryImageRecvOn') : t('stageSummaryImageRecvOff'), ONB.imageRecv) +
            sum(t('stageSummaryAutoConnect'), ONB.autoconnect ? t('stageSummaryAutoConnectOn') : t('stageSummaryAutoConnectOff'), ONB.autoconnect) +
            sum(t('stageSummaryBackground'), ONB.background ? t('stageSummaryBackgroundOn') : t('stageSummaryBackgroundOff'), ONB.background) +
            '</div>';
          foot = btn('secondary', '3', t('stageButtonPrevious')) + '<span class="spacer"></span>' + btn('secondary', 'main', t('stageButtonMainScreen')) + btn('primary', 'connect', t('stageButtonConnect'));
        }
        wiz.innerHTML = head + bars + '<div class="wiz-body">' + body + '</div><div class="wiz-foot">' + foot + '</div>';
        wireWiz();
      }

      // A2 는 '선택'만 한다(설치 아님). 설치는 A4 '연동하기'에서 시작.
      function togglePick(k) {
        if (k === 'none') { ONB.none = true; ONB.ollama = false; return; }
        ONB.none = false;
        ONB[k] = !ONB[k];
      }
      // 온보딩 3단계 동작 토글을 백엔드에 반영한다(autostart→자동 시작 서비스, background→트레이 상주,
      // autoConnect→자동 연결, enableImage→이미지 수신). 이게 없으면 토글이 UI 에만 켜지고 실제 적용이 안 된다.
      async function applyOnboardingSettings() {
        // 실제 저장을 기다린다(낙관적 전환 금지). 실패해도 흐름은 막지 않되 사용자에게 알린다(설정에서 변경 가능).
        try {
          const r = await api.applyOnboarding({
            enableImage: ONB.imageRecv,
            autostart: ONB.autostart,
            autoConnect: ONB.autoconnect,
            background: ONB.background,
          });
          if (r && r.ok === false) window.toast?.(t('stageSaveSettingsFailed'), { type: 'info' });
        } catch (_e) { window.toast?.(t('stageSaveSettingsFailed'), { type: 'info' }); }
      }
      // A4 '연동하기' → 동작 설정 반영(대기) + 선택한 런타임 설치(install.js installRuntime — 토스트 진행 SSOT) → 연결로.
      async function finishOnboarding() {
        await applyOnboardingSettings();
        if (ONB.ollama) installRuntime('ollama');
        App.connectOrigin = 'onboarding'; connectSub = 'login'; // 첫 인증 — 로그인부터
        setStage('connect');
      }
      function wireWiz() {
        wiz.querySelectorAll('[data-opt]').forEach(b => b.onclick = () => { togglePick(b.dataset.opt); renderWiz(); });
        wiz.querySelectorAll('[data-tog]').forEach(b => b.onclick = () => { ONB[b.dataset.tog] = !ONB[b.dataset.tog]; renderWiz(); });
        wiz.querySelectorAll('[data-go]').forEach(b => b.onclick = async () => {
          const g = b.dataset.go;
          if (g === 'connect') { b.disabled = true; try { await finishOnboarding(); } finally { b.disabled = false; } }   // 연동하기 = 설정 저장 대기 + 설치 시작 + 연결
          else if (g === 'main') { b.disabled = true; try { await applyOnboardingSettings(); } finally { b.disabled = false; } setStage('main'); } // 설정 저장 후 메인
          else { ONB.step = +g; renderWiz(); emit(); }
        });
      }

      // stage/connectOrigin 은 state.js(App) 가 SSOT. connectSub·connectedGuild 는 connect 내부 상태.
      let connectSub = 'login', connectedGuild = '';
      // 실 앱 'waiting' 서브의 완료 폴링 핸들(브라우저 OAuth 완료 → hasToken=true 감지). 화면 이탈/취소 시 정리.
      let connectPoll = null;
      function stopConnectPoll() { if (connectPoll) { clearInterval(connectPoll); connectPoll = null; } }
      function emit() { window.dispatchEvent(new CustomEvent('stagechange', { detail: { stage: App.stage, step: ONB.step, connectSub } })); }
      // 언어가 바뀌면 현재 활성 stage(온보딩/연동) 마법사를 다시 그린다(JS 렌더 문구 갱신). IIFE 1회 등록(리스너 누적 없음).
      onLangChange(() => { if (App.stage === 'onboarding') renderWiz(); else if (App.stage === 'connect') renderConnect(); });
      function setStage(name) {
        // 메인 노출(app-booting 해제) 전에 현재 해시의 최상위 뷰를 먼저 active 로 맞춘다 → 홈 깜빡임 방지.
        //   라우터 모듈이 아직 평가 전이라 window.__activateRoute 가 없을 수 있어 여기서 직접 처리(의존 제거).
        if (name === 'main') {
          const ROUTE_VIEWS = ['home', 'models', 'servers', 'local', 'logs', 'settings'];
          const a = (location.hash.replace(/^#\/?/, '').split('/').filter(Boolean)[0]) || 'home';
          const v = ROUTE_VIEWS.includes(a) ? a : 'home';
          document.querySelectorAll('.nav-item').forEach((x) => x.classList.toggle('active', x.dataset.view === v));
          document.querySelectorAll('.view').forEach((x) => x.classList.toggle('active', x.dataset.view === v));
        }
        document.documentElement.classList.remove('app-booting'); // 부팅 판정 끝 — 메인 숨김 해제(이후 stage 가 표시 제어)
        if (name !== 'connect') stopConnectPoll(); // connect 화면 이탈 시 'waiting' 폴링 정리(누수 방지)
        applyStage(name); // App.stage 갱신 + authed 보정(I1: main ⟹ authed)
        app.style.display = name === 'main' ? '' : 'none';
        onbLayer.hidden = name !== 'onboarding';
        connectLayer.hidden = name !== 'connect';
        if (name === 'onboarding') renderWiz();
        if (name === 'connect') renderConnect();
        emit();
        if (window.navTo) { // URL 동기화(라우터 SSOT)
          if (name === 'onboarding') window.navTo('#/onboarding/' + ONB.step);
          else if (name === 'connect') window.navTo('#/connect/' + connectSub);
          // main 진입: 온보딩/연결 흐름에서 왔거나 경로가 없을 때만 홈으로. 이미 앱 경로(#/models 등)면
          // 보존(새로고침/딥링크 시 홈으로 튕겨 깜빡이는 문제 방지).
          else if (name === 'main') {
            const h = location.hash;
            if (!h || h === '#/' || h.startsWith('#/onboarding') || h.startsWith('#/connect')) window.navTo('#/home');
          }
        }
      }

      // ════ Discord 연결(B) — login → select → result(approved/pending/none/cancelled) ════
      const DISCORD_ICON = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M20.32 4.37A19.79 19.79 0 0 0 15.4 3a13.9 13.9 0 0 0-.63 1.31 18.27 18.27 0 0 0-5.54 0A13.9 13.9 0 0 0 8.6 3a19.79 19.79 0 0 0-4.92 1.37C.56 9.05-.26 13.6.15 18.08a19.93 19.93 0 0 0 6.04 3.05c.49-.67.93-1.38 1.3-2.12-.72-.27-1.41-.61-2.07-1 .17-.13.34-.27.5-.41a14.15 14.15 0 0 0 12.16 0c.17.14.34.28.5.41-.66.39-1.35.73-2.07 1 .37.74.81 1.45 1.3 2.12a19.93 19.93 0 0 0 6.04-3.05c.48-5.19-.82-9.69-3.53-13.71ZM8.02 15.3c-1.18 0-2.15-1.1-2.15-2.44 0-1.35.95-2.44 2.15-2.44 1.2 0 2.17 1.1 2.15 2.44 0 1.34-.96 2.44-2.15 2.44Zm7.96 0c-1.18 0-2.15-1.1-2.15-2.44 0-1.35.95-2.44 2.15-2.44 1.2 0 2.17 1.1 2.15 2.44 0 1.34-.96 2.44-2.15 2.44Z"/></svg>';
      // 진입 맥락: onboarding(첫 인증 — 로그인부터) vs main(이미 인증됨 — 서버 추가, 로그인 생략)
      const cxHead = () => '<div class="wiz-head"><div class="wiz-logo"><img src="img/nexa-logo.png" alt=""></div><div class="wt"><b>NEXA</b><span>' + (App.connectOrigin === 'main' ? t('stageServerAdd') : t('stageConnectTitle')) + '</span></div></div>';
      async function renderConnect() {
        const sub = connectSub;
        const isMain = App.connectOrigin === 'main';
        let html = cxHead();
        if (sub === 'login') {
          html += '<div class="wiz-body">' +
            '<div class="wiz-title">' + t('stageLoginTitle') + '</div>' +
            '<div class="wiz-sub">' + t('stageLoginSub') + '</div>' +
            '<div class="wiz-list"><button class="btn btn--lg btn--primary cx-discord" id="cxLogin">' + DISCORD_ICON + ' ' + t('stageLoginButton') + '</button></div>' +
            '<div class="cx-token"><button class="cx-tok-tgl" id="cxTokTgl">' + t('stageTokenAdvanced') + '</button>' +
              '<div class="cx-tok-body" hidden><input class="cx-input" id="cxTok" placeholder="' + t('stageTokenPlaceholder') + '"><button class="btn btn--md btn--secondary" id="cxTokAdd">' + t('stageTokenAdd') + '</button></div>' +
            '</div></div>' +
            '<div class="wiz-foot"><button class="btn btn--md btn--secondary" data-cx="back-onb">' + t('stageLoginCancel') + '</button></div>';
        } else if (sub === 'waiting') {
          // 실 앱 전용: OAuth 가 시스템 브라우저에서 진행 중. 완료(토큰 저장)되면 폴링이 자동으로 메인으로 보낸다.
          html += '<div class="wiz-body">' +
            '<div class="wiz-title">' + t('stageWaitingTitle') + '</div>' +
            '<div class="wiz-sub">' + t('stageWaitingSub') + '</div>' +
            '</div>' +
            '<div class="wiz-foot"><button class="btn btn--md btn--secondary" data-cx="login">' + t('stageLoginCancel') + '</button></div>';
        } else if (sub === 'select') {
          const cands = await api.getConnectCandidates();
          if (!cands.length) { connectSub = 'none'; return renderConnect(); }
          // 메인(이미 인증)에선 로그인 없이 후보 선택 + 참여 토큰 옵션. 온보딩에선 로그인 다음 단계.
          const tokenBlock = isMain ? '<div class="cx-token"><button class="cx-tok-tgl" id="cxTokTgl">' + t('stageSelectTokenBlock') + '</button>' +
            '<div class="cx-tok-body" hidden><input class="cx-input" id="cxTok" placeholder="' + t('stageTokenPlaceholder') + '"><button class="btn btn--md btn--secondary" id="cxTokAdd">' + t('stageTokenAdd') + '</button></div></div>' : '';
          html += '<div class="wiz-body"><div class="wiz-title">' + (isMain ? t('stageSelectTitle') : t('stageSelectTitleOnboarding')) + '</div>' +
            '<div class="wiz-sub">' + t('stageSelectSub') + '</div><div class="wiz-list">' +
            cands.map((c) => '<button class="wiz-opt cx-cand" data-guild="' + c.guildId + '">' +
              '<span class="cx-cav">' + c.guildName.trim().charAt(0) + '</span>' +
              '<span class="ob"><b>' + c.guildName + '</b><p>' + (c.autoApprove ? t('stageServerApprovalAuto') : t('stageServerApprovalWait')) + '</p></span>' +
              '<svg class="srv-go" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="m9 6 6 6-6 6"/></svg></button>').join('') +
            '</div>' + tokenBlock + '</div>' +
            '<div class="wiz-foot"><button class="btn btn--md btn--secondary" data-cx="' + (isMain ? 'main' : 'login') + '">' + (isMain ? t('stageSelectCancel') : t('stageSelectBack')) + '</button></div>';
        } else {
          const where = connectedGuild; // 서버명. 이미 '서버'를 포함 → 중복 방지
          const R = {
            approved: { ic: '✓', cls: 'ok', title: t('stageResultApprovedTitle'), sub: t('stageResultApprovedSub').replace('{n}', where || t('stageThisServerFallback')), btns: '<button class="btn btn--md btn--primary" data-cx="main">' + t('stageButtonMainScreen') + '</button>' },
            pending: { ic: '⏳', cls: 'pending', title: t('stageResultPendingTitle'), sub: t('stageResultPendingSub').replace('{n}', where || t('stageThisServerFallback')), btns: '<button class="btn btn--md btn--secondary" data-cx="select">' + t('stageResultPendingOtherServer') + '</button><button class="btn btn--md btn--primary" data-cx="main">' + t('stageButtonMainScreen') + '</button>' },
            none: { ic: '∅', cls: 'pending', title: t('stageResultNoneTitle'), sub: t('stageResultNoneSub'), btns: '<button class="btn btn--md btn--secondary" data-cx="login">' + t('stageResultRetry') + '</button>' },
            cancelled: { ic: '✕', cls: 'error', title: t('stageResultCancelledTitle'), sub: t('stageResultCancelledSub'), btns: '<button class="btn btn--md btn--primary" data-cx="login">' + t('stageResultRetry') + '</button>' },
          }[sub] || {};
          html += '<div class="wiz-body"><div class="cx-result"><div class="cx-big ' + R.cls + '">' + R.ic + '</div>' +
            '<div class="wiz-title">' + R.title + '</div><div class="wiz-sub">' + R.sub + '</div></div></div>' +
            '<div class="wiz-foot"><span class="spacer"></span>' + R.btns + '</div>';
        }
        connectWiz.innerHTML = html;
        wireConnect();
      }
      function wireConnect() {
        const q = (s) => connectWiz.querySelector(s);
        // 'waiting'(실 앱) 진입 시 완료 폴링 시작, 그 외 서브에선 정리(중복 방지·이탈 정리).
        // 폴링은 window.__SESSION_KEY 가 있는 실 앱에서만(프로토타입은 mock 흐름이라 폴링 불필요).
        if (connectSub === 'waiting' && window.__SESSION_KEY) {
          if (!connectPoll) connectPoll = setInterval(async () => {
            try {
              const st = await api.getStatus();
              if (st && st.hasToken) {
                stopConnectPoll();
                setStage('main');
                toast(t('stageConnectToast'), { type: 'ok' });
              }
            } catch (e) { /* 일시적 실패는 다음 주기에 재시도 */ }
          }, 2500);
        } else {
          stopConnectPoll();
        }
        const login = q('#cxLogin');
        if (login) login.onclick = async () => {
          /* @proto-only */
          // 프로토타입(키 없음)은 기존 mock 흐름(앱 내 후보 select) — connect-open 결과 무관.
          if (!window.__SESSION_KEY) { await api.connectOpen(); connectSub = 'select'; renderConnect(); emit(); return; }
          /* @end-proto-only */
          // 실 앱: connect-open 이 시스템 브라우저로 OAuth 를 연다. 단 서버에 OAuth 미설정이면
          // {ok:false,error} 를 돌려주고 브라우저가 안 열린다 — 이때 'waiting' 으로 가면 영원히 폴링하므로
          // 사유를 안내하고 login 화면을 유지한다. ok 일 때만 'waiting'(완료 폴링)으로 넘어간다.
          const r = await api.connectOpen();
          if (r && r.ok === false) {
            toast(t('stageBrowserOpenFailed'), { type: 'info', sub: r.error ? (r.error + t('stageBrowserOpenFailedHintSuffix')) : t('stageBrowserOpenFailedHint') });
            return;
          }
          connectSub = 'waiting';
          renderConnect(); emit();
        };
        const tgl = q('#cxTokTgl');
        if (tgl) tgl.onclick = () => { const b = q('.cx-tok-body'); b.hidden = !b.hidden; };
        const tokAdd = q('#cxTokAdd');
        if (tokAdd) tokAdd.onclick = async () => {
          const token = (q('#cxTok')?.value || '').trim();
          toast(t('stageTokenCheckingIn'), { type: 'run', sticky: true, id: 'join' });
          const res = await api.joinByToken(token);          // 토큰 검증 → guildName 복원
          const ok = res.state === ProviderState.APPROVED;
          connectedGuild = res.guildName || '';
          toast(ok ? t('stageTokenComplete') : t('stageApprovalRequested'), { type: ok ? 'ok' : 'info', replace: 'join' });
          connectSub = ok ? 'approved' : 'pending';
          renderConnect(); emit();
        };
        connectWiz.querySelectorAll('.cx-cand').forEach((el) => el.onclick = async () => {
          const gid = el.dataset.guild; // 64bit guildId — 문자열 유지(정밀도)
          toast(t('stageServerJoiningIn'), { type: 'run', sticky: true, id: 'join' });
          const res = await api.requestJoin(gid);
          const ok = res.state === ProviderState.APPROVED;
          connectedGuild = res.guildName || '';                // 결과 화면 서버명
          toast(ok ? t('stageTokenComplete') : t('stageApprovalRequested'), { type: ok ? 'ok' : 'info', replace: 'join' });
          connectSub = ok ? 'approved' : 'pending';
          renderConnect(); emit();
        });
        connectWiz.querySelectorAll('[data-cx]').forEach((el) => el.onclick = () => {
          const a = el.dataset.cx;
          if (a === 'main') setStage('main');
          else if (a === 'back-onb') setStage('onboarding');
          else { connectSub = a; renderConnect(); emit(); }
        });
      }

      // 프로토타입 컨트롤러용 노출
      window.setStage = setStage;
      window.setOnbStep = (n) => { ONB.step = n; setStage('onboarding'); };
      window.setConnectSub = (sub) => { connectSub = sub; setStage('connect'); };
      // 메인(이미 인증)에서 서버 추가 — 로그인 없이 후보 선택부터. I3 가드.
      window.enterServerAdd = () => { if (!canEnterServerAdd()) return; App.connectOrigin = 'main'; connectSub = 'select'; setStage('connect'); };
      // 미연결(토큰 없음)에서 홈 '서버 추가' — 디스코드 로그인부터(연결 화면). 온보딩 origin 으로 로그인 단계 진입.
      window.enterConnectLogin = () => { App.connectOrigin = 'onboarding'; connectSub = 'login'; setStage('connect'); };

      // 실 앱 부팅 분기: 세션키가 주입된 실제 앱(USE_MOCK=false)에서만 토큰 유무로 온보딩/메인을 자동 결정한다.
      // 프로토타입(세션키 없음)은 라우터 hash·PROTO 도구로 stage 를 제어하므로 자동 분기하지 않는다.
      // 실 앱 홈을 실제 status 로 동기화한다(하드코딩 가짜값 방지). 미연결(토큰 없음)이면 정상 제공처럼
      // 보이지 않게 미연결 상태·0 통계를 보인다. 프로토타입(키 없음)은 PROTO 데모가 그대로 제어.
      async function syncHomeFromStatus() {
        let st = {};
        try { st = await api.getStatus() || {}; } catch (e) { return; }
        // 오늘 처리 통계 — 백엔드 미제공 필드는 0/— 으로(가짜값 금지).
        const setText = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
        if (st.version) setText('sideVer', 'v' + st.version); // 사이드바 앱 버전(실값)
        setText('statReq', String(st.processed || 0));
        setText('statModels', String((st.models && st.models.length) || 0));
        setText('statAvg', '—'); // 평균 응답은 백엔드 미제공 — 가짜 0ms 대신 —
        // 제공 중 판정: 이 창의 인-프로세스 에이전트(running)가 돌거나, 백그라운드 자동시작 서비스가
        // 이미 연결 중(backgroundRunning)이면 "제공 중"이다. backgroundRunning 을 무시하면 백그라운드
        // 서비스가 정상 제공 중인데도 홈이 '제공 중단됨/Discord 끊김 + 죽은 다시연결'을 보였다(실버그).
        const serving = !!(st.running || st.backgroundRunning);
        // 런타임 카드 상태: Ollama(모델 보유·제공) / ComfyUI(이미지 준비) 기준.
        if (window.setImageRecv) window.setImageRecv(!!st.enableImage); // 이미지 요청 받기 실 상태 반영(먼저)
        if (window.setBgOn) window.setBgOn(!!st.background); // 백그라운드 핀 = 상주 '설정값'(설정 화면과 동일). 런타임(backgroundRunning) 아님
        if (window.setRuntime) {
          const nModels = (st.models && st.models.length) || 0;
          const ollamaState = (serving && nModels) ? 'running' : (nModels ? 'stopped' : 'absent');
          // Ollama 메타는 실제 제공 모델 수로(하드코딩 '모델 7개' 금지).
          window.setRuntime('Ollama', ollamaState, ollamaState === 'running' ? t('stageOllamaModelCount').replace('{n}', nModels) : undefined);
          // ComfyUI: 이 창에 인-프로세스 에이전트가 없으면(backgroundRunning) imageReady 를 알 수 없으므로
          // 이미지 받기 설정이 켜져 있으면 백그라운드가 제공 중인 것으로 본다(가짜 '실행 중' 대신 설정 기반 추정).
          // 상세 설치/시작은 '로컬 실행' 탭의 ComfyUI 카드가 /api/comfy/status 로 관리한다.
          const comfyRunning = st.backgroundRunning ? !!st.enableImage : !!st.imageReady;
          const comfyAvailable = !!(st.imageReady || st.enableImage); // 메인 status 에 설치여부는 없음 → 준비/켜짐을 'available' 로
          window.setRuntime('ComfyUI', !comfyAvailable ? 'absent' : (comfyRunning ? 'running' : 'stopped'));
        }
        // 히어로 상태 + 페이지 부제 — 미연결이면 미연결, 연결됐으면 제공 여부(running 또는 backgroundRunning)로 ok/error.
        const sub = document.getElementById('homeSub');
        if (!st.hasToken) {
          if (sub) sub.textContent = t('stageHomeUnconnected');
          window.setHeroState?.('unconnected');
        } else {
          if (sub) sub.textContent = serving ? t('stageHomeProvidingOk') : t('stageHomeProvidingError');
          window.setHeroState?.(serving ? 'ok' : 'error');
        }
      }

      if (window.__SESSION_KEY) {
        App.connectOrigin = 'onboarding';
        api.getStatus()
          .then((st) => {
            if (st && st.hasToken) {
              setStage('main');
              window.applyRouteNow?.(); // 메인 노출과 같은 틱에 현재 해시 뷰 적용 → 홈 플래시 방지(#/models 등 딥링크 보존)
            } else { setStage('onboarding'); }
          })
          .catch(() => setStage('onboarding'));
        syncHomeFromStatus(); // 홈 진입 여부와 무관하게 실제 status 로 미리 동기화(가짜값 방지)
        // 실 앱: 홈을 실제 status 로 **지속 동기화**한다. 버튼의 낙관적/가짜 전환이 아니라 항상 진짜 상태가
        //   이기게 해, "시작/다시연결" 후 실제 완료되어야 화면이 바뀐다(프로토타입 데모 setTimeout 가짜 금지).
        window.syncHomeFromStatus = syncHomeFromStatus;
        setInterval(() => { syncHomeFromStatus(); }, 3000);
        // 홈 hero·런타임 카드가 호출하는 실 액션 헬퍼(완료 후 즉시 동기화). 에이전트=텍스트 제공 본체.
        window.realProviderAction = async (action) => {
          let r;
          if (action === 'stop') r = await api.stopAgent();
          else if (action === 'reconnect') { await api.stopAgent(); r = await api.startAgent(); } // 중앙 서버 재연결
          else r = await api.startAgent();
          await syncHomeFromStatus();
          // 시작/재연결이 거부되면(예: 백그라운드 서비스가 이미 제공 중 → otherInstanceConnected) 정직하게 실패로
          // 알린다. 이전엔 {ok:false}를 무시하고 가짜 성공 토스트를 띄워 '눌러도 그대로'로 보였다.
          if (r && r.ok === false) throw new Error(r.error || t('stageRequestFailed'));
          return r;
        };
        // ComfyUI 설치/시작은 '로컬 실행' 탭의 ComfyUI 카드가 소유한다(앱 관리 설치·웹UI 오픈·체크포인트 선택).
        // 홈 카드에서 '시작'을 누르면 그 전문 화면으로 보낸다(홈에서 즉석 설치하던 startSetup('image') 경로 폐기).
        window.realStartComfy = async () => { const b = document.querySelector('.nav-item[data-view="local"]'); if (b) b.click(); };
        window.realGetStatus = () => api.getStatus(); // 진단(hero diag)이 실제 상태로 점검
        window.realSetImageRecv = async (on) => { const r = await api.setImageReceiving(on); await syncHomeFromStatus(); return r || {}; }; // 이미지 요청 받기 실 토글(응답 반환)
        window.realSetSetting = (k, v) => api.setSetting(k, v); // 홈 백그라운드 핀 등 단일 설정 저장
        window.realOpenFolder = (which) => api.openFolder(which); // ⋯ '출력 폴더 열기'(실제 OS 탐색기)
        // 새로고침/재부팅 복원: 진행 중인 런타임 설치가 있으면 어느 화면이든 진행 토스트를 무조건 복원한다.
        // phase 가 진행 상태(=done/idle/error/cancelled 가 아님)면 watchSetup 으로 폴링·토스트만 이어붙인다.
        const _ACTIVE = (p) => p && p.phase && !['done', 'idle', 'error', 'cancelled'].includes(p.phase);
        // 진행 중 설치 복원은 Ollama 만(getSetupProgress 대상). ComfyUI 설치 진행 복원은 '로컬 실행' 탭이 /api/comfy/setup-progress 로 따로 관리한다.
        ['ollama'].forEach((rt) => {
          api.getSetupProgress(rt).then((p) => { if (_ACTIVE(p)) watchSetup(rt); }).catch(() => {});
        });
      }
    })();

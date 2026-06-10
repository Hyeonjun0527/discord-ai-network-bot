// NEXA 데스크톱 — router.js (index.html 에서 분리, SoC/SRP). 동작 보존 verbatim.
    const VIEWS = ['home', 'models', 'servers', 'local', 'logs', 'settings'];
    let routing = false;  // 내부 전환이 만든 hash 변경이면 applyRoute 를 건너뛴다(무한 루프 방지)
    let applying = false; // 라우팅 중(URL→화면)엔 화면→URL(navTo) 억제 — 중간 hash 깜빡임 방지

    window.navTo = (hash) => {
      if (applying) return; // URL 이 이미 목표값이므로 라우팅 중엔 갱신하지 않는다
      if (location.hash === hash) return;
      routing = true;
      location.hash = hash;
    };

    function gotoView(v) {
      if (window.App && window.App.stage !== 'main') window.setStage?.('main');
      document.querySelector('.nav-item[data-view="' + v + '"]')?.click();
    }

    async function applyRoute() {
      if (routing) { routing = false; return; } // 내부 갱신이면 화면 재전환 안 함
      applying = true; // 이하 화면 전환이 만드는 navTo 는 무시(원본 URL 유지)
      try {
        const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean);
        const [a, b, c] = parts;
        if (!a || a === 'home') return gotoView('home');
        if (a === 'onboarding') return void window.setOnbStep?.(Number(b) || 1);
        if (a === 'connect') return void window.setConnectSub?.(b || 'login');
        if (VIEWS.includes(a)) {
          if (a === 'servers' && b) {
            gotoView('servers');
            if (c === 'manage') return void (await window.openServerManage?.(b, parts[3])); // guildId 문자열(64bit 정밀도)
            return void (await window.openServerDetail?.(b));
          }
          return gotoView(a);
        }
        gotoView('home'); // 알 수 없는 경로는 홈으로 폴백
      } finally {
        applying = false;
      }
    }

    window.addEventListener('hashchange', applyRoute);
    window.applyRouteNow = applyRoute; // 부팅이 딥링크(서버 상세 #/servers/:id 등)까지 완전 적용하도록(최상위 뷰는 setStage 가 노출 전 선반영)
    if (location.hash && location.hash !== '#/home') applyRoute(); // 새로고침/딥링크 초기 진입

// NEXA 데스크톱 — screen-home.js (index.html 에서 분리, SoC/SRP). 동작 보존 verbatim.
    import { api } from './adapter.js';
    import { presentServerState, presentServerMeta, presentRole } from './presenter.js';

    // 아바타 틴트(브랜드 hue 순환 — 디자인 언어: 색은 토큰에서만)
    const TINT = ['--c-violet', '--c-blue', '--c-cyan', '--c-purple'];
    // 역할 아이콘(UI 표현 — 관리자 방패 / 기부자 하트)
    const ROLE_ICON = {
      admin: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2 4 5v6c0 5 3.5 8.4 8 9.5 4.5-1.1 8-4.5 8-9.5V5Z"/></svg>',
      provider: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>',
    };
    const initialOf = (name) => (name || '·').trim().charAt(0);

    const list = document.getElementById('srvList');
    const goServers = () => { const b = document.querySelector('.nav-item[data-view="servers"]'); if (b) b.click(); };

    function render(servers) {
      if (!servers.length) {
        // 빈틈: 연결된 서버 0개(온보딩 직후·전부 제거). 서버 추가 유도.
        list.innerHTML = '<div class="empty-card"><b>아직 연결된 서버가 없어요</b>' +
          '<p>AI 를 제공하려면 Discord 서버에 연결하세요.</p>' +
          '<button class="btn btn--md btn--primary" id="srvAddEmpty">+ 서버 추가</button></div>';
        const add = document.getElementById('srvAddEmpty');
        if (add) add.addEventListener('click', () => { if (window.enterServerAdd) window.enterServerAdd(); });
        return;
      }
      list.innerHTML = servers.map((s, i) => {
        const tint = TINT[i % TINT.length];
        const avatarStyle = 'background: color-mix(in srgb, var(' + tint + ') 22%, transparent); border-color: color-mix(in srgb, var(' + tint + ') 40%, transparent);';
        const img = s.iconUrl ? '<img src="' + s.iconUrl + '" alt="" onerror="this.remove()">' : '';
        const st = presentServerState(s.state);
        const role = presentRole(s.role);
        return '<button class="srv-item">' +
          '<span class="srv-avatar" style="' + avatarStyle + '">' + initialOf(s.guildName) + img + '</span>' +
          '<span class="srv-main">' +
            '<span class="srv-name"><span class="nm">' + s.guildName + '</span></span>' +
            '<span class="srv-meta"><span class="srv-st ' + st.dot + '"><span class="d"></span>' + st.label + '</span> · ' + presentServerMeta(s) + '</span>' +
          '</span>' +
          '<span class="srv-role ' + role.cls + '">' + ROLE_ICON[role.cls] + role.label + '</span>' +
          '<svg class="srv-go" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="m9 6 6 6-6 6"/></svg>' +
        '</button>';
      }).join('');
      list.querySelectorAll('.srv-item').forEach(el => el.addEventListener('click', goServers));
    }

    const servers = await api.getServers();
    render(servers);
    document.getElementById('srvAll').addEventListener('click', goServers);
    // 실 앱: 홈의 '연결된 서버'도 주기적으로 자동 갱신(연결·제공 모델 수 변화 즉시 반영).
    if (window.__SESSION_KEY) {
      const homeView = document.querySelector('.view[data-view="home"]');
      setInterval(async () => { if (homeView.classList.contains('active')) { try { render(await api.getServers()); } catch (_e) { /* 일시 실패 무시 */ } } }, 3000);
    }
    /* @proto-only */
    // 빈틈 데모: 서버 0개 ↔ 복원
    window.setHomeServers = (mode) => render(mode === 'empty' ? [] : servers);
    /* @end-proto-only */

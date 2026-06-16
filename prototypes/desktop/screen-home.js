// NEXA 데스크톱 — screen-home.js (index.html 에서 분리, SoC/SRP). 동작 보존 verbatim.
    import { api } from './adapter.js';
    import { presentServerState, presentServerMeta, presentRole } from './presenter.js';
    import { t, onLangChange } from './i18n.js';

    // 아바타 틴트(브랜드 hue 순환 — 디자인 언어: 색은 토큰에서만)
    const TINT = ['--c-violet', '--c-blue', '--c-cyan', '--c-purple'];
    // 역할 아이콘(UI 표현 — 관리자 방패 / 기부자 하트)
    const ROLE_ICON = {
      admin: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2 4 5v6c0 5 3.5 8.4 8 9.5 4.5-1.1 8-4.5 8-9.5V5Z"/></svg>',
      provider: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>',
    };
    const initialOf = (name) => (name || '·').trim().charAt(0);
    // 디스코드 서버명에 <>& 가 흔해 마크업 주입/렌더 깨짐 방지(다른 입력과 동일하게 escape).
    const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

    const list = document.getElementById('srvList');
    const goServers = () => { const b = document.querySelector('.nav-item[data-view="servers"]'); if (b) b.click(); };

    let _servers = [];
    function render(servers) {
      _servers = servers;
      if (!servers.length) {
        // 빈틈: 연결된 서버 0개(온보딩 직후·전부 제거). 서버 추가 유도.
        list.innerHTML = '<div class="empty-card"><b>' + t('homeEmptyTitle') + '</b>' +
          '<p>' + t('homeEmptyDescription') + '</p>' +
          '<button class="btn btn--md btn--primary" id="srvAddEmpty">' + t('homeAddServerButton') + '</button></div>';
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
        const nm = esc(s.guildName);
        // 스크린리더용: 서버명 + 상태 + 역할(시각 배지를 음성으로 전달).
        const aria = esc(s.guildName + ', ' + st.label + ', ' + role.label);
        return '<button class="srv-item" aria-label="' + aria + '">' +
          '<span class="srv-avatar" style="' + avatarStyle + '">' + esc(initialOf(s.guildName)) + img + '</span>' +
          '<span class="srv-main">' +
            '<span class="srv-name"><span class="nm">' + nm + '</span></span>' +
            '<span class="srv-meta"><span class="srv-st ' + st.dot + '"><span class="d"></span>' + st.label + '</span> · ' + presentServerMeta(s) + '</span>' +
          '</span>' +
          '<span class="srv-role ' + role.cls + '">' + ROLE_ICON[role.cls] + role.label + '</span>' +
          '<svg class="srv-go" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="m9 6 6 6-6 6"/></svg>' +
        '</button>';
      }).join('');
      list.querySelectorAll('.srv-item').forEach(el => el.addEventListener('click', goServers));
    }

    // 최초 로드 중에는 스켈레톤 — '비었음(서버 0개)'과 '불러오는 중'을 구분(빈 화면 착시 방지).
    list.innerHTML = '<div class="skel-row"></div><div class="skel-row"></div><div class="skel-row"></div>';
    const servers = await api.getServers();
    render(servers);
    onLangChange(() => render(_servers));
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

// NEXA 데스크톱 — screen-logs.js (index.html 에서 분리, SoC/SRP). 동작 보존 verbatim.
    import { api } from './adapter.js';
    import { toast } from './toast.js';
    import { t, onLangChange } from './i18n.js';

    const view = document.querySelector('.view[data-view="logs"]');
    const logView = document.getElementById('logView');
    let _raw = [], _filter = 'all', _source = 'all', _auto = true;

    const esc = (s) => s.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
    // 출처 분류 — 백엔드 토큰(SD/Ollama/Relay/Agent)을 우선, 없으면(mock/구버전) 메시지 키워드로 추론.
    const srcLabel = (src) => ({ agent: t('logsSourceAgent'), ollama: 'Ollama', sd: 'SD', relay: t('logsSourceRelay') }[src] || src);
    function inferSource(msg) {
      const m = (msg || ''), low = m.toLowerCase();
      if (low.includes('comfyui') || low.includes('stable diffusion') || low.includes('sd(') || low.includes('/imagine') || m.includes('이미지')) return 'sd';
      if (low.includes('ollama') || low.includes('/ask')) return 'ollama';
      if (m.includes('중앙 서버') || m.includes('릴레이') || low.includes('relay') || low.includes('wss')) return 'relay';
      return 'agent';
    }
    function normSource(tok) {
      const t = (tok || '').toLowerCase();
      if (t === 'sd') return 'sd';
      if (t === 'ollama') return 'ollama';
      if (t === 'relay') return 'relay';
      if (t === 'agent') return 'agent';
      return null;
    }
    function parse(raw) {
      // 우선 'HH:MM:SS LEVEL SOURCE | msg'(실 백엔드), 실패 시 'HH:MM:SS LEVEL | msg'(mock/구버전).
      let time = '', lvRaw = 'info', srcTok = null, msg = raw;
      let m = raw.match(/^(\d{2}:\d{2}:\d{2})\s+(\w+)\s+(\S+)\s*\|\s*([\s\S]*)$/);
      if (m) { time = m[1]; lvRaw = m[2]; srcTok = m[3]; msg = m[4]; }
      else {
        m = raw.match(/^(\d{2}:\d{2}:\d{2})\s+(\w+)\s*\|\s*([\s\S]*)$/);
        if (m) { time = m[1]; lvRaw = m[2]; msg = m[3]; }
      }
      const lv = lvRaw.toLowerCase();
      const level = lv.startsWith('warn') ? 'warn'
        : (lv.startsWith('err') || lv.startsWith('crit') || lv.startsWith('fatal')) ? 'error'
          : lv.startsWith('debug') ? 'debug' : 'info';
      const source = normSource(srcTok) || inferSource(msg);
      return { time, level, source, msg };
    }
    function render() {
      const rows = _raw.map(parse).filter((l) =>
        (_filter === 'all' || l.level === _filter || (_filter === 'info' && l.level === 'debug'))
        && (_source === 'all' || l.source === _source));
      if (!rows.length) { logView.innerHTML = '<div class="log-empty">' + t('logsEmpty') + '</div>'; return; }
      const atBottom = logView.scrollHeight - logView.scrollTop - logView.clientHeight < 50;
      logView.innerHTML = rows.map((l) =>
        '<div class="log-line ' + l.level + '"><span class="lt">' + l.time + '</span><span class="ll">' + l.level.toUpperCase() + '</span>' +
        '<span class="lsrc lsrc-' + l.source + '">' + srcLabel(l.source) + '</span>' +
        '<span class="lm">' + esc(l.msg) + '</span></div>').join('');
      if (atBottom) logView.scrollTop = logView.scrollHeight;
    }
    async function load() { const r = await api.getLogs(); _raw = r.lines || []; render(); }

    document.getElementById('logRefresh').addEventListener('click', load);
    document.getElementById('logCopy').addEventListener('click', async () => {
      try { await navigator.clipboard.writeText(_raw.join('\n')); toast(t('logsCopiedSuccess'), { type: 'ok' }); }
      catch { toast(t('logsCopyFailed'), { type: 'warn' }); }
    });
    const autoSw = document.getElementById('logAutoSw');
    autoSw.addEventListener('click', () => { _auto = !_auto; autoSw.classList.toggle('on', _auto); autoSw.setAttribute('aria-checked', String(_auto)); });
    document.getElementById('logFilters').addEventListener('click', (e) => {
      const b = e.target.closest('.log-chip'); if (!b) return;
      _filter = b.dataset.level;
      document.querySelectorAll('#logFilters .log-chip').forEach((c) => c.classList.toggle('active', c === b));
      render();
    });
    document.getElementById('logSources').addEventListener('click', (e) => {
      const b = e.target.closest('.log-chip'); if (!b) return;
      _source = b.dataset.source;
      document.querySelectorAll('#logSources .log-chip').forEach((c) => c.classList.toggle('active', c === b));
      render();
    });

    const isActive = () => view.classList.contains('active');
    document.querySelector('.nav-item[data-view="logs"]').addEventListener('click', load);
    setInterval(() => { if (isActive() && _auto) load(); }, 2500);
    onLangChange(() => render());
    if (isActive()) load();

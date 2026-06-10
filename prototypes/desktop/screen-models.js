// NEXA 데스크톱 — screen-models.js (index.html 에서 분리, SoC/SRP). 동작 보존 verbatim.
    import { api } from './adapter.js';
    import { toast } from './toast.js';
    import { installRuntime } from './install.js';
    import { t, onLangChange } from './i18n.js';

    const listEl = document.getElementById('modelList');
    const selEl = document.getElementById('defaultModelSel');
    const footEl = document.getElementById('modelFoot');
    const applyEl = document.getElementById('modelApply');
    let models = [], defaultModel = '', dirty = false;

    function markDirty() { dirty = true; applyEl.hidden = false; }
    function renderFoot() {
      const on = models.filter(m => m.on).length;
      footEl.textContent = t('modelsFooterCount').replace('{count}', models.length).replace('{active}', on);
    }
    function renderDefaultOptions() {
      // 기본 모델은 '제공 중'인 모델 중에서만 선택
      selEl.innerHTML = models.filter(m => m.on).map(m => '<option value="' + m.name + '"' + (m.name === defaultModel ? ' selected' : '') + '>' + m.name + '</option>').join('');
    }
    function renderList() {
      listEl.innerHTML = models.map(m =>
        '<div class="model-row ' + (m.on ? '' : 'off') + '">' +
          '<div class="m-main">' +
            '<div class="m-name">' + m.name + '<span class="m-size">' + m.size + '</span>' + (m.name === defaultModel ? '<span class="m-default">' + t('modelsDefaultBadge') + '</span>' : '') + '</div>' +
            '<div class="m-tags">' + m.tags.map(t => '<span class="tag">#' + t + '</span>').join(' ') + ' · ' + t('modelsLastUsed').replace('{lastUsed}', m.lastUsed) + '</div>' +
          '</div>' +
          '<button class="switch model-switch ' + (m.on ? 'on' : '') + '" data-model="' + m.name + '" role="switch" aria-checked="' + m.on + '"></button>' +
        '</div>').join('');
      listEl.querySelectorAll('.model-switch').forEach(sw => sw.addEventListener('click', () => {
        const m = models.find(x => x.name === sw.dataset.model);
        m.on = !m.on;
        if (!m.on && m.name === defaultModel) { const first = models.find(x => x.on); defaultModel = first ? first.name : ''; } // 기본이 꺼지면 다른 제공 모델로
        renderAll(); markDirty();
      }));
    }
    function renderAll() { renderList(); renderDefaultOptions(); renderFoot(); }

    selEl.addEventListener('change', () => { defaultModel = selEl.value; renderList(); markDirty(); });
    document.getElementById('modelApplyBtn').addEventListener('click', async (e) => {
      const btn = e.currentTarget;
      const sel = models.filter(m => m.on).map(m => m.name); // 제공할 모델(켜진 것만)
      const def = (defaultModel && sel.includes(defaultModel)) ? defaultModel : (sel[0] || ''); // 기본 응답 모델(제공 중인 것만)
      btn.disabled = true;
      toast(t('modelsApplyingToast'), { type: 'run', sticky: true, id: 'remodel' });
      try {
        const r = await api.applyModels(sel, def); // POST /api/setup {models, default, enableImage 보존, applyToBackground}
        if (r && r.ok === false) { toast(r.error || t('modelsApplyErrorToast'), { type: 'error', id: 'remodel' }); return; }
        dirty = false; applyEl.hidden = true; // 실제 적용 성공 후에만 dirty 해제·버튼 숨김
        toast(t('modelsApplySuccessToast'), { type: 'ok', id: 'remodel', sub: t('modelsApplySuccessSub') });
      } catch (_err) { toast(t('modelsApplyErrorToast'), { type: 'error', id: 'remodel' }); }
      finally { btn.disabled = false; }
    });

    // 추천 모델 설치 — install.js 모달 레이어 재사용(카탈로그 선택 → 토스트 설치 SSOT)
    document.getElementById('catalogBtn').addEventListener('click', async () => {
      const layer = document.getElementById('installModal'); const card = document.getElementById('installCard');
      const cat = await api.ollamaCatalog();
      layer.hidden = false;
      // 설치 진행: 토스트 SSOT + 성공 시에만 목록 추가. (Ollama 전체 목록 API 없음 → 추천 + 직접 입력)
      // 모델명에서 파라미터(b)를 파싱 → 12b 이상이면 VRAM 확인 모달. 필요 VRAM ≈ 파라미터 ÷ 2(Q4).
      const parseParamB = (mn) => {
        const ms = mn.match(/(\d+(?:\.\d+)?)b(?![a-z0-9])/gi);
        return ms ? parseFloat(ms[ms.length - 1]) : null;
      };
      const confirmVram = (mn, needGb, param) => new Promise(resolve => {
        const lay = document.createElement('div');
        lay.className = 'modal-layer'; lay.style.zIndex = '7';
        lay.innerHTML = '<div class="modal" style="width:min(430px,100%)">' +
          '<h3>' + t('modelsLargeModelTitle') + '</h3>' +
          '<p class="msub"><b>' + mn + '</b> ' + t('modelsLargeModelPrompt').replace('{modelName}', mn).replace('{needGb}', needGb).replace('{param}', param) + '</p>' +
          '<div class="modal-foot"><button class="btn btn--md btn--secondary" data-no>' + t('modelsLargeModelCancel') + '</button>' +
          '<button class="btn btn--md btn--warn" data-yes>' + t('modelsLargeModelConfirm').replace('{needGb}', needGb) + '</button></div></div>';
        document.body.appendChild(lay);
        lay.querySelector('[data-no]').onclick = () => { lay.remove(); resolve(false); };
        lay.querySelector('[data-yes]').onclick = () => { lay.remove(); resolve(true); };
      });
      const startPull = async (mn) => {
        const param = parseParamB(mn);
        if (param && param >= 12) {
          const need = Math.ceil(param / 2);
          if (!await confirmVram(mn, need, param)) return; // 취소 — 카탈로그 모달 유지
        }
        layer.hidden = true;
        if (models.some(x => x.name === mn)) { toast(t('modelsAlreadyInstalledToast').replace('{modelName}', mn), { type: 'info' }); return; }
        const ok = await installRuntime('ollama', mn); // 없는 모델이면 false(실패 토스트는 installRuntime 이 띄움)
        if (ok) { models.push({ name: mn, size: '—', tags: [t('modelsNewTag')], on: true, lastUsed: t('modelsJustNow') }); renderAll(); markDirty(); }
      };
      // 카테고리 드롭다운으로 한 분류씩만 표시(전체를 펼치면 너무 길다). 전체 변형은 직접 입력으로.
      const cats = [...new Set(cat.map(m => m.cat))];
      card.innerHTML = '<button class="modal-x" data-close="1" aria-label="' + t('modelsCloseButton') + '">✕</button>' +
        '<h3>' + t('modelsInstallTitle') + '</h3><p class="msub">' + t('modelsInstallDescription') + '</p>' +
        '<div class="cat-help">💡 <b>' + t('modelsInstallHelpTitle') + '</b> — ' + t('modelsInstallHelpContent') + '</div>' +
        '<div class="cat-pick"><label>' + t('modelsInstallCategoryLabel') + '</label><select id="catSel">' +
          cats.map(c => '<option value="' + c + '">' + c + '</option>').join('') + '</select></div>' +
        '<div class="wiz-list" id="catList"></div>' +
        '<div class="cat-manual"><div class="cm-label">' + t('modelsInstallManualLabel') + ' <span>' + t('modelsInstallManualSubtext') + '</span></div>' +
          '<div class="cm-row"><input class="cx-input" id="catManual" placeholder="' + t('modelsInstallManualPlaceholder') + '"><button class="btn btn--md btn--secondary" id="catManualBtn">' + t('modelsInstallManualButton') + '</button></div>' +
          '<div class="cat-help" style="margin-top:8px">💡 <b>' + t('modelsInstallHuggingfaceTitle') + '</b> — ' + t('modelsInstallHuggingfaceContent') + '</div></div>';
      // 선택된 분류의 모델만 렌더(분류 변경 시 재호출)
      const listEl = card.querySelector('#catList');
      const renderList = (c) => {
        listEl.innerHTML = cat.filter(m => m.cat === c).map(m => '<button class="wiz-opt" data-cat="' + m.name + '"><span class="ob"><b>' + m.name + '</b><p>' + m.desc + '</p></span><span class="osize">' + m.size + '</span></button>').join('');
        listEl.querySelectorAll('[data-cat]').forEach(b => b.onclick = () => startPull(b.dataset.cat));
      };
      const catSel = card.querySelector('#catSel');
      catSel.onchange = () => renderList(catSel.value);
      renderList(cats[0]);
      const inp = card.querySelector('#catManual');
      const go = () => {
        const v = (inp.value || '').trim();
        if (!v) { toast(t('modelsEmptyModelNameToast'), { type: 'info' }); return; }
        if (!/^[a-z0-9][a-z0-9._:/-]*$/i.test(v)) { toast(t('modelsInvalidModelNameToast'), { type: 'info', sub: t('modelsInvalidModelNameSub') }); return; }
        startPull(v);
      };
      card.querySelector('#catManualBtn').onclick = go;
      inp.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
      card.querySelectorAll('[data-close]').forEach(b => b.onclick = () => { layer.hidden = true; });
    });

    // 진입 시 로드
    onLangChange(() => renderAll());
    (async () => {
      const d = await api.getModels();
      models = d.models; defaultModel = d.defaultModel;
      renderAll();
    })();

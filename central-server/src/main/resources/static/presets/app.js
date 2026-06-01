const $ = (id) => document.getElementById(id);
let pendingImport = null;
let selectedPresetId = null;

function esc(value) {
  return String(value ?? "").replace(/[&<>'"]/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[ch]);
}

async function json(url, options = {}) {
  const res = await fetch(url, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });
  if (!res.ok) throw new Error(`${res.status} ${url}`);
  return res.json();
}

function anonUserId() {
  const key = "nyassistantPresetAnonUserId";
  const existing = localStorage.getItem(key);
  if (existing) return Number(existing);
  const id = Math.floor(100000000 + Math.random() * 800000000);
  localStorage.setItem(key, String(id));
  return id;
}

function catalogUrl() {
  const params = new URLSearchParams();
  const query = $("query").value.trim();
  const category = $("category").value.trim();
  const sort = $("sort").value || "popular";
  const limit = Math.min(100, Math.max(1, Number($("limit").value || 24)));
  if (query) params.set("query", query);
  if (category) params.set("category", category);
  params.set("sort", sort);
  params.set("limit", String(limit));
  return `/api/ai-network/presets/catalog?${params.toString()}`;
}

function renderCatalog(presets) {
  if (!presets.length) {
    $("catalog").innerHTML = `<article class="card"><h3>아직 공개된 프리셋이 없습니다.</h3><p>대시보드에서 서버 프리셋을 게시하면 여기에 나타납니다.</p></article>`;
    return;
  }
  $("catalog").innerHTML = presets.map((preset) => `
    <article class="card">
      <div class="meta">
        <span class="badge">#${esc(preset.id)}</span>
        <span class="badge">${esc(preset.category || "general")}</span>
        <span class="badge">${esc(preset.responseMode || "balanced")}</span>
      </div>
      <h3>${esc(preset.title)}</h3>
      <p>${esc(preset.description || "설명 없음")}</p>
      <div class="meta">
        <span class="badge">좋아요 ${esc(preset.likeCount)}</span>
        <span class="badge">가져오기 ${esc(preset.importCount)}</span>
        <span class="badge">${esc(preset.publisherLabel || "공유자")}</span>
      </div>
      <div class="card-actions">
        <button data-action="preview" data-id="${esc(preset.id)}">미리보기</button>
        <button class="secondary" data-action="like" data-id="${esc(preset.id)}">따봉</button>
      </div>
    </article>
  `).join("");
}

async function refreshCatalog() {
  $("status").textContent = "프리셋을 불러오는 중입니다.";
  try {
    const data = await json(catalogUrl());
    renderCatalog(data.presets || []);
    $("status").textContent = `웹 카탈로그 ${data.presets?.length || 0}개 · ${data.sort || "popular"}`;
  } catch (e) {
    $("status").textContent = `프리셋 로딩 실패: ${e.message}`;
  }
}

function targetIds() {
  const guildId = $("guildId").value.trim();
  const channelId = $("channelId").value.trim();
  if (!/^\d+$/.test(guildId) || !/^\d+$/.test(channelId)) return null;
  return { guildId: Number(guildId), channelId: Number(channelId) };
}

function renderPreview(detail, preview) {
  const preset = detail.preset || detail;
  const lines = [
    `프리셋: ${preset.title || preset.name || "이름 없음"} (#${selectedPresetId})`,
    preset.description ? `설명: ${preset.description}` : null,
    preset.category ? `카테고리: ${preset.category}` : null,
    preset.responseMode ? `응답 모드: ${preset.responseMode}` : null,
  ].filter(Boolean);
  if (preview) {
    const conflicts = preview.conflicts || [];
    lines.push(
      "",
      "가져오면 일어나는 일",
      preview.willApplyToChannel ? "- 현재 채널 AI에 적용됨" : "- 서버 프리셋 복사본만 생성됨",
      preview.willOverwriteChannelAi ? "- 기존 채널 AI 설정을 덮어쓸 수 있음" : "- 기존 채널 AI 덮어쓰기 없음",
      preview.willCreateApprovalProposal ? "- 고위험 변경이라 승인 제안 생성" : "- 즉시 적용 가능",
      "",
      "확인할 점",
      ...(conflicts.length ? conflicts.map((c) => `- [${c.severity}] ${c.code}: ${c.message}`) : ["- 충돌 없음"]),
    );
  } else {
    lines.push("", "서버 ID와 채널 ID를 입력하면 적용 전 충돌 미리보기를 볼 수 있습니다.");
  }
  $("preview").textContent = lines.join("\n");
}

async function previewPreset(id) {
  selectedPresetId = Number(id);
  pendingImport = null;
  $("confirmImport").disabled = true;
  $("likePreset").disabled = false;
  $("result").textContent = "";
  try {
    const detail = await json(`/api/ai-network/presets/catalog/${selectedPresetId}`);
    const target = targetIds();
    if (!target) {
      renderPreview(detail, null);
      return;
    }
    const preview = await json(`/api/ai-network/presets/published/${selectedPresetId}/import-preview`, {
      method: "POST",
      body: JSON.stringify({ targetGuildId: target.guildId, targetChannelId: target.channelId }),
    });
    pendingImport = { publishedPresetId: selectedPresetId, ...target, conflictCount: preview.preview?.conflicts?.length || 0 };
    renderPreview(detail, preview.preview);
    $("confirmImport").disabled = false;
  } catch (e) {
    $("preview").textContent = `미리보기 실패: ${e.message}`;
  }
}

async function likePreset(id = selectedPresetId) {
  if (!id) {
    $("result").textContent = "먼저 프리셋을 선택하세요.";
    return;
  }
  try {
    const data = await json(`/api/ai-network/presets/published/${id}/like`, {
      method: "POST",
      body: JSON.stringify({ userId: anonUserId() }),
    });
    $("result").textContent = `따봉 반영 완료 · 좋아요 ${data.likeCount}`;
    await refreshCatalog();
  } catch (e) {
    $("result").textContent = `따봉 실패: ${e.message}`;
  }
}

async function confirmImport() {
  if (!pendingImport) {
    $("result").textContent = "서버 ID와 채널 ID를 입력한 뒤 미리보기를 먼저 실행하세요.";
    return;
  }
  try {
    const data = await json(`/api/ai-network/presets/published/${pendingImport.publishedPresetId}/import`, {
      method: "POST",
      body: JSON.stringify({
        targetGuildId: pendingImport.guildId,
        targetChannelId: pendingImport.channelId,
        confirmConflicts: true,
      }),
    });
    $("result").textContent = `가져오기 완료 · ${data.status} · channelAi=${data.createdChannelAiId || "-"}`;
    $("confirmImport").disabled = true;
    pendingImport = null;
    await refreshCatalog();
  } catch (e) {
    $("result").textContent = `가져오기 실패: ${e.message}`;
  }
}

$("search").addEventListener("click", refreshCatalog);
$("confirmImport").addEventListener("click", confirmImport);
$("likePreset").addEventListener("click", () => likePreset());
$("catalog").addEventListener("click", (event) => {
  const button = event.target.closest("button[data-action]");
  if (!button) return;
  if (button.dataset.action === "preview") previewPreset(button.dataset.id);
  if (button.dataset.action === "like") likePreset(Number(button.dataset.id));
});
["query", "category"].forEach((id) => $(id).addEventListener("keydown", (event) => {
  if (event.key === "Enter") refreshCatalog();
}));
refreshCatalog();

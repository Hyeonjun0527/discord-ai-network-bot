const $ = (id) => document.getElementById(id);
const ADMIN_TOKEN_STORAGE_KEY = "nyassistantPresetDashboardAdminToken";
let pendingImport = null;
let selectedPresetId = null;

function esc(value) {
  return String(value ?? "").replace(/[&<>'"]/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[ch]);
}

function currentAdminToken() {
  return ($("adminToken")?.value || sessionStorage.getItem(ADMIN_TOKEN_STORAGE_KEY) || "").trim();
}

function adminHeaders() {
  const token = currentAdminToken();
  return token ? { "X-Dashboard-Admin-Token": token } : {};
}

function updateAdminTokenStatus() {
  const hasToken = Boolean(currentAdminToken());
  $("adminTokenStatus").textContent = hasToken
    ? "현재 브라우저 세션에 관리자 토큰이 저장되어 있습니다. 목록은 공개, 가져오기만 토큰을 사용합니다."
    : "목록 확인은 공개이고, 가져오기/미리보기만 관리자 토큰이 필요합니다.";
}

function saveAdminToken() {
  const token = ($("adminToken").value || "").trim();
  if (token) {
    sessionStorage.setItem(ADMIN_TOKEN_STORAGE_KEY, token);
  } else {
    sessionStorage.removeItem(ADMIN_TOKEN_STORAGE_KEY);
  }
  updateAdminTokenStatus();
}

function requireAdminTokenMessage() {
  return "가져오기 미리보기에는 관리자 토큰이 필요합니다. 토큰을 입력하고 [토큰 저장]을 누른 뒤 다시 미리보기하세요.";
}

async function json(url, options = {}) {
  const { admin = false, headers = {}, ...fetchOptions } = options;
  const res = await fetch(url, {
    headers: { "Content-Type": "application/json", ...(admin ? adminHeaders() : {}), ...headers },
    ...fetchOptions,
  });
  if (!res.ok) {
    let message = `${res.status} ${url}`;
    try {
      const body = await res.json();
      message = body.message || body.error || message;
    } catch (_) {
      message = `${res.status} ${url}`;
    }
    throw new Error(message);
  }
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
        <button class="secondary" data-action="report" data-id="${esc(preset.id)}">신고</button>
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
  return { guildId, channelId };
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
  $("reportPreset").disabled = false;
  $("result").textContent = "";
  try {
    const detail = await json(`/api/ai-network/presets/catalog/${selectedPresetId}`);
    const target = targetIds();
    if (!target) {
      renderPreview(detail, null);
      return;
    }
    if (!currentAdminToken()) {
      renderPreview(detail, null);
      $("result").textContent = requireAdminTokenMessage();
      return;
    }
    const preview = await json(`/api/ai-network/presets/published/${selectedPresetId}/import-preview`, {
      admin: true,
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

async function reportPreset(id = selectedPresetId) {
  if (!id) {
    $("result").textContent = "먼저 프리셋을 선택하세요.";
    return;
  }
  const reason = window.prompt("신고 사유를 간단히 적어주세요. 민감정보는 입력하지 마세요.");
  if (!reason || !reason.trim()) {
    $("result").textContent = "신고가 취소되었습니다.";
    return;
  }
  try {
    const data = await json(`/api/ai-network/presets/published/${id}/report`, {
      method: "POST",
      body: JSON.stringify({ reporterUserId: anonUserId(), reason: reason.trim() }),
    });
    $("result").textContent = `신고 접수 완료 · report ${data.id} · ${data.status}`;
    if (Number(id) === selectedPresetId) {
      $("confirmImport").disabled = true;
      $("likePreset").disabled = true;
      $("reportPreset").disabled = true;
      pendingImport = null;
    }
    await refreshCatalog();
  } catch (e) {
    $("result").textContent = `신고 실패: ${e.message}`;
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
      admin: true,
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
$("saveAdminToken").addEventListener("click", saveAdminToken);
$("adminToken").addEventListener("keydown", (event) => {
  if (event.key === "Enter") saveAdminToken();
});
$("confirmImport").addEventListener("click", confirmImport);
$("likePreset").addEventListener("click", () => likePreset());
$("reportPreset").addEventListener("click", () => reportPreset());
$("catalog").addEventListener("click", (event) => {
  const button = event.target.closest("button[data-action]");
  if (!button) return;
  if (button.dataset.action === "preview") previewPreset(button.dataset.id);
  if (button.dataset.action === "like") likePreset(Number(button.dataset.id));
  if (button.dataset.action === "report") reportPreset(Number(button.dataset.id));
});
["query", "category"].forEach((id) => $(id).addEventListener("keydown", (event) => {
  if (event.key === "Enter") refreshCatalog();
}));
$("adminToken").value = sessionStorage.getItem(ADMIN_TOKEN_STORAGE_KEY) || "";
updateAdminTokenStatus();
refreshCatalog();

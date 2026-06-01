const $ = (id) => document.getElementById(id);
const ADMIN_TOKEN_STORAGE_KEY = "nyassistantPresetDashboardAdminToken";
const LIKED_PRESETS_STORAGE_KEY = "nyassistantPresetLikedPresets";
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

function likedPresetIds() {
  try {
    return new Set(JSON.parse(localStorage.getItem(LIKED_PRESETS_STORAGE_KEY) || "[]").map(String));
  } catch (_) {
    return new Set();
  }
}

function rememberLikedPreset(id) {
  const ids = likedPresetIds();
  ids.add(String(id));
  localStorage.setItem(LIKED_PRESETS_STORAGE_KEY, JSON.stringify([...ids]));
}

function forgetLikedPreset(id) {
  const ids = likedPresetIds();
  ids.delete(String(id));
  localStorage.setItem(LIKED_PRESETS_STORAGE_KEY, JSON.stringify([...ids]));
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

function recommendedUrl() {
  const params = new URLSearchParams();
  const category = $("category").value.trim();
  if (category) params.set("category", category);
  params.set("limit", "8");
  return `/api/ai-network/presets/catalog/recommended?${params.toString()}`;
}

function renderCatalog(presets) {
  if (!presets.length) {
    $("catalog").innerHTML = `<article class="card"><h3>아직 공개된 프리셋이 없습니다.</h3><p>대시보드에서 서버 프리셋을 게시하면 여기에 나타납니다.</p></article>`;
    return;
  }
  const liked = likedPresetIds();
  $("catalog").innerHTML = presets.map((preset) => `
    <article class="card">
      <div class="meta">
        <span class="badge">#${esc(preset.id)}</span>
        <span class="badge">${esc(preset.category || "general")}</span>
        <span class="badge">${esc(preset.responseMode || "balanced")}</span>
        ${(preset.tags || []).slice(0, 3).map((tag) => `<span class="badge">${esc(tag)}</span>`).join("")}
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
        <button class="secondary" data-action="share" data-id="${esc(preset.id)}" data-slug="${esc(preset.slug || preset.id)}">공유</button>
        ${liked.has(String(preset.id))
          ? `<button class="secondary" data-action="unlike" data-id="${esc(preset.id)}">추천 취소</button>`
          : `<button class="secondary" data-action="like" data-id="${esc(preset.id)}">따봉</button>`}
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

function renderRecommendations(recommendations) {
  const items = (recommendations || []).slice(0, 8);
  if (!items.length) {
    $("recommendations").innerHTML = `<span class="badge">추천할 공개 프리셋이 아직 없습니다.</span>`;
    return;
  }
  $("recommendations").innerHTML = items.map((item) => {
    const preset = item.preset || item;
    return `<button type="button" data-action="preview" data-id="${esc(preset.id)}">
      ${esc(preset.title || "프리셋")} · 점수 ${esc(item.score ?? "-")}
    </button>`;
  }).join("");
}

function renderFacets(facets) {
  const categories = (facets?.categories || []).slice(0, 8);
  const tags = (facets?.tags || []).slice(0, 12);
  const buttons = [
    ...categories.map((facet) =>
      `<button type="button" data-facet-type="category" data-facet-value="${esc(facet.value)}">#${esc(facet.value)} ${esc(facet.count)}</button>`,
    ),
    ...tags.map((facet) =>
      `<button type="button" data-facet-type="tag" data-facet-value="${esc(facet.value)}">✦ ${esc(facet.value)} ${esc(facet.count)}</button>`,
    ),
  ];
  $("facets").innerHTML = buttons.length ? buttons.join("") : `<span class="badge">아직 집계된 카테고리/태그가 없습니다.</span>`;
}

async function refreshDiscovery() {
  try {
    const [recommended, facets] = await Promise.all([
      json(recommendedUrl()),
      json("/api/ai-network/presets/catalog/facets"),
    ]);
    renderRecommendations(recommended.recommendations || []);
    renderFacets(facets.facets || {});
  } catch (e) {
    $("recommendations").innerHTML = `<span class="badge">추천 로딩 실패: ${esc(e.message)}</span>`;
    $("facets").innerHTML = `<span class="badge">탐색 필터 로딩 실패</span>`;
  }
}

function renderImportHistory(imports) {
  const items = (imports || []).slice(0, 10);
  if (!items.length) {
    $("importHistory").innerHTML = "<li>아직 가져오기 기록이 없습니다.</li>";
    return;
  }
  $("importHistory").innerHTML = items.map((item) => `
    <li>
      <strong>published #${esc(item.publishedPresetId)} → preset #${esc(item.importedPresetId || "-")}</strong><br />
      채널 ${esc(item.targetChannelId || "서버 기본")} · source revision ${esc(item.sourceRevisionId || "-")} · ${esc(item.status || "imported")}
    </li>
  `).join("");
}

function targetIds() {
  const guildId = $("guildId").value.trim();
  const channelId = $("channelId").value.trim();
  if (!/^\d+$/.test(guildId) || !/^\d+$/.test(channelId)) return null;
  return { guildId, channelId };
}

function importHistoryTarget() {
  const guildId = $("guildId").value.trim();
  const channelId = $("channelId").value.trim();
  if (!/^\d+$/.test(guildId)) return null;
  return { guildId, channelId: /^\d+$/.test(channelId) ? channelId : null };
}

async function refreshImportHistory() {
  const target = importHistoryTarget();
  if (!target) {
    $("importHistory").innerHTML = "<li>서버 ID를 입력하면 가져오기 기록을 볼 수 있습니다.</li>";
    return;
  }
  if (!currentAdminToken()) {
    $("importHistory").innerHTML = "<li>가져오기 기록을 보려면 관리자 토큰을 저장하세요.</li>";
    return;
  }
  const params = new URLSearchParams();
  if (target.channelId) params.set("channelId", target.channelId);
  try {
    const result = await json(`/api/ai-network/presets/guilds/${target.guildId}/imports?${params.toString()}`, { admin: true });
    renderImportHistory(result.imports || []);
  } catch (e) {
    $("importHistory").innerHTML = `<li>가져오기 기록 로딩 실패: ${esc(e.message)}</li>`;
  }
}

function normalizePresetDetail(detail) {
  const root = detail.preset || detail;
  const published = root.published || root;
  const behavior = root.behavior || {};
  return { published, behavior };
}

function optionalLine(label, value) {
  return value ? `${label}: ${value}` : null;
}

function listLine(label, values) {
  const list = Array.isArray(values) ? values.filter(Boolean) : [];
  return list.length ? `${label}: ${list.join(", ")}` : null;
}

function renderPreview(detail, preview) {
  const { published, behavior } = normalizePresetDetail(detail);
  const lines = [
    `프리셋: ${published.title || published.name || "이름 없음"} (#${selectedPresetId})`,
    optionalLine("설명", published.description),
    optionalLine("카테고리", published.category),
    optionalLine("목적", published.purpose || behavior.purpose),
    optionalLine("말투", published.tone || behavior.tone),
    optionalLine("답변 길이", behavior.answerLength),
    optionalLine("안전 등급", published.safetyLevel || behavior.safetyLevel),
    optionalLine("응답 모드", published.responseMode || behavior.responseMode),
    optionalLine("선호 모델", published.preferredModel || behavior.preferredModel),
    optionalLine("최소 품질", published.minQualityTier || behavior.minQualityTier),
    behavior.maxCandidates ? `Provider 후보: ${behavior.maxCandidates}` : null,
    listLine("태그", published.tags || behavior.tags),
    listLine("Provider 태그", behavior.providerTagFilter),
    listLine("필요 지식 슬롯", behavior.knowledgeSlotNames),
    optionalLine("지식 등록 안내", behavior.knowledgeGuide),
    listLine("질문 예시", behavior.exampleQuestions),
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

async function previewPreset(locator) {
  const presetLocator = String(locator || "").trim();
  selectedPresetId = null;
  pendingImport = null;
  $("confirmImport").disabled = true;
  $("copyDiscordImport").disabled = true;
  $("likePreset").disabled = false;
  $("unlikePreset").disabled = false;
  $("reportPreset").disabled = false;
  $("result").textContent = "";
  try {
    const detailUrl = /^\d+$/.test(presetLocator)
      ? `/api/ai-network/presets/catalog/${presetLocator}`
      : `/api/ai-network/presets/catalog/slug/${encodeURIComponent(presetLocator)}`;
    const detail = await json(detailUrl);
    const { published } = normalizePresetDetail(detail);
    selectedPresetId = Number(published.id || presetLocator);
    if (!Number.isFinite(selectedPresetId)) throw new Error("프리셋 ID를 확인할 수 없습니다.");
    $("copyDiscordImport").disabled = false;
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
    $("copyDiscordImport").disabled = true;
    $("unlikePreset").disabled = true;
    $("preview").textContent = `미리보기 실패: ${e.message}`;
  }
}

async function copyDiscordImportCommand() {
  if (!selectedPresetId) {
    $("result").textContent = "먼저 프리셋을 선택하세요.";
    return;
  }
  const command = `/ai-preset-import published-id:${selectedPresetId} confirm-conflicts:false`;
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(command);
      $("result").textContent = `Discord에서 붙여넣을 가져오기 명령을 복사했습니다: ${command}`;
    } else {
      window.prompt("Discord에서 실행할 명령을 복사하세요.", command);
      $("result").textContent = "Discord 가져오기 명령을 열었습니다.";
    }
  } catch (e) {
    window.prompt("Discord에서 실행할 명령을 복사하세요.", command);
    $("result").textContent = `자동 복사 실패: ${e.message}`;
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
      $("copyDiscordImport").disabled = true;
      $("likePreset").disabled = true;
      $("unlikePreset").disabled = true;
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
    rememberLikedPreset(id);
    $("result").textContent = `따봉 반영 완료 · 좋아요 ${data.likeCount}`;
    await refreshCatalog();
  } catch (e) {
    $("result").textContent = `따봉 실패: ${e.message}`;
  }
}

async function unlikePreset(id = selectedPresetId) {
  if (!id) {
    $("result").textContent = "먼저 프리셋을 선택하세요.";
    return;
  }
  try {
    const data = await json(`/api/ai-network/presets/published/${id}/like`, {
      method: "DELETE",
      body: JSON.stringify({ userId: anonUserId() }),
    });
    forgetLikedPreset(id);
    $("result").textContent = `추천 취소 완료 · 좋아요 ${data.likeCount}`;
    await refreshCatalog();
  } catch (e) {
    $("result").textContent = `추천 취소 실패: ${e.message}`;
  }
}

async function sharePreset(slugOrId) {
  const locator = String(slugOrId || "").trim();
  if (!locator) {
    $("result").textContent = "공유할 프리셋을 찾지 못했습니다.";
    return;
  }
  const shareUrl = `${window.location.origin}/presets?preset=${encodeURIComponent(locator)}`;
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(shareUrl);
      $("result").textContent = `공유 링크를 복사했습니다: ${shareUrl}`;
    } else {
      window.prompt("공유 링크를 복사하세요.", shareUrl);
      $("result").textContent = "공유 링크를 열었습니다.";
    }
  } catch (e) {
    window.prompt("공유 링크를 복사하세요.", shareUrl);
    $("result").textContent = `자동 복사 실패: ${e.message}`;
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
    $("result").textContent =
      `가져오기 완료 · ${data.status} · sourceRevision=${data.sourceRevisionId || "-"} · channelAi=${data.createdChannelAiId || "-"}`;
    $("confirmImport").disabled = true;
    pendingImport = null;
    await refreshCatalog();
    await refreshImportHistory();
  } catch (e) {
    $("result").textContent = `가져오기 실패: ${e.message}`;
  }
}

$("search").addEventListener("click", () => {
  refreshCatalog();
  refreshDiscovery();
});
$("loadImportHistory").addEventListener("click", refreshImportHistory);
$("saveAdminToken").addEventListener("click", saveAdminToken);
$("adminToken").addEventListener("keydown", (event) => {
  if (event.key === "Enter") saveAdminToken();
});
$("confirmImport").addEventListener("click", confirmImport);
$("copyDiscordImport").addEventListener("click", copyDiscordImportCommand);
$("likePreset").addEventListener("click", () => likePreset());
$("unlikePreset").addEventListener("click", () => unlikePreset());
$("reportPreset").addEventListener("click", () => reportPreset());
$("catalog").addEventListener("click", (event) => {
  const button = event.target.closest("button[data-action]");
  if (!button) return;
  if (button.dataset.action === "preview") previewPreset(button.dataset.id);
  if (button.dataset.action === "share") sharePreset(button.dataset.slug || button.dataset.id);
  if (button.dataset.action === "like") likePreset(Number(button.dataset.id));
  if (button.dataset.action === "unlike") unlikePreset(Number(button.dataset.id));
  if (button.dataset.action === "report") reportPreset(Number(button.dataset.id));
});
$("recommendations").addEventListener("click", (event) => {
  const button = event.target.closest("button[data-action='preview']");
  if (button) previewPreset(button.dataset.id);
});
$("facets").addEventListener("click", (event) => {
  const button = event.target.closest("button[data-facet-type]");
  if (!button) return;
  if (button.dataset.facetType === "category") {
    $("category").value = button.dataset.facetValue || "";
  } else {
    $("query").value = button.dataset.facetValue || "";
  }
  refreshCatalog();
  refreshDiscovery();
});
["query", "category"].forEach((id) => $(id).addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    refreshCatalog();
    refreshDiscovery();
  }
}));
$("adminToken").value = sessionStorage.getItem(ADMIN_TOKEN_STORAGE_KEY) || "";
updateAdminTokenStatus();
const initialPresetLocator = new URLSearchParams(window.location.search).get("preset");
Promise.all([refreshCatalog(), refreshDiscovery()]).then(() => {
  if (initialPresetLocator) previewPreset(initialPresetLocator);
});

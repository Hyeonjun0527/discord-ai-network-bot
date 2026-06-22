// 대시보드 앱(차수 14). 빌드 불필요 바닐라 JS. 읽기전용 API 폴링 + 렌더.
"use strict";

const $ = (id) => document.getElementById(id);
const ADMIN_TOKEN_STORAGE_KEY = "nexa.dashboardAdminToken";
let pendingPresetImport = null;

function esc(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function currentDashboardAdminToken() {
  return ($("dashboardAdminToken")?.value || "").trim();
}

function apiHeaders(extra = {}) {
  const token = currentDashboardAdminToken();
  const headers = { Accept: "application/json", ...extra };
  if (token) headers["X-Dashboard-Admin-Token"] = token;
  return headers;
}

function updateDashboardAdminTokenStatus() {
  const token = currentDashboardAdminToken();
  $("dashboardAdminTokenStatus").textContent = token
    ? "관리자 토큰이 설정되어 admin view/쓰기 API 요청에 헤더를 붙입니다."
    : "토큰 미설정: 공개 프리셋 목록만 볼 수 있고, 서버 대시보드 읽기/쓰기는 관리자 토큰이 필요합니다.";
}

function loadDashboardAdminToken() {
  const saved = sessionStorage.getItem(ADMIN_TOKEN_STORAGE_KEY) || "";
  $("dashboardAdminToken").value = saved;
  updateDashboardAdminTokenStatus();
}

function saveDashboardAdminToken() {
  const token = currentDashboardAdminToken();
  if (token) sessionStorage.setItem(ADMIN_TOKEN_STORAGE_KEY, token);
  else sessionStorage.removeItem(ADMIN_TOKEN_STORAGE_KEY);
  updateDashboardAdminTokenStatus();
}

function clearDashboardAdminToken() {
  sessionStorage.removeItem(ADMIN_TOKEN_STORAGE_KEY);
  $("dashboardAdminToken").value = "";
  updateDashboardAdminTokenStatus();
}

// 통일 에러 모델 {success,status,error:{code,message},requestId} 와 옛/스프링 기본 바디를 모두 읽어
// 사람이 읽을 메시지를 뽑는다. 백엔드가 보낸 code/message/requestId 를 버리지 않는 것이 프론트의 역할.
async function apiErrorMessage(res, fallback) {
  try {
    const body = await res.json();
    const e = body && body.error;
    let msg;
    if (e && typeof e === "object") msg = e.message || e.code; // 통일 모델(중첩)
    else if (typeof e === "string") msg = body.message || e; // 옛/스프링 기본(top-level)
    else if (body && body.message) msg = body.message;
    msg = msg || fallback;
    const rid = body && body.requestId;
    return rid ? `${msg} (요청 ID: ${rid})` : msg;
  } catch (_) {
    return fallback;
  }
}

async function getJson(url) {
  const res = await fetch(url, { headers: apiHeaders() });
  if (!res.ok) throw new Error(await apiErrorMessage(res, `${res.status} ${url}`));
  return res.json();
}

async function postJson(url, body) {
  const res = await fetch(url, {
    method: "POST",
    headers: apiHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await apiErrorMessage(res, `${res.status} ${url}`));
  return res.json();
}

async function putJson(url, body) {
  const res = await fetch(url, {
    method: "PUT",
    headers: apiHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await apiErrorMessage(res, `${res.status} ${url}`));
  return res.json();
}

async function deleteJson(url) {
  const res = await fetch(url, { method: "DELETE", headers: apiHeaders() });
  if (!res.ok) throw new Error(await apiErrorMessage(res, `${res.status} ${url}`));
  return res.json();
}

// 풀 전역(#199) — 5초 폴링(#205)
async function refreshPool() {
  const badge = $("status");
  try {
    const pool = await getJson("/api/metrics/pool");
    $("activeProviders").textContent = pool.activeProviders ?? 0;
    $("inFlightTotal").textContent = pool.inFlightTotal ?? 0;
    $("guildCount").textContent = Object.keys(pool.guildPoolSizes || {}).length;
    badge.textContent = "온라인";
    badge.className = "badge ok";
    const ov = $("ovNetworkStatus");
    if (ov) ov.textContent = (pool.inFlightTotal ?? 0) > 0 ? "처리 중" : "정상";
  } catch (e) {
    badge.textContent = "연결 실패";
    badge.className = "badge bad";
    const ov = $("ovNetworkStatus");
    if (ov) ov.textContent = "점검";
  }
}

async function refreshLicenseFunnel() {
  try {
    const f = await getJson("/api/ai-network/license/funnel?audience=admin");
    renderList("licenseFunnel", [
      ["체험 중", f.trial ?? 0],
      ["체험 만료", f.expired ?? 0],
      ["구매 완료", f.licensed ?? 0],
      ["이벤트 무료", f.eventFree ?? 0],
      ["정지됨", f.revoked ?? 0],
      ["환불 이력", f.refunded ?? 0],
    ], "라이선스 지표 없음", ([label, value]) => `<li><strong>${esc(label)}</strong><span>${esc(value)}명</span></li>`);
  } catch (e) {
    renderList("licenseFunnel", [], "라이선스 지표를 보려면 관리자 토큰 또는 OAuth 로그인이 필요합니다.");
  }
}

// Overview 도넛: 참여 PC 상태 분포(정상/주의/보호)를 conic-gradient 로 그린다.
function renderProviderDonut(providers) {
  const list = providers || [];
  const total = list.length;
  let warn = 0;
  let protectedCount = 0;
  for (const p of list) {
    const state = String(p.state || "").toLowerCase();
    const risk = String(p.overloadRisk || "").toLowerCase();
    if (state.includes("protect") || risk === "critical") protectedCount += 1;
    else if (risk === "high" || state.includes("degrad")) warn += 1;
  }
  const normal = Math.max(0, total - warn - protectedCount);
  const donut = $("providerDonut");
  if (donut && total > 0) {
    const p1 = Math.round((normal / total) * 100);
    const p2 = Math.round(((normal + warn) / total) * 100);
    donut.style.setProperty("--p1", `${p1}%`);
    donut.style.setProperty("--p2", `${p2}%`);
    donut.style.setProperty("--p3", "100%");
  }
  if ($("providerDonutTotal")) $("providerDonutTotal").textContent = String(total);
  if ($("legendNormal")) $("legendNormal").textContent = String(normal);
  if ($("legendWarn")) $("legendWarn").textContent = String(warn);
  if ($("legendProtected")) $("legendProtected").textContent = String(protectedCount);
}

function renderTrend(series) {
  const box = $("trend");
  box.innerHTML = "";
  const max = Math.max(1, ...series.map((d) => d.count));
  for (const d of series) {
    const bar = document.createElement("div");
    bar.className = "bar";
    bar.style.height = `${Math.round((d.count / max) * 100)}%`;
    bar.title = `${d.date}: ${d.count}건`;
    const label = document.createElement("span");
    label.textContent = (d.date || "").slice(5, 10); // MM-DD
    bar.appendChild(label);
    box.appendChild(bar);
  }
}

function renderList(id, items, emptyText, renderer) {
  const box = $(id);
  box.innerHTML = "";
  if (!items || items.length === 0) {
    box.innerHTML = `<li class="empty">${esc(emptyText)}</li>`;
    return;
  }
  box.innerHTML = items.map(renderer).join("");
}

function setText(id, value) {
  const el = $(id);
  if (el) el.textContent = String(value ?? "");
}

function setHtml(id, html) {
  const el = $(id);
  if (el) el.innerHTML = html || "";
}

function resultCard(title, body, kind = "") {
  return `<article class="result-card ${esc(kind)}"><strong>${esc(title)}</strong><span>${body}</span></article>`;
}

function openFoldFor(target) {
  const fold = target?.closest?.(".ops-fold");
  if (fold) fold.open = true;
}

function focusInside(selector) {
  const target = document.querySelector(selector);
  if (!target) return;
  openFoldFor(target);
  target.scrollIntoView({ behavior: "smooth", block: "center" });
  const focusTarget = target.matches("input, select, textarea, button")
    ? target
    : target.querySelector("input, select, textarea, button");
  if (focusTarget) focusTarget.focus({ preventScroll: true });
}

function fillSelect(id, options) {
  const select = $(id);
  select.innerHTML = (options || []).map((o) =>
    `<option value="${esc(o.key)}" title="${esc(o.description)}">${esc(o.label)}</option>`,
  ).join("");
}

async function loadWizardOptions() {
  try {
    const options = await getJson("/api/ai-network/channel-ai/wizard/options");
    fillSelect("wizardJob", options.jobs);
    fillSelect("wizardTone", options.tones);
    fillSelect("wizardLength", options.answerLengths);
    // 프리셋 말투도 같은 알려진 옵션에서 선택(자유입력 제거).
    if ($("presetTone")) fillSelect("presetTone", options.tones);
    const firstJob = options.jobs?.[0];
    if (firstJob?.recommendedName && !$("wizardName").value) $("wizardName").value = firstJob.recommendedName;
    $("wizardPreview").textContent = (options.safetyRules || []).map((rule) => `- ${rule}`).join("\n");
  } catch (e) {
    $("wizardPreview").textContent = `마법사 옵션 로딩 실패: ${e.message}`;
  }
}

function wizardPayload() {
  return {
    name: $("wizardName").value.trim(),
    job: $("wizardJob").value,
    tone: $("wizardTone").value,
    answerLength: $("wizardLength").value,
    requireApproval: $("wizardApproval").checked,
  };
}

function numericValue(id) {
  const value = $(id).value.trim();
  return /^\d+$/.test(value) ? Number(value) : null;
}

function csvInput(id) {
  return $(id).value.split(",").map((v) => v.trim()).filter(Boolean);
}

const CHANNEL_ID_FIELDS = ["wizardChannelId", "routingChannelId", "knowledgeChannelId", "qualityChannelId", "multiChannelId"];

function currentChannelIdValue() {
  return CHANNEL_ID_FIELDS.map((id) => $(id)?.value?.trim() || "").find((value) => /^\d+$/.test(value)) || "";
}

function presetCatalogUrl() {
  const params = new URLSearchParams();
  const query = $("presetCatalogQuery").value.trim();
  const category = $("presetCatalogCategory").value.trim();
  const sort = $("presetCatalogSort").value || "popular";
  const limit = Number($("presetCatalogLimit").value || "20");
  if (query) params.set("query", query);
  if (category) params.set("category", category);
  params.set("sort", sort);
  params.set("limit", String(Math.min(100, Math.max(1, limit || 20))));
  return `/api/ai-network/presets/catalog?${params.toString()}`;
}

async function draftChannelAi() {
  try {
    const draft = await postJson("/api/ai-network/channel-ai/wizard/draft", wizardPayload());
    $("wizardName").value = $("wizardName").value.trim() || draft.name || "";
    $("wizardPreview").textContent = [
      draft.preview,
      "",
      "[AI 헌법]",
      draft.constitution,
    ].join("\n");
  } catch (e) {
    $("wizardPreview").textContent = `미리보기 실패: ${e.message}`;
  }
}

async function createChannelAi() {
  const gid = $("guildId").value.trim();
  const channelId = $("wizardChannelId").value.trim();
  if (!/^\d+$/.test(gid) || !/^\d+$/.test(channelId)) {
    $("wizardPreview").textContent = "서버 ID와 채널 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/channel-ai/${gid}/${channelId}/wizard`, wizardPayload());
    $("wizardPreview").textContent =
      `저장 완료: ${result.status} · channelAi=${result.channelAiId} · version=${result.version}` +
      (result.approvalReason ? `\n승인 사유: ${result.approvalReason}` : "");
    await loadGuild();
  } catch (e) {
    $("wizardPreview").textContent = `저장 실패: ${e.message}`;
  }
}

function routingIds() {
  const gid = $("guildId").value.trim();
  const channelId = currentChannelIdValue();
  if (!/^\d+$/.test(gid) || !/^\d+$/.test(channelId)) return null;
  $("routingChannelId").value = channelId;
  return { gid, channelId };
}

function routingPolicyPayload() {
  const maxCandidates = Number($("routingMaxCandidates").value || "1");
  return {
    responseMode: $("routingResponseMode").value,
    preferredModel: $("routingPreferredModel").value.trim() || null,
    allowedModels: csvInput("routingAllowedModels"),
    minQualityTier: $("routingMinQualityTier").value,
    maxCandidates: Number.isFinite(maxCandidates) ? Math.max(1, Math.min(5, maxCandidates)) : 1,
    providerTagFilter: csvInput("routingProviderTags"),
    costGuard: $("routingCostGuard").value,
  };
}

function renderRoutingCandidates(catalog = {}) {
  const models = catalog.modelSummaries || [];
  const availableModels = catalog.availableModels || models.filter((m) => m.available).map((m) => m.modelName);
  const blockedCount = (catalog.unavailableAllowedModels || []).length + models.filter((m) => m.available === false).length;
  const bestProviderFit = models.reduce((best, model) => Math.max(best, Number(model.eligibleProviderCount || 0)), 0);
  const recommendedModel = catalog.recommendedModel || models.find((m) => m.recommended)?.modelName || availableModels[0] || "";
  setText("routingCandidateCount", `${models.length}개 후보 · ${availableModels.length}개 사용 가능`);
  setText("routingAvailableCount", availableModels.length);
  setText("routingBlockedCount", blockedCount);
  setText("routingSelectedModel", recommendedModel || "–");
  setText("routingProviderFit", bestProviderFit ? `최대 ${bestProviderFit}명` : "적합 Provider 없음");
  setHtml("routingResultCards", [
    resultCard("추천 모델", esc(recommendedModel || "추천 없음"), recommendedModel ? "good" : "warn"),
    resultCard("사용 가능 후보", esc(availableModels.join(", ") || "없음"), availableModels.length ? "good" : "bad"),
    resultCard("보호/차단", esc(catalog.safetySummary || `${blockedCount}개 차단/불가`), blockedCount ? "warn" : "good"),
  ].join(""));
  renderList("routingModelCandidates", models.slice(0, 12), "사용 가능한 모델 후보가 없습니다", (m) =>
    `<li><strong>${esc(m.modelName)}${m.recommended ? " · 추천" : ""}${m.preferred ? " · 선호" : ""}</strong><span>${esc(m.available ? "사용 가능" : "불가")} · eligible ${esc(m.eligibleProviderCount)}/${esc(m.totalProviderCount)} · 보호 ${esc(m.protectedProviderCount)} · 품질 ${esc(m.bestQualityTier)} · shadow ${esc(m.shadowQualityScore ?? 0)} · 👍 ${esc(m.feedbackPositive ?? 0)} 👎 ${esc(m.feedbackNegative ?? 0)} 🚩 ${esc(m.feedbackReports ?? 0)} · ${(m.tags || []).map(esc).join(", ") || "태그 없음"} · ${(m.blockingReasons || []).map(esc).join(", ") || "차단 없음"}</span><button class="mini select-routing-model" data-model-name="${esc(m.modelName)}">이 모델로 정책 채우기</button></li>`,
  );
}

function renderRoutingChoice(choice) {
  const rows = [
    ["요청 모델", choice.requestedModel || "(직접 선택 없음)"],
    ["선호 모델", choice.preferredModel || "(정책 선호 없음)"],
    ["선택 모델", choice.selectedModel || "(선택 실패)"],
    ["응답 모드", choice.responseMode || "balanced"],
    ["fallback", choice.fallbackReason || "없음"],
    ["설명", choice.explanation || ""],
    ["유저 안내", choice.userMessage || "추가 안내 없음"],
    ["다음 행동", choice.nextAction || "바로 질문 가능"],
  ];
  renderList("routingModelChoice", rows, "선택 결과 없음", ([label, value]) =>
    `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`,
  );
  setText("routingChoiceState", choice.routingBlocked ? "라우팅 차단" : `선택 ${choice.selectedModel || "없음"}`);
  setText("routingSelectedModel", choice.selectedModel || choice.preferredModel || choice.requestedModel || "–");
  setHtml("routingResultCards", [
    resultCard("실제 선택", esc(choice.selectedModel || "선택 실패"), choice.routingBlocked ? "bad" : "good"),
    resultCard("Fallback", esc(choice.fallbackReason || "없음"), choice.fallbackReason ? "warn" : "good"),
    resultCard("사용자 안내", esc(choice.userMessage || "바로 질문 가능"), choice.routingBlocked ? "warn" : ""),
  ].join(""));
}

function selectRoutingModel(modelName) {
  if (!modelName) return;
  $("routingPreferredModel").value = modelName;
  $("routingRequestedModel").value = modelName;
  setText("routingSelectedModel", modelName);
  setText("routingChoiceState", "선택 결과 확인 필요");
  focusRoutingTask("policy");
}

function focusRoutingTask(task) {
  const targets = {
    policy: "#routingPreferredModel",
    candidates: "#routingLoadCandidates",
    choice: "#routingRequestedModel",
  };
  focusInside(targets[task] || "#routingPreferredModel");
}

async function loadEffectiveRoutingPolicy() {
  const ids = routingIds();
  if (!ids) {
    $("routingResult").textContent = "서버 ID와 채널 ID를 숫자로 입력하세요.";
    setHtml("routingResultCards", resultCard("채널 선택 필요", "서버의 채널을 먼저 열어야 라우팅 정책을 볼 수 있습니다.", "warn"));
    return;
  }
  try {
    const policy = await getJson(`/api/ai-network/channel-ai-routing/${ids.gid}/${ids.channelId}`);
    $("routingResponseMode").value = policy.responseMode || "balanced";
    $("routingPreferredModel").value = policy.preferredModel || "";
    $("routingAllowedModels").value = (policy.allowedModels || []).join(", ");
    $("routingMinQualityTier").value = policy.minQualityTier || "standard";
    $("routingMaxCandidates").value = policy.maxCandidates || 1;
    $("routingProviderTags").value = (policy.providerTagFilter || []).join(", ");
    $("routingCostGuard").value = policy.costGuard || "provider_safe";
    setText("routingPolicyState", `${policy.responseMode || "balanced"} · ${policy.preferredModel || "기본 모델"}`);
    setText("routingSelectedModel", policy.preferredModel || "서버 기본값");
    setText("routingChoiceState", "선택 결과 확인 필요");
    setHtml("routingResultCards", [
      resultCard("현재 정책", esc(`${policy.responseMode || "balanced"} · ${policy.costGuard || "provider_safe"}`), "good"),
      resultCard("선호 모델", esc(policy.preferredModel || "서버 기본값 사용"), policy.preferredModel ? "good" : ""),
      resultCard("허용 모델", esc((policy.allowedModels || []).join(", ") || "전체 허용"), ""),
    ].join(""));
    $("routingResult").textContent = `현재 정책을 불러왔습니다: mode=${policy.responseMode} · preferred=${policy.preferredModel || "-"} · allowed=${(policy.allowedModels || []).join(", ") || "전체"}`;
  } catch (e) {
    setHtml("routingResultCards", resultCard("현재 정책 로딩 실패", esc(e.message), "bad"));
    $("routingResult").textContent = `현재 정책 로딩 실패: ${e.message}`;
  }
}

async function saveRoutingPolicy() {
  const ids = routingIds();
  if (!ids) {
    $("routingResult").textContent = "서버 ID와 채널 ID를 숫자로 입력하세요.";
    setHtml("routingResultCards", resultCard("채널 선택 필요", "서버의 채널을 먼저 열어야 라우팅 정책을 저장할 수 있습니다.", "warn"));
    return;
  }
  try {
    const saved = await postJson(`/api/ai-network/channel-ai-routing/${ids.gid}/${ids.channelId}`, routingPolicyPayload());
    $("routingResult").textContent =
      `라우팅 정책 저장 완료: policy=${saved.id} · mode=${saved.responseMode} · preferred=${saved.preferredModel || "-"} · allowed=${(saved.allowedModels || []).join(", ") || "전체"}`;
    await loadModelCandidates();
  } catch (e) {
    setHtml("routingResultCards", resultCard("라우팅 정책 저장 실패", esc(e.message), "bad"));
    $("routingResult").textContent = `라우팅 정책 저장 실패: ${e.message}`;
  }
}

async function loadModelCandidates() {
  const ids = routingIds();
  if (!ids) {
    $("routingResult").textContent = "서버 ID와 채널 ID를 숫자로 입력하세요.";
    setHtml("routingResultCards", resultCard("채널 선택 필요", "서버의 채널을 먼저 열어야 모델 후보를 볼 수 있습니다.", "warn"));
    return;
  }
  try {
    const catalog = await getJson(`/api/ai-network/channel-ai-routing/${ids.gid}/${ids.channelId}/model-candidates`);
    renderRoutingCandidates(catalog);
    $("routingResult").textContent = [
      `모델 후보: ${catalog.safetySummary || "unknown"}`,
      `추천 모델: ${catalog.recommendedModel || "-"}`,
      `사용 가능: ${(catalog.availableModels || []).join(", ") || "없음"}`,
      `허용됐지만 현재 불가: ${(catalog.unavailableAllowedModels || []).join(", ") || "없음"}`,
    ].join("\n");
  } catch (e) {
    setHtml("routingResultCards", resultCard("모델 후보 로딩 실패", esc(e.message), "bad"));
    $("routingResult").textContent = `모델 후보 로딩 실패: ${e.message}`;
  }
}

async function checkModelChoice() {
  const ids = routingIds();
  if (!ids) {
    $("routingResult").textContent = "서버 ID와 채널 ID를 숫자로 입력하세요.";
    setHtml("routingResultCards", resultCard("채널 선택 필요", "서버의 채널을 먼저 열어야 실제 선택 결과를 확인할 수 있습니다.", "warn"));
    return;
  }
  try {
    const params = new URLSearchParams();
    const requestedModel = $("routingRequestedModel").value.trim();
    if (requestedModel) params.set("requestedModel", requestedModel);
    const suffix = params.toString() ? `?${params.toString()}` : "";
    const choice = await getJson(`/api/ai-network/channel-ai-routing/${ids.gid}/${ids.channelId}/model-choice${suffix}`);
    renderRoutingChoice(choice);
    $("routingResult").textContent = [
      `선택 모델: ${choice.selectedModel || "없음"}`,
      `fallback: ${choice.fallbackReason || "없음"}`,
      choice.userMessage ? `유저 안내: ${choice.userMessage}` : "유저 안내: 바로 질문 가능",
      choice.routingBlocked ? "상태: 라우팅 차단됨" : "상태: 라우팅 가능",
    ].join("\n");
  } catch (e) {
    setHtml("routingResultCards", resultCard("모델 선택 확인 실패", esc(e.message), "bad"));
    $("routingResult").textContent = `모델 선택 확인 실패: ${e.message}`;
  }
}

function knowledgeSpacePayload() {
  return {
    channelId: numericValue("knowledgeChannelId"),
    displayName: $("knowledgeSpaceName").value.trim() || "채널 지식공간",
  };
}

function knowledgeSourcePayload() {
  return {
    sourceType: $("knowledgeSourceType").value,
    title: $("knowledgeSourceTitle").value.trim() || "untitled",
    sourceUri: $("knowledgeSourceUri").value.trim() || null,
    contentPreview: $("knowledgeContentPreview").value.trim() || null,
  };
}

function renderKnowledgeReadiness(readiness, quality) {
  renderList("knowledgeReadiness", [
    ["상태", readiness.status || "unknown"],
    ["지식공간", `${readiness.spaceCount ?? 0}개`],
    ["색인된 소스", `${readiness.indexedSourceCount ?? 0}/${readiness.sourceCount ?? 0}`],
    ["차단/검토 필요", `${readiness.blockedSourceCount ?? 0}개`],
    ["품질 점수", `${quality?.coverageScore ?? 0}점 · ${quality?.qualityBand || "unknown"}`],
  ], "RAG 상태 없음", ([label, value]) => `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`);
  const actions = readiness.nextActions || quality?.recommendations || [];
  const spaces = readiness.spaces || [];
  $("knowledgeResult").textContent = [
    `RAG 상태: ${readiness.status || "unknown"}`,
    "",
    "[지식공간]",
    ...(spaces.length ? spaces.map((s) =>
      `- #${s.channelId || "-"} · space=${s.knowledgeSpaceId} · ${s.displayName} · ${s.readiness} · sources=${s.sourceCount}`,
    ) : ["- 아직 지식공간이 없습니다."]),
    "",
    "[다음 액션]",
    ...(actions.length ? actions.map((a) => `- ${a}`) : ["- 없음"]),
  ].join("\n");
}

function normalizeKnowledgeJobs(jobs) {
  if (Array.isArray(jobs)) return jobs;
  return jobs?.jobs || jobs?.items || [];
}

function selectKnowledgeSpace(spaceId, label = "") {
  $("knowledgeSpaceId").value = spaceId || "";
  const select = $("knowledgeSpaceQuickSelect");
  if (select) select.value = spaceId || "";
  setText("knowledgeSelectedSpaceName", label || (spaceId ? `space #${spaceId}` : "선택 안 됨"));
}

function renderKnowledgeSpaces(spaces = []) {
  const selectedId = $("knowledgeSpaceId").value.trim();
  const select = $("knowledgeSpaceQuickSelect");
  if (select) {
    select.innerHTML = [
      '<option value="">지식공간 선택…</option>',
      ...spaces.map((space) =>
        `<option value="${esc(space.knowledgeSpaceId)}">${esc(space.displayName)} · ${esc(space.readiness || "unknown")}</option>`,
      ),
    ].join("");
    select.value = selectedId;
  }
  renderList("knowledgeSpaceList", spaces, "아직 지식공간이 없습니다. 먼저 공간을 만들거나 상태를 새로고침하세요.", (space) => {
    const id = String(space.knowledgeSpaceId || "");
    const active = selectedId && selectedId === id ? " active" : "";
    return `<li class="knowledge-space-row${active}"><strong>${esc(space.displayName)} <small>#${esc(id)}</small></strong><span>${esc(space.readiness || "unknown")} · sources ${esc(space.sourceCount ?? 0)} · channel ${esc(space.channelId || "-")}</span><div class="row-actions"><button class="mini select-knowledge-space" data-space-id="${esc(id)}" data-space-name="${esc(space.displayName)}">이 공간 사용</button></div></li>`;
  });
  if (!selectedId && spaces.length) {
    const channelId = $("knowledgeChannelId").value.trim();
    const preferred = spaces.find((space) => channelId && String(space.channelId || "") === channelId) || spaces[0];
    selectKnowledgeSpace(String(preferred.knowledgeSpaceId || ""), preferred.displayName || "");
  } else if (selectedId) {
    const selected = spaces.find((space) => String(space.knowledgeSpaceId || "") === selectedId);
    setText("knowledgeSelectedSpaceName", selected ? selected.displayName : `space #${selectedId}`);
  }
}

function updateKnowledgeExperience(readiness = {}, quality = {}, ops = {}, jobsResponse = []) {
  const spaces = readiness.spaces || [];
  const jobs = normalizeKnowledgeJobs(jobsResponse);
  const actions = readiness.nextActions || quality.recommendations || ops.nextActions || [];
  const latest = jobs[0];
  renderKnowledgeSpaces(spaces);
  renderList("knowledgeActionList", actions.slice(0, 5), "지금 필요한 다음 액션이 없습니다.", (action) =>
    `<li><strong>다음</strong><span>${esc(action)}</span></li>`,
  );
  setText("knowledgeSpaceCount", readiness.spaceCount ?? spaces.length ?? 0);
  setText("knowledgeSourceProgress", `${readiness.indexedSourceCount ?? 0}/${readiness.sourceCount ?? 0}`);
  setText("knowledgeBlockedCount", readiness.blockedSourceCount ?? ops.blockedSourceCount ?? 0);
  setText("knowledgeQualityBand", quality.qualityBand || "unknown");
  setText("knowledgeNextAction", actions[0] || "검색 테스트로 확인");
  setText("knowledgeLatestJob", latest ? `job #${latest.id} · ${latest.status}` : "최근 작업 없음");
}

function focusRagTask(task) {
  const targets = {
    space: "#knowledgeSpaceName",
    source: "#knowledgeSourceTitle",
    index: "#knowledgeJobId",
    search: "#knowledgeSearchQuery",
  };
  focusInside(targets[task] || "#knowledgeSpaceName");
}

function renderKnowledgeIndexing(ops, jobs = []) {
  const commands = ops.commands || [];
  const jobRows = normalizeKnowledgeJobs(jobs).slice(0, 5);
  const latest = jobRows[0];
  const stats = [
    ["상태", ops.status || "unknown"],
    ["색인 가능 소스", `${ops.indexableSourceCount ?? 0}개`],
    ["차단 소스", `${ops.blockedSourceCount ?? 0}개`],
    ["최근 작업", latest ? `#${latest.id} · ${latest.status} · chunks ${latest.chunkCount}` : "없음"],
    ["실행 명령", commands[0] || ops.nextActions?.[0] || "색인할 작업 없음"],
  ];
  const items = [
    ...stats.map(([label, value]) => ({ kind: "stat", label, value })),
    ...jobRows.map((job) => ({ kind: "job", job })),
  ];
  renderList("knowledgeIndexing", items, "색인 작업 없음", (item) => {
    if (item.kind === "job") {
      const job = item.job;
      return `<li><strong>작업 #${esc(job.id)} · ${esc(job.status)}</strong><span>chunks ${esc(job.chunkCount ?? 0)} · space ${esc(job.knowledgeSpaceId || "-")} · 완료 처리 대상이면 아래 버튼을 누르세요.</span><button class="mini select-knowledge-job" data-job-id="${esc(job.id)}">완료 폼에 넣기</button></li>`;
    }
    return `<li><strong>${esc(item.label)}</strong><span>${esc(item.value)}</span></li>`;
  });
}

async function refreshKnowledge() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    $("knowledgeResult").textContent = "서버 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const spaceId = $("knowledgeSpaceId").value.trim();
    const jobQuery = /^\d+$/.test(spaceId) ? `?spaceId=${spaceId}&limit=10` : "?limit=10";
    const [readiness, quality, ops, jobs] = await Promise.all([
      getJson(`/api/ai-network/knowledge/${gid}/readiness`),
      getJson(`/api/ai-network/knowledge/${gid}/quality-summary`),
      getJson(`/api/ai-network/knowledge/${gid}/indexing-operations`),
      getJson(`/api/ai-network/knowledge/${gid}/index-jobs${jobQuery}`),
    ]);
    renderKnowledgeReadiness(readiness, quality);
    renderKnowledgeIndexing(ops, jobs);
    updateKnowledgeExperience(readiness, quality, ops, jobs);
  } catch (e) {
    setHtml("knowledgeResultCards", resultCard("RAG 상태 로딩 실패", esc(e.message), "bad"));
    $("knowledgeResult").textContent = `RAG 상태 로딩 실패: ${e.message}`;
  }
}

async function createKnowledgeSpace() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    $("knowledgeResult").textContent = "서버 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/knowledge/${gid}/spaces`, knowledgeSpacePayload());
    $("knowledgeSpaceId").value = result.id || "";
    $("knowledgeResult").textContent = `지식공간 생성 완료: space=${result.id} · ${result.status} · ${result.displayName}`;
    await refreshKnowledge();
  } catch (e) {
    $("knowledgeResult").textContent = `지식공간 생성 실패: ${e.message}`;
  }
}

async function addKnowledgeSource() {
  const gid = $("guildId").value.trim();
  const spaceId = $("knowledgeSpaceId").value.trim();
  if (!/^\d+$/.test(gid) || !/^\d+$/.test(spaceId)) {
    $("knowledgeResult").textContent = "서버 ID와 지식공간 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/knowledge/${gid}/spaces/${spaceId}/sources`, knowledgeSourcePayload());
    const indexing = result.inlineIndexed
      ? ` · 즉시 검색 가능 · chunks=${result.chunkCount} · job=${result.indexJobId || "-"}`
      : (result.indexSkippedReason ? ` · 색인 대기(${result.indexSkippedReason})` : "");
    $("knowledgeResult").textContent =
      `지식 소스 추가 완료: source=${result.id} · ${result.status} · risk=${result.riskLevel}${indexing}`;
    await refreshKnowledge();
  } catch (e) {
    $("knowledgeResult").textContent = `지식 소스 추가 실패: ${e.message}`;
  }
}

async function queueKnowledgeIndexJob() {
  const gid = $("guildId").value.trim();
  const spaceId = $("knowledgeSpaceId").value.trim();
  if (!/^\d+$/.test(gid) || !/^\d+$/.test(spaceId)) {
    $("knowledgeResult").textContent = "서버 ID와 지식공간 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/knowledge/${gid}/spaces/${spaceId}/index-jobs`, {});
    $("knowledgeJobId").value = result.id || "";
    $("knowledgeResult").textContent = `색인 작업 큐잉 완료: job=${result.id} · status=${result.status} · chunks=${result.chunkCount}`;
    await refreshKnowledge();
  } catch (e) {
    $("knowledgeResult").textContent = `색인 작업 큐잉 실패: ${e.message}`;
  }
}

async function completeKnowledgeIndexJob() {
  const gid = $("guildId").value.trim();
  const jobId = $("knowledgeJobId").value.trim();
  if (!/^\d+$/.test(gid) || !/^\d+$/.test(jobId)) {
    $("knowledgeResult").textContent = "서버 ID와 색인 작업 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/knowledge/${gid}/index-jobs/${jobId}/complete`, {
      status: $("knowledgeJobStatus").value,
      reason: $("knowledgeJobReason").value.trim() || null,
    });
    $("knowledgeResult").textContent = `색인 작업 상태 기록 완료: job=${result.id} · status=${result.status}`;
    await refreshKnowledge();
  } catch (e) {
    $("knowledgeResult").textContent = `색인 작업 상태 기록 실패: ${e.message}`;
  }
}

function knowledgeSearchUrl(gid) {
  const params = new URLSearchParams();
  const query = $("knowledgeSearchQuery").value.trim();
  const limit = Math.min(20, Math.max(1, Number($("knowledgeSearchLimit").value || 5)));
  const channelId = $("knowledgeChannelId").value.trim();
  const spaceId = $("knowledgeSpaceId").value.trim();
  params.set("query", query);
  params.set("limit", String(limit));
  if (/^\d+$/.test(channelId)) params.set("channelId", channelId);
  if (/^\d+$/.test(spaceId)) params.set("knowledgeSpaceId", spaceId);
  return `/api/ai-network/knowledge/${gid}/search?${params.toString()}`;
}

function renderKnowledgeSearchCards(result, rows) {
  if (!rows.length) {
    setHtml("knowledgeResultCards", resultCard("검색 결과 없음", "지식공간, 색인 상태, 질문 키워드를 확인하세요.", "warn"));
    return;
  }
  setHtml("knowledgeResultCards", rows.slice(0, 6).map((row, index) =>
    resultCard(
      `#${index + 1} ${row.title || `source ${row.sourceId}`}`,
      [
        `score ${row.score ?? "-"} · risk ${row.riskLevel || "-"}`,
        `source ${row.sourceId} · chunk ${row.chunkId || "-"} · space ${row.knowledgeSpaceId || "-"}`,
        (row.matchSignals || []).length ? `signals ${(row.matchSignals || []).join(", ")}` : null,
        row.contentPreview ? `preview ${row.contentPreview}` : null,
      ].filter(Boolean).map(esc).join("<br />"),
      index === 0 ? "good" : "",
    ),
  ).join(""));
}

function renderKnowledgeEvalCards(result, rows) {
  const summaryKind = result.passed ? "good" : "warn";
  const summary = resultCard(
    result.passed ? "RAG 평가 PASS" : "RAG 평가 확인 필요",
    [
      `cases ${result.caseCount ?? rows.length} · k ${result.k}`,
      `hitAtK ${Number(result.hitAtK || 0).toFixed(2)} · mrr ${Number(result.mrr || 0).toFixed(2)} · recall ${Number(result.recallAtK || 0).toFixed(2)}`,
    ].map(esc).join("<br />"),
    summaryKind,
  );
  const caseCards = rows.slice(0, 6).map((row) =>
    resultCard(
      `${row.hit ? "✓" : "✕"} ${row.name || row.query || "case"}`,
      [
        `rank ${row.firstHitRank || "-"} · recall ${Number(row.recall || 0).toFixed(2)}`,
        `expected ${(row.expectedSourceIds || []).join(", ") || "-"}`,
        `returned ${(row.returnedSourceIds || []).join(", ") || "-"}`,
      ].map(esc).join("<br />"),
      row.hit ? "good" : "warn",
    ),
  );
  setHtml("knowledgeResultCards", [summary, ...caseCards].join(""));
}

async function searchKnowledge() {
  const gid = $("guildId").value.trim();
  const query = $("knowledgeSearchQuery").value.trim();
  if (!/^\d+$/.test(gid)) {
    setHtml("knowledgeResultCards", resultCard("검색 불가", "서버 ID를 숫자로 입력하세요.", "bad"));
    $("knowledgeResult").textContent = "서버 ID를 숫자로 입력하세요.";
    return;
  }
  if (!query) {
    setHtml("knowledgeResultCards", resultCard("검색 질문 필요", "실제 사용자가 물어볼 질문을 입력하세요.", "warn"));
    $("knowledgeResult").textContent = "검색 테스트 질문을 입력하세요.";
    return;
  }
  try {
    const result = await getJson(knowledgeSearchUrl(gid));
    const rows = result.results || [];
    renderKnowledgeSearchCards(result, rows);
    $("knowledgeResult").textContent = [
      `RAG 검색 결과: ${rows.length}개 · query="${result.query || query}"`,
      result.fallbackReason ? `fallback=${result.fallbackReason}` : "fallback=none",
      "",
      ...(rows.length
        ? rows.map((row, index) => [
          `#${index + 1} source=${row.sourceId} chunk=${row.chunkId || "-"} space=${row.knowledgeSpaceId}`,
          `title=${row.title} · type=${row.sourceType} · risk=${row.riskLevel}`,
          `score=${row.score} · sourceWeight=${row.sourceWeight ?? 0} · signals=${(row.matchSignals || []).join(", ") || "-"}`,
          row.contentPreview ? `preview=${row.contentPreview}` : null,
        ].filter(Boolean).join("\n"))
        : ["검색 결과가 없습니다. 지식공간/색인/질문 키워드를 확인하세요."]),
    ].join("\n\n");
  } catch (e) {
    setHtml("knowledgeResultCards", resultCard("RAG 검색 실패", esc(e.message), "bad"));
    $("knowledgeResult").textContent = `RAG 검색 실패: ${e.message}`;
  }
}

function knowledgeEvalPayload() {
  const raw = $("knowledgeEvalCases").value.trim();
  const parsed = raw ? JSON.parse(raw) : [];
  const cases = Array.isArray(parsed) ? parsed : (parsed.cases || []);
  return {
    k: Math.min(20, Math.max(1, Number($("knowledgeEvalK").value || 10))),
    cases,
  };
}

async function evaluateKnowledge() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    setHtml("knowledgeResultCards", resultCard("평가 불가", "서버 ID를 숫자로 입력하세요.", "bad"));
    $("knowledgeResult").textContent = "서버 ID를 숫자로 입력하세요.";
    return;
  }
  let payload;
  try {
    payload = knowledgeEvalPayload();
  } catch (e) {
    setHtml("knowledgeResultCards", resultCard("JSON 파싱 실패", esc(e.message), "bad"));
    $("knowledgeResult").textContent = `골든 케이스 JSON 파싱 실패: ${e.message}`;
    return;
  }
  if (!payload.cases.length) {
    setHtml("knowledgeResultCards", resultCard("평가 케이스 필요", "골든 케이스 JSON을 1개 이상 입력하세요.", "warn"));
    $("knowledgeResult").textContent = "골든 케이스 JSON을 1개 이상 입력하세요.";
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/knowledge/${gid}/eval`, payload);
    const rows = result.cases || [];
    renderKnowledgeEvalCards(result, rows);
    $("knowledgeResult").textContent = [
      `RAG 평가: ${result.passed ? "PASS" : "FAIL"} · cases=${result.caseCount} · k=${result.k}`,
      `hitAtK=${Number(result.hitAtK || 0).toFixed(2)} · mrr=${Number(result.mrr || 0).toFixed(2)} · recallAtK=${Number(result.recallAtK || 0).toFixed(2)}`,
      "",
      ...(rows.length
        ? rows.map((row) => [
          `${row.hit ? "✓" : "✕"} ${row.name} · rank=${row.firstHitRank || "-"} · recall=${Number(row.recall || 0).toFixed(2)}`,
          `query=${row.query}`,
          `expected=${(row.expectedSourceIds || []).join(", ") || "-"} · returned=${(row.returnedSourceIds || []).join(", ") || "-"}`,
          row.fallbackReason ? `fallback=${row.fallbackReason}` : null,
        ].filter(Boolean).join("\n"))
        : ["평가 결과가 없습니다."]),
    ].join("\n\n");
  } catch (e) {
    setHtml("knowledgeResultCards", resultCard("RAG 평가 실패", esc(e.message), "bad"));
    $("knowledgeResult").textContent = `RAG 평가 실패: ${e.message}`;
  }
}

function qualityFeedbackPayload() {
  return {
    requestId: $("qualityRequestId").value.trim() || null,
    userId: numericValue("qualityUserId"),
    rating: Number($("qualityRating").value || "0"),
    feedbackType: $("qualityType").value,
    reason: $("qualityReason").value.trim() || null,
  };
}

function renderQualitySummary(summary, channelSummary = null) {
  const items = [
    ["서버 피드백", `${summary.feedbackCount ?? 0}건 · 👍 ${summary.positive ?? 0} · 👎 ${summary.negative ?? 0}`],
    ["서버 신고", `${summary.reports ?? 0}건 · 열린 신고 ${summary.openReports ?? 0}`],
  ];
  if (channelSummary) {
    items.push(
      ["채널 피드백", `${channelSummary.feedbackCount ?? 0}건 · 👍 ${channelSummary.positive ?? 0} · 👎 ${channelSummary.negative ?? 0}`],
      ["채널 신고", `${channelSummary.reports ?? 0}건 · 열린 신고 ${channelSummary.openReports ?? 0}`],
    );
  }
  const reasons = (channelSummary?.recentReasons || summary.recentReasons || []).join(" / ");
  items.push(["최근 사유", reasons || "아직 없음"]);
  renderList("qualitySummary", items, "품질 요약 없음", ([label, value]) =>
    `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`,
  );
}

function renderQualityReviewQueue(review) {
  renderList("qualityReviewQueue", review.queue?.slice(0, 12), "검토할 신고 없음", (item) =>
    `<li><strong>#${esc(item.id)} · ${esc(item.feedbackType)} · rating ${esc(item.rating ?? "-")}</strong><span>channel ${esc(item.channelId)} · ${esc(item.reason || "사유 없음")}</span><button class="mini select-quality-feedback" data-feedback-id="${esc(item.id)}">선택</button></li>`,
  );
}

function renderQualityModels(models) {
  const modelRows = Array.isArray(models) ? models : [];
  renderList("qualityModelSignals", modelRows.slice(0, 12), "모델 품질 신호 없음", (model) =>
    `<li><strong>${esc(model.modelName)}</strong><span>providers ${esc(model.providerCount)} · quality ${esc(model.qualityTier)} · overload ${esc(model.overloadRiskCount)}</span></li>`,
  );
}

function renderQualityExperience(summary = {}, review = {}, models = [], channelSummary = null) {
  const queue = review.queue || [];
  const openReports = review.openReportCount ?? summary.openReports ?? queue.length ?? 0;
  const reasons = channelSummary?.recentReasons || summary.recentReasons || [];
  const channelFeedbackCount = channelSummary?.feedbackCount ?? 0;
  setText("qualityFeedbackCount", summary.feedbackCount ?? 0);
  setText("qualityOpenReports", `${openReports}건 열린 신고`);
  setText("qualityReviewCount", queue.length);
  setText("qualityModelCount", `${models.length}개 모델 신호`);
  setText("qualityChannelScore", channelSummary ? `${channelFeedbackCount}건` : "채널 미선택");
  setText("qualityRecentReason", reasons[0] || "없음");
  setHtml("qualityResultCards", [
    resultCard("품질 현황", esc(`피드백 ${summary.feedbackCount ?? 0}건 · 열린 신고 ${openReports}건`), openReports ? "warn" : "good"),
    resultCard("검토 큐", esc(queue[0] ? `다음 신고 #${queue[0].id} · ${queue[0].feedbackType}` : "검토할 신고 없음"), queue.length ? "warn" : "good"),
    resultCard("모델 신호", esc(models.length ? `${models.length}개 모델 추적 중` : "모델 품질 신호 없음"), models.length ? "" : "warn"),
  ].join(""));
}

function focusQualityTask(task) {
  const targets = {
    feedback: "#qualityReason",
    review: "#qualityFeedbackId",
    models: "#qualityModelSignals",
  };
  focusInside(targets[task] || "#qualityReason");
}

async function refreshQualityDashboard() {
  const gid = $("guildId").value.trim();
  const channelId = currentChannelIdValue();
  if (!/^\d+$/.test(gid)) {
    $("qualityResult").textContent = "서버 ID를 숫자로 입력하세요.";
    setHtml("qualityResultCards", resultCard("서버 선택 필요", "서버 ID를 먼저 선택해야 품질 현황을 볼 수 있습니다.", "warn"));
    return;
  }
  if (/^\d+$/.test(channelId)) $("qualityChannelId").value = channelId;
  try {
    const [summary, review, models, channelSummary] = await Promise.all([
      getJson(`/api/ai-network/quality/${gid}/summary`),
      getJson(`/api/ai-network/quality/${gid}/review-summary`),
      getJson(`/api/ai-network/quality/${gid}/models`),
      /^\d+$/.test(channelId)
        ? getJson(`/api/ai-network/quality/${gid}/${channelId}/summary`)
        : Promise.resolve(null),
    ]);
    renderQualitySummary(summary, channelSummary);
    renderQualityReviewQueue(review);
    const modelRows = Array.isArray(models) ? models : [];
    renderQualityModels(modelRows);
    renderQualityExperience(summary, review, modelRows, channelSummary);
    const first = review.queue?.[0];
    if (first && !$("qualityFeedbackId").value.trim()) $("qualityFeedbackId").value = first.id;
    $("qualityResult").textContent =
      `품질 현황: 피드백 ${summary.feedbackCount ?? 0}건 · 열린 신고 ${review.openReportCount ?? 0}건 · 모델 ${modelRows.length}개`;
  } catch (e) {
    setHtml("qualityResultCards", resultCard("품질 현황 로딩 실패", esc(e.message), "bad"));
    $("qualityResult").textContent = `품질 현황 로딩 실패: ${e.message}`;
  }
}

async function submitQualityFeedback() {
  const gid = $("guildId").value.trim();
  const channelId = currentChannelIdValue();
  if (!/^\d+$/.test(gid) || !/^\d+$/.test(channelId)) {
    $("qualityResult").textContent = "서버 ID와 채널 ID를 숫자로 입력하세요.";
    setHtml("qualityResultCards", resultCard("채널 선택 필요", "품질 피드백을 저장할 채널을 먼저 여세요.", "warn"));
    return;
  }
  $("qualityChannelId").value = channelId;
  try {
    const result = await postJson(`/api/ai-network/quality/${gid}/${channelId}/feedback`, qualityFeedbackPayload());
    $("qualityFeedbackId").value = result.id || "";
    setHtml("qualityResultCards", resultCard("피드백 저장 완료", esc(`feedback=${result.id} · status=${result.status}`), "good"));
    $("qualityResult").textContent =
      `피드백 저장 완료: feedback=${result.id} · status=${result.status} · rating=${result.rating}`;
    await refreshQualityDashboard();
  } catch (e) {
    setHtml("qualityResultCards", resultCard("피드백 저장 실패", esc(e.message), "bad"));
    $("qualityResult").textContent = `피드백 저장 실패: ${e.message}`;
  }
}

async function resolveQualityFeedback() {
  const gid = $("guildId").value.trim();
  const feedbackId = $("qualityFeedbackId").value.trim();
  if (!/^\d+$/.test(gid) || !/^\d+$/.test(feedbackId)) {
    $("qualityResult").textContent = "서버 ID와 피드백 ID를 숫자로 입력하세요.";
    setHtml("qualityResultCards", resultCard("신고 선택 필요", "검토 큐에서 신고를 선택하거나 피드백 ID를 입력하세요.", "warn"));
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/quality/${gid}/feedback/${feedbackId}/review`, {
      status: $("qualityReviewStatus").value,
      reviewerUserId: numericValue("qualityReviewerUserId"),
      resolutionReason: $("qualityResolutionReason").value.trim() || null,
    });
    setHtml("qualityResultCards", resultCard("신고 검토 완료", esc(`feedback=${result.id} · ${result.status}`), "good"));
    $("qualityResult").textContent =
      `신고 검토 완료: feedback=${result.id} · status=${result.status} · reviewer=${result.reviewedBy || "-"}`;
    await refreshQualityDashboard();
  } catch (e) {
    setHtml("qualityResultCards", resultCard("신고 검토 실패", esc(e.message), "bad"));
    $("qualityResult").textContent = `신고 검토 실패: ${e.message}`;
  }
}

function renderProviderSafety(dashboard, plan) {
  const summary = [
    ["과부하 알림", `${dashboard.alertCount ?? 0}건 · high/critical ${dashboard.highRiskCount ?? 0}`],
    ["안전 온라인 Provider", `${dashboard.safeOnlineProviderCount ?? 0}명`],
    ["다중응답 안전", dashboard.fanoutSafe ? "가능" : "제한 필요"],
  ];
  renderList("safetyOverloadSummary", summary, "과부하 요약 없음", ([label, value]) =>
    `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`,
  );
  renderList("safetyOverloadAlerts", dashboard.alerts?.slice(0, 12), "활성 과부하 알림 없음", (alert) =>
    `<li><strong>${esc(alert.providerLabel)} · ${esc(alert.risk)} · ${esc(alert.providerState)}</strong><span>${esc(alert.message)} · ${esc(alert.recommendedAction)}</span></li>`,
  );
  const planItems = [
    ["요청 모드", `${plan.requestedResponseMode} → ${plan.effectiveResponseMode}`],
    ["후보 수", `${plan.requestedCandidates} 요청 · 최대 안전 ${plan.maxSafeCandidates}`],
    ["고급 기능", plan.advancedFeaturesAllowed ? "허용" : "비활성"],
    ["fan-out", plan.fanoutAllowed ? "허용" : "차단"],
    ["비활성 기능", (plan.disabledFeatures || []).join(", ") || "없음"],
    ["이유", (plan.reasons || []).join(" / ") || "위험 없음"],
  ];
  renderList("safetyExecutionPlan", planItems, "실행 계획 없음", ([label, value]) =>
    `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`,
  );
}

async function refreshProviderSafety() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    $("safetyResult").textContent = "서버 ID를 숫자로 입력하세요.";
    return;
  }
  const responseMode = $("safetyResponseMode").value;
  const requestedCandidates = Math.max(1, Math.min(5, Number($("safetyRequestedCandidates").value || "1")));
  try {
    const [dashboard, plan] = await Promise.all([
      getJson(`/api/ai-network/safety/${gid}/overload-alerts?audience=admin`),
      getJson(`/api/ai-network/safety/${gid}/execution-plan?responseMode=${encodeURIComponent(responseMode)}&requestedCandidates=${requestedCandidates}`),
    ]);
    renderProviderSafety(dashboard, plan);
    $("safetyResult").textContent =
      `Provider 보호 현황: 알림 ${dashboard.alertCount ?? 0}건 · safeOnline ${dashboard.safeOnlineProviderCount ?? 0} · fanout ${dashboard.fanoutSafe ? "가능" : "제한"}`;
  } catch (e) {
    $("safetyResult").textContent = `과부하 현황 로딩 실패: ${e.message}`;
  }
}

async function markProviderOverload() {
  const gid = $("guildId").value.trim();
  const providerId = $("safetyProviderId").value.trim();
  if (!/^\d+$/.test(gid) || !/^\d+$/.test(providerId)) {
    $("safetyResult").textContent = "서버 ID와 Provider 사용자 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/safety/${gid}/providers/${providerId}/overload?audience=admin`, {
      overloadRisk: $("safetyRisk").value,
      reason: $("safetyReason").value.trim() || null,
    });
    $("safetyResult").textContent =
      `Provider 보호 상태 저장 완료: ${result.providerLabel} · event=${result.eventId} · alerts=${result.overloadAlertCount} · health=${result.healthStatus}`;
    await refreshProviderSafety();
  } catch (e) {
    $("safetyResult").textContent = `Provider 보호 상태 저장 실패: ${e.message}`;
  }
}

function presetBehaviorPayload() {
  const maxCandidates = Number($("presetMaxCandidates").value || "1");
  const tags = $("presetTags").value.split(",").map((v) => v.trim()).filter(Boolean);
  const exampleQuestions = $("presetExampleQuestions").value.split(/\n+/).map((v) => v.trim()).filter(Boolean);
  return {
    purpose: $("presetPurpose").value.trim() || "general_assistant",
    tone: $("presetTone").value.trim() || "friendly",
    answerLength: "balanced",
    constitution: $("presetConstitution").value.trim() || null,
    responseMode: $("presetResponseMode").value.trim() || "balanced",
    preferredModel: $("presetPreferredModel").value.trim() || null,
    maxCandidates: Number.isFinite(maxCandidates) ? Math.max(1, Math.min(5, maxCandidates)) : 1,
    tags,
    exampleQuestions,
  };
}

function presetPayload(includeBehavior = true) {
  return {
    name: $("presetName").value.trim() || "새 AI 프리셋",
    summary: $("presetSummary").value.trim() || null,
    category: $("presetCategory").value.trim() || "general",
    visibility: "guild_private",
    behavior: includeBehavior ? presetBehaviorPayload() : null,
  };
}

function publishedPresetPayload() {
  const hasBehaviorInput =
    $("presetPurpose").value.trim() ||
    $("presetTone").value.trim() ||
    $("presetConstitution").value.trim() ||
    $("presetResponseMode").value.trim() ||
    $("presetPreferredModel").value.trim();
  return {
    title: $("presetName").value.trim() || null,
    description: $("presetSummary").value.trim() || null,
    behavior: hasBehaviorInput ? presetBehaviorPayload() : null,
  };
}

function updatePresetExperience(local = {}, published = {}) {
  const localCount = local.presets?.length ?? 0;
  const catalogCount = published.presets?.length ?? 0;
  setText("presetLocalCount", localCount);
  setText("presetCatalogCount", catalogCount);
}

function focusPresetTask(task) {
  const targets = {
    create: "#presetName",
    catalog: "#presetCatalogQuery",
    publish: "#publishedPresetId",
    moderation: "#presetReportId",
  };
  focusInside(targets[task] || "#presetName");
}

function selectLocalPresetFromElement(el) {
  $("presetId").value = el.dataset.localPresetId || "";
  if (el.dataset.presetName) $("presetName").value = el.dataset.presetName;
  if (el.dataset.presetCategory) $("presetCategory").value = el.dataset.presetCategory;
  if (el.dataset.presetSummary) $("presetSummary").value = el.dataset.presetSummary;
  focusPresetTask("create");
}

function selectPublishedPresetFromElement(el) {
  $("publishedPresetId").value = el.dataset.presetId || "";
  if (el.dataset.presetTitle) $("presetName").value = el.dataset.presetTitle;
  if (el.dataset.presetDescription) $("presetSummary").value = el.dataset.presetDescription;
  focusPresetTask("publish");
}

function renderPresetLists(local, published) {
  renderList("localPresetList", local?.presets?.slice(0, 8), "서버 프리셋 없음", (p) =>
    `<li class="pick-local-preset preset-row channel-item" data-local-preset-id="${esc(p.id)}" data-preset-name="${esc(p.name)}" data-preset-category="${esc(p.category || "general")}" data-preset-summary="${esc(p.summary || "")}"><strong>${esc(p.name)} <small>#${esc(p.id)}</small></strong><span>${esc(p.category)} · ${esc(p.status)} · ${esc(p.visibility)} · 클릭하면 편집 폼에 채워짐</span><div class="row-actions"><button class="mini pick-local-preset" data-local-preset-id="${esc(p.id)}" data-preset-name="${esc(p.name)}" data-preset-category="${esc(p.category || "general")}" data-preset-summary="${esc(p.summary || "")}">편집에 넣기</button></div></li>`,
  );
  renderList("publishedPresetList", published?.presets?.slice(0, 8), "게시 프리셋 없음", (p) =>
    `<li class="preset-row"><strong>${esc(p.title)} <small>#${esc(p.id)}</small></strong><span>좋아요 ${esc(p.likeCount)} · 가져오기 ${esc(p.importCount)} · 신고 ${esc(p.reportCount)} · ${esc(p.category || "general")} · ${(p.tags || []).map(esc).join(", ") || "태그 없음"}</span><div class="row-actions"><button class="mini preview-preset" data-preset-id="${esc(p.id)}">미리보기</button><button class="mini select-published-preset" data-preset-id="${esc(p.id)}" data-preset-title="${esc(p.title)}" data-preset-description="${esc(p.description || "")}">관리 선택</button><button class="mini report-preset" data-preset-id="${esc(p.id)}">신고</button><button class="mini unlist-published-preset" data-preset-id="${esc(p.id)}">비공개</button><button class="mini remove-published-preset" data-preset-id="${esc(p.id)}">숨김</button></div></li>`,
  );
}

async function refreshPresets() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    $("presetManageResult").textContent = "서버 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    pendingPresetImport = null;
    $("presetConfirmImport").disabled = true;
    setText("presetImportState", "미리보기 전");
    setHtml("presetImportPreviewCards", "");
    $("presetImportPreview").textContent =
      "가져올 프리셋의 [미리보기]를 먼저 누르면, 덮어쓰기/승인 필요 여부를 확인한 뒤 가져올 수 있습니다.";
    const [local, published] = await Promise.all([
      getJson(`/api/ai-network/presets/guilds/${gid}`),
      getJson(presetCatalogUrl()),
    ]);
    renderPresetLists(local, published);
    updatePresetExperience(local, published);
    $("presetManageResult").textContent =
      `서버 프리셋 ${local.presets?.length || 0}개 · 웹 카탈로그 ${published.presets?.length || 0}개`;
  } catch (e) {
    $("presetManageResult").textContent = `프리셋 로딩 실패: ${e.message}`;
  }
}

async function createPreset() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    $("presetManageResult").textContent = "서버 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/presets/${gid}`, presetPayload(true));
    $("presetId").value = result.id || "";
    $("presetManageResult").textContent = `프리셋 생성 완료: preset=${result.id} · ${result.status}`;
    await refreshPresets();
  } catch (e) {
    $("presetManageResult").textContent = `프리셋 생성 실패: ${e.message}`;
  }
}

async function updatePreset() {
  const presetId = $("presetId").value.trim();
  if (!/^\d+$/.test(presetId)) {
    $("presetManageResult").textContent = "프리셋 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await fetch(`/api/ai-network/presets/${presetId}`, {
      method: "PUT",
      headers: apiHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify(presetPayload(true)),
    });
    if (!result.ok) throw new Error(`${result.status} /api/ai-network/presets/${presetId}`);
    const json = await result.json();
    $("presetManageResult").textContent = `프리셋 수정 완료: preset=${json.id} · revision=${json.currentRevisionId}`;
    await refreshPresets();
  } catch (e) {
    $("presetManageResult").textContent = `프리셋 수정 실패: ${e.message}`;
  }
}

async function updatePublishedPreset() {
  const publishedPresetId = $("publishedPresetId").value.trim();
  if (!/^\d+$/.test(publishedPresetId)) {
    $("presetManageResult").textContent = "게시 프리셋 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await putJson(`/api/ai-network/presets/published/${publishedPresetId}`, publishedPresetPayload());
    $("presetManageResult").textContent =
      `게시 프리셋 수정 완료: published=${result.id} · revision=${result.revisionId} · ${result.status}`;
    await refreshPresets();
  } catch (e) {
    $("presetManageResult").textContent = `게시 프리셋 수정 실패: ${e.message}`;
  }
}

async function publishPreset() {
  const presetId = $("presetId").value.trim();
  if (!/^\d+$/.test(presetId)) {
    $("presetManageResult").textContent = "프리셋 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/presets/${presetId}/publish`, {
      title: $("presetName").value.trim() || null,
      description: $("presetSummary").value.trim() || null,
    });
    $("publishedPresetId").value = result.id || "";
    $("presetManageResult").textContent = `게시 완료: published=${result.id} · ${result.status} · ${result.slug}`;
    await refreshPresets();
  } catch (e) {
    $("presetManageResult").textContent = `게시 실패: ${e.message}`;
  }
}

async function deletePreset() {
  const presetId = $("presetId").value.trim();
  if (!/^\d+$/.test(presetId)) {
    $("presetManageResult").textContent = "프리셋 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await deleteJson(`/api/ai-network/presets/${presetId}`);
    $("presetManageResult").textContent = `삭제 완료: ${result.status}`;
    await refreshPresets();
  } catch (e) {
    $("presetManageResult").textContent = `삭제 실패: ${e.message}`;
  }
}

async function deletePublishedPreset(id = null) {
  const publishedPresetId = String(id || $("publishedPresetId").value.trim());
  if (!/^\d+$/.test(publishedPresetId)) {
    $("presetManageResult").textContent = "게시 프리셋 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await deleteJson(`/api/ai-network/presets/published/${publishedPresetId}`);
    $("presetManageResult").textContent = `게시 프리셋 숨김 완료: ${result.status}`;
    await refreshPresets();
    await refreshPresetModeration();
  } catch (e) {
    $("presetManageResult").textContent = `게시 프리셋 숨김 실패: ${e.message}`;
  }
}

async function unlistPublishedPreset(id = null) {
  const publishedPresetId = String(id || $("publishedPresetId").value.trim());
  if (!/^\d+$/.test(publishedPresetId)) {
    $("presetManageResult").textContent = "게시 프리셋 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/presets/published/${publishedPresetId}/unlist`, {});
    $("presetManageResult").textContent = `게시 프리셋 비공개 완료: ${result.status}`;
    await refreshPresets();
    await refreshPresetModeration();
  } catch (e) {
    $("presetManageResult").textContent = `게시 프리셋 비공개 실패: ${e.message}`;
  }
}

async function republishPublishedPreset(id = null) {
  const publishedPresetId = String(id || $("publishedPresetId").value.trim());
  if (!/^\d+$/.test(publishedPresetId)) {
    $("presetManageResult").textContent = "게시 프리셋 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/presets/published/${publishedPresetId}/republish`, {});
    $("presetManageResult").textContent = `게시 프리셋 재공개 완료: ${result.status}`;
    await refreshPresets();
    await refreshPresetModeration();
  } catch (e) {
    $("presetManageResult").textContent = `게시 프리셋 재공개 실패: ${e.message}`;
  }
}

async function likePreset() {
  const publishedPresetId = $("publishedPresetId").value.trim();
  if (!/^\d+$/.test(publishedPresetId)) {
    $("presetManageResult").textContent = "게시 프리셋 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/presets/published/${publishedPresetId}/like`, { userId: 0 });
    $("presetManageResult").textContent = `따봉 완료: published=${result.id} · like=${result.likeCount}`;
    await refreshPresets();
  } catch (e) {
    $("presetManageResult").textContent = `따봉 실패: ${e.message}`;
  }
}

async function reportPublishedPreset(id = null) {
  const publishedPresetId = String(id || $("publishedPresetId").value.trim());
  if (!/^\d+$/.test(publishedPresetId)) {
    $("presetManageResult").textContent = "게시 프리셋 ID를 숫자로 입력하세요.";
    return;
  }
  const reason = $("presetReportReason").value.trim() || "대시보드에서 신고됨";
  try {
    const result = await postJson(`/api/ai-network/presets/published/${publishedPresetId}/report`, {
      reporterUserId: 0,
      reasonCode: "other",
      details: reason,
    });
    $("presetReportId").value = result.id || "";
    $("presetManageResult").textContent = `신고 접수 완료: report=${result.id} · ${result.status} · ${result.reasonCode || "other"}`;
    await refreshPresets();
    await refreshPresetModeration();
  } catch (e) {
    $("presetManageResult").textContent = `신고 실패: ${e.message}`;
  }
}

function formatReportReasonCodes(item) {
  const entries = Object.entries(item?.reportReasonCodes || {});
  return entries.length ? entries.map(([code, count]) => `${code}:${count}`).join(", ") : "none";
}

function renderPresetModeration(summary) {
  renderList("presetModerationList", summary?.queue?.slice(0, 12), "검토할 신고 없음", (item) =>
    `<li><strong>${esc(item.publishedPresetId)} · ${esc(item.title)}</strong><span>${esc(item.status)} · 신고 ${esc(item.reportCount)} · 유형 ${esc(formatReportReasonCodes(item))} · ${esc((item.riskCodes || []).join(", ") || "none")}</span><button class="mini select-published-preset" data-preset-id="${esc(item.publishedPresetId)}">프리셋 선택</button></li>`,
  );
}

async function refreshPresetModeration() {
  try {
    const [summary, reports] = await Promise.all([
      getJson("/api/ai-network/presets/moderation/summary"),
      getJson("/api/ai-network/presets/reports/open"),
    ]);
    renderPresetModeration(summary.summary);
    const firstReport = reports.reports?.[0];
    if (firstReport && !$("presetReportId").value.trim()) $("presetReportId").value = firstReport.id;
    setText("presetModerationCount", summary.summary?.openReportCount ?? reports.reports?.length ?? 0);
    $("presetManageResult").textContent =
      `신고 큐: 열린 신고 ${summary.summary?.openReportCount || 0}건 · 검토중 ${summary.summary?.underReviewCount || 0}개`;
  } catch (e) {
    $("presetManageResult").textContent = `신고 큐 로딩 실패: ${e.message}`;
  }
}

async function reviewPresetReport() {
  const reportId = $("presetReportId").value.trim();
  if (!/^\d+$/.test(reportId)) {
    $("presetManageResult").textContent = "신고 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/presets/reports/${reportId}/review`, {
      decision: $("presetReportDecision").value,
      reviewerUserId: 0,
    });
    $("presetManageResult").textContent =
      `신고 처리 완료: report=${result.id} · ${result.status} · reviewer=${result.reviewedBy ?? "-"}`;
    await refreshPresets();
    await refreshPresetModeration();
  } catch (e) {
    $("presetManageResult").textContent = `신고 처리 실패: ${e.message}`;
  }
}

function multiNumber(id, fallback, min, max) {
  const value = Number($(id).value || fallback);
  if (!Number.isFinite(value)) return fallback;
  return Math.max(min, Math.min(max, value));
}

function currentMultiMaxFanout() {
  const cap = Number($("multiMaxCandidates").dataset.featureMaxFanout || "5");
  return Number.isFinite(cap) ? Math.max(1, Math.min(5, cap)) : 5;
}

function multiPolicyPayload() {
  const maxFanout = currentMultiMaxFanout();
  return {
    channelId: numericValue("multiChannelId"),
    mode: $("multiMode").value,
    maxCandidates: multiNumber("multiMaxCandidates", 1, 1, maxFanout),
    requireDistinctModels: $("multiDistinctModels").checked,
    providerDailyLimit: multiNumber("multiProviderDailyLimit", 0, 0, 100000),
    timeoutSeconds: multiNumber("multiTimeoutSeconds", 120, 10, 300),
    synthesisEnabled: $("multiSynthesis").checked,
    disabledReason: $("multiDisabledReason").value.trim() || null,
  };
}

function featureState(value) {
  return value ? "켜짐" : "꺼짐";
}

function renderMultiFeatureFlags(features = {}) {
  const maxFanout = Math.max(1, Math.min(5, Number(features.multiResponseMaxFanout || 1)));
  $("multiMaxCandidates").max = String(maxFanout);
  $("multiMaxCandidates").dataset.featureMaxFanout = String(maxFanout);
  if (Number($("multiMaxCandidates").value || "1") > maxFanout) $("multiMaxCandidates").value = String(maxFanout);
  $("multiSynthesis").disabled = !features.multiResponseSynthesis;
  renderList("multiFeatureFlags", [
    ["AI 네트워크", featureState(features.aiNetwork)],
    ["다중응답", featureState(features.multiResponse)],
    ["다중응답 대시보드", featureState(features.multiResponseDashboard)],
    ["후보 합성", featureState(features.multiResponseSynthesis)],
    ["RAG 결합", featureState(features.multiResponseRag)],
    ["최대 fanout", `${maxFanout}개`],
    ["kill switch", features.killSwitch ? "활성" : "비활성"],
  ], "기능 플래그 데이터 없음", ([label, value]) => `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`);
}

function formatRate(value) {
  const number = Number(value || 0);
  if (!Number.isFinite(number)) return "0%";
  return number <= 1 ? `${Math.round(number * 100)}%` : `${Math.round(number)}%`;
}

function renderMultiExperience(summary = {}, runs = [], decision = {}, recommendation = {}) {
  const loads = summary.providerLoads || [];
  const riskyLoads = loads.filter((load) => ["high", "critical"].includes(String(load.loadRisk || "").toLowerCase()));
  const safeToEnable = summary.safeToEnableAdvanced === true;
  setText("multiModeState", `${summary.status || "unknown"} · ${safeToEnable ? "고급 가능" : "주의 필요"}`);
  setText(
    "multiFanoutState",
    `${recommendation.fanoutAllowed ? "가능" : "차단/단일"} · ${recommendation.recommendedCandidateCount ?? 0}/${recommendation.maxSafeCandidates ?? 0}`,
  );
  setText("multiRunCount", runs.length || summary.recentRunCount || 0);
  setText("multiLoadState", riskyLoads.length ? `${riskyLoads.length}명 위험` : "안정");
  setText("multiProtectionCount", summary.providerProtectionBlockedCount ?? 0);
  setText("multiAdoptionRate", formatRate(decision.adoptionRate ?? summary.decisionSummary?.adoptionRate ?? 0));
  setHtml("multiResultCards", [
    resultCard("고급 모드 안전", esc(safeToEnable ? "켜도 되는 상태입니다." : "부하/품질 신호를 먼저 확인하세요."), safeToEnable ? "good" : "warn"),
    resultCard("추천 fanout", esc(`${recommendation.recommendedCandidateCount ?? 0}/${recommendation.maxSafeCandidates ?? 0} · ${recommendation.status || "unknown"}`), recommendation.fanoutAllowed ? "good" : "warn"),
    resultCard("Provider 부하", esc(riskyLoads.length ? `${riskyLoads.length}명 high/critical` : "위험 부하 없음"), riskyLoads.length ? "warn" : "good"),
    resultCard("최근 실행", esc(`${runs.length || summary.recentRunCount || 0}건 · 채택률 ${formatRate(decision.adoptionRate ?? summary.decisionSummary?.adoptionRate ?? 0)}`), ""),
  ].join(""));
}

function focusAdvancedTask(task) {
  const targets = {
    policy: "#multiMode",
    fanout: "#multiRefreshOps",
    pseudo: "#pseudoStreamAnswer",
  };
  focusInside(targets[task] || "#multiMode");
}

function renderMultiOps(summary, runs = [], decision = {}, features = {}, recommendation = {}) {
  renderList("multiOps", [
    ["상태", summary.status || "unknown"],
    ["고급 모드 안전", summary.safeToEnableAdvanced ? "가능" : "주의 필요"],
    ["최근 실행", `${summary.recentRunCount ?? 0}건`],
    ["평균 후보 수", summary.averageActualFanout ?? 0],
    ["채택률", decision.adoptionRate ?? summary.decisionSummary?.adoptionRate ?? 0],
    ["평균 품질", decision.averageQualityScore ?? summary.decisionSummary?.averageQualityScore ?? 0],
    ["fallback", `${summary.fallbackRunCount ?? 0}건`],
    ["Provider 보호 차단", `${summary.providerProtectionBlockedCount ?? 0}건`],
    ["최근 보호 사유", (summary.recentProviderProtectionReasons || []).join(" / ") || "없음"],
    ["RAG 결합", features.multiResponseRag ? "사용 가능" : "비활성/차단"],
    ["위험 코드", (summary.riskCodes || []).join(", ") || "없음"],
  ], "다중응답 운영 데이터 없음", ([label, value]) => `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`);
  renderMultiFeatureFlags(features);
  renderList("multiRecommendation", [
    ["상태", recommendation.status || "unknown"],
    ["정책", `${recommendation.policySource || "-"} · ${recommendation.policyMode || "-"}`],
    ["추천 후보", `${recommendation.recommendedCandidateCount ?? 0}/${recommendation.maxSafeCandidates ?? 0}`],
    ["fanout", recommendation.fanoutAllowed ? "가능" : "단일/차단"],
    ["사유", (recommendation.reasons || []).join(", ") || "없음"],
    ["Provider", (recommendation.providers || []).map((p) => `${p.providerLabel}·${p.modelName || "-"}`).join(" / ") || "없음"],
  ], "추천 fanout 미리보기 없음", ([label, value]) => `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`);
  renderList("multiProviderLoad", summary.providerLoads?.slice(0, 8), "Provider 부하 데이터 없음", (p) =>
    `<li><strong>${esc(p.providerLabel || p.providerUserId || "provider")}</strong><span>${esc(p.loadRisk)} · 후보 ${esc(p.candidateCount)} · timeout ${esc(p.timeoutCount)} · 품질 ${esc(p.averageQualityScore)}</span></li>`,
  );
  renderList("multiRecentRuns", runs?.slice(0, 8), "최근 다중응답 실행 없음", (run) =>
    `<li><strong>#${esc(run.id)} · ${esc(run.status)}</strong><span>#${esc(run.channelId)} · 후보 ${esc(run.candidateCount)} · ${esc(run.requestId || "request")}</span></li>`,
  );
  renderMultiExperience(summary, runs || [], decision || {}, recommendation || {});
}

async function refreshMultiOps() {
  const gid = $("guildId").value.trim();
  const channelId = currentChannelIdValue();
  if (!/^\d+$/.test(gid)) {
    $("multiResult").textContent = "서버 ID를 숫자로 입력하세요.";
    setHtml("multiResultCards", resultCard("서버 선택 필요", "서버 ID를 먼저 선택해야 고급 응답 현황을 볼 수 있습니다.", "warn"));
    return;
  }
  if (/^\d+$/.test(channelId)) $("multiChannelId").value = channelId;
  const qs = /^\d+$/.test(channelId) ? `?channelId=${channelId}` : "";
  const recommendationQs = new URLSearchParams({
    responseMode: $("multiMode").value,
    requestedCandidates: String(multiNumber("multiMaxCandidates", 1, 1, currentMultiMaxFanout())),
  });
  if (/^\d+$/.test(channelId)) recommendationQs.set("channelId", channelId);
  try {
    const [data, runs, decision, features, recommendation] = await Promise.all([
      getJson(`/api/ai-network/multi-response/${gid}/operations-summary${qs}`),
      getJson(`/api/ai-network/multi-response/${gid}/runs`),
      getJson(`/api/ai-network/multi-response/${gid}/decision-summary${qs ? `${qs}&limit=20` : "?limit=20"}`),
      getJson("/api/ai-network/features"),
      getJson(`/api/ai-network/multi-response/${gid}/recommendation?${recommendationQs.toString()}`),
    ]);
    const summary = data.summary || {};
    renderMultiOps(summary, runs || [], decision || {}, features || {}, recommendation || {});
    $("multiResult").textContent = [
      `다중응답 상태: ${summary.status || "unknown"}`,
      `고급 모드 안전: ${summary.safeToEnableAdvanced ? "yes" : "no"}`,
      `기능 플래그: multi=${featureState(features?.multiResponse)} · dashboard=${featureState(features?.multiResponseDashboard)} · synthesis=${featureState(features?.multiResponseSynthesis)} · rag=${featureState(features?.multiResponseRag)} · maxFanout=${features?.multiResponseMaxFanout ?? "?"}`,
      `최근 실행: ${runs?.length || 0}건 · 채택률: ${decision?.adoptionRate ?? summary.decisionSummary?.adoptionRate ?? 0}`,
      `추천 fanout: ${recommendation?.status || "unknown"} · 후보 ${recommendation?.recommendedCandidateCount ?? 0}/${recommendation?.maxSafeCandidates ?? 0}`,
      `Provider 보호 차단: ${summary.providerProtectionBlockedCount ?? 0}건`,
      `후보 상태: accepted=${decision?.acceptedCandidateCount ?? 0} · rejected=${decision?.rejectedCandidateCount ?? 0} · timeout=${decision?.timeoutCandidateCount ?? 0}`,
      "",
      "[다음 액션]",
      ...((summary.nextActions || []).length ? summary.nextActions.map((a) => `- ${a}`) : ["- 없음"]),
    ].join("\n");
  } catch (e) {
    setHtml("multiResultCards", resultCard("다중응답 운영 상태 로딩 실패", esc(e.message), "bad"));
    $("multiResult").textContent = `다중응답 운영 상태 로딩 실패: ${e.message}`;
  }
}

async function saveMultiPolicy() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    $("multiResult").textContent = "서버 ID를 숫자로 입력하세요.";
    setHtml("multiResultCards", resultCard("서버 선택 필요", "서버 ID를 먼저 선택해야 다중응답 정책을 저장할 수 있습니다.", "warn"));
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/multi-response/${gid}/policy`, multiPolicyPayload());
    setHtml("multiResultCards", resultCard("다중응답 정책 저장 완료", esc(`policy=${result.id} · ${result.mode} · candidates=${result.maxCandidates}`), "good"));
    $("multiResult").textContent =
      `다중응답 정책 저장 완료: policy=${result.id} · ${result.mode} · candidates=${result.maxCandidates}` +
      (result.disabledReason ? ` · disabled=${result.disabledReason}` : "");
    await refreshMultiOps();
  } catch (e) {
    setHtml("multiResultCards", resultCard("다중응답 정책 저장 실패", esc(e.message), "bad"));
    $("multiResult").textContent = `다중응답 정책 저장 실패: ${e.message}`;
  }
}

async function planPseudoStream() {
  const answer = $("pseudoStreamAnswer").value.trim();
  if (!answer) {
    $("multiResult").textContent = "미리보기할 긴 답변을 입력하세요.";
    setHtml("multiResultCards", resultCard("답변 텍스트 필요", "긴 답변을 붙여넣으면 Discord 수정 스냅샷을 계산합니다.", "warn"));
    return;
  }
  try {
    const result = await postJson("/api/ai-network/multi-response/pseudo-stream-plan", {
      answer,
      steps: [33, 66, 100],
      maxDiscordChars: 1900,
    });
    $("multiResult").textContent = [
      `최종 길이: ${result.finalLength} · truncated=${result.truncated}`,
      result.warning ? `경고: ${result.warning}` : "",
      "",
      ...(result.snapshots || []).map((s, i) => `[${i + 1}] ${s.percent}% · ${s.charCount}자\n${s.content}`),
    ].filter(Boolean).join("\n\n");
    setHtml("multiResultCards", [
      resultCard("스냅샷 계산 완료", esc(`최종 ${result.finalLength}자 · ${result.truncated ? "잘림 있음" : "잘림 없음"}`), result.truncated ? "warn" : "good"),
      resultCard("수정 단계", esc(`${(result.snapshots || []).length}단계 · 33%/66%/100%`), ""),
      result.warning ? resultCard("경고", esc(result.warning), "warn") : "",
    ].join(""));
  } catch (e) {
    setHtml("multiResultCards", resultCard("수정 스냅샷 계산 실패", esc(e.message), "bad"));
    $("multiResult").textContent = `수정 스냅샷 계산 실패: ${e.message}`;
  }
}

function renderDashboardFreshness(metadata) {
  const stale = metadata?.stale === true;
  const rows = [
    ["상태", stale ? "갱신 지연" : metadata?.freshnessStatus || "fresh"],
    ["마지막 성공 갱신", metadata?.generatedAt || "방금 계산됨"],
    ["데이터 소스", metadata?.source || "network_overview_projection"],
    ["재생성 명령", "GET /api/ai-network/{guildId}/overview?refresh=true"],
    ["운영 안내", stale ? "최근 상태 갱신이 지연되고 있어요. 질문 기능과는 별개입니다." : "상태판이 최신입니다."],
    ["다음 행동", metadata?.degradedReason || "필요 없음"],
  ];
  renderList("dashboardFreshness", rows, "상태 신뢰도 정보 없음", ([label, value]) =>
    `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`,
  );
}

function metadataFromOverview(overview) {
  return {
    generatedAt: overview.refreshedAt,
    freshnessStatus: overview.freshnessStatus,
    stale: overview.stale,
    degradedReason: overview.degradedReason,
    source: "network_overview_projection",
  };
}

async function refreshDashboardProjection() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    $("dashboardProjectionResult").textContent = "서버 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const overview = await getJson(`/api/ai-network/${gid}/overview?refresh=true`);
    renderDashboardFreshness(metadataFromOverview(overview));
    $("dashboardProjectionResult").textContent =
      `projection 재생성 완료 · 마지막 성공 갱신 ${overview.refreshedAt} · freshness=${overview.freshnessStatus}`;
  } catch (e) {
    $("dashboardProjectionResult").textContent = `projection 재생성 실패: ${e.message}`;
  }
}

function renderAiNetwork(data) {
  $("aiNetwork").hidden = false;
  $("networkTitle").textContent = data.overview?.displayName || "AI 네트워크";
  $("networkSummary").textContent = data.overview?.tagline || "여러 사용자의 로컬 AI를 안전하게 연결합니다.";
  const readiness = data.readiness?.status || data.overview?.healthStatus || "unknown";
  $("readinessBadge").textContent = readiness;
  $("readinessBadge").className = `pill ${readiness === "ready" ? "ok" : readiness === "blocked" ? "bad" : "warn"}`;
  renderDashboardFreshness(data.metadata);
  $("growthLevel").textContent = `Lv.${data.growthPlan?.currentLevel ?? data.overview?.networkLevel ?? "–"}`;
  $("growthSummary").textContent = data.growthPlan?.summary || "성장 계획을 계산 중입니다.";

  renderList("networkActions", data.nextActions?.slice(0, 5), "권장 액션 없음", (a) =>
    `<li><strong>${esc(a.title)}</strong><span>${esc(a.description)}</span></li>`,
  );
  renderList("channelAiCards", data.channels?.slice(0, 6), "채널 AI 없음", (c) =>
    `<li><strong>#${esc(c.channelId)} · ${esc(c.name)}</strong><span>${esc(c.readinessStatus)} · ${esc(c.purpose || "역할 미설정")}</span></li>`,
  );
  renderList("modelMap", data.modelMap?.slice(0, 6), "사용 가능한 모델 없음", (m) =>
    `<li><strong>${esc(m.modelName)}</strong><span>${esc(m.onlineProviderCount)}/${esc(m.totalProviderCount)} online · ${esc((m.qualityTiers || []).join(",") || "unknown")}</span></li>`,
  );
  renderList("growthTimeline", data.growthTimeline?.slice(0, 5), "최근 성장 이벤트 없음", (e) =>
    `<li><strong>${esc(e.title)}</strong><span>${esc((e.impactBullets || []).join(" · ") || e.summary || "")}</span></li>`,
  );
  renderList("presetCatalog", data.publishedPresets?.slice(0, 5), "게시된 프리셋 없음", (p) =>
    `<li><strong>${esc(p.title)}</strong><span>좋아요 ${esc(p.likeCount)} · 가져오기 ${esc(p.importCount)} · ${esc(p.publisherLabel)}</span><button class="mini preview-preset" data-preset-id="${esc(p.id)}">미리보기</button></li>`,
  );
  renderList("changeApproval", data.changeApproval?.pendingItems?.slice(0, 5), data.changeApproval?.nextActions?.[0] || "승인 대기 없음", (p) =>
    `<li><strong>#${esc(p.channelId)} 변경 대기</strong><span>${esc(p.reason || "사유 없음")} · 제안 ${esc(p.proposedBehaviorId || "-")}</span></li>`,
  );
  const qualityItems = [
    ["총 피드백", data.quality?.feedbackCount ?? 0],
    ["열린 신고", data.quality?.openReports ?? 0],
    ["검토 대기", data.qualityReview?.queue?.length ?? data.qualityReview?.openReportCount ?? 0],
    ["모델 품질", data.modelQuality?.length ?? 0],
  ];
  renderList("qualityReview", qualityItems, "품질 데이터 없음", ([label, value]) =>
    `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`,
  );
  renderProviderStatus(data.providers);
  renderChannelUsage(data.channelUsage);
  renderFeatureUsers(data.featureUsers);
  renderProviderHistory(data.providerHistory);
  renderLaunchChecklist(data.launchChecklist || null);
  renderOverviewSummary(data);
  renderProviderDonut(data.providers);
  renderServerChannels(data.channels || []);
}

// Overview 페이지의 '네트워크 준비 상태'·'지금 할 일' 카드를 서버 데이터로 채운다.
function renderOverviewSummary(data) {
  const readiness = data.readiness?.status || data.overview?.healthStatus || "unknown";
  const readyRows = [
    ["준비 상태", readiness],
    ["성장 레벨", `Lv.${data.growthPlan?.currentLevel ?? data.overview?.networkLevel ?? "–"}`],
    ["채널 AI", `${data.channels?.length ?? 0}개`],
    ["사용 가능 모델", `${data.modelMap?.length ?? 0}종`],
  ];
  renderList("ovReadiness", readyRows, "데이터 없음", ([label, value]) =>
    `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`,
  );
  const actions = [];
  for (const a of (data.nextActions || []).slice(0, 3)) actions.push([a.title, a.description]);
  const pending = data.changeApproval?.pendingItems?.length ?? 0;
  if (pending > 0) actions.push(["설정 승인 대기", `${pending}건 검토 필요`]);
  const openReports = data.quality?.openReports ?? 0;
  if (openReports > 0) actions.push(["열린 신고", `${openReports}건 검토 필요`]);
  renderList("ovActions", actions, "지금 처리할 항목이 없습니다 🎉", ([label, value]) =>
    `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`,
  );
}

// 어드민 (b): 프로바이더 상태(가용시간·마지막 활동 포함). dashboard 페이로드의 providers 보강.
function renderProviderStatus(providers) {
  renderList("providerStatus", providers, "참여 중인 프로바이더 없음", (p) => {
    const availability =
      p.availableFromHour != null && p.availableToHour != null
        ? `가용 ${p.availableFromHour}~${p.availableToHour}시(UTC)`
        : "가용시간 미설정";
    return `<li><strong>${esc(p.providerLabel)} · ${esc(p.state)}</strong><span>모델 ${esc(p.modelCount)} · 품질 ${esc(p.qualityTier)} · 부담 ${esc(p.maxBurden)} · 위험 ${esc(p.overloadRisk)} · ${esc(availability)} · 마지막 활동 ${esc(p.lastSeenAt || "기록 없음")}</span></li>`;
  });
}

// 어드민 (a): 채널 사용 현황.
function renderChannelUsage(channelUsage) {
  renderList("channelUsage", channelUsage, "아직 채널 사용 기록이 없습니다", (c) =>
    `<li><strong>#${esc(c.channelId)}</strong><span>요청 ${esc(c.requestCount)}건 · 유저 ${esc(c.distinctUsers)}명 · 마지막 ${esc(c.lastUsedAt || "-")}</span></li>`,
  );
}

// 어드민 (d): 기능 사용 유저(집계만, 프롬프트 본문 없음).
function renderFeatureUsers(featureUsers) {
  renderList("featureUsers", featureUsers, "아직 기능을 사용한 유저가 없습니다", (u) =>
    `<li><strong>유저 ${esc(u.userId)}</strong><span>요청 ${esc(u.requestCount)}건 · 첫 사용 ${esc(u.firstUsedAt || "-")} · 마지막 ${esc(u.lastUsedAt || "-")}</span></li>`,
  );
}

// 어드민 (c): 프로바이더 참여 이력 타임라인.
function renderProviderHistory(history) {
  renderList("providerHistory", history?.slice(0, 20), "프로바이더 참여 이력이 없습니다", (e) =>
    `<li><strong>${esc(e.title)} · ${esc(e.eventType)}</strong><span>provider ${esc(e.providerUserId ?? "-")} · ${esc(e.summary || "")} · ${esc(e.createdAt)}</span></li>`,
  );
}

async function refreshProviderHistory() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    alert("서버 ID(숫자)를 입력하세요.");
    return;
  }
  const providerUserId = $("providerHistoryUserId").value.trim();
  const suffix = /^\d+$/.test(providerUserId) ? `?providerUserId=${providerUserId}` : "";
  try {
    const history = await getJson(`/api/ai-network/${gid}/provider-history${suffix}`);
    renderProviderHistory(history);
  } catch (e) {
    renderProviderHistory([]);
  }
}

function renderLaunchChecklist(checklist) {
  if (!checklist) {
    renderList("launchChecklist", [], "체크리스트를 새로고침하세요", (i) => i);
    return;
  }
  const headline = [
    { title: `Gate ${checklist.releaseGate}`, status: checklist.status, nextAction: `score ${checklist.score}` },
    ...(checklist.items || []).slice(0, 8),
  ];
  renderList("launchChecklist", headline, "체크리스트 없음", (item) =>
    `<li><strong>${esc(item.title || item.key)} · ${esc(item.status)}</strong><span>${esc((item.evidence || []).join(" · ") || item.nextAction || "")}</span></li>`,
  );
}

async function refreshLaunchChecklist() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    alert("서버 ID(숫자)를 입력하세요.");
    return;
  }
  try {
    const checklist = await getJson(`/api/ai-network/${gid}/launch-checklist?audience=admin`);
    renderLaunchChecklist(checklist);
  } catch (e) {
    renderList("launchChecklist", [{ title: "체크리스트 로딩 실패", status: "error", evidence: [e.message] }], "체크리스트 없음", (item) =>
      `<li><strong>${esc(item.title)}</strong><span>${esc((item.evidence || []).join(" · "))}</span></li>`,
    );
  }
}

function renderPresetImportPreview(preview) {
  const conflicts = preview.conflicts || [];
  const conflictLines =
    conflicts.length === 0
      ? ["- 충돌 없음: 바로 가져올 수 있습니다."]
      : conflicts.map((c) => `- [${c.severity}] ${c.code}: ${c.message}`);
  const actions = [
    preview.willApplyToChannel ? "- 현재 채널 AI에 적용됨" : "- 서버 프리셋 복사본만 생성됨",
    preview.willOverwriteChannelAi ? "- 기존 채널 AI 설정을 덮어쓸 수 있음" : "- 기존 채널 AI 덮어쓰기 없음",
    preview.willCreateApprovalProposal ? "- 고위험 변경이라 승인 제안으로 생성됨" : "- 즉시 적용 가능",
  ];
  $("presetImportPreview").textContent = [
    `프리셋 미리보기: ${preview.title} (#${preview.publishedPresetId})`,
    preview.description ? `설명: ${preview.description}` : null,
    `목적: ${preview.purpose}`,
    `말투: ${preview.tone} · 길이: ${preview.answerLength} · 안전: ${preview.safetyLevel}`,
    `모드: ${preview.responseMode} · 최소 품질: ${preview.minQualityTier} · 후보 수: ${preview.maxCandidates}`,
    `태그: ${(preview.tags || []).join(", ") || "없음"}`,
    `질문 예시: ${(preview.exampleQuestions || []).join(" / ") || "없음"}`,
    "",
    "가져오면 일어나는 일",
    ...actions,
    "",
    "확인할 점",
    ...conflictLines,
    "",
    "문제가 없으면 [미리보기한 프리셋 가져오기]를 누르세요.",
  ].filter(Boolean).join("\n");
  setHtml("presetImportPreviewCards", [
    resultCard(
      `${preview.title || "프리셋"} #${preview.publishedPresetId}`,
      [
        preview.description ? esc(preview.description) : "설명 없음",
        `<code>${esc(preview.category || "general")}</code>`,
      ].join("<br />"),
      "good",
    ),
    resultCard(
      "가져오면 바뀌는 것",
      [
        preview.willApplyToChannel ? "현재 채널 AI에 적용" : "서버 프리셋 복사본만 생성",
        preview.willOverwriteChannelAi ? "기존 채널 AI 덮어쓰기 가능" : "기존 채널 AI 덮어쓰기 없음",
        preview.willCreateApprovalProposal ? "승인 제안으로 생성" : "즉시 적용 가능",
      ].map(esc).join("<br />"),
      preview.willOverwriteChannelAi || preview.willCreateApprovalProposal ? "warn" : "good",
    ),
    resultCard(
      "동작 요약",
      [
        `말투 ${preview.tone || "-"} · 길이 ${preview.answerLength || "-"}`,
        `모드 ${preview.responseMode || "-"} · 후보 ${preview.maxCandidates ?? "-"}`,
        `태그 ${(preview.tags || []).join(", ") || "없음"}`,
      ].map(esc).join("<br />"),
    ),
    resultCard(
      conflicts.length ? `충돌 ${conflicts.length}건` : "충돌 없음",
      conflicts.length
        ? conflicts.map((c) => `${esc(c.severity)} · ${esc(c.code)} · ${esc(c.message)}`).join("<br />")
        : "바로 가져올 수 있습니다.",
      conflicts.length ? "warn" : "good",
    ),
  ].join(""));
}

async function previewPresetImport(publishedPresetId) {
  const gid = $("guildId").value.trim();
  const channelId = currentChannelIdValue();
  if (!/^\d+$/.test(gid) || !/^\d+$/.test(channelId)) {
    setHtml("presetImportPreviewCards", resultCard("채널 선택 필요", "프리셋을 가져오려면 먼저 서버의 채널을 선택하세요.", "warn"));
    $("presetImportResult").textContent = "프리셋을 가져오려면 서버 ID와 채널 ID를 먼저 입력하세요.";
    return;
  }
  try {
    pendingPresetImport = null;
    $("presetConfirmImport").disabled = true;
    const result = await postJson(`/api/ai-network/presets/published/${publishedPresetId}/import-preview`, {
      targetGuildId: Number(gid),
      targetChannelId: Number(channelId),
    });
    const preview = result.preview;
    pendingPresetImport = {
      publishedPresetId,
      guildId: Number(gid),
      channelId: Number(channelId),
      conflictCount: preview.conflicts?.length || 0,
    };
    renderPresetImportPreview(preview);
    $("presetConfirmImport").disabled = false;
    setText("presetImportState", `미리보기 완료 · 충돌 ${pendingPresetImport.conflictCount}`);
    $("presetImportResult").textContent =
      `미리보기 완료: 충돌 ${pendingPresetImport.conflictCount}건 · 가져오려면 확인 버튼을 누르세요.`;
  } catch (e) {
    setHtml("presetImportPreviewCards", resultCard("미리보기 실패", esc(e.message), "bad"));
    $("presetImportResult").textContent = `프리셋 미리보기 실패: ${e.message}`;
  }
}

async function importPreset() {
  if (!pendingPresetImport) {
    $("presetImportResult").textContent = "먼저 가져올 프리셋의 [미리보기]를 누르세요.";
    return;
  }
  try {
    const imported = await postJson(`/api/ai-network/presets/published/${pendingPresetImport.publishedPresetId}/import`, {
      targetGuildId: pendingPresetImport.guildId,
      targetChannelId: pendingPresetImport.channelId,
      confirmConflicts: true,
    });
    $("presetImportResult").textContent =
      `가져오기 완료: ${imported.status} · sourceRevision=${imported.sourceRevisionId || "-"} · ` +
      `channelAi=${imported.createdChannelAiId || "-"} · ` +
      `충돌 ${pendingPresetImport.conflictCount}건 확인`;
    pendingPresetImport = null;
    $("presetConfirmImport").disabled = true;
    setText("presetImportState", "가져오기 완료");
    await loadGuild();
  } catch (e) {
    $("presetImportResult").textContent = `프리셋 가져오기 실패: ${e.message}`;
  }
}

// 서버 상세(#198 개요 / #201 로그 / #202 차트)
async function loadGuild() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    alert("서버 ID(숫자)를 입력하세요.");
    return;
  }
  try {
    const [overview, trend, requests, providers, aiNetwork, launchChecklist, channelUsage, featureUsers, providerHistory] =
      await Promise.all([
        getJson(`/api/dashboard/${gid}/overview`),
        getJson(`/api/dashboard/${gid}/usage-trend?days=7`),
        getJson(`/api/dashboard/${gid}/requests`),
        getJson(`/api/metrics/pool/${gid}`),
        getJson(`/api/ai-network/${gid}/dashboard?audience=admin`),
        getJson(`/api/ai-network/${gid}/launch-checklist?audience=admin`),
        getJson(`/api/ai-network/${gid}/channel-usage`),
        getJson(`/api/ai-network/${gid}/users?limit=20`),
        getJson(`/api/ai-network/${gid}/provider-history`),
      ]);

    $("guildOverview").innerHTML = [
      ["활성 프로바이더", overview.activeProviders],
      ["총 요청", overview.totalRequests],
      ["기본 모델", overview.defaultModel || "(자동)"],
      ["언어", overview.language],
      ["자동승인", overview.autoApprove ? "예" : "아니오"],
    ].map(([l, v]) => `<div class="stat"><div class="num">${v}</div><div class="lbl">${l}</div></div>`).join("");

    renderAiNetwork({ ...aiNetwork, launchChecklist, channelUsage, featureUsers, providerHistory });

    // 프로바이더 상세(#200)
    const ptbody = document.querySelector("#providers tbody");
    ptbody.innerHTML = (providers.providers || []).map((p) =>
      `<tr><td>${p.providerId}</td><td>${p.state}</td><td>${p.inFlight}</td><td>${p.queued ?? 0}</td><td>${p.failures}</td><td>${p.models}</td></tr>`,
    ).join("") || `<tr><td colspan="5">연결된 프로바이더 없음</td></tr>`;

    renderTrend(trend);

    const tbody = document.querySelector("#requests tbody");
    tbody.innerHTML = requests.map((r) =>
      `<tr><td>${r.requestId}</td><td>${r.state}</td><td>${r.burden}</td><td>${r.providerId ?? "-"}</td><td>${r.createdAt}</td></tr>`,
    ).join("") || `<tr><td colspan="5">요청 없음</td></tr>`;
    loadProviderPicker();
    loadChannelPicker();
    loadServerModelOptions();
  } catch (e) {
    alert(`불러오기 실패: ${e.message}`);
  }
}

// 정책 쓰기(#203/#204) — OAuth 세션 또는 관리자 토큰이 있을 때 성공.
async function postWrite(path, params) {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    alert("서버 ID(숫자)를 먼저 입력하세요.");
    return;
  }
  const qs = new URLSearchParams(params).toString();
  try {
    const res = await fetch(`/api/dashboard/${gid}/${path}?${qs}`, { method: "POST", headers: apiHeaders() });
    $("writeResult").textContent = res.ok ? `✅ 적용됨 (${path})` : `⛔ 실패 ${res.status}(인증 필요?)`;
  } catch (e) {
    $("writeResult").textContent = `오류: ${e.message}`;
  }
}

loadDashboardAdminToken();
$("dashboardAdminToken").addEventListener("input", updateDashboardAdminTokenStatus);
$("saveDashboardAdminToken").addEventListener("click", saveDashboardAdminToken);
$("clearDashboardAdminToken").addEventListener("click", clearDashboardAdminToken);
$("loadGuild").addEventListener("click", loadGuild);
$("saveWelcome").addEventListener("click", () => postWrite("welcome", { message: $("welcomeMsg").value }));
$("autoApproveOn").addEventListener("click", () => postWrite("auto-approve", { enabled: "true" }));
$("autoApproveOff").addEventListener("click", () => postWrite("auto-approve", { enabled: "false" }));
$("wizardDraft").addEventListener("click", draftChannelAi);
$("wizardCreate").addEventListener("click", createChannelAi);
$("routingLoadEffective").addEventListener("click", loadEffectiveRoutingPolicy);
$("routingSavePolicy").addEventListener("click", saveRoutingPolicy);
$("routingLoadCandidates").addEventListener("click", loadModelCandidates);
$("routingCheckChoice").addEventListener("click", checkModelChoice);
$("knowledgeCreateSpace").addEventListener("click", createKnowledgeSpace);
$("knowledgeAddSource").addEventListener("click", addKnowledgeSource);
$("knowledgeQueueJob").addEventListener("click", queueKnowledgeIndexJob);
$("knowledgeCompleteJob").addEventListener("click", completeKnowledgeIndexJob);
$("knowledgeSearch").addEventListener("click", searchKnowledge);
$("knowledgeEvaluate").addEventListener("click", evaluateKnowledge);
$("knowledgeRefresh").addEventListener("click", refreshKnowledge);
$("qualitySubmitFeedback").addEventListener("click", submitQualityFeedback);
$("qualityRefresh").addEventListener("click", refreshQualityDashboard);
$("qualityReviewResolve").addEventListener("click", resolveQualityFeedback);
$("safetyRefresh").addEventListener("click", refreshProviderSafety);
$("safetyMarkOverload").addEventListener("click", markProviderOverload);
$("presetRefresh").addEventListener("click", refreshPresets);
$("presetCreate").addEventListener("click", createPreset);
$("presetUpdate").addEventListener("click", updatePreset);
$("presetPublish").addEventListener("click", publishPreset);
$("presetDelete").addEventListener("click", deletePreset);
$("presetLike").addEventListener("click", likePreset);
$("publishedPresetUpdate").addEventListener("click", updatePublishedPreset);
$("publishedPresetUnlist").addEventListener("click", () => unlistPublishedPreset());
$("publishedPresetRepublish").addEventListener("click", () => republishPublishedPreset());
$("publishedPresetDelete").addEventListener("click", () => deletePublishedPreset());
$("publishedPresetReport").addEventListener("click", () => reportPublishedPreset());
$("presetModerationRefresh").addEventListener("click", refreshPresetModeration);
$("presetReportReview").addEventListener("click", reviewPresetReport);
$("presetConfirmImport").addEventListener("click", importPreset);
$("knowledgeSpaceQuickSelect").addEventListener("change", (event) => {
  const option = event.target.options[event.target.selectedIndex];
  selectKnowledgeSpace(event.target.value, option?.textContent?.split(" · ")[0] || "");
});
$("multiSavePolicy").addEventListener("click", saveMultiPolicy);
$("multiRefreshOps").addEventListener("click", refreshMultiOps);
$("pseudoStreamPlan").addEventListener("click", planPseudoStream);
$("dashboardProjectionRefresh").addEventListener("click", refreshDashboardProjection);
$("launchChecklistRefresh").addEventListener("click", refreshLaunchChecklist);
$("providerHistoryRefresh").addEventListener("click", refreshProviderHistory);
$("licenseFunnelRefresh").addEventListener("click", refreshLicenseFunnel);
document.addEventListener("click", (event) => {
  const presetFocus = event.target.closest("[data-preset-focus]");
  if (presetFocus) focusPresetTask(presetFocus.dataset.presetFocus);
  const ragFocus = event.target.closest("[data-rag-focus]");
  if (ragFocus) focusRagTask(ragFocus.dataset.ragFocus);
  const routingFocus = event.target.closest("[data-routing-focus]");
  if (routingFocus) focusRoutingTask(routingFocus.dataset.routingFocus);
  const qualityFocus = event.target.closest("[data-quality-focus]");
  if (qualityFocus) focusQualityTask(qualityFocus.dataset.qualityFocus);
  const advancedFocus = event.target.closest("[data-advanced-focus]");
  if (advancedFocus) focusAdvancedTask(advancedFocus.dataset.advancedFocus);
  const routingButton = event.target.closest(".select-routing-model");
  if (routingButton) selectRoutingModel(routingButton.dataset.modelName || "");
  const knowledgeSpaceButton = event.target.closest(".select-knowledge-space");
  if (knowledgeSpaceButton) {
    selectKnowledgeSpace(knowledgeSpaceButton.dataset.spaceId || "", knowledgeSpaceButton.dataset.spaceName || "");
  }
  const knowledgeJobButton = event.target.closest(".select-knowledge-job");
  if (knowledgeJobButton) {
    $("knowledgeJobId").value = knowledgeJobButton.dataset.jobId || "";
    focusRagTask("index");
  }
  const selectButton = event.target.closest(".select-published-preset");
  if (selectButton) selectPublishedPresetFromElement(selectButton);
  const qualityButton = event.target.closest(".select-quality-feedback");
  if (qualityButton) {
    $("qualityFeedbackId").value = qualityButton.dataset.feedbackId || "";
    focusQualityTask("review");
  }
  const previewButton = event.target.closest(".preview-preset, .import-preset");
  if (previewButton) previewPresetImport(previewButton.dataset.presetId);
  const reportButton = event.target.closest(".report-preset");
  if (reportButton) reportPublishedPreset(reportButton.dataset.presetId);
  const unlistButton = event.target.closest(".unlist-published-preset");
  if (unlistButton) unlistPublishedPreset(unlistButton.dataset.presetId);
  const removeButton = event.target.closest(".remove-published-preset");
  if (removeButton) deletePublishedPreset(removeButton.dataset.presetId);
  // 행 전체 클릭으로 프리셋 ID 선택(직접 입력 제거).
  const localPick = event.target.closest(".pick-local-preset");
  if (localPick) selectLocalPresetFromElement(localPick);
});
// 로그인 상태(Discord OAuth): 로그인 버튼 / 로그아웃 / 유저 표시.
async function loadAuth() {
  let me;
  try {
    me = await (await fetch("/api/me", { headers: { Accept: "application/json" } })).json();
  } catch (e) {
    return;
  }
  const loginLink = $("discordLogin");
  const userSpan = $("authUser");
  const logoutBtn = $("logoutBtn");
  const ownerOnlyNotice = $("ownerOnlyNotice");
  if (me.authenticated) {
    userSpan.textContent = (me.admin ? "운영자 " : "운영자 권한 없음 ") + (me.userId || "");
    userSpan.style.display = "";
    logoutBtn.style.display = "";
    loginLink.style.display = "none";
    if (ownerOnlyNotice) ownerOnlyNotice.hidden = me.admin;
  } else if (me.oauthEnabled) {
    // OAuth 활성인데 미로그인(엣지) — 로그인 버튼 노출. 보통은 보호 경로라 자동 리디렉트됨.
    loginLink.style.display = "";
    userSpan.style.display = "none";
    logoutBtn.style.display = "none";
    if (ownerOnlyNotice) ownerOnlyNotice.hidden = true;
  }
}
$("logoutBtn").addEventListener("click", async () => {
  try {
    await fetch("/logout", { method: "POST" });
  } catch (e) {}
  location.href = "/admin/dashboard/";
});
// ── 라우터(entity-first): Overview → 서버(서브탭) → 채널(탭). ────────────────
function showPage(name) {
  const valid = ["overview", "server", "channel"];
  if (!valid.includes(name)) name = "overview";
  document.querySelectorAll("main .page").forEach((p) => {
    p.hidden = p.dataset.page !== name;
  });
  document.querySelectorAll("nav.side a").forEach((a) => {
    a.classList.toggle("active", a.dataset.page === name);
  });
}

document.querySelectorAll("nav.side a").forEach((a) => {
  a.addEventListener("click", (e) => {
    e.preventDefault();
    const name = a.dataset.page;
    if (history.replaceState) history.replaceState(null, "", `#${name}`);
    showPage(name);
  });
});

// 서버 상세 서브탭(요약·채널·참여PC·프리셋·정책).
function showServerTab(name) {
  document.querySelectorAll('main .page[data-page="server"] .tabpane').forEach((p) => {
    p.hidden = p.dataset.stab !== name;
  });
  document.querySelectorAll("#serverTabs .tab").forEach((b) => {
    b.classList.toggle("active", b.dataset.stab === name);
  });
  if (name === "providers") loadProviderPicker();
  if (name === "channels") loadChannelPicker();
  if (name === "presets") refreshPresets();
}
document.querySelectorAll("#serverTabs .tab").forEach((b) => {
  b.addEventListener("click", () => showServerTab(b.dataset.stab));
});

// 채널 상세 탭(채널AI·모델·지식·품질·고급).
function showChannelTab(name) {
  document.querySelectorAll('main .page[data-page="channel"] .tabpane').forEach((p) => {
    p.hidden = p.dataset.ctab !== name;
  });
  document.querySelectorAll("#channelTabs .tab").forEach((b) => {
    b.classList.toggle("active", b.dataset.ctab === name);
  });
  if (name === "model") {
    loadEffectiveRoutingPolicy().then(() => loadModelCandidates());
  }
  if (name === "rag") refreshKnowledge();
  if (name === "quality") refreshQualityDashboard();
  if (name === "advanced") refreshMultiOps();
}
document.querySelectorAll("#channelTabs .tab").forEach((b) => {
  b.addEventListener("click", () => showChannelTab(b.dataset.ctab));
});

// 채널 선택 = 클릭/입력 한 번으로 모든 채널-스코프 설정의 channelId 를 동기화(직접 입력 제거).
function openChannel(channelId, name) {
  const cid = String(channelId || "").trim();
  if (!/^\d+$/.test(cid)) {
    alert("채널 ID(숫자)를 입력하거나 목록에서 채널을 선택하세요.");
    return;
  }
  CHANNEL_ID_FIELDS.forEach((id) => {
    const el = $(id);
    if (el) el.value = cid;
  });
  const label = name && name.trim() ? name.trim() : `채널 ${cid}`;
  $("channelDetailName").textContent = `채널 상세 · ${label}`;
  $("channelCrumbName").textContent = label;
  $("channelCrumb").hidden = false;
  if (history.replaceState) history.replaceState(null, "", "#channel");
  showPage("channel");
  showChannelTab("channel-ai");
}

// 서버 채널 목록(클릭 → 채널 상세). renderAiNetwork 가 data.channels 로 호출.
function renderServerChannels(channels) {
  renderList(
    "serverChannelList",
    channels,
    "이 서버에 채널 AI가 없습니다. 위 ‘채널 선택’ 드롭다운에서 디스코드 채널을 골라 만들 수 있습니다.",
    (c) =>
      `<li class="channel-item" data-channel-id="${esc(c.channelId)}" data-channel-name="${esc(c.name || "")}">` +
      `<strong>#${esc(c.channelId)} · ${esc(c.name || "이름 없음")}</strong>` +
      `<span>${esc(c.readinessStatus || "")} · ${esc(c.purpose || "역할 미설정")} · 클릭하여 상세 →</span></li>`,
  );
}
$("serverChannelList")?.addEventListener("click", (e) => {
  const li = e.target.closest(".channel-item");
  if (li) openChannel(li.dataset.channelId, li.dataset.channelName);
});
$("channelBack")?.addEventListener("click", () => {
  showPage("server");
  showServerTab("channels");
});

// 채널 선택 드롭다운(서버의 실제 디스코드 텍스트 채널). 채널 ID 직접 입력 제거.
async function loadChannelPicker() {
  const sel = $("channelPicker");
  const gid = $("guildId").value.trim();
  if (!sel || !/^\d+$/.test(gid)) return;
  try {
    const channels = await getJson(`/api/dashboard/${gid}/channels`);
    sel.innerHTML = channels.length
      ? '<option value="">채널 선택…</option>' +
        channels.map((c) => `<option value="${esc(c.id)}">#${esc(c.name)} (${esc(c.id)})</option>`).join("")
      : '<option value="">연결된 채널 없음(봇 미연결)</option>';
  } catch (e) {
    sel.innerHTML = '<option value="">채널 목록 불가(권한/봇)</option>';
  }
}
$("channelPicker")?.addEventListener("change", (e) => {
  const opt = e.target.selectedOptions[0];
  if (e.target.value) openChannel(e.target.value, opt ? opt.textContent.replace(/^#/, "").replace(/\s*\(\d+\)$/, "") : "");
});

// 서버 모델 datalist(선호/요청/허용 모델 입력의 제안). 모델 지도에서 채운다.
async function loadServerModelOptions() {
  const dl = $("serverModelOptions");
  const gid = $("guildId").value.trim();
  if (!dl || !/^\d+$/.test(gid)) return;
  try {
    const models = await getJson(`/api/ai-network/${gid}/model-map`);
    const names = [...new Set((models || []).map((m) => m.modelName).filter(Boolean))];
    dl.innerHTML = names.map((n) => `<option value="${esc(n)}"></option>`).join("");
  } catch (e) {
    /* 권한 없음/빈 서버 → 제안 비움(직접 입력 가능) */
  }
}

// 서버 선택 드롭다운(봇이 들어가 있는 서버 목록). 18자리 ID 를 외워 입력하지 않게 한다.
async function loadGuildPicker() {
  const sel = $("guildPicker");
  if (!sel) return;
  try {
    const guilds = await getJson("/api/dashboard/guilds");
    sel.innerHTML = guilds.length
      ? '<option value="">서버 선택…</option>' +
        guilds.map((g) => `<option value="${esc(g.id)}">${esc(g.name)} (${esc(g.id)})</option>`).join("")
      : '<option value="">연결된 서버 없음 — ID 직접 입력</option>';
  } catch (e) {
    sel.innerHTML = '<option value="">서버 목록 불가(권한/봇) — ID 직접 입력</option>';
  }
}
$("guildPicker")?.addEventListener("change", (e) => {
  const v = e.target.value;
  if (!v) return;
  $("guildId").value = v;
  loadGuild();
});

// 참여 PC 선택 드롭다운(선택한 서버의 프로바이더). userId 직접 입력 대신 클릭 선택.
async function loadProviderPicker() {
  const sel = $("safetyProviderPicker");
  const gid = $("guildId").value.trim();
  if (!sel || !/^\d+$/.test(gid)) return;
  try {
    const providers = await getJson(`/api/ai-network/${gid}/providers?audience=admin`);
    const rows = (providers || []).filter((p) => p.providerUserId != null);
    sel.innerHTML = rows.length
      ? '<option value="">참여 PC 선택…</option>' +
        rows
          .map((p) => `<option value="${esc(p.providerUserId)}">${esc(p.providerLabel)} · ${esc(p.state)} · 모델 ${esc(p.modelCount)}</option>`)
          .join("")
      : '<option value="">참여 PC 없음 — ID 직접 입력</option>';
  } catch (e) {
    sel.innerHTML = '<option value="">목록 불가(권한) — ID 직접 입력</option>';
  }
}
$("safetyProviderPicker")?.addEventListener("change", (e) => {
  if (e.target.value) $("safetyProviderId").value = e.target.value;
});

// ── NEXA 사회적 참여 설정(T019, 웹 대시보드 전용) ──────────────────────────────
// shadow/live 상태·데이터 처리 동의·롤백을 한 화면에서 제어한다. 위험한 LIVE 전환은 확인란을 요구한다.
const NEXA_REAL_SEND_LANES = ["CANARY", "LIVE"];

function nexaSelectedLane() {
  return ($("nexaLane")?.value || "LEGACY").trim().toUpperCase();
}

// 선택한 단계가 실제 발화(CANARY/LIVE)면 경고·확인란을 노출한다(T019 위험 전환 확인 요구).
function nexaUpdateLiveWarning() {
  const needsConfirm = NEXA_REAL_SEND_LANES.includes(nexaSelectedLane());
  const warn = $("nexaLiveWarning");
  const row = $("nexaConfirmRow");
  if (warn) warn.hidden = !needsConfirm;
  if (row) row.hidden = !needsConfirm;
  if (!needsConfirm && $("nexaConfirmLiveSend")) $("nexaConfirmLiveSend").checked = false;
}

async function nexaRefreshStatus() {
  const gid = $("guildId").value.trim();
  const list = $("nexaStatus");
  if (!list) return;
  if (!/^\d+$/.test(gid)) {
    list.innerHTML = '<li class="empty">서버 ID를 먼저 선택/입력하세요.</li>';
    return;
  }
  try {
    const s = await getJson(`/api/ai-network/nexa/${gid}/settings`);
    if ($("nexaLane")) $("nexaLane").value = s.guildLane || "LEGACY";
    nexaUpdateLiveWarning();
    const c = s.consent || {};
    const yn = (v) => (v ? "동의함" : "—");
    list.innerHTML = [
      `<li>현재 단계: <strong>${esc(s.guildLane)}</strong> · 실제 발화 ${s.realSendActive ? "ON" : "OFF(전송 0)"}</li>`,
      `<li>말 많음 후보값(승인 대기): ${esc(s.talkativenessCandidate)}</li>`,
      `<li>데이터 처리 동의 — 관찰: ${yn(c.observeScope)} · 외부 GLM: ${yn(c.externalGlmAllowed)} · 실제 전송: ${yn(c.liveSendAllowed)} · 학습: ${yn(c.learningOptIn)}</li>`,
    ].join("");
  } catch (e) {
    list.innerHTML = `<li class="empty">상태 조회 실패: ${esc(e.message)}</li>`;
  }
}

async function nexaApplyLane() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    nexaRefreshStatus();
    return;
  }
  const lane = nexaSelectedLane();
  const confirmLiveSend = !!$("nexaConfirmLiveSend")?.checked;
  if (NEXA_REAL_SEND_LANES.includes(lane) && !confirmLiveSend) {
    $("nexaStatus").innerHTML = '<li class="empty">실제 발화(CANARY/LIVE) 전환은 확인란을 체크해야 합니다.</li>';
    return;
  }
  try {
    await postJson(`/api/ai-network/nexa/${gid}/lane`, {
      lane,
      confirmLiveSend,
      reason: ($("nexaLaneReason")?.value || "").trim(),
    });
    await nexaRefreshStatus();
  } catch (e) {
    $("nexaStatus").innerHTML = `<li class="empty">적용 실패: ${esc(e.message)}</li>`;
  }
}

async function nexaRollbackLegacy() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) return;
  try {
    // 롤백(끄기)은 안전 방향이라 확인 불필요(confirmLiveSend=false).
    await postJson(`/api/ai-network/nexa/${gid}/lane`, { lane: "LEGACY", confirmLiveSend: false, reason: "대시보드 롤백" });
    await nexaRefreshStatus();
  } catch (e) {
    $("nexaStatus").innerHTML = `<li class="empty">롤백 실패: ${esc(e.message)}</li>`;
  }
}

$("nexaLane")?.addEventListener("change", nexaUpdateLiveWarning);
$("nexaRefresh")?.addEventListener("click", nexaRefreshStatus);
$("nexaApplyLane")?.addEventListener("click", nexaApplyLane);
$("nexaRollbackLegacy")?.addEventListener("click", nexaRollbackLegacy);

showPage((location.hash || "#overview").slice(1));
showServerTab("summary");
loadAuth();
loadWizardOptions();
loadGuildPicker();
refreshPool();
refreshLicenseFunnel();
setInterval(refreshPool, 5000);

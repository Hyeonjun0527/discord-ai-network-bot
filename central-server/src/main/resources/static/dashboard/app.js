// 대시보드 앱(차수 14). 빌드 불필요 바닐라 JS. 읽기전용 API 폴링 + 렌더.
"use strict";

const $ = (id) => document.getElementById(id);
let pendingPresetImport = null;

function esc(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

async function getJson(url) {
  const res = await fetch(url, { headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`${res.status} ${url}`);
  return res.json();
}

async function postJson(url, body) {
  const res = await fetch(url, {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${res.status} ${url}`);
  return res.json();
}

async function deleteJson(url) {
  const res = await fetch(url, { method: "DELETE", headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`${res.status} ${url}`);
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
  } catch (e) {
    badge.textContent = "연결 실패";
    badge.className = "badge bad";
  }
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
    $("wizardPreview").textContent = "길드 ID와 채널 ID를 숫자로 입력하세요.";
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

function renderKnowledgeIndexing(ops) {
  const commands = ops.commands || [];
  renderList("knowledgeIndexing", [
    ["상태", ops.status || "unknown"],
    ["색인 가능 소스", `${ops.indexableSourceCount ?? 0}개`],
    ["차단 소스", `${ops.blockedSourceCount ?? 0}개`],
    ["실행 명령", commands[0] || ops.nextActions?.[0] || "색인할 작업 없음"],
  ], "색인 작업 없음", ([label, value]) => `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`);
}

async function refreshKnowledge() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    $("knowledgeResult").textContent = "길드 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const [readiness, quality, ops] = await Promise.all([
      getJson(`/api/ai-network/knowledge/${gid}/readiness`),
      getJson(`/api/ai-network/knowledge/${gid}/quality-summary`),
      getJson(`/api/ai-network/knowledge/${gid}/indexing-operations`),
    ]);
    renderKnowledgeReadiness(readiness, quality);
    renderKnowledgeIndexing(ops);
  } catch (e) {
    $("knowledgeResult").textContent = `RAG 상태 로딩 실패: ${e.message}`;
  }
}

async function createKnowledgeSpace() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    $("knowledgeResult").textContent = "길드 ID를 숫자로 입력하세요.";
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
    $("knowledgeResult").textContent = "길드 ID와 지식공간 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/knowledge/${gid}/spaces/${spaceId}/sources`, knowledgeSourcePayload());
    $("knowledgeResult").textContent = `지식 소스 추가 완료: source=${result.id} · ${result.status} · risk=${result.riskLevel}`;
    await refreshKnowledge();
  } catch (e) {
    $("knowledgeResult").textContent = `지식 소스 추가 실패: ${e.message}`;
  }
}

function presetBehaviorPayload() {
  const maxCandidates = Number($("presetMaxCandidates").value || "1");
  return {
    purpose: $("presetPurpose").value.trim() || "general_assistant",
    tone: $("presetTone").value.trim() || "friendly",
    answerLength: "balanced",
    constitution: $("presetConstitution").value.trim() || null,
    responseMode: $("presetResponseMode").value.trim() || "balanced",
    preferredModel: $("presetPreferredModel").value.trim() || null,
    maxCandidates: Number.isFinite(maxCandidates) ? Math.max(1, Math.min(5, maxCandidates)) : 1,
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

function renderPresetLists(local, published) {
  renderList("localPresetList", local?.presets?.slice(0, 8), "서버 프리셋 없음", (p) =>
    `<li><strong>${esc(p.id)} · ${esc(p.name)}</strong><span>${esc(p.category)} · ${esc(p.status)} · ${esc(p.visibility)}</span></li>`,
  );
  renderList("publishedPresetList", published?.presets?.slice(0, 8), "게시 프리셋 없음", (p) =>
    `<li><strong>${esc(p.id)} · ${esc(p.title)}</strong><span>좋아요 ${esc(p.likeCount)} · 가져오기 ${esc(p.importCount)} · ${esc(p.category || "general")}</span><button class="mini preview-preset" data-preset-id="${esc(p.id)}">미리보기</button></li>`,
  );
}

async function refreshPresets() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    $("presetManageResult").textContent = "길드 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    pendingPresetImport = null;
    $("presetConfirmImport").disabled = true;
    $("presetImportPreview").textContent =
      "가져올 프리셋의 [미리보기]를 먼저 누르면, 덮어쓰기/승인 필요 여부를 확인한 뒤 가져올 수 있습니다.";
    const [local, published] = await Promise.all([
      getJson(`/api/ai-network/presets/guilds/${gid}`),
      getJson(presetCatalogUrl()),
    ]);
    renderPresetLists(local, published);
    $("presetManageResult").textContent =
      `서버 프리셋 ${local.presets?.length || 0}개 · 웹 카탈로그 ${published.presets?.length || 0}개`;
  } catch (e) {
    $("presetManageResult").textContent = `프리셋 로딩 실패: ${e.message}`;
  }
}

async function createPreset() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    $("presetManageResult").textContent = "길드 ID를 숫자로 입력하세요.";
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
      headers: { Accept: "application/json", "Content-Type": "application/json" },
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

function multiNumber(id, fallback, min, max) {
  const value = Number($(id).value || fallback);
  if (!Number.isFinite(value)) return fallback;
  return Math.max(min, Math.min(max, value));
}

function multiPolicyPayload() {
  return {
    channelId: numericValue("multiChannelId"),
    mode: $("multiMode").value,
    maxCandidates: multiNumber("multiMaxCandidates", 1, 1, 5),
    requireDistinctModels: $("multiDistinctModels").checked,
    providerDailyLimit: multiNumber("multiProviderDailyLimit", 0, 0, 100000),
    timeoutSeconds: multiNumber("multiTimeoutSeconds", 120, 10, 300),
    synthesisEnabled: $("multiSynthesis").checked,
  };
}

function renderMultiOps(summary) {
  renderList("multiOps", [
    ["상태", summary.status || "unknown"],
    ["고급 모드 안전", summary.safeToEnableAdvanced ? "가능" : "주의 필요"],
    ["최근 실행", `${summary.recentRunCount ?? 0}건`],
    ["평균 후보 수", summary.averageActualFanout ?? 0],
    ["fallback", `${summary.fallbackRunCount ?? 0}건`],
    ["위험 코드", (summary.riskCodes || []).join(", ") || "없음"],
  ], "다중응답 운영 데이터 없음", ([label, value]) => `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`);
  renderList("multiProviderLoad", summary.providerLoads?.slice(0, 8), "Provider 부하 데이터 없음", (p) =>
    `<li><strong>${esc(p.providerLabel || p.providerUserId || "provider")}</strong><span>${esc(p.loadRisk)} · 후보 ${esc(p.candidateCount)} · timeout ${esc(p.timeoutCount)} · 품질 ${esc(p.averageQualityScore)}</span></li>`,
  );
}

async function refreshMultiOps() {
  const gid = $("guildId").value.trim();
  const channelId = $("multiChannelId").value.trim();
  if (!/^\d+$/.test(gid)) {
    $("multiResult").textContent = "길드 ID를 숫자로 입력하세요.";
    return;
  }
  const qs = /^\d+$/.test(channelId) ? `?channelId=${channelId}` : "";
  try {
    const data = await getJson(`/api/ai-network/multi-response/${gid}/operations-summary${qs}`);
    const summary = data.summary || {};
    renderMultiOps(summary);
    $("multiResult").textContent = [
      `다중응답 상태: ${summary.status || "unknown"}`,
      `고급 모드 안전: ${summary.safeToEnableAdvanced ? "yes" : "no"}`,
      "",
      "[다음 액션]",
      ...((summary.nextActions || []).length ? summary.nextActions.map((a) => `- ${a}`) : ["- 없음"]),
    ].join("\n");
  } catch (e) {
    $("multiResult").textContent = `다중응답 운영 상태 로딩 실패: ${e.message}`;
  }
}

async function saveMultiPolicy() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    $("multiResult").textContent = "길드 ID를 숫자로 입력하세요.";
    return;
  }
  try {
    const result = await postJson(`/api/ai-network/multi-response/${gid}/policy`, multiPolicyPayload());
    $("multiResult").textContent = `다중응답 정책 저장 완료: policy=${result.id} · ${result.mode} · candidates=${result.maxCandidates}`;
    await refreshMultiOps();
  } catch (e) {
    $("multiResult").textContent = `다중응답 정책 저장 실패: ${e.message}`;
  }
}

async function planPseudoStream() {
  const answer = $("pseudoStreamAnswer").value.trim();
  if (!answer) {
    $("multiResult").textContent = "미리보기할 긴 답변을 입력하세요.";
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
  } catch (e) {
    $("multiResult").textContent = `수정 스냅샷 계산 실패: ${e.message}`;
  }
}

function renderAiNetwork(data) {
  $("aiNetwork").hidden = false;
  $("networkTitle").textContent = data.overview?.displayName || "AI 네트워크";
  $("networkSummary").textContent = data.overview?.tagline || "여러 사용자의 로컬 AI를 안전하게 연결합니다.";
  const readiness = data.readiness?.status || data.overview?.healthStatus || "unknown";
  $("readinessBadge").textContent = readiness;
  $("readinessBadge").className = `pill ${readiness === "ready" ? "ok" : readiness === "blocked" ? "bad" : "warn"}`;
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
  renderLaunchChecklist(data.launchChecklist || null);
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
    alert("길드 ID(숫자)를 입력하세요.");
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
    "",
    "가져오면 일어나는 일",
    ...actions,
    "",
    "확인할 점",
    ...conflictLines,
    "",
    "문제가 없으면 [미리보기한 프리셋 가져오기]를 누르세요.",
  ].filter(Boolean).join("\n");
}

async function previewPresetImport(publishedPresetId) {
  const gid = $("guildId").value.trim();
  const channelId = $("wizardChannelId").value.trim();
  if (!/^\d+$/.test(gid) || !/^\d+$/.test(channelId)) {
    $("presetImportResult").textContent = "프리셋을 가져오려면 길드 ID와 채널 ID를 먼저 입력하세요.";
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
    $("presetImportResult").textContent =
      `미리보기 완료: 충돌 ${pendingPresetImport.conflictCount}건 · 가져오려면 확인 버튼을 누르세요.`;
  } catch (e) {
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
      `가져오기 완료: ${imported.status} · channelAi=${imported.createdChannelAiId || "-"} · ` +
      `충돌 ${pendingPresetImport.conflictCount}건 확인`;
    pendingPresetImport = null;
    $("presetConfirmImport").disabled = true;
    await loadGuild();
  } catch (e) {
    $("presetImportResult").textContent = `프리셋 가져오기 실패: ${e.message}`;
  }
}

// 길드 상세(#198 개요 / #201 로그 / #202 차트)
async function loadGuild() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    alert("길드 ID(숫자)를 입력하세요.");
    return;
  }
  try {
    const [overview, trend, requests, providers, aiNetwork, launchChecklist] = await Promise.all([
      getJson(`/api/dashboard/${gid}/overview`),
      getJson(`/api/dashboard/${gid}/usage-trend?days=7`),
      getJson(`/api/dashboard/${gid}/requests`),
      getJson(`/api/metrics/pool/${gid}`),
      getJson(`/api/ai-network/${gid}/dashboard?audience=admin`),
      getJson(`/api/ai-network/${gid}/launch-checklist?audience=admin`),
    ]);

    $("guildOverview").innerHTML = [
      ["활성 프로바이더", overview.activeProviders],
      ["총 요청", overview.totalRequests],
      ["기본 모델", overview.defaultModel || "(자동)"],
      ["언어", overview.language],
      ["자동승인", overview.autoApprove ? "예" : "아니오"],
    ].map(([l, v]) => `<div class="stat"><div class="num">${v}</div><div class="lbl">${l}</div></div>`).join("");

    renderAiNetwork({ ...aiNetwork, launchChecklist });

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
  } catch (e) {
    alert(`불러오기 실패: ${e.message}`);
  }
}

// 정책 쓰기(#203/#204) — OAuth 활성 시에만 성공. 같은 출처라 쿠키 세션 자동 첨부.
async function postWrite(path, params) {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    alert("길드 ID(숫자)를 먼저 입력하세요.");
    return;
  }
  const qs = new URLSearchParams(params).toString();
  try {
    const res = await fetch(`/api/dashboard/${gid}/${path}?${qs}`, { method: "POST" });
    $("writeResult").textContent = res.ok ? `✅ 적용됨 (${path})` : `⛔ 실패 ${res.status}(인증 필요?)`;
  } catch (e) {
    $("writeResult").textContent = `오류: ${e.message}`;
  }
}

$("loadGuild").addEventListener("click", loadGuild);
$("saveWelcome").addEventListener("click", () => postWrite("welcome", { message: $("welcomeMsg").value }));
$("autoApproveOn").addEventListener("click", () => postWrite("auto-approve", { enabled: "true" }));
$("autoApproveOff").addEventListener("click", () => postWrite("auto-approve", { enabled: "false" }));
$("wizardDraft").addEventListener("click", draftChannelAi);
$("wizardCreate").addEventListener("click", createChannelAi);
$("knowledgeCreateSpace").addEventListener("click", createKnowledgeSpace);
$("knowledgeAddSource").addEventListener("click", addKnowledgeSource);
$("knowledgeRefresh").addEventListener("click", refreshKnowledge);
$("presetRefresh").addEventListener("click", refreshPresets);
$("presetCreate").addEventListener("click", createPreset);
$("presetUpdate").addEventListener("click", updatePreset);
$("presetPublish").addEventListener("click", publishPreset);
$("presetDelete").addEventListener("click", deletePreset);
$("presetLike").addEventListener("click", likePreset);
$("presetConfirmImport").addEventListener("click", importPreset);
$("multiSavePolicy").addEventListener("click", saveMultiPolicy);
$("multiRefreshOps").addEventListener("click", refreshMultiOps);
$("pseudoStreamPlan").addEventListener("click", planPseudoStream);
$("launchChecklistRefresh").addEventListener("click", refreshLaunchChecklist);
document.addEventListener("click", (event) => {
  const button = event.target.closest(".preview-preset, .import-preset");
  if (button) previewPresetImport(button.dataset.presetId);
});
loadWizardOptions();
refreshPool();
setInterval(refreshPool, 5000);

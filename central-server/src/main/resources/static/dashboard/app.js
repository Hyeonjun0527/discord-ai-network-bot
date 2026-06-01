// 대시보드 앱(차수 14). 빌드 불필요 바닐라 JS. 읽기전용 API 폴링 + 렌더.
"use strict";

const $ = (id) => document.getElementById(id);

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
    `<li><strong>${esc(p.title)}</strong><span>좋아요 ${esc(p.likeCount)} · 가져오기 ${esc(p.importCount)} · ${esc(p.publisherLabel)}</span></li>`,
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
}

// 길드 상세(#198 개요 / #201 로그 / #202 차트)
async function loadGuild() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    alert("길드 ID(숫자)를 입력하세요.");
    return;
  }
  try {
    const [overview, trend, requests, providers, aiNetwork] = await Promise.all([
      getJson(`/api/dashboard/${gid}/overview`),
      getJson(`/api/dashboard/${gid}/usage-trend?days=7`),
      getJson(`/api/dashboard/${gid}/requests`),
      getJson(`/api/metrics/pool/${gid}`),
      getJson(`/api/ai-network/${gid}/dashboard?audience=admin`),
    ]);

    $("guildOverview").innerHTML = [
      ["활성 프로바이더", overview.activeProviders],
      ["총 요청", overview.totalRequests],
      ["기본 모델", overview.defaultModel || "(자동)"],
      ["언어", overview.language],
      ["자동승인", overview.autoApprove ? "예" : "아니오"],
    ].map(([l, v]) => `<div class="stat"><div class="num">${v}</div><div class="lbl">${l}</div></div>`).join("");

    renderAiNetwork(aiNetwork);

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
refreshPool();
setInterval(refreshPool, 5000);

// 대시보드 앱(차수 14). 빌드 불필요 바닐라 JS. 읽기전용 API 폴링 + 렌더.
"use strict";

const $ = (id) => document.getElementById(id);

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

// 길드 상세(#198 개요 / #201 로그 / #202 차트)
async function loadGuild() {
  const gid = $("guildId").value.trim();
  if (!/^\d+$/.test(gid)) {
    alert("길드 ID(숫자)를 입력하세요.");
    return;
  }
  try {
    const [overview, trend, requests, providers] = await Promise.all([
      getJson(`/api/dashboard/${gid}/overview`),
      getJson(`/api/dashboard/${gid}/usage-trend?days=7`),
      getJson(`/api/dashboard/${gid}/requests`),
      getJson(`/api/metrics/pool/${gid}`),
    ]);

    $("guildOverview").innerHTML = [
      ["활성 프로바이더", overview.activeProviders],
      ["총 요청", overview.totalRequests],
      ["기본 모델", overview.defaultModel || "(자동)"],
      ["언어", overview.language],
      ["자동승인", overview.autoApprove ? "예" : "아니오"],
    ].map(([l, v]) => `<div class="stat"><div class="num">${v}</div><div class="lbl">${l}</div></div>`).join("");

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

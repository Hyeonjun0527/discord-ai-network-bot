import { captureConsoleError, type BugsinkApiContext } from "./bugsink";

export type GuildSummary = {
  id: string | number;
  name: string;
};

export type DashboardState = {
  guilds: GuildSummary[];
  overview: Record<string, unknown> | null;
  aiNetwork: Record<string, unknown> | null;
  requests: Record<string, unknown>[];
  usageTrend: Record<string, unknown>[];
};

type ApiOptions = {
  baseUrl: string;
  adminToken: string;
};

const REQUEST_ID_HEADER = "X-Request-Id";

function apiBase(baseUrl: string) {
  return baseUrl.trim().replace(/\/$/, "");
}

function createRequestId() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function resolveServerBaseUrl(baseUrl: string) {
  return apiBase(baseUrl) || window.location.origin;
}

function buildApiContext(
  path: string,
  options: ApiOptions,
  requestId: string,
  httpStatus?: number,
): BugsinkApiContext {
  return {
    requestId,
    method: "GET",
    apiEndpoint: path.split("?")[0] || path,
    httpStatus,
    serverBaseUrl: resolveServerBaseUrl(options.baseUrl),
  };
}

async function requestJson<T>(path: string, options: ApiOptions): Promise<T> {
  const requestId = createRequestId();
  const url = `${apiBase(options.baseUrl)}${path}`;
  const headers: Record<string, string> = { Accept: "application/json", [REQUEST_ID_HEADER]: requestId };
  if (options.adminToken.trim()) {
    headers["X-Dashboard-Admin-Token"] = options.adminToken.trim();
  }
  let response: Response;
  try {
    response = await fetch(url, { headers, credentials: "include" });
  } catch (error) {
    captureConsoleError(error, buildApiContext(path, options, requestId));
    throw error;
  }
  if (!response.ok) {
    const body = await response.text();
    const error = new Error(`${response.status} ${path}${body ? `: ${body.slice(0, 180)}` : ""}`);
    captureConsoleError(error, buildApiContext(path, options, requestId, response.status));
    throw error;
  }
  return (await response.json()) as T;
}

export async function loadDashboard(guildId: string, options: ApiOptions): Promise<DashboardState> {
  const [guilds, overview, aiNetwork, requests, usageTrend] = await Promise.all([
    requestJson<GuildSummary[]>("/api/dashboard/guilds", options).catch(() => []),
    guildId ? requestJson<Record<string, unknown>>(`/api/dashboard/${guildId}/overview`, options) : null,
    guildId
      ? requestJson<Record<string, unknown>>(`/api/ai-network/${guildId}/dashboard`, options).catch(() => null)
      : null,
    guildId ? requestJson<Record<string, unknown>[]>(`/api/dashboard/${guildId}/requests`, options).catch(() => []) : [],
    guildId
      ? requestJson<Record<string, unknown>[]>(`/api/dashboard/${guildId}/usage-trend?days=14`, options).catch(() => [])
      : [],
  ]);

  return {
    guilds,
    overview,
    aiNetwork,
    requests,
    usageTrend,
  };
}

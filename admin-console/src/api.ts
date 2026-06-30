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

export type ApiOptions = {
  baseUrl: string;
  adminToken: string;
};

export type NiaFewShotScope = {
  type: "GLOBAL" | "GUILD" | "CHANNEL" | "PERSONA";
  guildId?: number | null;
  channelId?: number | null;
  persona?: string | null;
};

export type NiaFewShotRawMessage = {
  ref: string;
  authorRole: string;
  offsetMs: number;
  text: string;
};

export type NiaFewShotBadAlternative = {
  action: string;
  whyBad: string;
};

export type NiaFewShotExample = {
  id?: number | null;
  title: string;
  rawMessages: NiaFewShotRawMessage[];
  expectedAction: string;
  reason: string;
  evidenceRefs: string[];
  badAlternative: NiaFewShotBadAlternative;
  tags: string[];
  priority: number;
  privacyClass?: string;
  evalStatus?: string;
};

export type NiaFewShotVersion = {
  id?: number | null;
  setId?: number | null;
  version: number;
  status: "DRAFT" | "ACTIVE" | "ARCHIVED";
  examples: NiaFewShotExample[];
  createdBy?: number | null;
  reviewedBy?: number | null;
  publishedAt?: string | null;
  rollbackOfVersion?: number | null;
  createdAt: string;
  updatedAt: string;
};

export type NiaFewShotSet = {
  id?: number | null;
  scope: NiaFewShotScope;
  activeVersion?: number | null;
  versions: NiaFewShotVersion[];
  createdAt: string;
  updatedAt: string;
};

export type NiaFewShotEval = {
  status: "PASS" | "FAIL";
  readyForPublish: boolean;
  checkedExamples: number;
  actionCoverage: Record<string, number>;
  failures: string[];
};

export type NiaFewShotPreview = Record<string, unknown>;

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
  method: string,
  httpStatus?: number,
): BugsinkApiContext {
  return {
    requestId,
    method,
    apiEndpoint: path.split("?")[0] || path,
    httpStatus,
    serverBaseUrl: resolveServerBaseUrl(options.baseUrl),
  };
}

async function requestJson<T>(
  path: string,
  options: ApiOptions,
  init: { method?: string; body?: unknown } = {},
): Promise<T> {
  const requestId = createRequestId();
  const url = `${apiBase(options.baseUrl)}${path}`;
  const headers: Record<string, string> = { Accept: "application/json", [REQUEST_ID_HEADER]: requestId };
  const method = init.method ?? "GET";
  if (options.adminToken.trim()) {
    headers["X-Dashboard-Admin-Token"] = options.adminToken.trim();
  }
  if (init.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  let response: Response;
  try {
    response = await fetch(url, {
      method,
      headers,
      credentials: "include",
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
    });
  } catch (error) {
    captureConsoleError(error, buildApiContext(path, options, requestId, method));
    throw error;
  }
  if (!response.ok) {
    const body = await response.text();
    const error = new Error(`${response.status} ${path}${body ? `: ${body.slice(0, 180)}` : ""}`);
    captureConsoleError(error, buildApiContext(path, options, requestId, method, response.status));
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

export async function loadFewShotSets(options: ApiOptions): Promise<NiaFewShotSet[]> {
  return requestJson<NiaFewShotSet[]>("/api/admin/nia/few-shot/sets", options);
}

export async function createFewShotDraft(
  options: ApiOptions,
  scope: NiaFewShotScope,
  examples: NiaFewShotExample[],
): Promise<NiaFewShotVersion> {
  return requestJson<NiaFewShotVersion>("/api/admin/nia/few-shot/sets", options, {
    method: "POST",
    body: { scope, examples },
  });
}

export async function replaceFewShotDraft(
  options: ApiOptions,
  setId: number,
  version: number,
  examples: NiaFewShotExample[],
): Promise<NiaFewShotVersion> {
  return requestJson<NiaFewShotVersion>(`/api/admin/nia/few-shot/sets/${setId}/drafts/${version}`, options, {
    method: "PUT",
    body: { examples },
  });
}

export async function previewFewShotDraft(
  options: ApiOptions,
  setId: number,
  version: number,
  redactRawText = false,
): Promise<NiaFewShotPreview> {
  return requestJson<NiaFewShotPreview>(`/api/admin/nia/few-shot/sets/${setId}/drafts/${version}/preview`, options, {
    method: "POST",
    body: { redactRawText },
  });
}

export async function evalFewShotDraft(options: ApiOptions, setId: number, version: number): Promise<NiaFewShotEval> {
  return requestJson<NiaFewShotEval>(`/api/admin/nia/few-shot/sets/${setId}/drafts/${version}/eval`, options, {
    method: "POST",
  });
}

export async function publishFewShotVersion(options: ApiOptions, setId: number, version: number): Promise<NiaFewShotSet> {
  return requestJson<NiaFewShotSet>(`/api/admin/nia/few-shot/sets/${setId}/versions/${version}/publish`, options, {
    method: "POST",
  });
}

export async function rollbackFewShotVersion(options: ApiOptions, setId: number, version: number): Promise<NiaFewShotSet> {
  return requestJson<NiaFewShotSet>(`/api/admin/nia/few-shot/sets/${setId}/versions/${version}/rollback`, options, {
    method: "POST",
  });
}

export async function archiveFewShotVersion(options: ApiOptions, setId: number, version: number): Promise<NiaFewShotVersion> {
  return requestJson<NiaFewShotVersion>(`/api/admin/nia/few-shot/sets/${setId}/versions/${version}/archive`, options, {
    method: "POST",
  });
}

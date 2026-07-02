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

type ApiErrorPayload = {
  success?: boolean;
  status?: number;
  requestId?: string;
  error?: {
    code?: string;
    message?: string;
    details?: Record<string, unknown>;
    currentState?: string;
    requiredState?: string;
    failedCondition?: string;
    blockedAction?: string;
    actionGuide?: string;
  };
};

export class ApiRequestError extends Error {
  readonly status: number;
  readonly path: string;
  readonly code?: string;
  readonly serverMessage?: string;
  readonly requestId?: string;
  readonly details?: Record<string, unknown>;
  readonly currentState?: string;
  readonly requiredState?: string;
  readonly failedCondition?: string;
  readonly blockedAction?: string;
  readonly actionGuide?: string;

  constructor(path: string, response: Response, payload: ApiErrorPayload | null) {
    const status = payload?.status ?? response.status;
    const code = payload?.error?.code;
    const serverMessage = payload?.error?.message;
    const requestId = payload?.requestId ?? response.headers.get(REQUEST_ID_HEADER) ?? undefined;
    const actionGuide = payload?.error?.actionGuide;
    const explanation = serverMessage ?? "서버가 구조화되지 않은 에러 응답을 보냈습니다.";
    const suffix = actionGuide ? ` (${actionGuide})` : "";
    const requestSuffix = requestId ? ` [requestId=${requestId}]` : "";
    super(`${status} ${path}: ${code ? `${code}: ` : ""}${explanation}${suffix}${requestSuffix}`);
    this.name = "ApiRequestError";
    this.status = status;
    this.path = path;
    this.code = code;
    this.serverMessage = serverMessage;
    this.requestId = requestId;
    this.details = payload?.error?.details;
    this.currentState = payload?.error?.currentState;
    this.requiredState = payload?.error?.requiredState;
    this.failedCondition = payload?.error?.failedCondition;
    this.blockedAction = payload?.error?.blockedAction;
    this.actionGuide = actionGuide;
  }
}

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
  serverRequestId?: string,
  errorCode?: string,
): BugsinkApiContext {
  return {
    requestId,
    serverRequestId,
    method,
    apiEndpoint: path.split("?")[0] || path,
    httpStatus,
    errorCode,
    serverBaseUrl: resolveServerBaseUrl(options.baseUrl),
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value : undefined;
}

function optionalNumber(value: unknown): number | undefined {
  return typeof value === "number" && Number.isInteger(value) ? value : undefined;
}

function parseApiErrorPayload(body: string): ApiErrorPayload | null {
  if (!body.trim()) return null;
  try {
    const parsed: unknown = JSON.parse(body);
    if (!isRecord(parsed) || !isRecord(parsed.error)) return null;
    return {
      success: typeof parsed.success === "boolean" ? parsed.success : undefined,
      status: optionalNumber(parsed.status),
      requestId: optionalString(parsed.requestId),
      error: {
        code: optionalString(parsed.error.code),
        message: optionalString(parsed.error.message),
        details: isRecord(parsed.error.details) ? parsed.error.details : undefined,
        currentState: optionalString(parsed.error.currentState),
        requiredState: optionalString(parsed.error.requiredState),
        failedCondition: optionalString(parsed.error.failedCondition),
        blockedAction: optionalString(parsed.error.blockedAction),
        actionGuide: optionalString(parsed.error.actionGuide),
      },
    };
  } catch {
    return null;
  }
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
    const error = new ApiRequestError(path, response, parseApiErrorPayload(body));
    captureConsoleError(
      error,
      buildApiContext(path, options, requestId, method, response.status, error.requestId, error.code),
    );
    throw error;
  }
  return (await response.json()) as T;
}

export async function loadDashboard(guildId: string, options: ApiOptions): Promise<DashboardState> {
  const [guilds, overview, aiNetwork, requests, usageTrend] = await Promise.all([
    requestJson<GuildSummary[]>("/api/dashboard/guilds", options),
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

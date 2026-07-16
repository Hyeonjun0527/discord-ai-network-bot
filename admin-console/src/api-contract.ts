export type GuildSummary = {
  id: string | number;
  name: string;
};

export type DashboardPanel = "aiNetwork" | "requests" | "usageTrend";

export type DashboardPartialError = {
  panel: DashboardPanel;
  path: string;
  message: string;
  status?: number;
  code?: string;
  serverRequestId?: string;
};

export type DashboardState = {
  guilds: GuildSummary[];
  overview: Record<string, unknown> | null;
  aiNetwork: Record<string, unknown> | null;
  requests: Record<string, unknown>[];
  usageTrend: Record<string, unknown>[];
  partialErrors: DashboardPartialError[];
};

export type ApiOptions = {
  baseUrl: string;
  adminToken: string;
};

export type ApiMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export type RequestTarget = {
  fetchUrl: string;
  requestUrl: string;
  serverBaseUrl: string;
};

export const REQUEST_ID_HEADER = "X-Request-Id";

export type ApiErrorPayload = {
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

export class ApiResponseParseError extends Error {
  readonly status: number;
  readonly path: string;
  readonly code = "INVALID_RESPONSE_JSON";
  readonly requestId: string;
  readonly serverRequestId?: string;

  constructor(path: string, response: Response, requestId: string, cause: unknown) {
    const serverRequestId = response.headers.get(REQUEST_ID_HEADER) ?? undefined;
    const requestSuffix = serverRequestId ? ` [requestId=${serverRequestId}]` : "";
    super(`${response.status} ${path}: INVALID_RESPONSE_JSON: 서버 성공 응답이 JSON이 아닙니다.${requestSuffix}`);
    this.name = "ApiResponseParseError";
    this.status = response.status;
    this.path = path;
    this.requestId = requestId;
    this.serverRequestId = serverRequestId;
    this.cause = cause;
  }
}

export function apiBase(baseUrl: string): string {
  return baseUrl.trim().replace(/\/$/, "");
}

export function resolveOrigin(): string {
  return globalThis.location?.origin ?? "";
}

export function resolveRequestTarget(path: string, baseUrl: string, origin: string = resolveOrigin()): RequestTarget {
  const base = apiBase(baseUrl);
  const serverBaseUrl = base || origin;
  return {
    fetchUrl: `${base}${path}`,
    requestUrl: serverBaseUrl ? `${serverBaseUrl}${path}` : path,
    serverBaseUrl,
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

export function parseApiErrorPayload(body: string): ApiErrorPayload | null {
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

export function toPartialDashboardError(panel: DashboardPanel, path: string, error: unknown): DashboardPartialError {
  if (error instanceof ApiRequestError) {
    return {
      panel,
      path,
      message: error.message,
      status: error.status,
      code: error.code,
      serverRequestId: error.requestId,
    };
  }
  if (error instanceof ApiResponseParseError) {
    return {
      panel,
      path,
      message: error.message,
      status: error.status,
      code: error.code,
      serverRequestId: error.serverRequestId,
    };
  }
  return {
    panel,
    path,
    message: error instanceof Error ? error.message : "알 수 없는 API 오류가 발생했습니다.",
  };
}

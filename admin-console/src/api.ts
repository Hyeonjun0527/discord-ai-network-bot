import { captureConsoleError, type BugsinkApiContext } from "./bugsink";
import {
  ApiRequestError,
  ApiResponseParseError,
  REQUEST_ID_HEADER,
  type ApiMethod,
  type ApiOptions,
  type DashboardPanel,
  type DashboardPartialError,
  type DashboardState,
  type GuildSummary,
  parseApiErrorPayload,
  resolveRequestTarget,
  toPartialDashboardError,
} from "./api-contract";

export { ApiRequestError, ApiResponseParseError };
export type { ApiOptions, DashboardPartialError, DashboardPanel, DashboardState, GuildSummary };

type RequestJsonOptions = {
  method?: ApiMethod;
  body?: unknown;
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
  expectedReplies: string[];
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
  hardAmbiguousCount: number;
  failures: string[];
};

export type NiaFewShotPreview = Record<string, unknown>;
function createRequestId() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function buildApiContext(
  path: string,
  options: ApiOptions,
  requestId: string,
  method: ApiMethod,
  httpStatus?: number,
  serverRequestId?: string,
  errorCode?: string,
): BugsinkApiContext {
  const target = resolveRequestTarget(path, options.baseUrl);
  return {
    requestId,
    serverRequestId,
    method,
    apiEndpoint: path.split("?")[0] || path,
    requestUrl: target.requestUrl,
    httpStatus,
    errorCode,
    serverBaseUrl: target.serverBaseUrl,
  };
}

async function parseSuccessJson<T>(path: string, response: Response, requestId: string): Promise<T> {
  try {
    return (await response.json()) as T;
  } catch (error) {
    throw new ApiResponseParseError(path, response, requestId, error);
  }
}

async function requestJson<T>(path: string, options: ApiOptions, requestOptions: RequestJsonOptions = {}): Promise<T> {
  const method = requestOptions.method ?? "GET";
  const requestId = createRequestId();
  const target = resolveRequestTarget(path, options.baseUrl);
  const headers: Record<string, string> = { Accept: "application/json", [REQUEST_ID_HEADER]: requestId };
  if (options.adminToken.trim()) {
    headers["X-Dashboard-Admin-Token"] = options.adminToken.trim();
  }
  if (requestOptions.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  let response: Response;
  try {
    response = await fetch(target.fetchUrl, {
      method,
      headers,
      credentials: "include",
      body: requestOptions.body === undefined ? undefined : JSON.stringify(requestOptions.body),
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
  try {
    return await parseSuccessJson<T>(path, response, requestId);
  } catch (error) {
    captureConsoleError(
      error,
      buildApiContext(
        path,
        options,
        requestId,
        method,
        response.status,
        error instanceof ApiResponseParseError ? error.serverRequestId : undefined,
        error instanceof ApiResponseParseError ? error.code : undefined,
      ),
    );
    throw error;
  }
}

async function loadOptionalPanel<T>(
  panel: DashboardPanel,
  path: string,
  fallback: T,
  options: ApiOptions,
  partialErrors: DashboardPartialError[],
): Promise<T> {
  try {
    return await requestJson<T>(path, options);
  } catch (error) {
    partialErrors.push(toPartialDashboardError(panel, path, error));
    return fallback;
  }
}

export async function loadDashboard(guildId: string, options: ApiOptions): Promise<DashboardState> {
  const partialErrors: DashboardPartialError[] = [];
  const guildsPromise = requestJson<GuildSummary[]>("/api/dashboard/guilds", options);
  const overviewPromise = guildId ? requestJson<Record<string, unknown>>(`/api/dashboard/${guildId}/overview`, options) : null;
  const aiNetworkPromise = guildId
    ? loadOptionalPanel<Record<string, unknown> | null>(
        "aiNetwork",
        `/api/ai-network/${guildId}/dashboard`,
        null,
        options,
        partialErrors,
      )
    : null;
  const requestsPromise = guildId
    ? loadOptionalPanel<Record<string, unknown>[]>(
        "requests",
        `/api/dashboard/${guildId}/requests`,
        [],
        options,
        partialErrors,
      )
    : [];
  const usageTrendPromise = guildId
    ? loadOptionalPanel<Record<string, unknown>[]>(
        "usageTrend",
        `/api/dashboard/${guildId}/usage-trend?days=14`,
        [],
        options,
        partialErrors,
      )
    : [];

  const [guilds, overview, aiNetwork, requests, usageTrend] = await Promise.all([
    guildsPromise,
    overviewPromise,
    aiNetworkPromise,
    requestsPromise,
    usageTrendPromise,
  ]);

  return {
    guilds,
    overview,
    aiNetwork,
    requests,
    usageTrend,
    partialErrors,
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

export async function createFewShotDraftForSet(
  options: ApiOptions,
  setId: number,
  examples: NiaFewShotExample[],
): Promise<NiaFewShotVersion> {
  return requestJson<NiaFewShotVersion>(`/api/admin/nia/few-shot/sets/${setId}/drafts`, options, {
    method: "POST",
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

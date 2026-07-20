import { captureConsoleError, type BugsinkApiContext } from "./bugsink";
import {
  ApiRequestError,
  ApiResponseParseError,
  REQUEST_ID_HEADER,
  type ApiMethod,
  type ChannelSummary,
  type DashboardPanel,
  type DashboardPartialError,
  type DashboardState,
  type GuildSummary,
  parseApiErrorPayload,
  resolveRequestTarget,
  toPartialDashboardError,
} from "./api-contract";

export { ApiRequestError, ApiResponseParseError };
export type { ChannelSummary, DashboardPartialError, DashboardPanel, DashboardState, GuildSummary };

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
  deliveryMode?: string | null;
  whyBad: string;
};

export type NiaFewShotExample = {
  id?: number | null;
  title: string;
  rawMessages: NiaFewShotRawMessage[];
  expectedAction: string;
  expectedDeliveryMode?: string | null;
  expectedReplies: string[];
  badReplies: string[];
  currentState?: string | null;
  expectedReactionCode?: string | null;
  expectedReevaluateAfterMs?: number | null;
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

export type NiaExecutionMessage = {
  speaker: string;
  text: string;
};

export type NiaRetrievedConversation = {
  id: string;
  messages: NiaExecutionMessage[];
};

export type NiaExecution = {
  correlationId: string;
  guildId: number;
  channelId: number;
  recordedAt: string;
  outcome: string;
  speechOutcome?: string | null;
  willSpeak?: boolean | null;
  currentConversation: NiaExecutionMessage[];
  retrievedConversations: NiaRetrievedConversation[];
  niaReply: string[];
};
function createRequestId() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function buildApiContext(
  path: string,
  requestId: string,
  method: ApiMethod,
  httpStatus?: number,
  serverRequestId?: string,
  errorCode?: string,
): BugsinkApiContext {
  const target = resolveRequestTarget(path);
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

async function requestJson<T>(path: string, requestOptions: RequestJsonOptions = {}): Promise<T> {
  const method = requestOptions.method ?? "GET";
  const requestId = createRequestId();
  const target = resolveRequestTarget(path);
  const headers: Record<string, string> = { Accept: "application/json", [REQUEST_ID_HEADER]: requestId };
  if (requestOptions.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  let response: Response;
  try {
    response = await fetch(target.fetchUrl, {
      method,
      headers,
      credentials: "same-origin",
      body: requestOptions.body === undefined ? undefined : JSON.stringify(requestOptions.body),
    });
  } catch (error) {
    captureConsoleError(error, buildApiContext(path, requestId, method));
    throw error;
  }
  if (!response.ok) {
    const body = await response.text();
    const error = new ApiRequestError(path, response, parseApiErrorPayload(body));
    captureConsoleError(
      error,
      buildApiContext(path, requestId, method, response.status, error.requestId, error.code),
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
  partialErrors: DashboardPartialError[],
): Promise<T> {
  try {
    return await requestJson<T>(path);
  } catch (error) {
    partialErrors.push(toPartialDashboardError(panel, path, error));
    return fallback;
  }
}

export async function loadDashboard(guildId: string): Promise<DashboardState> {
  const partialErrors: DashboardPartialError[] = [];
  const guilds = await requestJson<GuildSummary[]>("/api/dashboard/guilds");
  const selectedGuildId = guilds.some((guild) => String(guild.id) === guildId) ? guildId : String(guilds[0]?.id ?? "");
  const overviewPromise = selectedGuildId ? requestJson<Record<string, unknown>>(`/api/dashboard/${selectedGuildId}/overview`) : null;
  const aiNetworkPromise = selectedGuildId
    ? loadOptionalPanel<Record<string, unknown> | null>(
        "aiNetwork",
        `/api/ai-network/${selectedGuildId}/dashboard`,
        null,
        partialErrors,
      )
    : null;
  const usageTrendPromise = selectedGuildId
    ? loadOptionalPanel<Record<string, unknown>[]>(
        "usageTrend",
        `/api/dashboard/${selectedGuildId}/usage-trend?days=14`,
        [],
        partialErrors,
      )
    : [];

  const [overview, aiNetwork, usageTrend] = await Promise.all([
    overviewPromise,
    aiNetworkPromise,
    usageTrendPromise,
  ]);

  return {
    selectedGuildId,
    guilds,
    overview,
    aiNetwork,
    requests: [],
    usageTrend,
    partialErrors,
  };
}

export async function loadGuildChannels(guildId: string): Promise<ChannelSummary[]> {
  if (!guildId) return [];
  return requestJson<ChannelSummary[]>(`/api/dashboard/${guildId}/channels`);
}

export async function loadGuildChannelRequests(
  guildId: string,
  channelId: string,
): Promise<Record<string, unknown>[]> {
  if (!guildId || !channelId) return [];
  return requestJson<Record<string, unknown>[]>(
    `/api/dashboard/${guildId}/requests?channelId=${encodeURIComponent(channelId)}&audience=admin`,
  );
}

export async function loadNiaExecutions(guildId: string, channelId: string): Promise<NiaExecution[]> {
  if (!guildId || !channelId) return [];
  return requestJson<NiaExecution[]>(
    `/api/ai-network/nexa/debug/participation/guilds/${encodeURIComponent(guildId)}` +
      `/channels/${encodeURIComponent(channelId)}/traces?limit=50`,
  );
}

export async function loadFewShotSets(): Promise<NiaFewShotSet[]> {
  return requestJson<NiaFewShotSet[]>("/api/admin/nia/few-shot/sets");
}

export async function createFewShotDraft(
  scope: NiaFewShotScope,
  examples: NiaFewShotExample[],
): Promise<NiaFewShotVersion> {
  return requestJson<NiaFewShotVersion>("/api/admin/nia/few-shot/sets", {
    method: "POST",
    body: { scope, examples },
  });
}

export async function replaceFewShotDraft(
  setId: number,
  version: number,
  examples: NiaFewShotExample[],
): Promise<NiaFewShotVersion> {
  return requestJson<NiaFewShotVersion>(`/api/admin/nia/few-shot/sets/${setId}/drafts/${version}`, {
    method: "PUT",
    body: { examples },
  });
}

export async function createFewShotDraftForSet(
  setId: number,
  examples: NiaFewShotExample[],
): Promise<NiaFewShotVersion> {
  return requestJson<NiaFewShotVersion>(`/api/admin/nia/few-shot/sets/${setId}/drafts`, {
    method: "POST",
    body: { examples },
  });
}

export async function previewFewShotDraft(
  setId: number,
  version: number,
  redactRawText = false,
): Promise<NiaFewShotPreview> {
  return requestJson<NiaFewShotPreview>(`/api/admin/nia/few-shot/sets/${setId}/drafts/${version}/preview`, {
    method: "POST",
    body: { redactRawText },
  });
}

export async function evalFewShotDraft(setId: number, version: number): Promise<NiaFewShotEval> {
  return requestJson<NiaFewShotEval>(`/api/admin/nia/few-shot/sets/${setId}/drafts/${version}/eval`, {
    method: "POST",
  });
}

export async function publishFewShotVersion(setId: number, version: number): Promise<NiaFewShotSet> {
  return requestJson<NiaFewShotSet>(`/api/admin/nia/few-shot/sets/${setId}/versions/${version}/publish`, {
    method: "POST",
  });
}

export async function rollbackFewShotVersion(setId: number, version: number): Promise<NiaFewShotSet> {
  return requestJson<NiaFewShotSet>(`/api/admin/nia/few-shot/sets/${setId}/versions/${version}/rollback`, {
    method: "POST",
  });
}

export async function archiveFewShotVersion(setId: number, version: number): Promise<NiaFewShotVersion> {
  return requestJson<NiaFewShotVersion>(`/api/admin/nia/few-shot/sets/${setId}/versions/${version}/archive`, {
    method: "POST",
  });
}

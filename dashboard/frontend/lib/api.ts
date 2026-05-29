/**
 * Typed API client for the FastAPI backend.
 *
 * All requests go through the Next.js rewrite rules (next.config.js),
 * which proxy /api/* and /auth/* to http://localhost:8000 in development.
 *
 * Usage:
 *   const config = await apiFetch<GuildConfig>('/api/guilds/123/config');
 */

import {
  getToken,
  clearToken,
  isTokenExpiringSoon,
  refreshToken,
} from "./auth";

/** Base URL for direct backend calls.  Defaults to "" so Next.js rewrites apply. */
const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "";

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string
  ) {
    super(message);
    this.name = "ApiError";
  }
}

/**
 * Fetch a JSON resource from the backend with automatic Authorization header.
 *
 * Throws `ApiError` on non-2xx responses.
 * Automatically clears the token and redirects to `/` on 401.
 */
export async function apiFetch<T>(
  path: string,
  init: RequestInit = {},
  // #85: 401 후 토큰 갱신에 성공하면 한 번 재시도한다. 무한 루프 방지용 내부 플래그.
  _retried = false
): Promise<T> {
  // 만료 임박 시 요청 전에 선제적으로 토큰을 갱신한다 (#85).
  // refresh 라우트 자체를 다시 갱신하려 들지 않도록 제외한다.
  if (!path.startsWith("/auth/refresh") && isTokenExpiringSoon()) {
    await refreshToken();
  }

  const token = getToken();
  const headers: HeadersInit = {
    "Content-Type": "application/json",
    ...(init.headers as Record<string, string> | undefined),
  };
  if (token) {
    (headers as Record<string, string>)["Authorization"] = `Bearer ${token}`;
  }

  const url = `${BASE_URL}${path}`;
  const response = await fetch(url, { ...init, headers });

  if (response.status === 401) {
    // #85: 만료 등으로 401 이 떨어지면 토큰 갱신을 한 번 시도하고 성공 시 재시도한다.
    if (!_retried && !path.startsWith("/auth/refresh")) {
      const refreshed = await refreshToken();
      if (refreshed) {
        return apiFetch<T>(path, init, true);
      }
    }
    // 갱신 실패 → Token is invalid / expired — log out
    clearToken();
    if (typeof window !== "undefined") {
      window.location.href = "/";
    }
    throw new ApiError(401, "Session expired. Please log in again.");
  }

  if (!response.ok) {
    let detail = `HTTP ${response.status}`;
    try {
      const body = await response.json();
      detail = body.detail ?? body.message ?? detail;
    } catch {
      // ignore parse errors
    }
    throw new ApiError(response.status, detail);
  }

  return response.json() as Promise<T>;
}

// ---------------------------------------------------------------------------
// Typed helpers for common endpoints
// ---------------------------------------------------------------------------

export interface GuildConfig {
  guild_id: number;
  model: string;
  summary_limit: number;
  language: string;
  provider: string;
  updated_at: string | null;
}

export interface GuildStats {
  total: number;
  by_command: { command: string; count: number }[];
  avg_latency_ms: number;
  error_rate: number;
  daily: { day: string; count: number }[];
}

export interface OllamaModelsResponse {
  models: { name: string; size: number; modified_at: string }[];
  error?: string;
}

// #78: 피드백 집계 응답 타입
export interface GuildFeedback {
  total: number;
  positive: number;
  negative: number;
  /** 긍정/(긍정+부정) 백분율. 평가가 없으면 null. */
  satisfaction: number | null;
  rating_distribution: { rating: number; count: number }[];
  by_command: {
    command: string;
    positive: number;
    negative: number;
    total: number;
  }[];
  recent: {
    message_id: number;
    user_id: number;
    rating: number;
    command: string | null;
    created_at: string;
  }[];
}

export const api = {
  getConfig: (guildId: string) =>
    apiFetch<GuildConfig>(`/api/guilds/${guildId}/config`),

  updateConfig: (guildId: string, data: Partial<GuildConfig>) =>
    apiFetch<GuildConfig>(`/api/guilds/${guildId}/config`, {
      method: "PUT",
      body: JSON.stringify(data),
    }),

  getStats: (guildId: string) =>
    apiFetch<GuildStats>(`/api/guilds/${guildId}/stats`),

  // #78: 길드 피드백 집계 조회
  getFeedback: (guildId: string, limit = 50) =>
    apiFetch<GuildFeedback>(`/api/guilds/${guildId}/feedback?limit=${limit}`),

  getApiKeyStatus: (guildId: string) =>
    apiFetch<{ has_key: boolean }>(`/api/guilds/${guildId}/api-key`),

  clearApiKey: (guildId: string) =>
    apiFetch<{ cleared: boolean }>(`/api/guilds/${guildId}/api-key`, {
      method: "DELETE",
    }),

  listModels: () => apiFetch<OllamaModelsResponse>("/api/models"),

  healthCheck: () => apiFetch<{ status: string }>("/health"),
};

/**
 * Typed API client for the FastAPI backend.
 *
 * All requests go through the Next.js rewrite rules (next.config.js),
 * which proxy /api/* and /auth/* to http://localhost:8000 in development.
 *
 * Usage:
 *   const config = await apiFetch<GuildConfig>('/api/guilds/123/config');
 */

import { getToken, clearToken } from "./auth";

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
  init: RequestInit = {}
): Promise<T> {
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
    // Token is invalid / expired — log out
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

  getApiKeyStatus: (guildId: string) =>
    apiFetch<{ has_key: boolean }>(`/api/guilds/${guildId}/api-key`),

  clearApiKey: (guildId: string) =>
    apiFetch<{ cleared: boolean }>(`/api/guilds/${guildId}/api-key`, {
      method: "DELETE",
    }),

  listModels: () => apiFetch<OllamaModelsResponse>("/api/models"),

  healthCheck: () => apiFetch<{ status: string }>("/health"),
};

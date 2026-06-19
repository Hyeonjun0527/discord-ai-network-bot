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

function apiBase(baseUrl: string) {
  return baseUrl.trim().replace(/\/$/, "");
}

async function requestJson<T>(path: string, options: ApiOptions): Promise<T> {
  const url = `${apiBase(options.baseUrl)}${path}`;
  const headers: Record<string, string> = { Accept: "application/json" };
  if (options.adminToken.trim()) {
    headers["X-Dashboard-Admin-Token"] = options.adminToken.trim();
  }
  const response = await fetch(url, { headers, credentials: "include" });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(`${response.status} ${path}${body ? `: ${body.slice(0, 180)}` : ""}`);
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

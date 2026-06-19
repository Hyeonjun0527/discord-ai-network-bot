import { Activity, Bot, CheckCircle2, RefreshCw, ServerCog, ShieldAlert } from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import { captureConsoleError, wasBugsinkReported } from "./bugsink";
import { DashboardState, loadDashboard } from "./api";

const API_BASE_STORAGE_KEY = "nexa-console-api-base";
const ADMIN_TOKEN_STORAGE_KEY = "nexa-console-admin-token";
const GUILD_ID_STORAGE_KEY = "nexa-console-guild-id";

const defaultApiBase = import.meta.env.VITE_CENTRAL_API_BASE_URL || "";

function readStorage(key: string, fallback: string) {
  return window.localStorage.getItem(key) ?? fallback;
}

function Field({
  label,
  value,
  onChange,
  type = "text",
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: "text" | "password";
  placeholder?: string;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <input value={value} onChange={(event) => onChange(event.target.value)} type={type} placeholder={placeholder} />
    </label>
  );
}

function Metric({ label, value }: { label: string; value: unknown }) {
  return (
    <article className="metric">
      <span>{label}</span>
      <strong>{String(value ?? "-")}</strong>
    </article>
  );
}

function JsonPanel({ title, value }: { title: string; value: unknown }) {
  return (
    <section className="panel json-panel">
      <h2>{title}</h2>
      <pre>{JSON.stringify(value ?? {}, null, 2)}</pre>
    </section>
  );
}

function App() {
  const [apiBase, setApiBase] = useState(() => readStorage(API_BASE_STORAGE_KEY, defaultApiBase));
  const [adminToken, setAdminToken] = useState(() => readStorage(ADMIN_TOKEN_STORAGE_KEY, ""));
  const [guildId, setGuildId] = useState(() => readStorage(GUILD_ID_STORAGE_KEY, ""));
  const [state, setState] = useState<DashboardState | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const overviewMetrics = useMemo(() => {
    const overview = state?.overview ?? {};
    return [
      ["온라인 제공자", overview.onlineProviders ?? overview.onlineProviderCount],
      ["총 제공자", overview.totalProviders ?? overview.providerCount],
      ["오늘 요청", overview.todayRequests ?? overview.requestCount],
      ["정책", overview.policyMode ?? overview.privacyMode],
    ] as const;
  }, [state]);

  useEffect(() => {
    window.localStorage.setItem(API_BASE_STORAGE_KEY, apiBase);
    window.localStorage.setItem(ADMIN_TOKEN_STORAGE_KEY, adminToken);
    window.localStorage.setItem(GUILD_ID_STORAGE_KEY, guildId);
  }, [apiBase, adminToken, guildId]);

  async function refresh() {
    setLoading(true);
    setError("");
    try {
      setState(await loadDashboard(guildId.trim(), { baseUrl: apiBase, adminToken }));
    } catch (err) {
      if (!wasBugsinkReported(err)) {
        captureConsoleError(err);
      }
      setError(err instanceof Error ? err.message : "알 수 없는 오류가 발생했습니다.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="console-shell">
      <aside className="sidebar">
        <div className="brand-lockup">
          <div className="brand-mark">N</div>
          <div>
            <strong>Nexa Console</strong>
            <span>Discord AI operations</span>
          </div>
        </div>
        <nav aria-label="콘솔 영역">
          <a href="#overview">
            <Activity size={18} /> Overview
          </a>
          <a href="#requests">
            <Bot size={18} /> Requests
          </a>
          <a href="#raw">
            <ServerCog size={18} /> API Snapshot
          </a>
        </nav>
      </aside>

      <section className="workspace">
        <header className="workspace-header">
          <div>
            <p className="eyebrow">Admin SPA</p>
            <h1>서버 운영 상태를 한 화면에서 봅니다.</h1>
          </div>
          <button className="primary-action" onClick={refresh} disabled={loading}>
            <RefreshCw size={18} className={loading ? "spin" : ""} />
            새로고침
          </button>
        </header>

        <section className="control-strip" aria-label="연결 설정">
          <Field label="API Base URL" value={apiBase} onChange={setApiBase} placeholder="비우면 같은 origin" />
          <Field label="Guild ID" value={guildId} onChange={setGuildId} placeholder="Discord 서버 ID" />
          <Field label="Admin Token" value={adminToken} onChange={setAdminToken} type="password" />
        </section>

        {error && (
          <div className="error-banner" role="alert">
            <ShieldAlert size={18} />
            <span>{error}</span>
          </div>
        )}

        <section id="overview" className="metric-grid">
          {overviewMetrics.map(([label, value]) => (
            <Metric key={label} label={label} value={value} />
          ))}
        </section>

        <section className="panel guild-panel">
          <div className="panel-title">
            <h2>연결된 서버</h2>
            <span>{state?.guilds.length ?? 0}개</span>
          </div>
          <div className="guild-list">
            {(state?.guilds ?? []).slice(0, 12).map((guild) => (
              <button key={String(guild.id)} type="button" onClick={() => setGuildId(String(guild.id))}>
                <CheckCircle2 size={16} />
                <span>{guild.name}</span>
                <code>{String(guild.id)}</code>
              </button>
            ))}
            {state && state.guilds.length === 0 && <p>관리자 토큰 또는 OAuth 세션으로 서버 목록을 불러오세요.</p>}
          </div>
        </section>

        <section id="requests" className="panel">
          <div className="panel-title">
            <h2>최근 요청</h2>
            <span>{state?.requests.length ?? 0}건</span>
          </div>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>상태</th>
                  <th>제공자</th>
                  <th>시각</th>
                </tr>
              </thead>
              <tbody>
                {(state?.requests ?? []).slice(0, 12).map((request, index) => (
                  <tr key={index}>
                    <td>{String(request.status ?? request.state ?? "-")}</td>
                    <td>{String(request.provider ?? request.providerId ?? "-")}</td>
                    <td>{String(request.createdAt ?? request.timestamp ?? "-")}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <section className="split-grid" id="raw">
          <JsonPanel title="AI Network Snapshot" value={state?.aiNetwork} />
          <JsonPanel title="Usage Trend" value={state?.usageTrend} />
        </section>
      </section>
    </main>
  );
}

export default App;

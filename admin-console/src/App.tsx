import {
  Activity,
  Archive,
  Bot,
  BookOpen,
  CheckCircle2,
  Eye,
  PlayCircle,
  RefreshCw,
  Send,
  ServerCog,
  ShieldAlert,
  Undo2,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import { captureConsoleError, wasBugsinkReported } from "./bugsink";
import {
  createFewShotDraft,
  DashboardState,
  evalFewShotDraft,
  archiveFewShotVersion,
  loadDashboard,
  loadFewShotSets,
  NiaFewShotEval,
  NiaFewShotExample,
  NiaFewShotPreview,
  NiaFewShotScope,
  NiaFewShotSet,
  previewFewShotDraft,
  publishFewShotVersion,
  replaceFewShotDraft,
  rollbackFewShotVersion,
} from "./api";

const API_BASE_STORAGE_KEY = "nexa-console-api-base";
const ADMIN_TOKEN_STORAGE_KEY = "nexa-console-admin-token";
const GUILD_ID_STORAGE_KEY = "nexa-console-guild-id";

const defaultApiBase = import.meta.env.VITE_CENTRAL_API_BASE_URL || "";
const ACTIONS = ["IGNORE", "WAIT", "REACT", "SPEAK", "CANCEL"] as const;

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

function SelectField({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: readonly string[];
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        {options.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    </label>
  );
}

function TextArea({
  label,
  value,
  onChange,
  rows = 4,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  rows?: number;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <textarea value={value} rows={rows} onChange={(event) => onChange(event.target.value)} />
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

function StatusPill({ value }: { value: string }) {
  return <span className={`status-pill status-${value.toLowerCase()}`}>{value}</span>;
}

function defaultFewShotExample(): NiaFewShotExample {
  return {
    title: "direct reply request",
    rawMessages: [{ ref: "m1", authorRole: "member", offsetMs: 0, text: "야 대답해줘" }],
    expectedAction: "SPEAK",
    reason: "The raw message is a direct request to NIA, so the judge should answer.",
    evidenceRefs: ["m1"],
    badAlternative: { action: "WAIT", whyBad: "Waiting makes NIA look like it is ignoring the direct request." },
    tags: ["direct-ask"],
    priority: 100,
    privacyClass: "SYNTHETIC",
    evalStatus: "NOT_RUN",
  };
}

function scopeKey(scope: NiaFewShotScope) {
  if (scope.type === "CHANNEL") return `channel:${scope.guildId}:${scope.channelId}:${scope.persona ?? "nia"}`;
  if (scope.type === "GUILD") return `guild:${scope.guildId}:${scope.persona ?? "nia"}`;
  if (scope.type === "PERSONA") return `persona:${scope.persona ?? "nia"}`;
  return "global";
}

function App() {
  const [apiBase, setApiBase] = useState(() => readStorage(API_BASE_STORAGE_KEY, defaultApiBase));
  const [adminToken, setAdminToken] = useState(() => readStorage(ADMIN_TOKEN_STORAGE_KEY, ""));
  const [guildId, setGuildId] = useState(() => readStorage(GUILD_ID_STORAGE_KEY, ""));
  const [state, setState] = useState<DashboardState | null>(null);
  const [fewShotSets, setFewShotSets] = useState<NiaFewShotSet[]>([]);
  const [selectedFewShotSetId, setSelectedFewShotSetId] = useState("");
  const [fewShotExample, setFewShotExample] = useState<NiaFewShotExample>(() => defaultFewShotExample());
  const [fewShotScope, setFewShotScope] = useState<NiaFewShotScope>(() => ({
    type: "GLOBAL",
    guildId: null,
    channelId: null,
    persona: "nia",
  }));
  const [fewShotPreview, setFewShotPreview] = useState<NiaFewShotPreview | null>(null);
  const [fewShotEval, setFewShotEval] = useState<NiaFewShotEval | null>(null);
  const [loading, setLoading] = useState(false);
  const [fewShotLoading, setFewShotLoading] = useState(false);
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

  const apiOptions = useMemo(() => ({ baseUrl: apiBase, adminToken }), [apiBase, adminToken]);
  const selectedFewShotSet = useMemo(
    () => fewShotSets.find((set) => String(set.id) === selectedFewShotSetId) ?? fewShotSets[0] ?? null,
    [fewShotSets, selectedFewShotSetId],
  );
  const editableDraft = useMemo(
    () => selectedFewShotSet?.versions.find((version) => version.status === "DRAFT") ?? null,
    [selectedFewShotSet],
  );
  const activeFewShot = useMemo(
    () => selectedFewShotSet?.versions.find((version) => version.version === selectedFewShotSet.activeVersion) ?? null,
    [selectedFewShotSet],
  );

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

  async function refreshFewShot() {
    setFewShotLoading(true);
    setError("");
    try {
      const sets = await loadFewShotSets(apiOptions);
      setFewShotSets(sets);
      setSelectedFewShotSetId((current) => current || String(sets[0]?.id ?? ""));
    } catch (err) {
      if (!wasBugsinkReported(err)) {
        captureConsoleError(err);
      }
      setError(err instanceof Error ? err.message : "알 수 없는 오류가 발생했습니다.");
    } finally {
      setFewShotLoading(false);
    }
  }

  async function saveFewShotDraft() {
    setFewShotLoading(true);
    setError("");
    try {
      const nextBadAction =
        fewShotExample.badAlternative.action === fewShotExample.expectedAction
          ? ACTIONS.find((action) => action !== fewShotExample.expectedAction) ?? "WAIT"
          : fewShotExample.badAlternative.action;
      const example = { ...fewShotExample, badAlternative: { ...fewShotExample.badAlternative, action: nextBadAction } };
      if (editableDraft?.setId) {
        await replaceFewShotDraft(apiOptions, editableDraft.setId, editableDraft.version, [example]);
      } else {
        await createFewShotDraft(apiOptions, fewShotScope, [example]);
      }
      setFewShotEval(null);
      setFewShotPreview(null);
      await refreshFewShot();
    } catch (err) {
      if (!wasBugsinkReported(err)) {
        captureConsoleError(err);
      }
      setError(err instanceof Error ? err.message : "알 수 없는 오류가 발생했습니다.");
    } finally {
      setFewShotLoading(false);
    }
  }

  async function runFewShotPreview() {
    if (!editableDraft?.setId) return;
    setFewShotLoading(true);
    setError("");
    try {
      setFewShotPreview(await previewFewShotDraft(apiOptions, editableDraft.setId, editableDraft.version, true));
    } catch (err) {
      setError(err instanceof Error ? err.message : "알 수 없는 오류가 발생했습니다.");
    } finally {
      setFewShotLoading(false);
    }
  }

  async function runFewShotEval() {
    if (!editableDraft?.setId) return;
    setFewShotLoading(true);
    setError("");
    try {
      setFewShotEval(await evalFewShotDraft(apiOptions, editableDraft.setId, editableDraft.version));
    } catch (err) {
      setError(err instanceof Error ? err.message : "알 수 없는 오류가 발생했습니다.");
    } finally {
      setFewShotLoading(false);
    }
  }

  async function publishFewShot() {
    if (!editableDraft?.setId || fewShotEval?.readyForPublish !== true) return;
    setFewShotLoading(true);
    setError("");
    try {
      await publishFewShotVersion(apiOptions, editableDraft.setId, editableDraft.version);
      setFewShotEval(null);
      await refreshFewShot();
    } catch (err) {
      setError(err instanceof Error ? err.message : "알 수 없는 오류가 발생했습니다.");
    } finally {
      setFewShotLoading(false);
    }
  }

  async function rollbackFewShot(version: number) {
    if (!selectedFewShotSet?.id) return;
    setFewShotLoading(true);
    setError("");
    try {
      await rollbackFewShotVersion(apiOptions, selectedFewShotSet.id, version);
      await refreshFewShot();
    } catch (err) {
      setError(err instanceof Error ? err.message : "알 수 없는 오류가 발생했습니다.");
    } finally {
      setFewShotLoading(false);
    }
  }

  async function archiveFewShot(version: number) {
    if (!selectedFewShotSet?.id || version === selectedFewShotSet.activeVersion) return;
    setFewShotLoading(true);
    setError("");
    try {
      await archiveFewShotVersion(apiOptions, selectedFewShotSet.id, version);
      await refreshFewShot();
    } catch (err) {
      setError(err instanceof Error ? err.message : "알 수 없는 오류가 발생했습니다.");
    } finally {
      setFewShotLoading(false);
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
          <a href="#fewshot">
            <BookOpen size={18} /> NIA Few-Shot
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

        <section id="fewshot" className="panel">
          <div className="panel-title">
            <h2>NIA Few-Shot</h2>
            <button className="secondary-action" type="button" onClick={refreshFewShot} disabled={fewShotLoading}>
              <RefreshCw size={16} className={fewShotLoading ? "spin" : ""} />
              불러오기
            </button>
          </div>

          <div className="fewshot-grid">
            <div className="fewshot-list">
              {(fewShotSets.length ? fewShotSets : []).map((set) => (
                <button
                  key={String(set.id)}
                  type="button"
                  className={String(set.id) === String(selectedFewShotSet?.id) ? "selected" : ""}
                  onClick={() => setSelectedFewShotSetId(String(set.id))}
                >
                  <strong>{scopeKey(set.scope)}</strong>
                  <span>active v{set.activeVersion ?? "-"}</span>
                </button>
              ))}
              {fewShotSets.length === 0 && <p>등록된 세트 없음</p>}
            </div>

            <div className="fewshot-versions">
              <div className="version-summary">
                <strong>{selectedFewShotSet ? scopeKey(selectedFewShotSet.scope) : "global"}</strong>
                <span>{activeFewShot ? `active v${activeFewShot.version}` : "active 없음"}</span>
              </div>
              {(selectedFewShotSet?.versions ?? []).map((version) => (
                <div className="version-row" key={version.version}>
                  <div>
                    <strong>v{version.version}</strong>
                    <StatusPill value={version.status} />
                    <span>{version.examples.length} examples</span>
                  </div>
                  <div className="button-row compact">
                    <button
                      className="secondary-action"
                      type="button"
                      onClick={() => rollbackFewShot(version.version)}
                      disabled={fewShotLoading || version.status === "DRAFT" || version.version === selectedFewShotSet?.activeVersion}
                    >
                      <Undo2 size={15} />
                      rollback
                    </button>
                    <button
                      className="danger-action"
                      type="button"
                      onClick={() => archiveFewShot(version.version)}
                      disabled={fewShotLoading || version.version === selectedFewShotSet?.activeVersion}
                    >
                      <Archive size={15} />
                      archive
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </section>

        <section className="panel">
          <div className="panel-title">
            <h2>Draft Editor</h2>
            <span>{editableDraft ? `v${editableDraft.version}` : "new draft"}</span>
          </div>

          <div className="form-grid">
            <SelectField
              label="Scope"
              value={fewShotScope.type}
              onChange={(type) => setFewShotScope((scope) => ({ ...scope, type: type as NiaFewShotScope["type"] }))}
              options={["GLOBAL", "GUILD", "CHANNEL", "PERSONA"]}
            />
            <Field
              label="Guild ID"
              value={String(fewShotScope.guildId ?? "")}
              onChange={(value) => setFewShotScope((scope) => ({ ...scope, guildId: value ? Number(value) : null }))}
            />
            <Field
              label="Channel ID"
              value={String(fewShotScope.channelId ?? "")}
              onChange={(value) => setFewShotScope((scope) => ({ ...scope, channelId: value ? Number(value) : null }))}
            />
            <Field
              label="Persona"
              value={fewShotScope.persona ?? "nia"}
              onChange={(value) => setFewShotScope((scope) => ({ ...scope, persona: value || "nia" }))}
            />
            <SelectField
              label="Expected"
              value={fewShotExample.expectedAction}
              onChange={(expectedAction) =>
                setFewShotExample((example) => ({
                  ...example,
                  expectedAction,
                  badAlternative:
                    example.badAlternative.action === expectedAction
                      ? { ...example.badAlternative, action: ACTIONS.find((action) => action !== expectedAction) ?? "WAIT" }
                      : example.badAlternative,
                }))
              }
              options={ACTIONS}
            />
            <SelectField
              label="Bad Action"
              value={fewShotExample.badAlternative.action}
              onChange={(action) =>
                setFewShotExample((example) => ({ ...example, badAlternative: { ...example.badAlternative, action } }))
              }
              options={ACTIONS}
            />
          </div>

          <div className="form-grid wide">
            <Field
              label="Title"
              value={fewShotExample.title}
              onChange={(title) => setFewShotExample((example) => ({ ...example, title }))}
            />
            <Field
              label="Tags"
              value={fewShotExample.tags.join(",")}
              onChange={(value) =>
                setFewShotExample((example) => ({
                  ...example,
                  tags: value
                    .split(",")
                    .map((tag) => tag.trim())
                    .filter(Boolean),
                }))
              }
            />
          </div>

          <TextArea
            label="Raw Message"
            value={fewShotExample.rawMessages[0]?.text ?? ""}
            onChange={(text) =>
              setFewShotExample((example) => ({
                ...example,
                rawMessages: [{ ...(example.rawMessages[0] ?? { ref: "m1", authorRole: "member", offsetMs: 0 }), text }],
                evidenceRefs: ["m1"],
              }))
            }
          />
          <TextArea
            label="Reason"
            value={fewShotExample.reason}
            onChange={(reason) => setFewShotExample((example) => ({ ...example, reason }))}
          />
          <TextArea
            label="Why Bad"
            value={fewShotExample.badAlternative.whyBad}
            onChange={(whyBad) =>
              setFewShotExample((example) => ({ ...example, badAlternative: { ...example.badAlternative, whyBad } }))
            }
          />

          <div className="button-row">
            <button className="primary-action" type="button" onClick={saveFewShotDraft} disabled={fewShotLoading}>
              <Send size={17} />
              draft 저장
            </button>
            <button className="secondary-action" type="button" onClick={runFewShotPreview} disabled={fewShotLoading || !editableDraft}>
              <Eye size={17} />
              preview
            </button>
            <button className="secondary-action" type="button" onClick={runFewShotEval} disabled={fewShotLoading || !editableDraft}>
              <PlayCircle size={17} />
              eval
            </button>
            <button
              className="primary-action"
              type="button"
              onClick={publishFewShot}
              disabled={fewShotLoading || !editableDraft || fewShotEval?.readyForPublish !== true}
            >
              <CheckCircle2 size={17} />
              publish
            </button>
          </div>
        </section>

        <section className="split-grid">
          <JsonPanel title="Few-Shot Eval" value={fewShotEval} />
          <JsonPanel title="Judge Preview" value={fewShotPreview} />
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

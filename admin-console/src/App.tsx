import {
  Activity,
  Archive,
  Bot,
  BookOpen,
  CheckCircle2,
  Copy,
  Eye,
  MessageSquarePlus,
  PlayCircle,
  Plus,
  RefreshCw,
  Send,
  ServerCog,
  ShieldAlert,
  Trash2,
  Undo2,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import { captureConsoleError, wasBugsinkReported } from "./bugsink";
import {
  archiveFewShotVersion,
  createFewShotDraft,
  createFewShotDraftForSet,
  evalFewShotDraft,
  loadDashboard,
  loadFewShotSets,
  previewFewShotDraft,
  publishFewShotVersion,
  replaceFewShotDraft,
  rollbackFewShotVersion,
  type DashboardPanel,
  type DashboardState,
  type NiaFewShotEval,
  type NiaFewShotExample,
  type NiaFewShotPreview,
  type NiaFewShotScope,
  type NiaFewShotSet,
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
    expectedReplies: ["응 듣고 있어, 무슨 일인데"],
    reason: "니아를 직접 부르며 답을 요구했으므로 바로 반응한다.",
    evidenceRefs: ["m1"],
    badAlternative: { action: "WAIT", whyBad: "기다리면 직접 요청을 무시하는 것처럼 보인다." },
    tags: ["direct-ask"],
    priority: 100,
    privacyClass: "SYNTHETIC",
    evalStatus: "NOT_RUN",
  };
}

function cloneFewShotExamples(examples: NiaFewShotExample[]): NiaFewShotExample[] {
  return examples.map((example) => ({
    ...example,
    rawMessages: example.rawMessages.map((message) => ({ ...message })),
    expectedReplies: [...(example.expectedReplies ?? [])],
    evidenceRefs: [...example.evidenceRefs],
    badAlternative: { ...example.badAlternative },
    tags: [...example.tags],
  }));
}

function scopeKey(scope: NiaFewShotScope) {
  if (scope.type === "CHANNEL") return `channel:${scope.guildId}:${scope.channelId}:${scope.persona ?? "nia"}`;
  if (scope.type === "GUILD") return `guild:${scope.guildId}:${scope.persona ?? "nia"}`;
  if (scope.type === "PERSONA") return `persona:${scope.persona ?? "nia"}`;
  return "global";
}

function panelLabel(panel: DashboardPanel): string {
  switch (panel) {
    case "aiNetwork":
      return "AI Network";
    case "requests":
      return "Requests";
    case "usageTrend":
      return "Usage Trend";
  }
}

function App() {
  const [apiBase, setApiBase] = useState(() => readStorage(API_BASE_STORAGE_KEY, defaultApiBase));
  const [adminToken, setAdminToken] = useState(() => readStorage(ADMIN_TOKEN_STORAGE_KEY, ""));
  const [guildId, setGuildId] = useState(() => readStorage(GUILD_ID_STORAGE_KEY, ""));
  const [state, setState] = useState<DashboardState | null>(null);
  const [fewShotSets, setFewShotSets] = useState<NiaFewShotSet[]>([]);
  const [selectedFewShotSetId, setSelectedFewShotSetId] = useState("");
  const [fewShotExamples, setFewShotExamples] = useState<NiaFewShotExample[]>(() => [defaultFewShotExample()]);
  const [selectedFewShotExampleIndex, setSelectedFewShotExampleIndex] = useState(0);
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
  const selectedFewShotExample = fewShotExamples[selectedFewShotExampleIndex] ?? fewShotExamples[0] ?? null;

  useEffect(() => {
    window.localStorage.setItem(API_BASE_STORAGE_KEY, apiBase);
    window.localStorage.setItem(ADMIN_TOKEN_STORAGE_KEY, adminToken);
    window.localStorage.setItem(GUILD_ID_STORAGE_KEY, guildId);
  }, [apiBase, adminToken, guildId]);

  useEffect(() => {
    const source = editableDraft?.examples ?? activeFewShot?.examples;
    setFewShotExamples(source?.length ? cloneFewShotExamples(source) : [defaultFewShotExample()]);
    setSelectedFewShotExampleIndex(0);
    if (selectedFewShotSet) setFewShotScope({ ...selectedFewShotSet.scope });
  }, [selectedFewShotSet?.id, editableDraft?.version, activeFewShot?.version]);

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
      const examples = fewShotExamples.map((example) => {
        const nextBadAction =
          example.badAlternative.action === example.expectedAction
            ? ACTIONS.find((action) => action !== example.expectedAction) ?? "WAIT"
            : example.badAlternative.action;
        const rawMessages = example.rawMessages.map((message, index) => ({
          ...message,
          ref: `m${index + 1}`,
          offsetMs: index * 1000,
        }));
        return {
          ...example,
          id: null,
          rawMessages,
          expectedReplies: example.expectedAction === "SPEAK" ? example.expectedReplies.filter((reply) => reply.trim()) : [],
          evidenceRefs: [rawMessages.at(-1)?.ref ?? "m1"],
          badAlternative: { ...example.badAlternative, action: nextBadAction },
        };
      });
      if (editableDraft?.setId) {
        await replaceFewShotDraft(apiOptions, editableDraft.setId, editableDraft.version, examples);
      } else if (selectedFewShotSet?.id) {
        await createFewShotDraftForSet(apiOptions, selectedFewShotSet.id, examples);
      } else {
        await createFewShotDraft(apiOptions, fewShotScope, examples);
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

  function updateSelectedFewShotExample(update: (example: NiaFewShotExample) => NiaFewShotExample) {
    setFewShotExamples((examples) => examples.map((example, index) => (index === selectedFewShotExampleIndex ? update(example) : example)));
    setFewShotEval(null);
    setFewShotPreview(null);
  }

  function addFewShotExample() {
    setFewShotExamples((examples) => [...examples, defaultFewShotExample()]);
    setSelectedFewShotExampleIndex(fewShotExamples.length);
  }

  function duplicateFewShotExample() {
    if (!selectedFewShotExample) return;
    setFewShotExamples((examples) => [
      ...examples,
      { ...cloneFewShotExamples([selectedFewShotExample])[0], id: null, title: `${selectedFewShotExample.title} 복사본` },
    ]);
    setSelectedFewShotExampleIndex(fewShotExamples.length);
  }

  function removeFewShotExample() {
    if (fewShotExamples.length <= 1) return;
    setFewShotExamples((examples) => examples.filter((_, index) => index !== selectedFewShotExampleIndex));
    setSelectedFewShotExampleIndex((index) => Math.max(0, index - 1));
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

        {state?.partialErrors.length ? (
          <section className="partial-errors" role="status" aria-label="부분 API 오류">
            <div className="partial-errors-title">
              <ShieldAlert size={18} />
              <strong>일부 패널 오류</strong>
              <span>{state.partialErrors.length}건</span>
            </div>
            <ul>
              {state.partialErrors.map((partialError) => (
                <li key={`${partialError.panel}:${partialError.path}:${partialError.code ?? partialError.message}`}>
                  <span>{panelLabel(partialError.panel)}</span>
                  <code>{partialError.code ?? `HTTP_${partialError.status ?? "UNKNOWN"}`}</code>
                  <p>{partialError.message}</p>
                  {partialError.serverRequestId ? <small>requestId={partialError.serverRequestId}</small> : null}
                </li>
              ))}
            </ul>
          </section>
        ) : null}

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

        <section className="panel" aria-labelledby="fewshot-editor-title">
          <div className="panel-title">
            <div>
              <h2 id="fewshot-editor-title">니아 대화 예시 편집</h2>
              <p className="panel-description">게시 전까지는 실제 니아에게 적용되지 않습니다. SPEAK 예시의 기대 답변은 판단과 실제 말투 양쪽에 반영됩니다.</p>
            </div>
            <span>{editableDraft ? `초안 v${editableDraft.version}` : selectedFewShotSet ? "새 초안" : "새 세트"}</span>
          </div>

          {!selectedFewShotSet && (
            <div className="form-grid">
              <SelectField
                label="적용 범위"
                value={fewShotScope.type}
                onChange={(type) => setFewShotScope((scope) => ({ ...scope, type: type as NiaFewShotScope["type"] }))}
                options={["GLOBAL", "GUILD", "CHANNEL", "PERSONA"]}
              />
              <Field
                label="서버 ID"
                value={String(fewShotScope.guildId ?? "")}
                onChange={(value) => setFewShotScope((scope) => ({ ...scope, guildId: value ? Number(value) : null }))}
              />
              <Field
                label="채널 ID"
                value={String(fewShotScope.channelId ?? "")}
                onChange={(value) => setFewShotScope((scope) => ({ ...scope, channelId: value ? Number(value) : null }))}
              />
            </div>
          )}

          <div className="fewshot-editor-grid">
            <aside className="fewshot-example-list" aria-label="대화 예시 목록">
              <div className="example-list-toolbar">
                <strong>예시 {fewShotExamples.length}개</strong>
                <button className="secondary-action icon-action" type="button" onClick={addFewShotExample} title="예시 추가">
                  <Plus size={16} />
                </button>
              </div>
              {fewShotExamples.map((example, index) => (
                <button
                  key={`${index}-${example.title}`}
                  type="button"
                  className={index === selectedFewShotExampleIndex ? "selected" : ""}
                  onClick={() => setSelectedFewShotExampleIndex(index)}
                >
                  <span className="example-number">{String(index + 1).padStart(2, "0")}</span>
                  <span>
                    <strong>{example.title || "제목 없는 예시"}</strong>
                    <small>{example.expectedAction} · 대화 {example.rawMessages.length}줄</small>
                  </span>
                </button>
              ))}
            </aside>

            {selectedFewShotExample && (
              <div className="fewshot-example-editor">
                <div className="example-editor-toolbar">
                  <div>
                    <strong>예시 {selectedFewShotExampleIndex + 1}</strong>
                    <span>우선순위 {selectedFewShotExample.priority}</span>
                  </div>
                  <div className="button-row compact">
                    <button className="secondary-action" type="button" onClick={duplicateFewShotExample}>
                      <Copy size={15} /> 복제
                    </button>
                    <button className="danger-action" type="button" onClick={removeFewShotExample} disabled={fewShotExamples.length <= 1}>
                      <Trash2 size={15} /> 삭제
                    </button>
                  </div>
                </div>

                <div className="form-grid wide">
                  <Field
                    label="예시 이름"
                    value={selectedFewShotExample.title}
                    onChange={(title) => updateSelectedFewShotExample((example) => ({ ...example, title }))}
                    placeholder="예: 서연이에게 말하는데 니아가 끼어들지 않기"
                  />
                  <Field
                    label="태그"
                    value={selectedFewShotExample.tags.join(",")}
                    onChange={(value) =>
                      updateSelectedFewShotExample((example) => ({
                        ...example,
                        tags: value.split(",").map((tag) => tag.trim().toLowerCase().replace(/\s+/g, "-")).filter(Boolean),
                      }))
                    }
                    placeholder="server-meme, direct-ask"
                  />
                </div>

                <div className="form-grid">
                  <SelectField
                    label="니아가 할 행동"
                    value={selectedFewShotExample.expectedAction}
                    onChange={(expectedAction) =>
                      updateSelectedFewShotExample((example) => ({
                        ...example,
                        expectedAction,
                        expectedReplies: expectedAction === "SPEAK" ? example.expectedReplies : [],
                        badAlternative:
                          example.badAlternative.action === expectedAction
                            ? { ...example.badAlternative, action: ACTIONS.find((action) => action !== expectedAction) ?? "WAIT" }
                            : example.badAlternative,
                      }))
                    }
                    options={ACTIONS}
                  />
                  <SelectField
                    label="피해야 할 행동"
                    value={selectedFewShotExample.badAlternative.action}
                    onChange={(action) =>
                      updateSelectedFewShotExample((example) => ({ ...example, badAlternative: { ...example.badAlternative, action } }))
                    }
                    options={ACTIONS}
                  />
                  <Field
                    label="우선순위"
                    value={String(selectedFewShotExample.priority)}
                    onChange={(value) => updateSelectedFewShotExample((example) => ({ ...example, priority: Number(value) || 0 }))}
                  />
                </div>

                <div className="conversation-editor">
                  <div className="conversation-editor-title">
                    <div>
                      <strong>대화 장면</strong>
                      <span>실제로 이어진 순서대로 입력하세요</span>
                    </div>
                    <button
                      className="secondary-action"
                      type="button"
                      onClick={() =>
                        updateSelectedFewShotExample((example) => ({
                          ...example,
                          rawMessages: [
                            ...example.rawMessages,
                            { ref: `m${example.rawMessages.length + 1}`, authorRole: "member", offsetMs: example.rawMessages.length * 1000, text: "" },
                          ],
                        }))
                      }
                    >
                      <MessageSquarePlus size={16} /> 대화 한 줄 추가
                    </button>
                  </div>
                  {selectedFewShotExample.rawMessages.map((message, messageIndex) => (
                    <div className="fewshot-message-row" key={`${message.ref}-${messageIndex}`}>
                      <select
                        aria-label={`대화 ${messageIndex + 1} 화자`}
                        value={message.authorRole}
                        onChange={(event) =>
                          updateSelectedFewShotExample((example) => ({
                            ...example,
                            rawMessages: example.rawMessages.map((item, index) =>
                              index === messageIndex ? { ...item, authorRole: event.target.value } : item,
                            ),
                          }))
                        }
                      >
                        <option value="member">멤버</option>
                        <option value="nia">니아</option>
                        <option value="other">다른 사람</option>
                      </select>
                      <input
                        aria-label={`대화 ${messageIndex + 1} 내용`}
                        value={message.text}
                        onChange={(event) =>
                          updateSelectedFewShotExample((example) => ({
                            ...example,
                            rawMessages: example.rawMessages.map((item, index) =>
                              index === messageIndex ? { ...item, text: event.target.value } : item,
                            ),
                          }))
                        }
                        placeholder="이 장면에서 실제로 나온 말"
                      />
                      <button
                        className="icon-action danger-action"
                        type="button"
                        aria-label={`대화 ${messageIndex + 1} 삭제`}
                        disabled={selectedFewShotExample.rawMessages.length <= 1}
                        onClick={() =>
                          updateSelectedFewShotExample((example) => ({
                            ...example,
                            rawMessages: example.rawMessages.filter((_, index) => index !== messageIndex),
                          }))
                        }
                      >
                        <Trash2 size={15} />
                      </button>
                    </div>
                  ))}
                </div>

                {selectedFewShotExample.expectedAction === "SPEAK" && (
                  <TextArea
                    label="니아의 기대 답변 · 한 채팅당 한 줄"
                    value={selectedFewShotExample.expectedReplies.join("\n")}
                    rows={3}
                    onChange={(value) =>
                      updateSelectedFewShotExample((example) => ({ ...example, expectedReplies: value.split("\n").slice(0, 4) }))
                    }
                  />
                )}
                <TextArea
                  label="왜 이 행동이 자연스러운가"
                  value={selectedFewShotExample.reason}
                  onChange={(reason) => updateSelectedFewShotExample((example) => ({ ...example, reason }))}
                />
                <TextArea
                  label="피해야 할 행동이 왜 나쁜가"
                  value={selectedFewShotExample.badAlternative.whyBad}
                  onChange={(whyBad) =>
                    updateSelectedFewShotExample((example) => ({ ...example, badAlternative: { ...example.badAlternative, whyBad } }))
                  }
                />
              </div>
            )}
          </div>

          <div className="publish-bar">
            <div>
              <strong>{fewShotEval?.readyForPublish ? "검증 통과 · 게시 가능" : "저장 후 검증해야 게시할 수 있습니다"}</strong>
              <span>게시하면 이 범위의 이전 활성 버전은 자동 보관됩니다</span>
            </div>
            <div className="button-row compact">
              <button className="primary-action" type="button" onClick={saveFewShotDraft} disabled={fewShotLoading}>
                <Send size={17} /> 초안 저장
              </button>
              <button className="secondary-action" type="button" onClick={runFewShotPreview} disabled={fewShotLoading || !editableDraft}>
                <Eye size={17} /> 미리보기
              </button>
              <button className="secondary-action" type="button" onClick={runFewShotEval} disabled={fewShotLoading || !editableDraft}>
                <PlayCircle size={17} /> 검증
              </button>
              <button
                className="primary-action"
                type="button"
                onClick={publishFewShot}
                disabled={fewShotLoading || !editableDraft || fewShotEval?.readyForPublish !== true}
              >
                <CheckCircle2 size={17} /> 게시
              </button>
            </div>
          </div>
        </section>

        <section className="split-grid">
          <JsonPanel title="검증 결과" value={fewShotEval} />
          <JsonPanel title="프롬프트 미리보기" value={fewShotPreview} />
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

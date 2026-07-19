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
  loadGuildChannelRequests,
  loadGuildChannels,
  previewFewShotDraft,
  publishFewShotVersion,
  replaceFewShotDraft,
  rollbackFewShotVersion,
  type DashboardPanel,
  type DashboardState,
  type ChannelSummary,
  type NiaFewShotEval,
  type NiaFewShotExample,
  type NiaFewShotPreview,
  type NiaFewShotScope,
  type NiaFewShotSet,
} from "./api";

const GUILD_ID_STORAGE_KEY = "nexa-console-guild-id";

const ACTIONS = ["IGNORE", "WAIT", "REACT", "SPEAK", "CANCEL"] as const;
const ACTION_LABELS: Record<(typeof ACTIONS)[number], string> = {
  IGNORE: "말하지 않기",
  WAIT: "잠깐 기다리기",
  REACT: "리액션만 남기기",
  SPEAK: "말하기",
  CANCEL: "예정 행동 취소",
};
type AdminView = "OVERVIEW" | "REQUESTS" | "FEWSHOT" | "API";

const VIEW_META: Record<AdminView, { hash: string; title: string }> = {
  OVERVIEW: { hash: "overview", title: "운영 현황" },
  REQUESTS: { hash: "requests", title: "요청 기록" },
  FEWSHOT: { hash: "fewshot", title: "Few-shot 관리" },
  API: { hash: "api", title: "API 상태" },
};

function viewFromHash(hash: string): AdminView {
  const section = hash.replace(/^#/, "");
  return (Object.entries(VIEW_META).find(([, meta]) => meta.hash === section)?.[0] as AdminView | undefined) ?? "OVERVIEW";
}

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
  optionLabels,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: readonly string[];
  optionLabels?: Record<string, string>;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        {options.map((option) => (
          <option key={option} value={option}>
            {optionLabels?.[option] ?? option}
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
    title: "재미없는 이야기를 계속하지 않고 수습하기",
    rawMessages: [
      { ref: "m1", authorRole: "member", offsetMs: 0, text: "니아야 재밌는 얘기 좀 해봐" },
      { ref: "m2", authorRole: "nia", offsetMs: 1000, text: "친구가 무선 이어폰을 자랑하다가 한쪽만 끊겼대" },
      { ref: "m3", authorRole: "member", offsetMs: 2000, text: "아니 왜 계속함... 개노잼임" },
    ],
    expectedAction: "SPEAK",
    expectedReplies: ["아 인정 이번 건 접을게"],
    badReplies: ["야 욕은 잘하네 ㅋㅋ 그럼 하나 더 간다"],
    reason: "마지막 말은 새 이야기를 요구한 것이 아니라 직전 결과에 대한 부정적 평가다. 짧게 인정하고 멈추는 수습이 현재 장면을 가장 잘 이어간다.",
    evidenceRefs: ["m2", "m3"],
    badAlternative: { action: "WAIT", whyBad: "아무 반응 없이 기다리면 방금 받은 직접적인 피드백을 못 알아들은 것처럼 보일 수 있다." },
    tags: ["latest-feedback", "repair"],
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
    badReplies: [...(example.badReplies ?? [])],
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
  const [guildId, setGuildId] = useState(() => readStorage(GUILD_ID_STORAGE_KEY, ""));
  const [state, setState] = useState<DashboardState | null>(null);
  const [requestChannels, setRequestChannels] = useState<ChannelSummary[]>([]);
  const [requestChannelId, setRequestChannelId] = useState("");
  const [requestRows, setRequestRows] = useState<Record<string, unknown>[]>([]);
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
  const [activeView, setActiveView] = useState<AdminView>(() => viewFromHash(window.location.hash));
  const [loading, setLoading] = useState(false);
  const [requestLoading, setRequestLoading] = useState(false);
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
  const fewShotOutputCount = useMemo(
    () => fewShotExamples.reduce((count, example) => count + (example.expectedReplies?.length ?? 0), 0),
    [fewShotExamples],
  );

  useEffect(() => {
    window.localStorage.setItem(GUILD_ID_STORAGE_KEY, guildId);
  }, [guildId]);

  useEffect(() => {
    const syncViewWithHash = () => {
      setActiveView(viewFromHash(window.location.hash));
      window.scrollTo({ top: 0 });
    };
    syncViewWithHash();
    window.addEventListener("hashchange", syncViewWithHash);
    return () => window.removeEventListener("hashchange", syncViewWithHash);
  }, []);

  useEffect(() => {
    const source = editableDraft?.examples ?? activeFewShot?.examples;
    setFewShotExamples(source?.length ? cloneFewShotExamples(source) : [defaultFewShotExample()]);
    setSelectedFewShotExampleIndex(0);
    if (selectedFewShotSet) setFewShotScope({ ...selectedFewShotSet.scope });
  }, [selectedFewShotSet?.id, editableDraft?.version, activeFewShot?.version]);

  useEffect(() => {
    if (activeView === "FEWSHOT") {
      void refreshFewShot();
    } else {
      void refresh();
    }
  }, [activeView]);

  async function refresh(nextGuildId = guildId.trim()) {
    setLoading(true);
    setError("");
    if (activeView === "REQUESTS") {
      setRequestChannels([]);
      setRequestChannelId("");
      setRequestRows([]);
    }
    try {
      const dashboard = await loadDashboard(nextGuildId);
      setState(dashboard);
      setGuildId(dashboard.selectedGuildId);
      if (activeView === "REQUESTS") {
        const channels = await loadGuildChannels(dashboard.selectedGuildId);
        setRequestChannels(channels);
      }
    } catch (err) {
      if (!wasBugsinkReported(err)) {
        captureConsoleError(err);
      }
      setError(err instanceof Error ? err.message : "알 수 없는 오류가 발생했습니다.");
    } finally {
      setLoading(false);
    }
  }

  async function selectRequestChannel(nextChannelId: string) {
    setRequestChannelId(nextChannelId);
    setRequestRows([]);
    if (!guildId || !nextChannelId) return;
    setRequestLoading(true);
    setError("");
    try {
      setRequestRows(await loadGuildChannelRequests(guildId, nextChannelId));
    } catch (err) {
      if (!wasBugsinkReported(err)) captureConsoleError(err);
      setError(err instanceof Error ? err.message : "요청 기록을 불러오지 못했습니다.");
    } finally {
      setRequestLoading(false);
    }
  }

  async function refreshFewShot() {
    setFewShotLoading(true);
    setError("");
    try {
      const sets = await loadFewShotSets();
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
        const evidenceIndexes = example.rawMessages.flatMap((message, index) =>
          example.evidenceRefs.includes(message.ref) ? [index] : [],
        );
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
          badReplies: example.expectedAction === "SPEAK" ? (example.badReplies ?? []).filter((reply) => reply.trim()) : [],
          evidenceRefs:
            evidenceIndexes.length > 0
              ? evidenceIndexes.map((index) => rawMessages[index]?.ref).filter((ref): ref is string => Boolean(ref))
              : [rawMessages.at(-1)?.ref ?? "m1"],
          badAlternative: { ...example.badAlternative, action: nextBadAction },
        };
      });
      if (editableDraft?.setId) {
        await replaceFewShotDraft(editableDraft.setId, editableDraft.version, examples);
      } else if (selectedFewShotSet?.id) {
        await createFewShotDraftForSet(selectedFewShotSet.id, examples);
      } else {
        await createFewShotDraft(fewShotScope, examples);
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
      setFewShotPreview(await previewFewShotDraft(editableDraft.setId, editableDraft.version, true));
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
      setFewShotEval(await evalFewShotDraft(editableDraft.setId, editableDraft.version));
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
      await publishFewShotVersion(editableDraft.setId, editableDraft.version);
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
      await rollbackFewShotVersion(selectedFewShotSet.id, version);
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
      await archiveFewShotVersion(selectedFewShotSet.id, version);
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
          <a href="#overview" className={activeView === "OVERVIEW" ? "active" : ""} aria-current={activeView === "OVERVIEW" ? "page" : undefined}>
            <Activity size={18} /> 운영 현황
          </a>
          <a href="#requests" className={activeView === "REQUESTS" ? "active" : ""} aria-current={activeView === "REQUESTS" ? "page" : undefined}>
            <Bot size={18} /> 요청 기록
          </a>
          <a href="#fewshot" className={activeView === "FEWSHOT" ? "active" : ""} aria-current={activeView === "FEWSHOT" ? "page" : undefined}>
            <BookOpen size={18} /> Few-shot
          </a>
          <a href="#api" className={activeView === "API" ? "active" : ""} aria-current={activeView === "API" ? "page" : undefined}>
            <ServerCog size={18} /> API 상태
          </a>
        </nav>
      </aside>

      <section className="workspace">
        <header className="workspace-header">
          <div>
            <p className="workspace-path">관리자 / {VIEW_META[activeView].title}</p>
            <h1>{VIEW_META[activeView].title}</h1>
          </div>
          <button
            className="primary-action"
            onClick={activeView === "FEWSHOT" ? refreshFewShot : () => refresh()}
            disabled={activeView === "FEWSHOT" ? fewShotLoading : loading}
          >
            <RefreshCw size={18} className={activeView === "FEWSHOT" ? (fewShotLoading ? "spin" : "") : (loading ? "spin" : "")} />
            {activeView === "FEWSHOT" ? "데이터 불러오기" : "새로고침"}
          </button>
        </header>

        {error && (
          <div className="error-banner" role="alert">
            <ShieldAlert size={18} />
            <span>{error}</span>
          </div>
        )}

        {activeView !== "FEWSHOT" && state?.partialErrors.length ? (
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

        {activeView === "OVERVIEW" && (
          <>
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
                {(state?.guilds ?? []).slice(0, 12).map((guild) => {
                  const id = String(guild.id);
                  return (
                    <button
                      key={id}
                      type="button"
                      className={id === guildId ? "selected" : ""}
                      onClick={() => {
                        setGuildId(id);
                        void refresh(id);
                      }}
                    >
                      <CheckCircle2 size={16} />
                      <span>{guild.name}</span>
                      <code>{id}</code>
                    </button>
                  );
                })}
                {state && state.guilds.length === 0 && <p>관리할 수 있는 Discord 서버가 없습니다.</p>}
              </div>
            </section>
          </>
        )}

        {activeView === "REQUESTS" && (
          <section id="requests" className="panel page-panel">
            <div className="panel-title">
              <div>
                <h2>요청 기록</h2>
                <p>서버를 고른 뒤 그 서버의 채널별 기록을 확인합니다.</p>
              </div>
              <span>{requestRows.length}건</span>
            </div>
            <div className="request-scope-picker">
              <label>
                <span>Discord 서버</span>
                <select
                  value={guildId}
                  onChange={(event) => {
                    const nextGuildId = event.target.value;
                    setGuildId(nextGuildId);
                    void refresh(nextGuildId);
                  }}
                  disabled={loading || (state?.guilds.length ?? 0) === 0}
                >
                  {(state?.guilds ?? []).map((guild) => (
                    <option key={String(guild.id)} value={String(guild.id)}>{guild.name}</option>
                  ))}
                </select>
              </label>
              <span className="request-scope-step" aria-hidden="true">→</span>
              <label>
                <span>채널</span>
                <select
                  value={requestChannelId}
                  onChange={(event) => void selectRequestChannel(event.target.value)}
                  disabled={loading || requestChannels.length === 0}
                >
                  <option value="">채널을 선택하세요</option>
                  {requestChannels.map((channel) => (
                    <option key={String(channel.id)} value={String(channel.id)}>#{channel.name}</option>
                  ))}
                </select>
              </label>
            </div>
            {requestChannelId ? (
              <div className="request-scope-current">
                <strong>{state?.guilds.find((guild) => String(guild.id) === guildId)?.name ?? guildId}</strong>
                <span>/</span>
                <strong>#{requestChannels.find((channel) => String(channel.id) === requestChannelId)?.name ?? requestChannelId}</strong>
              </div>
            ) : null}
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
                  {requestRows.slice(0, 50).map((request, index) => (
                    <tr key={index}>
                      <td>{String(request.status ?? request.state ?? "-")}</td>
                      <td>{String(request.providerLabel ?? request.provider ?? request.providerId ?? "-")}</td>
                      <td>{String(request.createdAt ?? request.timestamp ?? "-")}</td>
                    </tr>
                  ))}
                  {!requestChannelId && (
                    <tr><td colSpan={3} className="empty-table-cell">확인할 채널을 먼저 선택하세요.</td></tr>
                  )}
                  {requestChannelId && !requestLoading && requestRows.length === 0 && (
                    <tr><td colSpan={3} className="empty-table-cell">선택한 채널의 요청 기록이 없습니다.</td></tr>
                  )}
                  {requestLoading && (
                    <tr><td colSpan={3} className="empty-table-cell">요청 기록을 불러오는 중입니다.</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </section>
        )}

        {activeView === "FEWSHOT" && (
          <>
            <section id="fewshot" className="fewshot-studio" aria-labelledby="fewshot-page-title">
              <div className="fewshot-page-title">
                <div>
                  <h2 id="fewshot-page-title">데이터셋</h2>
                  <span>{selectedFewShotSet ? scopeKey(selectedFewShotSet.scope) : scopeKey(fewShotScope)}</span>
                </div>
                <dl>
                  <div><dt>판단 예시</dt><dd>{fewShotExamples.length}</dd></div>
                  <div><dt>발화 예시</dt><dd>{fewShotOutputCount}</dd></div>
                  <div><dt>활성 버전</dt><dd>{activeFewShot ? `v${activeFewShot.version}` : "없음"}</dd></div>
                  <div><dt>편집 버전</dt><dd>{editableDraft ? `v${editableDraft.version}` : "새 초안"}</dd></div>
                </dl>
              </div>

          <div className="set-board">
            <div className="set-board-head">
              <div>
                <span>적용 범위</span>
                <strong>{selectedFewShotSet ? scopeKey(selectedFewShotSet.scope) : "새 세트"}</strong>
              </div>
              <span>{editableDraft ? `초안 v${editableDraft.version}` : activeFewShot ? `사용 중 v${activeFewShot.version}` : "게시 전"}</span>
            </div>
            <div className="fewshot-grid">
              <div className="fewshot-list">
                {fewShotSets.map((set) => (
                  <button
                    key={String(set.id)}
                    type="button"
                    className={String(set.id) === String(selectedFewShotSet?.id) ? "selected" : ""}
                    onClick={() => setSelectedFewShotSetId(String(set.id))}
                  >
                    <strong>{scopeKey(set.scope)}</strong>
                    <span>사용 중 v{set.activeVersion ?? "-"}</span>
                  </button>
                ))}
                {fewShotSets.length === 0 && <p className="empty-copy">저장된 세트가 없습니다.</p>}
              </div>

              <div className="fewshot-versions">
                {(selectedFewShotSet?.versions ?? []).map((version) => (
                  <div className="version-row" key={version.version}>
                    <div>
                      <strong>v{version.version}</strong>
                      <StatusPill value={version.status} />
                      <span>예시 {version.examples.length}개</span>
                    </div>
                    <div className="button-row compact">
                      <button
                        className="secondary-action"
                        type="button"
                        onClick={() => rollbackFewShot(version.version)}
                        disabled={fewShotLoading || version.status === "DRAFT" || version.version === selectedFewShotSet?.activeVersion}
                      >
                        <Undo2 size={15} /> 되돌리기
                      </button>
                      <button
                        className="danger-action"
                        type="button"
                        onClick={() => archiveFewShot(version.version)}
                        disabled={fewShotLoading || version.version === selectedFewShotSet?.activeVersion}
                      >
                        <Archive size={15} /> 보관
                      </button>
                    </div>
                  </div>
                ))}
                {!selectedFewShotSet && <p className="empty-copy">초안을 저장하면 버전 이력이 생성됩니다.</p>}
              </div>
            </div>
              </div>
            </section>

            <section className="fewshot-editor-shell" aria-labelledby="fewshot-editor-title">
              <div className="editor-heading">
                <div>
                  <h2 id="fewshot-editor-title">예시 편집</h2>
                  <p>저장되는 예시 한 건을 그대로 편집합니다.</p>
                </div>
                <span>{editableDraft ? `초안 v${editableDraft.version}` : selectedFewShotSet ? "새 초안" : "새 세트"}</span>
              </div>

              {!selectedFewShotSet && (
                <div className="scope-editor">
                  <SelectField
                    label="적용 범위"
                    value={fewShotScope.type}
                    onChange={(type) => setFewShotScope((scope) => ({ ...scope, type: type as NiaFewShotScope["type"] }))}
                    options={["GLOBAL", "GUILD", "CHANNEL", "PERSONA"]}
                    optionLabels={{ GLOBAL: "전체", GUILD: "서버", CHANNEL: "채널", PERSONA: "페르소나" }}
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
                <aside className="fewshot-example-list" aria-label="예시 목록">
                  <div className="example-list-toolbar">
                    <div>
                      <span>예시</span>
                      <strong>{fewShotExamples.length}개</strong>
                    </div>
                    <button className="secondary-action" type="button" onClick={addFewShotExample}>
                      <Plus size={16} /> 추가
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
                        <small>{ACTION_LABELS[example.expectedAction as keyof typeof ACTION_LABELS] ?? example.expectedAction} · 대화 {example.rawMessages.length}개</small>
                      </span>
                    </button>
                  ))}
                </aside>

                {selectedFewShotExample && (
                  <div className="fewshot-example-editor">
                    <div className="example-editor-toolbar">
                      <div>
                        <strong>예시 {String(selectedFewShotExampleIndex + 1).padStart(2, "0")}</strong>
                        <span>{ACTION_LABELS[selectedFewShotExample.expectedAction as keyof typeof ACTION_LABELS] ?? selectedFewShotExample.expectedAction}</span>
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

                    <div className="scene-metadata">
                      <Field
                        label="예시 이름"
                        value={selectedFewShotExample.title}
                        onChange={(title) => updateSelectedFewShotExample((example) => ({ ...example, title }))}
                        placeholder="예: 최신 불만을 알아채고 이야기 멈추기"
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
                        placeholder="latest-feedback, repair"
                      />
                      <Field
                        label="우선순위"
                        value={String(selectedFewShotExample.priority)}
                        onChange={(value) => updateSelectedFewShotExample((example) => ({ ...example, priority: Number(value) || 0 }))}
                      />
                      <SelectField
                        label="데이터 출처"
                        value={selectedFewShotExample.privacyClass ?? "SYNTHETIC"}
                        onChange={(privacyClass) => updateSelectedFewShotExample((example) => ({ ...example, privacyClass }))}
                        options={["SYNTHETIC", "ANONYMIZED", "PRODUCTION_DERIVED"]}
                        optionLabels={{ SYNTHETIC: "작성한 예시", ANONYMIZED: "익명 처리한 대화", PRODUCTION_DERIVED: "운영 대화 기반" }}
                      />
                    </div>

                    <section className="example-section" aria-labelledby="scene-input-title">
                      <div className="example-section-heading">
                        <span>1</span>
                        <div><h3 id="scene-input-title">대화 장면</h3><p>실제 대화 순서와 화자를 입력합니다.</p></div>
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
                          <MessageSquarePlus size={16} /> 대화 추가
                        </button>
                      </div>
                      <div className="conversation-editor">
                        {selectedFewShotExample.rawMessages.map((message, messageIndex) => (
                          <div className="fewshot-message-row" key={`${message.ref}-${messageIndex}`}>
                            <span className="message-order">{String(messageIndex + 1).padStart(2, "0")}</span>
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
                              placeholder="대화 내용"
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
                                  evidenceRefs: example.evidenceRefs.filter((ref) => ref !== message.ref),
                                }))
                              }
                            >
                              <Trash2 size={15} />
                            </button>
                          </div>
                        ))}
                      </div>
                    </section>

                    <section className="example-section" aria-labelledby="judgment-title">
                      <div className="example-section-heading">
                        <span>2</span>
                        <div><h3 id="judgment-title">판단 예시</h3><p>이 장면에서 고를 행동과 그 근거입니다.</p></div>
                      </div>
                      <div className="judgment-actions">
                        <SelectField
                          label="선택할 행동"
                          value={selectedFewShotExample.expectedAction}
                          onChange={(expectedAction) =>
                            updateSelectedFewShotExample((example) => ({
                              ...example,
                              expectedAction,
                              expectedReplies: expectedAction === "SPEAK" ? example.expectedReplies : [],
                              badReplies: expectedAction === "SPEAK" ? example.badReplies : [],
                              badAlternative:
                                example.badAlternative.action === expectedAction
                                  ? { ...example.badAlternative, action: ACTIONS.find((action) => action !== expectedAction) ?? "WAIT" }
                                  : example.badAlternative,
                            }))
                          }
                          options={ACTIONS}
                          optionLabels={ACTION_LABELS}
                        />
                        <SelectField
                          label="비교할 잘못된 행동"
                          value={selectedFewShotExample.badAlternative.action}
                          onChange={(action) =>
                            updateSelectedFewShotExample((example) => ({ ...example, badAlternative: { ...example.badAlternative, action } }))
                          }
                          options={ACTIONS}
                          optionLabels={ACTION_LABELS}
                        />
                      </div>
                      <TextArea
                        label="판단 이유"
                        value={selectedFewShotExample.reason}
                        rows={5}
                        onChange={(reason) => updateSelectedFewShotExample((example) => ({ ...example, reason }))}
                      />
                      <div className="evidence-picker">
                        <div><strong>근거 메시지</strong><span>판단에 직접 사용한 대화를 선택합니다.</span></div>
                        <div>
                          {selectedFewShotExample.rawMessages.map((message, index) => (
                            <label key={message.ref} className={selectedFewShotExample.evidenceRefs.includes(message.ref) ? "selected" : ""}>
                              <input
                                type="checkbox"
                                checked={selectedFewShotExample.evidenceRefs.includes(message.ref)}
                                onChange={(event) =>
                                  updateSelectedFewShotExample((example) => ({
                                    ...example,
                                    evidenceRefs: event.target.checked
                                      ? [...new Set([...example.evidenceRefs, message.ref])]
                                      : example.evidenceRefs.filter((ref) => ref !== message.ref),
                                  }))
                                }
                              />
                              <span>{String(index + 1).padStart(2, "0")}</span>
                              <p>{message.text || "내용 없음"}</p>
                            </label>
                          ))}
                        </div>
                      </div>
                      <TextArea
                        label="비교 행동이 잘못된 이유"
                        value={selectedFewShotExample.badAlternative.whyBad}
                        rows={4}
                        onChange={(whyBad) =>
                          updateSelectedFewShotExample((example) => ({ ...example, badAlternative: { ...example.badAlternative, whyBad } }))
                        }
                      />
                    </section>

                    <section className="example-section" aria-labelledby="speech-title">
                      <div className="example-section-heading">
                        <span>3</span>
                        <div><h3 id="speech-title">발화 예시</h3><p>SPEAK 행동일 때 실제 발화 생성에 사용됩니다.</p></div>
                      </div>
                      {selectedFewShotExample.expectedAction === "SPEAK" ? (
                        <>
                          <div className="output-editor-grid">
                            <div className="output-column good-output">
                              <TextArea
                                label="좋은 발화 · 메시지 하나당 한 줄"
                                value={selectedFewShotExample.expectedReplies.join("\n")}
                                rows={6}
                                onChange={(value) =>
                                  updateSelectedFewShotExample((example) => ({ ...example, expectedReplies: value.split("\n").slice(0, 4) }))
                                }
                              />
                            </div>
                            <div className="output-column bad-output">
                              <TextArea
                                label="피해야 할 발화 · 메시지 하나당 한 줄"
                                value={(selectedFewShotExample.badReplies ?? []).join("\n")}
                                rows={6}
                                onChange={(value) =>
                                  updateSelectedFewShotExample((example) => ({ ...example, badReplies: value.split("\n").slice(0, 4) }))
                                }
                              />
                            </div>
                          </div>
                          <div className="discord-preview" aria-label="Discord 발화 미리보기">
                            <div className="preview-label">Discord 미리보기</div>
                            <div className="preview-message">
                              <div className="preview-avatar">N</div>
                              <div>
                                <div className="preview-author"><strong>니아 · Nia</strong><span>앱</span><time>방금</time></div>
                                {(selectedFewShotExample.expectedReplies.length ? selectedFewShotExample.expectedReplies : ["좋은 발화를 입력하세요"]).map((reply, index) => (
                                  <p key={`${reply}-${index}`}>{reply || "빈 메시지"}</p>
                                ))}
                              </div>
                            </div>
                          </div>
                        </>
                      ) : (
                        <div className="silent-output">
                          <strong>발화 예시 없음</strong>
                          <p>{ACTION_LABELS[selectedFewShotExample.expectedAction as keyof typeof ACTION_LABELS] ?? selectedFewShotExample.expectedAction}은 판단 모델에만 사용되며 발화 모델에는 전달되지 않습니다.</p>
                        </div>
                      )}
                    </section>
                  </div>
                )}
              </div>

              <div className="publish-bar">
                <div>
                  <strong>{fewShotEval?.readyForPublish ? "게시 조건 통과" : "초안 저장 → 게시 조건 검사 → 게시"}</strong>
                  <span>게시된 버전만 실제 판단·발화 프롬프트에 반영됩니다.</span>
                </div>
                <div className="button-row compact">
                  <button className="primary-action" type="button" onClick={saveFewShotDraft} disabled={fewShotLoading}>
                    <Send size={17} /> 초안 저장
                  </button>
                  <button className="secondary-action" type="button" onClick={runFewShotPreview} disabled={fewShotLoading || !editableDraft}>
                    <Eye size={17} /> 저장 데이터 확인
                  </button>
                  <button className="secondary-action" type="button" onClick={runFewShotEval} disabled={fewShotLoading || !editableDraft}>
                    <PlayCircle size={17} /> 게시 조건 검사
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
              <JsonPanel title="게시 조건 검사 결과" value={fewShotEval} />
              <JsonPanel title="저장 데이터" value={fewShotPreview} />
            </section>
          </>
        )}

        {activeView === "API" && (
          <section className="split-grid page-panel" id="api">
            <JsonPanel title="AI Network Snapshot" value={state?.aiNetwork} />
            <JsonPanel title="Usage Trend" value={state?.usageTrend} />
          </section>
        )}
      </section>
    </main>
  );
}

export default App;

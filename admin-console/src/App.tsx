import {
  BookOpen,
  Check,
  ChevronRight,
  Copy,
  History,
  Plus,
  RefreshCw,
  Save,
  ShieldAlert,
  Trash2,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import { captureConsoleError, wasBugsinkReported } from "./bugsink";
import {
  parseConversationMarkdown,
  renameConversationMarkdown,
  serializeConversationMarkdown,
  summarizeConversationMarkdown,
  toStoredAction,
} from "./conversation-markdown";
import {
  createFewShotDraft,
  createFewShotDraftForSet,
  evalFewShotDraft,
  loadDashboard,
  loadFewShotSets,
  loadGuildChannels,
  loadNiaExecutions,
  publishFewShotVersion,
  replaceFewShotDraft,
  type ChannelSummary,
  type DashboardState,
  type NiaExecution,
  type NiaExecutionMessage,
  type NiaFewShotExample,
  type NiaFewShotSet,
  type NiaFewShotVersion,
} from "./api";

type AdminView = "DATA" | "EXECUTIONS";

const VIEW_META: Record<AdminView, { hash: string; title: string }> = {
  DATA: { hash: "data", title: "대화 데이터" },
  EXECUTIONS: { hash: "executions", title: "실행 기록" },
};

const GUILD_ID_STORAGE_KEY = "nexa-console-guild-id";

function viewFromHash(hash: string): AdminView {
  return hash.replace(/^#/, "") === VIEW_META.EXECUTIONS.hash ? "EXECUTIONS" : "DATA";
}

function newConversation(): NiaFewShotExample {
  return {
    title: "새 대화",
    rawMessages: [
      { ref: "m1", authorRole: "member", offsetMs: 0, text: "" },
      { ref: "m2", authorRole: "member", offsetMs: 1000, text: "" },
    ],
    expectedAction: "SPEAK",
    expectedReplies: [""],
    badReplies: [],
    currentState: null,
    expectedReactionCode: null,
    expectedReevaluateAfterMs: null,
    reason: "현재 대화의 흐름을 자연스럽게 이어간다.",
    evidenceRefs: ["m2"],
    badAlternative: { action: "WAIT", whyBad: "저장된 다음 발화를 수행하지 못한다." },
    tags: [],
    priority: 100,
    privacyClass: "SYNTHETIC",
    evalStatus: "NOT_RUN",
  };
}

function cloneExamples(examples: NiaFewShotExample[]): NiaFewShotExample[] {
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

function activeVersion(set: NiaFewShotSet | null): NiaFewShotVersion | null {
  return set?.versions.find((version) => version.version === set.activeVersion) ?? null;
}

function draftVersion(set: NiaFewShotSet | null): NiaFewShotVersion | null {
  return set?.versions.find((version) => version.status === "DRAFT") ?? null;
}

function scopeLabel(set: NiaFewShotSet): string {
  switch (set.scope.type) {
    case "CHANNEL":
      return `채널 ${set.scope.channelId ?? "-"}`;
    case "GUILD":
      return `서버 ${set.scope.guildId ?? "-"}`;
    case "PERSONA":
      return `페르소나 ${set.scope.persona ?? "nia"}`;
    default:
      return "모든 서버 공통";
  }
}

function normalizeExamples(examples: NiaFewShotExample[]): NiaFewShotExample[] {
  return examples.map((example) => {
    const rawMessages = example.rawMessages
      .filter((message) => message.text.trim())
      .map((message, index) => ({
        ...message,
        ref: `m${index + 1}`,
        offsetMs: index * 1000,
        text: message.text.trim(),
      }));
    if (rawMessages.length === 0) throw new Error(`'${example.title}' 대화가 비어 있습니다.`);
    const expectedReplies = example.expectedReplies.map((reply) => reply.trim()).filter(Boolean);
    if (example.expectedAction === "SPEAK" && expectedReplies.length === 0) {
      throw new Error(`'${example.title}'에 니아가 이어서 할 말을 입력하세요.`);
    }
    const expectedAction = example.expectedAction || "SPEAK";
    if (expectedAction === "REACT" && !example.expectedReactionCode?.trim()) {
      throw new Error(`'${example.title}'에 리액션을 입력하세요.`);
    }
    if (expectedAction === "WAIT" && (!example.expectedReevaluateAfterMs || example.expectedReevaluateAfterMs <= 0)) {
      throw new Error(`'${example.title}'에 다시 판단할 시간을 입력하세요.`);
    }
    const badAction = example.badAlternative.action === expectedAction ? "WAIT" : example.badAlternative.action;
    return {
      ...example,
      id: null,
      title: example.title.trim() || "제목 없는 대화",
      rawMessages,
      expectedReplies: expectedAction === "SPEAK" ? expectedReplies : [],
      badReplies: expectedAction === "SPEAK" ? example.badReplies.filter((reply) => reply.trim()) : [],
      currentState: example.currentState?.trim() || null,
      expectedReactionCode: expectedAction === "REACT" ? example.expectedReactionCode?.trim() : null,
      expectedReevaluateAfterMs: expectedAction === "WAIT" ? example.expectedReevaluateAfterMs : null,
      evidenceRefs: [rawMessages.at(-1)?.ref ?? "m1"],
      badAlternative: { ...example.badAlternative, action: badAction },
    };
  });
}

function formatTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("ko-KR", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

function Conversation({ messages }: { messages: NiaExecutionMessage[] }) {
  if (messages.length === 0) return <p className="empty-copy">저장된 대화가 없습니다.</p>;
  return (
    <div className="conversation-lines">
      {messages.map((message, index) => (
        <div className="conversation-line" key={`${message.speaker}-${index}`}>
          <span>{message.speaker}</span>
          <p>{message.text}</p>
        </div>
      ))}
    </div>
  );
}

function App() {
  const [activeView, setActiveView] = useState<AdminView>(() => viewFromHash(window.location.hash));
  const [sets, setSets] = useState<NiaFewShotSet[]>([]);
  const [selectedSetId, setSelectedSetId] = useState("");
  const [examples, setExamples] = useState<NiaFewShotExample[]>([newConversation()]);
  const [markdownDocuments, setMarkdownDocuments] = useState<string[]>([
    serializeConversationMarkdown(newConversation()),
  ]);
  const [selectedExampleIndex, setSelectedExampleIndex] = useState(0);
  const [dashboard, setDashboard] = useState<DashboardState | null>(null);
  const [guildId, setGuildId] = useState(() => window.localStorage.getItem(GUILD_ID_STORAGE_KEY) ?? "");
  const [channels, setChannels] = useState<ChannelSummary[]>([]);
  const [channelId, setChannelId] = useState("");
  const [executions, setExecutions] = useState<NiaExecution[]>([]);
  const [selectedExecutionId, setSelectedExecutionId] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const selectedSet = useMemo(
    () => sets.find((set) => String(set.id) === selectedSetId) ?? sets[0] ?? null,
    [sets, selectedSetId],
  );
  const draft = useMemo(() => draftVersion(selectedSet), [selectedSet]);
  const active = useMemo(() => activeVersion(selectedSet), [selectedSet]);
  const selectedExample = examples[selectedExampleIndex] ?? examples[0] ?? null;
  const selectedMarkdown = markdownDocuments[selectedExampleIndex] ?? markdownDocuments[0] ?? "";
  const selectedExecution = useMemo(
    () => executions.find((execution) => execution.correlationId === selectedExecutionId) ?? executions[0] ?? null,
    [executions, selectedExecutionId],
  );

  useEffect(() => {
    const onHashChange = () => setActiveView(viewFromHash(window.location.hash));
    onHashChange();
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);

  useEffect(() => {
    if (activeView === "DATA") void refreshData();
    else void refreshExecutionScope();
  }, [activeView]);

  useEffect(() => {
    const source = draft?.examples ?? active?.examples;
    const nextExamples = source?.length ? cloneExamples(source) : [newConversation()];
    setExamples(nextExamples);
    setMarkdownDocuments(nextExamples.map(serializeConversationMarkdown));
    setSelectedExampleIndex(0);
  }, [selectedSet?.id, draft?.version, draft?.updatedAt, active?.version, active?.updatedAt]);

  async function run(task: () => Promise<void>) {
    setLoading(true);
    setError("");
    try {
      await task();
    } catch (cause) {
      if (!wasBugsinkReported(cause)) captureConsoleError(cause);
      setError(cause instanceof Error ? cause.message : "요청을 처리하지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  async function refreshData() {
    await run(async () => {
      const nextSets = await loadFewShotSets();
      setSets(nextSets);
      setSelectedSetId((current) => current || String(nextSets[0]?.id ?? ""));
    });
  }

  async function refreshExecutionScope(nextGuildId = guildId) {
    await run(async () => {
      const nextDashboard = await loadDashboard(nextGuildId);
      const selectedGuildId = nextDashboard.selectedGuildId;
      setDashboard(nextDashboard);
      setGuildId(selectedGuildId);
      window.localStorage.setItem(GUILD_ID_STORAGE_KEY, selectedGuildId);
      setChannels(await loadGuildChannels(selectedGuildId));
      setChannelId("");
      setExecutions([]);
      setSelectedExecutionId("");
    });
  }

  async function selectChannel(nextChannelId: string) {
    setChannelId(nextChannelId);
    setExecutions([]);
    setSelectedExecutionId("");
    if (!nextChannelId) return;
    await run(async () => {
      const nextExecutions = await loadNiaExecutions(guildId, nextChannelId);
      setExecutions(nextExecutions);
      setSelectedExecutionId(nextExecutions[0]?.correlationId ?? "");
    });
  }

  function updateMarkdown(markdown: string) {
    setMarkdownDocuments((current) =>
      current.map((document, index) => (index === selectedExampleIndex ? markdown : document)),
    );
  }

  function addExample() {
    const conversation = newConversation();
    setExamples((current) => [...current, conversation]);
    setMarkdownDocuments((current) => [...current, serializeConversationMarkdown(conversation)]);
    setSelectedExampleIndex(examples.length);
  }

  function duplicateExample() {
    if (!selectedExample) return;
    const copy = cloneExamples([selectedExample])[0];
    const summary = summarizeConversationMarkdown(selectedMarkdown);
    const title = `${summary.title} 복사본`;
    setExamples((current) => [...current, { ...copy, id: null, title }]);
    setMarkdownDocuments((current) => [...current, renameConversationMarkdown(selectedMarkdown, title)]);
    setSelectedExampleIndex(examples.length);
  }

  function removeExample() {
    if (examples.length === 1) return;
    setExamples((current) => current.filter((_, index) => index !== selectedExampleIndex));
    setMarkdownDocuments((current) => current.filter((_, index) => index !== selectedExampleIndex));
    setSelectedExampleIndex((current) => Math.max(0, current - 1));
  }

  function examplesFromMarkdown(): NiaFewShotExample[] {
    return markdownDocuments.map((markdown, index) => {
      const parsed = parseConversationMarkdown(markdown);
      const base = examples[index] ?? newConversation();
      return {
        ...base,
        title: parsed.title,
        rawMessages: parsed.messages.map((message, messageIndex) => ({
          ref: `m${messageIndex + 1}`,
          authorRole: message.authorRole,
          offsetMs: messageIndex * 1000,
          text: message.text,
        })),
        expectedAction: toStoredAction(parsed.action),
        expectedReplies: parsed.replies,
        currentState: parsed.currentState,
        expectedReactionCode: parsed.reactionCode,
        expectedReevaluateAfterMs: parsed.reevaluateAfterMs,
        reason: parsed.reason,
        badAlternative: {
          action: toStoredAction(parsed.badAction),
          whyBad: parsed.badReason,
        },
      };
    });
  }

  async function saveDraft(): Promise<NiaFewShotVersion | null> {
    let saved: NiaFewShotVersion | null = null;
    await run(async () => {
      const normalized = normalizeExamples(examplesFromMarkdown());
      if (draft?.setId) saved = await replaceFewShotDraft(draft.setId, draft.version, normalized);
      else if (selectedSet?.id) saved = await createFewShotDraftForSet(selectedSet.id, normalized);
      else {
        saved = await createFewShotDraft(
          { type: "GLOBAL", guildId: null, channelId: null, persona: "nia" },
          normalized,
        );
      }
      const nextSets = await loadFewShotSets();
      setSets(nextSets);
      setSelectedSetId(String(saved?.setId ?? nextSets[0]?.id ?? ""));
    });
    return saved;
  }

  async function applyDraft() {
    await run(async () => {
      if (!draft?.setId) throw new Error("먼저 대화 데이터를 저장하세요.");
      const evaluation = await evalFewShotDraft(draft.setId, draft.version);
      if (!evaluation.readyForPublish) {
        throw new Error(evaluation.failures[0] ?? "이 데이터는 아직 적용할 수 없습니다.");
      }
      await publishFewShotVersion(draft.setId, draft.version);
      const nextSets = await loadFewShotSets();
      setSets(nextSets);
    });
  }

  return (
    <main className="console-shell">
      <aside className="sidebar">
        <div className="brand-lockup">
          <div className="brand-mark">N</div>
          <div>
            <strong>Nexa Console</strong>
            <span>NIA conversation control</span>
          </div>
        </div>
        <nav aria-label="관리 영역">
          <a href="#data" className={activeView === "DATA" ? "active" : ""}>
            <BookOpen size={18} /> 대화 데이터
          </a>
          <a href="#executions" className={activeView === "EXECUTIONS" ? "active" : ""}>
            <History size={18} /> 실행 기록
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
            className="secondary-action"
            onClick={() => (activeView === "DATA" ? void refreshData() : void refreshExecutionScope())}
            disabled={loading}
          >
            <RefreshCw size={17} className={loading ? "spin" : ""} /> 새로고침
          </button>
        </header>

        {error ? (
          <div className="error-banner" role="alert">
            <ShieldAlert size={18} /> {error}
          </div>
        ) : null}

        {activeView === "DATA" ? (
          <section className="data-workspace">
            <div className="data-toolbar">
              <label>
                <span>대화 데이터</span>
                <select value={selectedSetId} onChange={(event) => setSelectedSetId(event.target.value)}>
                  {sets.map((set) => (
                    <option key={String(set.id)} value={String(set.id)}>{scopeLabel(set)}</option>
                  ))}
                  {sets.length === 0 ? <option value="">저장된 데이터 없음</option> : null}
                </select>
              </label>
              <div className="version-status">
                <span>사용 중 {active ? `v${active.version}` : "없음"}</span>
                <span>편집 중 {draft ? `v${draft.version}` : "새 초안"}</span>
              </div>
              <div className="toolbar-actions">
                <button className="secondary-action" onClick={() => void saveDraft()} disabled={loading}>
                  <Save size={16} /> 저장
                </button>
                <button className="primary-action" onClick={() => void applyDraft()} disabled={loading || !draft}>
                  <Check size={16} /> 니아에 적용
                </button>
              </div>
            </div>

            <div className="data-layout">
              <aside className="episode-list">
                <div className="section-heading">
                  <strong>대화 {examples.length}개</strong>
                  <button type="button" onClick={addExample} aria-label="대화 추가"><Plus size={17} /></button>
                </div>
                {markdownDocuments.map((markdown, index) => {
                  const summary = summarizeConversationMarkdown(markdown);
                  return (
                    <button
                      type="button"
                      className={index === selectedExampleIndex ? "episode-row selected" : "episode-row"}
                      key={index}
                      onClick={() => setSelectedExampleIndex(index)}
                    >
                      <span>{summary.title}</span>
                      <small>{summary.action} · {summary.messageCount}개 메시지</small>
                      <ChevronRight size={15} />
                    </button>
                  );
                })}
              </aside>

              {selectedExample ? (
                <article className="episode-editor">
                  <div className="editor-heading">
                    <div>
                      <strong>Markdown 편집기</strong>
                      <span>대화 전체를 아래 한 칸에 붙여넣으세요</span>
                    </div>
                    <div>
                      <button type="button" onClick={duplicateExample} aria-label="대화 복제"><Copy size={17} /></button>
                      <button type="button" onClick={removeExample} disabled={examples.length === 1} aria-label="대화 삭제">
                        <Trash2 size={17} />
                      </button>
                    </div>
                  </div>

                  <div className="markdown-format" aria-label="Markdown 형식">
                    <code># 제목</code>
                    <code>## 니아의 행동</code>
                    <code>SPEAK · REACT · WAIT · IGNORE · CANCEL_PENDING</code>
                    <code>## 현재 상태</code>
                    <code>## 대화</code>
                    <code>### 사람</code>
                    <code>### 니아</code>
                    <code>## 판단 이유</code>
                    <code>## 피해야 할 행동</code>
                    <code>## 니아가 이어서 할 말</code>
                    <code>## 리액션</code>
                    <code>## 다시 판단할 시간(ms)</code>
                  </div>
                  <textarea
                    className="markdown-editor"
                    value={selectedMarkdown}
                    onChange={(event) => updateMarkdown(event.target.value)}
                    aria-label="대화 Markdown"
                    spellCheck={false}
                  />
                </article>
              ) : null}
            </div>
          </section>
        ) : (
          <section className="execution-workspace">
            <div className="execution-toolbar">
              <label>
                <span>Discord 서버</span>
                <select value={guildId} onChange={(event) => void refreshExecutionScope(event.target.value)}>
                  {(dashboard?.guilds ?? []).map((guild) => (
                    <option key={String(guild.id)} value={String(guild.id)}>{guild.name}</option>
                  ))}
                </select>
              </label>
              <label>
                <span>채널</span>
                <select value={channelId} onChange={(event) => void selectChannel(event.target.value)}>
                  <option value="">채널 선택</option>
                  {channels.map((channel) => (
                    <option key={String(channel.id)} value={String(channel.id)}>#{channel.name}</option>
                  ))}
                </select>
              </label>
            </div>

            <div className="execution-layout">
              <aside className="execution-list">
                <div className="section-heading"><strong>최근 실행 {executions.length}건</strong></div>
                {!channelId ? <p className="empty-copy">채널을 선택하세요.</p> : null}
                {channelId && executions.length === 0 && !loading ? <p className="empty-copy">최근 실행이 없습니다.</p> : null}
                {executions.map((execution) => (
                  <button
                    type="button"
                    className={execution.correlationId === selectedExecution?.correlationId ? "execution-row selected" : "execution-row"}
                    key={execution.correlationId}
                    onClick={() => setSelectedExecutionId(execution.correlationId)}
                  >
                    <span>{formatTime(execution.recordedAt)}</span>
                    <strong>{execution.niaReply[0] || (execution.willSpeak ? "발화 예약" : "발화 없음")}</strong>
                    <small>{execution.currentConversation.at(-1)?.text ?? execution.outcome}</small>
                  </button>
                ))}
              </aside>

              <article className="execution-detail">
                {selectedExecution ? (
                  <>
                    <section>
                      <div className="detail-heading"><span>1</span><h2>현재 대화</h2></div>
                      <Conversation messages={selectedExecution.currentConversation} />
                    </section>
                    <section>
                      <div className="detail-heading"><span>2</span><h2>참고한 대화</h2></div>
                      {selectedExecution.retrievedConversations.length === 0 ? (
                        <p className="empty-copy">이 실행에서 검색된 대화가 없습니다.</p>
                      ) : (
                        <div className="retrieved-grid">
                          {selectedExecution.retrievedConversations.slice(0, 2).map((conversation) => (
                            <div className="retrieved-card" key={conversation.id}>
                              <strong>{conversation.id}</strong>
                              <Conversation messages={conversation.messages} />
                            </div>
                          ))}
                        </div>
                      )}
                    </section>
                    <section className="nia-output">
                      <div className="detail-heading"><span>3</span><h2>니아의 답변</h2></div>
                      {selectedExecution.niaReply.length ? (
                        selectedExecution.niaReply.map((bubble, index) => <p key={index}>{bubble}</p>)
                      ) : (
                        <p className="empty-copy">니아가 말하지 않은 실행입니다.</p>
                      )}
                    </section>
                  </>
                ) : (
                  <div className="detail-placeholder">왼쪽에서 실행을 선택하세요.</div>
                )}
              </article>
            </div>
          </section>
        )}
      </section>
    </main>
  );
}

export default App;

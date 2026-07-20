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
      return "전체 서버";
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
    const badAction = example.badAlternative.action === expectedAction ? "WAIT" : example.badAlternative.action;
    return {
      ...example,
      id: null,
      title: example.title.trim() || "제목 없는 대화",
      rawMessages,
      expectedReplies: expectedAction === "SPEAK" ? expectedReplies : [],
      badReplies: expectedAction === "SPEAK" ? example.badReplies.filter((reply) => reply.trim()) : [],
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
    setExamples(source?.length ? cloneExamples(source) : [newConversation()]);
    setSelectedExampleIndex(0);
  }, [selectedSet?.id, draft?.version, active?.version]);

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

  function updateExample(update: (example: NiaFewShotExample) => NiaFewShotExample) {
    setExamples((current) => current.map((example, index) => (index === selectedExampleIndex ? update(example) : example)));
  }

  function addExample() {
    setExamples((current) => [...current, newConversation()]);
    setSelectedExampleIndex(examples.length);
  }

  function duplicateExample() {
    if (!selectedExample) return;
    const copy = cloneExamples([selectedExample])[0];
    setExamples((current) => [...current, { ...copy, id: null, title: `${copy.title} 복사본` }]);
    setSelectedExampleIndex(examples.length);
  }

  function removeExample() {
    if (examples.length === 1) return;
    setExamples((current) => current.filter((_, index) => index !== selectedExampleIndex));
    setSelectedExampleIndex((current) => Math.max(0, current - 1));
  }

  async function saveDraft(): Promise<NiaFewShotVersion | null> {
    let saved: NiaFewShotVersion | null = null;
    await run(async () => {
      const normalized = normalizeExamples(examples);
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
                <span>데이터 세트</span>
                <select value={selectedSetId} onChange={(event) => setSelectedSetId(event.target.value)}>
                  {sets.map((set) => (
                    <option key={String(set.id)} value={String(set.id)}>{scopeLabel(set)}</option>
                  ))}
                  {sets.length === 0 ? <option value="">새 전체 서버 데이터</option> : null}
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
                {examples.map((example, index) => (
                  <button
                    type="button"
                    className={index === selectedExampleIndex ? "episode-row selected" : "episode-row"}
                    key={`${example.title}-${index}`}
                    onClick={() => setSelectedExampleIndex(index)}
                  >
                    <span>{example.title || "제목 없는 대화"}</span>
                    <small>{example.rawMessages.length}줄</small>
                    <ChevronRight size={15} />
                  </button>
                ))}
              </aside>

              {selectedExample ? (
                <article className="episode-editor">
                  <div className="editor-heading">
                    <input
                      className="title-input"
                      value={selectedExample.title}
                      onChange={(event) => updateExample((example) => ({ ...example, title: event.target.value }))}
                      aria-label="대화 제목"
                    />
                    <div>
                      <button type="button" onClick={duplicateExample} aria-label="대화 복제"><Copy size={17} /></button>
                      <button type="button" onClick={removeExample} disabled={examples.length === 1} aria-label="대화 삭제">
                        <Trash2 size={17} />
                      </button>
                    </div>
                  </div>

                  <div className="message-editor">
                    <div className="section-heading">
                      <strong>대화 원문</strong>
                      <button
                        type="button"
                        onClick={() =>
                          updateExample((example) => ({
                            ...example,
                            rawMessages: [
                              ...example.rawMessages,
                              {
                                ref: `m${example.rawMessages.length + 1}`,
                                authorRole: "member",
                                offsetMs: example.rawMessages.length * 1000,
                                text: "",
                              },
                            ],
                          }))
                        }
                      >
                        <Plus size={15} /> 줄 추가
                      </button>
                    </div>
                    {selectedExample.rawMessages.map((message, messageIndex) => (
                      <div className="message-row" key={`${message.ref}-${messageIndex}`}>
                        <select
                          value={message.authorRole}
                          onChange={(event) =>
                            updateExample((example) => ({
                              ...example,
                              rawMessages: example.rawMessages.map((item, index) =>
                                index === messageIndex ? { ...item, authorRole: event.target.value } : item,
                              ),
                            }))
                          }
                          aria-label={`${messageIndex + 1}번째 화자`}
                        >
                          <option value="member">사람</option>
                          <option value="nia">니아</option>
                        </select>
                        <textarea
                          value={message.text}
                          onChange={(event) =>
                            updateExample((example) => ({
                              ...example,
                              rawMessages: example.rawMessages.map((item, index) =>
                                index === messageIndex ? { ...item, text: event.target.value } : item,
                              ),
                            }))
                          }
                          rows={2}
                          placeholder="실제 대화를 그대로 입력"
                        />
                        <button
                          type="button"
                          onClick={() =>
                            updateExample((example) => ({
                              ...example,
                              rawMessages: example.rawMessages.filter((_, index) => index !== messageIndex),
                            }))
                          }
                          disabled={selectedExample.rawMessages.length === 1}
                          aria-label={`${messageIndex + 1}번째 대화 삭제`}
                        >
                          <Trash2 size={15} />
                        </button>
                      </div>
                    ))}
                  </div>

                  <label className="reply-editor">
                    <span>니아가 이어서 할 말</span>
                    <textarea
                      value={selectedExample.expectedReplies.join("\n")}
                      onChange={(event) =>
                        updateExample((example) => ({ ...example, expectedReplies: event.target.value.split("\n") }))
                      }
                      rows={4}
                      placeholder="메시지가 여러 개면 줄을 나눠 입력"
                    />
                  </label>
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

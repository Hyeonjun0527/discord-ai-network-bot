import {
  BookOpen,
  Check,
  Database,
  History,
  Plus,
  RefreshCw,
  Save,
  Search,
  SlidersHorizontal,
  ShieldAlert,
  Trash2,
  Upload,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import { captureConsoleError, wasBugsinkReported } from "./bugsink";
import {
  parseConversationDataset,
  serializeConversationDataset,
  summarizeConversationDataset,
  toStoredAction,
} from "./conversation-markdown";
import {
  createFewShotDraft,
  createFewShotDraftForSet,
  createConversationRagEntries,
  createConversationRagEntry,
  deleteConversationRagEntry,
  evalFewShotDraft,
  loadEffectiveFewShot,
  loadDashboard,
  loadConversationRagStats,
  loadConversationRagEntries,
  loadConversationRagEntry,
  loadFewShotSets,
  loadGuildChannels,
  loadNiaExecutions,
  publishFewShotVersion,
  replaceFewShotDraft,
  searchConversationRag,
  updateConversationRagEntry,
  loadNiaPromptConfiguration,
  saveNiaPromptConfiguration,
  applyNiaPromptConfiguration,
  resetNiaPromptDraft,
  type ChannelSummary,
  type DashboardState,
  type ConversationRagStats,
  type ConversationRagEntrySummary,
  type ConversationRagMatch,
  type NiaExecution,
  type NiaExecutionMessage,
  type NiaFewShotExample,
  type NiaEffectiveFewShot,
  type NiaFewShotSet,
  type NiaFewShotVersion,
  type NiaPromptConfiguration,
} from "./api";

type AdminView = "PROMPTS" | "GLOBAL_FEWSHOT" | "CONVERSATION_RAG" | "EXECUTIONS";

const VIEW_META: Record<AdminView, { hash: string; title: string }> = {
  PROMPTS: { hash: "prompts", title: "기본 프롬프트" },
  GLOBAL_FEWSHOT: { hash: "global-fewshot", title: "전역 Few-shot" },
  CONVERSATION_RAG: { hash: "conversation-rag", title: "대화 RAG" },
  EXECUTIONS: { hash: "executions", title: "실행 기록" },
};

const GUILD_ID_STORAGE_KEY = "nexa-console-guild-id";

function viewFromHash(hash: string): AdminView {
  const value = hash.replace(/^#/, "");
  if (value === VIEW_META.EXECUTIONS.hash) return "EXECUTIONS";
  if (value === VIEW_META.CONVERSATION_RAG.hash) return "CONVERSATION_RAG";
  if (value === VIEW_META.PROMPTS.hash) return "PROMPTS";
  return "GLOBAL_FEWSHOT";
}

function newConversation(): NiaFewShotExample {
  return {
    title: "새 대화",
    rawMessages: [
      { ref: "m1", authorRole: "a", offsetMs: 0, text: "오늘 모임 몇 시야" },
      { ref: "m2", authorRole: "b", offsetMs: 1000, text: "여덟 시" },
      { ref: "m3", authorRole: "a", offsetMs: 2000, text: "아 확인" },
    ],
    expectedAction: "CANCEL",
    expectedDeliveryMode: null,
    expectedReplies: [],
    badReplies: [],
    currentState: "니아가 2초 뒤 A의 질문에 답하려고 기다리는 중",
    expectedReactionCode: null,
    expectedReevaluateAfterMs: null,
    reason: "B가 이미 답했고 A도 확인했으므로 예약된 발화를 취소한다",
    evidenceRefs: ["m2", "m3"],
    badAlternative: { action: "SPEAK", deliveryMode: "CHANNEL", whyBad: "끝난 질문에 뒤늦게 같은 답을 하게 된다" },
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
    return {
      ...example,
      id: null,
      title: example.title.trim() || "제목 없는 대화",
      rawMessages,
      expectedReplies: expectedAction === "SPEAK" ? expectedReplies : [],
      expectedDeliveryMode: expectedAction === "SPEAK" ? example.expectedDeliveryMode : null,
      badReplies: expectedAction === "SPEAK" ? example.badReplies.filter((reply) => reply.trim()) : [],
      currentState: example.currentState?.trim() || null,
      expectedReactionCode: expectedAction === "REACT" ? example.expectedReactionCode?.trim() : null,
      expectedReevaluateAfterMs: expectedAction === "WAIT" ? example.expectedReevaluateAfterMs : null,
      evidenceRefs: [rawMessages.at(-1)?.ref ?? "m1"],
      badAlternative: {
        ...example.badAlternative,
        deliveryMode: example.badAlternative.action === "SPEAK" ? example.badAlternative.deliveryMode : null,
      },
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

function parseExamples(markdown: string, bases: NiaFewShotExample[]): NiaFewShotExample[] {
  return parseConversationDataset(markdown).map((parsed, index) => {
    const base = bases[index] ?? newConversation();
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
      expectedDeliveryMode: parsed.deliveryMode,
      expectedReplies: parsed.replies,
      badReplies: [],
      currentState: parsed.currentState,
      expectedReactionCode: parsed.reactionCode,
      expectedReevaluateAfterMs: parsed.reevaluateAfterMs,
      reason: parsed.reason,
      badAlternative: {
        action: toStoredAction(parsed.badAction),
        deliveryMode: parsed.badDeliveryMode,
        whyBad: parsed.badReason,
      },
      tags: [],
      priority: 100,
      privacyClass: "SYNTHETIC",
      evalStatus: "NOT_RUN",
    };
  });
}

function MarkdownFormat() {
  return (
    <div className="markdown-format" aria-label="Markdown 형식">
      <code>- A: 메시지</code>
      <code>- NIA: 이전 메시지</code>
      <code>- =&gt; NIA [SPEAK CHANNEL]: 발화</code>
      <code>- =&gt; NIA [SPEAK REPLY]: 발화</code>
      <code>- =&gt; NIA [REACT 👍]</code>
      <code>- =&gt; NIA [WAIT 1800ms]</code>
      <code>- =&gt; NIA [IGNORE]</code>
      <code>- =&gt; NIA [CANCEL_PENDING]</code>
      <code>--- 예시 구분</code>
    </div>
  );
}

function BuiltInJudgeExamples({ examples }: { examples: NiaFewShotExample[] }) {
  return (
    <div className="built-in-list">
      {examples.map((example, index) => (
        <details key={`${example.title}-${index}`}>
          <summary>
            <span>{index + 1}</span>
            <strong>{example.title}</strong>
            <small>{example.expectedAction}{example.expectedDeliveryMode ? ` · ${example.expectedDeliveryMode}` : ""}</small>
          </summary>
          <div className="built-in-detail">
            <Conversation messages={example.rawMessages.map((message) => ({ speaker: message.authorRole, text: message.text }))} />
            {example.expectedReplies.map((reply, replyIndex) => <p className="good-example" key={replyIndex}>NIA · {reply}</p>)}
            <p className="reason-copy">{example.reason}</p>
          </div>
        </details>
      ))}
    </div>
  );
}

function BuiltInSpeechExamples({ examples }: { examples: NonNullable<NiaEffectiveFewShot>["builtInSpeechExamples"] }) {
  return (
    <div className="built-in-list">
      {examples.map((example, index) => (
        <details key={`${example.title}-${index}`}>
          <summary>
            <span>{index + 1}</span>
            <strong>{example.title}</strong>
            <small>관리자 세트가 없을 때 사용</small>
          </summary>
          <div className="built-in-detail">
            <Conversation messages={example.messages.map((message) => {
              const separator = message.indexOf(":");
              return separator < 0
                ? { speaker: "scene", text: message }
                : { speaker: message.slice(0, separator), text: message.slice(separator + 1).trim() };
            })} />
            {example.goodReplies.map((reply, replyIndex) => <p className="good-example" key={`good-${replyIndex}`}>좋은 답변 · {reply}</p>)}
            {example.badReplies.map((reply, replyIndex) => <p className="bad-example" key={`bad-${replyIndex}`}>피할 답변 · {reply}</p>)}
          </div>
        </details>
      ))}
    </div>
  );
}

function App() {
  const [activeView, setActiveView] = useState<AdminView>(() => viewFromHash(window.location.hash));
  const [sets, setSets] = useState<NiaFewShotSet[]>([]);
  const [effectiveFewShot, setEffectiveFewShot] = useState<NiaEffectiveFewShot | null>(null);
  const [promptConfig, setPromptConfig] = useState<NiaPromptConfiguration | null>(null);
  const [selectedPromptKey, setSelectedPromptKey] = useState("");
  const [selectedSetId, setSelectedSetId] = useState("");
  const [examples, setExamples] = useState<NiaFewShotExample[]>([newConversation()]);
  const [datasetMarkdown, setDatasetMarkdown] = useState(() => serializeConversationDataset([newConversation()]));
  const [ragLibrary, setRagLibrary] = useState<ConversationRagStats | null>(null);
  const [ragEntries, setRagEntries] = useState<ConversationRagEntrySummary[]>([]);
  const [selectedRagEntryId, setSelectedRagEntryId] = useState("");
  const [ragEditorBases, setRagEditorBases] = useState<NiaFewShotExample[]>([newConversation()]);
  const [ragMarkdown, setRagMarkdown] = useState(() => serializeConversationDataset([newConversation()]));
  const [ragListQuery, setRagListQuery] = useState("");
  const [ragBulkMode, setRagBulkMode] = useState(false);
  const [ragSearchScene, setRagSearchScene] = useState("");
  const [ragMatches, setRagMatches] = useState<ConversationRagMatch[]>([]);
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
  const datasetSummary = useMemo(() => summarizeConversationDataset(datasetMarkdown), [datasetMarkdown]);
  const ragSummary = useMemo(() => summarizeConversationDataset(ragMarkdown), [ragMarkdown]);
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
    if (activeView === "PROMPTS") void refreshPromptData();
    else if (activeView === "GLOBAL_FEWSHOT") void refreshFewShotData();
    else if (activeView === "CONVERSATION_RAG") void refreshRagData();
    else void refreshExecutionScope();
  }, [activeView]);

  useEffect(() => {
    const source = draft?.examples ?? active?.examples;
    const defaults = effectiveFewShot?.editableDefaultExamples;
    const nextExamples = source?.length ? cloneExamples(source) : defaults?.length ? cloneExamples(defaults) : [newConversation()];
    setExamples(nextExamples);
    setDatasetMarkdown(serializeConversationDataset(nextExamples));
  }, [selectedSet?.id, draft?.version, draft?.updatedAt, active?.version, active?.updatedAt, effectiveFewShot]);

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

  async function refreshFewShotData() {
    await run(async () => {
      const [nextSets, effective] = await Promise.all([loadFewShotSets(), loadEffectiveFewShot()]);
      setSets(nextSets);
      setEffectiveFewShot(effective);
      setSelectedSetId((current) => current || String(nextSets[0]?.id ?? ""));
    });
  }

  async function refreshPromptData() {
    await run(async () => {
      const next = await loadNiaPromptConfiguration();
      setPromptConfig(next);
      setSelectedPromptKey((current) => current || next.documents[0]?.key || "");
    });
  }

  function updatePromptDraft(key: string, content: string) {
    setPromptConfig((current) => current && ({
      ...current,
      dirty: true,
      documents: current.documents.map((document) => document.key === key ? { ...document, draftContent: content } : document),
    }));
  }

  async function savePromptDraft() {
    await run(async () => {
      if (!promptConfig) return;
      const documents = Object.fromEntries(promptConfig.documents.map((document) => [document.key, document.draftContent]));
      setPromptConfig(await saveNiaPromptConfiguration(documents));
    });
  }

  async function applyPromptDraft() {
    await run(async () => setPromptConfig(await applyNiaPromptConfiguration()));
  }

  async function resetPromptDraft() {
    await run(async () => setPromptConfig(await resetNiaPromptDraft()));
  }

  async function loadRagState(preferredEntryId?: string) {
    const [library, page] = await Promise.all([loadConversationRagStats(), loadConversationRagEntries(ragListQuery)]);
    setRagLibrary(library);
    setRagEntries(page.entries);
    const nextId = preferredEntryId && page.entries.some((entry) => String(entry.id) === preferredEntryId)
      ? preferredEntryId
      : String(page.entries[0]?.id ?? "");
    if (!nextId) {
      startNewRagEntry();
      return;
    }
    const entry = await loadConversationRagEntry(Number(nextId));
    setSelectedRagEntryId(nextId);
    setRagBulkMode(false);
    setRagEditorBases([entry.example]);
    setRagMarkdown(serializeConversationDataset([entry.example]));
  }

  async function refreshRagData(preferredEntryId?: string) {
    await run(async () => {
      await loadRagState(preferredEntryId);
      setRagMatches([]);
    });
  }

  function startNewRagEntry() {
    const example = newConversation();
    setSelectedRagEntryId("");
    setRagBulkMode(false);
    setRagEditorBases([example]);
    setRagMarkdown(serializeConversationDataset([example]));
  }

  function startRagBulkImport() {
    setSelectedRagEntryId("");
    setRagBulkMode(true);
    setRagEditorBases([]);
    setRagMarkdown("");
  }

  async function selectRagEntry(entryId: number) {
    await run(async () => {
      const entry = await loadConversationRagEntry(entryId);
      setSelectedRagEntryId(String(entryId));
      setRagBulkMode(false);
      setRagEditorBases([entry.example]);
      setRagMarkdown(serializeConversationDataset([entry.example]));
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

  async function saveDraft(): Promise<NiaFewShotVersion | null> {
    let saved: NiaFewShotVersion | null = null;
    await run(async () => {
      const normalized = normalizeExamples(parseExamples(datasetMarkdown, examples));
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

  async function saveRagEntry() {
    await run(async () => {
      const normalized = normalizeExamples(parseExamples(ragMarkdown, ragEditorBases));
      if (ragBulkMode) {
        await createConversationRagEntries(normalized);
        await loadRagState();
      } else {
        if (normalized.length !== 1) throw new Error("개별 편집에서는 대화 하나만 입력하세요.");
        const saved = selectedRagEntryId
          ? await updateConversationRagEntry(Number(selectedRagEntryId), normalized[0])
          : await createConversationRagEntry(normalized[0]);
        await loadRagState(String(saved.id ?? ""));
      }
      setRagMatches([]);
    });
  }

  async function removeRagEntry() {
    if (!selectedRagEntryId || !window.confirm("이 대화를 RAG 라이브러리에서 삭제할까요?")) return;
    await run(async () => {
      await deleteConversationRagEntry(Number(selectedRagEntryId));
      await loadRagState();
    });
  }

  async function filterRagEntries() {
    await run(async () => {
      const page = await loadConversationRagEntries(ragListQuery);
      setRagEntries(page.entries);
    });
  }

  async function testRagSearch() {
    await run(async () => {
      if (!ragSearchScene.trim()) throw new Error("검색할 현재 대화를 입력하세요.");
      setRagMatches(await searchConversationRag(ragSearchScene.trim()));
    });
  }

  function refreshCurrentView() {
    if (activeView === "PROMPTS") void refreshPromptData();
    else if (activeView === "GLOBAL_FEWSHOT") void refreshFewShotData();
    else if (activeView === "CONVERSATION_RAG") void refreshRagData();
    else void refreshExecutionScope();
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
          <a href="#prompts" className={activeView === "PROMPTS" ? "active" : ""}>
            <SlidersHorizontal size={18} /> 기본 프롬프트
          </a>
          <a href="#global-fewshot" className={activeView === "GLOBAL_FEWSHOT" ? "active" : ""}>
            <BookOpen size={18} /> 전역 Few-shot
          </a>
          <a href="#conversation-rag" className={activeView === "CONVERSATION_RAG" ? "active" : ""}>
            <Database size={18} /> 대화 RAG
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
            onClick={refreshCurrentView}
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

        {activeView === "PROMPTS" ? (
          <section className="prompt-config-workspace">
            <div className="data-toolbar">
              <div className="version-status">
                <span>적용 버전 v{promptConfig?.activeVersion ?? 0}</span>
                <span>{promptConfig?.source === "MANAGED" ? "관리자 설정 사용 중" : "배포 기본값 사용 중"}</span>
                <span>{promptConfig?.dirty ? "저장된 초안 있음" : "적용본과 동일"}</span>
              </div>
              <div className="toolbar-actions">
                <button className="secondary-action" onClick={() => void resetPromptDraft()} disabled={loading}>기본값 불러오기</button>
                <button className="secondary-action" onClick={() => void savePromptDraft()} disabled={loading}><Save size={16} /> 초안 저장</button>
                <button className="primary-action" onClick={() => void applyPromptDraft()} disabled={loading || !promptConfig?.dirty}><Check size={16} /> 니아에 적용</button>
              </div>
            </div>
            <article className="prompt-editor-layout">
              <aside className="prompt-document-list">
                {Array.from(new Set(promptConfig?.documents.map((document) => document.group) ?? [])).map((group) => (
                  <section key={group}>
                    <h2>{group}</h2>
                    {promptConfig?.documents.filter((document) => document.group === group).map((document) => (
                      <button key={document.key} className={selectedPromptKey === document.key ? "selected" : ""} onClick={() => setSelectedPromptKey(document.key)}>
                        <strong>{document.title}</strong>
                        <span>{document.activeContent === document.draftContent ? "적용본" : "수정됨"}</span>
                      </button>
                    ))}
                  </section>
                ))}
              </aside>
              {promptConfig?.documents.filter((document) => document.key === selectedPromptKey).map((document) => (
                <section className="prompt-document-editor" key={document.key}>
                  <div className="editor-heading">
                    <div><strong>{document.title}</strong><span>{document.description}</span></div>
                    <code>{document.key}</code>
                  </div>
                  {document.requiredPlaceholders.length ? <p className="placeholder-copy">유지할 변수 · {document.requiredPlaceholders.map((item) => `{{${item}}}`).join(" · ")}</p> : null}
                  <textarea value={document.draftContent} onChange={(event) => updatePromptDraft(document.key, event.target.value)} spellCheck={false} aria-label={document.title} />
                  <details className="prompt-details"><summary>현재 적용본 비교</summary><pre>{document.activeContent}</pre></details>
                </section>
              ))}
            </article>
          </section>
        ) : activeView === "GLOBAL_FEWSHOT" ? (
          <section className="data-workspace">
            <article className="effective-fewshot">
              <div className="effective-heading">
                <div>
                  <span>현재 판단 입력</span>
                  <strong>{effectiveFewShot?.judgeSource === "MANAGED_GLOBAL" ? "관리자 전역 Few-shot 사용 중" : "기본 판단 예시 사용 중"}</strong>
                </div>
                <div className="effective-counts">
                  <span>판단 기본 <strong>{effectiveFewShot?.builtInJudgeExamples.length ?? 0}</strong></span>
                  <span>발화 기본 <strong>{effectiveFewShot?.builtInSpeechExamples.length ?? 0}</strong></span>
                  <span>관리자 적용 <strong>{effectiveFewShot?.managedGlobalExamples.length ?? 0}</strong></span>
                </div>
              </div>
              <div className="built-in-columns">
                <section>
                  <div className="section-title">
                    <strong>기본 판단 예시</strong>
                    <span>{effectiveFewShot?.judgeSource === "BUILT_IN_FALLBACK" ? "현재 사용 중" : "관리자 세트가 없을 때 사용"}</span>
                  </div>
                  <BuiltInJudgeExamples examples={effectiveFewShot?.builtInJudgeExamples ?? []} />
                </section>
                <section>
                  <div className="section-title">
                    <strong>기본 발화 예시</strong>
                    <span>관리자 세트가 없을 때 사용</span>
                  </div>
                  <BuiltInSpeechExamples examples={effectiveFewShot?.builtInSpeechExamples ?? []} />
                </section>
              </div>
            </article>

            <div className="data-toolbar">
              <label>
                <span>전역 Few-shot</span>
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

            <article className="dataset-editor">
              <div className="editor-heading">
                <div>
                  <strong>Markdown 편집기</strong>
                  <span>매 실행에 포함 · {datasetSummary.exampleCount}개 예시 · {datasetSummary.messageCount}개 메시지</span>
                </div>
              </div>
              <MarkdownFormat />
              <textarea
                className="markdown-editor dataset-markdown-editor"
                value={datasetMarkdown}
                onChange={(event) => setDatasetMarkdown(event.target.value)}
                aria-label="대화 데이터 Markdown"
                spellCheck={false}
              />
            </article>
          </section>
        ) : activeView === "CONVERSATION_RAG" ? (
          <section className="data-workspace">
            <div className="data-toolbar rag-toolbar">
              <div className="rag-library-status">
                <span><strong>{ragLibrary?.totalCount ?? 0}</strong>개 대화</span>
                <span><strong>{ragLibrary?.indexedCount ?? 0}</strong>개 색인</span>
                <span><strong>{ragLibrary?.embeddingModel ?? "-"}</strong> 모델</span>
              </div>
              <div className="toolbar-actions">
                <button className="secondary-action" onClick={startRagBulkImport} disabled={loading}>
                  <Upload size={16} /> 여러 대화 추가
                </button>
                <button className="primary-action" onClick={startNewRagEntry} disabled={loading}>
                  <Plus size={16} /> 새 대화
                </button>
              </div>
            </div>

            <article className="rag-library-workspace">
              <aside className="rag-entry-list">
                <div className="rag-list-search">
                  <input
                    value={ragListQuery}
                    onChange={(event) => setRagListQuery(event.target.value)}
                    onKeyDown={(event) => { if (event.key === "Enter") void filterRagEntries(); }}
                    placeholder="제목이나 대화 검색"
                    aria-label="RAG 라이브러리 검색"
                  />
                  <button onClick={() => void filterRagEntries()} aria-label="목록 검색"><Search size={16} /></button>
                </div>
                <div className="rag-list-count">대화 {ragEntries.length}개</div>
                <div className="rag-list-scroll">
                  {ragEntries.map((entry) => (
                    <button
                      className={`rag-entry-row ${selectedRagEntryId === String(entry.id) ? "selected" : ""}`}
                      key={entry.id}
                      onClick={() => void selectRagEntry(entry.id)}
                    >
                      <strong>{entry.title}</strong>
                      <span>{entry.messageCount}개 메시지 · {entry.expectedAction}</span>
                      <small>{entry.indexed ? "색인됨" : "텍스트 검색"} · {formatTime(entry.updatedAt)}</small>
                    </button>
                  ))}
                  {ragEntries.length === 0 ? <p className="empty-copy">저장된 대화가 없습니다.</p> : null}
                </div>
              </aside>

              <section className="rag-entry-editor">
                <div className="editor-heading">
                  <div>
                    <strong>{ragBulkMode ? "여러 대화 추가" : selectedRagEntryId ? "대화 편집" : "새 대화"}</strong>
                    <span>
                      {ragBulkMode
                        ? `${ragSummary.exampleCount}개 대화를 기존 라이브러리에 추가`
                        : `${ragSummary.messageCount}개 메시지 · 저장하면 즉시 검색 색인 갱신`}
                    </span>
                  </div>
                  <div>
                    {selectedRagEntryId ? (
                      <button onClick={() => void removeRagEntry()} disabled={loading} aria-label="대화 삭제"><Trash2 size={16} /></button>
                    ) : null}
                    <button className="save-icon-button" onClick={() => void saveRagEntry()} disabled={loading} aria-label="대화 저장"><Save size={16} /></button>
                  </div>
                </div>
                <MarkdownFormat />
                <textarea
                  className="markdown-editor rag-entry-markdown"
                  value={ragMarkdown}
                  onChange={(event) => setRagMarkdown(event.target.value)}
                  aria-label="대화 RAG Markdown"
                  placeholder={ragBulkMode ? "대화 여러 개를 --- 로 나눠 붙여넣기" : "대화 하나 입력"}
                  spellCheck={false}
                />
              </section>
            </article>

            <article className="rag-search-panel">
              <div className="editor-heading">
                <div>
                  <strong>검색 결과 확인</strong>
                  <span>현재 대화를 넣으면 실제 런타임과 같은 방식으로 상위 2개를 찾습니다.</span>
                </div>
              </div>
              <div className="rag-search-form">
                <textarea
                  value={ragSearchScene}
                  onChange={(event) => setRagSearchScene(event.target.value)}
                  placeholder="현재 Discord 대화를 그대로 붙여넣기"
                  aria-label="RAG 검색 테스트 대화"
                />
                <button className="secondary-action" onClick={() => void testRagSearch()} disabled={loading}>
                  <Search size={16} /> 상위 2개 찾기
                </button>
              </div>
              <div className="rag-results">
                {ragMatches.map((match, index) => (
                  <div className="retrieved-card" key={String(match.id ?? index)}>
                    <div className="match-heading">
                      <strong>{index + 1}. {match.example.title}</strong>
                      <span>{match.score.toFixed(3)} · {match.scoringMethod === "EMBEDDING" ? "의미 검색" : "텍스트 검색"}</span>
                    </div>
                    <Conversation messages={match.example.rawMessages.map((message) => ({ speaker: message.authorRole, text: message.text }))} />
                  </div>
                ))}
                {ragMatches.length === 0 ? <p className="empty-copy">검색 전입니다.</p> : null}
              </div>
            </article>
          </section>
        ) : (
          <section className="execution-workspace">
            <div className="execution-toolbar">
              <label>
                <span>Discord 서버</span>
                <select value={guildId} onChange={(event) => void refreshExecutionScope(event.target.value)}>
                  {(dashboard?.guilds ?? []).map((guild) => (
                    <option key={guild.id} value={guild.id}>{guild.name}</option>
                  ))}
                </select>
              </label>
              <label>
                <span>채널</span>
                <select value={channelId} onChange={(event) => void selectChannel(event.target.value)}>
                  <option value="">채널 선택</option>
                  {channels.map((channel) => (
                    <option key={channel.id} value={channel.id}>#{channel.name}</option>
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
                      <div className="detail-heading">
                        <span>2</span><h2>전역 Few-shot</h2><a href="#global-fewshot">수정</a>
                      </div>
                      <div className="input-summary">
                        <strong>{selectedExecution.inputSnapshot?.globalFewShotExampleCount ?? 0}개 예시</strong>
                        <span>세트 {selectedExecution.inputSnapshot?.globalFewShotSetId ?? "-"}</span>
                        <span>버전 {selectedExecution.inputSnapshot?.globalFewShotVersion ?? "-"}</span>
                      </div>
                    </section>
                    <section>
                      <div className="detail-heading">
                        <span>3</span><h2>검색된 대화 RAG</h2><a href="#conversation-rag">수정</a>
                      </div>
                      {selectedExecution.retrievedConversations.length === 0 ? (
                        <p className="empty-copy">이 실행에서 검색된 대화가 없습니다.</p>
                      ) : (
                        <div className="retrieved-grid">
                          {selectedExecution.retrievedConversations.slice(0, 2).map((conversation) => (
                            <div className="retrieved-card" key={conversation.id}>
                              <div className="match-heading">
                                <strong>{conversation.title}</strong>
                                <span>{conversation.score.toFixed(3)} · {conversation.scoringMethod === "EMBEDDING" ? "의미 검색" : "텍스트 검색"}</span>
                              </div>
                              <Conversation messages={conversation.messages} />
                              <p className="match-action">정답 행동 · {conversation.expectedAction}</p>
                            </div>
                          ))}
                        </div>
                      )}
                    </section>
                    <section>
                      <div className="detail-heading"><span>4</span><h2>모델에 들어간 실제 입력</h2></div>
                      {selectedExecution.inputSnapshot?.judgePrompt ? (
                        <details className="prompt-details">
                          <summary>상황 판단 모델 입력</summary>
                          <pre>{selectedExecution.inputSnapshot.judgePrompt}</pre>
                        </details>
                      ) : null}
                      {selectedExecution.inputSnapshot?.speechSystemPrompt ? (
                        <details className="prompt-details">
                          <summary>발화 모델 시스템 입력</summary>
                          <pre>{selectedExecution.inputSnapshot.speechSystemPrompt}</pre>
                        </details>
                      ) : null}
                      {selectedExecution.inputSnapshot?.speechUserPrompt ? (
                        <details className="prompt-details">
                          <summary>발화 모델 사용자 입력</summary>
                          <pre>{selectedExecution.inputSnapshot.speechUserPrompt}</pre>
                        </details>
                      ) : null}
                      {!selectedExecution.inputSnapshot?.judgePrompt ? (
                        <p className="empty-copy">이 실행에는 입력 스냅샷이 없습니다.</p>
                      ) : null}
                    </section>
                    <section className="nia-output">
                      <div className="detail-heading"><span>5</span><h2>니아의 결과</h2></div>
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

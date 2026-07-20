export type ConversationMarkdownMessage = {
  authorRole: "member" | "nia";
  text: string;
};

export type ConversationAction = "SPEAK" | "REACT" | "WAIT" | "IGNORE" | "CANCEL_PENDING";

export type ConversationMarkdownDocument = {
  title: string;
  action: ConversationAction;
  currentState: string | null;
  messages: ConversationMarkdownMessage[];
  reason: string;
  badAction: ConversationAction;
  badReason: string;
  replies: string[];
  reactionCode: string | null;
  reevaluateAfterMs: number | null;
};

type ConversationMarkdownSource = {
  title: string;
  rawMessages: Array<{ authorRole: string; text: string }>;
  expectedAction?: string;
  currentState?: string | null;
  expectedReplies?: string[];
  expectedReactionCode?: string | null;
  expectedReevaluateAfterMs?: number | null;
  reason?: string;
  badAlternative?: { action: string; whyBad: string };
};

const ACTION_HEADING = "니아의 행동";
const STATE_HEADING = "현재 상태";
const CONVERSATION_HEADING = "대화";
const REASON_HEADING = "판단 이유";
const BAD_ACTION_HEADING = "피해야 할 행동";
const REPLY_HEADING = "니아가 이어서 할 말";
const REACTION_HEADING = "리액션";
const WAIT_HEADING = "다시 판단할 시간(ms)";
const PERSON_HEADING = "### 사람";
const NIA_HEADING = "### 니아";
const ANSWER_HEADING = /^### 답변(?:\s+\d+)?$/;
const SUBHEADING = /^###\s+/;

const ACTIONS = new Set<ConversationAction>(["SPEAK", "REACT", "WAIT", "IGNORE", "CANCEL_PENDING"]);
const REACTION_TO_CODE: Record<string, string> = {
  "👍": "thumbs_up",
  "🙂": "smile",
  "😂": "laugh",
  "👀": "eyes",
  "🤔": "thinking",
  "😑": "unamused",
  "❤": "heart",
  "❤️": "heart",
};
const CODE_TO_REACTION: Record<string, string> = {
  ack: "👍",
  thumbs_up: "👍",
  smile: "🙂",
  laugh: "😂",
  eyes: "👀",
  thinking: "🤔",
  unamused: "😑",
  heart: "❤️",
};

function trimmedBlock(lines: string[]): string {
  return lines.join("\n").trim();
}

function toEditorAction(action?: string): ConversationAction {
  const normalized = action?.trim().toUpperCase() || "SPEAK";
  if (normalized === "CANCEL") return "CANCEL_PENDING";
  if (!ACTIONS.has(normalized as ConversationAction)) {
    throw new Error(`행동은 SPEAK, REACT, WAIT, IGNORE, CANCEL_PENDING 중 하나여야 합니다: ${normalized}`);
  }
  return normalized as ConversationAction;
}

function parseSections(lines: string[]): Map<string, string[]> {
  const sections = new Map<string, string[]>();
  let heading: string | null = null;
  for (const line of lines) {
    const match = /^##\s+([^#].*)$/.exec(line.trim());
    if (match) {
      heading = match[1].trim();
      if (sections.has(heading)) throw new Error(`'## ${heading}' 제목은 한 번만 넣어주세요.`);
      sections.set(heading, []);
      continue;
    }
    if (heading) sections.get(heading)?.push(line);
  }
  return sections;
}

function requiredSection(sections: Map<string, string[]>, heading: string): string[] {
  const value = sections.get(heading);
  if (!value) throw new Error(`'## ${heading}' 제목을 넣어주세요.`);
  return value;
}

function parseMessages(lines: string[]): ConversationMarkdownMessage[] {
  const messages: ConversationMarkdownMessage[] = [];
  let currentRole: ConversationMarkdownMessage["authorRole"] | null = null;
  let currentLines: string[] = [];
  const flush = () => {
    if (!currentRole) return;
    const text = trimmedBlock(currentLines);
    if (text) messages.push({ authorRole: currentRole, text });
    currentLines = [];
  };

  for (const line of lines) {
    const marker = line.trim();
    if (marker === PERSON_HEADING || marker === NIA_HEADING) {
      flush();
      currentRole = marker === NIA_HEADING ? "nia" : "member";
    } else if (!currentRole && marker) {
      throw new Error("대화 내용 앞에 '### 사람' 또는 '### 니아'를 넣어주세요.");
    } else if (currentRole) {
      currentLines.push(line);
    }
  }
  flush();
  if (messages.length === 0) throw new Error("대화 내용을 한 줄 이상 입력해주세요.");
  return messages;
}

function parseReplies(lines: string[]): string[] {
  const replies: string[] = [];
  let current: string[] = [];
  const flush = () => {
    const reply = trimmedBlock(current);
    if (reply) replies.push(reply);
    current = [];
  };
  for (const line of lines) {
    if (ANSWER_HEADING.test(line.trim())) flush();
    else current.push(line);
  }
  flush();
  return replies;
}

function parseBadAlternative(lines: string[]): { action: ConversationAction; reason: string } {
  const meaningful = lines.map((line) => line.trim()).filter(Boolean);
  const actionHeading = meaningful.findIndex((line) => line === "### 행동");
  const reasonHeading = meaningful.findIndex((line) => line === "### 이유");
  const actionText = actionHeading >= 0 ? meaningful[actionHeading + 1] : meaningful[0];
  const reasonLines = reasonHeading >= 0 ? meaningful.slice(reasonHeading + 1) : meaningful.slice(1);
  if (!actionText) throw new Error("'피해야 할 행동'에 행동을 입력해주세요.");
  const reason = reasonLines.filter((line) => !SUBHEADING.test(line)).join("\n").trim();
  if (!reason) throw new Error("'피해야 할 행동'에 그 행동이 나쁜 이유도 입력해주세요.");
  return { action: toEditorAction(actionText), reason };
}

function serializeBadAlternative(action: ConversationAction, reason: string): string[] {
  return ["## 피해야 할 행동", "", "### 행동", action, "", "### 이유", reason.trim()];
}

export function serializeConversationMarkdown(source: ConversationMarkdownSource): string {
  const action = toEditorAction(source.expectedAction);
  const badAction = toEditorAction(source.badAlternative?.action ?? (action === "SPEAK" ? "WAIT" : "SPEAK"));
  const lines = [`# ${source.title.trim() || "제목 없는 대화"}`, "", `## ${ACTION_HEADING}`, action, ""];

  if (source.currentState?.trim()) lines.push(`## ${STATE_HEADING}`, source.currentState.trim(), "");
  lines.push(`## ${CONVERSATION_HEADING}`, "");
  source.rawMessages.forEach((message) => {
    lines.push(message.authorRole === "nia" ? NIA_HEADING : PERSON_HEADING, message.text, "");
  });
  lines.push(`## ${REASON_HEADING}`, source.reason?.trim() || "이 장면에서 가장 자연스러운 행동이다.", "");
  lines.push(...serializeBadAlternative(badAction, source.badAlternative?.whyBad || "현재 장면에는 맞지 않는 행동이다."));

  if (action === "SPEAK") {
    lines.push("", `## ${REPLY_HEADING}`, "");
    const replies = source.expectedReplies?.length ? source.expectedReplies : [""];
    replies.forEach((reply, index) => {
      lines.push(replies.length > 1 ? `### 답변 ${index + 1}` : "### 답변", reply);
      if (index < replies.length - 1) lines.push("");
    });
  } else if (action === "REACT") {
    const code = source.expectedReactionCode?.trim() || "thumbs_up";
    lines.push("", `## ${REACTION_HEADING}`, CODE_TO_REACTION[code] ?? code);
  } else if (action === "WAIT") {
    lines.push("", `## ${WAIT_HEADING}`, String(source.expectedReevaluateAfterMs ?? 1500));
  }
  return `${lines.join("\n").trimEnd()}\n`;
}

export function summarizeConversationMarkdown(markdown: string): { title: string; messageCount: number; action: string } {
  const lines = markdown.replace(/\r\n?/g, "\n").split("\n");
  const title = lines.find((line) => /^#\s+[^#]/.test(line.trim()))?.trim().replace(/^#\s+/, "").trim();
  const messageCount = lines.filter((line) => [PERSON_HEADING, NIA_HEADING].includes(line.trim())).length;
  let action = "-";
  try {
    const actionLines = parseSections(lines).get(ACTION_HEADING);
    const actionText = actionLines?.find((line) => line.trim())?.trim();
    if (actionText) action = actionText.toUpperCase();
  } catch {
    // Editing summaries stay available while the document is incomplete.
  }
  return { title: title || "제목 없는 대화", messageCount, action };
}

export function renameConversationMarkdown(markdown: string, title: string): string {
  const normalized = markdown.replace(/\r\n?/g, "\n");
  const nextTitle = `# ${title.trim() || "제목 없는 대화"}`;
  if (/^#\s+[^#].*$/m.test(normalized)) return normalized.replace(/^#\s+[^#].*$/m, nextTitle);
  return `${nextTitle}\n\n${normalized.trimStart()}`;
}

export function parseConversationMarkdown(markdown: string): ConversationMarkdownDocument {
  const lines = markdown.replace(/\r\n?/g, "\n").split("\n");
  const sections = parseSections(lines);
  const actionText = trimmedBlock(requiredSection(sections, ACTION_HEADING));
  const action = toEditorAction(actionText);
  const messages = parseMessages(requiredSection(sections, CONVERSATION_HEADING));
  const reason = trimmedBlock(requiredSection(sections, REASON_HEADING));
  if (!reason) throw new Error("'판단 이유'를 입력해주세요.");
  const bad = parseBadAlternative(requiredSection(sections, BAD_ACTION_HEADING));
  if (bad.action === action) throw new Error("'피해야 할 행동'은 니아의 행동과 달라야 합니다.");

  const replies = sections.has(REPLY_HEADING) ? parseReplies(requiredSection(sections, REPLY_HEADING)) : [];
  const reactionText = sections.has(REACTION_HEADING) ? trimmedBlock(requiredSection(sections, REACTION_HEADING)) : "";
  const reactionCode = reactionText ? (REACTION_TO_CODE[reactionText] ?? reactionText.toLowerCase()) : null;
  const waitText = sections.has(WAIT_HEADING) ? trimmedBlock(requiredSection(sections, WAIT_HEADING)) : "";
  const reevaluateAfterMs = waitText ? Number(waitText) : null;
  const currentState = sections.has(STATE_HEADING) ? trimmedBlock(requiredSection(sections, STATE_HEADING)) || null : null;

  if (action === "SPEAK" && replies.length === 0) throw new Error(`'## ${REPLY_HEADING}'에 답변을 입력해주세요.`);
  if (action !== "SPEAK" && replies.length > 0) throw new Error(`${action} 행동에는 '${REPLY_HEADING}'을 넣지 마세요.`);
  if (action === "REACT" && !reactionCode) throw new Error(`REACT 행동에는 '## ${REACTION_HEADING}'을 입력해주세요.`);
  if (reactionCode && !CODE_TO_REACTION[reactionCode]) {
    throw new Error("리액션은 👍, 🙂, 😂, 👀, 🤔, 😑, ❤️ 중 하나를 입력해주세요.");
  }
  if (action !== "REACT" && reactionCode) throw new Error(`${action} 행동에는 '${REACTION_HEADING}'을 넣지 마세요.`);
  if (action === "WAIT" && (!Number.isInteger(reevaluateAfterMs) || (reevaluateAfterMs ?? 0) <= 0)) {
    throw new Error(`WAIT 행동에는 '## ${WAIT_HEADING}'을 양의 정수로 입력해주세요.`);
  }
  if (action !== "WAIT" && reevaluateAfterMs !== null) throw new Error(`${action} 행동에는 '${WAIT_HEADING}'을 넣지 마세요.`);
  if (action === "CANCEL_PENDING" && !currentState) throw new Error("CANCEL_PENDING 행동에는 취소할 예약이 보이도록 '## 현재 상태'를 입력해주세요.");

  return {
    title: summarizeConversationMarkdown(markdown).title,
    action,
    currentState,
    messages,
    reason,
    badAction: bad.action,
    badReason: bad.reason,
    replies,
    reactionCode,
    reevaluateAfterMs,
  };
}

export function toStoredAction(action: ConversationAction): string {
  return action === "CANCEL_PENDING" ? "CANCEL" : action;
}

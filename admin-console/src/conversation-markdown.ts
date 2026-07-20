export type ConversationAction = "SPEAK" | "REACT" | "WAIT" | "IGNORE" | "CANCEL_PENDING";
export type ConversationDeliveryMode = "CHANNEL" | "REPLY";

export type ConversationMarkdownMessage = {
  authorRole: string;
  text: string;
};

export type ConversationMarkdownDocument = {
  title: string;
  action: ConversationAction;
  deliveryMode: ConversationDeliveryMode | null;
  currentState: string | null;
  messages: ConversationMarkdownMessage[];
  reason: string;
  badAction: ConversationAction;
  badDeliveryMode: ConversationDeliveryMode | null;
  badReason: string;
  replies: string[];
  reactionCode: string | null;
  reevaluateAfterMs: number | null;
};

type ConversationMarkdownSource = {
  title: string;
  rawMessages: Array<{ authorRole: string; text: string }>;
  expectedAction?: string;
  expectedDeliveryMode?: string | null;
  currentState?: string | null;
  expectedReplies?: string[];
  expectedReactionCode?: string | null;
  expectedReevaluateAfterMs?: number | null;
  reason?: string;
  badAlternative?: { action: string; deliveryMode?: string | null; whyBad: string };
};

const ACTIONS = new Set<ConversationAction>(["SPEAK", "REACT", "WAIT", "IGNORE", "CANCEL_PENDING"]);
const DELIVERY_MODES = new Set<ConversationDeliveryMode>(["CHANNEL", "REPLY"]);
const MESSAGE_PATTERN = /^-\s+([A-Z]|NIA):(?:\s?(.*))?$/;
const OUTPUT_PATTERN = /^-\s+=>\s+NIA(?:\s+\[([^\]]+)\])?(?::(?:\s?(.*))?)?$/;
const META_PATTERN = /^>\s*(상태|이유|피하기):\s*(.*)$/;
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

function toAction(value?: string): ConversationAction {
  const normalized = value?.trim().toUpperCase() || "SPEAK";
  const action = normalized === "CANCEL" ? "CANCEL_PENDING" : normalized;
  if (!ACTIONS.has(action as ConversationAction)) {
    throw new Error(`행동은 SPEAK, REACT, WAIT, IGNORE, CANCEL_PENDING 중 하나여야 합니다: ${normalized}`);
  }
  return action as ConversationAction;
}

function toDeliveryMode(value?: string | null): ConversationDeliveryMode | null {
  if (!value?.trim()) return null;
  const normalized = value.trim().toUpperCase();
  if (!DELIVERY_MODES.has(normalized as ConversationDeliveryMode)) {
    throw new Error(`전송 방식은 CHANNEL 또는 REPLY여야 합니다: ${normalized}`);
  }
  return normalized as ConversationDeliveryMode;
}

function parseActionDescriptor(descriptor: string): {
  action: ConversationAction;
  deliveryMode: ConversationDeliveryMode | null;
  reactionCode: string | null;
  reevaluateAfterMs: number | null;
} {
  const tokens = descriptor.trim().split(/\s+/).filter(Boolean);
  const action = toAction(tokens[0]);
  let deliveryMode: ConversationDeliveryMode | null = null;
  let reactionCode: string | null = null;
  let reevaluateAfterMs: number | null = null;

  if (action === "SPEAK") {
    deliveryMode = toDeliveryMode(tokens[1]);
    if (!deliveryMode) throw new Error("SPEAK에는 CHANNEL 또는 REPLY를 지정해주세요.");
    if (tokens.length > 2) throw new Error("SPEAK 행동 형식이 올바르지 않습니다.");
  } else if (action === "REACT") {
    const emoji = tokens.slice(1).join("");
    reactionCode = REACTION_TO_CODE[emoji] ?? null;
    if (!reactionCode) throw new Error("REACT에는 👍, 🙂, 😂, 👀, 🤔, 😑, ❤️ 중 하나를 넣어주세요.");
  } else if (action === "WAIT") {
    const wait = /^(\d+)ms$/i.exec(tokens[1] ?? "");
    if (!wait || Number(wait[1]) <= 0) throw new Error("WAIT에는 양수 시간(ms)을 넣어주세요. 예: WAIT 1800ms");
    reevaluateAfterMs = Number(wait[1]);
  } else if (tokens.length > 1) {
    throw new Error(`${action} 행동에는 추가 값을 넣지 마세요.`);
  }
  return { action, deliveryMode, reactionCode, reevaluateAfterMs };
}

function parseBadAlternative(value: string): {
  action: ConversationAction;
  deliveryMode: ConversationDeliveryMode | null;
  reason: string;
} {
  const parts = value.split(/\s+[—–-]\s+/, 2);
  if (parts.length !== 2 || !parts[1].trim()) throw new Error("'피하기'는 '행동 — 이유' 형식으로 입력해주세요.");
  const parsed = parseActionDescriptor(parts[0]);
  return { action: parsed.action, deliveryMode: parsed.deliveryMode, reason: parts[1].trim() };
}

function parseExample(markdown: string, exampleIndex: number): ConversationMarkdownDocument {
  const lines = markdown.replace(/\r\n?/g, "\n").split("\n");
  const titleLine = lines.find((line) => /^#\s+[^#]/.test(line.trim()));
  const title = titleLine?.trim().replace(/^#\s+/, "").trim();
  if (!title) throw new Error(`${exampleIndex + 1}번째 예시에 '# 제목'을 넣어주세요.`);

  const messages: ConversationMarkdownMessage[] = [];
  const replies: string[] = [];
  let actionDescriptor: ReturnType<typeof parseActionDescriptor> | null = null;
  let currentState: string | null = null;
  let reason = "";
  let bad: ReturnType<typeof parseBadAlternative> | null = null;
  let continuation: { kind: "message" | "reply"; index: number } | null = null;

  for (const rawLine of lines) {
    const line = rawLine.trimEnd();
    const message = MESSAGE_PATTERN.exec(line);
    if (message) {
      const role = message[1];
      messages.push({ authorRole: role === "NIA" ? "nia" : role.toLowerCase(), text: message[2]?.trim() ?? "" });
      continuation = { kind: "message", index: messages.length - 1 };
      continue;
    }

    const output = OUTPUT_PATTERN.exec(line);
    if (output) {
      const descriptor = output[1]?.trim();
      const text = output[2]?.trim() ?? "";
      if (descriptor) {
        if (actionDescriptor) throw new Error(`'${title}'에는 행동을 한 번만 지정해주세요.`);
        actionDescriptor = parseActionDescriptor(descriptor);
        if (actionDescriptor.action === "SPEAK") {
          if (!text) throw new Error(`'${title}'의 SPEAK 행동에는 실제 발화를 입력해주세요.`);
          replies.push(text);
          continuation = { kind: "reply", index: replies.length - 1 };
        } else {
          if (text) throw new Error(`'${title}'의 ${actionDescriptor.action} 행동 뒤에는 발화문을 넣지 마세요.`);
          continuation = null;
        }
      } else {
        if (actionDescriptor?.action !== "SPEAK") throw new Error(`'${title}'의 추가 NIA 발화 앞에는 SPEAK 행동이 필요합니다.`);
        if (!text) throw new Error(`'${title}'의 추가 NIA 발화가 비어 있습니다.`);
        replies.push(text);
        continuation = { kind: "reply", index: replies.length - 1 };
      }
      continue;
    }

    const meta = META_PATTERN.exec(line);
    if (meta) {
      continuation = null;
      if (meta[1] === "상태") currentState = meta[2].trim() || null;
      if (meta[1] === "이유") reason = meta[2].trim();
      if (meta[1] === "피하기") bad = parseBadAlternative(meta[2]);
      continue;
    }

    if (/^\s{2,}\S/.test(rawLine) && continuation) {
      const text = rawLine.trim();
      if (continuation.kind === "message") {
        messages[continuation.index].text = `${messages[continuation.index].text}\n${text}`.trim();
      } else {
        replies[continuation.index] = `${replies[continuation.index]}\n${text}`.trim();
      }
      continue;
    }
    if (line.trim() && !/^#\s+/.test(line.trim())) {
      throw new Error(`'${title}'에서 이해할 수 없는 줄입니다: ${line.trim()}`);
    }
  }

  if (messages.length === 0 || messages.some((message) => !message.text.trim())) {
    throw new Error(`'${title}'의 대화 메시지는 비어 있을 수 없습니다.`);
  }
  if (!actionDescriptor) throw new Error(`'${title}'에 '- => NIA [행동]'을 넣어주세요.`);
  if (!reason) throw new Error(`'${title}'에 '> 이유:'를 넣어주세요.`);
  if (!bad) throw new Error(`'${title}'에 '> 피하기:'를 넣어주세요.`);
  if (
    bad.action === actionDescriptor.action &&
    (bad.action !== "SPEAK" || bad.deliveryMode === actionDescriptor.deliveryMode)
  ) {
    throw new Error(`'${title}'의 피하기는 정답 행동과 전송 방식 조합이 달라야 합니다.`);
  }
  if (actionDescriptor.action === "CANCEL_PENDING" && !currentState) {
    throw new Error(`'${title}'의 CANCEL_PENDING에는 '> 상태:'를 넣어주세요.`);
  }

  return {
    title,
    action: actionDescriptor.action,
    deliveryMode: actionDescriptor.deliveryMode,
    currentState,
    messages,
    reason,
    badAction: bad.action,
    badDeliveryMode: bad.deliveryMode,
    badReason: bad.reason,
    replies,
    reactionCode: actionDescriptor.reactionCode,
    reevaluateAfterMs: actionDescriptor.reevaluateAfterMs,
  };
}

export function parseConversationDataset(markdown: string): ConversationMarkdownDocument[] {
  const examples = markdown.replace(/\r\n?/g, "\n").split(/^---\s*$/m).map((item) => item.trim()).filter(Boolean);
  if (examples.length === 0) throw new Error("대화 예시를 한 개 이상 입력해주세요.");
  return examples.map(parseExample);
}

function descriptorFor(source: ConversationMarkdownSource): string {
  const action = toAction(source.expectedAction);
  if (action === "SPEAK") return `SPEAK ${toDeliveryMode(source.expectedDeliveryMode) ?? "CHANNEL"}`;
  if (action === "REACT") return `REACT ${CODE_TO_REACTION[source.expectedReactionCode ?? "thumbs_up"] ?? "👍"}`;
  if (action === "WAIT") return `WAIT ${source.expectedReevaluateAfterMs ?? 1800}ms`;
  return action;
}

export function serializeConversationMarkdown(source: ConversationMarkdownSource): string {
  const lines = [`# ${source.title.trim() || "제목 없는 대화"}`, ""];
  if (source.currentState?.trim()) lines.push(`> 상태: ${source.currentState.trim()}`, "");
  source.rawMessages.forEach((message) => {
    const role = message.authorRole.toLowerCase() === "nia" ? "NIA" : message.authorRole.toUpperCase();
    const parts = message.text.split("\n");
    lines.push(`- ${role}: ${parts[0] ?? ""}`);
    parts.slice(1).forEach((part) => lines.push(`  ${part}`));
  });

  const action = toAction(source.expectedAction);
  if (action === "SPEAK") {
    const replies = source.expectedReplies?.length ? source.expectedReplies : [""];
    replies.forEach((reply, index) => {
      const parts = reply.split("\n");
      lines.push(index === 0 ? `- => NIA [${descriptorFor(source)}]: ${parts[0] ?? ""}` : `- => NIA: ${parts[0] ?? ""}`);
      parts.slice(1).forEach((part) => lines.push(`  ${part}`));
    });
  } else {
    lines.push(`- => NIA [${descriptorFor(source)}]`);
  }

  const badAction = toAction(source.badAlternative?.action ?? (action === "SPEAK" ? "WAIT" : "SPEAK"));
  const badMode = badAction === "SPEAK" ? ` ${toDeliveryMode(source.badAlternative?.deliveryMode) ?? "CHANNEL"}` : "";
  lines.push("", `> 이유: ${source.reason?.trim() || "이 장면에서 가장 자연스러운 행동이다"}`);
  lines.push(`> 피하기: ${badAction}${badMode} — ${source.badAlternative?.whyBad?.trim() || "현재 장면에는 맞지 않는다"}`);
  return `${lines.join("\n").trimEnd()}\n`;
}

export function serializeConversationDataset(sources: ConversationMarkdownSource[]): string {
  return sources.map((source) => serializeConversationMarkdown(source).trim()).join("\n\n---\n\n") + "\n";
}

export function summarizeConversationDataset(markdown: string): { exampleCount: number; messageCount: number } {
  try {
    const examples = parseConversationDataset(markdown);
    return { exampleCount: examples.length, messageCount: examples.reduce((sum, example) => sum + example.messages.length, 0) };
  } catch {
    const exampleCount = markdown.split(/^---\s*$/m).filter((item) => item.trim()).length;
    const messageCount = markdown.split("\n").filter((line) => MESSAGE_PATTERN.test(line)).length;
    return { exampleCount, messageCount };
  }
}

export function toStoredAction(action: ConversationAction): string {
  return action === "CANCEL_PENDING" ? "CANCEL" : action;
}

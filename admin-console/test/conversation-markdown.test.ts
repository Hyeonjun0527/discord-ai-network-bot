import assert from "node:assert/strict";
import test from "node:test";

import {
  parseConversationMarkdown,
  renameConversationMarkdown,
  serializeConversationMarkdown,
  summarizeConversationMarkdown,
  toStoredAction,
} from "../src/conversation-markdown.js";

const common = `## 대화
### 사람
니아야 머해

## 판단 이유
직접 말을 걸었으므로 짧게 답한다

## 피해야 할 행동
### 행동
IGNORE
### 이유
직접 부른 말을 무시하게 된다`;

test("SPEAK Markdown round-trips a full multi-turn conversation", () => {
  const markdown = serializeConversationMarkdown({
    title: "피곤한 새벽 대화",
    rawMessages: [
      { authorRole: "member", text: "니아야 ㅠ\n피곤하다" },
      { authorRole: "nia", text: "으\n많이 피곤해?" },
      { authorRole: "member", text: "엉..." },
    ],
    expectedAction: "SPEAK",
    currentState: "니아와 대화가 이어지는 중",
    reason: "상대가 피곤함을 털어놓고 있다",
    badAlternative: { action: "IGNORE", whyBad: "대화 중 갑자기 사라지게 된다" },
    expectedReplies: ["오늘은 걍 자자", "내일 얘기해"],
  });

  assert.deepEqual(parseConversationMarkdown(markdown), {
    title: "피곤한 새벽 대화",
    action: "SPEAK",
    currentState: "니아와 대화가 이어지는 중",
    messages: [
      { authorRole: "member", text: "니아야 ㅠ\n피곤하다" },
      { authorRole: "nia", text: "으\n많이 피곤해?" },
      { authorRole: "member", text: "엉..." },
    ],
    reason: "상대가 피곤함을 털어놓고 있다",
    badAction: "IGNORE",
    badReason: "대화 중 갑자기 사라지게 된다",
    replies: ["오늘은 걍 자자", "내일 얘기해"],
    reactionCode: null,
    reevaluateAfterMs: null,
  });
});

test("REACT accepts a supported emoji and converts it to a runtime code", () => {
  const parsed = parseConversationMarkdown(`# 눈으로 확인

## 니아의 행동
REACT

${common.replace("IGNORE", "SPEAK")}

## 리액션
👀
`);
  assert.equal(parsed.action, "REACT");
  assert.equal(parsed.reactionCode, "eyes");
});

test("WAIT requires and parses a positive reevaluation delay", () => {
  const parsed = parseConversationMarkdown(`# 메시지 버스트 대기

## 니아의 행동
WAIT

${common.replace("IGNORE", "SPEAK")}

## 다시 판단할 시간(ms)
1800
`);
  assert.equal(parsed.action, "WAIT");
  assert.equal(parsed.reevaluateAfterMs, 1800);
});

test("IGNORE has no output payload", () => {
  const parsed = parseConversationMarkdown(`# 사람끼리 대화

## 니아의 행동
IGNORE

${common.replace("### 행동\nIGNORE", "### 행동\nSPEAK")}
`);
  assert.equal(parsed.action, "IGNORE");
  assert.deepEqual(parsed.replies, []);
  assert.equal(parsed.reactionCode, null);
});

test("CANCEL_PENDING requires current pending state and maps to stored CANCEL", () => {
  const parsed = parseConversationMarkdown(`# 예약 취소

## 니아의 행동
CANCEL_PENDING

## 현재 상태
이전 WAIT 행동 wait_1이 예약되어 있지만 사용자가 답을 철회함

${common.replace("IGNORE", "WAIT")}
`);
  assert.equal(parsed.action, "CANCEL_PENDING");
  assert.equal(toStoredAction(parsed.action), "CANCEL");
});

test("summary and rename work while the document is still incomplete", () => {
  const markdown = "## 대화\n\n### 사람\n안녕";
  assert.deepEqual(summarizeConversationMarkdown(markdown), { title: "제목 없는 대화", messageCount: 1, action: "-" });
  assert.match(renameConversationMarkdown(markdown, "인사"), /^# 인사\n\n## 대화/);
});

test("parser rejects missing sections and action-specific payload mismatches", () => {
  assert.throws(() => parseConversationMarkdown(`# 제목\n\n## 대화\n### 사람\n안녕`), /니아의 행동/);
  assert.throws(
    () => parseConversationMarkdown(`# 제목\n\n## 니아의 행동\nSPEAK\n\n${common}`),
    /니아가 이어서 할 말/,
  );
  assert.throws(
    () => parseConversationMarkdown(`# 제목\n\n## 니아의 행동\nREACT\n\n${common.replace("IGNORE", "SPEAK")}`),
    /리액션/,
  );
  assert.throws(
    () => parseConversationMarkdown(`# 제목\n\n## 니아의 행동\nWAIT\n\n${common.replace("IGNORE", "SPEAK")}\n\n## 다시 판단할 시간\(ms\)\n0`),
    /양의 정수/,
  );
  assert.throws(
    () => parseConversationMarkdown(`# 제목\n\n## 니아의 행동\nCANCEL_PENDING\n\n${common.replace("IGNORE", "WAIT")}`),
    /현재 상태/,
  );
});

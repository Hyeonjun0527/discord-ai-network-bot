import assert from "node:assert/strict";
import test from "node:test";

import {
  parseConversationDataset,
  serializeConversationDataset,
  summarizeConversationDataset,
} from "../src/conversation-markdown.js";

const dataset = `# 기다리던 답변이 필요 없어짐

> 상태: 니아가 2초 뒤 A의 질문에 답하려고 기다리는 중

- A: 오늘 모임 몇 시야
- B: 여덟 시
- A: 아 확인
- => NIA [CANCEL_PENDING]

> 이유: B가 이미 답했고 A도 확인했으므로 예약된 발화를 취소한다
> 피하기: SPEAK CHANNEL — 끝난 질문에 뒤늦게 같은 답을 하게 된다

---

# 흐름 속에서 짧게 말함

- A: 아
- A: 개피곤하다
- B: 그럼 좀 자
- A: 니아는
  아직 안 잠?
- => NIA [SPEAK CHANNEL]: 난 아직
  근데 넌 진짜 자야 될 듯
- => NIA: 폰 내려놔

> 이유: 특정 메시지 인용 없이 현재 흐름에 참여하는 편이 자연스럽다
> 피하기: SPEAK REPLY — 답장 UI가 대화를 지나치게 딱딱하게 만든다

---

# 질문을 정확히 가리켜 답함

- A: 오늘 갈까
- B: 난 내일
- A: 니아는 어떻게 생각해
- => NIA [SPEAK REPLY]: 나도 내일이 나을 듯

> 이유: 니아에게 직접 받은 질문을 명확히 가리켜 답한다
> 피하기: IGNORE — 직접 받은 질문을 놓친다
`;

test("한 Markdown 문서에서 여러 대화 예시와 자유로운 화자 순서를 읽는다", () => {
  const parsed = parseConversationDataset(dataset);
  assert.equal(parsed.length, 3);
  assert.equal(parsed[0].action, "CANCEL_PENDING");
  assert.equal(parsed[0].currentState, "니아가 2초 뒤 A의 질문에 답하려고 기다리는 중");
  assert.deepEqual(parsed[1].messages.map((message) => message.authorRole), ["a", "a", "b", "a"]);
  assert.equal(parsed[1].messages[3].text, "니아는\n아직 안 잠?");
  assert.equal(parsed[1].deliveryMode, "CHANNEL");
  assert.deepEqual(parsed[1].replies, ["난 아직\n근데 넌 진짜 자야 될 듯", "폰 내려놔"]);
  assert.equal(parsed[1].badDeliveryMode, "REPLY");
  assert.equal(parsed[2].deliveryMode, "REPLY");
});

test("모든 행동 payload를 읽는다", () => {
  const parsed = parseConversationDataset(`# 리액션
- A: 굿
- => NIA [REACT 👍]
> 이유: 말 없이 반응하면 충분하다
> 피하기: SPEAK CHANNEL — 불필요하게 말을 보탠다
---
# 기다림
- A: 잠깐
- => NIA [WAIT 1800ms]
> 이유: A가 말을 이어갈 가능성이 있다
> 피하기: SPEAK CHANNEL — 말을 끊는다
---
# 침묵
- A: B야 자니
- => NIA [IGNORE]
> 이유: B에게 한 말이다
> 피하기: SPEAK REPLY — 남의 대화에 끼어든다`);
  assert.equal(parsed[0].reactionCode, "thumbs_up");
  assert.equal(parsed[1].reevaluateAfterMs, 1800);
  assert.equal(parsed[2].action, "IGNORE");
});

test("API 데이터를 새 문법으로 직렬화한 뒤 다시 읽는다", () => {
  const markdown = serializeConversationDataset([
    {
      title: "직접 질문",
      rawMessages: [{ authorRole: "a", text: "니아는 어때" }],
      expectedAction: "SPEAK",
      expectedDeliveryMode: "REPLY",
      expectedReplies: ["난 괜찮아"],
      reason: "직접 받은 질문이다",
      badAlternative: { action: "IGNORE", whyBad: "질문을 놓친다" },
    },
    {
      title: "같은 상황의 다른 장면",
      rawMessages: [{ authorRole: "b", text: "니아 생각은" }],
      expectedAction: "SPEAK",
      expectedDeliveryMode: "CHANNEL",
      expectedReplies: ["난 내일이 나아"],
      reason: "흐름에 그냥 참여한다",
      badAlternative: { action: "SPEAK", deliveryMode: "REPLY", whyBad: "굳이 인용할 필요가 없다" },
    },
  ]);
  const parsed = parseConversationDataset(markdown);
  assert.equal(parsed.length, 2);
  assert.equal(parsed[0].deliveryMode, "REPLY");
  assert.equal(parsed[1].badDeliveryMode, "REPLY");
  assert.deepEqual(summarizeConversationDataset(markdown), { exampleCount: 2, messageCount: 2 });
});

test("잘못된 화자, 누락된 SPEAK 방식, 동일한 피하기 조합을 거부한다", () => {
  assert.throws(
    () => parseConversationDataset(`# 이름 노출\n- HJ: 안녕\n- => NIA [IGNORE]\n> 이유: 테스트\n> 피하기: SPEAK CHANNEL — 테스트`),
    /이해할 수 없는 줄/,
  );
  assert.throws(
    () => parseConversationDataset(`# 방식 누락\n- A: 안녕\n- => NIA [SPEAK]: 응\n> 이유: 테스트\n> 피하기: IGNORE — 테스트`),
    /CHANNEL 또는 REPLY/,
  );
  assert.throws(
    () => parseConversationDataset(`# 동일\n- A: 안녕\n- => NIA [SPEAK CHANNEL]: 응\n> 이유: 테스트\n> 피하기: SPEAK CHANNEL — 테스트`),
    /조합이 달라야/,
  );
});

#!/usr/bin/env python3
"""유효 Speech-style JSONL 카드에서 private runtime candidate를 만든다.

원본 JSONL은 그대로 보존한다. 각 카드에는 `response_move`, `response_form`, `response_rhythm` 관찰
메타데이터와 `prompt_eligible`을 파생한다. 검색 후보가 된 카드는 provider에 원문 대화나 실제 답변을 보이지
않는다. 대신 닫힌 metadata에서 만든 비식별 style pattern만 Speech prompt에 붙는다. 원문 대화와 실제 답변은
암호화된 감사 저장소에만 남긴다.

7개 response enum은 후보 풀을 제한하는 유일한 public hard gate다. 관찰 metadata는 검색·선택 보조값이며,
prompt 비활성 카드는 삭제하지 않고 암호화된 감사용 카드로 남긴다. 출력은 집계만 한다.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


IMPORT_SCHEMA = "nia-human-speech-style-import-card.v1"
OUTPUT_CARD_SCHEMA = "nia-human-speech-style-import-card.v4"
OUTPUT_SCHEMA = "nia-human-speech-style-runtime-candidate-manifest.v8"
RESPONSE_MOVE_REVIEW_LEDGER_SCHEMA = "nia-human-speech-style-response-move-review-ledger.v1"
RESPONSE_MOVE_REVIEW_SCHEMA = "nia-human-speech-style-response-move-review.v1"
RESPONSE_MOVE_POLICY = "observed_response_metadata_with_fresh_review_overlay_v1"
RETRIEVAL_POLICY = "closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11"
PROMPT_SURFACE_POLICY = "closed_style_pattern_v1"
PROMPT_SURFACE_STYLE_PATTERN = "STYLE_PATTERN"
PROMPT_SURFACE_AUDIT_ONLY = "AUDIT_ONLY"
DEFAULT_SOURCE_COVERAGE_POLICY = Path(__file__).resolve().parents[1] / "central-server/src/main/resources/human-speech-style-source-coverage.json"
MAX_SCENE_TRAITS = 2
MAX_PROVIDER_STYLE_CUES = 1
INPUT_CARD_KEYS = {
    "schema",
    "example_id",
    "response_mode",
    "situation",
    "style_signals",
    "context_bubbles",
    "response_bubbles",
    "quality",
    "source_fingerprint",
    "consent_revision",
    "combined_chars",
    "response_surface_has_card_local_alias",
    "embedding_model",
}
MODES = (
    "REACTION",
    "ALIGNMENT",
    "PLAY",
    "FOLLOW_UP",
    "SPECULATION",
    "CARE",
    "COORDINATION",
)
REVIEW_VERDICTS = {"PASS", "REJECT"}
FRESH_VERIFIER_REVIEWER_TYPE = "FRESH_VERIFIER"
REVIEW_CRITERIA = {
    "actual_response_action",
    "response_move_fit",
    "prompt_surface_safety",
    "response_mode_fit",
}

# SPECULATION 안에서만 쓴다. 보수적인 종결형/오타형만 보완하고, 일반 수식어인 "같은"은 의도적으로 넣지 않는다.
SPECULATION_HEDGED_GUESS_PATTERN = re.compile(
    r"(?:"
    r"같아|같은데|같음|듯|아마|일까|겠|모르|아닐|인듯|probably|maybe|"
    r"가\s*봐|"
    r"(?:인가(?:요)?|인\s*가)(?=$|[\s.!?,…~ㅋㅎㅠㅜ])|"
    r"인가\s*보(?:다|네|지|임)?(?=$|[\s.!?,…~ㅋㅎㅠㅜ])|"
    r"려나(?=$|[\s.!?,…~ㅋㅎㅠㅜ])|"
    r"같(?:ㅇ|ㅇㆍ|은대|은디)(?=$|[\s.!?,…~ㅋㅎㅠㅜ])|"
    r"(?:것|거)\s*같(?:아|애|음|다|네|지|기도|은데|은대|은디)(?=$|[\s.!?,…~ㅋㅎㅠㅜ])"
    r")",
    re.IGNORECASE,
)

RESPONSE_MOVES_BY_MODE = {
    "REACTION": (
        ("REACTION_GOOD_NEWS", re.compile(r"(?:드디어|해냈|성공|합격|샀어|샀다|축하|좋은 소식|결과)", re.IGNORECASE)),
        ("REACTION_SURPRISE", re.compile(r"(?:헐|헉|와|뭐임|대박|ㅁㅊ|미친|뜻밖|예상 못)", re.IGNORECASE)),
        ("REACTION_FUNNY", re.compile(r"(?:웃기|웃겨|ㅋㅋ|ㅋ{2,}|재밌|개웃|웃음)", re.IGNORECASE)),
    ),
    "ALIGNMENT": (
        ("ALIGNMENT_LOW_ENERGY", re.compile(r"(?:기빨|피곤|졸리|졸령|집중 안|처진|기운 없|지치)", re.IGNORECASE)),
        ("ALIGNMENT_COMPLAINT", re.compile(r"(?:답답|싫|불편|너무 많|안 와|길게 느껴|별로|짜증|눅눅)", re.IGNORECASE)),
    ),
    "PLAY": (
        ("PLAY_COMPETITIVE_TEASE", re.compile(r"(?:이기|더 빠|대결|경쟁|못 이겨|졌다|승부)", re.IGNORECASE)),
        ("PLAY_FRIENDLY_TEASE", re.compile(r"(?:너답|왜 이렇게|착함|착하|또 너|니가|쟤 왜)", re.IGNORECASE)),
        ("PLAY_LIGHT_EXAGGERATION", re.compile(r"(?:너무 일찍|과장|큰일|죽겠|레전드|미쳤)", re.IGNORECASE)),
    ),
    "FOLLOW_UP": (
        ("FOLLOW_UP_STATUS", re.compile(r"(?:병원|아프|어디가|몸은|상태|감기|치료)", re.IGNORECASE)),
        ("FOLLOW_UP_CHANGE", re.compile(r"(?:바뀌|변경|일정|달라졌|취소|미뤄)", re.IGNORECASE)),
        ("FOLLOW_UP_CAUSE", re.compile(r"(?:왜|이유|어쩌다|그렇게 된)", re.IGNORECASE)),
        ("FOLLOW_UP_PROGRESS", re.compile(r"(?:결국|어떻게 됐|진행|결과|마무리|그 뒤|후에)", re.IGNORECASE)),
    ),
    "SPECULATION": (
        ("SPECULATION_CAUSE", re.compile(r"(?:왜|이유|어쩌다|때문|답.{0,12}없|안.{0,12}(?:오|되)|그럴까)", re.IGNORECASE)),
        ("SPECULATION_FUTURE", re.compile(r"(?:내일|나중|앞으로|오늘.{0,12}(?:올|갈|될)|올까|될까|려나)", re.IGNORECASE)),
        ("SPECULATION_PRESENT", re.compile(r"(?:지금|현재|자고\s*있|하고\s*있|있나|왔나)", re.IGNORECASE)),
    ),
    "CARE": (
        ("CARE_PHYSICAL", re.compile(r"(?:머리|감기|아프|병원|몸|약|치료|다쳤)", re.IGNORECASE)),
        ("CARE_FATIGUE", re.compile(r"(?:피곤|지치|지쳐|잠|기운|졸리|쉬어)", re.IGNORECASE)),
        ("CARE_EMOTIONAL", re.compile(r"(?:예민|속상|마음|기분|힘들|울|스트레스)", re.IGNORECASE)),
    ),
    "COORDINATION": (
        ("COORDINATION_ROLE", re.compile(r"(?:누가|누굴|누구|먼저|역할|순서|담당|맡)", re.IGNORECASE)),
        ("COORDINATION_CHOICE", re.compile(r"(?:뭐|어디|어느|뭐로|먹|골라|선택|할지)", re.IGNORECASE)),
        ("COORDINATION_TIME", re.compile(r"(?:몇\s*시|언제|주말|시간|이따|나중|끝나고|오전|오후|저녁)", re.IGNORECASE)),
        ("COORDINATION_ACTION", re.compile(r"(?:갈까|가자|하자|할까|ㄱㄱ|보자|볼까|만나자|만날까|먹자|먹을까|제안)", re.IGNORECASE)),
    ),
}

SCENE_TRAITS_BY_MODE = {
    "REACTION": (
        ("REACTION_GOOD_NEWS", re.compile(r"(?:드디어|해냈|성공|합격|샀어|샀다|축하|좋은\s*소식|반가운|잘됐)", re.IGNORECASE)),
        ("REACTION_SURPRISE_OR_FUNNY", re.compile(r"(?:헐|헉|와|뭐임|대박|ㅁㅊ|미친|뜻밖|예상\s*못|웃기|웃겨|ㅋ{2,}|재밌|개웃|웃음)", re.IGNORECASE)),
    ),
    "ALIGNMENT": (
        ("ALIGNMENT_COMPLAINT_OR_LOW_ENERGY", re.compile(r"(?:답답|싫|불편|불평|불만|너무\s*많|안\s*와|길게\s*느껴|별로|짜증|눅눅|기빨|피곤|졸리|졸령|집중\s*안|처진|기운\s*없|지치)", re.IGNORECASE)),
    ),
    "PLAY": (
        ("PLAY_BANTER", re.compile(r"(?:웃기|웃겨|ㅋ{2,}|ㅎ{2,}|어쩔|장난|놀리|딱대|승부|이기|졌다|너답|쟤\s*왜|대결|경쟁|못\s*이겨|레전드|과장)", re.IGNORECASE)),
    ),
    "FOLLOW_UP": (
        ("FOLLOW_UP_STATUS_OR_PROGRESS", re.compile(r"(?:병원|감기|치료|약|다쳤|아프|몸살|컨디션|상태|결국|어떻게\s*됐|진행|결과|마무리|그\s*뒤|후에)", re.IGNORECASE)),
        ("FOLLOW_UP_CHANGE", re.compile(r"(?:바뀌|변경|일정|달라졌|취소|미뤄)", re.IGNORECASE)),
        ("FOLLOW_UP_CAUSE", re.compile(r"(?:왜|이유|어쩌다|그렇게\s*된)", re.IGNORECASE)),
    ),
    "SPECULATION": (
        ("SPECULATION_CAUSE", re.compile(r"(?:왜|이유|어쩌다|때문|그럴까)", re.IGNORECASE)),
        ("SPECULATION_FUTURE", re.compile(r"(?:내일|나중|앞으로|오늘.{0,12}(?:올|갈|될)|올까|될까|려나)", re.IGNORECASE)),
        ("SPECULATION_PRESENT", re.compile(r"(?:지금|현재|자고\s*있|하고\s*있|있나|왔나)", re.IGNORECASE)),
    ),
    "CARE": (
        ("CARE_PHYSICAL_CONDITION", re.compile(r"(?:병원|감기|머리\s*아프|몸살|약|치료|다쳤|아프(?:다|네|냐|겠|구나))", re.IGNORECASE)),
        ("CARE_FATIGUE_OVERLOAD", re.compile(r"(?:피곤|지치|지쳐|잠|기운\s*없|졸리|쉬어)", re.IGNORECASE)),
        ("CARE_EMOTIONAL_DISTRESS", re.compile(r"(?:예민|속상|마음|기분|힘들|울|스트레스)", re.IGNORECASE)),
    ),
    "COORDINATION": (
        ("COORDINATION_CHOICE", re.compile(r"(?:(?:뭐|어디|어느|뭐로|골라|선택).{0,12}(?:갈|하|보|먹|만나)|(?:갈|하|보|먹|만나).{0,12}(?:뭐|어디|어느|뭐로|골라|선택))", re.IGNORECASE)),
        ("COORDINATION_TIME", re.compile(r"(?:몇\s*시|언제|주말|이따|끝나고|오전|오후|저녁)", re.IGNORECASE)),
        ("COORDINATION_ACTION_PROPOSAL", re.compile(r"(?:갈까|가자|하자|할까|ㄱㄱ|보자|볼까|만나자|만날까|먹자|먹을까|제안)", re.IGNORECASE)),
        ("COORDINATION_ROLE_OR_ORDER", re.compile(r"(?:누가|누굴|누구|먼저|역할|순서|담당|맡)", re.IGNORECASE)),
    ),
}

# 실제 답변 말풍선이 어떤 리듬을 보이는지만 적는다. 장면·사건·인물·주제를 새로 분류하지 않는다.
RESPONSE_FORM_BY_MODE = {
    "REACTION": (
        ("EXPRESSIVE", re.compile(r"(?:ㅋ{2,}|ㅎ{2,}|헐|헉|와|ㅁㅊ|미친|대박|ㄹㅇ|진짜|오+|wow)", re.IGNORECASE)),
    ),
    "ALIGNMENT": (
        ("ALIGN_AND_ADD", re.compile(r"(?:나도|ㄴㄷ|맞아|맞지|인정|ㅇㅈ|ㄹㅇ|그러게|그니까|ㄱㄴㄲ|동감|same)", re.IGNORECASE)),
    ),
    "PLAY": (
        ("PLAYFUL_RETURN", re.compile(r"(?:ㅋ|ㅎ|어쩔|미친|ㅁㅊ|너|니|쟤|ㄱ)", re.IGNORECASE)),
    ),
    "FOLLOW_UP": (
        ("QUESTION", re.compile(r"(?:\?|왜|뭐|어디|언제|어떻게|누구|몇|어느|가\?|냐\?|임\?|맞아\?)", re.IGNORECASE)),
    ),
    "SPECULATION": (
        ("HEDGED_GUESS", SPECULATION_HEDGED_GUESS_PATTERN),
    ),
    "CARE": (
        ("SUPPORTIVE", re.compile(r"(?:푹|쉬|아프|힘들|괜찮|고생|무리|잘자|조심|병원|약|낫|몸|피곤|지치)", re.IGNORECASE)),
    ),
    "COORDINATION": (
        ("PROPOSAL", re.compile(r"(?:갈까|가자|하자|ㄱㄱ|보자|만나|먹자|하는 게|하자고)", re.IGNORECASE)),
        ("QUESTION", re.compile(r"(?:\?|왜|뭐|어디|언제|어떻게|누구|몇|어느|가\?|냐\?|임\?|맞아\?)", re.IGNORECASE)),
    ),
}

# 외부 embedding에는 원문 답변 대신 이 닫힌 표지만 보낸다. 주제·사건·인물·원문 표현을 담지 않는다.
RESPONSE_RHYTHM_BY_MODE = {
    "REACTION": (
        ("SHORT_REACTION", re.compile(r"(?:헐|헉|와|오+|ㅁㅊ|미친|대박|진짜|ㄹㅇ)", re.IGNORECASE)),
        ("LAUGHTER", re.compile(r"(?:ㅋ{2,}|ㅎ{2,}|웃기|재밌)", re.IGNORECASE)),
        ("POSITIVE_ACKNOWLEDGMENT", re.compile(r"(?:축하|잘됐|해냈|좋겠다|멋지)", re.IGNORECASE)),
    ),
    "ALIGNMENT": (
        ("AGREE_AND_ADD", re.compile(r"(?:나도|ㄴㄷ|맞아|맞지|인정|ㅇㅈ|ㄹㅇ|그러게|그니까|ㄱㄴㄲ|동감|same)", re.IGNORECASE)),
        ("SHARED_FEELING", re.compile(r"(?:답답|싫|불편|피곤|졸리|지치|별로|짜증|힘들)", re.IGNORECASE)),
    ),
    "PLAY": (
        ("LAUGHTER", re.compile(r"(?:ㅋ{2,}|ㅎ{2,}|웃기|재밌)", re.IGNORECASE)),
        ("PLAYFUL_RETURN", re.compile(r"(?:어쩔|너|니|쟤|이기|졌다|승부|딱대|ㄱ)", re.IGNORECASE)),
        ("LIGHT_EXAGGERATION", re.compile(r"(?:죽겠|레전드|미쳤|큰일|개[가-힣]|존나|ㅈㄴ)", re.IGNORECASE)),
    ),
    "FOLLOW_UP": (
        ("DIRECT_QUESTION", re.compile(r"(?:\?|왜|뭐|어디|언제|어떻게|누구|몇|어느|냐\?|임\?|맞아\?)", re.IGNORECASE)),
    ),
    "SPECULATION": (
        ("HEDGED_GUESS", SPECULATION_HEDGED_GUESS_PATTERN),
    ),
    "CARE": (
        ("GENTLE_CARE", re.compile(r"(?:괜찮|아프|힘들|고생|걱정|몸|피곤|지치)", re.IGNORECASE)),
        ("SUPPORTIVE_NUDGE", re.compile(r"(?:푹|쉬|무리|잘자|조심|병원|약|낫)", re.IGNORECASE)),
    ),
    "COORDINATION": (
        ("ACTION_PROPOSAL", re.compile(r"(?:갈까|가자|하자|ㄱㄱ|보자|만나|먹자|하는 게|하자고)", re.IGNORECASE)),
        ("COORDINATION_CHECK", re.compile(r"(?:\?|왜|뭐|어디|언제|어떻게|누구|몇|어느|냐\?|임\?|맞아\?)", re.IGNORECASE)),
    ),
}

# provider cue는 response form/rhythm의 allowlist 조합만으로 만든다. 장면·상황·인물·원문 단어는 이 표에 없다.
PROVIDER_STYLE_CUE_RULES_BY_MODE = {
    "REACTION": (
        ("REACTION_WARM_ACK", set(), {"POSITIVE_ACKNOWLEDGMENT"}),
        ("REACTION_LAUGH_ALONG", set(), {"LAUGHTER"}),
        ("REACTION_IMMEDIATE", {"EXPRESSIVE"}, {"SHORT_REACTION"}),
    ),
    "ALIGNMENT": (
        ("ALIGNMENT_SHARED_FEELING", set(), {"SHARED_FEELING"}),
        ("ALIGNMENT_LOW_KEY_ACK", {"ALIGN_AND_ADD"}, {"AGREE_AND_ADD"}),
    ),
    "PLAY": (
        ("PLAY_LIGHT_EXAGGERATION", set(), {"LIGHT_EXAGGERATION"}),
        ("PLAY_COUNTERTEASE", {"PLAYFUL_RETURN"}, {"PLAYFUL_RETURN"}),
    ),
    "FOLLOW_UP": (
        ("FOLLOW_UP_DIRECT_CHECK", set(), {"DIRECT_QUESTION"}),
        ("FOLLOW_UP_SOFT_CHECK", {"QUESTION"}, set()),
    ),
    "SPECULATION": (
        ("SPECULATION_LIGHT_HEDGE", {"HEDGED_GUESS"}, {"HEDGED_GUESS"}),
    ),
    "CARE": (
        ("CARE_SOFT_NUDGE", set(), {"SUPPORTIVE_NUDGE"}),
        ("CARE_GENTLE_VALIDATE", {"SUPPORTIVE"}, {"GENTLE_CARE"}),
    ),
    "COORDINATION": (
        ("COORDINATION_CONFIRM", set(), {"COORDINATION_CHECK"}),
        ("COORDINATION_ASK_ONE", {"QUESTION"}, set()),
        ("COORDINATION_PROPOSE", {"PROPOSAL"}, {"ACTION_PROPOSAL"}),
    ),
}

# 답변의 주제·사건을 새로 분류하지 않고, 실제 말풍선에서 확인되는 전달 호흡만 닫힌 enum으로 남긴다. 이 표지는
# provider에 원문을 보내지 않으면서도 같은 response mode 안의 카드가 모두 같은 안내문으로 붕괴하지 않게 한다.
TRAILING_PAUSE_PATTERN = re.compile(r"(?:\.{2,}|…|~+)\s*$")
CASUAL_SHORT_FORM_PATTERN = re.compile(
    r"(?:^|\s)(?:ㅇㅇ|응|어|웅|ㄴㄷ|ㅇㅈ|ㄹㅇ|ㅁㅊ|ㅇㅋ|ㄱㄱ|ㄱㄴㄲ|ㅇㄴ|ㅈㄴ|ㅅㅂ|ㅂㅅ)(?=$|\s|[.!?~…])",
    re.IGNORECASE,
)
SOFT_EMOTION_MARKER_PATTERN = re.compile(r"(?:ㅠ|ㅜ)+")
DELIVERY_ONLY_RHYTHM_CUES = {
    "TINY_REPLY",
    "SHORT_REPLY",
    "MEDIUM_REPLY",
    "LONGER_REPLY",
    "TRAILING_PAUSE",
    "CASUAL_SHORT_FORM",
    "SOFT_EMOTION_MARKER",
    "SINGLE_BUBBLE",
    "MULTI_BUBBLE",
}

# 원문은 provider에 보내지 않지만, marker·직접 식별자·고위험 내용이 남은 카드는 style pattern을 뽑는 근거로도
# 쓰지 않는다. 이 검사는 source card를 삭제하지 않고 prompt_eligible만 fail-closed로 만든다.
SOURCE_CONTENT_MARKER_PATTERN = re.compile(
    r"(?:\[[^\]\n]{1,80}\]|https?://|외부\s*링크|내용\s*없음|첨부(?:물)?|reacted|shared|스토리(?:를)?\s*공유|사진|영상|emoji)",
    re.IGNORECASE,
)
SOURCE_CONTENT_DIRECT_IDENTIFIER_PATTERN = re.compile(
    r"(?:\d[\d -]{6,}\d|\b\d{6,}\b|(?:주소|전화번호|주민등록|계좌|비밀번호|이메일|아이디)\s*[:：]?)",
    re.IGNORECASE,
)
SOURCE_CONTENT_HIGH_RISK_PATTERN = re.compile(
    r"(?:자살|죽어라|죽여|강간|근친|섹스|성관계)",
    re.IGNORECASE,
)
PROMPT_CONTEXTUAL_ACK_PATTERN = re.compile(
    r"^(?:"
    r"ㅇ|ㅇㅇ|응|어|아|웅|ㄴ|ㄴㄴ|ㅇㅋ|ok|ㄱㄱ|"
    r"나도|ㄴㄷ|맞아|그러게|인정|ㅇㅈ|ㄹㅇ|ㄱㄴㄲ|"
    r"모르겠(?:다)?|몰라|진짜|ㅋㅋ+|ㅋ+|ㅎㅎ+|ㅎ+|ㅠ+|ㅜ+"
    r")[.!?~…]*$",
    re.IGNORECASE,
)
PROMPT_RESPONSE_SELF_FOCUSED_PATTERN = re.compile(r"(?:^|\s)(?:나|난|내|나는|저|제가)(?:\s|$|도|는|가|를)")
PROMPT_RESPONSE_CARE_DIRECTED_PATTERN = re.compile(
    r"(?:괜찮|아프|힘들겠|고생|걱정|푹\s*쉬|무리\s*(?:하지|하지\s*마)|조심해|아프지\s*마|잘\s*자|병원\s*가|약\s*챙|낫길|힘내)",
    re.IGNORECASE,
)
PROMPT_RESPONSE_COORDINATION_ACTION_PATTERN = re.compile(
    r"(?:갈까|가자|하자|할까|보자|볼까|먹자|먹을까|만나자|만날까|같이\s*(?:갈|하|보|먹|만나))",
    re.IGNORECASE,
)
PROMPT_RESPONSE_COORDINATION_TIME_PATTERN = re.compile(
    r"(?:(?:언제|몇\s*시).{0,12}(?:갈|하|보|먹|만나)|(?:갈|하|보|먹|만나).{0,12}(?:언제|몇\s*시))",
    re.IGNORECASE,
)
PROMPT_RESPONSE_COORDINATION_CHOICE_PATTERN = re.compile(
    r"(?:(?:뭐|어디).{0,12}(?:갈|하|보|먹|만나)|(?:갈|하|보|먹|만나).{0,12}(?:뭐|어디))",
    re.IGNORECASE,
)
PROMPT_RESPONSE_COORDINATION_ROLE_PATTERN = re.compile(
    r"(?:누가.{0,12}(?:할|갈|먼저|맡)|(?:할|갈|먼저|맡).{0,12}누가)",
    re.IGNORECASE,
)
PROMPT_CONTEXT_PLAY_PATTERN = re.compile(
    r"(?:웃기|웃겨|ㅋ{2,}|ㅎ{2,}|어쩔|장난|놀리|딱대|승부|이기|졌다|너답|쟤 왜)",
    re.IGNORECASE,
)
def main() -> int:
    args = parse_args()
    input_path = args.input_jsonl.resolve()
    output_dir = args.output_dir.resolve()
    if not input_path.is_file():
        raise ValueError("private Speech-style input JSONL does not exist")
    records = read_records(input_path)
    source_coverage_policy = read_source_coverage_policy(args.source_coverage_policy)
    validate_source_coverage(records, source_coverage_policy)
    input_jsonl_sha256 = sha256_file(input_path)
    response_move_reviews = read_response_move_reviews(
        args.response_move_review_ledger,
        records,
        input_jsonl_sha256,
    )
    materialized: list[dict[str, Any]] = []
    eligible_by_mode: Counter[str] = Counter()
    scene_trait_counts: Counter[str] = Counter()
    provider_style_cue_counts: Counter[str] = Counter()
    response_move_metadata_counts: Counter[str] = Counter()
    response_move_provenance_counts: Counter[str] = Counter()
    prompt_eligible_response_move_counts: Counter[str] = Counter()
    prompt_eligible_response_move_sources: dict[str, set[str]] = defaultdict(set)
    response_form_metadata_counts: Counter[str] = Counter()
    response_rhythm_cue_counts: Counter[str] = Counter()
    response_rhythm_behavior_coverage = 0
    prompt_ineligible_reason_counts: Counter[str] = Counter()
    for record in records:
        candidate, ineligibility_reasons = materialize_record(
            record,
            response_move_reviews.get(record["example_id"]),
        )
        materialized.append(candidate)
        if candidate["prompt_eligible"]:
            eligible_by_mode[candidate["response_mode"]] += 1
            if candidate["response_move"] is not None:
                prompt_eligible_response_move_counts[candidate["response_move"]] += 1
                prompt_eligible_response_move_sources[candidate["response_move"]].add(candidate["source_fingerprint"])
        else:
            prompt_ineligible_reason_counts.update(ineligibility_reasons)
        scene_trait_counts.update(candidate["scene_traits"])
        provider_style_cue_counts.update(candidate["provider_style_cues"])
        if candidate["response_move"] is not None:
            response_move_metadata_counts[candidate["response_move"]] += 1
        response_move_provenance_counts[candidate["response_move_provenance"]] += 1
        if candidate["response_form"] is not None:
            response_form_metadata_counts[candidate["response_form"]] += 1
        response_rhythm_cue_counts.update(candidate["response_rhythm"])
        if has_observed_response_rhythm(candidate["response_rhythm"]):
            response_rhythm_behavior_coverage += 1

    output_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(output_dir, 0o700)
    jsonl_path = output_dir / "human-speech-style-cards.jsonl"
    manifest_path = output_dir / "manifest.json"
    write_jsonl(jsonl_path, materialized)
    write_json(
        manifest_path,
        {
            "schema": OUTPUT_SCHEMA,
            "input_jsonl_sha256": input_jsonl_sha256,
            "record_count": len(materialized),
            "jsonl_sha256": sha256_file(jsonl_path),
            "prompt_eligible_count": sum(record["prompt_eligible"] for record in materialized),
            "prompt_disabled_count": sum(not record["prompt_eligible"] for record in materialized),
            "prompt_eligible_by_response_mode": dict(sorted(eligible_by_mode.items())),
            "prompt_ineligible_reason_counts": dict(sorted(prompt_ineligible_reason_counts.items())),
            "scene_trait_counts": dict(sorted(scene_trait_counts.items())),
            "provider_style_cue_counts": dict(sorted(provider_style_cue_counts.items())),
            "response_move_metadata_counts": dict(sorted(response_move_metadata_counts.items())),
            "response_move_provenance_counts": dict(sorted(response_move_provenance_counts.items())),
            "prompt_eligible_response_move_counts": dict(sorted(prompt_eligible_response_move_counts.items())),
            "prompt_eligible_response_move_source_counts": {
                move: len(sources)
                for move, sources in sorted(prompt_eligible_response_move_sources.items())
            },
            "response_form_metadata_counts": dict(sorted(response_form_metadata_counts.items())),
            "response_rhythm_cue_counts": dict(sorted(response_rhythm_cue_counts.items())),
            "response_rhythm_coverage": len(materialized),
            "response_rhythm_behavior_coverage": response_rhythm_behavior_coverage,
            "response_rhythm_delivery_only_count": len(materialized) - response_rhythm_behavior_coverage,
            "response_move_policy": RESPONSE_MOVE_POLICY,
            "response_move_review_ledger_sha256": (
                sha256_file(args.response_move_review_ledger)
                if args.response_move_review_ledger is not None
                else None
            ),
            "fresh_verified_response_move_count": response_move_provenance_counts["FRESH_VERIFIED"],
            "heuristically_observed_response_move_count": response_move_provenance_counts["HEURISTIC_OBSERVED"],
            "rejected_response_move_review_count": response_move_provenance_counts["FRESH_REJECTED"],
            "retrieval_policy": RETRIEVAL_POLICY,
            "prompt_surface_policy": PROMPT_SURFACE_POLICY,
            "source_fingerprint_count": len({record["source_fingerprint"] for record in materialized}),
            "expected_source_count": source_coverage_policy["source_count"],
            "expected_source_fingerprint_set_sha256": source_coverage_policy["source_fingerprint_set_sha256"],
            "source_coverage_complete": True,
            "quality_counts": dict(sorted(Counter(record["quality"] for record in materialized).items())),
            "purpose": "private runtime candidate materialization; all source cards are retained encrypted for audit, and only closed de-identified style patterns derived from observed response metadata are searchable for Speech prompts",
        },
    )
    print(
        "human-speech-style runtime candidate materialized "
        f"records={len(materialized)} prompt_eligible={sum(record['prompt_eligible'] for record in materialized)} "
        f"prompt_disabled={sum(not record['prompt_eligible'] for record in materialized)} "
        f"modes={dict(sorted(eligible_by_mode.items()))} "
        f"retrieval_policy={RETRIEVAL_POLICY} "
        f"prompt_surface_policy={PROMPT_SURFACE_POLICY}",
    )
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-jsonl", type=Path, required=True, help="complete private runtime JSONL")
    parser.add_argument("--output-dir", type=Path, required=True, help="private runtime-candidate output directory")
    parser.add_argument(
        "--source-coverage-policy",
        type=Path,
        default=DEFAULT_SOURCE_COVERAGE_POLICY,
        help="trusted private corpus source-set coverage policy",
    )
    parser.add_argument(
        "--response-move-review-ledger",
        type=Path,
        help="private fresh-verifier response-move ledger; PASS replaces observed metadata and REJECT clears it",
    )
    return parser.parse_args()


def read_source_coverage_policy(path: Path) -> dict[str, Any]:
    try:
        with path.open(encoding="utf-8") as handle:
            policy = json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("human speech style source coverage policy could not be read") from error
    if set(policy) != {"schema", "source_count", "source_fingerprint_set_sha256"}:
        raise ValueError("human speech style source coverage policy fields are invalid")
    if policy.get("schema") != "nia-human-speech-style-source-coverage.v1":
        raise ValueError("human speech style source coverage policy schema is invalid")
    if not isinstance(policy.get("source_count"), int) or policy["source_count"] < 1:
        raise ValueError("human speech style source coverage policy source count is invalid")
    if not isinstance(policy.get("source_fingerprint_set_sha256"), str) or not re.fullmatch(
        r"[0-9a-f]{64}",
        policy["source_fingerprint_set_sha256"],
    ):
        raise ValueError("human speech style source coverage policy fingerprint digest is invalid")
    return policy


def validate_source_coverage(records: list[dict[str, Any]], policy: dict[str, Any]) -> None:
    fingerprints = {record["source_fingerprint"] for record in records}
    if len(fingerprints) != policy["source_count"]:
        raise ValueError("private Speech-style input does not match required source coverage count")
    digest = hashlib.sha256("\n".join(sorted(fingerprints)).encode()).hexdigest()
    if digest != policy["source_fingerprint_set_sha256"]:
        raise ValueError("private Speech-style input does not match required source coverage set")


def read_records(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    example_ids: set[str] = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"private Speech-style JSONL is invalid at line {line_number}") from error
        validate_record(record, line_number)
        example_id = record["example_id"]
        if example_id in example_ids:
            raise ValueError("private Speech-style JSONL has duplicate example ids")
        example_ids.add(example_id)
        records.append(record)
    if not records:
        raise ValueError("private Speech-style JSONL is empty")
    return records


def validate_record(record: Any, line_number: int) -> None:
    if not isinstance(record, dict) or set(record) != INPUT_CARD_KEYS:
        raise ValueError(f"private Speech-style card fields are invalid at line {line_number}")
    if record["schema"] != IMPORT_SCHEMA:
        raise ValueError(f"private Speech-style card schema is invalid at line {line_number}")
    if record["response_mode"] not in MODES:
        raise ValueError(f"private Speech-style card response mode is invalid at line {line_number}")
    if not isinstance(record["situation"], str) or not record["situation"].strip() or len(record["situation"]) > 240:
        raise ValueError(f"private Speech-style card situation is invalid at line {line_number}")
    for key in ("context_bubbles", "response_bubbles"):
        bubbles = record[key]
        if not isinstance(bubbles, list) or not bubbles:
            raise ValueError(f"private Speech-style card {key} is invalid at line {line_number}")
        if not all(
            isinstance(bubble, dict)
            and isinstance(bubble.get("speaker"), str)
            and bubble["speaker"].strip()
            and isinstance(bubble.get("text"), str)
            and bubble["text"].strip()
            for bubble in bubbles
        ):
            raise ValueError(f"private Speech-style card bubble is invalid at line {line_number}")
    alias_flag = record["response_surface_has_card_local_alias"]
    if not isinstance(alias_flag, bool):
        raise ValueError(f"private Speech-style card alias safety flag is invalid at line {line_number}")


def materialize_record(
    record: dict[str, Any],
    response_move_review: dict[str, Any] | None,
) -> tuple[dict[str, Any], list[str]]:
    candidate = {
        "schema": OUTPUT_CARD_SCHEMA,
        "example_id": record["example_id"],
        "response_mode": record["response_mode"],
        "situation": record["situation"],
        "style_signals": record["style_signals"],
        "context_bubbles": record["context_bubbles"],
        "response_bubbles": record["response_bubbles"],
        "quality": record["quality"],
        "source_fingerprint": record["source_fingerprint"],
        "consent_revision": record["consent_revision"],
        "combined_chars": record["combined_chars"],
        "response_surface_has_card_local_alias": record["response_surface_has_card_local_alias"],
        "embedding_model": record["embedding_model"],
    }
    candidate["scene_traits"] = classify_scene_traits(candidate)
    candidate["response_move"], candidate["response_move_provenance"] = resolve_response_move_metadata(
        classify_response_move(candidate),
        response_move_review,
    )
    candidate["response_form"] = classify_response_form(candidate)
    candidate["response_rhythm"] = classify_response_rhythm(candidate)
    candidate["provider_style_cues"] = classify_provider_style_cues(
        candidate["response_mode"],
        candidate["response_form"],
        candidate["response_rhythm"],
    )
    prompt_surface, ineligibility_reasons = determine_prompt_surface(candidate)
    candidate["prompt_surface"] = prompt_surface
    candidate["prompt_eligible"] = prompt_surface != PROMPT_SURFACE_AUDIT_ONLY
    return candidate, ineligibility_reasons


def read_response_move_reviews(
    path: Path | None,
    records: list[dict[str, Any]],
    expected_input_jsonl_sha256: str,
) -> dict[str, dict[str, Any]]:
    """Load only metadata-only fresh-verifier decisions bound to this exact source JSONL.

    A reviewed move says how the actual reply behaves; it is never inferred from a topic word.  Keeping this
    overlay separate from the source cards makes a rejected review durable without copying any dialogue into a
    review artifact.
    """
    if path is None:
        return {}
    if not path.is_file():
        raise ValueError("private response-move review ledger does not exist")
    try:
        ledger = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("private response-move review ledger is invalid") from error
    if not isinstance(ledger, dict):
        raise ValueError("private response-move review ledger is invalid")
    if ledger.get("schema") != RESPONSE_MOVE_REVIEW_LEDGER_SCHEMA:
        raise ValueError("private response-move review ledger schema is invalid")
    if ledger.get("input_jsonl_sha256") != expected_input_jsonl_sha256:
        raise ValueError("private response-move review ledger is not bound to this input")
    reviews = ledger.get("reviews")
    if not isinstance(reviews, list):
        raise ValueError("private response-move review ledger reviews are invalid")

    records_by_id = {record["example_id"]: record for record in records}
    reviewed: dict[str, dict[str, Any]] = {}
    for review in reviews:
        validate_response_move_review(review, records_by_id)
        example_id = review["example_id"]
        if example_id in reviewed:
            raise ValueError("private response-move review ledger has duplicate example ids")
        reviewed[example_id] = review
    return reviewed


def validate_response_move_review(
    review: Any,
    records_by_id: dict[str, dict[str, Any]],
) -> None:
    if not isinstance(review, dict):
        raise ValueError("private response-move review is invalid")
    required_keys = {
        "schema",
        "example_id",
        "source_fingerprint",
        "response_mode",
        "verdict",
        "response_move",
        "reviewer_type",
        "criteria",
    }
    if not required_keys.issubset(review):
        raise ValueError("private response-move review is incomplete")
    if review["schema"] != RESPONSE_MOVE_REVIEW_SCHEMA:
        raise ValueError("private response-move review schema is invalid")
    if review["reviewer_type"] != FRESH_VERIFIER_REVIEWER_TYPE:
        raise ValueError("private response-move review is not fresh-verifier evidence")
    example_id = review["example_id"]
    record = records_by_id.get(example_id)
    if record is None:
        raise ValueError("private response-move review refers to an unknown card")
    if review["source_fingerprint"] != record["source_fingerprint"]:
        raise ValueError("private response-move review source does not match its card")
    if review["response_mode"] != record["response_mode"]:
        raise ValueError("private response-move review mode does not match its card")
    verdict = review["verdict"]
    if verdict not in REVIEW_VERDICTS:
        raise ValueError("private response-move review verdict is invalid")
    criteria = review["criteria"]
    if not isinstance(criteria, dict) or set(criteria) != REVIEW_CRITERIA or any(value not in {"PASS", "FAIL"} for value in criteria.values()):
        raise ValueError("private response-move review criteria are invalid")
    response_move = review["response_move"]
    if verdict == "PASS":
        if not isinstance(response_move, str) or response_move not in response_move_names_for_mode(record["response_mode"]):
            raise ValueError("private response-move review move is invalid")
        if any(value != "PASS" for value in criteria.values()):
            raise ValueError("private response-move PASS review has failed criteria")
    elif response_move is not None:
        raise ValueError("private response-move REJECT review must not assign a move")


def response_move_names_for_mode(response_mode: str) -> set[str]:
    return {move for move, _ in RESPONSE_MOVES_BY_MODE[response_mode]}


def resolve_response_move_metadata(
    observed_move: str | None,
    response_move_review: dict[str, Any] | None,
) -> tuple[str | None, str]:
    if response_move_review is None:
        return observed_move, "HEURISTIC_OBSERVED" if observed_move is not None else "NONE"
    if response_move_review["verdict"] == "PASS":
        return response_move_review["response_move"], "FRESH_VERIFIED"
    return None, "FRESH_REJECTED"


def classify_scene_traits(record: dict[str, Any]) -> list[str]:
    scene_text = f"{bubble_text(record['context_bubbles'])} {record['situation']}".strip()
    traits = [
        trait
        for trait, pattern in SCENE_TRAITS_BY_MODE[record["response_mode"]]
        if pattern.search(scene_text)
    ]
    return traits[:MAX_SCENE_TRAITS]


def classify_response_move(record: dict[str, Any]) -> str | None:
    """실제 답변 행동을 우선해 내부 move를 관찰한다.

    response move는 현재 장면의 주제가 아니라 provider가 참고할 **사람의 실제 반응 행동**을 뜻한다. 다만
    조율에서 `ㅇㅋ`처럼 짧은 승낙은 앞 대화의 action/time/choice/role을 받아들이는 행동이므로 그때만 문맥으로
    보완한다. 이 순서가 없으면 "선택을 묻는 장면 → 전혀 다른 답변"도 choice card로 잘못 검색된다.
    """
    moves = RESPONSE_MOVES_BY_MODE[record["response_mode"]]
    response_text = bubble_text(record["response_bubbles"])
    direct_move = classify_text_move(moves, response_text)
    if direct_move is not None:
        return direct_move
    if (
        record["response_mode"] == "SPECULATION"
        and SPECULATION_HEDGED_GUESS_PATTERN.search(response_text)
    ):
        scene_text = f"{bubble_text(record['context_bubbles'])} {record['situation']}"
        return classify_text_move(moves, scene_text)
    if record["response_mode"] == "COORDINATION" and PROMPT_CONTEXTUAL_ACK_PATTERN.fullmatch(response_text.strip()):
        scene_text = f"{bubble_text(record['context_bubbles'])} {record['situation']}"
        return classify_text_move(moves, scene_text)
    return None


def classify_response_form(record: dict[str, Any]) -> str | None:
    """응답 자체의 질문·맞장구·제안 리듬만 보며, 앞 대화 문맥은 사용하지 않는다."""
    return classify_text_move(RESPONSE_FORM_BY_MODE[record["response_mode"]], bubble_text(record["response_bubbles"]))


def classify_response_rhythm(record: dict[str, Any]) -> list[str]:
    """실제 답변에서 확인한 리듬만 남기고, 확인하지 못한 반응을 enum으로 추정하지 않는다."""
    response_mode = record["response_mode"]
    response_bubbles = record["response_bubbles"]
    response_text = bubble_text(response_bubbles)
    cues = [
        cue
        for cue, pattern in RESPONSE_RHYTHM_BY_MODE[response_mode]
        if pattern.search(response_text)
    ]
    cues.extend(classify_response_delivery(response_bubbles, response_text))
    cues.append("SINGLE_BUBBLE" if len(response_bubbles) == 1 else "MULTI_BUBBLE")
    return list(dict.fromkeys(cues))


def classify_provider_style_cues(
    response_mode: str,
    response_form: str | None,
    response_rhythm: list[str],
) -> list[str]:
    """Build only closed provider-safe style cues from already observed response metadata."""
    return [
        cue
        for cue, compatible_forms, compatible_rhythm in PROVIDER_STYLE_CUE_RULES_BY_MODE[response_mode]
        if response_form in compatible_forms or compatible_rhythm.intersection(response_rhythm)
    ][:MAX_PROVIDER_STYLE_CUES]


def classify_response_delivery(
    response_bubbles: list[dict[str, Any]],
    response_text: str,
) -> list[str]:
    """말풍선 길이·말끝·축약·감정 기호만 관찰한다. 카드 주제나 인물을 label로 만들지 않는다."""
    compact_lengths = [len("".join(bubble["text"].split())) for bubble in response_bubbles]
    longest = max(compact_lengths)
    length_cue = (
        "TINY_REPLY"
        if longest <= 4
        else "SHORT_REPLY"
        if longest <= 12
        else "MEDIUM_REPLY"
        if longest <= 28
        else "LONGER_REPLY"
    )
    final_bubble = response_bubbles[-1]["text"].strip()
    cues = [length_cue]
    if TRAILING_PAUSE_PATTERN.search(final_bubble):
        cues.append("TRAILING_PAUSE")
    if CASUAL_SHORT_FORM_PATTERN.search(response_text):
        cues.append("CASUAL_SHORT_FORM")
    if SOFT_EMOTION_MARKER_PATTERN.search(response_text):
        cues.append("SOFT_EMOTION_MARKER")
    return cues


def has_observed_response_rhythm(cues: list[str]) -> bool:
    return any(cue not in DELIVERY_ONLY_RHYTHM_CUES for cue in cues)


def prompt_ineligibility_reasons(record: dict[str, Any]) -> list[str]:
    """원문 없이 만드는 closed style pattern의 근거가 충분한지 판단한다.

    provider가 받는 것은 fixed Korean guidance와 enum metadata뿐이다. 그래도 source card가 parser marker·직접
    식별자·고위험 내용을 포함하면 prompt 후보로 승격하지 않고 audit-only로 둔다. 이 함수는 카드 원문을 새로
    쓰거나 provider surface로 돌려주지 않는다.
    """
    context_text = bubble_text(record["context_bubbles"])
    response_text = bubble_text(record["response_bubbles"])
    source_text = f"{context_text} {response_text}".strip()
    reasons: list[str] = []

    if SOURCE_CONTENT_MARKER_PATTERN.search(source_text):
        reasons.append("MEDIA_OR_SYSTEM_MARKER")
    if SOURCE_CONTENT_DIRECT_IDENTIFIER_PATTERN.search(source_text):
        reasons.append("IDENTIFIER_OR_NUMBER")
    if SOURCE_CONTENT_HIGH_RISK_PATTERN.search(source_text):
        reasons.append("HIGH_RISK_CONTENT")
    if record["response_form"] is None:
        reasons.append("MISSING_REUSABLE_RESPONSE_FORM")
    if not has_observed_response_rhythm(record["response_rhythm"]):
        reasons.append("LOW_SIGNAL_RESPONSE_RHYTHM")
    if not record["provider_style_cues"]:
        reasons.append("MISSING_PROVIDER_STYLE_CUE")
    if not has_visible_enum_act(record, response_text, context_text):
        reasons.append("ENUM_ACT_NOT_VISIBLE")
    return reasons


def determine_prompt_surface(record: dict[str, Any]) -> tuple[str, list[str]]:
    """닫힌 style pattern만 provider 후보로 만들고, 나머지는 audit-only로 유지한다."""
    reasons = prompt_ineligibility_reasons(record)
    if not reasons:
        return PROMPT_SURFACE_STYLE_PATTERN, []
    return PROMPT_SURFACE_AUDIT_ONLY, [f"STYLE_PATTERN_{reason}" for reason in reasons]


def has_visible_enum_act(record: dict[str, Any], response_text: str, context_text: str) -> bool:
    """대화-응답 쌍에서 현재 enum의 재사용 가능한 반응 행동이 보이는지 확인한다."""
    mode = record["response_mode"]
    rhythm = set(record["response_rhythm"])

    if mode == "REACTION":
        return bool(rhythm & {"SHORT_REACTION", "POSITIVE_ACKNOWLEDGMENT"}) or "LAUGHTER" in rhythm
    if mode == "ALIGNMENT":
        return "AGREE_AND_ADD" in rhythm
    if mode == "PLAY":
        return "LAUGHTER" in rhythm and (
            bool(rhythm & {"PLAYFUL_RETURN", "LIGHT_EXAGGERATION"})
            or PROMPT_CONTEXT_PLAY_PATTERN.search(context_text) is not None
        )
    if mode == "FOLLOW_UP":
        return "DIRECT_QUESTION" in rhythm and ("?" in response_text or len(response_text) >= 6)
    if mode == "SPECULATION":
        return "HEDGED_GUESS" in rhythm
    if mode == "CARE":
        return (
            bool(rhythm & {"GENTLE_CARE", "SUPPORTIVE_NUDGE"})
            and not PROMPT_RESPONSE_SELF_FOCUSED_PATTERN.search(response_text)
            and bool(PROMPT_RESPONSE_CARE_DIRECTED_PATTERN.search(response_text))
        )
    if mode == "COORDINATION":
        direct_action = bool(
            PROMPT_RESPONSE_COORDINATION_ACTION_PATTERN.search(response_text)
            or PROMPT_RESPONSE_COORDINATION_TIME_PATTERN.search(response_text)
            or PROMPT_RESPONSE_COORDINATION_CHOICE_PATTERN.search(response_text)
            or PROMPT_RESPONSE_COORDINATION_ROLE_PATTERN.search(response_text)
        )
        contextual_ack = (
            PROMPT_CONTEXTUAL_ACK_PATTERN.fullmatch(response_text.strip()) is not None
            and classify_text_move(RESPONSE_MOVES_BY_MODE["COORDINATION"], context_text) is not None
        )
        return direct_action or contextual_ack
    raise ValueError(f"unsupported response mode: {mode}")


def classify_text_move(moves: tuple[tuple[str, re.Pattern[str]], ...], text: str) -> str | None:
    for move, pattern in moves:
        if pattern.search(text):
            return move
    return None


def bubble_text(bubbles: list[dict[str, Any]]) -> str:
    return " ".join(bubble["text"].strip() for bubble in bubbles)


def write_jsonl(path: Path, records: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")
    os.chmod(path, 0o600)


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.chmod(path, 0o600)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


if __name__ == "__main__":
    raise SystemExit(main())

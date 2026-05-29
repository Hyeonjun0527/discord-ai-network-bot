"""프롬프트 회귀 평가 하니스 (#23).

고정된 "골든 입력"을 각 프롬프트 빌더(build_summarize/ask/translate/chat/...)에
넣고, 생성된 프롬프트 문자열의 구조를 키워드/구조 단언으로 검증한다. 이는
LLM 호출 없이 프롬프트 자체의 회귀(섹션 누락, 지시문 변경, 구분자 래핑 누락,
언어 라벨 누락 등)를 잡기 위한 테스트다.

test_prompts.py 가 인젝션 방어(토큰 무력화) 위주인 반면, 여기서는 프롬프트의
"형태(shape)" — 필수 섹션 헤더, 행동 지시문, <tag> 래핑, 대상 언어 라벨 —
가 유지되는지에 집중한다.
"""
from __future__ import annotations

import unittest

from discord_assistant.prompts import (
    _INJECTION_GUARD,
    build_ask_prompt,
    build_chat_prompt,
    build_chat_with_history_prompt,
    build_image_analysis_prompt,
    build_search_result_prompt,
    build_summarize_prompt,
    build_translate_prompt,
    language_label,
)

# --- 고정 골든 입력 -----------------------------------------------------------
# 회귀 테스트용으로 의도적으로 평범한(인젝션 토큰 없는) 입력을 쓴다.
GOLDEN_TRANSCRIPT = "alice: 내일 3시에 배포하자\nbob: 좋아, 테스트는 내가 맡을게"
GOLDEN_QUESTION = "배포는 언제로 정해졌어?"
GOLDEN_MESSAGE = "오늘 날씨 어때?"
GOLDEN_TRANSLATE_TEXT = "Hello, how are you today?"
GOLDEN_SEARCH_QUERY = "배포 일정"


def _wrapped_section(prompt: str, tag: str) -> str:
    """프롬프트에서 ``<tag>\\n ... \\n</tag>`` 데이터 블록 본문만 추출한다.

    _INJECTION_GUARD 안내문에 예시로 들어간 태그 나열과 구분하기 위해 실제
    데이터 블록은 항상 ``<tag>\\n`` 으로 시작한다는 규약을 이용한다.
    """
    marker = f"<{tag}>\n"
    start = prompt.index(marker) + len(marker)
    end = prompt.index(f"</{tag}>", start)
    return prompt[start:end]


def _assert_wrapped(test: unittest.TestCase, prompt: str, tag: str) -> None:
    """프롬프트가 주어진 태그로 신뢰불가 데이터를 정확히 한 번 래핑하는지 단언한다."""
    test.assertIn(f"<{tag}>\n", prompt, f"<{tag}> 여는 구분자 누락")
    test.assertEqual(
        prompt.count(f"</{tag}>"), 1, f"</{tag}> 닫는 구분자는 정확히 1개여야 함"
    )


class LanguageLabelTest(unittest.TestCase):
    """language_label 의 코드→라벨 매핑 회귀 검증."""

    def test_known_codes_map_to_labels(self) -> None:
        cases = {
            "ko": "Korean",
            "en": "English",
            "ja": "Japanese",
            "zh": "Chinese",
            "fr": "French",
            "de": "German",
            "es": "Spanish",
        }
        for code, expected_substr in cases.items():
            with self.subTest(code=code):
                self.assertIn(expected_substr, language_label(code))

    def test_legacy_aliases(self) -> None:
        # kr → ko, jp → ja 의 레거시 별칭이 유지되어야 한다.
        self.assertIn("Korean", language_label("kr"))
        self.assertIn("Japanese", language_label("jp"))

    def test_case_insensitive_and_whitespace(self) -> None:
        self.assertIn("Korean", language_label("  KO  "))

    def test_unknown_code_falls_back_to_korean_default(self) -> None:
        # 빈 값/미지정은 한국어 기본 라벨로 폴백한다.
        self.assertIn("Korean", language_label(""))


class SummarizePromptStructureTest(unittest.TestCase):
    """build_summarize_prompt 의 필수 섹션·지시문·래핑·언어 라벨 회귀."""

    def setUp(self) -> None:
        self.prompt = build_summarize_prompt(GOLDEN_TRANSCRIPT, language="ko")

    def test_contains_required_sections(self) -> None:
        for section in (
            "Key Summary",
            "Decisions/Agreements",
            "Action Items",
            "Context for Latecomers",
        ):
            self.assertIn(section, self.prompt, f"필수 섹션 누락: {section}")

    def test_contains_anti_hallucination_rule(self) -> None:
        self.assertIn("Do not invent facts", self.prompt)

    def test_includes_target_language_label(self) -> None:
        self.assertIn("Korean", self.prompt)
        self.assertIn(language_label("ko"), self.prompt)

    def test_wraps_transcript_and_includes_content(self) -> None:
        _assert_wrapped(self, self.prompt, "transcript")
        self.assertIn(GOLDEN_TRANSCRIPT, _wrapped_section(self.prompt, "transcript"))

    def test_includes_injection_guard(self) -> None:
        self.assertIn(_INJECTION_GUARD, self.prompt)

    def test_language_label_propagates_to_header(self) -> None:
        # 언어를 바꾸면 헤더의 대상 언어 라벨도 따라 바뀐다(회귀 방지).
        en_prompt = build_summarize_prompt(GOLDEN_TRANSCRIPT, language="en")
        self.assertIn("Answer in English", en_prompt)
        ja_prompt = build_summarize_prompt(GOLDEN_TRANSCRIPT, language="ja")
        self.assertIn("Japanese", ja_prompt)


class AskPromptStructureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.prompt = build_ask_prompt(
            GOLDEN_TRANSCRIPT, GOLDEN_QUESTION, language="ko"
        )

    def test_wraps_question_and_transcript_separately(self) -> None:
        _assert_wrapped(self, self.prompt, "question")
        _assert_wrapped(self, self.prompt, "transcript")
        self.assertIn(GOLDEN_QUESTION, _wrapped_section(self.prompt, "question"))
        self.assertIn(
            GOLDEN_TRANSCRIPT, _wrapped_section(self.prompt, "transcript")
        )

    def test_contains_grounding_instruction(self) -> None:
        # 트랜스크립트에서 근거를 찾지 못하면 확인 불가라고 답하라는 지시.
        self.assertIn("cannot confirm", self.prompt)

    def test_contains_quote_format_instruction(self) -> None:
        self.assertIn("> [speaker]: quote", self.prompt)

    def test_includes_language_and_guard(self) -> None:
        self.assertIn("Korean", self.prompt)
        self.assertIn(_INJECTION_GUARD, self.prompt)

    def test_question_appears_before_transcript(self) -> None:
        # 질문 블록이 트랜스크립트 블록보다 앞에 위치하는 구조를 유지한다.
        self.assertLess(
            self.prompt.index("<question>\n"), self.prompt.index("<transcript>\n")
        )


class ChatPromptStructureTest(unittest.TestCase):
    def test_basic_chat_wraps_message_and_has_language(self) -> None:
        prompt = build_chat_prompt(GOLDEN_MESSAGE, language="ko")
        _assert_wrapped(self, prompt, "message")
        self.assertIn(GOLDEN_MESSAGE, _wrapped_section(prompt, "message"))
        self.assertIn("Korean", prompt)
        self.assertIn(_INJECTION_GUARD, prompt)
        # 기본(페르소나 없음)에는 Persona 라인이 없어야 한다.
        self.assertNotIn("Persona:", prompt)

    def test_persona_is_injected_when_provided(self) -> None:
        prompt = build_chat_prompt(
            GOLDEN_MESSAGE, language="ko", persona="너는 친절한 비서야"
        )
        self.assertIn("Persona: 너는 친절한 비서야", prompt)

    def test_blank_persona_is_ignored(self) -> None:
        prompt = build_chat_prompt(GOLDEN_MESSAGE, language="ko", persona="   ")
        self.assertNotIn("Persona:", prompt)

    def test_chat_with_history_includes_turns_and_message(self) -> None:
        history = [
            {"role": "user", "content": "안녕"},
            {"role": "assistant", "content": "안녕하세요! 무엇을 도와드릴까요?"},
        ]
        prompt = build_chat_with_history_prompt(
            GOLDEN_MESSAGE, history, language="ko"
        )
        self.assertIn("Previous conversation:", prompt)
        # 각 턴의 라벨(User/Assistant)과 내용이 포함된다.
        self.assertIn("User: 안녕", prompt)
        self.assertIn("Assistant: 안녕하세요! 무엇을 도와드릴까요?", prompt)
        _assert_wrapped(self, prompt, "message")
        self.assertIn(GOLDEN_MESSAGE, _wrapped_section(prompt, "message"))

    def test_chat_with_empty_history_omits_history_section(self) -> None:
        prompt = build_chat_with_history_prompt(GOLDEN_MESSAGE, [], language="ko")
        self.assertNotIn("Previous conversation:", prompt)
        _assert_wrapped(self, prompt, "message")


class TranslatePromptStructureTest(unittest.TestCase):
    def test_includes_target_language_and_preservation_rules(self) -> None:
        prompt = build_translate_prompt(
            GOLDEN_TRANSLATE_TEXT, target_language="ko"
        )
        _assert_wrapped(self, prompt, "text")
        self.assertIn(GOLDEN_TRANSLATE_TEXT, _wrapped_section(prompt, "text"))
        self.assertIn("Korean", prompt)
        # 멘션/URL/코드블록/고유명사 보존 지시가 유지되어야 한다.
        self.assertIn("Keep Discord mentions", prompt)
        self.assertIn("Return only the translated text", prompt)
        self.assertIn("untrusted DATA to translate", prompt)

    def test_source_language_hint_is_included_when_given(self) -> None:
        prompt = build_translate_prompt(
            GOLDEN_TRANSLATE_TEXT, target_language="ko", source_language="en"
        )
        # "from English" 형태의 소스 언어 힌트가 들어간다.
        self.assertIn("from English", prompt)
        self.assertIn("into Korean", prompt)

    def test_source_hint_absent_when_not_given(self) -> None:
        prompt = build_translate_prompt(
            GOLDEN_TRANSLATE_TEXT, target_language="ko"
        )
        self.assertNotIn(" from ", prompt)

    def test_translate_preserves_content_verbatim(self) -> None:
        # 번역 대상은 글자 그대로 보존(토큰 무력화 안 함)되어야 한다.
        text = "Mention <@42> and visit https://example.com please"
        prompt = build_translate_prompt(text, target_language="ko")
        self.assertIn(text, prompt)


class SearchResultPromptStructureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.prompt = build_search_result_prompt(
            GOLDEN_TRANSCRIPT, GOLDEN_SEARCH_QUERY, language="ko"
        )

    def test_wraps_query_and_transcript(self) -> None:
        _assert_wrapped(self, self.prompt, "question")
        _assert_wrapped(self, self.prompt, "transcript")
        self.assertIn(
            GOLDEN_SEARCH_QUERY, _wrapped_section(self.prompt, "question")
        )
        self.assertIn(
            GOLDEN_TRANSCRIPT, _wrapped_section(self.prompt, "transcript")
        )

    def test_includes_summarize_instruction_and_language(self) -> None:
        self.assertIn("Summarize the relevant information", self.prompt)
        self.assertIn("Korean", self.prompt)
        self.assertIn(_INJECTION_GUARD, self.prompt)


class ImageAnalysisPromptStructureTest(unittest.TestCase):
    def test_vision_path_no_url_has_describe_instruction(self) -> None:
        # 새 비전 경로: URL 인자 없이 텍스트 지시만 생성(URL 나열 없음).
        prompt = build_image_analysis_prompt(language="ko")
        self.assertIn("Analyze the attached image", prompt)
        self.assertIn("Korean", prompt)
        self.assertIn(_INJECTION_GUARD, prompt)
        # 새 경로에는 <image_url> 데이터 블록이 없다(가이드 내 언급은 제외).
        self.assertNotIn("<image_url>\n", prompt)

    def test_legacy_url_path_wraps_url(self) -> None:
        url = "https://cdn.example.com/pic.png"
        prompt = build_image_analysis_prompt(url, language="ko")
        _assert_wrapped(self, prompt, "image_url")
        self.assertIn(url, _wrapped_section(prompt, "image_url"))
        self.assertIn("Korean", prompt)


class PromptDeterminismTest(unittest.TestCase):
    """동일 입력에 대해 빌더가 결정적(deterministic)으로 같은 문자열을 내는지 회귀."""

    def test_summarize_is_deterministic(self) -> None:
        a = build_summarize_prompt(GOLDEN_TRANSCRIPT, language="ko")
        b = build_summarize_prompt(GOLDEN_TRANSCRIPT, language="ko")
        self.assertEqual(a, b)

    def test_ask_is_deterministic(self) -> None:
        a = build_ask_prompt(GOLDEN_TRANSCRIPT, GOLDEN_QUESTION, language="en")
        b = build_ask_prompt(GOLDEN_TRANSCRIPT, GOLDEN_QUESTION, language="en")
        self.assertEqual(a, b)

    def test_all_builders_return_nonempty_stripped_strings(self) -> None:
        # 모든 빌더 출력이 비어있지 않고 앞뒤 공백 없이 strip 된 문자열이어야 한다.
        prompts = [
            build_summarize_prompt(GOLDEN_TRANSCRIPT, language="ko"),
            build_ask_prompt(GOLDEN_TRANSCRIPT, GOLDEN_QUESTION, language="ko"),
            build_chat_prompt(GOLDEN_MESSAGE, language="ko"),
            build_translate_prompt(GOLDEN_TRANSLATE_TEXT, target_language="ko"),
            build_search_result_prompt(
                GOLDEN_TRANSCRIPT, GOLDEN_SEARCH_QUERY, language="ko"
            ),
            build_image_analysis_prompt(language="ko"),
        ]
        for prompt in prompts:
            self.assertIsInstance(prompt, str)
            self.assertTrue(prompt)
            self.assertEqual(prompt, prompt.strip())
            # 헤더와 본문이 개행으로 구조화되어 있어야 한다(단일 행 회귀 방지).
            self.assertIn("\n", prompt)


if __name__ == "__main__":
    unittest.main()

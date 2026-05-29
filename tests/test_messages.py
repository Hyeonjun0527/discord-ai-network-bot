"""messages.t() 번역 헬퍼 / 카탈로그 단위 테스트 (#87 i18n).

핵심 보증:
  - ko 는 기존 한국어 원문을 그대로 돌려준다(회귀 0).
  - en 은 카탈로그에 있으면 영어 번역을, 없으면 ko 로 폴백한다.
  - 미지원 언어/누락 키/포맷 인자 불일치도 안전하게 폴백한다.
"""
from __future__ import annotations

import unittest

from discord_assistant.messages import MESSAGES, t
from discord_assistant.prompts import _LANGUAGE_LABELS


class TestTranslateHelper(unittest.TestCase):
    def test_ko_returns_original_korean(self) -> None:
        # ko 는 원문 한국어 그대로(기존 테스트 단언과 100% 일치).
        self.assertEqual(t("settings.title", "ko"), "서버 AI 설정")

    def test_default_lang_is_ko(self) -> None:
        # lang 인자를 생략하면 ko 로 동작한다(백워드 호환).
        self.assertEqual(t("settings.title"), "서버 AI 설정")

    def test_en_uses_english_translation(self) -> None:
        self.assertEqual(t("settings.title", "en"), "Server AI Settings")

    def test_missing_translation_falls_back_to_ko(self) -> None:
        # 'fr' 번역은 카탈로그에 없으므로 ko 로 폴백한다.
        self.assertEqual(t("settings.title", "fr"), "서버 AI 설정")

    def test_unsupported_lang_falls_back_to_ko(self) -> None:
        # 지원 목록에 없는 코드는 ko 로 폴백한다.
        self.assertEqual(t("settings.title", "zz"), "서버 AI 설정")

    def test_none_lang_falls_back_to_ko(self) -> None:
        self.assertEqual(t("settings.title", None), "서버 AI 설정")

    def test_auto_lang_falls_back_to_ko(self) -> None:
        # UI 표면에서는 auto 를 ko 로 취급한다(트랜스크립트가 없어 감지 불가).
        self.assertEqual(t("settings.title", "auto"), "서버 AI 설정")

    def test_legacy_alias_kr_maps_to_ko(self) -> None:
        # prompts._LANGUAGE_ALIASES 재사용: kr→ko, jp→ja.
        self.assertEqual(t("settings.title", "kr"), "서버 AI 설정")

    def test_unknown_key_returns_key_string(self) -> None:
        # 등록되지 않은 키는 키 자체를 돌려줘 누락을 눈에 띄게 한다.
        self.assertEqual(t("nope.does.not.exist", "ko"), "nope.does.not.exist")

    def test_format_kwargs_ko(self) -> None:
        self.assertEqual(
            t("settings.summary_limit.value", "ko", count=50), "50개 메시지"
        )

    def test_format_kwargs_en(self) -> None:
        self.assertEqual(
            t("settings.summary_limit.value", "en", count=50), "50 messages"
        )

    def test_summary_header_format_ko(self) -> None:
        header = t("summary.header", "ko", count=100, since="")
        self.assertEqual(header, "**최근 100개 메시지 요약**\n")

    def test_summary_header_format_en(self) -> None:
        header = t("summary.header", "en", count=100, since="")
        self.assertEqual(header, "**Summary of the last 100 messages**\n")

    def test_summary_header_with_since(self) -> None:
        header = t("summary.header", "ko", count=10, since=" (since: 1h)")
        self.assertIn("(since: 1h)", header)

    def test_format_mismatch_returns_raw_text(self) -> None:
        # 포맷 인자가 안 맞아도 렌더가 깨지지 않고 원문 템플릿을 돌려준다.
        out = t("settings.summary_limit.value", "ko", wrong_kw=1)
        self.assertEqual(out, MESSAGES["settings.summary_limit.value"]["ko"])

    def test_external_title_format_en(self) -> None:
        out = t("external.title", "en", emoji="X", provider="OpenAI")
        self.assertEqual(out, "X  OpenAI Settings")

    def test_invalid_format_spec_returns_raw_text(self) -> None:
        # 잘못된 포맷 스펙(닫히지 않은 중괄호)은 ValueError 를 내지만
        # 렌더가 깨지지 않고 원문 템플릿을 그대로 돌려준다.
        MESSAGES["_test.invalid_spec"] = {"ko": "잔액 {count balance"}
        try:
            out = t("_test.invalid_spec", "ko", count=5)
            self.assertEqual(out, "잔액 {count balance")
        finally:
            del MESSAGES["_test.invalid_spec"]

    def test_empty_translation_falls_back_to_ko(self) -> None:
        # 빈 문자열('') 번역은 None 폴백과 동일하게 ko 로 폴백한다.
        MESSAGES["_test.empty"] = {"ko": "케이", "en": ""}
        try:
            self.assertEqual(t("_test.empty", "en"), "케이")
        finally:
            del MESSAGES["_test.empty"]


class TestCatalogIntegrity(unittest.TestCase):
    def test_every_key_has_korean(self) -> None:
        # ko 는 모든 키에 필수(폴백 최종 기준).
        for key, langs in MESSAGES.items():
            self.assertIn("ko", langs, msg=f"{key} 에 ko 누락")
            self.assertTrue(langs["ko"], msg=f"{key} 의 ko 가 비어 있음")

    def test_lang_codes_are_known(self) -> None:
        # 카탈로그에 등장하는 언어 코드는 모두 지원 목록에 있어야 한다.
        known = set(_LANGUAGE_LABELS)
        for key, langs in MESSAGES.items():
            for code in langs:
                self.assertIn(code, known, msg=f"{key} 의 알 수 없는 언어 코드 {code}")


if __name__ == "__main__":
    unittest.main()

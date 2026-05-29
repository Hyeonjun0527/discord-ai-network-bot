from __future__ import annotations

import unittest

from discord_assistant.prompts import build_ask_prompt, build_summarize_prompt, build_translate_prompt


class PromptTest(unittest.TestCase):
    def test_summary_prompt_contains_transcript_and_guardrails(self) -> None:
        prompt = build_summarize_prompt("alice: 배포 완료", language="ko")

        self.assertIn("alice: 배포 완료", prompt)
        self.assertIn("Do not invent facts", prompt)
        self.assertIn("Korean", prompt)

    def test_ask_prompt_contains_question(self) -> None:
        prompt = build_ask_prompt("bob: 회의는 3시", "회의 언제야?", language="ko")

        self.assertIn("회의 언제야?", prompt)
        self.assertIn("bob: 회의는 3시", prompt)
        self.assertIn("cannot confirm", prompt)

    def test_translate_prompt_preserves_mentions_rule(self) -> None:
        prompt = build_translate_prompt("hello <@123>", target_language="en")

        self.assertIn("hello <@123>", prompt)
        self.assertIn("Keep Discord mentions", prompt)

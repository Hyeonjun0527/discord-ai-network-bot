"""Unit tests for ui.py — settings_embed output validation."""
from __future__ import annotations

import unittest

import discord

from discord_assistant.models import GuildConfig, LLMProvider
from discord_assistant.ui import settings_embed


def _make_config(**overrides) -> GuildConfig:
    defaults = dict(
        guild_id=123,
        model="llama3.1:8b",
        summary_limit=50,
        language="ko",
        admin_role_id=None,
        provider=LLMProvider.OLLAMA,
        api_key_encrypted=None,
    )
    defaults.update(overrides)
    return GuildConfig(**defaults)


class TestSettingsEmbed(unittest.TestCase):
    def test_returns_discord_embed_instance(self):
        config = _make_config()
        embed = settings_embed(config, "Test Server")
        self.assertIsInstance(embed, discord.Embed)

    def test_embed_title(self):
        config = _make_config()
        embed = settings_embed(config, "My Server")
        self.assertEqual(embed.title, "서버 AI 설정")

    def test_embed_has_six_fields(self):
        """The embed is defined with exactly 6 fields (제공자, 모델, API 키, 언어, 요약 범위, blank)."""
        config = _make_config()
        embed = settings_embed(config, "Test Server")
        self.assertEqual(len(embed.fields), 6)

    def test_provider_field_present(self):
        config = _make_config(provider=LLMProvider.OLLAMA)
        embed = settings_embed(config, "Test Server")
        names = [f.name for f in embed.fields]
        self.assertIn("제공자", names)

    def test_model_field_present(self):
        config = _make_config(model="gpt-4o-mini")
        embed = settings_embed(config, "Test Server")
        names = [f.name for f in embed.fields]
        self.assertIn("모델", names)

    def test_api_key_absent_shows_unregistered(self):
        # Use OPENAI provider so _api_key_status returns a real status (not N/A)
        config = _make_config(provider=LLMProvider.OPENAI, api_key_encrypted=None)
        embed = settings_embed(config, "Test Server")
        api_key_field = next(f for f in embed.fields if f.name == "API 키")
        self.assertIn("미등록", api_key_field.value)

    def test_api_key_present_shows_registered(self):
        # Use OPENAI provider so _api_key_status returns a real status (not N/A)
        config = _make_config(provider=LLMProvider.OPENAI, api_key_encrypted="gAAAAABxxxxENcrypted")
        embed = settings_embed(config, "Test Server")
        api_key_field = next(f for f in embed.fields if f.name == "API 키")
        self.assertIn("등록됨", api_key_field.value)

    def test_guild_name_appears_in_description(self):
        config = _make_config()
        embed = settings_embed(config, "My Test Guild")
        self.assertIn("My Test Guild", embed.description or "")

    def test_language_field_present(self):
        config = _make_config(language="en")
        embed = settings_embed(config, "Test Server")
        names = [f.name for f in embed.fields]
        self.assertIn("언어", names)

    def test_summary_limit_field_present(self):
        config = _make_config(summary_limit=100)
        embed = settings_embed(config, "Test Server")
        names = [f.name for f in embed.fields]
        self.assertIn("요약 범위", names)

    def test_summary_limit_value_is_correct(self):
        config = _make_config(summary_limit=75)
        embed = settings_embed(config, "Test Server")
        summary_field = next(f for f in embed.fields if f.name == "요약 범위")
        self.assertIn("75", summary_field.value)

    def test_openai_provider_name_displayed(self):
        config = _make_config(provider=LLMProvider.OPENAI)
        embed = settings_embed(config, "Test Server")
        provider_field = next(f for f in embed.fields if f.name == "제공자")
        self.assertIn("OpenAI", provider_field.value)

    def test_anthropic_provider_name_displayed(self):
        config = _make_config(provider=LLMProvider.ANTHROPIC)
        embed = settings_embed(config, "Test Server")
        provider_field = next(f for f in embed.fields if f.name == "제공자")
        self.assertIn("Anthropic", provider_field.value)


if __name__ == "__main__":
    unittest.main()

"""Unit tests for bot.py helper functions (no real Discord objects required)."""
from __future__ import annotations

import unittest
from unittest.mock import MagicMock

from discord_assistant.bot import (
    MAX_DISCORD_MESSAGE_CHARS,
    UserFacingError,
    _effective_limit,
    _get_llm,
    _has_config_permission,
    _split_discord_text,
)
from discord_assistant.llm import AnthropicClient, OllamaClient, OpenAIClient
from discord_assistant.models import GuildConfig, LLMProvider
from discord_assistant.settings import AppSettings

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_settings(**overrides) -> AppSettings:
    """Return a minimal AppSettings for tests (no real token needed)."""
    defaults = dict(
        discord_bot_token="test-token",
        ollama_base_url="http://localhost:11434",
        ollama_model="llama3.1:8b",
        database_url=":memory:",
        default_summary_limit=50,
        max_context_chars=12_000,
        default_language="ko",
        ollama_timeout_seconds=60,
        auto_sync_commands=False,
        secret_key="test-secret-key-for-unit-tests",
    )
    defaults.update(overrides)
    return AppSettings(**defaults)


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


def _make_interaction(*, is_admin: bool = False, admin_role_id: int | None = None) -> MagicMock:
    """Duck-typed Discord Interaction using MagicMock."""
    interaction = MagicMock()
    permissions = MagicMock()
    permissions.administrator = is_admin
    permissions.manage_guild = False
    interaction.user.guild_permissions = permissions
    interaction.user.roles = []
    return interaction


# ---------------------------------------------------------------------------
# _split_discord_text
# ---------------------------------------------------------------------------


class TestSplitDiscordText(unittest.TestCase):
    def test_short_text_returns_single_chunk(self):
        result = _split_discord_text("Hello, world!")
        self.assertEqual(result, ["Hello, world!"])

    def test_empty_string_returns_placeholder(self):
        result = _split_discord_text("")
        self.assertEqual(result, ["(empty response)"])

    def test_whitespace_only_returns_placeholder(self):
        result = _split_discord_text("   \n   ")
        self.assertEqual(result, ["(empty response)"])

    def test_long_single_line_is_chunked(self):
        long_line = "x" * (MAX_DISCORD_MESSAGE_CHARS * 2 + 50)
        result = _split_discord_text(long_line)
        self.assertGreater(len(result), 1)
        for chunk in result:
            self.assertLessEqual(len(chunk), MAX_DISCORD_MESSAGE_CHARS)
        self.assertEqual("".join(result), long_line)

    def test_multiline_stays_under_limit(self):
        lines = ["Line number " + str(i) for i in range(200)]
        text = "\n".join(lines)
        result = _split_discord_text(text)
        for chunk in result:
            self.assertLessEqual(len(chunk), MAX_DISCORD_MESSAGE_CHARS)

    def test_all_chunks_reassemble_to_original_content(self):
        text = "Alpha\nBeta\nGamma"
        result = _split_discord_text(text)
        # All words must appear somewhere in the combined chunks
        combined = "\n".join(result)
        self.assertIn("Alpha", combined)
        self.assertIn("Beta", combined)
        self.assertIn("Gamma", combined)

    def test_exactly_max_chars_is_single_chunk(self):
        text = "a" * MAX_DISCORD_MESSAGE_CHARS
        result = _split_discord_text(text)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0], text)

    def test_custom_max_chars(self):
        result = _split_discord_text("abcdefghij", max_chars=3)
        for chunk in result:
            self.assertLessEqual(len(chunk), 3)


# ---------------------------------------------------------------------------
# _effective_limit
# ---------------------------------------------------------------------------


class TestEffectiveLimit(unittest.TestCase):
    def test_none_returns_default(self):
        self.assertEqual(_effective_limit(None, 50), 50)

    def test_valid_value_returned_as_is(self):
        self.assertEqual(_effective_limit(30, 50), 30)

    def test_zero_is_clamped_to_one(self):
        self.assertEqual(_effective_limit(0, 50), 1)

    def test_negative_is_clamped_to_one(self):
        self.assertEqual(_effective_limit(-10, 50), 1)

    def test_above_200_is_clamped_to_200(self):
        self.assertEqual(_effective_limit(999, 50), 200)

    def test_boundary_200_allowed(self):
        self.assertEqual(_effective_limit(200, 50), 200)

    def test_boundary_1_allowed(self):
        self.assertEqual(_effective_limit(1, 50), 1)


# ---------------------------------------------------------------------------
# _has_config_permission
# ---------------------------------------------------------------------------


class TestHasConfigPermission(unittest.TestCase):
    def test_administrator_returns_true(self):
        interaction = _make_interaction(is_admin=True)
        self.assertTrue(_has_config_permission(interaction, None))

    def test_manage_guild_returns_true(self):
        interaction = _make_interaction()
        interaction.user.guild_permissions.manage_guild = True
        self.assertTrue(_has_config_permission(interaction, None))

    def test_no_permissions_no_role_returns_false(self):
        interaction = _make_interaction()
        self.assertFalse(_has_config_permission(interaction, None))

    def test_matching_admin_role_returns_true(self):
        interaction = _make_interaction()
        role = MagicMock()
        role.id = 999
        interaction.user.roles = [role]
        self.assertTrue(_has_config_permission(interaction, 999))

    def test_non_matching_admin_role_returns_false(self):
        interaction = _make_interaction()
        role = MagicMock()
        role.id = 888
        interaction.user.roles = [role]
        self.assertFalse(_has_config_permission(interaction, 999))

    def test_no_guild_permissions_attribute_falls_back(self):
        """User without guild_permissions (e.g., DM) should return False."""
        interaction = MagicMock()
        del interaction.user.guild_permissions
        interaction.user.roles = []
        self.assertFalse(_has_config_permission(interaction, None))


# ---------------------------------------------------------------------------
# _get_llm
# ---------------------------------------------------------------------------


class TestGetLlm(unittest.TestCase):
    def _encrypted_key(self) -> str:
        from discord_assistant.crypto import encrypt_api_key
        return encrypt_api_key("sk-test-1234", "test-secret-key-for-unit-tests")

    def test_ollama_provider_returns_ollama_client(self):
        settings = _make_settings()
        config = _make_config(provider=LLMProvider.OLLAMA)
        client = _get_llm(config, settings)
        self.assertIsInstance(client, OllamaClient)

    def test_openai_provider_with_key_returns_openai_client(self):
        settings = _make_settings()
        encrypted = self._encrypted_key()
        config = _make_config(provider=LLMProvider.OPENAI, api_key_encrypted=encrypted)
        client = _get_llm(config, settings)
        self.assertIsInstance(client, OpenAIClient)

    def test_anthropic_provider_with_key_returns_anthropic_client(self):
        settings = _make_settings()
        encrypted = self._encrypted_key()
        config = _make_config(provider=LLMProvider.ANTHROPIC, api_key_encrypted=encrypted)
        client = _get_llm(config, settings)
        self.assertIsInstance(client, AnthropicClient)

    def test_openai_without_key_raises_user_facing_error(self):
        settings = _make_settings()
        config = _make_config(provider=LLMProvider.OPENAI, api_key_encrypted=None)
        with self.assertRaises(UserFacingError):
            _get_llm(config, settings)

    def test_anthropic_without_key_raises_user_facing_error(self):
        settings = _make_settings()
        config = _make_config(provider=LLMProvider.ANTHROPIC, api_key_encrypted=None)
        with self.assertRaises(UserFacingError):
            _get_llm(config, settings)

    def test_wrong_secret_key_raises_user_facing_error(self):
        """Decrypting with a different secret key should raise UserFacingError."""
        from discord_assistant.crypto import encrypt_api_key
        encrypted = encrypt_api_key("sk-test-1234", "correct-secret")
        settings = _make_settings(secret_key="wrong-secret")
        config = _make_config(provider=LLMProvider.OPENAI, api_key_encrypted=encrypted)
        with self.assertRaises(UserFacingError):
            _get_llm(config, settings)


if __name__ == "__main__":
    unittest.main()

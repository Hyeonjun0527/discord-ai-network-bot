from __future__ import annotations

from datetime import datetime, timezone
import unittest

from discord_assistant.context import build_transcript, filter_chat_messages, from_discord_message
from discord_assistant.models import ChatMessage


class ContextBuilderTest(unittest.TestCase):
    def test_filters_empty_and_bot_messages(self) -> None:
        messages = [
            ChatMessage("alice", "  hello\nworld  "),
            ChatMessage("bot", "ignore me", is_bot=True),
            ChatMessage("bob", "   "),
        ]

        filtered = filter_chat_messages(messages)

        self.assertEqual(filtered, [ChatMessage("alice", "hello world")])

    def test_build_transcript_keeps_newest_when_trimmed(self) -> None:
        messages = [
            ChatMessage("a", "older message", datetime(2026, 5, 1, tzinfo=timezone.utc)),
            ChatMessage("b", "newest important message", datetime(2026, 5, 2, tzinfo=timezone.utc)),
        ]

        transcript = build_transcript(messages, max_chars=80)

        self.assertNotIn("older message", transcript)
        self.assertIn("newest important message", transcript)
        self.assertIn("2026-05-02 00:00", transcript)

    def test_from_discord_message_duck_typed_attachment(self) -> None:
        class Author:
            display_name = "Alice"
            bot = False

        class Attachment:
            filename = "diagram.png"

        class Message:
            author = Author()
            clean_content = "look here"
            created_at = None
            attachments = [Attachment()]

        message = from_discord_message(Message())

        self.assertEqual(message.author, "Alice")
        self.assertIn("look here", message.content)
        self.assertIn("diagram.png", message.content)


if __name__ == "__main__":
    unittest.main()

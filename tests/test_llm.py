from __future__ import annotations

import unittest

from discord_assistant.llm import OllamaClient, OllamaError, parse_generate_response


class LlmTest(unittest.TestCase):
    def test_parse_generate_response(self) -> None:
        self.assertEqual(parse_generate_response({"response": " hello "}), "hello")

    def test_parse_generate_response_error(self) -> None:
        with self.assertRaises(OllamaError):
            parse_generate_response({"error": "model not found"})

    def test_client_strips_trailing_base_url_slash(self) -> None:
        client = OllamaClient(base_url="http://localhost:11434/", default_model="llama3.1:8b")

        self.assertEqual(client.base_url, "http://localhost:11434")


if __name__ == "__main__":
    unittest.main()

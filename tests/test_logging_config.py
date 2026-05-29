"""logging_config.setup_logging / JsonFormatter 단위 테스트 (#45)."""
from __future__ import annotations

import json
import logging
import unittest

from discord_assistant.logging_config import JsonFormatter, setup_logging


def _record(
    *,
    name: str = "test.logger",
    level: int = logging.INFO,
    msg: str = "hello",
    args: tuple[object, ...] = (),
    exc_info=None,
) -> logging.LogRecord:
    """포맷터 검증용 LogRecord를 만든다."""
    return logging.LogRecord(
        name=name,
        level=level,
        pathname=__file__,
        lineno=1,
        msg=msg,
        args=args,
        exc_info=exc_info,
    )


class JsonFormatterTest(unittest.TestCase):
    def test_output_is_valid_json_with_expected_keys(self) -> None:
        formatter = JsonFormatter()
        line = formatter.format(_record(name="my.logger", msg="world"))
        # 한 줄이 그대로 JSON으로 파싱 가능해야 한다.
        parsed = json.loads(line)
        self.assertEqual(parsed["level"], "INFO")
        self.assertEqual(parsed["logger"], "my.logger")
        self.assertEqual(parsed["message"], "world")
        self.assertIn("time", parsed)
        # 예외가 없으면 exception 키는 포함되지 않는다.
        self.assertNotIn("exception", parsed)

    def test_message_args_are_interpolated(self) -> None:
        formatter = JsonFormatter()
        line = formatter.format(_record(msg="count=%d name=%s", args=(3, "kim")))
        parsed = json.loads(line)
        self.assertEqual(parsed["message"], "count=3 name=kim")

    def test_exception_is_included(self) -> None:
        formatter = JsonFormatter()
        try:
            raise ValueError("boom")
        except ValueError:
            import sys

            rec = _record(level=logging.ERROR, msg="failed", exc_info=sys.exc_info())
        line = formatter.format(rec)
        parsed = json.loads(line)
        self.assertEqual(parsed["level"], "ERROR")
        self.assertIn("exception", parsed)
        self.assertIn("ValueError: boom", parsed["exception"])


class SetupLoggingTest(unittest.TestCase):
    def setUp(self) -> None:
        # 각 테스트 전후로 루트 로거 핸들러를 초기 상태로 되돌린다.
        self._root = logging.getLogger()
        self._saved_handlers = list(self._root.handlers)
        self._saved_level = self._root.level
        self._root.handlers.clear()

    def tearDown(self) -> None:
        self._root.handlers.clear()
        self._root.handlers.extend(self._saved_handlers)
        self._root.setLevel(self._saved_level)

    def test_text_format_is_default(self) -> None:
        handler = setup_logging(log_format=None, stream=None)
        # 환경변수가 없으면 텍스트 포맷터여야 한다.
        self.assertNotIsInstance(handler.formatter, JsonFormatter)
        self.assertEqual(self._root.level, logging.INFO)

    def test_json_format_selected(self) -> None:
        handler = setup_logging(log_format="json")
        self.assertIsInstance(handler.formatter, JsonFormatter)

    def test_is_idempotent_no_duplicate_handlers(self) -> None:
        before = len(self._root.handlers)
        h1 = setup_logging(log_format="text")
        h2 = setup_logging(log_format="json")
        # 두 번 호출해도 핸들러는 하나만 추가되어야 한다.
        self.assertEqual(len(self._root.handlers), before + 1)
        # 같은 핸들러 객체를 재사용하며 포맷터만 갱신한다.
        self.assertIs(h1, h2)
        self.assertIsInstance(h2.formatter, JsonFormatter)

    def test_custom_level(self) -> None:
        setup_logging(level=logging.DEBUG)
        self.assertEqual(self._root.level, logging.DEBUG)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()

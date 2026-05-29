"""logging_config.setup_logging / JsonFormatter 단위 테스트 (#45)."""
from __future__ import annotations

import json
import logging
import unittest

from discord_assistant.logging_config import (
    CorrelationIdFilter,
    JsonFormatter,
    get_correlation_id,
    set_correlation_id,
    setup_logging,
)


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

    def test_cid_defaults_to_dash_without_filter(self) -> None:
        # 필터 없이 직접 포맷해도 KeyError 없이 cid 기본값('-')이 들어가야 한다.
        formatter = JsonFormatter()
        parsed = json.loads(formatter.format(_record()))
        self.assertEqual(parsed["cid"], "-")

    def test_cid_from_record_attribute_is_serialized(self) -> None:
        formatter = JsonFormatter()
        rec = _record()
        rec.cid = "abc-123"
        parsed = json.loads(formatter.format(rec))
        self.assertEqual(parsed["cid"], "abc-123")

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

    def test_handler_has_correlation_filter(self) -> None:
        # #46: 핸들러에 CorrelationIdFilter 가 부착되어 cid 가 실제로 주입돼야 한다.
        handler = setup_logging(log_format="text")
        self.assertTrue(
            any(isinstance(f, CorrelationIdFilter) for f in handler.filters)
        )

    def test_idempotent_does_not_duplicate_filter(self) -> None:
        # 반복 호출해도 필터가 중복 부착되지 않아야 한다.
        h1 = setup_logging(log_format="text")
        setup_logging(log_format="json")
        cid_filters = [
            f for f in h1.filters if isinstance(f, CorrelationIdFilter)
        ]
        self.assertEqual(len(cid_filters), 1)

    def test_text_format_emits_cid(self) -> None:
        # cid 를 바인딩하면 텍스트 출력에 (cid=...) 가 실제로 찍혀야 한다.
        import io

        buffer = io.StringIO()
        setup_logging(log_format="text", stream=buffer)
        set_correlation_id("trace-77")
        try:
            logging.getLogger("cid.test").info("hello")
        finally:
            set_correlation_id(None)
        self.assertIn("(cid=trace-77)", buffer.getvalue())

    def test_json_format_emits_cid(self) -> None:
        import io

        buffer = io.StringIO()
        setup_logging(log_format="json", stream=buffer)
        set_correlation_id("trace-88")
        try:
            logging.getLogger("cid.test").info("hi")
        finally:
            set_correlation_id(None)
        line = buffer.getvalue().strip().splitlines()[-1]
        self.assertEqual(json.loads(line)["cid"], "trace-88")


class CorrelationIdFilterTest(unittest.TestCase):
    def tearDown(self) -> None:
        set_correlation_id(None)

    def test_default_is_dash(self) -> None:
        set_correlation_id(None)
        self.assertEqual(get_correlation_id(), "-")

    def test_set_and_get_roundtrip(self) -> None:
        set_correlation_id(12345)
        self.assertEqual(get_correlation_id(), "12345")

    def test_filter_injects_cid_and_returns_true(self) -> None:
        set_correlation_id("xyz")
        flt = CorrelationIdFilter()
        rec = logging.LogRecord(
            name="t", level=logging.INFO, pathname=__file__,
            lineno=1, msg="m", args=(), exc_info=None,
        )
        self.assertTrue(flt.filter(rec))
        self.assertEqual(rec.cid, "xyz")

    def test_filter_does_not_overwrite_existing_cid(self) -> None:
        set_correlation_id("ctx")
        flt = CorrelationIdFilter()
        rec = logging.LogRecord(
            name="t", level=logging.INFO, pathname=__file__,
            lineno=1, msg="m", args=(), exc_info=None,
        )
        rec.cid = "preset"
        flt.filter(rec)
        self.assertEqual(rec.cid, "preset")


if __name__ == "__main__":  # pragma: no cover
    unittest.main()

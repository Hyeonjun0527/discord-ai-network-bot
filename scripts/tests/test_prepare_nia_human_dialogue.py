import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "prepare-nia-human-dialogue.py"
SPEC = importlib.util.spec_from_file_location("prepare_nia_human_dialogue", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class PrepareNiaHumanDialogueTest(unittest.TestCase):
    def test_parses_timestamped_and_continuation_lines_without_loss(self):
        parsed = MODULE.parse_messages(
            "source-01-test",
            "[2026-01-02 03:04] 참여자 A: 첫 줄\n이어진 줄\n[2026-01-02 03:05] 참여자 B: 응",
        )

        self.assertEqual(2, len(parsed.messages))
        self.assertEqual("첫 줄\n이어진 줄", parsed.messages[0].content)
        self.assertEqual("minute", parsed.messages[0].timestamp_precision)
        self.assertEqual(1, parsed.continuation_lines)

    def test_parses_month_heading_and_bare_messages(self):
        parsed = MODULE.parse_messages(
            "source-02-test",
            "익명화 안내\n[2024-07]\n참여자 A: 안녕\n참여자 B: 어 안녕",
        )

        self.assertEqual(2, len(parsed.messages))
        self.assertEqual("2024-07", parsed.messages[0].period_key)
        self.assertEqual("month", parsed.messages[0].timestamp_precision)
        self.assertEqual(1, parsed.skipped_prologue_lines)

    def test_builds_both_direction_response_scenes_and_marks_exact_duplicates(self):
        parsed = MODULE.parse_messages(
            "source-03-test",
            "[2026-01-02] 참여자 A: 힘들어\n[2026-01-02] 참여자 B: 왜 무슨 일 있어\n"
            "[2026-01-02] 참여자 A: 그냥 일이 많아",
        )
        scenes = MODULE.build_scene_candidates("source-03-test", parsed.messages)

        self.assertEqual(2, len(scenes))
        self.assertEqual("A_TO_B", scenes[0]["direction"])
        self.assertEqual("B_TO_A", scenes[1]["direction"])
        self.assertEqual("PENDING", scenes[0]["review"]["status"])

    def test_prepare_creates_private_snapshot_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "conversation.txt"
            source.write_text(
                "[2026-01-02] 참여자 A: 안녕\n[2026-01-02] 참여자 B: 어 안녕",
                encoding="utf-8",
            )
            manifest = root / "manifest.txt"
            manifest.write_text(str(source), encoding="utf-8")
            output = root / "snapshot"

            summary = MODULE.prepare(manifest, output)

            self.assertEqual(2, summary["message_count"])
            self.assertEqual(1, summary["scene_candidate_count"])
            self.assertEqual(0, summary["network_requests"])
            self.assertTrue((output / "reports" / "summary.json").is_file())
            with self.assertRaises(ValueError):
                MODULE.prepare(manifest, output)


if __name__ == "__main__":
    unittest.main()

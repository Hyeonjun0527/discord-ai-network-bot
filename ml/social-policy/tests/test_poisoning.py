"""NEXA-P17-T019: 학습 데이터 poisoning 탐지 — 도배·coordinated mention·반복·bot 을 격리한다.

acceptance: 자동 탐지 결과를 사람 검토 없이 사용자 제재로 쓰지 않는다(격리 표시만, 결정론).
"""

from __future__ import annotations

from nexa_policy.data.poisoning import (
    PoisoningKind,
    PoisoningThresholds,
    TrainingRecord,
    detect_bot_generated,
    detect_coordinated_mention,
    detect_flooding,
    detect_near_duplicate,
    filter_clean,
    scan_poisoning,
)

THRESHOLDS = PoisoningThresholds()


def _rec(rid: str, actor: str, t: int, text: str = "hi", target: str | None = None, bot: bool = False) -> TrainingRecord:
    return TrainingRecord(record_id=rid, actor=actor, time_ms=t, text=text, mention_target=target, is_bot=bot)


def test_clean_records_have_no_quarantine() -> None:
    records = [
        _rec("r1", "a", 0, "안녕"),
        _rec("r2", "b", 5_000, "반가워"),
        _rec("r3", "c", 10_000, "좋은 하루"),
    ]
    report = scan_poisoning(records, THRESHOLDS)
    assert report.ok
    assert report.quarantined_ids == set()


def test_flooding_is_quarantined() -> None:
    # 한 actor 가 한 창 안에 flood_count 이상 — 모두 격리.
    records = [_rec(f"f{i}", "spammer", i * 1_000, f"msg{i}") for i in range(THRESHOLDS.flood_count)]
    entries = detect_flooding(records, THRESHOLDS)
    assert {e.record_id for e in entries} == {f"f{i}" for i in range(THRESHOLDS.flood_count)}
    assert all(e.kind is PoisoningKind.FLOODING for e in entries)


def test_flooding_below_threshold_passes() -> None:
    records = [_rec(f"f{i}", "x", i * 1_000, f"m{i}") for i in range(THRESHOLDS.flood_count - 1)]
    assert detect_flooding(records, THRESHOLDS) == []


def test_coordinated_mention_is_quarantined() -> None:
    # 서로 다른 actor coordinated_actor_count 이상이 같은 target 멘션 → 격리.
    records = [
        _rec(f"c{i}", f"actor{i}", i * 1_000, target="victim")
        for i in range(THRESHOLDS.coordinated_actor_count)
    ]
    entries = detect_coordinated_mention(records, THRESHOLDS)
    assert {e.record_id for e in entries} == {f"c{i}" for i in range(THRESHOLDS.coordinated_actor_count)}
    assert all(e.kind is PoisoningKind.COORDINATED_MENTION for e in entries)


def test_same_actor_repeated_mention_is_not_coordinated() -> None:
    # 같은 actor 가 여러 번 멘션해도 actor 다양성이 없으면 coordinated 아님.
    records = [_rec(f"s{i}", "solo", i * 1_000, target="victim") for i in range(5)]
    assert detect_coordinated_mention(records, THRESHOLDS) == []


def test_near_duplicate_is_quarantined() -> None:
    records = [_rec(f"d{i}", f"a{i}", i * 1_000, text="Buy NOW   http://x") for i in range(THRESHOLDS.duplicate_count)]
    entries = detect_near_duplicate(records, THRESHOLDS)
    assert {e.record_id for e in entries} == {f"d{i}" for i in range(THRESHOLDS.duplicate_count)}
    assert all(e.kind is PoisoningKind.NEAR_DUPLICATE for e in entries)


def test_near_duplicate_normalizes_case_and_whitespace() -> None:
    records = [
        _rec("n1", "a", 0, "Spam Text"),
        _rec("n2", "b", 1, "spam   text"),
        _rec("n3", "c", 2, "  SPAM TEXT "),
    ]
    entries = detect_near_duplicate(records, THRESHOLDS)
    assert {e.record_id for e in entries} == {"n1", "n2", "n3"}


def test_bot_generated_is_quarantined() -> None:
    records = [_rec("b1", "botuser", 0, bot=True), _rec("h1", "human", 1, bot=False)]
    entries = detect_bot_generated(records)
    assert {e.record_id for e in entries} == {"b1"}
    assert entries[0].kind is PoisoningKind.BOT_GENERATED


def test_scan_is_deterministic() -> None:
    records = [_rec(f"f{i}", "spammer", i * 1_000) for i in range(THRESHOLDS.flood_count)]
    first = scan_poisoning(records, THRESHOLDS).quarantined_ids
    second = scan_poisoning(records, THRESHOLDS).quarantined_ids
    assert first == second


def test_report_only_marks_no_sanction_api() -> None:
    # acceptance: 격리 표시만 — ban/suspend/제재 메서드가 없다(사람 검토 위임).
    report = scan_poisoning([_rec("b1", "bot", 0, bot=True)], THRESHOLDS)
    public = {name for name in dir(report) if not name.startswith("_")}
    forbidden = {"ban", "suspend", "block", "sanction", "punish", "delete_user", "deleteUser"}
    assert public & forbidden == set()
    assert "quarantined" in public


def test_filter_clean_removes_quarantined_only() -> None:
    flood = [_rec(f"f{i}", "spammer", i * 1_000) for i in range(THRESHOLDS.flood_count)]
    good = [_rec("g1", "alice", 0, "진짜 사람 대화")]
    clean, report = filter_clean(flood + good, THRESHOLDS)
    assert {r.record_id for r in clean} == {"g1"}
    assert report.quarantined_ids == {f"f{i}" for i in range(THRESHOLDS.flood_count)}

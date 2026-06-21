"""T012 guild-level split·T013 시간 holdout 테스트 — 누출 방지·결정론."""

from __future__ import annotations

from dataclasses import dataclass

import pytest

from nexa_policy.data.split import (
    Split,
    SplitRatios,
    TimeSplit,
    assert_no_guild_leakage,
    assert_no_time_leakage,
    split_by_guild,
    split_by_time,
)


@dataclass(frozen=True)
class Sample:
    guild: str
    time_ms: int
    sample_id: str


def _samples() -> list[Sample]:
    out: list[Sample] = []
    for g in range(40):
        for k in range(3):  # 길드당 파생 샘플 3개.
            out.append(Sample(guild=f"guild-{g}", time_ms=g * 1000 + k, sample_id=f"g{g}-{k}"))
    return out


# ---- T012 ----
def test_same_guild_never_in_two_splits() -> None:
    split_map = split_by_guild(_samples(), guild_of=lambda s: s.guild, seed=0)
    assert_no_guild_leakage(split_map, guild_of=lambda s: s.guild)
    # 명시적으로도 검사.
    guild_to_split: dict[str, Split] = {}
    for split, samples in split_map.items():
        for s in samples:
            assert guild_to_split.setdefault(s.guild, split) == split


def test_derived_samples_follow_guild() -> None:
    split_map = split_by_guild(_samples(), guild_of=lambda s: s.guild, seed=0)
    # 같은 길드의 3개 파생 샘플은 같은 split.
    for _split, samples in split_map.items():
        by_guild: dict[str, int] = {}
        for s in samples:
            by_guild[s.guild] = by_guild.get(s.guild, 0) + 1
        for guild, count in by_guild.items():
            assert count == 3, f"{guild} 가 split 에 {count}개만 — 파생 샘플 분리됨"


def test_split_is_deterministic() -> None:
    a = split_by_guild(_samples(), guild_of=lambda s: s.guild, seed=5)
    b = split_by_guild(_samples(), guild_of=lambda s: s.guild, seed=5)
    assert {k: [s.sample_id for s in v] for k, v in a.items()} == {
        k: [s.sample_id for s in v] for k, v in b.items()
    }


def test_all_three_splits_populated() -> None:
    split_map = split_by_guild(_samples(), guild_of=lambda s: s.guild, seed=0)
    assert len(split_map[Split.TRAIN]) > 0
    assert len(split_map[Split.VALIDATION]) > 0
    assert len(split_map[Split.TEST]) > 0


def test_leakage_guard_detects_injected_leak() -> None:
    leak = {
        Split.TRAIN: [Sample("guild-X", 1, "a")],
        Split.TEST: [Sample("guild-X", 2, "b")],
        Split.VALIDATION: [],
    }
    with pytest.raises(ValueError, match="누출"):
        assert_no_guild_leakage(leak, guild_of=lambda s: s.guild)


def test_invalid_ratios_rejected() -> None:
    with pytest.raises(ValueError):
        SplitRatios(train=0.5, validation=0.3, test=0.3)


# ---- T013 ----
def test_time_holdout_cutoff_no_future_in_train() -> None:
    samples = [Sample("g", t, f"s{t}") for t in (10, 20, 30, 40, 50)]
    split_map = split_by_time(samples, time_of=lambda s: s.time_ms, cutoff_ms=30)
    assert all(s.time_ms < 30 for s in split_map[TimeSplit.TRAIN])
    assert all(s.time_ms >= 30 for s in split_map[TimeSplit.HOLDOUT])
    assert_no_time_leakage(split_map, time_of=lambda s: s.time_ms, cutoff_ms=30)


def test_time_leakage_guard_detects_future_in_train() -> None:
    bad = {
        TimeSplit.TRAIN: [Sample("g", 99, "x")],
        TimeSplit.HOLDOUT: [],
    }
    with pytest.raises(ValueError, match="시간 누출"):
        assert_no_time_leakage(bad, time_of=lambda s: s.time_ms, cutoff_ms=30)


def test_guild_and_time_splits_are_independent() -> None:
    # 길드 분리와 시간 holdout 을 독립적으로 적용해도 일관.
    samples = _samples()
    guild_map = split_by_guild(samples, guild_of=lambda s: s.guild, seed=1)
    train_guild = guild_map[Split.TRAIN]
    time_map = split_by_time(train_guild, time_of=lambda s: s.time_ms, cutoff_ms=20_000)
    assert_no_time_leakage(time_map, time_of=lambda s: s.time_ms, cutoff_ms=20_000)

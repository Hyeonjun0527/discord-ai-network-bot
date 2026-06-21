#!/usr/bin/env python3
"""NEXA-P04-T023 — baseline 버스트 분할 정밀도·재현율 측정(experiment).

라벨 데이터(`test-fixtures/nexa/bursts/labels/*.yaml`)에 대해 두 분할 규칙을 비교한다:

- `fixed_gap`: 시간 gap 만 보는 naive 기준선 — 인접 fragment 의 gap 이 임계값(기본 7s)을 넘으면 boundary(split).
- `dynamic_feature`: 화자 변경·gap 을 합친 동적 규칙 — 화자가 바뀌거나 gap 이 임계값을 넘으면 boundary.
  (reply target·thread·typing 신호는 fixture 에 없으면 영향 없음 — 있으면 추가 boundary 신호로 합산.)

정답은 사람이 라벨한 boundary(`split`/`join`)다. `ambiguous: true` boundary 는 strict 정밀도/재현율 분모에서
제외하고 별도 비율로 보고한다(annotator 불확실 신호). boundary F1·over-merge(놓친 split=실제 boundary 인데
join 으로 예측)·over-split(헛 split=실제 join 인데 split 으로 예측)을 언어/채널별로도 집계한다.

실제 운영 데이터를 쓰지 않는다 — 합성 golden fixture 와 그 라벨만 본다(EXP-burst-baseline.md 참조).

실행:
    python3 scripts/evaluate-burst-segmentation.py            # 표 출력
    python3 scripts/evaluate-burst-segmentation.py --json     # 기계 판독(JSON)
    python3 scripts/evaluate-burst-segmentation.py --gap-seconds 7
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import yaml

REPO_ROOT = Path(__file__).resolve().parents[1]
FIXTURE_DIR = REPO_ROOT / "test-fixtures" / "nexa" / "bursts"
LABEL_DIR = FIXTURE_DIR / "labels"
LABEL_SCHEMA = "nexa.burst-labels.v1"
FIXTURE_SCHEMA = "nexa.burst-fixture.v1"


@dataclass(frozen=True)
class Boundary:
    after_id: str
    before_id: str
    gold_split: bool
    ambiguous: bool


@dataclass(frozen=True)
class LabeledFixture:
    fixture_id: str
    language: str
    channel_kind: str
    boundaries: list[Boundary]
    # adjacency fragment metadata for prediction: (author, offset_ms) keyed by messageId
    author_by_msg: dict[str, str]
    offset_by_msg: dict[str, int]


@dataclass(frozen=True)
class Score:
    strategy: str
    true_positive: int  # gold split predicted split
    false_positive: int  # gold join predicted split (over-split)
    false_negative: int  # gold split predicted join (over-merge)
    true_negative: int
    confident: int
    ambiguous_excluded: int

    @property
    def precision(self) -> float:
        denom = self.true_positive + self.false_positive
        return self.true_positive / denom if denom else 1.0

    @property
    def recall(self) -> float:
        denom = self.true_positive + self.false_negative
        return self.true_positive / denom if denom else 1.0

    @property
    def f1(self) -> float:
        p, r = self.precision, self.recall
        return (2 * p * r / (p + r)) if (p + r) else 0.0


def load_fixture(fixture_id: str) -> tuple[dict[str, str], dict[str, int]]:
    path = FIXTURE_DIR / f"{fixture_id}.yaml"
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != FIXTURE_SCHEMA:
        raise ValueError(f"{path.name}: schemaVersion must be {FIXTURE_SCHEMA}")
    base = data.get("time", {}).get("baseInstant")
    author_by_msg: dict[str, str] = {}
    offset_by_msg: dict[str, int] = {}
    for frag in data.get("fragments", []):
        mid = str(frag["messageId"])
        author_by_msg[mid] = str(frag["authorId"])
        offset_by_msg[mid] = int(frag["atOffsetMs"])
    if not author_by_msg:
        raise ValueError(f"{path.name}: no fragments")
    _ = base  # baseInstant unused beyond offsets; kept for clarity.
    return author_by_msg, offset_by_msg


def load_labels(path: Path) -> LabeledFixture:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != LABEL_SCHEMA:
        raise ValueError(f"{path.name}: schemaVersion must be {LABEL_SCHEMA}")
    fixture_id = str(data["fixtureId"])
    author_by_msg, offset_by_msg = load_fixture(fixture_id)
    boundaries = [
        Boundary(
            after_id=str(b["afterMessageId"]),
            before_id=str(b["beforeMessageId"]),
            gold_split=str(b["label"]) == "split",
            ambiguous=bool(b.get("ambiguous", False)),
        )
        for b in data.get("boundaries", [])
    ]
    expected = len(author_by_msg) - 1
    if len(boundaries) != expected:
        raise ValueError(f"{path.name}: expected {expected} boundaries for {len(author_by_msg)} fragments, got {len(boundaries)}")
    return LabeledFixture(
        fixture_id=fixture_id,
        language=str(data.get("language", "unknown")),
        channel_kind=str(data.get("channelKind", "unknown")),
        boundaries=boundaries,
        author_by_msg=author_by_msg,
        offset_by_msg=offset_by_msg,
    )


def predict_fixed_gap(fix: LabeledFixture, b: Boundary, gap_ms: int) -> bool:
    """naive 기준선: 시간 gap 만 본다(화자 무시)."""
    gap = fix.offset_by_msg[b.before_id] - fix.offset_by_msg[b.after_id]
    return gap > gap_ms


def predict_dynamic_feature(fix: LabeledFixture, b: Boundary, gap_ms: int) -> bool:
    """동적 규칙: 화자 변경 또는 gap 초과면 boundary(reply/thread/typing 신호는 fixture 에 있으면 합산)."""
    if fix.author_by_msg[b.after_id] != fix.author_by_msg[b.before_id]:
        return True
    gap = fix.offset_by_msg[b.before_id] - fix.offset_by_msg[b.after_id]
    return gap > gap_ms


def score(fixtures: list[LabeledFixture], strategy: str, predict, gap_ms: int) -> Score:
    tp = fp = fn = tn = ambiguous = 0
    for fix in fixtures:
        for b in fix.boundaries:
            if b.ambiguous:
                ambiguous += 1
                continue
            predicted = predict(fix, b, gap_ms)
            if b.gold_split and predicted:
                tp += 1
            elif b.gold_split and not predicted:
                fn += 1
            elif not b.gold_split and predicted:
                fp += 1
            else:
                tn += 1
    return Score(
        strategy=strategy,
        true_positive=tp,
        false_positive=fp,
        false_negative=fn,
        true_negative=tn,
        confident=tp + fp + fn + tn,
        ambiguous_excluded=ambiguous,
    )


def per_segment_scores(fixtures: list[LabeledFixture], predict, gap_ms: int, key) -> dict[str, Score]:
    groups: dict[str, list[LabeledFixture]] = {}
    for fix in fixtures:
        groups.setdefault(key(fix), []).append(fix)
    return {k: score(v, "segment", predict, gap_ms) for k, v in sorted(groups.items())}


def build_report(gap_ms: int) -> dict[str, Any]:
    label_paths = sorted(LABEL_DIR.glob("*.yaml"))
    if not label_paths:
        raise ValueError(f"no label files under {LABEL_DIR.relative_to(REPO_ROOT)}")
    fixtures = [load_labels(p) for p in label_paths]

    strategies = {
        "fixed_gap": predict_fixed_gap,
        "dynamic_feature": predict_dynamic_feature,
    }
    overall = {name: score(fixtures, name, fn, gap_ms) for name, fn in strategies.items()}

    def seg(predict, key):
        return {k: asdict_score(s) for k, s in per_segment_scores(fixtures, predict, gap_ms, key).items()}

    return {
        "gapMillis": gap_ms,
        "fixtures": [f.fixture_id for f in fixtures],
        "labeledBoundaries": sum(len(f.boundaries) for f in fixtures),
        "overall": {name: asdict_score(s) for name, s in overall.items()},
        "perLanguage": {name: seg(fn, lambda f: f.language) for name, fn in strategies.items()},
        "perChannel": {name: seg(fn, lambda f: f.channel_kind) for name, fn in strategies.items()},
    }


def asdict_score(s: Score) -> dict[str, Any]:
    d = asdict(s)
    d.update(
        precision=round(s.precision, 4),
        recall=round(s.recall, 4),
        f1=round(s.f1, 4),
        overSplit=s.false_positive,
        overMerge=s.false_negative,
    )
    return d


def print_table(report: dict[str, Any]) -> None:
    print(f"# 버스트 분할 baseline 측정 (gap={report['gapMillis']}ms)")
    print(f"fixtures: {', '.join(report['fixtures'])} · labeled boundaries: {report['labeledBoundaries']}")
    print()
    print("| strategy | precision | recall | F1 | over-split | over-merge | ambiguous excl |")
    print("| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    for name, s in report["overall"].items():
        print(
            f"| {name} | {s['precision']:.3f} | {s['recall']:.3f} | {s['f1']:.3f} "
            f"| {s['overSplit']} | {s['overMerge']} | {s['ambiguous_excluded']} |"
        )
    print()
    for dim, title in (("perLanguage", "언어별"), ("perChannel", "채널별")):
        print(f"## {title} F1")
        print("| strategy | segment | precision | recall | F1 | over-split | over-merge |")
        print("| --- | --- | ---: | ---: | ---: | ---: | ---: |")
        for name, segments in report[dim].items():
            for seg_key, s in segments.items():
                print(
                    f"| {name} | {seg_key} | {s['precision']:.3f} | {s['recall']:.3f} | {s['f1']:.3f} "
                    f"| {s['overSplit']} | {s['overMerge']} |"
                )
        print()


def main() -> int:
    parser = argparse.ArgumentParser(description="NEXA burst segmentation baseline precision/recall")
    parser.add_argument("--gap-seconds", type=float, default=7.0, help="fixed-gap 임계값(초, 기본 7)")
    parser.add_argument("--json", action="store_true", help="JSON 출력")
    args = parser.parse_args()

    try:
        report = build_report(int(args.gap_seconds * 1000))
    except (OSError, ValueError, yaml.YAMLError) as exc:
        print(f"INVALID: {exc}", file=sys.stderr)
        return 1

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print_table(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

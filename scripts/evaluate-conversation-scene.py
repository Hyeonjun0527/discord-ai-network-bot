#!/usr/bin/env python3
"""NEXA-P05-T023 — thread·addressee 평가 스크립트(experiment).

golden fixture(`test-fixtures/nexa/scenes/*.yaml`)에 대해 네 가지 장면 지표를 측정한다:

- **edge F1**: thread reply-edge baseline 이 fixture 의 reply edge(=같은 thread 로 묶는 강한 신호)를
  맞히는 정밀도/재현율/F1. baseline 은 "명시된 replyTo 가 있으면 edge 다" 규칙(naive reply-only)이다.
- **target top-1 accuracy**: addressee baseline 이 각 burst 의 target(none/group/특정인)을 얼마나 맞히나.
  baseline 규칙: replyTo→specific, groupForm→group, 그 외(nickname/self/무표식)→none(약한 신호 미사용).
- **thread clustering score**: reply 그래프로 burst 를 union-find 병합한 클러스터가 fixture 의 thread 정답
  분할과 얼마나 일치하나(쌍별 동일-클러스터 정확도 = pairwise clustering accuracy).
- **correction rate**: baseline 의 target 예측이 ambiguous 가 아닌 정답과 어긋나 사후 교정이 필요한 비율.

acceptance(T023): 전체 평균뿐 아니라 **reply/mention/무표식 상황별** target 오류를 분해해 보고한다.
ambiguous 라벨은 strict target accuracy 분모에서 제외하고 별도 비율로 보고한다(annotator 불확실 신호).

실제 운영 데이터를 쓰지 않는다 — 합성 golden fixture 만 본다(EXP-scene-baseline.md 참조).

실행:
    python3 scripts/evaluate-conversation-scene.py            # 표 출력
    python3 scripts/evaluate-conversation-scene.py --json     # 기계 판독(JSON)
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from itertools import combinations
from pathlib import Path
from typing import Any

import yaml

REPO_ROOT = Path(__file__).resolve().parents[1]
SCENE_DIR = REPO_ROOT / "test-fixtures" / "nexa" / "scenes"
ADDRESSEE_SCHEMA = "nexa.addressee-labels.v1"
SCENE_SCHEMA = "nexa.scene-fixture.v1"

# 상황 분류(situation) — target 오류를 reply/mention/무표식 별로 분해하는 키.
SIT_REPLY = "reply"
SIT_MENTION = "mention"
SIT_UNMARKED = "unmarked"


@dataclass(frozen=True)
class AddresseeCase:
    burst_id: str
    situation: str
    gold_kind: str  # none | group | specific
    gold_target: str | None
    ambiguous: bool
    predicted_kind: str
    predicted_target: str | None

    @property
    def correct(self) -> bool:
        return self.predicted_kind == self.gold_kind and self.predicted_target == self.gold_target


def _read(path: Path) -> dict[str, Any]:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"{path.name}: root must be a mapping")
    return data


def classify_situation(signals: dict[str, Any]) -> str:
    """burst 신호로 상황을 분류한다(reply > mention(nickname) > 무표식)."""
    if signals.get("replyTo") or signals.get("selfReply"):
        return SIT_REPLY
    if signals.get("nicknameCall"):
        return SIT_MENTION
    return SIT_UNMARKED


def predict_target(signals: dict[str, Any]) -> tuple[str, str | None]:
    """addressee baseline: reply→specific, groupForm→group, 그 외→none(약한 신호 미사용)."""
    reply_to = signals.get("replyTo")
    if reply_to and not signals.get("selfReply"):
        return "specific", str(reply_to)
    if signals.get("groupForm"):
        return "group", None
    return "none", None


def load_addressee_cases(path: Path) -> list[AddresseeCase]:
    data = _read(path)
    if data.get("schemaVersion") != ADDRESSEE_SCHEMA:
        raise ValueError(f"{path.name}: schemaVersion must be {ADDRESSEE_SCHEMA}")
    cases: list[AddresseeCase] = []
    for burst in data.get("bursts", []):
        signals = burst.get("signals") or {}
        if not isinstance(signals, dict):
            raise ValueError(f"{path.name}: {burst.get('burstId')}.signals must be a mapping")
        label = burst.get("label") or {}
        gold_kind = str(label.get("targetKind"))
        if gold_kind not in {"none", "group", "specific"}:
            raise ValueError(f"{path.name}: {burst.get('burstId')}.targetKind invalid: {gold_kind}")
        gold_target = label.get("target")
        pred_kind, pred_target = predict_target(signals)
        cases.append(
            AddresseeCase(
                burst_id=str(burst["burstId"]),
                situation=classify_situation(signals),
                gold_kind=gold_kind,
                gold_target=str(gold_target) if gold_target is not None else None,
                ambiguous=bool(label.get("ambiguous", False)),
                predicted_kind=pred_kind,
                predicted_target=str(pred_target) if pred_target is not None else None,
            )
        )
    if not cases:
        raise ValueError(f"{path.name}: no bursts")
    return cases


@dataclass(frozen=True)
class SceneCase:
    fixture_id: str
    # gold reply edges (source burst -> target burst) and predicted edges.
    gold_edges: set[tuple[str, str]]
    predicted_edges: set[tuple[str, str]]
    # gold thread clustering: burstId -> threadKey, plus all bursts in order.
    burst_ids: list[str]
    gold_thread_of: dict[str, str]
    pred_cluster_of: dict[str, str]


def _union_find_clusters(burst_ids: list[str], edges: set[tuple[str, str]]) -> dict[str, str]:
    """reply edge 로 burst 를 union-find 병합 → burstId -> 클러스터 대표 id."""
    parent = {b: b for b in burst_ids}

    def find(x: str) -> str:
        root = x
        while parent[root] != root:
            root = parent[root]
        while parent[x] != root:
            parent[x], x = root, parent[x]
        return root

    for src, dst in edges:
        if src in parent and dst in parent:
            parent[find(dst)] = find(src)
    return {b: find(b) for b in burst_ids}


def load_scene_case(path: Path) -> SceneCase:
    data = _read(path)
    if data.get("schemaVersion") != SCENE_SCHEMA:
        raise ValueError(f"{path.name}: schemaVersion must be {SCENE_SCHEMA}")
    bursts = data.get("bursts") or []
    burst_ids = [str(b["burstId"]) for b in bursts]
    # predicted reply edges: 명시된 replyTo 가 있으면 (source, target) edge(naive reply-only baseline).
    predicted_edges = {
        (str(b["burstId"]), str(b["replyTo"])) for b in bursts if b.get("replyTo")
    }
    expected = data.get("expected") or {}
    threads = expected.get("threads") or []
    gold_thread_of: dict[str, str] = {}
    gold_edges: set[tuple[str, str]] = set()
    for thread in threads:
        key = str(thread["threadKey"])
        members = [str(x) for x in thread.get("burstIds", [])]
        for bid in members:
            gold_thread_of[bid] = key
    # gold reply edges = fixture 의 replyTo (정답 그래프). baseline 과 같은 소스라 edge F1 은 baseline
    # 이 명시 reply 를 빠짐없이 잡는지(정밀도)와 누락 없는지(재현율)를 thread 동일성으로 교차검증한다:
    # gold edge 는 "같은 thread 의 인접 reply" 로 정의해 baseline reply edge 가 thread 정답과 일치하는지 본다.
    for b in bursts:
        if b.get("replyTo"):
            src, dst = str(b["burstId"]), str(b["replyTo"])
            if gold_thread_of.get(src) == gold_thread_of.get(dst) and src in gold_thread_of:
                gold_edges.add((src, dst))
    pred_cluster = _union_find_clusters(burst_ids, predicted_edges)
    return SceneCase(
        fixture_id=str(data["fixtureId"]),
        gold_edges=gold_edges,
        predicted_edges=predicted_edges,
        burst_ids=burst_ids,
        gold_thread_of=gold_thread_of,
        pred_cluster_of=pred_cluster,
    )


def _prf(tp: int, fp: int, fn: int) -> dict[str, float]:
    precision = tp / (tp + fp) if (tp + fp) else 1.0
    recall = tp / (tp + fn) if (tp + fn) else 1.0
    f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) else 0.0
    return {"precision": round(precision, 4), "recall": round(recall, 4), "f1": round(f1, 4)}


def edge_f1(scenes: list[SceneCase]) -> dict[str, Any]:
    tp = fp = fn = 0
    for s in scenes:
        tp += len(s.gold_edges & s.predicted_edges)
        fp += len(s.predicted_edges - s.gold_edges)
        fn += len(s.gold_edges - s.predicted_edges)
    return {"truePositive": tp, "falsePositive": fp, "falseNegative": fn, **_prf(tp, fp, fn)}


def thread_clustering_score(scenes: list[SceneCase]) -> dict[str, Any]:
    """pairwise clustering accuracy: 같은/다른 thread 정답을 예측 클러스터가 맞히는 쌍 비율."""
    correct = total = 0
    for s in scenes:
        labeled = [b for b in s.burst_ids if b in s.gold_thread_of]
        for a, b in combinations(labeled, 2):
            same_gold = s.gold_thread_of[a] == s.gold_thread_of[b]
            same_pred = s.pred_cluster_of[a] == s.pred_cluster_of[b]
            if same_gold == same_pred:
                correct += 1
            total += 1
    accuracy = correct / total if total else 1.0
    return {"pairs": total, "correctPairs": correct, "accuracy": round(accuracy, 4)}


def target_accuracy(cases: list[AddresseeCase]) -> dict[str, Any]:
    confident = [c for c in cases if not c.ambiguous]
    correct = sum(1 for c in confident if c.correct)
    ambiguous = len(cases) - len(confident)
    acc = correct / len(confident) if confident else 1.0
    # correction rate: confident 인데 baseline 이 틀린 비율(사후 교정 필요).
    corrections = sum(1 for c in confident if not c.correct)
    correction_rate = corrections / len(confident) if confident else 0.0
    return {
        "confidentCases": len(confident),
        "correct": correct,
        "ambiguousExcluded": ambiguous,
        "top1Accuracy": round(acc, 4),
        "correctionRate": round(correction_rate, 4),
    }


def per_situation(cases: list[AddresseeCase]) -> dict[str, Any]:
    """acceptance: reply/mention/무표식 상황별 target 오류 분해."""
    out: dict[str, Any] = {}
    for sit in (SIT_REPLY, SIT_MENTION, SIT_UNMARKED):
        subset = [c for c in cases if c.situation == sit]
        out[sit] = target_accuracy(subset) if subset else {
            "confidentCases": 0,
            "correct": 0,
            "ambiguousExcluded": 0,
            "top1Accuracy": 1.0,
            "correctionRate": 0.0,
        }
    return out


def build_report() -> dict[str, Any]:
    addressee_paths = sorted(SCENE_DIR.glob("addressee-*.yaml"))
    scene_paths = sorted(p for p in SCENE_DIR.glob("*.yaml") if not p.name.startswith("addressee-"))
    if not addressee_paths:
        raise ValueError(f"no addressee fixtures under {SCENE_DIR.relative_to(REPO_ROOT)}")
    if not scene_paths:
        raise ValueError(f"no scene fixtures under {SCENE_DIR.relative_to(REPO_ROOT)}")

    cases: list[AddresseeCase] = []
    for p in addressee_paths:
        cases.extend(load_addressee_cases(p))
    scenes = [load_scene_case(p) for p in scene_paths]

    return {
        "addresseeFixtures": [p.stem for p in addressee_paths],
        "sceneFixtures": [p.stem for p in scene_paths],
        "edgeF1": edge_f1(scenes),
        "threadClustering": thread_clustering_score(scenes),
        "targetOverall": target_accuracy(cases),
        "targetPerSituation": per_situation(cases),
    }


def print_table(report: dict[str, Any]) -> None:
    print("# 장면(thread·addressee) baseline 측정")
    print(f"addressee fixtures: {', '.join(report['addresseeFixtures'])}")
    print(f"scene fixtures: {', '.join(report['sceneFixtures'])}")
    print()
    e = report["edgeF1"]
    print("## edge F1 (reply edge baseline ↔ thread 정답)")
    print(f"precision={e['precision']:.3f} recall={e['recall']:.3f} F1={e['f1']:.3f} "
          f"(tp={e['truePositive']} fp={e['falsePositive']} fn={e['falseNegative']})")
    print()
    c = report["threadClustering"]
    print("## thread clustering score (pairwise)")
    print(f"accuracy={c['accuracy']:.3f} ({c['correctPairs']}/{c['pairs']} pairs)")
    print()
    t = report["targetOverall"]
    print("## target top-1 accuracy / correction rate")
    print(f"top1={t['top1Accuracy']:.3f} correctionRate={t['correctionRate']:.3f} "
          f"(confident={t['confidentCases']}, ambiguous excl={t['ambiguousExcluded']})")
    print()
    print("## 상황별 target 오류 (acceptance: reply/mention/무표식 분해)")
    print("| situation | top1 | correctionRate | confident | ambiguous excl |")
    print("| --- | ---: | ---: | ---: | ---: |")
    for sit, s in report["targetPerSituation"].items():
        print(f"| {sit} | {s['top1Accuracy']:.3f} | {s['correctionRate']:.3f} "
              f"| {s['confidentCases']} | {s['ambiguousExcluded']} |")
    print()


def main() -> int:
    parser = argparse.ArgumentParser(description="NEXA thread/addressee scene baseline evaluation")
    parser.add_argument("--json", action="store_true", help="JSON 출력")
    args = parser.parse_args()
    try:
        report = build_report()
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

"""학습 데이터 poisoning 탐지·격리(NEXA-P17-T019).

학습 dataset 으로 흘러드는 악의/이상 데이터 — 도배(flooding), coordinated mention,
비정상 반복(near-duplicate), bot-generated 신호 — 를 **결정론적** 규칙으로 탐지해 격리한다.
운영 데이터에 접근하지 않고 in-memory 레코드만 본다(stdlib 전용, torch·외부호출 없음).

**acceptance(T019) — 자동 탐지 결과를 사람 검토 없이 사용자 제재로 사용하지 않는다**:
- 탐지기는 의심 레코드를 *격리(quarantine)* 표시만 한다([PoisoningReport]). 사용자 차단·삭제·제재
  같은 처분 권한이 없다 — 다운스트림(사람 검토)이 결정한다. 이 모듈에 ban/suspend API 가 없다.
- 결과는 결정론적이다(같은 입력 → 같은 격리 집합). 무작위·시각 의존 없음.

탐지 규칙(모두 결정론):
- **flooding(도배)**: 한 actor 가 짧은 시간창에 임계 이상 이벤트를 쏟아냄.
- **coordinated mention**: 서로 다른 actor 다수가 같은 target 을 임계 이상 동시에 멘션.
- **near-duplicate repetition**: 같은 정규화 본문이 임계 이상 반복(여러 actor 라도).
- **bot-generated**: actor 가 bot 으로 표시됨(학습 부적격 — 사람 대화만 학습).
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from enum import StrEnum


class PoisoningKind(StrEnum):
    """poisoning 신호 종류(격리 사유 코드)."""

    FLOODING = "flooding"
    COORDINATED_MENTION = "coordinated_mention"
    NEAR_DUPLICATE = "near_duplicate"
    BOT_GENERATED = "bot_generated"


@dataclass(frozen=True)
class TrainingRecord:
    """학습 후보 레코드(합성/가명, 운영 PII 아님).

    poisoning 탐지에 필요한 최소 필드만 본다 — actor 가명, 시각(ms), 정규화 본문,
    멘션 target 가명, bot 여부. 원문 snowflake·실명은 담지 않는다(상류 minimizer 보장 전제).
    """

    record_id: str
    actor: str
    time_ms: int
    text: str
    mention_target: str | None = None
    is_bot: bool = False


@dataclass(frozen=True)
class QuarantineEntry:
    """격리 표시 1건 — record_id 와 사유. 제재 아님(사람 검토 대상)."""

    record_id: str
    kind: PoisoningKind
    detail: str


@dataclass
class PoisoningReport:
    """탐지 결과. 격리 표시만 담는다(처분 권한 없음 — acceptance T019)."""

    quarantined: list[QuarantineEntry] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        """poisoning 신호가 전혀 없는가."""
        return not self.quarantined

    @property
    def quarantined_ids(self) -> set[str]:
        """격리된 record_id 집합(중복 제거)."""
        return {e.record_id for e in self.quarantined}

    def mark(self, record_id: str, kind: PoisoningKind, detail: str) -> None:
        self.quarantined.append(QuarantineEntry(record_id=record_id, kind=kind, detail=detail))


@dataclass(frozen=True)
class PoisoningThresholds:
    """탐지 임계(결정론 파라미터). 운영 튜닝은 호출부에서 명시 주입."""

    flood_window_ms: int = 60_000
    flood_count: int = 5
    coordinated_actor_count: int = 3
    coordinated_window_ms: int = 120_000
    duplicate_count: int = 3


# 기본 임계 싱글톤(불변 frozen). 호출부가 thresholds 를 안 주면 이 값을 쓴다.
DEFAULT_THRESHOLDS = PoisoningThresholds()


def _normalize(text: str) -> str:
    """near-duplicate 비교용 정규화(소문자·공백 접기). 결정론."""
    return " ".join(text.lower().split())


def detect_flooding(
    records: list[TrainingRecord], thresholds: PoisoningThresholds
) -> list[QuarantineEntry]:
    """한 actor 가 [flood_window_ms] 안에 [flood_count] 이상 이벤트를 쏟으면 그 창의 레코드를 격리."""
    by_actor: dict[str, list[TrainingRecord]] = defaultdict(list)
    for r in records:
        by_actor[r.actor].append(r)

    entries: list[QuarantineEntry] = []
    for actor, items in by_actor.items():
        ordered = sorted(items, key=lambda r: (r.time_ms, r.record_id))
        start = 0
        flooded: set[str] = set()
        for end in range(len(ordered)):
            while ordered[end].time_ms - ordered[start].time_ms > thresholds.flood_window_ms:
                start += 1
            if end - start + 1 >= thresholds.flood_count:
                for i in range(start, end + 1):
                    flooded.add(ordered[i].record_id)
        for rid in sorted(flooded):
            entries.append(
                QuarantineEntry(
                    record_id=rid,
                    kind=PoisoningKind.FLOODING,
                    detail=f"actor {actor} flooding ≥{thresholds.flood_count}/{thresholds.flood_window_ms}ms",
                )
            )
    return entries


def detect_coordinated_mention(
    records: list[TrainingRecord], thresholds: PoisoningThresholds
) -> list[QuarantineEntry]:
    """서로 다른 actor [coordinated_actor_count] 이상이 같은 target 을 한 창 안에서 멘션하면 격리."""
    by_target: dict[str, list[TrainingRecord]] = defaultdict(list)
    for r in records:
        if r.mention_target is not None:
            by_target[r.mention_target].append(r)

    entries: list[QuarantineEntry] = []
    for target, items in by_target.items():
        ordered = sorted(items, key=lambda r: (r.time_ms, r.record_id))
        start = 0
        coordinated: set[str] = set()
        for end in range(len(ordered)):
            while ordered[end].time_ms - ordered[start].time_ms > thresholds.coordinated_window_ms:
                start += 1
            window = ordered[start : end + 1]
            distinct_actors = {w.actor for w in window}
            if len(distinct_actors) >= thresholds.coordinated_actor_count:
                for w in window:
                    coordinated.add(w.record_id)
        for rid in sorted(coordinated):
            entries.append(
                QuarantineEntry(
                    record_id=rid,
                    kind=PoisoningKind.COORDINATED_MENTION,
                    detail=f"target {target} mentioned by ≥{thresholds.coordinated_actor_count} actors",
                )
            )
    return entries


def detect_near_duplicate(
    records: list[TrainingRecord], thresholds: PoisoningThresholds
) -> list[QuarantineEntry]:
    """같은 정규화 본문이 [duplicate_count] 이상 반복되면(여러 actor 라도) 모두 격리."""
    by_norm: dict[str, list[TrainingRecord]] = defaultdict(list)
    for r in records:
        by_norm[_normalize(r.text)].append(r)

    entries: list[QuarantineEntry] = []
    for norm, items in by_norm.items():
        if norm and len(items) >= thresholds.duplicate_count:
            for r in sorted(items, key=lambda r: r.record_id):
                entries.append(
                    QuarantineEntry(
                        record_id=r.record_id,
                        kind=PoisoningKind.NEAR_DUPLICATE,
                        detail=f"repeated text ×{len(items)} ≥{thresholds.duplicate_count}",
                    )
                )
    return entries


def detect_bot_generated(records: list[TrainingRecord]) -> list[QuarantineEntry]:
    """bot actor 가 만든 레코드는 학습 부적격 — 격리(사람 대화만 학습)."""
    return [
        QuarantineEntry(
            record_id=r.record_id,
            kind=PoisoningKind.BOT_GENERATED,
            detail=f"actor {r.actor} is bot",
        )
        for r in sorted((r for r in records if r.is_bot), key=lambda r: r.record_id)
    ]


def scan_poisoning(
    records: list[TrainingRecord],
    thresholds: PoisoningThresholds = DEFAULT_THRESHOLDS,
) -> PoisoningReport:
    """모든 규칙을 적용해 격리 보고서를 만든다(결정론·격리만, 제재 없음 — acceptance T019)."""
    report = PoisoningReport()
    for entry in detect_flooding(records, thresholds):
        report.quarantined.append(entry)
    for entry in detect_coordinated_mention(records, thresholds):
        report.quarantined.append(entry)
    for entry in detect_near_duplicate(records, thresholds):
        report.quarantined.append(entry)
    for entry in detect_bot_generated(records):
        report.quarantined.append(entry)
    return report


def filter_clean(
    records: list[TrainingRecord],
    thresholds: PoisoningThresholds = DEFAULT_THRESHOLDS,
) -> tuple[list[TrainingRecord], PoisoningReport]:
    """격리된 레코드를 제외한 깨끗한 레코드와 보고서를 함께 돌려준다(학습 입력 전처리용)."""
    report = scan_poisoning(records, thresholds)
    bad = report.quarantined_ids
    clean = [r for r in records if r.record_id not in bad]
    return clean, report

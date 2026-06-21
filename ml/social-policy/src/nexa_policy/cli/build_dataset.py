"""재현 가능한 dataset builder CLI(NEXA-P10-T018).

config 파일 하나로 export→transform→split→manifest 를 실행한다.

**acceptance(T018) — 중간 산출물이 content-addressed 경로에 저장되고 실패 지점부터 재개 가능하다**:
- 각 stage(export/transform/split/manifest)의 출력은 입력+config 의 안정 해시 경로(content-addressed)에 쓴다.
- 같은 입력이면 같은 경로 → 이미 있으면 재계산을 건너뛴다(실패 지점부터 재개). 결정론.

**합성 fixture 전용**: 운영 DB 에 연결하지 않는다. export stage 입력은 이미 가명화된 [EventRecord]
fixture(JSON)다. 실제 사용자 원문은 들어오지 않으며, T015 redaction·T014 재가명화를 거쳐 export 된다.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from nexa_policy.data.export.boundary import ApprovedProjection, build_export_manifest
from nexa_policy.data.leakage import (
    LeakageReport,
    assert_no_leakage,
    check_group_leakage,
)
from nexa_policy.data.manifest import DatasetManifest, build_manifest
from nexa_policy.data.privacy import PseudonymPolicy, privatize_record
from nexa_policy.data.schema import EventRecord
from nexa_policy.data.split import Split, SplitRatios, assert_no_guild_leakage, split_by_guild
from nexa_policy.data.versioning import DatasetVersionInputs, stable_digest
from nexa_policy.reporting.dataset_card import render_dataset_card


class BuildError(ValueError):
    """builder config/stage 오류."""


@dataclass(frozen=True)
class BuildConfig:
    """builder config(파일 하나). 모든 재현 파라미터를 담는다."""

    fixture_path: str  # 합성 EventRecord JSON 입력(가명·신호만).
    source_watermark: str
    consent_snapshot_id: str
    code_commit: str
    purpose_salt: str
    output_dir: str
    seed: int = 0
    train: float = 0.7
    validation: float = 0.15
    test: float = 0.15

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> BuildConfig:
        required = (
            "fixture_path",
            "source_watermark",
            "consent_snapshot_id",
            "code_commit",
            "purpose_salt",
            "output_dir",
        )
        missing = [k for k in required if k not in payload]
        if missing:
            raise BuildError(f"config 필수 키 누락: {missing}")
        return cls(
            fixture_path=str(payload["fixture_path"]),
            source_watermark=str(payload["source_watermark"]),
            consent_snapshot_id=str(payload["consent_snapshot_id"]),
            code_commit=str(payload["code_commit"]),
            purpose_salt=str(payload["purpose_salt"]),
            output_dir=str(payload["output_dir"]),
            seed=int(payload.get("seed", 0)),
            train=float(payload.get("train", 0.7)),
            validation=float(payload.get("validation", 0.15)),
            test=float(payload.get("test", 0.15)),
        )

    def ratios(self) -> SplitRatios:
        return SplitRatios(train=self.train, validation=self.validation, test=self.test)

    def config_digest(self) -> str:
        """config 의 안정 해시(purpose_salt 제외 — 비밀은 ID 에 직접 넣지 않는다)."""
        return stable_digest(
            {
                "source_watermark": self.source_watermark,
                "consent_snapshot_id": self.consent_snapshot_id,
                "seed": self.seed,
                "train": self.train,
                "validation": self.validation,
                "test": self.test,
            }
        )


def _load_fixture_records(path: Path) -> list[EventRecord]:
    """합성 fixture JSON(가명·신호만)을 EventRecord 로 로드한다."""
    payload = json.loads(path.read_text(encoding="utf-8"))
    rows = payload["records"] if isinstance(payload, dict) else payload
    records: list[EventRecord] = []
    for row in rows:
        records.append(
            EventRecord(
                guild_pseudonym=row["guild_pseudonym"],
                channel_pseudonym=row["channel_pseudonym"],
                thread_pseudonym=row.get("thread_pseudonym"),
                event_id=row["event_id"],
                event_time_ms=int(row["event_time_ms"]),
                burst_id=row["burst_id"],
                scene_id=row["scene_id"],
                actor_pseudonym=row["actor_pseudonym"],
                event_kind=row["event_kind"],
                features=row.get("features", {}),
                masks=row.get("masks", {}),
                training_eligible=bool(row.get("training_eligible", False)),
            )
        )
    return records


def _content_path(output_dir: Path, stage: str, digest: str) -> Path:
    return output_dir / "stages" / stage / f"{digest}.json"


def _write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, sort_keys=True, ensure_ascii=False, indent=2), encoding="utf-8"
    )


@dataclass(frozen=True)
class BuildResult:
    dataset_id: str
    manifest: DatasetManifest
    card_path: Path
    manifest_path: Path
    split_counts: dict[str, int]


def run_build(config: BuildConfig) -> BuildResult:
    """export→transform→split→manifest 를 결정론·재개 가능하게 실행한다.

    각 stage 는 content-addressed 경로에 캐시되고, 이미 있으면 건너뛴다(실패 지점부터 재개).
    """
    output_dir = Path(config.output_dir)
    cfg_digest = config.config_digest()
    records = _load_fixture_records(Path(config.fixture_path))

    # --- stage 1: export(보안 경계 — eligibility 필터) ---
    projection = ApprovedProjection(
        source=config.source_watermark, records=tuple(records)
    )
    export_manifest = build_export_manifest(projection)
    eligible = list(export_manifest.eligible)
    export_digest = stable_digest(
        {"cfg": cfg_digest, "ids": sorted(r.event_id for r in eligible)}
    )
    export_path = _content_path(output_dir, "export", export_digest)
    if not export_path.exists():
        _write_json(export_path, {"event_ids": sorted(r.event_id for r in eligible)})

    # --- stage 2: transform(T014 재가명화 + T015 redaction) ---
    policy = PseudonymPolicy(purpose_salt=config.purpose_salt)
    transformed = [privatize_record(r, policy) for r in eligible]
    transform_digest = stable_digest(
        {"export": export_digest, "rows": [r.to_row() for r in transformed]}
    )
    transform_path = _content_path(output_dir, "transform", transform_digest)
    if not transform_path.exists():
        _write_json(transform_path, {"rows": [r.to_row() for r in transformed]})

    # --- stage 3: split(guild-level, 누출 방지) ---
    split_map = split_by_guild(
        transformed,
        guild_of=lambda r: r.guild_pseudonym,
        ratios=config.ratios(),
        seed=config.seed,
    )
    assert_no_guild_leakage(split_map, guild_of=lambda r: r.guild_pseudonym)
    # 누출 자동 검사(T023) — group leakage 가드 한 번 더.
    leak_report: LeakageReport = check_group_leakage(
        {s.value: rs for s, rs in split_map.items()},
        key_of=lambda r: r.guild_pseudonym,
        group_name="guild",
    )
    assert_no_leakage(leak_report)
    split_counts = {s.value: len(rs) for s, rs in split_map.items()}
    split_digest = stable_digest({"transform": transform_digest, "counts": split_counts})
    split_path = _content_path(output_dir, "split", split_digest)
    if not split_path.exists():
        _write_json(
            split_path,
            {
                s.value: sorted(r.event_id for r in rs) for s, rs in split_map.items()
            },
        )

    # --- stage 4: manifest + dataset card ---
    version_inputs = DatasetVersionInputs(
        source_watermark=config.source_watermark,
        schema_version=transformed[0].schema_version if transformed else 1,
        code_commit=config.code_commit,
        consent_snapshot_id=config.consent_snapshot_id,
        config_digest=cfg_digest,
    )
    exclusions = {
        "export_excluded": export_manifest.excluded_count,
        **dict(Counter({"empty_after_transform": 0})),
    }
    manifest = build_manifest(
        records=transformed,
        version_inputs=version_inputs,
        class_labels=[r.event_kind for r in transformed],
        exclusions=exclusions,
    )
    manifest_path = output_dir / "manifest.json"
    _write_json(manifest_path, manifest.to_dict())

    card_path = output_dir / "dataset-card.md"
    card_path.write_text(render_dataset_card(manifest), encoding="utf-8")

    return BuildResult(
        dataset_id=manifest.dataset_id,
        manifest=manifest,
        card_path=card_path,
        manifest_path=manifest_path,
        split_counts={Split(s).value: c for s, c in split_counts.items()},
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="NEXA social-policy dataset builder(합성 fixture 전용, 운영 데이터 미접근)."
    )
    parser.add_argument("--config", required=True, help="빌드 config JSON 경로")
    args = parser.parse_args(argv)

    config = BuildConfig.from_dict(json.loads(Path(args.config).read_text(encoding="utf-8")))
    result = run_build(config)
    print(f"dataset_id: {result.dataset_id}")
    print(f"manifest:   {result.manifest_path}")
    print(f"card:       {result.card_path}")
    print(f"splits:     {result.split_counts}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

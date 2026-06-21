"""T018 builder CLI·T019 fixture·T024 dataset card 테스트 — 재현·content-addressed·재개·PII 없음."""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

import pytest

from nexa_policy.cli.build_dataset import (
    BuildConfig,
    BuildError,
    run_build,
)
from nexa_policy.data.schema import _FORBIDDEN_COLUMN_SUBSTRINGS

FIXTURES = Path(__file__).resolve().parent / "fixtures"
SYNTHETIC = FIXTURES / "synthetic_dataset.json"


def _config(tmp_path: Path, **over: object) -> BuildConfig:
    base = json.loads((FIXTURES / "build_config.json").read_text(encoding="utf-8"))
    base["fixture_path"] = str(SYNTHETIC)
    base["output_dir"] = str(tmp_path / "out")
    base.update(over)
    base.pop("_note", None)
    return BuildConfig.from_dict(base)


# ---- T019: fixture 에 실제 PII 없음 ----
def test_fixture_no_pii() -> None:
    raw = SYNTHETIC.read_text(encoding="utf-8")
    payload = json.loads(raw)
    # URL·snowflake 형태 없음.
    assert "http://" not in raw and "https://" not in raw
    for record in payload["records"]:
        # 모든 가명은 합성 접두사만(actor-/guild-/chan-/thr-/ev-/b-/s-).
        assert record["actor_pseudonym"].startswith("actor-")
        assert record["guild_pseudonym"].startswith("guild-")
        # 금지 컬럼명이 features/masks 키에 없다(원문/식별자 흔적 없음).
        for key in list(record.get("features", {})) + list(record.get("masks", {})):
            for forbidden in _FORBIDDEN_COLUMN_SUBSTRINGS:
                assert forbidden not in key.lower(), f"금지 키 {key}"
        # 17~20자리 snowflake 형태의 가명 없음.
        for val in record.values():
            if isinstance(val, str):
                assert not (val.isdigit() and 17 <= len(val) <= 20)


def test_fixture_covers_all_action_and_kinds() -> None:
    payload = json.loads(SYNTHETIC.read_text(encoding="utf-8"))
    kinds = {r["event_kind"] for r in payload["records"]}
    # action/target 케이스 커버: message(speak)·reply·reaction(react)·mention(target).
    assert {"message", "reply", "reaction", "mention"} <= kinds


# ---- T018: 재현·content-addressed·재개 ----
def test_build_is_reproducible(tmp_path: Path) -> None:
    out_a = tmp_path / "a"
    out_b = tmp_path / "b"
    r1 = run_build(_config(tmp_path, output_dir=str(out_a)))
    r2 = run_build(_config(tmp_path, output_dir=str(out_b)))
    # 같은 입력·코드·config → 같은 dataset_id(T016/T018 재현).
    assert r1.dataset_id == r2.dataset_id
    assert r1.manifest.content_hash == r2.manifest.content_hash


def test_build_writes_manifest_and_card(tmp_path: Path) -> None:
    result = run_build(_config(tmp_path))
    assert result.manifest_path.exists()
    assert result.card_path.exists()
    manifest = json.loads(result.manifest_path.read_text(encoding="utf-8"))
    assert manifest["dataset_id"] == result.dataset_id
    assert manifest["row_count"] > 0
    assert manifest["guild_count"] >= 3


def test_build_stages_are_content_addressed(tmp_path: Path) -> None:
    result = run_build(_config(tmp_path))
    stages = Path(result.manifest_path).parent / "stages"
    # export/transform/split stage 산출물이 content-addressed 경로에 있다.
    assert (stages / "export").exists()
    assert (stages / "transform").exists()
    assert (stages / "split").exists()
    # 파일명이 해시(content address)다.
    for stage in ("export", "transform", "split"):
        files = list((stages / stage).glob("*.json"))
        assert files, f"{stage} 산출물 없음"
        assert all(len(f.stem) >= 16 for f in files)


def test_build_resumes_from_cached_stage(tmp_path: Path) -> None:
    config = _config(tmp_path)
    run_build(config)
    stages = Path(config.output_dir) / "stages"
    transform_files = list((stages / "transform").glob("*.json"))
    sentinel = transform_files[0]
    mtime_before = sentinel.stat().st_mtime_ns
    # 재실행: content-addressed 캐시가 있으면 기존 파일을 다시 쓰지 않는다(실패 지점부터 재개).
    run_build(config)
    assert sentinel.stat().st_mtime_ns == mtime_before


def test_build_output_has_no_raw_content(tmp_path: Path) -> None:
    result = run_build(_config(tmp_path))
    transform_dir = Path(result.manifest_path).parent / "stages" / "transform"
    blob = "".join(p.read_text(encoding="utf-8") for p in transform_dir.glob("*.json")).lower()
    for forbidden in ("message_text", "content", "username", "snowflake", "http"):
        assert forbidden not in blob


def test_build_repseudonymizes_actors(tmp_path: Path) -> None:
    result = run_build(_config(tmp_path))
    transform_dir = Path(result.manifest_path).parent / "stages" / "transform"
    blob = "".join(p.read_text(encoding="utf-8") for p in transform_dir.glob("*.json"))
    # 원본 합성 가명(actor-a1)이 transform 산출물에 그대로 남지 않는다(T014 재가명화).
    assert "actor-a1" not in blob
    assert "guild-alpha" not in blob


def test_missing_config_key_rejected() -> None:
    with pytest.raises(BuildError):
        BuildConfig.from_dict({"fixture_path": "x"})


# ---- T024: dataset card 한계 명시 + manifest 수치 자동 삽입 ----
def test_dataset_card_contains_manifest_numbers(tmp_path: Path) -> None:
    result = run_build(_config(tmp_path))
    card = result.card_path.read_text(encoding="utf-8")
    assert result.dataset_id in card
    assert str(result.manifest.row_count) in card
    assert str(result.manifest.guild_count) in card


def test_dataset_card_states_limitations_and_forbidden(tmp_path: Path) -> None:
    result = run_build(_config(tmp_path))
    card = result.card_path.read_text(encoding="utf-8")
    assert "제한 (Limitations)" in card
    assert "금지 추론" in card
    assert "삭제" in card
    assert "재현" in card
    assert "공개 불가" in card


def test_dataset_card_autoupdates_with_manifest(tmp_path: Path) -> None:
    from nexa_policy.reporting.dataset_card import render_dataset_card

    result = run_build(_config(tmp_path))
    # manifest row_count 를 바꾸면 카드 수치도 따라간다(수동 드리프트 없음).
    bumped = replace(result.manifest, row_count=result.manifest.row_count + 999)
    card = render_dataset_card(bumped)
    assert str(result.manifest.row_count + 999) in card

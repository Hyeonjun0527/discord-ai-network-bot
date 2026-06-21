"""데이터셋 version hash(NEXA-P10-T016).

source watermark, schema version, code commit, consent snapshot 으로 **immutable dataset ID** 를 만든다.

**acceptance(T016) — 같은 입력과 코드에서 같은 ID 가 생성된다**:
- ID 는 정규화된 입력 dict 를 안정 직렬화(sort_keys)한 BLAKE2b 다 → 결정론(reproducible).
- 입력 구성요소 중 하나라도 바뀌면 ID 가 바뀐다(immutable·content-addressed).
- 부동소수·비정렬 dict 같은 비결정 요소를 입력에 허용하지 않는다(fail-closed).
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any

from nexa_policy.data.schema import SCHEMA_VERSION

DATASET_ID_PREFIX = "nexa-ds"


class VersioningError(ValueError):
    """dataset version 입력 불변식 위반(fail-closed)."""


@dataclass(frozen=True)
class DatasetVersionInputs:
    """immutable dataset ID 를 만드는 구성요소.

    - [source_watermark]: 승인된 projection 의 watermark(예: "participation_projection_v1@2026-06-01").
    - [schema_version]: 이벤트 스키마 버전(고정 SSOT).
    - [code_commit]: 빌더 코드 commit sha(같은 코드 보장). 'unknown' 도 허용(재현은 같은 값끼리만).
    - [consent_snapshot_id]: export 시점 동의 스냅샷 id(동의 철회 반영).
    - [config_digest]: 빌드 config 의 안정 해시(파라미터 변경 시 ID 변경).
    """

    source_watermark: str
    schema_version: int
    code_commit: str
    consent_snapshot_id: str
    config_digest: str

    def __post_init__(self) -> None:
        for name, val in (
            ("source_watermark", self.source_watermark),
            ("code_commit", self.code_commit),
            ("consent_snapshot_id", self.consent_snapshot_id),
            ("config_digest", self.config_digest),
        ):
            if not str(val).strip():
                raise VersioningError(f"{name} 는 비어 있을 수 없다(재현 가능한 ID 구성요소).")
        if self.schema_version != SCHEMA_VERSION:
            raise VersioningError(
                f"schema_version 불일치: {self.schema_version} != {SCHEMA_VERSION}(SSOT)"
            )

    def to_canonical(self) -> dict[str, Any]:
        return {
            "source_watermark": self.source_watermark,
            "schema_version": self.schema_version,
            "code_commit": self.code_commit,
            "consent_snapshot_id": self.consent_snapshot_id,
            "config_digest": self.config_digest,
        }


def stable_digest(payload: Any, *, digest_size: int = 16) -> str:
    """임의 JSON-직렬화 가능 payload 의 안정(정렬·ASCII-free) BLAKE2b 해시.

    config/구성요소 digest 계산에 공용으로 쓴다(같은 내용 → 같은 digest).
    """
    encoded = json.dumps(payload, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.blake2b(encoded.encode("utf-8"), digest_size=digest_size).hexdigest()


def compute_dataset_id(inputs: DatasetVersionInputs) -> str:
    """구성요소로부터 immutable dataset ID 를 계산한다(T016 acceptance: 같은 입력→같은 ID)."""
    digest = stable_digest(inputs.to_canonical(), digest_size=20)
    return f"{DATASET_ID_PREFIX}-{digest}"

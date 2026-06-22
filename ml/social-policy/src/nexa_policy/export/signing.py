"""모델 artifact 서명·hash 검증(NEXA-P17-T020, ml 측).

학습·export 된 정책 bundle(ONNX 모델 + feature schema config + calibration)의 무결성을
**결정론적**으로 봉인·검증한다. 각 구성요소의 sha256 을 모은 manifest 를 만들고, 그 manifest 를
대칭키 HMAC-SHA256 으로 서명한다. 로드 시 (1) 각 파일 hash 가 manifest 와 일치하고 (2) manifest
서명이 유효해야 ACTIVE 자격이 생긴다 — 둘 중 하나라도 어긋나면 [ArtifactIntegrityError].

central(JVM registry, T020)이 같은 정의로 검증하도록 manifest 직렬화 형식을 SSOT 로 고정한다:
JSON, key 정렬, 컴포넌트별 `{name, sha256}` 목록. central 은 이 manifest+서명을 받아 같은
HMAC 으로 재검증하고, 변조 artifact 가 registry 에서 ACTIVE 가 되지 못하게 한다(acceptance T020).

stdlib 전용(hashlib·hmac·json) — torch·외부호출·네트워크 없음. 서명키는 호출부에서 명시 주입한다
(이 모듈은 키를 만들거나 보관하지 않는다 — 비밀 비저장).
"""

from __future__ import annotations

import hashlib
import hmac
import json
from dataclasses import dataclass
from pathlib import Path

# manifest 직렬화 형식 버전(JVM 과 공유하는 SSOT — 형식이 바뀌면 올린다).
MANIFEST_FORMAT_VERSION = 1

# HMAC 다이제스트 알고리즘(JVM HmacSHA256 과 일치).
SIGNATURE_ALGORITHM = "HmacSHA256"


class ArtifactIntegrityError(Exception):
    """artifact 무결성 위반(hash 불일치·서명 불일치·구성 누락). fail-closed 로 던진다."""


@dataclass(frozen=True)
class ComponentDigest:
    """bundle 한 구성요소의 무결성 항목 — 논리 이름과 sha256 hex."""

    name: str
    sha256: str

    def to_dict(self) -> dict[str, str]:
        return {"name": self.name, "sha256": self.sha256}


@dataclass(frozen=True)
class ArtifactManifest:
    """서명 대상 manifest — bundle 구성요소 digest 목록 + 모델 식별 메타.

    서명은 이 manifest 의 **정규 직렬화**([canonical_bytes]) 위에 만들어진다. central 이 같은
    정규형으로 재직렬화해 같은 HMAC 을 계산하므로, 컴포넌트 순서나 공백에 의존하지 않는다.
    """

    model_version: str
    components: tuple[ComponentDigest, ...]
    format_version: int = MANIFEST_FORMAT_VERSION

    def to_dict(self) -> dict[str, object]:
        # 컴포넌트는 name 으로 정렬해 직렬화 순서를 결정론으로 고정한다(JVM SSOT).
        ordered = sorted(self.components, key=lambda c: c.name)
        return {
            "formatVersion": self.format_version,
            "modelVersion": self.model_version,
            "components": [c.to_dict() for c in ordered],
        }

    def canonical_bytes(self) -> bytes:
        """서명·검증이 공유하는 정규 바이트(정렬·구분자 고정). 플랫폼 무관 결정론."""
        return json.dumps(
            self.to_dict(),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")

    def digest_of(self, name: str) -> str | None:
        """[name] 구성요소의 sha256(없으면 None)."""
        for c in self.components:
            if c.name == name:
                return c.sha256
        return None


@dataclass(frozen=True)
class SignedArtifact:
    """서명된 manifest — central 으로 넘기는 검증 단위. 서명은 hex(HMAC-SHA256)."""

    manifest: ArtifactManifest
    signature: str
    algorithm: str = SIGNATURE_ALGORITHM

    def to_dict(self) -> dict[str, object]:
        return {
            "manifest": self.manifest.to_dict(),
            "signature": self.signature,
            "algorithm": self.algorithm,
        }


def sha256_of_file(path: Path) -> str:
    """파일 내용의 sha256 hex(스트리밍 — 큰 파일도 메모리 한 번에 안 올림)."""
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def build_manifest(
    model_version: str,
    component_paths: dict[str, Path],
) -> ArtifactManifest:
    """bundle 구성요소들의 sha256 을 계산해 manifest 를 만든다(hash 수동 입력 금지).

    [component_paths] 는 논리 이름→파일 경로(예: {"model": onnx, "config": cfg, "calibration": cal}).
    파일이 없으면 [ArtifactIntegrityError](봉인 대상 누락은 무결성 실패).
    """
    if not component_paths:
        raise ArtifactIntegrityError("서명할 구성요소가 없다(빈 bundle)")
    components: list[ComponentDigest] = []
    for name, path in component_paths.items():
        if not path.is_file():
            raise ArtifactIntegrityError(f"구성요소 파일이 없다: {name} -> {path}")
        components.append(ComponentDigest(name=name, sha256=sha256_of_file(path)))
    return ArtifactManifest(model_version=model_version, components=tuple(components))


def sign_manifest(manifest: ArtifactManifest, signing_key: bytes) -> SignedArtifact:
    """manifest 정규형을 HMAC-SHA256 으로 서명한다(키는 호출부 주입 — 이 모듈은 키 비보관)."""
    if not signing_key:
        raise ArtifactIntegrityError("서명키가 비어 있다(fail-closed)")
    signature = hmac.new(signing_key, manifest.canonical_bytes(), hashlib.sha256).hexdigest()
    return SignedArtifact(manifest=manifest, signature=signature)


def sign_artifact(
    model_version: str,
    component_paths: dict[str, Path],
    signing_key: bytes,
) -> SignedArtifact:
    """bundle 의 manifest 를 만들고 서명한다(build_manifest + sign_manifest)."""
    return sign_manifest(build_manifest(model_version, component_paths), signing_key)


def verify_signature(signed: SignedArtifact, signing_key: bytes) -> None:
    """manifest 서명이 [signing_key] 로 유효한지 상수시간 비교로 검증한다.

    위조·변조된 manifest 면 [ArtifactIntegrityError](서명 불일치). 일치하면 조용히 통과.
    """
    if not signing_key:
        raise ArtifactIntegrityError("서명키가 비어 있다(fail-closed)")
    expected = hmac.new(signing_key, signed.manifest.canonical_bytes(), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, signed.signature):
        raise ArtifactIntegrityError("manifest 서명 불일치(변조 또는 잘못된 키) — ACTIVE 자격 없음")


def verify_artifact(
    signed: SignedArtifact,
    component_paths: dict[str, Path],
    signing_key: bytes,
) -> None:
    """로드 시 전체 무결성 검증 — 서명 + 각 파일 hash 일치(둘 다 통과해야 ACTIVE 자격).

    1) [verify_signature]: manifest 가 서명키로 봉인됐는가(manifest 변조 차단).
    2) 각 구성요소의 현재 파일 sha256 이 manifest digest 와 일치하는가(파일 swap·변조 차단).
    3) manifest 가 가리키는 구성요소가 모두 제공됐는가(누락 차단).

    하나라도 어긋나면 [ArtifactIntegrityError] — 변조 artifact 가 ACTIVE 가 되지 못한다(acceptance T020).
    """
    verify_signature(signed, signing_key)

    manifest_names = {c.name for c in signed.manifest.components}
    provided_names = set(component_paths.keys())
    if manifest_names != provided_names:
        raise ArtifactIntegrityError(
            f"구성요소 집합 불일치: manifest={sorted(manifest_names)} provided={sorted(provided_names)}"
        )

    for name, path in component_paths.items():
        expected = signed.manifest.digest_of(name)
        if not path.is_file():
            raise ArtifactIntegrityError(f"구성요소 파일이 없다: {name} -> {path}")
        actual = sha256_of_file(path)
        if expected != actual:
            raise ArtifactIntegrityError(
                f"구성요소 hash 불일치(변조): {name} — ACTIVE 자격 없음"
            )

#!/usr/bin/env python3
"""기획 명세(5분서) ID 정합 검증 스크립트.

원칙
----
- MD = 기획 원문(SSOT). 이 스크립트는 MD 를 절대 수정하지 않는다(읽기 전용).
- manifest.yaml = 문서/네임스페이스 메타. traceability.yaml = REQ↔하위 ID 연결.
- 각 MD 에서 자기 네임스페이스 ID 를 정규식으로 추출해 "선언 집합"을 만들고,
  traceability 의 모든 참조가 그 선언 집합 안에 있는지(깨진 참조 0) 검사한다.

검사 항목
---------
1. traceability 의 모든 참조 ID 가 소유 문서의 선언 집합에 존재하는가(깨진 참조).
2. 중복 정의 ID(같은 ID 가 정의 위치에 2회 이상) 탐지.
3. 모든 REQ 가 traceability 에 연결됐는가(미연결 목록).
4. screens.md 가 인용하는 API-* 가 api.md 선언 집합에 존재하는가(교차 검증).

종료 코드: 에러 0 → 0, 에러 ≥ 1 → 1.

표준 라이브러리 + PyYAML. PyYAML 미설치 시 manifest/traceability 전용 최소 라인 파서로
fallback 한다(두 YAML 의 구조가 단순·고정이므로 가능).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Any

# ── 경로 상수 ──────────────────────────────────────────────────────────
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
DOMAIN_DIR = (
    REPO_ROOT
    / "specs"
    / "product-v2"
    / "domains"
    / "community-provider-pool"
)
MANIFEST = DOMAIN_DIR / "manifest.yaml"
TRACEABILITY = DOMAIN_DIR / "traceability.yaml"

# ── ID 추출 정규식(네임스페이스별) ─────────────────────────────────────
# 핵심: "xxx 대역 라벨"(SCR-3xx)·템플릿(API-REST-<영역>-<동작>)을 배제하기 위해
# 숫자 ID 는 자리수를 고정하고, 영문 ID 는 마지막 글자가 영숫자로 끝나도록 강제한다.
ID_PATTERNS: dict[str, re.Pattern[str]] = {
    "REQ": re.compile(r"\bREQ-\d{3}\b"),
    "SCN": re.compile(r"\bSCN-\d{2}\b"),
    "SCR": re.compile(r"\bSCR-\d{3}\b"),
    "FLOW": re.compile(r"\bFLOW-\d{2}(?:\.\d+)?\b"),
    "DM-E": re.compile(r"\bDM-E-[A-Za-z][A-Za-z0-9]*\b"),
    "DM-V": re.compile(r"\bDM-V-[A-Za-z][A-Za-z0-9]*\b"),
    "DM-S": re.compile(r"\bDM-S-[A-Za-z][A-Za-z0-9]*\b"),
    "DM-R": re.compile(r"\bDM-R-[0-9]+[a-z]?\b"),
    "DM-EV": re.compile(r"\bDM-EV-[A-Za-z][A-Za-z0-9]*\b"),
    "API-CMD": re.compile(r"\bAPI-CMD-[A-Z][A-Z0-9-]*[A-Z0-9]\b"),
    "API-REST": re.compile(r"\bAPI-REST-[A-Z][A-Z0-9-]*[A-Z0-9]\b"),
    "API-WS": re.compile(r"\bAPI-WS-[A-Z][A-Z0-9-]*[A-Z0-9]\b"),
    "API-INT": re.compile(r"\bAPI-INT-[A-Z][A-Z0-9-]*[A-Z0-9]\b"),
    "ERR": re.compile(r"\bERR-[A-Z][A-Z0-9-]*[A-Z0-9]\b"),
}

# prefix 가 다른 prefix 의 부분 문자열이라 충돌하므로(DM-E ⊂ DM-EV, API-* 공통),
# "가장 긴 매칭 prefix"를 우선 적용하기 위한 정렬 순서.
PREFIX_BY_LENGTH = sorted(ID_PATTERNS.keys(), key=len, reverse=True)


# ── YAML 로딩(PyYAML 우선, 없으면 fallback) ────────────────────────────
def load_yaml(path: Path) -> Any:
    text = path.read_text(encoding="utf-8")
    try:
        import yaml  # type: ignore

        return yaml.safe_load(text)
    except ImportError:
        sys.stderr.write(
            "[warn] PyYAML 미설치 — 최소 라인 파서로 fallback 합니다.\n"
        )
        return _fallback_parse(path.name, text)


def _fallback_parse(filename: str, text: str) -> Any:
    """manifest.yaml / traceability.yaml 의 고정 구조 전용 최소 파서.

    범용 YAML 파서가 아니다. 두 파일의 알려진 형태만 처리한다.
    """
    if filename == "manifest.yaml":
        return _fallback_manifest(text)
    if filename == "traceability.yaml":
        return _fallback_traceability(text)
    raise RuntimeError(f"fallback 파서가 지원하지 않는 파일: {filename}")


def _inline_list(value: str) -> list[str]:
    value = value.strip()
    if value.startswith("["):
        # 대괄호 안쪽만 취한다(뒤에 붙은 `# 주석` 무시).
        close = value.find("]")
        inner = value[1 : close if close != -1 else len(value)].strip()
        if not inner:
            return []
        return [v.strip().strip("'\"") for v in inner.split(",") if v.strip()]
    # 인라인 스칼라: `#` 주석 제거 후 따옴표 제거.
    scalar = value.split("#", 1)[0].strip().strip("'\"")
    return [scalar] if scalar else []


def _fallback_manifest(text: str) -> dict[str, Any]:
    documents: list[dict[str, Any]] = []
    id_namespaces: dict[str, str] = {}
    section: str | None = None
    cur_doc: dict[str, Any] | None = None
    for raw in text.splitlines():
        line = raw.rstrip()
        if not line or line.lstrip().startswith("#"):
            continue
        stripped = line.strip()
        if line.startswith("documents:"):
            section = "documents"
            continue
        if line.startswith("id_namespaces:"):
            section = "id_namespaces"
            continue
        if re.match(r"^[a-z_]+:", line) and not line.startswith(" "):
            section = None  # 다른 최상위 키
            continue
        if section == "documents":
            m = re.match(r"\s*-\s*path:\s*(.+)$", line)
            if m:
                cur_doc = {"path": m.group(1).strip()}
                documents.append(cur_doc)
                continue
            m = re.match(r"\s*id_prefix:\s*(.+)$", line)
            if m and cur_doc is not None:
                cur_doc["id_prefix"] = _inline_list(m.group(1))
                continue
        if section == "id_namespaces":
            m = re.match(r"\s*([A-Za-z0-9-]+):\s*(.+)$", line)
            if m:
                id_namespaces[m.group(1)] = m.group(2).strip()
    return {"documents": documents, "id_namespaces": id_namespaces}


def _fallback_traceability(text: str) -> dict[str, Any]:
    entries: list[dict[str, Any]] = []
    cur: dict[str, Any] | None = None
    list_keys = (
        "domain_models",
        "screens",
        "flows",
        "apis",
        "errors",
    )
    for raw in text.splitlines():
        line = raw.rstrip()
        if not line or line.lstrip().startswith("#"):
            continue
        m = re.match(r"\s*-\s*requirement:\s*(.+)$", line)
        if m:
            cur = {"requirement": m.group(1).strip()}
            entries.append(cur)
            continue
        if cur is None:
            continue
        m = re.match(r"\s*title:\s*(.+)$", line)
        if m:
            cur["title"] = m.group(1).strip()
            continue
        for key in list_keys:
            m = re.match(rf"\s*{key}:\s*(.*)$", line)
            if m:
                cur[key] = _inline_list(m.group(1))
                break
    return {"links": entries}


# ── ID 추출 ────────────────────────────────────────────────────────────
def classify(token: str) -> str | None:
    """ID 문자열을 가장 긴 매칭 prefix 네임스페이스로 분류."""
    for prefix in PREFIX_BY_LENGTH:
        if ID_PATTERNS[prefix].fullmatch(token):
            return prefix
    return None


def extract_declared(md_text: str, prefixes: list[str]) -> dict[str, set[str]]:
    """문서 본문에서 주어진 prefix 들의 선언 ID 집합을 추출."""
    result: dict[str, set[str]] = {p: set() for p in prefixes}
    for prefix in prefixes:
        for m in ID_PATTERNS[prefix].finditer(md_text):
            token = m.group(0)
            # 충돌 방지: 토큰이 실제로 이 prefix 로 분류되는 것만 채택
            # (예: DM-EV-* 토큰이 DM-E 패턴에 잡히지 않도록 classify 로 재확인)
            if classify(token) == prefix:
                result[prefix].add(token)
    return result


def extract_definition_lines(md_text: str, prefixes: list[str]) -> dict[str, list[str]]:
    """중복 정의 탐지를 위해 '정의 anchor'(canonical heading)의 ID 출현만 수집.

    정의 anchor 판별(MD 의 실제 작성 규약 기반):
      - 백틱 네임스페이스(SCR/DM-*/API-*/REQ/SCN/ERR): heading 안에서 **백틱으로 감싼**
        ID 만 정의로 본다(예: `### 3.1 Embed — `SCR-301``). 본문/표/인덱스의 비백틱
        인용은 제외 → 동일 ID 가 cross-reference heading 에 다시 나와도 오탐 안 함.
      - FLOW: 백틱을 쓰지 않으므로 **heading 의 선두 토큰**(`### FLOW-xx ...`)만 정의로
        본다. `### 8.13 ... — FLOW-09`(번호 선두) 같은 인덱스 heading 은 제외.
    그룹 라벨(`API-REST-GUILD-*` 의 `*` 형태)은 정의가 아니므로 자동 제외된다
    (정규식이 trailing alphanumeric 을 요구해 `-*` 직전 토큰은 잡지만, 아래에서
    바로 뒤가 `-*` 인 경우를 추가로 걸러낸다).
    """
    occurrences: dict[str, list[str]] = {p: [] for p in prefixes}
    for raw in md_text.splitlines():
        if not raw.lstrip().startswith("#"):
            continue
        # heading 본문(앞쪽 #들 제거)
        heading_body = raw.lstrip("#").strip()
        for prefix in prefixes:
            if prefix == "FLOW":
                # 선두 토큰이 FLOW-ID 인 heading 만 정의
                m = re.match(r"(FLOW-\d{2}(?:\.\d+)?)\b", heading_body)
                if m and classify(m.group(1)) == "FLOW":
                    occurrences[prefix].append(m.group(1))
                continue
            # 백틱으로 감싼 ID 만 정의로 채택
            for m in ID_PATTERNS[prefix].finditer(raw):
                token = m.group(0)
                if classify(token) != prefix:
                    continue
                start, end = m.span()
                # 그룹 라벨 제외: 바로 뒤가 `-*`
                if raw[end : end + 2] == "-*":
                    continue
                # 백틱 감쌈 여부: 토큰 양옆이 ` 인지
                left_bt = start > 0 and raw[start - 1] == "`"
                right_bt = end < len(raw) and raw[end] == "`"
                if left_bt and right_bt:
                    occurrences[prefix].append(token)
    return occurrences


# ── 메인 검증 ──────────────────────────────────────────────────────────
def main() -> int:
    errors: list[str] = []
    warnings: list[str] = []

    if not MANIFEST.exists():
        sys.stderr.write(f"[fatal] manifest 없음: {MANIFEST}\n")
        return 1

    manifest = load_yaml(MANIFEST)
    id_namespaces: dict[str, str] = manifest.get("id_namespaces", {})
    documents: list[dict[str, Any]] = manifest.get("documents", [])

    # 문서별 선언 집합 구축
    declared: dict[str, set[str]] = {}  # prefix -> 선언 ID 집합
    owner_doc: dict[str, str] = {}      # prefix -> 소유 MD 경로(상대)
    dup_errors: list[str] = []

    for doc in documents:
        path = DOMAIN_DIR / doc["path"]
        prefixes = doc.get("id_prefix", [])
        if not path.exists():
            errors.append(f"문서 누락: {doc['path']}")
            continue
        text = path.read_text(encoding="utf-8")
        decl = extract_declared(text, prefixes)
        defs = extract_definition_lines(text, prefixes)
        for prefix in prefixes:
            declared[prefix] = decl[prefix]
            owner_doc[prefix] = doc["path"]
            # 중복 정의(heading anchor 에 같은 ID 2회 이상).
            # 단, DM-S(상태 머신)는 하나의 상태 집합을 '주 정의표 + 부분-흐름 뷰'로
            # 나눠 같은 ID 를 여러 sub-heading 에 anchor 하는 것이 정상이다
            # (예: §6.4 DM-S-RequestState 주표 + §6.5 routing 부분 흐름). 이 경우는
            # 하드 에러가 아니라 경고로 다룬다(MD 의 의도된 구조, 단일 정의로 간주).
            seen: dict[str, int] = {}
            for tok in defs[prefix]:
                seen[tok] = seen.get(tok, 0) + 1
            for tok, cnt in seen.items():
                if cnt > 1:
                    msg = f"{tok} (heading anchor {cnt}회, {doc['path']})"
                    if prefix == "DM-S":
                        warnings.append(
                            f"상태 머신 다중 뷰(정상, 단일 정의 간주): {msg}"
                        )
                    else:
                        dup_errors.append(msg)

    # prefix -> 그 prefix 로 시작하는 선언 ID 전체(참조 해석용 인덱스)
    def is_declared(token: str) -> tuple[bool, str | None]:
        prefix = classify(token)
        if prefix is None:
            return (False, None)
        return (token in declared.get(prefix, set()), prefix)

    # traceability 로드 및 참조 검사
    if not TRACEABILITY.exists():
        errors.append(f"traceability 없음: {TRACEABILITY}")
        trace = {"links": []}
    else:
        trace = load_yaml(TRACEABILITY)

    links: list[dict[str, Any]] = trace.get("links", []) or []
    linked_reqs: set[str] = set()
    broken_refs: list[str] = []

    ref_fields = ("domain_models", "screens", "flows", "apis", "errors")
    for entry in links:
        req = entry.get("requirement", "")
        if not req:
            errors.append("requirement 키 없는 traceability 항목 존재")
            continue
        # requirement 자체가 선언된 REQ 인지
        ok, prefix = is_declared(req)
        if not ok or prefix != "REQ":
            broken_refs.append(f"{req} (requirement, 미선언)")
        else:
            linked_reqs.add(req)
        # 하위 참조 ID 검사
        for field in ref_fields:
            for token in entry.get(field, []) or []:
                ok, _ = is_declared(token)
                if not ok:
                    broken_refs.append(f"{token} ({field} of {req})")

    # 모든 REQ 가 연결됐는가
    all_reqs = declared.get("REQ", set())
    unlinked = sorted(all_reqs - linked_reqs)

    # screens.md → api.md 교차 검증: screens 가 인용한 API-* 가 api.md 에 있는가
    cross_missing: list[str] = []
    screens_path = DOMAIN_DIR / "screens.md"
    if screens_path.exists():
        screens_text = screens_path.read_text(encoding="utf-8")
        api_prefixes = ["API-CMD", "API-REST", "API-WS", "API-INT"]
        cited = extract_declared(screens_text, api_prefixes)
        for prefix in api_prefixes:
            defined = declared.get(prefix, set())
            for token in sorted(cited[prefix]):
                if token in defined:
                    continue
                # 그룹/약식 인용 허용: 정의된 concrete ID 의 prefix 면 유효한 인용으로 간주
                # (예: screens 의 `API-WS-AUTH` ↔ api.md 의 API-WS-AUTH-OK/-ERR 패밀리).
                if any(d.startswith(token + "-") for d in defined):
                    continue
                cross_missing.append(f"{token} (screens.md 인용, api.md 미정의)")

    # ── 결과 출력 ──────────────────────────────────────────────────────
    print("=" * 64)
    print(" 기획 명세 ID 정합 검증 — community-provider-pool")
    print("=" * 64)
    print("\n[1] 선언된 ID (prefix 별 개수)")
    for prefix in sorted(declared, key=lambda p: (id_namespaces.get(p, ""), p)):
        print(f"    {prefix:9s} : {len(declared[prefix]):3d}  ({owner_doc.get(prefix, '?')})")

    print(f"\n[2] traceability 항목 수 : {len(links)}")
    print(f"    연결된 REQ          : {len(linked_reqs)} / {len(all_reqs)}")

    print("\n[3] 중복 정의 검사")
    if dup_errors:
        for d in dup_errors:
            print(f"    [ERR] 중복 정의: {d}")
        errors.extend(f"중복 정의: {d}" for d in dup_errors)
    else:
        print("    OK — 중복 정의 없음")

    print("\n[4] traceability 깨진 참조 검사")
    if broken_refs:
        for b in broken_refs:
            print(f"    [ERR] 깨진 참조: {b}")
        errors.extend(f"깨진 참조: {b}" for b in broken_refs)
    else:
        print("    OK — 깨진 참조 없음")

    print("\n[5] 미연결 REQ 검사")
    if unlinked:
        for r in unlinked:
            print(f"    [ERR] 미연결 REQ: {r}")
        errors.extend(f"미연결 REQ: {r}" for r in unlinked)
    else:
        print("    OK — 모든 REQ 가 연결됨")

    print("\n[6] screens.md → api.md API 교차 검증")
    if cross_missing:
        for c in cross_missing:
            print(f"    [ERR] {c}")
        errors.extend(f"교차참조 누락: {c}" for c in cross_missing)
    else:
        print("    OK — screens 인용 API 가 모두 api.md 에 존재")

    if warnings:
        print("\n[warn]")
        for w in warnings:
            print(f"    {w}")

    print("\n" + "=" * 64)
    if errors:
        print(f" 결과: 실패 — 에러 {len(errors)}건")
        print("=" * 64)
        return 1
    print(" 결과: 성공 — 에러 0건")
    print("=" * 64)
    return 0


if __name__ == "__main__":
    sys.exit(main())

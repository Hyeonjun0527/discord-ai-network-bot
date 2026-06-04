#!/usr/bin/env python3
"""WS 와이어 계약 코드 생성기 — 단일 SSOT(`protocol/wire-contract.json`)에서
Kotlin(central-server)·Python(provider-agent)의 공유 상수 파일을 생성한다.

이전에는 같은 상수(PROTOCOL_VERSION·FrameType·ErrorCode·ALLOWED_OPTION_KEYS 등)가 두 언어에
손으로 미러링돼 drift 위험이 있었다. 이제 이 스크립트가 양쪽을 한곳에서 생성한다.

사용법:
  python scripts/gen_wire_contract.py          # 생성(파일 갱신)
  python scripts/gen_wire_contract.py --check   # 생성 결과가 최신인지 검증(드리프트 시 exit 1)
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC_PATH = ROOT / "protocol" / "wire-contract.json"
KOTLIN_OUT = (
    ROOT
    / "central-server/src/main/kotlin/com/discordassistant/central/relay/protocol/WireContractGenerated.kt"
)
PYTHON_OUT = ROOT / "provider-agent/src/provider_agent/_wire_contract_generated.py"

GEN_NOTE = "DO NOT EDIT — `protocol/wire-contract.json` 에서 `scripts/gen_wire_contract.py` 로 생성됨."


def load_spec() -> dict:
    return json.loads(SPEC_PATH.read_text(encoding="utf-8"))


def render_kotlin(spec: dict) -> str:
    lines: list[str] = [
        "package com.discordassistant.central.relay.protocol",
        "",
        f"// {GEN_NOTE}",
        "",
        "/** 프로토콜 버전. 핸드셰이크에서 협상하며 major 가 다르면 비호환. */",
        f'const val PROTOCOL_VERSION: String = "{spec["protocolVersion"]}"',
        "",
        "/** 단일 프레임 최대 직렬화 크기(바이트). */",
        f'const val MAX_FRAME_BYTES: Int = {spec["maxFrameBytes"]}',
        "",
        "/** 프롬프트 최대 길이(문자). */",
        f'const val MAX_PROMPT_CHARS: Int = {spec["maxPromptChars"]}',
        "",
        "/** WS 프레임 type 값 (specs api.md §8, ADR 0002). */",
        "object FrameType {",
    ]
    for name, value in spec["frameTypes"].items():
        lines.append(f'    const val {name} = "{value}"')
    lines += ["}", "", "/** error 프레임 코드. */", "object ErrorCode {"]
    for name, value in spec["errorCodes"].items():
        lines.append(f'    const val {name} = "{value}"')
    lines += [
        "}",
        "",
        "/** 추론 옵션 화이트리스트. relay 가 outbound InferRequest 를 만들 때 적용한다. */",
        "val ALLOWED_OPTION_KEYS: Set<String> =",
        "    setOf(",
    ]
    for key in spec["allowedOptionKeys"]:
        lines.append(f'        "{key}",')
    lines += ["    )", ""]
    return "\n".join(lines)


def render_python(spec: dict) -> str:
    lines: list[str] = [
        f'"""{GEN_NOTE}"""',
        "from __future__ import annotations",
        "",
        "from typing import Final",
        "",
        f'PROTOCOL_VERSION: Final[str] = "{spec["protocolVersion"]}"',
        f'MAX_FRAME_BYTES: Final[int] = {spec["maxFrameBytes"]}',
        f'MAX_PROMPT_CHARS: Final[int] = {spec["maxPromptChars"]}',
        "",
        "",
        "class FrameType:",
        '    """WS 프레임 type 값(중앙 서버 FrameType 과 동일)."""',
        "",
    ]
    for name, value in spec["frameTypes"].items():
        lines.append(f'    {name}: Final[str] = "{value}"')
    lines += ["", "", "class ErrorCode:", '    """error 프레임 코드(중앙 서버 ErrorCode 와 동일)."""', ""]
    for name, value in spec["errorCodes"].items():
        lines.append(f'    {name}: Final[str] = "{value}"')
    keys = ", ".join(f'"{k}"' for k in spec["allowedOptionKeys"])
    lines += [
        "",
        "",
        "ALLOWED_OPTION_KEYS: Final[frozenset[str]] = frozenset(",
        f"    {{{keys}}}",
        ")",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    spec = load_spec()
    targets = {KOTLIN_OUT: render_kotlin(spec), PYTHON_OUT: render_python(spec)}
    check = "--check" in sys.argv[1:]
    drifted = False
    for path, content in targets.items():
        current = path.read_text(encoding="utf-8") if path.exists() else None
        if current == content:
            continue
        if check:
            drifted = True
            print(f"DRIFT: {path.relative_to(ROOT)} 가 SSOT 와 다릅니다. `python scripts/gen_wire_contract.py` 실행 필요.")
        else:
            path.write_text(content, encoding="utf-8")
            print(f"generated: {path.relative_to(ROOT)}")
    if check and drifted:
        return 1
    if check:
        print("wire-contract 생성물 최신 상태 OK.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

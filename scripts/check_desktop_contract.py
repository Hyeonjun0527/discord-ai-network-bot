#!/usr/bin/env python3
"""데스크톱 앱 프로토타입 ↔ 실구현(provider-agent webui) **계약 드리프트 가드**.

프로토타입(prototypes/desktop)은 디자인/UX 의 SSOT 다. 화면·CSS·플로우는 같은 파일을
sync 하므로 절대 갈라지지 않는다. 그러나 **프론트(JS) ↔ 백엔드(Python webui) 계약**
(HTTP 엔드포인트 경로·응답 shape)은 `contract.js` 가 문서(JSDoc)일 뿐 강제되지 않아
조용히 갈라질 수 있다(예: adapter 가 `/api/servers/{g}/pause` 를 부르는데 webui 엔 라우트
없음 → 프로토타입 mock 은 멀쩡, 실 앱만 404).

이 스크립트는 그 드리프트를 빌드/검증 단계에서 잡는다:

  1) **엔드포인트 경로 일치**: adapter.js 가 **실제로 호출**하는(ENDPOINTS.<name> 참조) 모든
     계약 경로가 webui 의 등록 라우트에 존재하는지(세그먼트 패턴 매칭, {param} 은 와일드카드).
  2) **생성물 누수/문법(선택, --assets)**: sync 산출물 webui_assets 에 프로토타입 전용
     mock/데모(@proto-only·MOCK·USE_MOCK=true·#proto)가 남지 않았는지.

응답 shape(필드명) 일치는 정적으로 완전 보장하기 어렵다 — 그 층은 provider-agent pytest
(webui 응답 키)와 실 앱 헤드리스 스모크로 보강한다(README/AGENTS 참고).

  python3 scripts/check_desktop_contract.py            # 계약 경로 검사
  python3 scripts/check_desktop_contract.py --assets   # + 생성물 누수 검사(sync 후)
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "prototypes" / "desktop" / "contract.js"
ADAPTER = ROOT / "prototypes" / "desktop" / "adapter.js"
ASSETS = ROOT / "provider-agent" / "src" / "provider_agent" / "webui_assets"


def parse_contract_endpoints() -> dict[str, str]:
    """contract.js 의 ENDPOINTS 항목을 {name: path-template} 로 파싱. {param} 은 와일드카드 표기 {id}."""
    text = CONTRACT.read_text(encoding="utf-8")
    m = re.search(r"export const ENDPOINTS\s*=\s*Object\.freeze\(\{(.*?)\}\);", text, re.DOTALL)
    if not m:
        raise SystemExit("contract.js 에서 ENDPOINTS 블록을 찾지 못했습니다(구조 변경?).")
    body = m.group(1)
    out: dict[str, str] = {}
    for raw in body.splitlines():
        line = re.sub(r"//.*$", "", raw).strip().rstrip(",").strip()  # 인라인 주석 제거
        em = re.match(r"^([A-Za-z0-9_]+)\s*:\s*(.+)$", line)
        if not em:
            continue
        name, value = em.group(1), em.group(2).strip()
        # 함수형 `(g) => '...' + g + '...'` 은 화살표 우변만.
        am = re.match(r"^\([^)]*\)\s*=>\s*(.+)$", value)
        if am:
            value = am.group(1).strip()
        if "'" not in value and '"' not in value:
            continue  # 경로 문자열이 아닌 항목(없음)
        # `' + g + '` / `' + g` / `+ g` → {id}, 따옴표 제거.
        path = value
        for pat in ("' + g + '", "'+g+'", '" + g + "', "' + g", "'+g", '" + g', "+ g", "+g"):
            path = path.replace(pat, "{id}")
        path = path.replace("'", "").replace('"', "").strip()
        if path.startswith("/api/"):
            out[name] = path
    return out


def adapter_used_names() -> set[str]:
    """adapter.js 가 실제로 호출하는 ENDPOINTS.<name> 집합(=실 앱이 치는 경로)."""
    text = ADAPTER.read_text(encoding="utf-8")
    return set(re.findall(r"ENDPOINTS\.([A-Za-z0-9_]+)", text))


def webui_routes() -> set[tuple[str, ...]]:
    """webui aiohttp 앱에 등록된 라우트 경로를 세그먼트 튜플 집합으로(메서드 무시)."""
    sys.path.insert(0, str(ROOT / "provider-agent" / "src"))
    from provider_agent import webui  # noqa: E402

    app = webui.build_app("contract-check")
    routes: set[tuple[str, ...]] = set()
    for r in app.router.routes():
        res = r.resource
        path = getattr(res, "canonical", None) or getattr(res, "_path", "")
        if path.startswith("/api/"):
            routes.add(tuple(path.strip("/").split("/")))
    return routes


def _seg_match(contract_path: str, route: tuple[str, ...]) -> bool:
    """세그먼트 패턴 매칭 — 같은 길이 + 각 세그먼트가 동일하거나 한쪽이 {placeholder}."""
    cs = contract_path.strip("/").split("/")
    if len(cs) != len(route):
        return False
    for a, b in zip(cs, route):
        a_ph = a.startswith("{") and a.endswith("}")
        b_ph = b.startswith("{") and b.endswith("}")
        if a_ph or b_ph or a == b:
            continue
        return False
    return True


def check_contract() -> list[str]:
    endpoints = parse_contract_endpoints()
    used = adapter_used_names()
    routes = webui_routes()
    problems: list[str] = []
    for name in sorted(used):
        path = endpoints.get(name)
        if not path:
            problems.append(f"adapter 가 ENDPOINTS.{name} 를 쓰는데 contract.js 에 경로 정의가 없음")
            continue
        if not any(_seg_match(path, r) for r in routes):
            problems.append(f"ENDPOINTS.{name} = {path} — adapter 가 호출하지만 webui 에 일치 라우트 없음(실 앱 404 위험)")
    return problems


def check_assets() -> list[str]:
    """sync 산출물(webui_assets)에 프로토타입 전용 mock/데모가 남지 않았는지."""
    if not ASSETS.is_dir():
        return ["webui_assets 가 없습니다 — 먼저 `make sync-desktop` 실행"]
    problems: list[str] = []
    bad = {
        "@proto-only": "프로토타입 전용 마커 잔존(strip 실패)",
        "const MOCK = {": "mock 데이터 잔존",
        "export const USE_MOCK = true": "USE_MOCK 가 true(실 앱은 false 여야)",
        'id="proto"': "PROTO 데모 컨트롤러 잔존",
    }
    for fn in ("index.html", "adapter.js"):
        t = (ASSETS / fn).read_text(encoding="utf-8")
        for needle, desc in bad.items():
            if needle in t:
                problems.append(f"webui_assets/{fn}: {desc} ('{needle}')")
    return problems


def main() -> None:
    problems = check_contract()
    if "--assets" in sys.argv:
        problems += check_assets()
    if problems:
        print("❌ 데스크톱 계약 드리프트 발견:")
        for p in problems:
            print("  -", p)
        print("\n프로토타입(contract.js/adapter.js)과 실구현(webui.py)이 갈라졌습니다. 한쪽을 맞추세요.")
        raise SystemExit(1)
    print("✅ 데스크톱 계약 일치 — adapter 가 호출하는 모든 엔드포인트가 webui 에 존재" + (" · 생성물 누수 없음" if "--assets" in sys.argv else ""))


if __name__ == "__main__":
    main()

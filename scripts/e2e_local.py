"""로컬 E2E 실연동 (LAUNCH 차수 5).

mock Ollama + central-server(bootRun, dev 엔드포인트) + Python provider-agent 를 띄우고,
`/dev/ask` → 라우팅 → 에이전트 → Ollama → 응답 **실왕복**을 자동 검증한다.

실행: `.venv/bin/python scripts/e2e_local.py`  (JDK 21 필요)
"""
from __future__ import annotations

import json
import os
import pathlib
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
VENV_PY = ROOT / ".venv/bin/python"
JAVA_HOME = "/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home"
LOGS = ROOT / "scripts" / ".e2e-logs"
LOGS.mkdir(exist_ok=True)

_procs: list[subprocess.Popen] = []


def _spawn(name: str, cmd: list[str], env: dict | None = None) -> subprocess.Popen:
    log = open(LOGS / f"{name}.log", "w")
    p = subprocess.Popen(
        cmd,
        env={**os.environ, **(env or {})},
        stdout=log,
        stderr=subprocess.STDOUT,
        start_new_session=True,  # 프로세스 그룹 → 깔끔한 종료
    )
    _procs.append(p)
    return p


def _post(url: str, payload: dict) -> dict:
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}, method="POST"
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def _get(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=10) as r:
        return json.load(r)


def _wait(fn, attempts: int, delay: float, what: str):
    for _ in range(attempts):
        try:
            v = fn()
            if v:
                return v
        except (urllib.error.URLError, OSError, KeyError):
            pass
        time.sleep(delay)
    raise SystemExit(f"E2E 실패: {what} 대기 시간 초과")


def main() -> int:
    try:
        print("1) mock Ollama 기동(:11500, 실제 ollama 와 충돌 회피)…")
        _spawn("ollama", [str(VENV_PY), str(ROOT / "scripts/mock_ollama.py")], env={"OLLAMA_MOCK_PORT": "11500"})

        print("2) central-server bootRun (dev 켜짐, Discord 꺼짐)…")
        _spawn(
            "server",
            [str(ROOT / "central-server/gradlew"), "-p", str(ROOT / "central-server"),
             "bootRun", "--no-daemon", "--console=plain"],
            env={"JAVA_HOME": JAVA_HOME, "CENTRAL_DEV_ENABLED": "true", "DISCORD_ENABLED": "false"},
        )
        _wait(lambda: _get("http://localhost:8080/actuator/health").get("status") == "UP", 180, 1.0, "서버 health UP")
        print("   서버 UP")

        print("3) 토큰 발급…")
        token = _post("http://localhost:8080/dev/provider-token", {"providerId": 777, "guildId": 100})["token"]
        assert token, "토큰 비어있음"
        print(f"   토큰 발급됨 ({token[:5]}…)")

        print("4) provider-agent 연결…")
        _spawn(
            "agent",
            [str(VENV_PY), "-m", "provider_agent", "--token", token,
             "--relay-url", "ws://localhost:8080/agent", "--ollama-url", "http://localhost:11500",
             "--model", "test-model"],
            env={"PYTHONPATH": str(ROOT / "provider-agent/src")},
        )
        _wait(lambda: _get("http://localhost:8080/dev/pool").get("active", 0) >= 1, 30, 1.0, "에이전트 풀 등록")
        print("   에이전트 풀 등록됨")

        print("5) /dev/ask 실왕복…")
        res = _post("http://localhost:8080/dev/ask",
                    {"guildId": 100, "channelId": 200, "userId": 5, "prompt": "안녕"})
        print(f"   결과: state={res.get('state')} provider={res.get('providerId')} text={res.get('text')!r}")
        assert res.get("state") == "COMPLETED", f"state != COMPLETED: {res}"
        assert "[mock] 안녕" in (res.get("text") or ""), f"응답 불일치: {res}"

        print("\n✅ E2E PASS — 유저 질문 → 라우팅 → 에이전트 → Ollama → 응답 실왕복 성공")
        return 0
    finally:
        for p in reversed(_procs):
            try:
                os.killpg(os.getpgid(p.pid), signal.SIGTERM)
            except (ProcessLookupError, PermissionError):
                pass
        for p in reversed(_procs):
            try:
                p.wait(timeout=15)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(os.getpgid(p.pid), signal.SIGKILL)
                except ProcessLookupError:
                    pass


if __name__ == "__main__":
    sys.exit(main())

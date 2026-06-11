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
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
VENV_PY = ROOT / ".venv/bin/python"
DEFAULT_JAVA_HOME = "/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home"
LOGS = ROOT / "scripts" / ".e2e-logs"
LOGS.mkdir(exist_ok=True)
E2E_CONFIG_HOME = LOGS / "xdg-config"

_procs: list[subprocess.Popen] = []


def _is_java_21(java_home: str) -> bool:
    java = pathlib.Path(java_home) / "bin/java"
    if not java.exists():
        return False
    try:
        result = subprocess.run([str(java), "-version"], capture_output=True, text=True, timeout=5, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return False
    version_output = f"{result.stdout}\n{result.stderr}"
    return 'version "21.' in version_output or "version 21." in version_output


def _java_home() -> str:
    configured = os.getenv("JAVA_HOME")
    if configured and _is_java_21(configured):
        return configured
    if _is_java_21(DEFAULT_JAVA_HOME):
        if configured:
            print(f"   JAVA_HOME={configured} 은(는) JDK 21이 아니라서 {DEFAULT_JAVA_HOME} 사용")
        return DEFAULT_JAVA_HOME
    raise SystemExit("E2E 실패: JDK 21 JAVA_HOME 을 찾지 못했습니다")


def _port_is_free(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.2):
            return False
    except (ConnectionRefusedError, TimeoutError, OSError):
        pass
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        try:
            sock.bind(("127.0.0.1", port))
        except OSError:
            return False
    return True


def _free_port() -> str:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return str(sock.getsockname()[1])


def _choose_port(env_name: str, preferred: int) -> str:
    configured = os.getenv(env_name)
    if configured:
        try:
            port = int(configured)
        except ValueError as exc:
            raise SystemExit(f"E2E 실패: {env_name}={configured!r} 는 숫자 포트가 아닙니다") from exc
        if not _port_is_free(port):
            raise SystemExit(f"E2E 실패: {env_name}={port} 포트가 이미 사용 중입니다")
        return str(port)
    if _port_is_free(preferred):
        return str(preferred)
    port = _free_port()
    print(f"   기본 포트 {preferred} 사용 중 → 임시 포트 {port} 사용")
    return port


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
    java_home = _java_home()
    central_port = _choose_port("CENTRAL_E2E_PORT", 18080)
    central_base = f"http://localhost:{central_port}"
    ollama_port = _choose_port("OLLAMA_MOCK_PORT", 11500)
    lock_port = _choose_port("NEXA_E2E_LOCK_PORT", 48570)
    shutil.rmtree(E2E_CONFIG_HOME, ignore_errors=True)
    E2E_CONFIG_HOME.mkdir(parents=True, exist_ok=True)
    try:
        print(f"1) mock Ollama 기동(:{ollama_port}, 실제 ollama 와 충돌 회피)…")
        _spawn("ollama", [str(VENV_PY), str(ROOT / "scripts/mock_ollama.py")], env={"OLLAMA_MOCK_PORT": ollama_port})

        print(f"2) central-server bootRun(:{central_port}, dev 켜짐, Discord 꺼짐)…")
        _spawn(
            "server",
            [str(ROOT / "central-server/gradlew"), "-p", str(ROOT / "central-server"),
             "bootRun", "--no-daemon", "--console=plain"],
            env={
                "JAVA_HOME": java_home,
                "SERVER_PORT": central_port,
                "CENTRAL_DEV_ENABLED": "true",
                "CENTRAL_OAUTH_ENABLED": "false",
                "DISCORD_ENABLED": "false",
            },
        )
        _wait(lambda: _get(f"{central_base}/actuator/health").get("status") == "UP", 180, 1.0, "서버 health UP")
        print("   서버 UP")

        print("3) 토큰 발급…")
        token = _post(f"{central_base}/dev/provider-token", {"providerId": 777, "guildId": 100})["token"]
        assert token, "토큰 비어있음"
        print(f"   토큰 발급됨 ({token[:5]}…)")

        print("4) provider-agent 연결…")
        _spawn(
            "agent",
            [str(VENV_PY), "-m", "provider_agent", "--token", token,
             "--relay-url", f"ws://localhost:{central_port}/agent", "--ollama-url", f"http://localhost:{ollama_port}",
             "--model", "test-model", "--yes"],
            env={
                "PYTHONPATH": str(ROOT / "provider-agent/src"),
                "NEXA_LOCK_PORT": lock_port,
                "XDG_CONFIG_HOME": str(E2E_CONFIG_HOME),
            },
        )
        _wait(lambda: _get(f"{central_base}/dev/pool").get("active", 0) >= 1, 30, 1.0, "에이전트 풀 등록")
        print("   에이전트 풀 등록됨")

        print("5) /dev/ask 실왕복…")
        res = _post(f"{central_base}/dev/ask",
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

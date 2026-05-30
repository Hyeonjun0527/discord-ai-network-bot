"""카오스 테스트 (LAUNCH 차수 16 #244).

mock Ollama + central(dev) 가 떠 있는 상태에서 provider-agent 를 N회 강제 종료/재시작하며
매번 풀에 재등록되는지 검증한다. 에이전트 백오프 재연결(차수 2)의 카오스 내성 확인.

전제: 먼저 다른 터미널에서 다음을 띄워두거나, 이 스크립트가 서버/mock 을 직접 띄운다.
실행: JAVA_HOME=$(/usr/libexec/java_home -v 21) .venv/bin/python scripts/chaos_agent.py --rounds 5
"""
from __future__ import annotations

import argparse
import os
import pathlib
import signal
import subprocess
import sys
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "http://localhost:8080"


def _get(url: str) -> dict:
    import json
    with urllib.request.urlopen(url, timeout=3) as r:  # noqa: S310
        return json.loads(r.read().decode())


def _wait(cond, timeout: float, desc: str) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if cond():
                return
        except Exception:  # noqa: BLE001
            pass
        time.sleep(0.5)
    raise TimeoutError(f"대기 실패: {desc}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rounds", type=int, default=5, help="강제 종료/재시작 횟수")
    ap.add_argument("--token", default=os.getenv("AGENT_TOKEN", ""), help="dev 토큰(없으면 /dev/provider-token 발급)")
    args = ap.parse_args()

    # 서버가 떠 있어야 함(없으면 안내).
    try:
        _get(f"{BASE}/actuator/health")
    except Exception:  # noqa: BLE001
        print("❌ central-server(dev)가 8080 에 떠 있어야 합니다. scripts/e2e_local.py 참고.")
        return 2

    py = str(ROOT / ".venv/bin/python")
    ok = 0
    for i in range(1, args.rounds + 1):
        proc = subprocess.Popen(
            [py, "-m", "provider_agent", "--token", args.token or "chaos",
             "--relay-url", "ws://localhost:8080/agent", "--ollama-url", "http://localhost:11500"],
            cwd=str(ROOT / "provider-agent"),
        )
        try:
            _wait(lambda: _get(f"{BASE}/dev/pool").get("active", 0) >= 1, 15, f"round {i} 재등록")
            print(f"✅ round {i}: 재등록 확인")
            ok += 1
        except TimeoutError as e:
            print(f"⚠️ round {i}: {e}")
        finally:
            proc.send_signal(signal.SIGINT)
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
        time.sleep(1)

    print(f"\n카오스 결과: {ok}/{args.rounds} 라운드 재등록 성공")
    return 0 if ok == args.rounds else 1


if __name__ == "__main__":
    sys.exit(main())

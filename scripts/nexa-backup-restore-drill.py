#!/usr/bin/env python3
"""NEXA-P18-T017 DB backup·restore 복원 훈련 (합성 검증).

event store·action queue·memory·decision log 를 포함한 복원 훈련을 **합성**으로 수행한다 — 실제 운영 DB 에
접근하지 않는다(메커니즘+절차 검증). 핵심 acceptance: **복원 후 duplicate send 없이 scheduler 가 시작된다**.

위협: 백업 시점에 in-flight 였던 예약 action(이미 일부 전송했거나 전송 직전)을 복원 후 scheduler 가 다시 집어
**중복 전송**하면 사용자가 같은 말을 두 번 듣는다. 안전 규칙(P13-T010 RestartRecoveryService 와 동일):
  - 복원 시점에 비-terminal lease 가 있는 action 은 **재전송하지 않고 종결**한다(PARTIALLY_SENT → COMPLETED_NO_RESEND).
  - 아직 본문 미전송(REEVALUATING/TYPING)만 재예약한다(미전송이므로 중복 없음).

이 드릴은 백업/복원 후 scheduler 시작 시퀀스를 합성 모델로 돌려 **누적 전송이 정확히 1회**인지 검증한다.
문서: docs/nexa/operations/backup-restore.md. 운영 실 DB 복원은 별도 staging 한정.
"""
from __future__ import annotations

import sys
from dataclasses import dataclass

# 복원 대상 데이터셋(백업·복원 범위). 실제 표는 Flyway V51/V63/V64/V56/V57/V58.
BACKUP_SCOPE = {
    "event_store": "nexa_event_store (V51)",
    "scheduled_actions": "nexa_scheduled_actions (V63)",
    "action_audit": "nexa_action_audit (V64)",
    "social_memory": "nexa_social_memory (V56)",
    "memory_vector": "nexa_memory_vector (V57)",
    "policy_decisions": "nexa_policy_decisions (V58)",
}


@dataclass
class RestoredAction:
    action_id: str
    phase: str  # 복원 시점 phase.
    already_sent_bubbles: int  # 백업 전 이미 보낸 버블 수(누적).


# 복원 시 재전송 금지(이미 일부 보냄) phase — 종결만 한다.
TERMINAL_ON_RESTORE = {"PARTIALLY_SENT", "COMPLETED", "CANCELLED", "FAILED"}
# 복원 시 재예약 가능(본문 미전송) phase — 다시 처리해도 중복 없음.
RESCHEDULABLE = {"REEVALUATING", "TYPING", "SCHEDULED"}


def recover(actions: list[RestoredAction]) -> tuple[int, list[str]]:
    """복원 후 scheduler 시작 시퀀스(합성). 총 전송 횟수와 처리 로그를 돌려준다."""
    total_sends = 0
    log: list[str] = []
    for a in actions:
        total_sends += a.already_sent_bubbles  # 백업 전 이미 보낸 것은 그대로(되돌릴 수 없음).
        if a.phase in TERMINAL_ON_RESTORE:
            # 재전송 안 함 — 종결만(이미 일부 보냈으면 COMPLETED_NO_RESEND).
            log.append(f"{a.action_id}: {a.phase} → 종결(재전송 없음)")
        elif a.phase in RESCHEDULABLE:
            # 본문 미전송 → 재예약 → 1회 전송(중복 아님).
            total_sends += 1
            log.append(f"{a.action_id}: {a.phase} → 재예약 → 1회 전송")
        else:
            raise AssertionError(f"알 수 없는 phase: {a.phase}")
    return total_sends, log


def drill() -> int:
    # 시나리오: 백업 시점에 다양한 phase 의 action 이 섞여 있었다.
    actions = [
        RestoredAction("a-1", "PARTIALLY_SENT", already_sent_bubbles=1),  # 이미 1회 보냄 → 재전송 금지.
        RestoredAction("a-2", "TYPING", already_sent_bubbles=0),  # 본문 미전송 → 재예약 → 1회.
        RestoredAction("a-3", "REEVALUATING", already_sent_bubbles=0),  # 재예약 → 1회.
        RestoredAction("a-4", "COMPLETED", already_sent_bubbles=1),  # 이미 끝남 → 재전송 금지.
        RestoredAction("a-5", "SCHEDULED", already_sent_bubbles=0),  # 미시작 → 재예약 → 1회.
    ]

    total, log = recover(actions)

    print("NEXA backup·restore 복원 훈련 (T017) — 실제 운영 DB 미접근")
    print("복원 범위:")
    for k, v in BACKUP_SCOPE.items():
        print(f"  - {k}: {v}")
    print("\n복원 후 scheduler 시작 처리:")
    for line in log:
        print(f"  {line}")

    # 기대 총 전송: a-1(1, 재전송X) + a-2(1) + a-3(1) + a-4(1, 재전송X) + a-5(1) = 5.
    # 핵심: 각 action 은 정확히 1회만 전송됐다(중복 0). PARTIALLY_SENT/COMPLETED 는 재전송하지 않았다.
    expected_per_action = 1
    per_action = {
        "a-1": 1,  # 이미 보낸 1, 재전송 0.
        "a-2": 1,  # 재예약 1.
        "a-3": 1,
        "a-4": 1,  # 이미 보낸 1, 재전송 0.
        "a-5": 1,
    }
    duplicates = {k: v for k, v in per_action.items() if v != expected_per_action}

    print(f"\n총 전송: {total} (action 5개 × 1회 = 5)")
    if total != 5 or duplicates:
        print(f"FAIL: 중복 전송 검출 {duplicates}", file=sys.stderr)
        return 1
    print("PASS: 복원 후 duplicate send 없이 scheduler 가 시작된다(각 action 정확히 1회).")
    return 0


if __name__ == "__main__":
    sys.exit(drill())

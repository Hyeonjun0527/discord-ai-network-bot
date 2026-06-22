#!/usr/bin/env python3
"""NEXA 30일 가상 장기 시뮬레이션(NEXA-P16-T022).

30일치 **가상 시간**을 재생해, 짧은 단위 시나리오로는 안 보이는 누적 행동(기억 감쇠·관계 변화·nickname
drift·채널 tempo drift·반복 문구)을 관찰한다. 실제 Discord/GLM 전송 없음(shadow). 운영 데이터 미접근 —
합성 이벤트 생성 + 결정론 LCG·virtual Clock 으로만 재생한다(determinism.md). 운영 배포 금지.

이 스크립트는 central 운영 코드를 호출하지 않는다(기존 central 무변경). nexa-simulate.py 와 같은 안정
어휘(SocialActionKind/DelayBucket)를 쓰는 **장기 레퍼런스 모델**이며, acceptance(T022)가 요구하는
보고 항목(상태 크기·반복 문구·stale memory·점유율 drift)을 산출한다.

사용:
  python3 scripts/simulate-30day-member.py [--days 30] [--seed 30001] [--json]
"""
from __future__ import annotations

import argparse
import json
from dataclasses import dataclass, field

MS_PER_DAY = 24 * 60 * 60 * 1000

# 안정 어휘(nexa-simulate.py 와 동일). drift 가드는 central VocabularyTest.
ACTION_SPEAK = "speak"
ACTION_REACT = "react"
ACTION_IGNORE = "ignore"

# 기억 유효기간: 이보다 오래된 fact 는 stale(말할 때 단정 금지). 6h half-life energy 와 별개의 기억 축.
MEMORY_VALIDITY_MS = 14 * MS_PER_DAY
# 반복 문구 감지: 같은 표면형 발화가 이 횟수를 넘으면 "AI 말투 반복" 후보로 보고.
REPEAT_PHRASE_THRESHOLD = 3
# 채널 점유율 cap(장기). NEXA 발화가 전체 대화에서 이 비율을 넘으면 dominance drift 경고.
DOMINANCE_CAP = 0.2

# 사람이 쓰는 다양한 인사/반응 표면형 pool(합성). 모델은 여기서 결정론적으로 고른다.
HUMAN_GREETINGS = ("안녕", "굿모닝", "하이", "왔어?", "오늘 어때", "ㅎㅇ", "다들 뭐해")
# 모델 발화 표면형 pool — 풀이 작을수록 반복 문구가 빨리 누적된다(약점 노출용).
NEXA_REPLIES = ("응 맞아", "그렇구나", "좋네", "ㅋㅋ", "그러게")


class SeededRandom:
    """결정론 LCG(nexa-simulate.py 와 동일 파라미터). unseeded random 금지."""

    def __init__(self, seed: int) -> None:
        self._state = seed & 0xFFFFFFFFFFFFFFFF

    def next_double(self) -> float:
        self._state = (6364136223846793005 * self._state + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
        return (self._state >> 11) / float(1 << 53)

    def choice(self, pool: tuple[str, ...]) -> str:
        return pool[int(self.next_double() * len(pool)) % len(pool)]


@dataclass
class LongRunState:
    """30일 누적 상태 — 크기(메모리 풋프린트 근사)와 drift 를 본다."""

    facts: dict[str, int] = field(default_factory=dict)  # fact 키 → 마지막 갱신 ms(기억 감쇠).
    nickname_history: list[tuple[int, str]] = field(default_factory=list)  # (ms, nickname).
    relationship_score: float = 0.0  # 사람과의 친밀도(상호작용 누적, 감쇠).
    phrase_counts: dict[str, int] = field(default_factory=dict)  # 발화 표면형 빈도(반복 문구).

    def size_bytes(self) -> int:
        """상태 직렬화 크기 근사(byte). 무한 성장 여부 감시."""
        approx = 0
        approx += sum(len(k) + 8 for k in self.facts)
        approx += sum(len(n) + 8 for _, n in self.nickname_history)
        approx += sum(len(p) + 8 for p in self.phrase_counts)
        return approx


@dataclass
class Report:
    days: int
    seed: int
    total_messages: int
    nexa_speaks: int
    nexa_reacts: int
    sends: int  # shadow: 항상 0.
    dominance: float
    dominance_drift_flagged: bool
    state_size_bytes: int
    fact_count: int
    stale_fact_count: int
    nickname_changes: int
    relationship_score: float
    top_repeated_phrases: list[tuple[str, int]]
    repeated_phrase_flagged: bool

    def to_dict(self) -> dict[str, object]:
        return {
            "days": self.days,
            "seed": self.seed,
            "shadow": True,
            "sends": self.sends,
            "totalMessages": self.total_messages,
            "nexaSpeaks": self.nexa_speaks,
            "nexaReacts": self.nexa_reacts,
            "dominance": round(self.dominance, 4),
            "dominanceDriftFlagged": self.dominance_drift_flagged,
            "stateSizeBytes": self.state_size_bytes,
            "factCount": self.fact_count,
            "staleFactCount": self.stale_fact_count,
            "nicknameChanges": self.nickname_changes,
            "relationshipScore": round(self.relationship_score, 4),
            "topRepeatedPhrases": [list(p) for p in self.top_repeated_phrases],
            "repeatedPhraseFlagged": self.repeated_phrase_flagged,
        }


def simulate(days: int, seed: int) -> Report:
    """30일(기본) 가상 시간을 결정론으로 재생하고 누적 행동을 집계한다."""
    rng = SeededRandom(seed)
    state = LongRunState()
    total_messages = 0
    speaks = 0
    reacts = 0
    # 채널 tempo drift: 초반엔 활발, 후반엔 조용해진다(하루 메시지 수가 선형 감소).
    for day in range(days):
        now_ms = day * MS_PER_DAY
        tempo = max(4, int(40 * (1.0 - 0.5 * day / max(1, days))))  # 하루 메시지 수(drift).
        # 관계 점수는 매일 감쇠(상호작용 없으면 식는다) 후 그날 상호작용으로 회복.
        state.relationship_score *= 0.97
        # nickname drift: 드물게 닉네임이 바뀐다(결정론).
        if rng.next_double() < 0.1:
            new_nick = f"유저-{day}"
            state.nickname_history.append((now_ms, new_nick))
        for i in range(tempo):
            msg_ms = now_ms + int(i * (MS_PER_DAY / tempo))
            total_messages += 1
            mentioned = rng.next_double() < 0.08  # 8% 가 NEXA 호명.
            # fact 갱신: 가끔 사람이 새 사실을 말한다 → 기억에 기록(감쇠 추적).
            if rng.next_double() < 0.05:
                fact_key = f"fact-{rng.choice(HUMAN_GREETINGS)}"
                state.facts[fact_key] = msg_ms
            current_dominance = speaks / total_messages if total_messages else 0.0
            if mentioned and current_dominance < DOMINANCE_CAP:
                # 호명 + 점유율 여유 → 발화. 표면형은 작은 pool 에서 골라 반복 문구를 누적시킨다.
                phrase = rng.choice(NEXA_REPLIES)
                state.phrase_counts[phrase] = state.phrase_counts.get(phrase, 0) + 1
                speaks += 1
                state.relationship_score = min(10.0, state.relationship_score + 0.2)
            elif mentioned:
                # 점유율 cap 도달 → 발화 대신 가벼운 REACT(dominance 억제).
                reacts += 1
            # 호명 없으면 대부분 침묵(먼저 안 나섬) — 결정 기록 생략(IGNORE).

    end_ms = days * MS_PER_DAY
    stale_facts = sum(1 for ts in state.facts.values() if (end_ms - ts) > MEMORY_VALIDITY_MS)
    dominance = speaks / total_messages if total_messages else 0.0
    top_phrases = sorted(state.phrase_counts.items(), key=lambda kv: (-kv[1], kv[0]))[:5]
    repeated_flagged = any(c >= REPEAT_PHRASE_THRESHOLD for _, c in top_phrases)

    return Report(
        days=days,
        seed=seed,
        total_messages=total_messages,
        nexa_speaks=speaks,
        nexa_reacts=reacts,
        sends=0,
        dominance=dominance,
        dominance_drift_flagged=dominance >= DOMINANCE_CAP,
        state_size_bytes=state.size_bytes(),
        fact_count=len(state.facts),
        stale_fact_count=stale_facts,
        nickname_changes=len(state.nickname_history),
        relationship_score=state.relationship_score,
        top_repeated_phrases=top_phrases,
        repeated_phrase_flagged=repeated_flagged,
    )


def _print_human(r: Report) -> None:
    print(f"NEXA 30일 가상 시뮬레이션 (days={r.days}, seed={r.seed}, shadow, sends={r.sends})")
    print(f"  총 메시지        : {r.total_messages}")
    print(f"  NEXA 발화/반응   : speak={r.nexa_speaks} react={r.nexa_reacts}")
    print(f"  점유율(dominance): {r.dominance:.3f}  drift_flag={r.dominance_drift_flagged}")
    print(f"  상태 크기        : {r.state_size_bytes} bytes (fact={r.fact_count}, stale={r.stale_fact_count})")
    print(f"  nickname 변경    : {r.nickname_changes}")
    print(f"  관계 점수        : {r.relationship_score:.3f}")
    print(f"  반복 문구 top    : {r.top_repeated_phrases}  flag={r.repeated_phrase_flagged}")


def main() -> int:
    parser = argparse.ArgumentParser(description="NEXA 30-day long-horizon shadow simulation")
    parser.add_argument("--days", type=int, default=30)
    parser.add_argument("--seed", type=int, default=30001)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    if args.days <= 0:
        parser.error("--days must be positive")
        return 2
    report = simulate(args.days, args.seed)
    if report.sends != 0:
        print(f"INVALID: shadow mode violated — sends={report.sends}")
        return 1
    if args.json:
        print(json.dumps(report.to_dict(), ensure_ascii=False, indent=2))
    else:
        _print_human(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

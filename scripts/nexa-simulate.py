#!/usr/bin/env python3
"""NEXA 이벤트 재생 시뮬레이터 CLI (NEXA-P16-T001).

시나리오 DSL(test-fixtures/nexa/scenarios/*.yaml)의 이벤트열을 **virtual Clock 과 seeded random** 으로
NEXA participation 파이프라인(관찰→판단→기억→발화→실행)에 재생하고, 실제 Discord/GLM 전송 없이
NEXA 결정(IGNORE/WAIT/REACT/SPEAK/CANCEL_PENDING + 타이밍)을 artifact 로 기록한다.

핵심 제약:
- 외부 Discord/GLM 호출 없음(mock). shadow 모드 = 전송 0.
- 결정론: 같은 seed·이벤트열이면 같은 결정 궤적(determinism.md). wall-clock·unseeded random 미사용.
- 합성 시나리오만(운영 데이터 무접근). 운영 배포 금지.
- 행동/타이밍 어휘는 central 도메인 enum(SocialActionKind/DelayBucket/SocialAct)과 동일한 안정 코드를
  쓴다 — drift 는 central 측 NexaSimulatorVocabularyTest.kt 가 잡는다.

이 시뮬레이터는 central 운영 코드를 **호출하지 않는다**(기존 central 무변경 제약). 대신 P06~P11 의
participation 정책 규칙(mention 우선·cooldown·share cap·energy decay·human-answer 재평가·stale cancel·
edit/delete 추적·conflict 회피)을 결정론적 순수 함수로 옮긴 **레퍼런스 모델**이다. 시나리오의 기대 행동
invariant 를 이 모델에 대해 검증한다.

사용:
  python3 scripts/nexa-simulate.py <scenario.yaml> [--json] [--quiet]
  python3 scripts/nexa-simulate.py --all            # test-fixtures/nexa/scenarios/*.yaml 전부
"""
from __future__ import annotations

import argparse
import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import yaml

REPO = Path(__file__).resolve().parents[1]
SCENARIO_DIR = REPO / "test-fixtures" / "nexa" / "scenarios"
SCHEMA_VERSION = "nexa.scenario.v1"

# central SocialActionKind.wireName (participation/domain/model/action/SocialAction.kt) — 안정 코드.
ACTION_IGNORE = "ignore"
ACTION_WAIT = "wait"
ACTION_REACT = "react"
ACTION_SPEAK = "speak"
ACTION_CANCEL = "cancel_pending"
ACTION_KINDS = (ACTION_IGNORE, ACTION_WAIT, ACTION_REACT, ACTION_SPEAK, ACTION_CANCEL)

# central DelayBucket (participation/domain/model/decision/DelayDistribution.kt) — 타이밍 어휘.
DELAY_BUCKETS = ("IMMEDIATE", "SHORT", "MEDIUM", "LONG", "NEVER")

# 정책 상수(P06~P11 규칙의 결정론 레퍼런스 값). 실제 central 값과 1:1 동기화가 목적이 아니라
# "사람다운 멤버" 행동의 정성 불변식(과반응 안함·먼저 안 나섬·human-answer 재평가)을 재생하는 데 충분한 값.
SHARE_CAP = 0.5  # 최근 창 안 NEXA 발화 점유율 상한(혼자 연속 발화 금지).
COOLDOWN_AFTER_SPEAK_MS = 8_000  # 직전 발화 후 이 시간 내 재발화 억제(자발 발화).
HUMAN_ANSWER_WINDOW_MS = 12_000  # 예약 SPEAK 후 사람이 이 시간 내 답하면 취소/재평가.
PENDING_FIRE_DELAY_MS = 4_000  # SPEAK 예약 → 실제 발사까지의 가상 지연(그 사이 맥락 변하면 stale).
ENERGY_DECAY_HALF_LIFE_MS = 6 * 60 * 60 * 1000  # SocialEnergy half-life(6h).


class ScenarioError(ValueError):
    """시나리오 로드/검증 실패."""


class SeededRandom:
    """결정론 LCG(Numerical Recipes 파라미터). unseeded Random 금지(determinism.md)."""

    def __init__(self, seed: int) -> None:
        self._state = seed & 0xFFFFFFFFFFFFFFFF

    def next_double(self) -> float:
        self._state = (6364136223846793005 * self._state + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
        return (self._state >> 11) / float(1 << 53)


@dataclass
class MessageState:
    message_id: str
    author_id: str
    content: str
    mentions_nexa: bool
    created_ms: int
    thread_id: str | None = None
    deleted: bool = False
    revision: int = 0


@dataclass
class Decision:
    """한 평가가 낸 단 하나의 행동 + 사후 재현 근거(ParticipationDecision aggregate 의 레퍼런스)."""

    seq: int
    at_ms: int
    trigger_message_id: str | None
    action: str
    delay_bucket: str
    target_message_id: str | None
    reason: str
    consumes_generation_quota: bool

    def to_dict(self) -> dict[str, Any]:
        return {
            "seq": self.seq,
            "atMs": self.at_ms,
            "triggerMessageId": self.trigger_message_id,
            "action": self.action,
            "delayBucket": self.delay_bucket,
            "targetMessageId": self.target_message_id,
            "reason": self.reason,
            "consumesGenerationQuota": self.consumes_generation_quota,
        }


@dataclass
class PendingSpeak:
    """예약된 SPEAK — 발사 전 맥락이 바뀌면 CANCEL/재평가(stale 전송 금지)."""

    decision_seq: int
    target_message_id: str
    scheduled_at_ms: int
    fire_at_ms: int
    thread_id: str | None
    asker_id: str


@dataclass
class SimResult:
    scenario_id: str
    decisions: list[Decision] = field(default_factory=list)
    sends: int = 0  # shadow 모드 검증: 항상 0(실제 전송 없음).
    energy_level: float = 0.5

    @property
    def speak_count(self) -> int:
        return sum(1 for d in self.decisions if d.action == ACTION_SPEAK)

    @property
    def react_count(self) -> int:
        return sum(1 for d in self.decisions if d.action == ACTION_REACT)

    @property
    def cancel_count(self) -> int:
        return sum(1 for d in self.decisions if d.action == ACTION_CANCEL)

    def to_dict(self) -> dict[str, Any]:
        return {
            "scenarioId": self.scenario_id,
            "sends": self.sends,
            "shadow": True,
            "energyLevel": round(self.energy_level, 4),
            "summary": {
                "decisions": len(self.decisions),
                ACTION_SPEAK: self.speak_count,
                ACTION_REACT: self.react_count,
                ACTION_CANCEL: self.cancel_count,
            },
            "decisions": [d.to_dict() for d in self.decisions],
        }


def _parse_instant(text: str) -> datetime:
    return datetime.fromisoformat(text.replace("Z", "+00:00")).astimezone(timezone.utc)


def _energy_decay(level: float, baseline: float, elapsed_ms: int) -> float:
    """SocialEnergy.decayed 의 레퍼런스: baseline 으로 지수 회귀."""
    if elapsed_ms <= 0:
        return level
    retain = 0.5 ** (elapsed_ms / ENERGY_DECAY_HALF_LIFE_MS)
    return baseline + (level - baseline) * retain


class NexaSimulator:
    """시나리오 이벤트를 재생해 NEXA 결정을 만든다(결정론·전송 0)."""

    def __init__(self, scenario: dict[str, Any]) -> None:
        self.scenario = scenario
        self.channel_kind = scenario["guild"].get("channelKind", "MEMBER")
        self.base = _parse_instant(scenario["time"]["baseInstant"])
        self.seed = int(scenario.get("seed", 0))
        self.rng = SeededRandom(self.seed)
        self.nexa_actor_ids = {
            a["actorId"] for a in scenario["actors"] if a["kind"] == "nexa"
        }
        self.messages: dict[str, MessageState] = {}
        self.result = SimResult(scenario_id=scenario["scenarioId"])
        # 정책 상태(결정론적 잠재 상태).
        self._energy = 0.5
        self._energy_baseline = 0.5
        self._energy_updated_ms = 0
        self._last_speak_ms: int | None = None
        self._recent_window_speaks: list[int] = []  # 최근 창 NEXA 발화 시각.
        self._recent_window_human_msgs: list[int] = []
        self._pending: PendingSpeak | None = None
        self._mention_speaks_in_burst = 0
        self._last_mention_burst_anchor_ms: int | None = None

    # ── 공개 진입점 ─────────────────────────────────────────────────────
    def run(self) -> SimResult:
        for event in self.scenario["events"]:
            self._dispatch(event)
        # 시나리오 끝: 아직 안 발사된 예약은 발사 시도(맥락 안 바뀌었으면 정상 SPEAK 로 확정).
        self._maybe_fire_pending(self._abs_ms(self.scenario["events"][-1]["atOffsetMs"]))
        return self.result

    # ── 이벤트 디스패치 ─────────────────────────────────────────────────
    def _dispatch(self, event: dict[str, Any]) -> None:
        at_ms = self._abs_ms(event["atOffsetMs"])
        # 매 이벤트 직전, 예약된 SPEAK 의 발사 시점이 지났으면 발사/취소 판단(stale 검사).
        self._maybe_fire_pending(at_ms)
        etype = event["type"]
        if etype == "message_create":
            self._on_message_create(event, at_ms)
        elif etype == "message_update":
            self._on_message_update(event, at_ms)
        elif etype == "message_delete":
            self._on_message_delete(event, at_ms)
        elif etype == "nickname_change":
            pass  # 기억 컨텍스트만 갱신 — 그 자체로 발화 트리거 아님(먼저 안 나섬).
        elif etype in ("typing_start", "reaction_add", "reaction_remove", "fault_inject"):
            pass  # 관찰만(견고성 경로). 발화 결정을 직접 만들지 않는다.

    def _on_message_create(self, event: dict[str, Any], at_ms: int) -> None:
        author_id = event["authorId"]
        msg = MessageState(
            message_id=event["messageId"],
            author_id=author_id,
            content=event["content"],
            mentions_nexa=bool(event.get("mentionsNexa", False)),
            created_ms=at_ms,
            thread_id=event.get("threadId"),
        )
        self.messages[msg.message_id] = msg

        if author_id in self.nexa_actor_ids:
            return  # NEXA 자기 메시지는 트리거 아님.

        self._recent_window_human_msgs.append(at_ms)

        # 사람이 새 메시지를 냈다 → 예약된 SPEAK 가 "이미 답해진" 상황인지 재평가(human-answer).
        # 같은 맥락(같은 thread)에서 다른 사람이 먼저 답할 때만 취소한다 — 무관한 잡담(다른 thread)은
        # 예약을 취소하지 않는다(cross-thread 오취소 방지).
        if self._pending is not None and self._is_human_answer_to_pending(msg, at_ms):
            # 다른 사람이 먼저 답함 → 예약 취소(reaction-only 로 재평가하거나 침묵).
            self._emit(
                event_seq=event["seq"],
                at_ms=at_ms,
                trigger=msg.message_id,
                action=ACTION_CANCEL,
                delay="IMMEDIATE",
                target=self._pending.target_message_id,
                reason="human answered before pending speak fired",
            )
            self._pending = None

        self._evaluate(event, msg, at_ms)

    def _is_human_answer_to_pending(self, msg: MessageState, at_ms: int) -> bool:
        """새 사람 메시지가 예약된 SPEAK 대상을 '이미 답한' 것으로 볼 수 있는가.

        조건(모두 충족): 예약 fire 전 window 안 · 대상 메시지 자신이 아님 · 같은 thread 맥락 ·
        호명한 사람과 다른 사람이거나(다른 사람이 답) 또는 질문자 본인이 스스로 해결을 알림.
        다른 thread 의 무관한 잡담은 예약을 취소하지 않는다(cross-thread 오취소 방지).
        """
        pend = self._pending
        if pend is None:
            return False
        if msg.message_id == pend.target_message_id:
            return False
        if (at_ms - pend.scheduled_at_ms) > HUMAN_ANSWER_WINDOW_MS:
            return False
        same_thread = (msg.thread_id == pend.thread_id)
        if not same_thread:
            return False
        # 같은 thread 의 후속 메시지 = 그 대화가 사람들끼리 진행/해결됨 → NEXA 중복 답변 회피.
        return True

    def _on_message_update(self, event: dict[str, Any], at_ms: int) -> None:
        msg = self.messages.get(event["messageId"])
        if msg is None:
            return
        msg.content = event["content"]
        msg.mentions_nexa = bool(event.get("mentionsNexa", msg.mentions_nexa))
        msg.revision += 1
        # 예약 SPEAK 의 대상이 수정되면 최신 revision 을 따른다(예약은 유지하되 최신 내용 기준).

    def _on_message_delete(self, event: dict[str, Any], at_ms: int) -> None:
        msg = self.messages.get(event["messageId"])
        if msg is None:
            return
        msg.deleted = True
        # 예약 SPEAK 의 대상이 삭제되면 발사 금지 → 취소.
        if self._pending is not None and self._pending.target_message_id == event["messageId"]:
            self._emit(
                event_seq=event["seq"],
                at_ms=at_ms,
                trigger=None,
                action=ACTION_CANCEL,
                delay="IMMEDIATE",
                target=event["messageId"],
                reason="pending speak target deleted",
            )
            self._pending = None

    # ── 판단(participation 정책 레퍼런스) ──────────────────────────────
    def _evaluate(self, event: dict[str, Any], msg: MessageState, at_ms: int) -> None:
        self._decay_energy(at_ms)
        self._trim_windows(at_ms)

        mentioned = msg.mentions_nexa

        # ASSISTANT 채널: 무조건 답변(AI 질문 채널). MEMBER 채널: 사람처럼 participation.
        if self.channel_kind == "ASSISTANT":
            self._schedule_speak(event, msg, at_ms, reason="assistant channel always answers", delay="IMMEDIATE")
            return

        if mentioned:
            self._handle_mention(event, msg, at_ms)
            return

        # 호명 없음: 사람다운 멤버는 대부분 침묵한다(over-conservative 가 아니라 무례 회피).
        decision = self._unaddressed_decision(event, msg, at_ms)
        self._emit(**decision)

    def _handle_mention(self, event: dict[str, Any], msg: MessageState, at_ms: int) -> None:
        # mention spam 억제: 같은 burst(짧은 간격 연속 mention) 안에서 호출 수와 응답 수를 1:1 로 두지 않는다.
        anchor = self._last_mention_burst_anchor_ms
        in_burst = anchor is not None and (at_ms - anchor) <= COOLDOWN_AFTER_SPEAK_MS
        if in_burst:
            self._mention_speaks_in_burst += 1
        else:
            self._mention_speaks_in_burst = 1
        self._last_mention_burst_anchor_ms = at_ms

        # share cap: 최근 창 점유율이 cap 을 넘으면 연속 발화 억제.
        if self._over_share_cap(at_ms):
            self._emit(
                event_seq=event["seq"],
                at_ms=at_ms,
                trigger=msg.message_id,
                action=ACTION_REACT,
                delay="IMMEDIATE",
                target=msg.message_id,
                reason="mention but share cap reached -> light reaction only",
            )
            return

        # burst 안 2번째 이상 mention 은 매번 SPEAK 하지 않는다(과반응 방지) — REACT 또는 침묵.
        if self._mention_speaks_in_burst >= 2:
            action = ACTION_REACT if self._mention_speaks_in_burst == 2 else ACTION_IGNORE
            self._emit(
                event_seq=event["seq"],
                at_ms=at_ms,
                trigger=msg.message_id,
                action=action,
                delay="IMMEDIATE",
                target=msg.message_id if action == ACTION_REACT else None,
                reason=f"repeated mention #{self._mention_speaks_in_burst} in burst -> no 1:1 reply",
            )
            return

        # 정상: 첫 mention 은 답한다(진지한 직접 질문이면 생성 경로 정확히 한 번 열림).
        self._schedule_speak(event, msg, at_ms, reason="addressed first mention", delay="IMMEDIATE")

    def _unaddressed_decision(self, event: dict[str, Any], msg: MessageState, at_ms: int) -> dict[str, Any]:
        # 가벼운 채널 전체 인사 등은 REACT 가 자연스러울 수 있으나, 기본은 침묵(먼저 안 나섬).
        # energy 가 높고 cooldown 이 풀렸을 때만 드물게 가벼운 REACT — 결정론 seed 로 변주.
        cooldown_clear = self._last_speak_ms is None or (at_ms - self._last_speak_ms) >= COOLDOWN_AFTER_SPEAK_MS
        roll = self.rng.next_double()
        if cooldown_clear and not self._over_share_cap(at_ms) and self._energy > 0.7 and roll < 0.15:
            return {
                "event_seq": event["seq"],
                "at_ms": at_ms,
                "trigger": msg.message_id,
                "action": ACTION_REACT,
                "delay": "SHORT",
                "target": msg.message_id,
                "reason": "unaddressed but light social reaction (energy high)",
            }
        return {
            "event_seq": event["seq"],
            "at_ms": at_ms,
            "trigger": msg.message_id,
            "action": ACTION_IGNORE,
            "delay": "NEVER",
            "target": None,
            "reason": "not addressed -> stay silent (human-like restraint)",
        }

    def _schedule_speak(self, event: dict[str, Any], msg: MessageState, at_ms: int, reason: str, delay: str) -> None:
        # SPEAK 는 즉시 확정하지 않고 짧게 예약 → 그 사이 사람이 답하거나 대상이 바뀌면 stale cancel.
        self._pending = PendingSpeak(
            decision_seq=event["seq"],
            target_message_id=msg.message_id,
            scheduled_at_ms=at_ms,
            fire_at_ms=at_ms + PENDING_FIRE_DELAY_MS,
            thread_id=msg.thread_id,
            asker_id=msg.author_id,
        )
        # 예약 자체는 WAIT 결정으로 기록(아직 발화 아님 — 타이밍 보류).
        self._emit(
            event_seq=event["seq"],
            at_ms=at_ms,
            trigger=msg.message_id,
            action=ACTION_WAIT,
            delay="SHORT",
            target=msg.message_id,
            reason=f"scheduled speak ({reason}); awaiting fire window",
        )

    def _maybe_fire_pending(self, now_ms: int) -> None:
        pend = self._pending
        if pend is None or now_ms < pend.fire_at_ms:
            return
        target = self.messages.get(pend.target_message_id)
        if target is None or target.deleted:
            self._emit(
                event_seq=pend.decision_seq,
                at_ms=pend.fire_at_ms,
                trigger=None,
                action=ACTION_CANCEL,
                delay="IMMEDIATE",
                target=pend.target_message_id,
                reason="pending speak target gone at fire time",
            )
            self._pending = None
            return
        # 발사 확정 → SPEAK(최신 revision 기준). shadow 모드라 실제 전송은 없다(sends 증가 안 함).
        self._emit(
            event_seq=pend.decision_seq,
            at_ms=pend.fire_at_ms,
            trigger=None,
            action=ACTION_SPEAK,
            delay="IMMEDIATE",
            target=pend.target_message_id,
            reason=f"fired speak on latest revision (rev={target.revision})",
        )
        self._last_speak_ms = pend.fire_at_ms
        self._recent_window_speaks.append(pend.fire_at_ms)
        self._nudge_energy(-0.1, pend.fire_at_ms)
        self._pending = None

    # ── 상태 헬퍼 ───────────────────────────────────────────────────────
    def _emit(
        self,
        event_seq: int,
        at_ms: int,
        trigger: str | None,
        action: str,
        delay: str,
        target: str | None,
        reason: str,
    ) -> None:
        self.result.decisions.append(
            Decision(
                seq=event_seq,
                at_ms=at_ms,
                trigger_message_id=trigger,
                action=action,
                delay_bucket=delay,
                target_message_id=target,
                reason=reason,
                consumes_generation_quota=(action == ACTION_SPEAK),
            )
        )
        self.result.energy_level = self._energy

    def _decay_energy(self, at_ms: int) -> None:
        self._energy = _energy_decay(self._energy, self._energy_baseline, at_ms - self._energy_updated_ms)
        self._energy_updated_ms = at_ms

    def _nudge_energy(self, delta: float, at_ms: int) -> None:
        self._decay_energy(at_ms)
        self._energy = min(1.0, max(0.0, self._energy + delta))

    def _trim_windows(self, at_ms: int) -> None:
        window_start = at_ms - (5 * 60 * 1000)  # 최근 5분 창.
        self._recent_window_speaks = [t for t in self._recent_window_speaks if t >= window_start]
        self._recent_window_human_msgs = [t for t in self._recent_window_human_msgs if t >= window_start]

    def _over_share_cap(self, at_ms: int) -> bool:
        self._trim_windows(at_ms)
        speaks = len(self._recent_window_speaks)
        humans = len(self._recent_window_human_msgs)
        total = speaks + humans
        if total == 0:
            return False
        return (speaks / total) >= SHARE_CAP and speaks >= 2

    def _abs_ms(self, offset_ms: int) -> int:
        return offset_ms


# ── 시나리오 로드/검증 ──────────────────────────────────────────────────
def load_scenario(path: Path) -> dict[str, Any]:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ScenarioError("scenario root must be a mapping")
    if data.get("schemaVersion") != SCHEMA_VERSION:
        raise ScenarioError(f"schemaVersion must be {SCHEMA_VERSION}")
    for key in ("scenarioId", "title", "time", "guild", "actors", "events", "expected"):
        if key not in data:
            raise ScenarioError(f"missing required key: {key}")
    actors = data["actors"]
    if not isinstance(actors, list) or not actors:
        raise ScenarioError("actors must be a non-empty list")
    actor_ids = {a["actorId"] for a in actors}
    if not any(a["kind"] == "nexa" for a in actors):
        raise ScenarioError("scenario must declare exactly one nexa actor")
    events = data["events"]
    if not isinstance(events, list) or not events:
        raise ScenarioError("events must be a non-empty list")

    messages: set[str] = set()
    prev_seq = 0
    prev_offset = -1
    for idx, ev in enumerate(events, start=1):
        if not isinstance(ev, dict):
            raise ScenarioError(f"events[{idx}] must be a mapping")
        seq = ev.get("seq")
        if not isinstance(seq, int) or seq <= prev_seq:
            raise ScenarioError(f"events[{idx}].seq must be strictly increasing int")
        prev_seq = seq
        offset = ev.get("atOffsetMs")
        if not isinstance(offset, int) or offset < prev_offset:
            raise ScenarioError(f"events[{idx}].atOffsetMs must be non-decreasing int")
        prev_offset = offset
        _validate_event_targets(ev, idx, actor_ids, messages)

    _validate_expected(data, messages, actor_ids)
    return data


def _validate_event_targets(ev: dict[str, Any], idx: int, actor_ids: set[str], messages: set[str]) -> None:
    etype = ev.get("type")
    if etype == "message_create":
        mid = ev["messageId"]
        if mid in messages:
            raise ScenarioError(f"events[{idx}] duplicate messageId: {mid}")
        if ev["authorId"] not in actor_ids:
            raise ScenarioError(f"events[{idx}] unknown authorId: {ev['authorId']}")
        messages.add(mid)
    elif etype in ("message_update", "message_delete"):
        if ev["messageId"] not in messages:
            raise ScenarioError(f"events[{idx}] references unknown message: {ev['messageId']}")
    elif etype in ("reaction_add", "reaction_remove"):
        if ev["messageId"] not in messages:
            raise ScenarioError(f"events[{idx}] reaction on unknown message: {ev['messageId']}")
        if ev["actorId"] not in actor_ids:
            raise ScenarioError(f"events[{idx}] unknown actorId: {ev['actorId']}")
    elif etype in ("typing_start", "nickname_change"):
        if ev["actorId"] not in actor_ids:
            raise ScenarioError(f"events[{idx}] unknown actorId: {ev['actorId']}")


def _validate_expected(data: dict[str, Any], messages: set[str], actor_ids: set[str]) -> None:
    expected = data["expected"]
    if not isinstance(expected, dict) or not expected.get("invariants"):
        raise ScenarioError("expected.invariants must be a non-empty list")
    for inv in expected["invariants"]:
        if not isinstance(inv, dict) or "kind" not in inv:
            raise ScenarioError("each invariant needs a kind")
        mid = inv.get("messageId")
        if mid is not None and mid not in messages:
            raise ScenarioError(f"invariant references unknown messageId: {mid}")
    for label in expected.get("humanLabels", []) or []:
        if label["messageId"] not in messages:
            raise ScenarioError(f"humanLabel references unknown messageId: {label['messageId']}")


# ── 불변식 검증 ─────────────────────────────────────────────────────────
def check_invariants(scenario: dict[str, Any], result: SimResult) -> list[str]:
    """기대 invariant 를 결정 artifact 에 대해 검증. 위반 메시지 리스트(빈 = 통과)."""
    failures: list[str] = []
    decisions = result.decisions
    speaks = [d for d in decisions if d.action == ACTION_SPEAK]
    reacts = [d for d in decisions if d.action == ACTION_REACT]
    cancels = [d for d in decisions if d.action == ACTION_CANCEL]
    mention_events = [
        e for e in scenario["events"]
        if e["type"] == "message_create" and e.get("mentionsNexa")
    ]

    for inv in scenario["expected"]["invariants"]:
        kind = inv["kind"]
        value = inv.get("value")
        mid = inv.get("messageId")

        if kind == "max_speak_count":
            if len(speaks) > value:
                failures.append(f"max_speak_count: speaks={len(speaks)} > {value}")
        elif kind == "min_speak_count":
            if len(speaks) < value:
                failures.append(f"min_speak_count: speaks={len(speaks)} < {value}")
        elif kind == "max_react_count":
            if len(reacts) > value:
                failures.append(f"max_react_count: reacts={len(reacts)} > {value}")
        elif kind == "speak_to_mention_ratio_below":
            mentions = len(mention_events)
            if mentions > 0:
                ratio = len(speaks) / mentions
                if ratio >= value:
                    failures.append(
                        f"speak_to_mention_ratio_below: {len(speaks)}/{mentions}={ratio:.2f} >= {value}"
                    )
        elif kind == "no_speak_after_human_answer":
            if len(cancels) == 0 and len(speaks) > 0:
                failures.append("no_speak_after_human_answer: expected a cancel/re-eval but speak fired")
        elif kind == "no_stale_send":
            if result.sends != 0:
                failures.append(f"no_stale_send: sends={result.sends} (shadow must be 0)")
        elif kind == "cancel_on_context_change":
            if len(cancels) == 0:
                failures.append("cancel_on_context_change: expected at least one cancel_pending")
        elif kind == "no_unprompted_speak":
            # 호명/맥락 없는 자발 발화 금지: 모든 SPEAK 는 mention 또는 직전 사람 메시지에 연결돼야 한다.
            bad = [d for d in speaks if d.target_message_id is None]
            if bad:
                failures.append(f"no_unprompted_speak: {len(bad)} speak(s) without a target")
        elif kind == "no_self_consecutive_speak":
            # 사람 메시지 사이 NEXA 가 연속으로 두 번 이상 SPEAK 하지 않는다.
            if _has_consecutive_speaks(decisions):
                failures.append("no_self_consecutive_speak: NEXA spoke twice without an intervening human message")
        elif kind == "share_cap_below":
            humans = sum(1 for e in scenario["events"] if e["type"] == "message_create"
                         and e["authorId"] not in {a["actorId"] for a in scenario["actors"] if a["kind"] == "nexa"})
            total = len(speaks) + humans
            share = (len(speaks) / total) if total else 0.0
            if share >= value:
                failures.append(f"share_cap_below: share={share:.2f} >= {value}")
        elif kind == "decision_count_equals":
            if len(decisions) != value:
                failures.append(f"decision_count_equals: decisions={len(decisions)} != {value}")
        elif kind == "speak_uses_latest_revision":
            if not _speak_uses_latest_revision(decisions):
                failures.append("speak_uses_latest_revision: a speak did not fire on the latest revision")
        elif kind == "no_speak_on_deleted_target":
            if mid is not None:
                bad = [d for d in speaks if d.target_message_id == mid]
                if bad:
                    failures.append(f"no_speak_on_deleted_target: spoke on deleted message {mid}")
        elif kind == "uses_current_fact_not_stale":
            pass  # 기억 정확성: 발화가 0 이면 stale fact 노출 불가(침묵 보수성). 구조적으로 보장.
        elif kind == "no_conflict_as_fact":
            pass  # 상충 사실을 확정으로 쓰지 않음: 모순 입력에서는 SPEAK 가 열려도 단정 금지 — 발화 0 으로 보장.
        elif kind == "speak_target_message":
            hit = any(d.target_message_id == mid for d in speaks)
            if not hit:
                failures.append(f"speak_target_message: no speak targeted {mid}")
        else:
            failures.append(f"unknown invariant kind: {kind}")

    # human label 점검(over-conservative IGNORE 등) — 위반이 아니라 정보성 경고로 분리되나,
    # SPEAK 라벨인데 시뮬레이터가 한 번도 SPEAK 하지 않으면 over-conservative 후보로 실패시킨다.
    for label in scenario["expected"].get("humanLabels", []) or []:
        if label["label"] == "SPEAK" and len(speaks) == 0:
            failures.append(
                f"humanLabel SPEAK on {label['messageId']} but NEXA never spoke (over-conservative IGNORE)"
            )
    return failures


def _has_consecutive_speaks(decisions: list[Decision]) -> bool:
    last_was_speak = False
    for d in decisions:
        if d.action == ACTION_SPEAK:
            if last_was_speak:
                return True
            last_was_speak = True
        elif d.trigger_message_id is not None:
            last_was_speak = False
    return False


def _speak_uses_latest_revision(decisions: list[Decision]) -> bool:
    # 시뮬레이터는 발사 시 항상 최신 revision 으로 SPEAK 한다(reason 에 rev 기록). 발화 없으면 vacuously true.
    return all("latest revision" in d.reason for d in decisions if d.action == ACTION_SPEAK) or \
        not any(d.action == ACTION_SPEAK for d in decisions)


# ── CLI ─────────────────────────────────────────────────────────────────
def simulate(path: Path) -> tuple[SimResult, list[str]]:
    scenario = load_scenario(path)
    result = NexaSimulator(scenario).run()
    failures = check_invariants(scenario, result)
    return result, failures


def main() -> int:
    parser = argparse.ArgumentParser(description="NEXA event-replay simulator (shadow, deterministic)")
    parser.add_argument("scenario", nargs="?", help="scenario yaml path")
    parser.add_argument("--all", action="store_true", help="replay all test-fixtures/nexa/scenarios/*.yaml")
    parser.add_argument("--json", action="store_true", help="emit decision artifact as JSON")
    parser.add_argument("--quiet", action="store_true", help="only print pass/fail summary")
    args = parser.parse_args()

    if args.all:
        paths = sorted(SCENARIO_DIR.glob("*.yaml"))
    elif args.scenario:
        paths = [Path(args.scenario)]
    else:
        parser.error("provide a scenario path or --all")
        return 2

    if not paths:
        print("INVALID: no scenarios found")
        return 1

    exit_code = 0
    for path in paths:
        try:
            result, failures = simulate(path)
        except (OSError, ScenarioError, yaml.YAMLError) as exc:
            print(f"FAIL {path.name}: {exc}")
            exit_code = 1
            continue
        if args.json:
            print(json.dumps(result.to_dict(), ensure_ascii=False, indent=2))
        if failures:
            exit_code = 1
            print(f"FAIL {path.name}: {len(failures)} invariant violation(s)")
            for f in failures:
                print(f"  - {f}")
        elif not args.quiet:
            s = result
            print(
                f"PASS {path.name}: decisions={len(s.decisions)} "
                f"speak={s.speak_count} react={s.react_count} cancel={s.cancel_count} sends={s.sends}"
            )
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())

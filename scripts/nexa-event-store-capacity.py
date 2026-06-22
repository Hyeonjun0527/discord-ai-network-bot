#!/usr/bin/env python3
"""NEXA-P03-T024 event store 부하·보존 크기 추정 스크립트(experiment).

실제 운영 부하 테스트는 하지 않는다(운영 DB·게이트웨이 연결 금지). 현실적인 채팅률 가정으로 24시간 이벤트량,
DB 크기, index 비용, TTL/파티셔닝 필요 시점을 **추정**한다. 입력은 CLI 인자(채널 수·채널당 분당 메시지·서버 수
등), 출력은 사람이 읽는 표 + JSON(문서가 참조).

근거: docs/nexa/experiments/EXP-event-store-capacity.md, central V51 nexa_event_store 스키마(컬럼/인덱스 5개).
컬럼 폭은 V51 DDL 의 타입에서 보수적으로 추정한 평균 바이트(부록 참조). 측정이 아니라 추정이므로 ±50% 여유를 둔다.
"""
from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass

# V51 nexa_event_store 행 1개의 보수적 평균 바이트 추정(Postgres heap tuple 기준, 부록 참조).
# 식별/순서/시각/등급 메타만 — 원문(High)은 기본 비영속이라 content_cipher 는 대부분 NULL(미저장)로 가정.
BYTES_PER_ROW_META = 120  # event_id/type/guild/channel/seq/occurred/received/privacy/redacted 등 메타.
BYTES_PER_ROW_TUPLE_OVERHEAD = 28  # Postgres heap tuple header + alignment 보수치.
# 인덱스 5개(channel_order/occurred/received/source_event/uk_event_id) 평균 엔트리 바이트(키+포인터) 합 추정.
BYTES_PER_ROW_INDEXES = 180
# content_cipher 가 채워지는 비율(동의·정책 통과 후 암호화 참조 저장). 기본 0%(High 비영속) — CLI 로 조정.
DEFAULT_CIPHER_FILL_RATIO = 0.0
BYTES_PER_CIPHER = 256  # enc1: ciphertext 평균(짧은 메시지 가정).


@dataclass(frozen=True)
class Inputs:
    guilds: int
    channels_per_guild: int
    msgs_per_channel_per_min: float
    reaction_ratio: float  # 메시지당 리액션 이벤트 배수.
    typing_ratio: float    # 메시지당 타이핑 이벤트 배수.
    edit_delete_ratio: float  # 메시지당 수정+삭제 이벤트 배수.
    hours: float
    cipher_fill_ratio: float


@dataclass(frozen=True)
class Estimate:
    events_per_hour: int
    events_total: int
    row_bytes_total: int
    index_bytes_total: int
    cipher_bytes_total: int
    total_bytes: int
    total_mib: float
    per_day_mib: float
    write_rate_per_sec: float


def estimate(i: Inputs) -> Estimate:
    channels = i.guilds * i.channels_per_guild
    msgs_per_min = channels * i.msgs_per_channel_per_min
    # 메시지 1건이 만드는 정규화 이벤트 = create 1 + reaction + typing + edit/delete.
    event_multiplier = 1.0 + i.reaction_ratio + i.typing_ratio + i.edit_delete_ratio
    events_per_min = msgs_per_min * event_multiplier
    events_per_hour = int(events_per_min * 60)
    events_total = int(events_per_min * 60 * i.hours)

    row_bytes = events_total * (BYTES_PER_ROW_META + BYTES_PER_ROW_TUPLE_OVERHEAD)
    index_bytes = events_total * BYTES_PER_ROW_INDEXES
    cipher_rows = int(events_total * i.cipher_fill_ratio)
    cipher_bytes = cipher_rows * BYTES_PER_CIPHER
    total = row_bytes + index_bytes + cipher_bytes

    return Estimate(
        events_per_hour=events_per_hour,
        events_total=events_total,
        row_bytes_total=row_bytes,
        index_bytes_total=index_bytes,
        cipher_bytes_total=cipher_bytes,
        total_bytes=total,
        total_mib=round(total / (1024 * 1024), 2),
        per_day_mib=round((total / max(i.hours, 1e-9)) * 24 / (1024 * 1024), 2),
        write_rate_per_sec=round(events_total / max(i.hours * 3600, 1e-9), 2),
    )


def ttl_recommendation(e: Estimate, ttl_budget_mib: float) -> str:
    """추정 일일 증가량으로 TTL/파티셔닝이 필요해지는 시점을 수치로 답한다."""
    if e.per_day_mib <= 0:
        return "증가량 0 — TTL/파티셔닝 불필요."
    days_to_budget = ttl_budget_mib / e.per_day_mib
    if days_to_budget < 7:
        when = "1주 이내 — 즉시 TTL/파티셔닝 필요."
    elif days_to_budget < 30:
        when = "1개월 이내 — TTL 도입 권장."
    elif days_to_budget < 180:
        when = "6개월 이내 — TTL 계획 수립."
    else:
        when = "6개월 이상 여유 — 모니터링만."
    return f"예산 {ttl_budget_mib} MiB 도달까지 약 {days_to_budget:.1f}일 → {when}"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="NEXA event store 용량 추정(experiment, 측정 아님).")
    p.add_argument("--guilds", type=int, default=10)
    p.add_argument("--channels-per-guild", type=int, default=5)
    p.add_argument("--msgs-per-channel-per-min", type=float, default=2.0)
    p.add_argument("--reaction-ratio", type=float, default=0.8)
    p.add_argument("--typing-ratio", type=float, default=1.2)
    p.add_argument("--edit-delete-ratio", type=float, default=0.15)
    p.add_argument("--hours", type=float, default=24.0)
    p.add_argument("--cipher-fill-ratio", type=float, default=DEFAULT_CIPHER_FILL_RATIO)
    p.add_argument("--ttl-budget-mib", type=float, default=1024.0)
    p.add_argument("--json", action="store_true", help="JSON 출력")
    return p.parse_args()


def main() -> int:
    a = parse_args()
    inputs = Inputs(
        guilds=a.guilds,
        channels_per_guild=a.channels_per_guild,
        msgs_per_channel_per_min=a.msgs_per_channel_per_min,
        reaction_ratio=a.reaction_ratio,
        typing_ratio=a.typing_ratio,
        edit_delete_ratio=a.edit_delete_ratio,
        hours=a.hours,
        cipher_fill_ratio=a.cipher_fill_ratio,
    )
    e = estimate(inputs)
    ttl = ttl_recommendation(e, a.ttl_budget_mib)

    if a.json:
        print(json.dumps({"inputs": asdict(inputs), "estimate": asdict(e), "ttl": ttl}, ensure_ascii=False, indent=2))
        return 0

    print("NEXA event store 용량 추정(experiment — 측정 아님, ±50% 여유)")
    print("=" * 64)
    print(f"입력: 길드 {inputs.guilds} · 길드당 채널 {inputs.channels_per_guild} · 채널당 분당 메시지 {inputs.msgs_per_channel_per_min}")
    print(f"      이벤트 배수: reaction×{inputs.reaction_ratio} typing×{inputs.typing_ratio} edit/delete×{inputs.edit_delete_ratio}")
    print(f"      기간 {inputs.hours}h · content_cipher 채움 비율 {inputs.cipher_fill_ratio}")
    print("-" * 64)
    print(f"시간당 이벤트       : {e.events_per_hour:,}")
    print(f"총 이벤트({inputs.hours}h)   : {e.events_total:,}")
    print(f"쓰기 처리율         : {e.write_rate_per_sec:,}/s")
    print(f"행 데이터           : {e.row_bytes_total / 1024 / 1024:.2f} MiB")
    print(f"인덱스(5개)         : {e.index_bytes_total / 1024 / 1024:.2f} MiB")
    print(f"암호 payload        : {e.cipher_bytes_total / 1024 / 1024:.2f} MiB")
    print(f"총 크기             : {e.total_mib:.2f} MiB")
    print(f"일일 증가(환산)     : {e.per_day_mib:.2f} MiB/day")
    print("-" * 64)
    print(f"TTL/파티셔닝 시점   : {ttl}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

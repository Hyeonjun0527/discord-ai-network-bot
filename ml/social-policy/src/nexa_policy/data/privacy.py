"""export 시 가명 재가명화(NEXA-P10-T014) + 원문 redaction·minimization(NEXA-P10-T015).

**T014 — 사용자 pseudonym 변환 (acceptance: 운영 DB pseudonym 과 학습 artifact pseudonym 이 직접 연결되지 않는다)**:
- export 시 actor/guild/channel/thread/event 가명을 **용도별 salt + guild-scope** 로 다시 해시한다.
- 결과는 결정론(같은 운영 가명 + 같은 salt → 같은 학습 가명)이지만 일방향(BLAKE2b)이라 역연결 불가.
- guild-scope: 같은 운영 user 가명이라도 다른 길드에서는 다른 학습 가명이 된다(cross-guild 연결 차단).
- 실제 user id/snowflake 는 이 모듈에 **들어오지 않는다** — 이미 가명화된 [EventRecord] 만 입력으로 받는다.

**T015 — 원문 redaction·minimization (acceptance: GLM prompt 원문·첨부 URL·Discord snowflake 가 Parquet 에 없다)**:
- features/masks 에서 허용 신호 키만 남기고(allow-list) 원문/URL/식별자 흔적은 제거한다.
- 정책 모델에 불필요한 raw text 는 제거하고, 길이/질문여부 같은 짧은 local feature 신호만 남긴다.
- [redact_record] 는 schema.conform 으로 fail-closed 검증을 통과한 안전한 행만 내보낸다.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, replace

from nexa_policy.data.schema import EventRecord, SchemaError, conform, load_schema

# T015: features 에 남길 수 있는 신호 키 allow-list(원문/파생 텍스트 금지). schema.json properties 와 일치.
_ALLOWED_FEATURE_KEYS = frozenset(
    {
        "char_len_bucket",
        "is_question",
        "mentions_nexa",
        "reply_to_event_id",
        "mention_target_pseudonym",
        "reaction_code",
        "gap_to_prev_ms",
    }
)
# T015: masks 에 남길 수 있는 키 allow-list.
_ALLOWED_MASK_KEYS = frozenset({"is_observable", "consent_opt_in"})

# T015: 가명 값 자리에 원문/URL/snowflake 가 끼었는지 잡는 fail-closed 패턴.
_DISCORD_SNOWFLAKE = re.compile(r"\b\d{17,20}\b")
_URL = re.compile(r"https?://", re.IGNORECASE)


class PrivacyError(SchemaError):
    """가명화·redaction 불변식 위반(fail-closed)."""


@dataclass(frozen=True)
class PseudonymPolicy:
    """용도별(purpose) salt 로 학습 artifact 가명을 결정론·일방향 재생성하는 정책.

    - [purpose_salt]: 학습 export 용 별도 비밀 salt. 운영 DB 가명 salt 와 달라야 운영↔학습 가명이
      직접 연결되지 않는다(T014 acceptance).
    - 재가명화는 guild-scope: 같은 actor 가명이라도 길드가 다르면 다른 학습 가명.
    """

    purpose_salt: str
    digest_size: int = 12

    def __post_init__(self) -> None:
        if not self.purpose_salt.strip():
            raise PrivacyError("용도별 salt(purpose_salt)는 비어 있을 수 없다.")
        if self.digest_size < 8:
            raise PrivacyError("학습 가명 digest 는 최소 8바이트여야 한다(충돌 방지).")

    def _hash(self, *, namespace: str, guild_scope: str, value: str) -> str:
        """guild-scope + namespace + value 를 salt 와 함께 일방향 해시한다.

        salt 를 BLAKE2b key 로 써서 salt 없이는 재현/역연결이 불가능하게 한다.
        """
        payload = f"{namespace}\x1f{guild_scope}\x1f{value}".encode()
        digest = hashlib.blake2b(
            payload, key=self.purpose_salt.encode(), digest_size=self.digest_size
        ).hexdigest()
        return f"{namespace[:1]}_{digest}"

    def repseudonymize_guild(self, guild_pseudonym: str) -> str:
        # guild 자체는 자기 자신을 scope 로(길드 가명의 안정 재해시).
        return self._hash(namespace="guild", guild_scope=guild_pseudonym, value=guild_pseudonym)

    def repseudonymize_actor(self, *, guild_pseudonym: str, actor_pseudonym: str) -> str:
        return self._hash(namespace="actor", guild_scope=guild_pseudonym, value=actor_pseudonym)

    def repseudonymize_ref(
        self, *, namespace: str, guild_pseudonym: str, value: str
    ) -> str:
        """채널/스레드/이벤트/burst/scene 등 guild-scope 참조 가명을 재해시한다."""
        return self._hash(namespace=namespace, guild_scope=guild_pseudonym, value=value)


def _assert_no_raw_traces(value: str, *, field: str) -> None:
    """가명 값에 snowflake/URL 원문 흔적이 없는지 단언한다(T015 fail-closed)."""
    if _DISCORD_SNOWFLAKE.search(value):
        raise PrivacyError(f"{field} 에 Discord snowflake 형태가 감지됨(원문/식별자 누출): {value!r}")
    if _URL.search(value):
        raise PrivacyError(f"{field} 에 URL 이 감지됨(첨부 URL 누출): {value!r}")


def _minimize_signals(
    signals: dict[str, object], *, allowed: frozenset[str], field: str
) -> dict[str, object]:
    """allow-list 키만 남기고, 문자열 값에 원문/URL/snowflake 흔적이 없는지 검사한다."""
    out: dict[str, object] = {}
    for key, val in signals.items():
        if key not in allowed:
            continue  # 허용 목록 밖 키(원문/파생 텍스트 후보)는 제거(minimization).
        if isinstance(val, str):
            _assert_no_raw_traces(val, field=f"{field}.{key}")
        out[key] = val
    return out


def repseudonymize_record(record: EventRecord, policy: PseudonymPolicy) -> EventRecord:
    """한 레코드의 모든 가명을 용도별 salt 로 guild-scope 재해시한다(T014).

    먼저 새 guild 가명을 만들고, 나머지 참조는 **원래 guild 가명** 을 scope 로 묶어
    같은 길드 안에서의 관계(같은 actor 는 같은 새 가명)는 보존하되 cross-guild 연결은 끊는다.
    """
    src_guild = record.guild_pseudonym
    new_guild = policy.repseudonymize_guild(src_guild)

    def ref(namespace: str, value: str) -> str:
        return policy.repseudonymize_ref(
            namespace=namespace, guild_pseudonym=src_guild, value=value
        )

    new_features = dict(record.features)
    for fk in ("reply_to_event_id", "mention_target_pseudonym"):
        fv = new_features.get(fk)
        if isinstance(fv, str) and fv:
            ns = "event" if fk == "reply_to_event_id" else "actor"
            new_features[fk] = ref(ns, fv)

    return replace(
        record,
        guild_pseudonym=new_guild,
        channel_pseudonym=ref("channel", record.channel_pseudonym),
        thread_pseudonym=ref("thread", record.thread_pseudonym)
        if record.thread_pseudonym
        else None,
        event_id=ref("event", record.event_id),
        burst_id=ref("burst", record.burst_id),
        scene_id=ref("scene", record.scene_id),
        actor_pseudonym=policy.repseudonymize_actor(
            guild_pseudonym=src_guild, actor_pseudonym=record.actor_pseudonym
        ),
        features=new_features,
    )


def redact_record(record: EventRecord) -> EventRecord:
    """원문/식별자를 제거·최소화한 레코드를 만든다(T015).

    - features/masks 를 allow-list 로 좁히고 원문/URL/snowflake 흔적을 거부한다.
    - 결과 row 는 schema.conform 을 통과해야 한다(fail-closed) — 원문 컬럼이 몰래 끼면 export 가 실패한다.
    """
    minimized_features = _minimize_signals(
        record.features, allowed=_ALLOWED_FEATURE_KEYS, field="features"
    )
    minimized_masks = _minimize_signals(
        record.masks, allowed=_ALLOWED_MASK_KEYS, field="masks"
    )
    redacted = replace(record, features=minimized_features, masks=minimized_masks)
    # schema conformance(금지 컬럼/원문 흔적) 강제 — 통과 못 하면 PrivacyError.
    conform(redacted.to_row(), load_schema())
    return redacted


def privatize_record(record: EventRecord, policy: PseudonymPolicy) -> EventRecord:
    """T014 재가명화 후 T015 redaction 을 순서대로 적용한 export-ready 레코드."""
    return redact_record(repseudonymize_record(record, policy))

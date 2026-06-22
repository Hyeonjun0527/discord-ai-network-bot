"""합성 학습 데이터셋(P11). 운영 데이터 미접근 — seed 결정론 fixture 만.

feature 벡터(P08 카탈로그 차원)와 멀티태스크 라벨(action/target/delay/burst/social-act, UNKNOWN mask
포함)을 한 seed 에서 결정론적으로 만든다. 클래스 불균형(대다수 IGNORE/SILENT)을 의도적으로 넣어
baseline·imbalance 실험(T003/T014)이 의미를 갖게 한다.

라벨 의미는 P10 라벨러(action/target/delay/burst/social_act)와 일치하는 클래스 집합을 쓴다.
샘플 수는 수백 단위(초 단위 학습)로 제한한다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.data.labels.action import ActionClass
from nexa_policy.features.schema import FeatureCatalog, load_feature_catalog
from nexa_policy.reproducibility import rng

if TYPE_CHECKING:
    import numpy as np

# 멀티클래스 action head 의 안정 클래스 순서(IGNORE/WAIT/REACT/SPEAK/CANCEL). UNKNOWN 은 mask 로 처리.
ACTION_HEAD_CLASSES: tuple[str, ...] = ("ignore", "wait", "react", "speak", "cancel")
# delay bin 순서(P08 delay bins + never). bins 는 단조 경계, 마지막은 never.
DELAY_BINS: tuple[str, ...] = ("0-2s", "2-10s", "10-60s", "60s+", "never")
# burst shape: message_count bucket(0/1/2+) × reaction-only.
BURST_COUNT_BUCKETS: tuple[str, ...] = ("none", "single", "multi")
# social act(보조) — central SocialAct 미러.
SOCIAL_ACT_CLASSES: tuple[str, ...] = (
    "acknowledge",
    "agree",
    "ask",
    "tease",
    "self_disclose",
    "unknown",
)
# target ranking: 후보 슬롯 수(padding 포함). 라벨은 선택된 후보 index 또는 -1(none).
N_TARGET_CANDIDATES = 4


@dataclass(frozen=True)
class PolicyDataset:
    """멀티태스크 정책 학습용 합성 데이터셋(numpy 배열).

    - [features]/[missing_mask]: (n, dim).
    - [guild_ids]: (n,) 길드 holdout/split 용 가명.
    - [event_time_ms]: (n,) 시간 holdout 용.
    - [action_labels]: (n,) int — ACTION_HEAD_CLASSES index, UNKNOWN 은 -1.
    - [action_mask]: (n,) float — 라벨 있으면 1.0, UNKNOWN 0.0.
    - [target_labels]: (n,) int — 선택 후보 index(0..N-1) 또는 -1(none/no-label).
    - [target_candidate_mask]: (n, N_TARGET_CANDIDATES) — padding/privacy-excluded 후보 0.0.
    - [delay_labels]/[delay_mask]: (n,) bin index, censored 면 mask 0.0.
    - [burst_labels]/[burst_mask]: (n,) count bucket index.
    - [act_labels]/[act_weight]: (n,) social act index, weak confidence weight(0~1).
    """

    catalog: FeatureCatalog
    features: np.ndarray
    missing_mask: np.ndarray
    guild_ids: list[str]
    event_time_ms: np.ndarray
    action_labels: np.ndarray
    action_mask: np.ndarray
    target_labels: np.ndarray
    target_candidate_mask: np.ndarray
    delay_labels: np.ndarray
    delay_mask: np.ndarray
    burst_labels: np.ndarray
    burst_mask: np.ndarray
    act_labels: np.ndarray
    act_weight: np.ndarray

    @property
    def n(self) -> int:
        return int(self.features.shape[0])

    @property
    def dim(self) -> int:
        return self.catalog.dim


def make_synthetic_dataset(
    *,
    n_samples: int = 240,
    n_guilds: int = 8,
    seed: int = 20260622,
    unknown_action_frac: float = 0.1,
    catalog: FeatureCatalog | None = None,
) -> PolicyDataset:
    """seed 결정론 합성 데이터셋. 같은 seed/인자 → 같은 배열.

    feature 와 라벨에 약한 인과 구조를 넣어(예: mentions_nexa 신호가 SPEAK 확률을 올림) 모델이
    무작위보다 나은 신호를 학습할 수 있게 한다. 동시에 다수 클래스가 IGNORE 가 되도록 편향을 둔다.
    """
    import numpy as np

    cat = catalog or load_feature_catalog()
    gen = rng(seed)
    dim = cat.dim

    # feature: [0,1) 정규화 잡음 + 일부 신호 채널.
    feats = gen.random((n_samples, dim)).astype(np.float32)
    missing = np.zeros((n_samples, dim), dtype=np.float32)
    # 일부 셀을 결정론적으로 missing 처리(0 과 '모름' 구분 학습 신호).
    miss_idx = gen.random((n_samples, dim)) < 0.05
    missing[miss_idx] = 1.0
    feats[miss_idx] = 0.0

    # 신호 채널: burst.is_question(idx 3), burst.has_mention(idx 4) 를 0/1 로.
    q_idx, mention_idx = 3, 4
    feats[:, q_idx] = (gen.random(n_samples) < 0.3).astype(np.float32)
    feats[:, mention_idx] = (gen.random(n_samples) < 0.25).astype(np.float32)
    missing[:, [q_idx, mention_idx]] = 0.0

    guild_ids = [f"guild-{int(g)}" for g in gen.integers(0, n_guilds, size=n_samples)]
    base_time = 1_700_000_000_000
    event_time_ms = (base_time + np.sort(gen.integers(0, 10_000_000, size=n_samples))).astype(
        np.int64
    )

    # action: mention/question 이 있으면 SPEAK/REACT 확률↑, 아니면 대부분 IGNORE(불균형).
    speak_logit = (
        -1.6
        + 2.2 * feats[:, mention_idx]
        + 1.4 * feats[:, q_idx]
        + 0.5 * (gen.random(n_samples) - 0.5)
    )
    react_logit = -1.2 + 1.0 * feats[:, q_idx] + 0.5 * (gen.random(n_samples) - 0.5)
    wait_logit = np.full(n_samples, -2.5) + 0.3 * (gen.random(n_samples) - 0.5)
    cancel_logit = np.full(n_samples, -3.5) + 0.2 * (gen.random(n_samples) - 0.5)
    ignore_logit = np.full(n_samples, 0.8)
    logits = np.stack(
        [ignore_logit, wait_logit, react_logit, speak_logit, cancel_logit], axis=1
    )
    probs = _softmax(logits)
    action_labels = np.array(
        [gen.choice(len(ACTION_HEAD_CLASSES), p=probs[i]) for i in range(n_samples)],
        dtype=np.int64,
    )

    # UNKNOWN mask: 일부 샘플은 관찰 불가 → action_mask 0(UNKNOWN 은 -1 라벨).
    action_mask = (gen.random(n_samples) >= unknown_action_frac).astype(np.float32)
    action_labels = np.where(action_mask > 0, action_labels, -1).astype(np.int64)

    # target ranking: SPEAK/REACT 일 때만 target 존재. mention 이면 후보0 선택.
    speak_idx = ACTION_HEAD_CLASSES.index("speak")
    react_idx = ACTION_HEAD_CLASSES.index("react")
    has_target = np.isin(action_labels, [speak_idx, react_idx]) & (action_mask > 0)
    target_labels = np.full(n_samples, -1, dtype=np.int64)
    candidate_mask = np.zeros((n_samples, N_TARGET_CANDIDATES), dtype=np.float32)
    n_valid = gen.integers(1, N_TARGET_CANDIDATES + 1, size=n_samples)
    for i in range(n_samples):
        candidate_mask[i, : n_valid[i]] = 1.0
        if has_target[i]:
            # mention 이면 후보0, 아니면 유효 후보 중 결정론 선택.
            chosen = 0 if feats[i, mention_idx] > 0 else int(gen.integers(0, n_valid[i]))
            target_labels[i] = chosen

    # delay bin: SPEAK 면 빠른 bin, 그 외 느린/never. censored 일부는 mask.
    delay_labels = np.full(n_samples, len(DELAY_BINS) - 1, dtype=np.int64)  # never 기본.
    for i in range(n_samples):
        if action_mask[i] == 0:
            continue
        if action_labels[i] == speak_idx:
            delay_labels[i] = int(gen.integers(0, 2))  # 0-2s/2-10s.
        elif action_labels[i] == react_idx:
            delay_labels[i] = int(gen.integers(1, 3))
        else:
            delay_labels[i] = len(DELAY_BINS) - 1  # never.
    # censored: 행동 없는데 세션 종료로 잘린 일부 → loss mask 0.
    delay_mask = action_mask.copy()
    censored = (gen.random(n_samples) < 0.08) & (delay_labels == len(DELAY_BINS) - 1)
    delay_mask[censored] = 0.0

    # burst shape: SPEAK 면 single/multi, REACT/그외 none.
    burst_labels = np.zeros(n_samples, dtype=np.int64)  # none.
    burst_mask = np.zeros(n_samples, dtype=np.float32)
    for i in range(n_samples):
        if action_mask[i] == 0:
            continue
        if action_labels[i] == speak_idx:
            burst_labels[i] = 1 + int(gen.random() < 0.4)  # single or multi.
            burst_mask[i] = 1.0
        elif action_labels[i] == react_idx:
            burst_labels[i] = 0  # reaction-only → none(메시지 0).
            burst_mask[i] = 1.0

    # social act(보조): weak label, 낮은 confidence 는 weight 낮음/0.
    act_labels = np.full(n_samples, SOCIAL_ACT_CLASSES.index("unknown"), dtype=np.int64)
    act_weight = np.zeros(n_samples, dtype=np.float32)
    for i in range(n_samples):
        if action_mask[i] == 0 or action_labels[i] not in (speak_idx, react_idx):
            continue
        if feats[i, q_idx] > 0:
            act_labels[i] = SOCIAL_ACT_CLASSES.index("ask")
            act_weight[i] = 0.8
        elif action_labels[i] == react_idx:
            act_labels[i] = SOCIAL_ACT_CLASSES.index("acknowledge")
            act_weight[i] = 0.7
        else:
            act_labels[i] = SOCIAL_ACT_CLASSES.index(
                gen.choice(["agree", "tease", "self_disclose"])
            )
            # 낮은 confidence 약지도 → weight 0.3, 더 모호하면 0(제외).
            act_weight[i] = 0.3 if gen.random() < 0.7 else 0.0

    return PolicyDataset(
        catalog=cat,
        features=feats,
        missing_mask=missing,
        guild_ids=guild_ids,
        event_time_ms=event_time_ms,
        action_labels=action_labels,
        action_mask=action_mask,
        target_labels=target_labels,
        target_candidate_mask=candidate_mask,
        delay_labels=delay_labels,
        delay_mask=delay_mask,
        burst_labels=burst_labels,
        burst_mask=burst_mask,
        act_labels=act_labels,
        act_weight=act_weight,
    )


def action_is_speak(labels: np.ndarray) -> np.ndarray:
    """action 라벨에서 SPEAK 여부(이진 SPEAK/SILENT baseline 용). UNKNOWN(-1)은 False."""
    import numpy as np

    speak_idx = ACTION_HEAD_CLASSES.index("speak")
    return (labels == speak_idx).astype(np.int64) if labels.size else np.array([], dtype=np.int64)


def _softmax(logits: np.ndarray) -> np.ndarray:
    import numpy as np

    z = logits - logits.max(axis=-1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=-1, keepdims=True)


# ActionClass 미러 sanity(드리프트 가드): head 클래스가 P10 ActionClass 와 일치하는지.
def assert_action_class_alignment() -> None:
    """ACTION_HEAD_CLASSES 가 P10 ActionClass(UNKNOWN 제외) ∪ {cancel} 과 정합한지 단언한다."""
    p10 = {c.value for c in ActionClass} - {"unknown"}
    head = set(ACTION_HEAD_CLASSES)
    missing = p10 - head
    if missing:
        raise ValueError(f"action head 가 P10 ActionClass 를 누락: {missing}")

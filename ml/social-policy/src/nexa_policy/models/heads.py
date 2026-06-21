"""멀티헤드 정책 모델(NEXA-P11-T008~T012).

공유 MLP trunk 위에 5개 head 를 둔다. 각 head 는 P10 라벨 태스크에 1:1 대응한다:

- action head(T008): IGNORE/WAIT/REACT/SPEAK/CANCEL 확률(softmax). 지원 없는 action 은 mask,
  확률 합은 1(softmax 보장).
- target ranking head(T009): 후보 슬롯별 점수. padding/privacy-excluded 후보는 -inf 로 마스킹돼
  선택되지 않는다.
- delay head(T010): P08 delay bin + never 확률. censored 라벨은 loss 에서 mask(여기선 head 만 정의,
  mask 는 training/losses).
- burst head(T011): message count bucket(none/single/multi) — 실제 문장 token 을 만들지 않는다
  (분류만, 텍스트 생성 없음).
- social-act head(T012): weak/gold social act 분포. 낮은 confidence 라벨은 weight 로 제외(losses).

모든 head 는 numpy [Linear] 라 [export.onnx] 가 그대로 그래프로 내보낸다(파이썬 추론 일치, T017).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.datasets import (
    ACTION_HEAD_CLASSES,
    BURST_COUNT_BUCKETS,
    DELAY_BINS,
    N_TARGET_CANDIDATES,
    SOCIAL_ACT_CLASSES,
)
from nexa_policy.models.nn import Linear, relu, softmax

if TYPE_CHECKING:
    import numpy as np

N_ACTIONS = len(ACTION_HEAD_CLASSES)
N_DELAY_BINS = len(DELAY_BINS)
N_BURST = len(BURST_COUNT_BUCKETS)
N_ACTS = len(SOCIAL_ACT_CLASSES)


@dataclass
class MultiHeadPolicyModel:
    """공유 trunk + 5 head. forward 는 head 별 logits/확률을 낸다(결정론)."""

    in_dim: int
    hidden_dim: int
    trunk: Linear
    trunk2: Linear
    action_head: Linear
    target_head: Linear
    delay_head: Linear
    burst_head: Linear
    act_head: Linear

    @classmethod
    def build(cls, *, in_dim: int, hidden_dim: int = 16, seed: int = 20260622) -> (
        MultiHeadPolicyModel
    ):
        trunk = Linear(in_dim, hidden_dim)
        trunk2 = Linear(hidden_dim, hidden_dim)
        action_head = Linear(hidden_dim, N_ACTIONS)
        # target head: 각 후보에 1 점수(공유 가중치, 후보 feature 없으므로 hidden→N_candidates).
        target_head = Linear(hidden_dim, N_TARGET_CANDIDATES)
        delay_head = Linear(hidden_dim, N_DELAY_BINS)
        burst_head = Linear(hidden_dim, N_BURST)
        act_head = Linear(hidden_dim, N_ACTS)
        for i, layer in enumerate(
            (trunk, trunk2, action_head, target_head, delay_head, burst_head, act_head)
        ):
            layer.init(seed + i)
        return cls(
            in_dim=in_dim,
            hidden_dim=hidden_dim,
            trunk=trunk,
            trunk2=trunk2,
            action_head=action_head,
            target_head=target_head,
            delay_head=delay_head,
            burst_head=burst_head,
            act_head=act_head,
        )

    @property
    def layers(self) -> tuple[Linear, ...]:
        return (
            self.trunk,
            self.trunk2,
            self.action_head,
            self.target_head,
            self.delay_head,
            self.burst_head,
            self.act_head,
        )

    def forward_trunk(self, x: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        """x → (h1_pre, h1, h2). h2 가 head 입력."""
        h1_pre = self.trunk.forward(x)
        h1 = relu(h1_pre)
        h2_pre = self.trunk2.forward(h1)
        h2 = relu(h2_pre)
        return h1_pre, h2_pre, h2

    def action_proba(self, x: np.ndarray) -> np.ndarray:
        """action 확률(softmax, 합=1). 지원 없는 action 마스킹은 [masked_action_proba]."""
        _, _, h2 = self.forward_trunk(x)
        return softmax(self.action_head.forward(h2))

    def masked_action_proba(self, x: np.ndarray, support_mask: np.ndarray) -> np.ndarray:
        """support_mask(0/1, shape (N_ACTIONS,) 또는 (n, N_ACTIONS))로 미지원 action 을 0 으로 빼고
        재정규화한다(확률 합=1 유지, T008 acceptance)."""
        import numpy as np

        _, _, h2 = self.forward_trunk(x)
        logits = self.action_head.forward(h2)
        mask = np.broadcast_to(support_mask, logits.shape).astype(np.float64)
        neg_inf = np.where(mask > 0, 0.0, -1e30)
        return softmax(logits + neg_inf)

    def target_scores(self, x: np.ndarray, candidate_mask: np.ndarray) -> np.ndarray:
        """후보별 점수. candidate_mask(0/1) 0 인 후보(padding/privacy-excluded)는 -inf → 선택 불가
        (T009 acceptance). softmax 로 ranking 확률 반환."""
        import numpy as np

        _, _, h2 = self.forward_trunk(x)
        scores = self.target_head.forward(h2)
        masked = np.where(candidate_mask > 0, scores, -1e30)
        return softmax(masked)

    def delay_proba(self, x: np.ndarray) -> np.ndarray:
        _, _, h2 = self.forward_trunk(x)
        return softmax(self.delay_head.forward(h2))

    def burst_proba(self, x: np.ndarray) -> np.ndarray:
        _, _, h2 = self.forward_trunk(x)
        return softmax(self.burst_head.forward(h2))

    def act_proba(self, x: np.ndarray) -> np.ndarray:
        _, _, h2 = self.forward_trunk(x)
        return softmax(self.act_head.forward(h2))

    def all_logits(self, x: np.ndarray) -> dict[str, np.ndarray]:
        """ONNX export·multitask loss 용 raw logits(softmax 전). head 이름 → (n, C)."""
        _, _, h2 = self.forward_trunk(x)
        return {
            "action": self.action_head.forward(h2),
            "target": self.target_head.forward(h2),
            "delay": self.delay_head.forward(h2),
            "burst": self.burst_head.forward(h2),
            "act": self.act_head.forward(h2),
        }

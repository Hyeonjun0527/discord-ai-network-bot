"""서버 문화 embedding(NEXA-P19-T004). 운영 데이터 미접근 — 합성 fixture·결정론. torch 미사용(numpy).

길드(서버)의 **관찰 통계**(tempo·burst·reaction)만으로 작은 culture representation(저차원 벡터)을 학습한다.
이 표현은 P19-T005~T007 의 server-conditioned 적응(talkativeness·delay·action mix)의 입력이 된다.

acceptance(T004) — 원본 guild ID memorization 없이 unseen guild 적응을 평가한다:
- **입력은 통계뿐**(guild id 미입력). [ServerCultureStats] 에 식별자 필드가 없고, 인코더는 통계 벡터만 받는다
  → 모델이 특정 길드를 외울 수 없다(structural guard). [GUILD_ID_IS_NOT_AN_INPUT] 가드로 단언한다.
- **unseen 일반화**: train 길드로 인코더를 적합하고, **train 에 없던** 길드 통계로 재구성 오차를 평가한다
  ([evaluate_unseen_generalization]). 비슷한 문화의 unseen 길드는 train 길드 근처로 임베딩돼야 한다(memorization
  이면 unseen 에서 무너진다 — generalization.py 의 부분군 붕괴 정신과 일관).

구조: 작은 선형 오토인코더(bottleneck = culture dim). 통계를 정규화 → encode(저차원) → decode(재구성).
학습은 결정론 numpy gradient descent(소수 step). 무거운 학습 금지.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from nexa_policy.models.nn import Linear

if TYPE_CHECKING:
    import numpy as np

# guild id 는 인코더 입력이 절대 아니다(memorization 금지 구조 가드 — acceptance T004).
GUILD_ID_IS_NOT_AN_INPUT = True

# 관찰 통계 차원(tempo·burst·reaction 계열). 식별자 없음.
STAT_FIELDS: tuple[str, ...] = (
    "messages_per_hour",      # tempo: 시간당 메시지(빠른/느린 서버).
    "median_burst_size",      # burst: 한 번에 몰아치는 메시지 수.
    "reaction_per_message",   # reaction 문화: 메시지당 reaction.
    "active_fraction",        # 동시 활동 비율(붐비는/한산한).
    "thread_fraction",        # thread 사용 비율(구조적 대화 문화).
)


@dataclass(frozen=True)
class ServerCultureStats:
    """한 길드의 관찰된 문화 통계. **식별자 필드 없음**(guild id 미포함 — memorization 금지)."""

    messages_per_hour: float
    median_burst_size: float
    reaction_per_message: float
    active_fraction: float
    thread_fraction: float

    def to_vector(self) -> np.ndarray:
        import numpy as np

        return np.array(
            [
                self.messages_per_hour,
                self.median_burst_size,
                self.reaction_per_message,
                self.active_fraction,
                self.thread_fraction,
            ],
            dtype=np.float64,
        )


def _normalizer(matrix: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """열별 mean/std 를 구해 정규화 통계를 만든다(std 0 은 1 로 보호)."""
    import numpy as np

    mean = matrix.mean(axis=0)
    std = matrix.std(axis=0)
    std = np.where(std < 1e-8, 1.0, std)
    return mean, std


@dataclass(frozen=True)
class ServerCultureEncoder:
    """학습된 통계→culture embedding 인코더(선형 오토인코더의 인코더 부분).

    [enc]/[dec] 는 numpy Linear. [mean]/[std] 는 입력 정규화 통계. culture dim = enc.out_dim.
    """

    enc: Linear
    dec: Linear
    mean: np.ndarray
    std: np.ndarray

    @property
    def culture_dim(self) -> int:
        return self.enc.out_dim

    def _normalize(self, x: np.ndarray) -> np.ndarray:
        return (x - self.mean) / self.std

    def encode(self, stats: ServerCultureStats) -> np.ndarray:
        """통계 → culture embedding(저차원). guild id 미사용."""
        x = self._normalize(stats.to_vector().reshape(1, -1))
        return self.enc.forward(x).reshape(-1)

    def reconstruction_error(self, stats: ServerCultureStats) -> float:
        """encode→decode 재구성 MSE(정규화 공간). 일반화 평가의 단위."""
        import numpy as np

        x = self._normalize(stats.to_vector().reshape(1, -1))
        recon = self.dec.forward(self.enc.forward(x))
        return float(np.mean((recon - x) ** 2))


def fit_culture_encoder(
    train_stats: list[ServerCultureStats],
    *,
    culture_dim: int = 2,
    seed: int = 20260622,
    epochs: int = 400,
    lr: float = 0.02,
) -> ServerCultureEncoder:
    """train 길드 통계로 선형 오토인코더를 적합한다(결정론 numpy GD). 식별자 미사용.

    bottleneck=culture_dim 으로 통계를 압축·복원하도록 학습 → 문화 유사 길드가 가까운 임베딩을 얻는다.
    수치 안정을 위해 작은 lr + gradient L2 norm clipping(발산 방지) 을 쓴다.
    """
    import numpy as np

    if len(train_stats) < 2:
        raise ValueError("culture encoder 적합에는 최소 2개 길드 통계가 필요하다.")
    raw = np.stack([s.to_vector() for s in train_stats])
    mean, std = _normalizer(raw)
    x = (raw - mean) / std

    in_dim = x.shape[1]
    enc = Linear(in_dim, culture_dim)
    dec = Linear(culture_dim, in_dim)
    enc.init(seed)
    dec.init(seed + 1)

    clip_norm = 5.0
    for _ in range(epochs):
        enc.zero_grad()
        dec.zero_grad()
        h = enc.forward(x)
        recon = dec.forward(h)
        grad = (2.0 / x.shape[0]) * (recon - x)
        dh = dec.backward(grad)
        enc.backward(dh)
        _clip_grads(enc, clip_norm)
        _clip_grads(dec, clip_norm)
        enc.step(lr)
        dec.step(lr)

    return ServerCultureEncoder(enc=enc, dec=dec, mean=mean, std=std)


def _clip_grads(layer: Linear, max_norm: float) -> None:
    """layer 의 grad L2 norm 을 max_norm 으로 제한한다(발산 방지)."""
    import numpy as np

    norm = float(np.sqrt((layer.gW**2).sum() + (layer.gb**2).sum()))
    if norm > max_norm and norm > 0.0:
        scale = max_norm / norm
        layer.gW *= scale
        layer.gb *= scale


@dataclass(frozen=True)
class UnseenGeneralizationReport:
    """unseen 길드 일반화 리포트(재구성 오차 train vs unseen). memorization 이면 unseen 이 크게 나빠진다."""

    train_reconstruction_error: float
    unseen_reconstruction_error: float

    @property
    def generalization_gap(self) -> float:
        """unseen - train 재구성 오차. 클수록 memorization/과적합 의심."""
        return self.unseen_reconstruction_error - self.train_reconstruction_error

    def generalizes(self, *, max_gap: float) -> bool:
        """unseen 재구성 오차가 train 대비 [max_gap] 이내면 일반화로 본다(memorization 아님)."""
        return self.generalization_gap <= max_gap

    def to_dict(self) -> dict[str, object]:
        return {
            "train_reconstruction_error": self.train_reconstruction_error,
            "unseen_reconstruction_error": self.unseen_reconstruction_error,
            "generalization_gap": self.generalization_gap,
        }


def evaluate_unseen_generalization(
    encoder: ServerCultureEncoder,
    *,
    train_stats: list[ServerCultureStats],
    unseen_stats: list[ServerCultureStats],
) -> UnseenGeneralizationReport:
    """train·unseen 길드 통계의 평균 재구성 오차를 비교한다(unseen 적응 평가)."""
    import numpy as np

    train_err = float(np.mean([encoder.reconstruction_error(s) for s in train_stats]))
    unseen_err = float(np.mean([encoder.reconstruction_error(s) for s in unseen_stats]))
    return UnseenGeneralizationReport(
        train_reconstruction_error=train_err,
        unseen_reconstruction_error=unseen_err,
    )

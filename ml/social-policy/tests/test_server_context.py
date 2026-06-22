"""NEXA-P19-T004: 서버 문화 embedding — guild id memorization 없이 unseen 일반화를 평가한다."""

from __future__ import annotations

import numpy as np

from nexa_policy.models.server_context import (
    GUILD_ID_IS_NOT_AN_INPUT,
    STAT_FIELDS,
    ServerCultureStats,
    evaluate_unseen_generalization,
    fit_culture_encoder,
)


def _stats(mph, burst, rpm, active, thread):
    return ServerCultureStats(
        messages_per_hour=mph,
        median_burst_size=burst,
        reaction_per_message=rpm,
        active_fraction=active,
        thread_fraction=thread,
    )


def _two_cultures(n_per=6, seed=0):
    """두 문화군(빠름·reaction중심 vs 느림·thread중심)을 결정론으로 만든다."""
    gen = np.random.default_rng(seed)
    fast, slow = [], []
    for _ in range(n_per):
        fast.append(_stats(60 + gen.random() * 5, 4 + gen.random(), 0.8 + gen.random() * 0.1, 0.6, 0.1))
        slow.append(_stats(5 + gen.random() * 2, 1 + gen.random() * 0.3, 0.1 + gen.random() * 0.05, 0.1, 0.7))
    return fast, slow


def test_acceptance_guild_id_는_입력이_아니다():
    # 구조적 가드 + ServerCultureStats 에 식별자 필드 없음.
    assert GUILD_ID_IS_NOT_AN_INPUT
    assert "guild_id" not in STAT_FIELDS
    assert not hasattr(ServerCultureStats(1, 1, 0.1, 0.1, 0.1), "guild_id")


def test_acceptance_unseen_일반화_평가():
    fast, slow = _two_cultures()
    train = fast[:4] + slow[:4]
    encoder = fit_culture_encoder(train, culture_dim=2, seed=1, epochs=400)
    # unseen 은 train 에 없던 같은 두 문화군의 추가 길드.
    unseen = fast[4:] + slow[4:]
    report = evaluate_unseen_generalization(encoder, train_stats=train, unseen_stats=unseen)
    # 같은 문화 분포의 unseen 은 train 대비 크게 나빠지지 않는다(memorization 아님).
    assert report.generalizes(max_gap=report.train_reconstruction_error + 0.5)
    assert report.unseen_reconstruction_error >= 0.0


def test_encode_차원과_결정론():
    fast, slow = _two_cultures()
    train = fast[:3] + slow[:3]
    enc1 = fit_culture_encoder(train, culture_dim=2, seed=7, epochs=100)
    enc2 = fit_culture_encoder(train, culture_dim=2, seed=7, epochs=100)
    v1 = enc1.encode(fast[0])
    v2 = enc2.encode(fast[0])
    assert v1.shape == (2,)
    assert np.allclose(v1, v2)  # 같은 seed → 같은 인코더.


def test_적합에_최소_2개_필요():
    try:
        fit_culture_encoder([_stats(1, 1, 0.1, 0.1, 0.1)])
        raise AssertionError("1개 통계로 적합하면 거부해야 한다")
    except ValueError:
        pass

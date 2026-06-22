"""NEXA-P19-T015: 보수적 offline RL PoC — OOD action 억제, OPE·simulation·human review 만(운영 미적용)."""

from __future__ import annotations

import numpy as np
import pytest

from nexa_policy.rl.train_conservative import (
    OfflineBatch,
    action_support_counts,
    conservative_review_summary,
    make_synthetic_offline_batch,
    ope_value,
    simulate_action_support,
    train_conservative_q,
)


def test_support_counts_mark_ood_as_zero():
    batch, _ = make_synthetic_offline_batch(ood_action=3)
    counts = action_support_counts(batch)
    # behavior 가 ood_action=3 을 한 번도 안 했으므로 support 0(OOD).
    assert float(counts[:, 3].sum()) == 0.0
    assert float(counts[:, 0].sum()) > 0.0


def test_acceptance_conservative_avoids_ood_action():
    batch, _ = make_synthetic_offline_batch(ood_action=3)
    # 충분히 보수적: OOD action 의 Q 가 끌려내려가 그리디가 데이터 안쪽만 고른다.
    result = train_conservative_q(batch, conservative_penalty=1.0)
    sim = simulate_action_support(result)
    assert sim["in_support_fraction"] == 1.0
    assert sim["greedy_ood"] == 0
    # 어떤 상태에서도 OOD action(3)을 그리디로 고르지 않는다.
    for s in range(batch.n_states):
        assert result.learned_greedy_action(s) != 3


def test_nonconservative_can_overvalue_ood():
    # penalty=0(비보수적)이면 OOD action 의 Q 가 0 근처라, support 안쪽 평균 reward 가 음수가 되도록
    # 만들면 OOD 가 선택될 수 있다 — 보수성의 효과를 대비한다.
    rng = np.random.default_rng(0)
    n_states, n_actions = 4, 3
    states = np.repeat(np.arange(n_states), 20)
    actions = rng.integers(0, n_actions - 1, size=states.size)  # action 2 는 OOD.
    rewards = np.full(states.size, -0.5)  # support 안쪽은 음수 reward.
    batch = OfflineBatch(
        states=states.astype(np.int64),
        actions=actions.astype(np.int64),
        rewards=rewards,
        n_states=n_states,
        n_actions=n_actions,
    )
    nonconservative = train_conservative_q(batch, conservative_penalty=0.0)
    sim = simulate_action_support(nonconservative)
    # 비보수적: OOD(action 2, Q≈0 > 음수)을 일부 상태에서 선택 → in_support_fraction < 1.
    assert sim["in_support_fraction"] < 1.0
    # 같은 데이터에 보수적이면 OOD 를 더 회피한다.
    conservative = train_conservative_q(batch, conservative_penalty=1.0)
    sim_c = simulate_action_support(conservative)
    assert sim_c["in_support_fraction"] >= sim["in_support_fraction"]


def test_acceptance_ope_only_no_production_integration():
    batch, beh = make_synthetic_offline_batch()
    result = train_conservative_q(batch, conservative_penalty=1.0)
    # 평가는 OPE(IPS)뿐 — CI·support 진단 동반(점추정만 아님).
    ope = ope_value(result, batch, behavior_action_prob=beh)
    assert ope["method"] == "ips"
    assert "ci_low" in ope and "ci_high" in ope
    assert "support" in ope
    # human review 요약은 production 미적용을 명시한다.
    review = conservative_review_summary(result, batch)
    assert review["production_applied"] is False
    assert "human gate" in review["review_note"]


def test_empty_batch_rejected():
    with pytest.raises(ValueError):
        OfflineBatch(
            states=np.array([], dtype=np.int64),
            actions=np.array([], dtype=np.int64),
            rewards=np.array([], dtype=np.float64),
            n_states=1,
            n_actions=1,
        )

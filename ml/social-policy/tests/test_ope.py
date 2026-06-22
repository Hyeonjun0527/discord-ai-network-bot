"""NEXA-P19-T013: offline policy evaluation — IPS/DR 점추정 + CI + support 진단(숨기지 않음)."""

from __future__ import annotations

import numpy as np
import pytest

from nexa_policy.rl.ope import estimate_dr, estimate_ips


def test_acceptance_IPS_점추정과_CI를_함께_보고():
    n = 50
    rewards = np.full(n, 1.0)
    target = np.full(n, 0.5)
    behavior = np.full(n, 0.5)  # weight=1 → V=mean(reward)=1.
    est = estimate_ips(rewards=rewards, target_action_prob=target, behavior_action_prob=behavior)
    assert est.value == pytest.approx(1.0)
    assert est.ci_low <= est.value <= est.ci_high
    assert est.method == "ips"


def test_acceptance_support_부족이_드러난다():
    # 한 표본이 매우 큰 weight 를 가져 ESS 가 작아진다(support 부족).
    n = 20
    rewards = np.ones(n)
    target = np.full(n, 0.9)
    behavior = np.full(n, 0.9)
    behavior[0] = 0.01  # 한 행동만 behavior 가 거의 안 함 → 큰 weight.
    target[0] = 0.9
    est = estimate_ips(rewards=rewards, target_action_prob=target, behavior_action_prob=behavior, clip=100.0)
    assert est.support.ess_fraction < 1.0  # 소수 표본이 지배 → ESS 작음.
    assert est.support.max_weight > 1.0


def test_높은_분산은_넓은_CI로_드러난다():
    rng = np.random.default_rng(0)
    n = 30
    rewards = rng.random(n)
    target = rng.random(n) * 0.9 + 0.05
    behavior = rng.random(n) * 0.9 + 0.05  # 큰 weight 변동 → 큰 분산.
    est = estimate_ips(rewards=rewards, target_action_prob=target, behavior_action_prob=behavior)
    assert est.ci_width > 0.0


def test_DR_추정과_CI():
    n = 40
    rewards = np.full(n, 1.0)
    target = np.full(n, 0.5)
    behavior = np.full(n, 0.5)
    q = np.full(n, 0.9)
    v = np.full(n, 0.9)
    est = estimate_dr(
        rewards=rewards,
        target_action_prob=target,
        behavior_action_prob=behavior,
        q_estimate=q,
        v_estimate=v,
    )
    # V_DR = mean(v + w*(r-q)) = 0.9 + 1*(1-0.9) = 1.0.
    assert est.value == pytest.approx(1.0)
    assert est.method == "dr"


def test_clip_비율_보고():
    n = 10
    rewards = np.ones(n)
    target = np.full(n, 0.9)
    behavior = np.full(n, 0.01)  # 큰 raw weight → clip.
    est = estimate_ips(rewards=rewards, target_action_prob=target, behavior_action_prob=behavior, clip=2.0)
    assert est.support.clipped_fraction == 1.0


def test_표본_부족_거부():
    with pytest.raises(ValueError):
        estimate_ips(rewards=np.array([1.0]), target_action_prob=np.array([0.5]), behavior_action_prob=np.array([0.5]))

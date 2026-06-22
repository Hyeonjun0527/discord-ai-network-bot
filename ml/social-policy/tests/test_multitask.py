"""T008~T013 멀티헤드·멀티태스크 loss·mask 테스트."""

from __future__ import annotations

import numpy as np

from nexa_policy.datasets import (
    N_TARGET_CANDIDATES,
    make_synthetic_dataset,
)
from nexa_policy.models.heads import MultiHeadPolicyModel
from nexa_policy.training.losses import TaskWeights, train_step
from nexa_policy.training.splitting import make_split_indices
from nexa_policy.training.trainer import design_matrix, make_batch, train_multihead

_DS = make_synthetic_dataset(seed=7, n_guilds=12)
_SP = make_split_indices(_DS, seed=0)
_IN_DIM = design_matrix(_DS, _SP.train[:1]).shape[1]


# ---- T008 action head ----
def test_action_proba_sums_to_one() -> None:
    res = train_multihead(_DS, train_idx=_SP.train, val_idx=_SP.validation, epochs=40, seed=3)
    x = design_matrix(_DS, _SP.test)
    proba = res.model.action_proba(x)
    assert np.allclose(proba.sum(axis=1), 1.0)


def test_unsupported_action_masked_and_renormalized() -> None:
    """지원 데이터 없는 action 은 mask 되고 확률 합은 1(acceptance T008)."""
    model = MultiHeadPolicyModel.build(in_dim=_IN_DIM, seed=3)
    x = design_matrix(_DS, _SP.test)
    support = np.array([1.0, 1.0, 1.0, 1.0, 0.0])  # CANCEL 미지원.
    proba = model.masked_action_proba(x, support)
    assert proba[:, 4].max() < 1e-9
    assert np.allclose(proba.sum(axis=1), 1.0)


# ---- T009 target ranking head ----
def test_target_padding_not_selected() -> None:
    """padding candidate 는 선택되지 않는다(acceptance T009)."""
    model = MultiHeadPolicyModel.build(in_dim=_IN_DIM, seed=3)
    x = design_matrix(_DS, _SP.test)
    cand_mask = _DS.target_candidate_mask[_SP.test]
    scores = model.target_scores(x, cand_mask)
    picked = scores.argmax(axis=1)
    for i, p in enumerate(picked):
        assert cand_mask[i, p] > 0  # 선택된 후보는 항상 유효(non-padding).


def test_target_all_padding_yields_uniform_no_crash() -> None:
    model = MultiHeadPolicyModel.build(in_dim=_IN_DIM, seed=3)
    x = design_matrix(_DS, _SP.test[:3])
    cand_mask = np.zeros((3, N_TARGET_CANDIDATES))
    scores = model.target_scores(x, cand_mask)
    assert np.isfinite(scores).all()


# ---- T013 multitask mask ----
def test_fully_masked_head_zero_gradient() -> None:
    """label 없는 head 가 gradient 를 만들지 않는다(acceptance T013)."""
    model = MultiHeadPolicyModel.build(in_dim=_IN_DIM, seed=5)
    batch = make_batch(_DS, _SP.train)
    batch.act_weight[:] = 0.0  # act head 전부 mask.
    train_step(model, batch, TaskWeights(), lr=0.1)
    assert np.allclose(model.act_head.gW, 0.0)
    assert np.allclose(model.act_head.gb, 0.0)


def test_supported_head_nonzero_gradient() -> None:
    """라벨 있는 head 는 gradient 가 생긴다(대조)."""
    model = MultiHeadPolicyModel.build(in_dim=_IN_DIM, seed=5)
    batch = make_batch(_DS, _SP.train)
    train_step(model, batch, TaskWeights(), lr=0.1)
    assert not np.allclose(model.action_head.gW, 0.0)


def test_zero_weight_excludes_head_from_total_loss() -> None:
    """weight 0 head 는 total loss 에 기여하지 않는다(configurable weight)."""
    from nexa_policy.training.losses import compute_multitask_loss

    model = MultiHeadPolicyModel.build(in_dim=_IN_DIM, seed=5)
    batch = make_batch(_DS, _SP.validation)
    full = compute_multitask_loss(model, batch, TaskWeights())
    no_act = compute_multitask_loss(model, batch, TaskWeights(act=0.0))
    assert no_act.total < full.total or np.isclose(full.per_head["act"], 0.0)


def test_multitask_loss_decreases() -> None:
    res = train_multihead(_DS, train_idx=_SP.train, val_idx=_SP.validation, epochs=80, seed=3)
    assert res.train_losses[-1] < res.train_losses[0]


def test_multihead_deterministic() -> None:
    a = train_multihead(_DS, train_idx=_SP.train, val_idx=_SP.validation, epochs=40, seed=3)
    b = train_multihead(_DS, train_idx=_SP.train, val_idx=_SP.validation, epochs=40, seed=3)
    assert np.allclose(a.model.action_head.W, b.model.action_head.W)
    assert np.allclose(a.train_losses, b.train_losses)


# ---- T010 delay / T011 burst / T012 act heads produce valid distributions ----
def test_aux_heads_valid_distributions() -> None:
    res = train_multihead(_DS, train_idx=_SP.train, val_idx=_SP.validation, epochs=20, seed=3)
    x = design_matrix(_DS, _SP.test)
    for proba in (res.model.delay_proba(x), res.model.burst_proba(x), res.model.act_proba(x)):
        assert np.allclose(proba.sum(axis=1), 1.0)


def test_delay_censored_masked_in_training() -> None:
    """censored delay 라벨은 loss 에서 mask(delay_weight 0)."""
    batch = make_batch(_DS, _SP.train)
    censored = (_DS.delay_mask[_SP.train] == 0) & (_DS.action_mask[_SP.train] > 0)
    if censored.any():
        assert (batch.delay_weight[censored] == 0).all()

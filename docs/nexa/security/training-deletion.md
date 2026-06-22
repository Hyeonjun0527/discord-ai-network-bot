# 학습 artifact 삭제 tombstone (training deletion)

- 작업: NEXA-P17-T011 (`human_gate: true`, security/ml) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md)
- 근거: [deletion-propagation.md](../../../specs/product-v2/nexa/deletion-propagation.md),
  [data-lineage.md](../../../specs/product-v2/nexa/data-lineage.md),
  [threat-model.md](./threat-model.md)(잔여 위험: 개별 샘플 제거 불가)
- 구현: [`deletion.py`](../../../ml/social-policy/src/nexa_policy/data/deletion.py)

## 목적

삭제 대상 source(이벤트/사용자/길드)가 **포함된 dataset/model 을 식별**하고, 재학습 또는 폐기
상태를 추적 가능한 tombstone 으로 관리한다. 삭제 전파([deletion-propagation.md](../../../specs/product-v2/nexa/deletion-propagation.md))의
"dataset/model 단계"를 ML 쪽에서 닫는다.

## 핵심 한계 (acceptance)

> **학습된 모델 가중치에서 개별 샘플을 사후 제거할 수 없다.**

신경망/트리 모델의 파라미터에는 학습 데이터가 분산 인코딩되므로, 한 사용자의 행을 제거해도
이미 학습된 모델에서 그 영향을 외과적으로 제거할 수 없다. 따라서 삭제 권리는 **모델 수준에서는
재학습(retrain) 또는 폐기(retire)** 로만 충족된다. 이 한계와 재학습 기준을 명시하는 것이
acceptance 다.

## tombstone 모델

`DeletionTombstone` 은 삭제 요청 1건이 학습 artifact 에 남긴 불변 증적이다.

- `deletion_request_id`: 삭제 요청 식별자(central 삭제 orchestration 의 추적 id 와 연결).
- `deleted_source_ids`: 삭제 대상 가명 source id 집합(원문·snowflake 아님).
- `affected_dataset_ids`: 그 source 를 포함하는 dataset id 집합.
- `affected_model_ids`: 그 dataset 으로 학습된 model id 집합.
- `status`: `PENDING_RETRAIN` → `RETRAINED` / `RETIRED`.
- `requested_at_ms`, `resolved_at_ms`: 추적 시각.

원문은 담지 않는다 — **삭제 증적은 비가역 식별자 + 시각만**([deletion-propagation.md](../../../specs/product-v2/nexa/deletion-propagation.md) 불변식 2).

## 재학습 기준 (retrain criteria)

dataset 에서 삭제 대상 행을 제외해 재학습하면 tombstone 을 `RETRAINED` 로 종결한다. 재학습 전까지
영향받은 model 은 다음 기준 중 하나면 **즉시 폐기(`RETIRED`)** 해야 한다:

1. 삭제가 **동의 철회** 또는 **법적 삭제 요청**에서 비롯됨(즉시 제거 의무).
2. 영향받은 dataset 의 삭제 행 비율이 임계치(`retrain_threshold`, 기본 1%) 이상 — 재학습까지의
   노출 창이 길어지면 폐기를 우선한다.

그 외(예: 단일 메시지 삭제)는 다음 정기 재학습 사이클까지 `PENDING_RETRAIN` 으로 둘 수 있다.

## acceptance 충족

- **모델에서 개별 샘플을 제거할 수 없다는 한계가 명확하다**: 위 "핵심 한계" 절 + `deletion.py`
  docstring 에 명시. `DeletionResolution.can_resolve_in_model_weights` 는 항상 `False` 를 돌려준다.
- **재학습 기준이 명확하다**: 위 "재학습 기준" 절 + `resolve_tombstone(...)` 이 트리거 종류·삭제 비율로
  `RETRAINED`/`RETIRED`/`PENDING_RETRAIN` 을 결정한다(테스트로 증명).

## 불변식

1. tombstone 은 불변(immutable) — 상태 전이는 새 tombstone 인스턴스로 표현한다.
2. tombstone 에 원문·snowflake 를 담지 않는다(가명 id·시각만).
3. 동의 철회/법적 삭제 트리거는 재학습 전 model 폐기를 강제한다.

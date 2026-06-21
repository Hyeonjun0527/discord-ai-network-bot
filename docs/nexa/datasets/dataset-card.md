# Dataset Card (NEXA-P10-T024)

- 작업: NEXA-P10-T024 (`kind: documentation`, `human_gate: true`) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md),
  [ADR 0010 ainetwork·socialmemory 경계](../../adr/0010-ainetwork-socialmemory-boundary.md)
- 자동 생성기(SSOT): [`ml/social-policy/src/nexa_policy/reporting/dataset_card.py`](../../../ml/social-policy/src/nexa_policy/reporting/dataset_card.py)
- 입력 manifest: [`ml/social-policy/src/nexa_policy/data/manifest.py`](../../../ml/social-policy/src/nexa_policy/data/manifest.py)

## 자동 생성 — 수동 수치 드리프트 금지 (acceptance)

Dataset Card 의 **수치(row 수·길드 수·기간·class 분포·exclusions·hash)는 manifest 에서 자동 삽입**된다.
사람이 수치를 손으로 적지 않는다. builder CLI([build_dataset.py](../../../ml/social-policy/src/nexa_policy/cli/build_dataset.py))가
빌드 출력 디렉터리에 `dataset-card.md` 를 manifest 와 함께 생성한다:

```
python -m nexa_policy.cli.build_dataset --config <config.json>
# → <output_dir>/manifest.json + <output_dir>/dataset-card.md
```

`render_dataset_card(manifest)` 가 manifest 의 실제 수치를 Markdown 에 채우므로, manifest 가 바뀌면
카드 수치도 같이 바뀐다(드리프트 0). 이 문서는 카드의 **정적 정책 섹션의 설명**이며, 수치 자리는 생성기가 채운다.

## 포함 섹션

자동 생성되는 카드는 다음 섹션을 담는다(정적 텍스트는 생성기 상수가 SSOT):

| 섹션 | 출처 |
| --- | --- |
| 목적 (Purpose) | participation 정책(SPEAK/REACT/WAIT/IGNORE·target·timing·social act) 지도학습 |
| 동의 (Consent) | opt-in·관찰가능 행만, `consent_snapshot_id` 봉인 (P10-T002) |
| 구성 (Composition) | manifest: dataset_id·schema·source_watermark·row/guild 수·기간·class 분포 |
| 편향·제외 (Bias / Exclusions) | manifest.exclusions + 길드 split 과대표집 주의 |
| 제한 (Limitations) | 원문/식별자 미포함·약지도 라벨 비-gold·UNKNOWN 마스킹 |
| 금지 추론 (Forbidden Inference) | 내면/정체성/민감 속성 추론 금지 ([observable-state-policy](../social-state/observable-state-policy.md)) |
| 삭제 (Deletion) | 삭제 요청 시 재빌드 제외 + dataset_id 재계산 (P10-T016) |
| 재현 (Reproducibility) | content_hash + 같은 입력→같은 dataset_id |
| 라이선스 (License) | 내부 전용(공개 불가), 운영 데이터 재배포 금지 |

## 한계 명시 (acceptance 보강)

카드는 다음 한계를 **명시**한다(축소·은폐 금지):

- 원문·첨부 URL·실제 식별자는 데이터셋에 없다(가명·신호만, [redaction](../../../ml/social-policy/src/nexa_policy/data/privacy.py)).
- 약지도 social act 라벨은 gold 가 아니다(confidence·model_version 동반).
- 관찰 불가 구간은 UNKNOWN 으로 마스킹되며 강제 라벨링하지 않는다.
- 길드 단위 split 으로 소수 활성 길드가 과대표집될 수 있다.

## 출처·라이선스

합성 fixture([tests/fixtures](../../../ml/social-policy/tests/fixtures/README.md))로 카드 생성을 증명하며,
운영 데이터는 export 보안 경계를 통과한 승인 projection 만 입력이 된다. 데이터셋은 내부 전용이며 공개 불가다.

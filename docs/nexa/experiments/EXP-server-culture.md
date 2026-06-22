# EXP — 서버 문화 embedding (NEXA-P19-T004)

- 작업: NEXA-P19-T004 (`kind: experiment`, `human_gate: true`, `risk: high`) · 선행: NEXA-P19-T003
- 코드: [`server_context.py`](../../../ml/social-policy/src/nexa_policy/models/server_context.py)
- 테스트: [`test_server_context.py`](../../../ml/social-policy/tests/test_server_context.py)
- 상위: [cohort-design](../longitudinal/cohort-design.md), [generalization](../../../ml/social-policy/src/nexa_policy/eval/generalization.py)

## 범위·금지

- **운영 데이터 미접근**: 합성 fixture·결정론만. 실제 길드 식별·LIVE 적응 없음.
- **guild ID memorization 금지**: 인코더 입력은 관찰 통계(tempo·burst·reaction)뿐이다. 식별자는 입력이
  아니다(`GUILD_ID_IS_NOT_AN_INPUT` 구조 가드, `ServerCultureStats` 에 식별자 필드 없음).
- **torch 미사용**: numpy 선형 오토인코더(bottleneck=culture dim), 결정론 GD.

## 방법

1. tempo·burst·reaction 등 길드 관찰 통계 5종을 정규화한다(`STAT_FIELDS`).
2. train 길드 통계로 선형 오토인코더를 적합한다(`fit_culture_encoder`, bottleneck=culture_dim).
3. encode→decode 재구성 오차를 train 길드와 **unseen 길드**(train 에 없던)에서 비교한다
   (`evaluate_unseen_generalization`).

## acceptance — 원본 guild ID memorization 없이 unseen guild 적응을 평가한다

- 구조적으로 guild id 를 받지 못하므로 특정 길드를 외울 수 없다(입력 = 통계 벡터).
- unseen 길드의 재구성 오차가 train 대비 `max_gap` 이내면 일반화로 본다(`UnseenGeneralizationReport.generalizes`).
  비슷한 문화의 unseen 길드는 train 길드 근처로 임베딩돼야 한다 — memorization 이면 unseen 에서 무너진다.
- 산출 culture embedding 은 P19-T005~T007(talkativeness·delay·action mix 적응)의 server-conditioned 입력이 된다.

## 결과 해석

- generalization_gap 이 작을수록 문화 표현이 일반화된 것이다. 큰 gap = 과적합/memorization 의심 → RL/적응
  입력으로 쓰지 않는다.
- 이 실험은 **추정**이다(합성). 실 길드 적용은 인간 승인 게이트(P19-T024) 이후다.

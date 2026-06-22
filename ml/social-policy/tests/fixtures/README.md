# 공개 불가 fixture dataset (NEXA-P10-T019)

> **공개 불가 (NOT FOR DISTRIBUTION)** — 단, 이 표시는 취급 등급일 뿐 **실제 PII 는 포함하지 않는다**.

이 디렉터리의 데이터는 **전부 합성(synthetic)** 이다.

- 가상 사용자 가명(`actor-*`)·가상 길드 가명(`guild-*`)만 사용한다. 실제 Discord user id/snowflake·실명·원문·첨부 URL 없음.
- `event_kind`·길이 버킷·질문 여부 같은 **신호만** 담는다(원문 텍스트 없음, P10-T015 redaction 일관).
- 모든 action(IGNORE/WAIT/REACT/SPEAK)·target(message/member/thread/none)·time(즉시/지연/세션경계/holdout) 케이스를 포함하도록 설계했다.
- 실제 사용자 원문이 저장소에 커밋되지 않음을 단위 테스트(`test_fixture_no_pii`)가 강제한다.

`synthetic_dataset.json` 은 builder CLI(`nexa_policy.cli.build_dataset`)의 입력 fixture 이며,
`build_config.json` 으로 결정론 재현(같은 입력→같은 dataset_id)을 증명한다.

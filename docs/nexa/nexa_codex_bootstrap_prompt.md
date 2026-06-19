# Codex 첫 실행용 프롬프트 — 기존 discord-assistant 저장소

아래 프롬프트를 Codex에 그대로 전달하되, 먼저 다음 세 파일을 저장소의 임시 검토 위치에 넣는다.

- `nexa_500_step_master_plan.md`
- `nexa_500_task_graph.yaml`
- `validate_nexa_500_task_graph.py`

```text
현재 저장소는 이미 운영 중인 `discord-assistant` 모노레포다.
기존 central-server의 헥사고날 경계, ArchUnit 규칙, Flyway 이력,
protocol/i18n/UI SSOT, provider-agent 경계를 보존해야 한다.

첨부된 다음 문서는 구현 명령이 아니라 검토가 필요한 계획 초안이다.

- nexa_500_step_master_plan.md
- nexa_500_task_graph.yaml
- validate_nexa_500_task_graph.py

이번 실행에서는 NEXA 기능을 구현하지 마라.
`NEXA-P00-T001` 하나만 수행하라.
다음 작업을 자동으로 시작하지 마라.

작업 순서:

1. 저장소 루트부터 실제 파일 구조를 읽어 사용자 제공 인벤토리와 비교한다.
2. 기존 AGENTS.md, README, Makefile, Gradle/Python 설정, CI, ai-context를 읽는다.
3. task graph validator를 실행해 계획 파일 자체가 500개 DAG인지 확인한다.
4. `NEXA-P00-T001`의 선행조건·권장 경로·산출물·완료 조건을 읽는다.
5. 현재 branch, commit, working tree, submodule, generated artifact 상태를
   `docs/nexa/baseline/repository-state.md`에 기록한다.
6. 계획에 적힌 경로가 실제 저장소와 다르면 아직 전체 task graph를 수정하지 말고,
   차이 목록과 수정 제안을 별도 섹션에 기록한다.
7. 사용자 작업물을 삭제·정리·reset하지 마라.
8. build나 test를 아직 전부 실행하지 마라. 그것은 P00-T002 이후 범위다.
9. 비밀·토큰·원문 사용자 데이터를 문서에 복사하지 마라.
10. 변경 diff를 검토하고 문서 링크만 검증한다.

완료 보고 형식:

- 확인한 실제 저장소 상태
- 생성·수정 파일
- 사용자 인벤토리와 다른 점
- 검증 명령과 결과
- P00-T001 완료 조건 충족 여부
- BLOCKER
- 다음 READY 작업 ID는 표시만 하고 실행하지 않음

이 작업은 REVIEW 상태까지만 올려라.
사람의 확인 없이 VERIFIED 처리하지 마라.
```

## 이후 작업용 짧은 프롬프트

```text
`nexa_500_task_graph.yaml`의 `<TASK_ID>` 하나만 수행하라.

- 적용되는 모든 AGENTS.md, PLANS.md, 관련 ADR, task node, 현재 코드를 먼저 읽어라.
- 선행 작업이 VERIFIED가 아니면 구현하지 말고 BLOCKED로 보고하라.
- baseline 검증을 먼저 실행해 ExecPlan에 기록하라.
- recommended_paths 밖 수정이 필요하면 이유와 영향을 먼저 기록하라.
- deliverable과 acceptance를 테스트로 입증하라.
- 테스트 삭제, assertion 약화, 실패 숨기기를 금지한다.
- 구현 뒤 architecture, concurrency, test quality, privacy 관점의 읽기 전용 subagent 리뷰를 수행하라.
- verification 명령을 모두 재실행하라.
- human_gate=true인 작업은 REVIEW까지만 올려라.
- 다음 작업을 자동으로 시작하지 마라.
```

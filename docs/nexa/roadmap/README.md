# NEXA task graph schema

이 디렉터리는 [NEXA 500 task graph](../nexa_500_task_graph.yaml)의 구조 계약을 보관한다.
현재 스키마 파일은 [task-schema.json](task-schema.json)이고, 상태 전이 규칙은 [task-lifecycle.md](task-lifecycle.md)이다.

## 스키마가 정의하는 것

`task-schema.json`은 다음 필드를 필수 계약으로 둔다.

- 작업 ID: `NEXA-P00-T001` 형식
- 의존성: `depends_on` 배열, 같은 작업 ID 형식
- 상태: `DRAFT`, `READY`, `IN_PROGRESS`, `REVIEW`, `VERIFIED`, `BLOCKED`, `ABANDONED`
- 권장 경로: `recommended_paths`
- 산출물: `deliverable`
- 완료 기준: `acceptance`
- 검증 명령: `verification`
- 검증 증거: `verification_evidence` (`VERIFIED` 상태에서 필수)
- 인간 게이트: `human_gate`
- 실행 규칙: `execution_rule`

표준 JSON Schema만으로는 배열 안 객체의 특정 속성(`id`)만 기준으로 한 유일성 검사를 표현할 수 없다.
그래서 `tasks.uniqueItems`는 완전 동일 객체 중복만 막고, **중복 task ID**와 의존 DAG 검사는
[../validate_nexa_500_task_graph.py](../validate_nexa_500_task_graph.py)가 추가로 강제한다.

## 검증 방법

루트에서 아래를 실행한다.

```bash
./scripts/nexa-verify.sh docs
```

이 wrapper는 task graph 검증, 문서 링크 검사, `git diff --check`를 순서대로 실행한다.
개별 validator만 확인할 때는 아래 명령을 쓴다.

```bash
python3 scripts/validate-nexa-task-graph.py
```

## 실패해야 하는 대표 사례

- 허용되지 않은 `status` 값
- 필수 필드 누락
- 중복 task ID
- 존재하지 않는 dependency
- dependency cycle
- 프로그램별 25개 작업/인간 review gate 누락

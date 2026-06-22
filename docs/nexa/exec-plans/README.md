# NEXA ExecPlans

이 디렉터리는 NEXA 장기 작업의 실행 계획을 보관한다. 루트 `../../PLANS.md` 는 템플릿이고,
여기에는 실제 작업별 ExecPlan을 둔다.

## 언제 작성하나

다음 조건 중 하나라도 맞으면 ExecPlan을 만든다.

- 한 세션 안에 끝나기 어려운 작업이다.
- 여러 모듈, 생성물, 배포/운영 절차를 함께 바꾼다.
- task graph 여러 항목을 묶어 진행한다.
- 실패 원인, 설계 결정, 롤백 방법을 다음 세션에 정확히 넘겨야 한다.

짧고 독립적인 한 단계 작업은 task graph 상태와 검증 로그만으로 충분하면 ExecPlan을 만들지 않는다.

## 필수 내용

각 ExecPlan은 self-contained여야 한다. 최소한 아래 내용을 포함한다.

- 목표와 비목표
- 현재 기준선과 관련 task graph ID
- 설계 결정과 근거
- 실행 단계, 상태, 산출물, 검증 명령
- 실제 검증 증거
- 롤백 계획
- 발견사항과 재개 지침

## 운영 규칙

- 계획 파일 하나가 하나의 장기 작업을 대표한다.
- 진행 중에는 같은 파일에 진행 로그를 누적한다.
- 완료 후에도 검증 증거와 롤백 정보를 보존한다.
- 비밀값, 토큰, 로컬 개인 경로의 민감 정보는 쓰지 않는다.
- 루트 `../../docs/nexa/nexa_500_task_graph.yaml` 의 상태를 바꿀 때는 해당 ExecPlan 진행 로그에도 근거를 남긴다.

## 검증

문서만 추가·수정한 경우 루트에서 아래를 실행한다.

```bash
python3 docs/nexa/validate_nexa_500_task_graph.py docs/nexa/nexa_500_task_graph.yaml
python3 scripts/check_links.py
git diff --check
```

`./scripts/nexa-verify.sh docs` 가 추가된 뒤에는 해당 wrapper를 우선 실행한다.

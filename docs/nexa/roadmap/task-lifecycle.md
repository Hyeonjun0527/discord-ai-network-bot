# NEXA task lifecycle

이 문서는 `docs/nexa/nexa_500_task_graph.yaml` 의 `status` 전이 규칙을 정의한다. 스키마는
[task-schema.json](task-schema.json), 실행 검증기는 [../../../scripts/validate-nexa-task-graph.py](../../../scripts/validate-nexa-task-graph.py)다.

## 상태 정의

| 상태 | 의미 | 다음 상태 |
| --- | --- | --- |
| `DRAFT` | 작업이 존재하지만 아직 실행 준비가 끝나지 않았다. | `READY`, `ABANDONED` |
| `READY` | 의존 작업이 충족됐고 목표·경로·검증 명령이 실행 가능한 상태다. | `IN_PROGRESS`, `BLOCKED`, `ABANDONED` |
| `IN_PROGRESS` | 현재 세션에서 실제로 파일을 읽고 변경하거나 검증 중이다. | `REVIEW`, `BLOCKED`, `ABANDONED` |
| `REVIEW` | 산출물이 만들어졌고 검증·리뷰·인간 게이트 확인이 남았다. | `VERIFIED`, `IN_PROGRESS`, `BLOCKED`, `ABANDONED` |
| `VERIFIED` | 산출물과 완료 기준이 현재 worktree 증거로 확인됐다. | 없음. 수정이 필요하면 새 작업이나 task-graph revision을 만든다. |
| `BLOCKED` | 같은 작업을 계속할 수 없는 외부 입력·권한·환경 문제가 있다. | `READY`, `IN_PROGRESS`, `ABANDONED` |
| `ABANDONED` | 작업이 폐기됐거나 다른 작업으로 대체됐다. | 없음. 재개하려면 새 작업을 만든다. |

정상 흐름은 `DRAFT → READY → IN_PROGRESS → REVIEW → VERIFIED`다. 아주 작은 작업은 한 세션 안에서
여러 상태를 압축해 진행할 수 있지만, 최종적으로 `VERIFIED`를 기록하려면 아래 증거 규칙을 반드시 만족해야 한다.

## VERIFIED 증거 규칙

`status: VERIFIED` 인 작업은 반드시 `verification_evidence` 필드를 가진다.

```yaml
verification_evidence:
- "./scripts/nexa-verify.sh docs → VALID, 링크 OK, diff-check OK"
```

증거 항목은 다음을 포함해야 한다.

- 실행한 명령 또는 확인한 산출물
- 성공/실패 여부를 판단할 수 있는 핵심 출력
- fixture를 사용했다면 어떤 실패 조건을 검증했는지

금지:

- 실제로 실행하지 않은 명령을 통과한 것처럼 적기
- 넓은 목표를 좁은 명령 하나로 검증했다고 주장하기
- 토큰, 로컬 비밀값, 개인 계정 정보를 증거에 적기

검증 명령 목록인 `verification`은 “무엇을 실행해야 하는가”이고, `verification_evidence`는 “이번에 실제로 무엇이 통과했는가”다. 두 필드는 서로 대체할 수 없다.

## BLOCKED 조건

`BLOCKED`는 작업자가 의미 있는 진전을 더 만들 수 없을 때만 쓴다. 단순히 어렵거나 오래 걸리는 일은
`BLOCKED`가 아니다. 예시는 다음과 같다.

- 사용자 승인 없이는 진행할 수 없는 파괴적 변경
- 필요한 외부 권한, 네트워크, runner, secret, 장비가 반복적으로 불가능한 경우
- 인간 게이트가 명시돼 있고 승인이 아직 없는 경우

차단이 풀리면 바로 `READY` 또는 `IN_PROGRESS`로 되돌리고, 무엇이 풀렸는지 진행 로그나 증거에 남긴다.

## ABANDONED 조건

`ABANDONED`는 기존 작업이 더 이상 실행 대상이 아닐 때만 쓴다.

- 상위 gate나 ADR로 요구사항이 바뀌어 작업이 무효화됐다.
- 더 구체적인 새 작업이 기존 작업을 대체한다.
- 저장소 구조가 바뀌어 recommended path가 의미를 잃었다.

폐기할 때는 새 작업 ID, ADR, 또는 task graph revision 근거를 남긴다.

## 전이 체크리스트

`VERIFIED`로 바꾸기 전 확인한다.

1. `depends_on` 작업이 모두 `VERIFIED`이거나, task graph revision으로 의존성이 갱신됐다.
2. 산출물이 `recommended_paths` 안에 있거나 변경 이유가 문서화됐다.
3. `acceptance` 문장을 현재 파일/명령 출력으로 증명했다.
4. `verification`에 적힌 명령을 실행했거나, 아직 없는 wrapper는 동등한 실제 명령으로 대체하고 이유를 남겼다.
5. `verification_evidence`에 실제 증거를 추가했다.
6. `./scripts/nexa-verify.sh docs`가 통과한다.

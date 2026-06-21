# ADR 0013: 정책 서빙 경계 — JVM in-process ONNX 유지, gRPC serving 은 옵션

- 상태(Status): 제안됨 (Proposed) — **인간 승인 게이트 대기** (NEXA-P12-T018, `human_gate: true`, `risk: high`)
- 날짜(Date): 2026-06-22
- 결정자(Deciders): Hyeonjun0527
- 관련: [ADR 0004](./0004-kotlin-spring-central-server.md), [ADR 0008](./0008-spring-modulith-evaluation.md)
- 근거(성능): [EXP-policy-latency](../nexa/experiments/EXP-policy-latency.md),
  추론 latency benchmark 코드
  [`inference_latency.py`](../../ml/social-policy/src/nexa_policy/benchmarks/inference_latency.py)
- 계약: [policy-decision-request.schema.json](../../contracts/policy/policy-decision-request.schema.json),
  [policy-decision-response.schema.json](../../contracts/policy/policy-decision-response.schema.json),
  proto(옵션 경로) [social_policy.proto](../../contracts/protobuf/social_policy.proto)

## 맥락 (Context)

P11-T018 에서 participation 의 사회 정책 추론은 **JVM in-process ONNX Runtime**
([SocialPolicyPort] 의 ONNX 어댑터, `participation/adapter/outbound/policy/onnx`)으로 구현됐다. P12 에서
타이밍 모델(생존분석·hazard·burst)이 추가되며 모델 연산량이 커졌고, "정책 서버를 Python gRPC 로 분리할
것인가" 라는 운영 경계 질문이 생겼다. 이 ADR 은 **현재 모델의 연산·배포·관측 요구**에 근거해 그 경계를 정한다.

### 검토한 대안

| 축 | A. JVM in-process ONNX (현행) | B. Python gRPC serving (모델 서버 분리) |
| --- | --- | --- |
| **추론 latency** | 네트워크 hop 없음 — 호출 = 함수 호출 + ONNX forward. EXP-policy-latency 기준 정책 p95 목표 80ms(=GLM p95 예산 800ms × 0.1) 안에서 충분히 끝남 | hop + 직렬화 추가(수 ms~수십 ms). 같은 forward 에 네트워크·proto 직렬화 비용이 더해짐 |
| **배포 복잡도** | central 단일 배포물(JAR). 모델 = ONNX 파일 자산 | central + 별도 정책 서버(2개 배포·헬스체크·버전 동기·스케일) |
| **rollback** | 모델 자산 교체 또는 코드 롤백 1곳. shadow registry(V62)로 모델 버전 전환 | central·정책 서버 **두 배포물의 버전 정합**을 함께 롤백해야 함(부분 롤백 시 schema drift 위험) |
| **관측(observability)** | 같은 프로세스 — 결정 trace 가 requestlog/decision log 와 같은 컨텍스트 | 분산 trace 필요(서버 경계 넘는 deadline·error 전파). 더 정직한 격리지만 인프라 더 듦 |
| **장애 격리** | 추론 폭주가 central 프로세스 자원과 경쟁(CPU/메모리) | 정책 서버 장애가 central 과 분리 — central 은 fallback 으로 살아남기 쉬움 |
| **언어/생태계** | ONNX 로 학습(Python)→서빙(JVM) 분리, runtime 의존만. torch 미사용 유지 | Python 모델 코드를 그대로 서빙(연속시간/MTPP 등 복잡 모델에 유리) |
| **현재 연산 요구** | 경량 numpy/ONNX forward — in-process 로 충분(benchmark p95 ≪ 목표) | 현재 모델엔 과한 인프라(YAGNI) |

## 결정 (Decision)

**현행 JVM in-process ONNX 서빙을 유지한다(방안 A). Python gRPC serving 은 채택하지 않고 — 미래 옵션으로만
계약을 준비한다.**

근거:

1. **성능**: 현재 정책 모델은 경량(numpy/ONNX forward)이라 in-process latency 가 정책 p95 목표(80ms,
   EXP-policy-latency) 안에서 끝난다. gRPC 의 hop·직렬화 비용은 **순이득 없이** latency·복잡도만 더한다.
2. **운영 복잡도·rollback**: in-process 는 단일 배포물·단일 롤백 지점이다. gRPC 분리는 두 배포물의 버전
   정합·부분 롤백 위험·추가 헬스체크/스케일을 들이는데, 현재 연산 요구가 그 비용을 정당화하지 않는다(YAGNI).
3. **fail-safe 일관**: 어느 서빙이든 엔진 부재·오류 시 **fallback 체인**(learned→safe baseline→always silent,
   T021)으로 안전 하강한다. 이 안전망이 서빙 방식과 **독립**이므로, 서빙을 단순하게 둘 수 있다.

### gRPC 를 옵션으로 남기는 이유와 범위

연속시간/MTPP 같은 무거운 모델(T023 연구)이 production 으로 승격되면 Python 서빙이 유리해질 수 있다. 그
전환 비용을 낮추려고 **계약과 어댑터 골격만** 미리 둔다 — 단, **실제 gRPC 서버는 기동하지 않는다**:

- **T019**: `contracts/protobuf/social_policy.proto` — JSON 정책 계약과 **의미가 일치**하고 raw Discord
  content 를 요구하지 않는 request/response/version/deadline/trace/error 정의(옵션 경로의 SSOT).
- **T020**: Kotlin gRPC 정책 어댑터 **골격** — deadline·circuit breaker·schema validation 을 가지되, 서비스
  부재 시 configured fallback 으로 동작해 live action 을 멈출 수 있다(서버 미기동에서도 안전).

즉 결정은 "**현행 ONNX 유지 + gRPC 는 옵션**"이며, T019/T020 은 그 결정에 맞춰 **최소 구현**(계약+골격)이다.

### 비-목표

- gRPC 정책 서버의 실제 기동·배포·운영 — 하지 않는다(미래 결정).
- central 기존 ONNX 경로 변경 — 하지 않는다(이 ADR 은 경계 결정·신규 옵션 골격만, 기존 무변경).

## 결과 (Consequences)

**장점**: 단순 배포·낮은 latency·단일 롤백·관측 컨텍스트 공유. 무거운 모델 등장 시 전환할 계약·골격 준비됨.
**단점**: 추론이 central 자원과 경쟁(경량이라 현재 무해). Python 전용 복잡 모델은 ONNX export 가능 범위로 제약
(연속시간 모델이 ONNX 로 어려우면 그때 gRPC 옵션을 활성화·재평가).

## 인간 승인 상태 (Approval)

- `NEXA-P12-T018`, `human_gate: true`, `risk` 높음.
- acceptance("운영 복잡도와 rollback 을 포함하고 성능 수치에 근거한다") 충족 — 대안 표에서 배포·rollback·관측·
  장애격리를 비교하고, EXP-policy-latency 의 정책 p95 목표(80ms) 수치에 근거해 in-process 유지를 결정.

## 미해결 질문

- 연속시간/MTPP 모델의 ONNX export 가능성 — 불가하면 gRPC 옵션 활성화 트리거(T023 연구로 추적).
- gRPC 옵션 활성화 시의 분산 trace·deadline 예산 운영 기준(현재는 골격의 deadline 필드만 정의).

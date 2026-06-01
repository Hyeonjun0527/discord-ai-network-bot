# AI Network 도메인 설계 비관적 감사

> 상태: Draft  
> 작성일: 2026-06-01  
> 감사 대상: [AI Network 도메인 모델 설계 초안](./00_DOMAIN_MODEL_DESIGN.md)  
> 관점: “잘 될 이유”가 아니라 **장기적으로 망가질 이유**를 먼저 찾는다.

## 0. 총평

현재 설계 방향은 옳다. 특히 `ChannelAi` 를 중심 Aggregate 로 잡고, `AiBehaviorVersion`, `AiChangeProposal`, `KnowledgeSpace`, `Dashboard Projection` 을 분리한 것은 장기 확장에 유리하다.

하지만 그대로 구현하면 위험한 지점도 많다.

가장 큰 위험은 다음 7개다.

1. **ChannelAi 가 God Aggregate 로 비대해질 위험**
2. **RAG/지식 업로드가 보안·개인정보·비용 폭탄이 될 위험**
3. **프롬프트/헌법/프리셋 버전 관리가 복잡해져 운영자가 이해 못 할 위험**
4. **대시보드 projection 이 stale 하거나 원본과 어긋나 신뢰를 잃을 위험**
5. **품질 점수와 모델 선택이 Provider 과부하를 유도할 위험**
6. **권한/승인 경계가 허술하면 서버 관리자 실수로 큰 사고가 날 위험**
7. **기능이 너무 많아져 MVP 가 영원히 출시되지 못할 위험**

따라서 구현 순서는 “많이 만들기”가 아니라 **먼저 실패를 막는 뼈대**부터 가야 한다.

## 1. 위험 요약표

| ID | 영역 | 위험 | 심각도 | 가능성 | 결론 |
| --- | --- | --- | --- | --- | --- |
| R-01 | 도메인 | ChannelAi 가 모든 것을 품는 God Aggregate 가 됨 | 높음 | 높음 | 분리 기준을 더 엄격히 둬야 함 |
| R-02 | DB | version/proposal/preset 이 중복 snapshot 지옥이 됨 | 높음 | 중간 | snapshot 범위와 참조 범위 결정 필요 |
| R-03 | 보안 | RAG 업로드가 민감정보 저장소가 됨 | 매우 높음 | 높음 | RAG 는 별도 보안 게이트 전까지 후순위 |
| R-04 | 보안 | system prompt 평문 저장/노출 사고 | 높음 | 중간 | 암호화/마스킹/권한 필요 |
| R-05 | 품질 | AI 헌법/지식/프롬프트가 충돌해 답변 품질 저하 | 높음 | 높음 | 우선순위와 prompt composer 필요 |
| R-06 | 성능 | 대시보드가 원본 테이블을 조인하며 느려짐 | 중간 | 높음 | projection 필수 |
| R-07 | 성능 | RAG 검색 + 긴 프롬프트가 Provider 과부하 유발 | 높음 | 높음 | token budget 과 retrieval cap 필수 |
| R-08 | 운영 | projection stale 로 상태판을 못 믿게 됨 | 중간 | 중간 | freshness 표시와 재생성 필요 |
| R-09 | 권한 | 관리자 누구나 AI 설정 변경 가능 | 높음 | 높음 | AI 관리자 역할/승인 기본 설계 필요 |
| R-10 | Provider 보호 | 품질 점수 높은 Provider 에 요청 몰림 | 매우 높음 | 높음 | fairness/cooldown 을 score 보다 우선 |
| R-11 | 제품 | 설정이 너무 어려워 아무도 못 씀 | 중간 | 높음 | 마법사/프리셋 중심 UX 필요 |
| R-12 | 출시 | 15개 기능이 얽혀 첫 출시가 지연됨 | 높음 | 높음 | foundation MVP 를 작게 잘라야 함 |

## 2. 도메인 모델 감사

### 2.1 ChannelAi 중심 설계는 맞지만 위험하다

`ChannelAi` 를 중심에 두는 것은 맞다. 하지만 다음 필드가 계속 붙으면 God Aggregate 가 된다.

- 이름/아이콘
- 말투/헌법/system prompt
- 모델 정책
- 응답 모드
- 지식 공간
- 온보딩
- 승인 상태
- 버전
- 프리셋
- 피드백
- 대시보드 표시 상태

이걸 한 엔티티에서 다 관리하면 장기적으로 다음 문제가 생긴다.

- 변경 하나마다 트랜잭션 충돌 증가
- 테스트가 거대해짐
- migration 이 위험해짐
- UI 변경과 라우팅 변경이 같은 모델을 건드림
- 버전/승인/롤백 로직이 꼬임

### 감사 결론

`ChannelAi` 는 **정체성 + 현재 publish pointer** 만 가져야 한다.

권장 책임:

- channel binding
- 표시 이름/아이콘/간단한 설명
- 현재 published behavior version id
- draft pointer 정도
- status

금지 책임:

- 긴 system prompt 직접 보관
- RAG 문서 직접 보관
- feedback 집계 직접 보관
- Provider 점수 직접 보관
- dashboard 통계 직접 보관

## 3. DB/버전 관리 감사

### 3.1 snapshot 과 reference 가 섞일 위험

`AiBehaviorVersion`, `AiPreset`, `AiChangeProposal` 모두 snapshot 을 가질 수 있다. 이 설계는 강력하지만 위험하다.

나쁜 경우:

- `AiBehaviorVersion` 에 system prompt snapshot
- `AiPreset` 에 behavior snapshot
- `AiChangeProposal` 에 payload snapshot
- audit log 에 또 snapshot

그러면 어떤 값이 진짜인지 모르게 된다.

### 감사 결론

snapshot 정책을 명확히 해야 한다.

권장:

- `AiBehaviorVersion`: 실행에 쓰이는 최종 immutable snapshot
- `AiChangeProposal`: 적용 전 검토 payload snapshot. 적용 후에는 applied version id 를 참조
- `AiPreset`: 복사용 template snapshot. 실행에는 직접 쓰지 않음
- `AuditLog`: 변경 전후 요약 + 참조 id. 전체 payload 복사는 최소화

### 3.2 version number race 위험

동시에 두 관리자가 버전을 만들면 `versionNumber` 충돌 가능성이 있다.

필수 제약:

- unique `(channel_ai_id, version_number)`
- publish 시 optimistic locking 또는 transaction lock
- active/published pointer update 원자성

## 4. 보안 감사

### 4.1 RAG 는 가장 위험한 기능이다

RAG 지식 업로드는 기능적으로 매력적이지만, 보안 관점에서는 제일 위험하다.

위험:

- 사용자가 비밀번호/API 키가 든 문서를 업로드
- 사내 문서/개인정보가 Provider PC 로 전달
- 링크 크롤링이 SSRF 로 변질
- 외부 URL 다운로드가 악성 파일 처리 경로가 됨
- 문서가 chunk 로 쪼개진 뒤 삭제/정정이 어려움
- embedding 저장소에 민감정보가 반영됨

### 감사 결론

RAG 는 Channel AI MVP 에 넣으면 안 된다. 설계는 하되 구현은 보안 게이트 이후로 미룬다.

RAG 구현 전 필수:

- 파일 크기 제한
- 허용 MIME type 제한
- 링크 allowlist/denylist
- SSRF 방지
- 민감정보 스캐너
- 지식 삭제 시 chunk/embedding 삭제 보장
- 지식 scope 표시
- 관리자 전용 업로드
- audit log
- retrieval token budget

### 4.2 System prompt 저장 위험

system prompt 는 운영 정책과 보안 우회 힌트를 담을 수 있다.

위험:

- 일반 유저에게 노출
- Discord embed 에 실수로 표시
- audit payload 에 평문 중복 저장
- prompt injection 대응 규칙 노출

권장:

- 관리자만 보기
- 대시보드 projection 에 넣지 않기
- audit log 에 전문 저장하지 않기
- export 시 마스킹 옵션
- DB 암호화는 후순위라도 접근 경계는 먼저 구현

## 5. 품질 감사

### 5.1 커스터마이징이 오히려 답변 품질을 망칠 수 있다

관리자가 자유롭게 헌법/system prompt/RAG/모델 정책을 만지면 다음 문제가 생긴다.

- 채널 AI가 과도하게 장황해짐
- 헌법과 지식이 충돌
- 사용자 모델 선택과 채널 정책 충돌
- 프롬프트가 너무 길어져 로컬 모델 성능 저하
- 작은 모델에 복잡한 지시를 줘서 답변 품질 하락

권장:

- 자유 입력보다 프리셋/마법사 우선
- system prompt 길이 제한
- 헌법 규칙 개수 제한
- 응답 모드별 token budget
- 미리보기/테스트 질문 필수
- 변경 전후 품질 비교를 저장

### 5.2 AI 헌법 우선순위가 필요하다

권장 우선순위:

1. 시스템 안전 정책
2. Provider 보호 정책
3. 서버/Guild 정책
4. 채널 AI 헌법
5. RAG 검색 결과
6. 사용자 질문
7. 사용자 선호

이 우선순위가 없으면 prompt composer 가 임의로 섞다가 품질과 안전이 무너진다.

## 6. 성능 감사

### 6.1 대시보드는 projection 없이는 느려질 가능성이 높다

네트워크 상태, Provider 상태, 모델 지도, 채널 AI 카드, 피드백, 과부하 알림을 매번 실시간 조인하면 느려진다.

위험:

- Provider 수 증가 시 dashboard API 지연
- Discord command timeout
- 운영자가 상태판을 열 때 DB 부하 증가
- request path 와 dashboard query 가 같은 테이블을 때림

권장:

- 1차는 on-demand DTO 여도 되지만, 집계 쿼리 수를 제한
- 2차부터 projection table/cache
- 모든 projection 에 `generatedAt` 표시
- projection 재생성 명령 제공
- dashboard query 는 request routing transaction 과 분리

### 6.2 RAG 와 긴 프롬프트가 Provider 과부하를 만들 수 있다

RAG 는 검색만 문제가 아니다. 검색 결과가 prompt 에 붙어 로컬 모델 입력이 길어진다.

필수 제한:

- topK 제한
- chunk 총 문자 수 제한
- 모델 burden 별 retrieval budget
- economy/fast 모드에서는 RAG 제한
- Provider 별 max prompt chars 준수

## 7. Provider 보호 감사

### 7.1 품질 점수는 선의의 Provider 를 갈아 넣을 수 있다

품질 점수가 높으면 좋은 Provider 에 요청이 몰린다. 이는 서비스 품질은 올라가도 사람의 PC를 망가뜨릴 수 있다.

필수 invariant:

- Provider protection > availability > quota > fairness > quality score
- quality score 는 최종 선택의 보조 요소일 뿐
- 최근 처리량/cooldown/idle bonus 가 품질 점수보다 강해야 함
- high quality Provider 에도 max share 제한 필요

### 7.2 성장 레벨이 기여 경쟁을 부추길 위험

AI 성장 레벨은 제품적으로 좋지만 위험하다.

나쁜 UX:

- “더 높은 레벨을 위해 Provider 더 켜세요”
- “고성능 모델이 부족합니다”를 특정 유저 압박처럼 표현

권장:

- 성장 레벨은 감사/가시화 중심
- 기여 강요 문구 금지
- Provider 보호 문구와 함께 표시
- 순위보다 “추가된 능력” 중심

## 8. 권한/승인 감사

### 8.1 AI 설정 변경은 생각보다 위험하다

다음 변경은 high risk 로 봐야 한다.

- system prompt 변경
- AI 헌법 변경
- RAG 지식 추가/삭제
- 모델 정책을 heavy/restricted 허용으로 변경
- fan-out 허용
- Provider tag 조건 완화
- 안전 문구 제거

권장:

- `AI 관리자` 역할 별도 도입
- high risk 는 승인 필요
- 소규모 서버는 approval optional 가능
- 승인 payload hash 저장
- 승인 후 payload 변경 시 재승인

## 9. 운영/장애 감사

### 9.1 설정 장애가 질문 기능 전체 장애로 번질 위험

Channel AI 설정이 깨져도 기본 질문은 가능해야 한다.

필수 fallback:

- ChannelAi 없음 → guild default AI
- BehaviorVersion 없음 → safe default behavior
- KnowledgeSpace 장애 → RAG 없이 답변
- Projection 장애 → 대시보드만 degraded, 질문은 정상
- Model 선택 실패 → 자동 모델 fallback 또는 명확한 안내

### 9.2 Migration 위험

기존 `channel_ai_profile` 이 이미 운영에 있다. 새 `channel_ai` 로 옮길 때 위험하다.

필수:

- backfill script
- compatibility service
- rollback plan
- 운영 DB dry run
- 중복 unique 제약 확인

## 10. MVP 재조정 제안

### 구현은 먼저 만들지 말아야 하지만, 기획은 먼저 끝내야 할 것

아래 기능은 foundation MVP 전에 runtime 구현하면 위험하다. 그러나 도메인/DB/보안/CI-CD 기획은 반드시 먼저 끝내야 한다.

- RAG 업로드 전체 — Dailyting RAG 스택 이식 설계, Qdrant/CI/CD/보안 게이트까지 선기획
- 다중 응답/fan-out — Provider 보호와 비용 상한까지 선기획 (`04_MULTI_RESPONSE_DESIGN.md`)
- 공개/웹 프리셋 공유 — 게시/가져오기/수정/삭제/추천/신고까지 선기획
- 복잡한 성장 레벨 gamification — 기여 강요 방지 정책까지 선기획
- 실시간 대시보드 전체 — projection/freshness/권한 설계까지 선기획

### 먼저 만들어야 할 Foundation MVP

1. `ChannelAi` 기본 모델
2. `AiBehaviorVersion` immutable version
3. `AiChangeProposal` 최소 승인 흐름
4. `CustomizationAuditLog`
5. 기존 `channel_ai_profile` migration/compatibility
6. Channel AI 카드 읽기 API/Discord embed
7. 마법사로 draft 생성
8. publish/rollback
9. safe default fallback
10. 권한/승인/버전 테스트

이 10개가 없으면 나머지는 전부 흔들린다.

## 11. 설계 수정 권고

### 반드시 수정

- [ ] `ChannelAi` 책임을 더 작게 제한한다.
- [ ] snapshot/reference 정책을 명문화한다.
- [ ] AI 헌법/RAG/사용자 질문의 prompt 우선순위를 명문화한다.
- [ ] system prompt 저장/노출 정책을 명문화한다.
- [ ] Provider quality score 가 Provider 보호를 넘지 못한다는 invariant 를 라우터 설계에 넣는다.
- [ ] RAG 는 별도 보안 게이트 전까지 구현 후순위로 둔다.
- [ ] projection stale/freshness 정책을 넣는다.
- [ ] 기존 `channel_ai_profile` 마이그레이션 전략을 구체화한다.

### 가능하면 수정

- [ ] `AiNetworkLevel` 은 초기에 계산형 projection 으로만 둔다.
- [ ] `AiPreset` 1차 구현은 behavior snapshot 만 지원하되, 장기 설계는 웹 게시/가져오기/수정/삭제/추천/신고까지 포함한다.
- [ ] 대시보드 1차는 Discord 패널 + 읽기 API 만 만든다.
- [ ] 웹 대시보드는 인증/권한 설계 이후 시작한다.

## 12. 최종 판정

현 설계는 방향은 좋지만, 그대로 전 기능을 밀어붙이면 위험하다.

판정:

- **도메인 방향**: 통과
- **RAG 포함 전체 구현**: 보류, 단 Dailyting 기반 기술스택/인프라/CI-CD 기획은 선행
- **대시보드 전체 구현**: 보류, 단 projection/권한/정보구조 기획은 선행
- **Foundation MVP**: 진행 권장
- **필수 선행 조건**: 버전/승인/권한/Provider 보호 invariant 를 코드 레벨로 강제

가장 중요한 결론:

> AI 네트워크의 핵심은 대시보드가 아니라, 안전하게 버전 관리되는 Channel AI 설정이다.  
> 이 foundation 이 없으면 대시보드, RAG, 모델 선택, 프리셋, 성장 레벨은 모두 불안정한 장식이 된다.

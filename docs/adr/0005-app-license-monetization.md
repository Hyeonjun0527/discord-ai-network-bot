# ADR 0005: 데스크톱 앱 트라이얼웨어 라이선스 수익화

- 상태(Status): 승인됨 (Accepted)
- 날짜(Date): 2026-06-10
- 결정자(Deciders): Hyeonjun0527
- 관련: [ADR 0003 커뮤니티 Provider Pool](./0003-community-provider-pool.md)의 비-목표를 **부분 supersede** 한다.
  작업 계획: [`ROADMAP_MONETIZATION_300.md`](../ROADMAP_MONETIZATION_300.md) (확정 정책 P1~P20 표 포함).

## 맥락 (Context)

운영 비용(서버·도메인)과 지속 가능성을 위해 수익화가 필요하다. 검토된 구독 모델
(개인 연 $6 / 서버 월 $1~2, 토큰 미터링)은 폐기되었다 — 소액 반복 결제는 MoR 수수료
(Paddle 5%+$0.50)가 매출의 30~105% 를 잠식하고, 클라우드 원가를 우리가 계속 부담하는
구조였다. 대신 **원가가 0 인 데스크톱 앱 라이선스**(일회성 $10, 수수료 10%)로 단일화한다.

ADR 0003 은 "판매자/구매자/가격표/수수료/정산" 을 비-목표로 명시했다. 이 결정의 **정신은
유지**한다: 풀 기여는 여전히 무보상·무과금이며, 프로바이더는 판매자가 아니다. 유료화 대상은
풀이 아니라 **데스크톱 앱의 관리 편의 기능**이다.

## 결정 (Decision)

**가입(central 계정 최초 연결) 후 3개월 무료 체험 → $10 일회성 영구 라이선스**(트라이얼웨어).

| 항목 | 결정 |
|---|---|
| 영구 무료(체험 만료 후에도) | 풀 기여 · 로컬 모델 설치 · `/ask` · `/그림` (제품 코어) |
| 유료(라이선스 필요) | **서버 관리 프리미엄** — 관리자뷰 고급: 페르소나 커스텀·채널별 설정·RAG 지식·프리셋·안전 리포트 |
| 체험 시계 | 유저 단위, 최초 계정 연결 시점부터 달력 3개월(UTC). 기존 유저는 MIN(provider.created_at) 백필(EPOCH 오염값은 기능 배포일로 대체) |
| 런칭 이벤트 | 기여 실적 ≥1건 + 계정 연결이면 앱 버튼으로 **평생 무료(EVENT_FREE)** 등록. 운영자가 닫을 때까지 무기한·정원 없음. 권리는 $10 구매와 동일(그랜드파더링 불변) |
| 귀속 | Discord 계정당 1개, 기기 무제한(계정 공유 금지는 EULA 명시만) |
| 가격 | USD $10 단일(Paddle 자동 환산 표기) |
| 환불 | 30일 무조건. webhook 수신 즉시 REVOKED + `refund_flag`(체험/이벤트 재부여 금지, 재구매는 허용) |
| 어뷰즈 | 신규 Discord 계정 체험 리셋은 기술적으로 막지 않음(EULA 금지 조항만) |
| 오픈소스 | MIT 유지 + 신사협정. 유료 가치는 central entitlement 가 실체(소스 빌드 우회는 서버가 거부) |
| 영구의 정의 | "서비스 존속 기간 내 영구" 를 EULA 에 명시. 종료(sunset) 시 3개월 사전 고지 + 서버 검증 없이 동작하는 해제 빌드 공개 |
| BYOK 클라우드 | 관리자 BYOK·freeask 한도(30/시·100/일) 전부 무료 동결. 모델만 `gemini-3.1-flash-lite` 교체 |
| 게이미피케이션 | XP/레벨/리더보드 폐지 → **니아 호감도**(사용할수록 증가, 순위 비교 없음)로 교체 |
| 사업자 | 샌드박스로 전체 구현, 실결제 전 사업자 등록(간이과세+통신판매업) 후 Paddle 실계정 전환 |

### 검증 아키텍처

- **판정은 central 단일 함수**: `EntitlementService(userId) → REVOKED > EVENT_FREE > LICENSED > TRIAL > EXPIRED > FREE`.
- **유료 기능 강제는 central**: 서버 관리 프리미엄 API 경로에 선언적 feature-flag 매핑
  (`기능키 → FREE|LICENSED`) 1곳으로 게이트. 실시간 판정이라 환불 회수 지연 0, 구버전/개조 앱 우회 불가.
- **앱 UI 는 이중 게이트(UX 용)**: 서명 entitlement 토큰 `lv1.<payload>.<hmac>`(dv1 패턴 재사용,
  **TTL 7일, UI 표시 전용**)을 `/provider/agent/sync` 응답에 동봉 — 와이어 프로토콜 무변경(신규 필드 optional).
  만료 시 '확인 불가' 표시(판정 권위는 항상 central).
- **결제**: 앱 내 구매만. checkout `custom_data.discordUserId` 는 **문자열**(64bit JS 정밀도 함정).
  webhook 은 Paddle-Signature HMAC 검증 + `billing_event.event_id` UNIQUE 멱등.
- **데이터 모델**: `user_license`(user_id UNIQUE, status, source PADDLE|EVENT_GRANT|ADMIN_GRANT,
  trial_started_at, licensed_at, revoked_at, refund_flag, paddle_*) + `billing_event`(멱등/감사) + `license_audit`(전이 로그).
- **환경**: `PADDLE_ENV=sandbox|live` + API key/webhook secret/price ID 전부 ENV 외부화(전환=환경변수 교체).

### ADR 0003 과의 경계 (부분 supersede)

- 유지: 풀 내 거래 금지 — provider/routing 도메인에 billing·price·seller·payout 개념을 넣지 않는다.
- 변경: 저장소에 **별도 `licensing` bounded context** 를 신설한다(헥사고날, 도메인 순수성 ArchUnit 강제).
  라이선스는 풀의 기여 모델과 무관한 앱 제품 권한이다.

## 결과 (Consequences)

- 약관 제1조("판매·결제가 아닌…")·개인정보처리방침(Gemini 국외 전송·Paddle) 전면 개정 + 버전 재동의 메커니즘 필요.
- 퍼널 계측은 central 서버측 집계만(앱 텔레메트리 확장 없음 — 프라이버시 원칙 유지).
- 정책 변경은 본 ADR 개정으로만 한다(P1~P20 상세와 단계별 계획은 ROADMAP_MONETIZATION_300.md).

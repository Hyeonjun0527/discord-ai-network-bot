# 수익화(트라이얼웨어 라이선스) — 300 단계 체크리스트

> **작성: 2026-06-10. 정책 20개 전부 유저 확정 완료(같은 날, 5라운드).**
> 전략: **데스크톱 앱 — 가입 후 3개월 무료 체험 → $10 일회성 영구 라이선스**(트라이얼웨어).
> 클라우드(Gemini) 구독안(개인 연$6/서버플랜)은 **폐기**. 클라우드는 관리자 BYOK 만 유지하고 무료 클라우드
> 모델은 `gemini-2.5-flash-lite` → `gemini-3.1-flash-lite` 교체(공식 ID·가격 $0.25/$1.50 per 1M 확인됨).
> **영구 무료(체험 만료 후에도)**: 풀 기여 · 로컬 모델 설치 · `/ask` · `/그림`. 유료 = **서버 관리 프리미엄**.
> **런칭 이벤트**: 기여 실적 1건 이상이면 앱 버튼으로 평생 무료 등록(운영자가 닫을 때까지 무기한·정원 없음).
> **결제**: Paddle(MoR, $10 수수료 10%) — 샌드박스로 전체 구현 후 실계정 전환. **환불**: 30일 무조건(즉시 회수).
>
> 안전 루프: 구현 → 빌드/테스트 → 커밋 → 다음. 상태: `[ ]` 미착수 · `[x]` 완료 · `[-]` 환경상 불가(유저/외부 작업).
>
> 근거: 2026-06-10 코드베이스 탐사(에이전트 11, 도구 214회). 핵심 사실 — 결제/라이선스 코드 **전무**(그린필드),
> Flyway 최신 V43(신규는 V44~), durable 토큰 dv1 HMAC 패턴·폐기 테이블 선례 존재, ADR 0003 이 billing 을
> 비-목표로 명시(supersede 필요), LICENSE=MIT, 약관 제1조 "판매·결제 아님" 충돌(개정 필수),
> agentVersion 보고·자동업데이트 인프라 존재, i18n/contract/desktop-check 가드 체계 완비.

## 확정 정책 20 (2026-06-10 유저 결정 — 변경은 ADR 0005 개정으로만)

| # | 정책 | 결정 |
|---|---|---|
| P1 | 유료 기능 | **서버 관리 프리미엄**(관리자뷰 고급: 페르소나 커스텀·채널별 설정·RAG 지식·프리셋·안전 리포트). 실유저 없으므로 공격적 전환 OK |
| P2 | MIT↔페이월 | **MIT 유지 + 신사협정**(유료 기능이 central API 경유라 서버 강제는 자연 성립) |
| P3 | 체험 시계 | **유저 단위 — 가입(최초 central 계정 연결)부터 3개월**. 기존 유저는 이벤트로 흡수 |
| P4 | 귀속 단위 | **Discord 계정당 1개, 기기 무제한**(계정 공유 금지는 EULA 명시만) |
| P5 | 구매 진입점 | **앱 내 구매만** — checkout custom_data=discordUserId(**문자열**, 64bit 정밀도 함정) |
| P6 | 검증·grace | **central 실시간 판정 + 앱 UI 표시용 서명 캐시 7일** |
| P7 | 강제 지점 | **이중**: central 판정(보안) + 앱 UI 잠금(UX). 선언적 feature-flag 매핑 1곳 |
| P8 | 만료 UX | **soft-lock(비활성+업셀) + 앱 배너 D-14/D-3/D-day**(DM 없음) |
| P9 | 퍼널 계측 | **central 서버측 집계만**(앱 텔레메트리 확장 없음) |
| P10 | 이벤트 자격 | **기여 실적 1건 이상**(풀에서 요청 1건 이상 처리) + 계정 연결 |
| P11 | 이벤트 기간·정원 | **운영자가 닫을 때까지 무기한, 정원 없음**(어드민 토글) |
| P12 | 평생 무료 범위 | **$10 영구 라이선스와 동일 권리**(향후 상위 티어 별도 판매 가능) |
| P13 | 가격 | **USD $10 단일**(Paddle 자동 환산 표기) |
| P14 | 환불 | **webhook 즉시 회수(REVOKED) + 환불 계정 체험/이벤트 재부여 금지, 재구매 허용**(EULA 명시) |
| P15 | 체험 리셋 어뷰즈 | **수용(기술적 차단 없음)** — EULA 금지 조항만 |
| P16 | 기여 인센티브 | **없음. XP/레벨/리더보드 폐지 → 니아 호감도(사용할수록 증가)로 교체 — 이번 작업 범위 포함** |
| P17 | 수익 주체 | **샌드박스 구현 완료 후, 실결제 전 사업자 등록**(간이과세+통신판매업) |
| P18 | 약관 체계 | **표준안 일괄**: 버전+재동의 모달 · ko 원본+en/ja 참고용 · 성년 자기확인 체크박스 · 미성년 취소=환불 |
| P19 | 영구·sunset | **'서비스 존속 기간 내 영구' 명시 + 종료 시 3개월 사전 고지 + 검증 해제 빌드 공개** |
| P20 | BYOK 경계 | **BYOK·freeask 전부 무료 동결, 모델만 3.1 교체**(EULA에 BYOK 비용/책임 조항 + 단가 고지) |

## 차수 0 — 정책·법무·문서 기반 (1~24)

- [x] 1. 미결정 정책 P1~P20 유저와 전부 확정(2026-06-10, AskUserQuestion 5라운드)
- [ ] 2. ADR 0005 작성: 앱 라이선스 수익화 — ADR 0003 'billing 비-목표' **부분 supersede**(풀 기여는 여전히 무보상, 앱 라이선스만 유료) + P1~P20 결정 기록
- [ ] 3. ADR 0005 에 검증 아키텍처 기록: central 실시간 판정 + lv1 서명 캐시 7일(UI 용) + 이중 게이트
- [ ] 4. `docs/BILLING_SUBSCRIPTION_TODO.md` 전면 갱신 — 구독안 폐기 명시, 본 로드맵 포인터화
- [ ] 5. 본 문서 최신화 유지(결정 변경 시 ADR 경유)
- [ ] 6. README '공식 빌드·서버측 가치' 절 추가(P2: MIT 유지 — 라이선스는 central entitlement 가 실체)
- [ ] 7. EULA ko 개정 초안: 라이선스 부여·30일 환불(회수·재부여 금지)·정지 절차(중대/일반 차등)·'서비스 존속 기간 내 영구'·계정 공유 금지·BYOK 비용/책임·체험 리셋 어뷰즈 금지
- [ ] 8. EULA en/ja 작성 + 'ko 원본 우선, 번역은 참고용' 명시
- [ ] 9. 개인정보처리방침 개정: Gemini 국외 전송(Google)·Paddle 결제정보(국외 이전)·central 퍼널 집계 항목
- [ ] 10. 환불 정책 단독 페이지(30일 무조건 + 회수/재부여 금지 조항 — Paddle 실계정 심사 요건)
- [ ] 11. 사업자/特定商取引法 표기 페이지 골격(KR/JP — 사업자 정보는 P17 등록 후 기입)
- [ ] 12. 약관 버전·재동의 설계: consent 마커에 버전 기록, 불일치 시 재동의 모달(P18)
- [ ] 13. 가격·이벤트 카피 확정(ko/en/ja): "$10 평생 이용권(서비스 존속 기간)"·"3개월 무료 체험"·"런칭 기념 평생 무료 등록"
- [ ] 14. 이벤트 운영 파라미터 확정 반영: 자격=기여 1건+계정 연결, 기간=어드민 토글, 정원 없음
- [-] 15. Paddle 샌드박스 계정 생성(유저 작업)·API 키/웹훅 시크릿 확보
- [ ] 16. Paddle 샌드박스 product/price 생성($10 일회성, USD 단일)
- [ ] 17. ENV 설계: `PADDLE_ENV`/`PADDLE_API_KEY`/`PADDLE_WEBHOOK_SECRET`/`PADDLE_PRICE_ID` — `.env.example` 갱신(실값 금지)
- [ ] 18. `requirements.yaml` 에 @REQ-LIC-* P0 요구사항 정의(체험/구매/환불/이벤트/강제)
- [ ] 19. 라이선스 상태머신 용어 확정: `FREE`/`TRIAL`/`EXPIRED`/`LICENSED`/`EVENT_FREE`/`REVOKED`
- [ ] 20. 기존 유저 공지문 초안: 가입일 기준 3개월 체험 + 기여 1건이면 평생 무료 등록 안내
- [ ] 21. BYOK 고지 문구: 모델 3.1 교체·단가 3.5배·키 소유자 비용 책임(P20)
- [ ] 22. `scripts/check_links.py` 통과(문서 링크)
- [ ] 23. 차수 0 검증: P1~P20 전부 ADR 반영 확인
- [ ] 24. `main` 에서 `feat/monetization-license` 브랜치 생성

## 차수 1 — Gemini 3.1 Flash-Lite 교체 (25~40) — 정책 독립, 선행 가능

- [ ] 25. central `DiscordBot.kt:496` `FREE_CLOUD_MODEL` → `gemini-3.1-flash-lite`
- [ ] 26. central `SlashCommandCatalog.kt` 주석 모델명 갱신
- [ ] 27. agent `gemini.py` `DEFAULT_GEMINI_MODEL` + 모듈 docstring 갱신
- [ ] 28. agent `config.py:38` 주석 갱신
- [ ] 29. agent `agent.py` 라이브 적용 주석/기본값 갱신
- [ ] 30. `tests/test_agent.py` 2곳(L108, L161) 갱신
- [ ] 31. `tests/test_gemini.py` 주석·상수 사용 확인(상수 참조면 자동 통과)
- [ ] 32. 문서 내 모델 문자열 갱신(BILLING TODO 포함 3곳)
- [ ] 33. freeask rate limit(30/시·100/일) 현행 유지 확인(P20: 무료 동결)
- [ ] 34. `gradlew build` 그린(central)
- [ ] 35. `ruff`+`mypy`+`pytest`(cov≥70%) 그린(agent)
- [ ] 36. 교체 커밋(Conventional, why=공식 ID/가격 확인 근거 포함)
- [ ] 37. PR 생성·CI 그린
- [ ] 38. 머지 → `central-deploy` 자동배포 헬스 확인
- [ ] 39. 라이브 `/무료질문` 1회 실검증(3.1 응답 확인)
- [ ] 40. 차수 1 검증 기록

## 차수 2 — licensing 도메인·스키마 (41~70)

- [ ] 41. `licensing` 패키지 스캐폴딩: `domain`/`application`/`adapter/{inbound/web,outbound/persistence}` (헥사고날, provider 파일럿 패턴)
- [ ] 42. ArchUnit `migratedDomainsArePure` 에 `licensing` 추가(도메인 순수성 강제)
- [ ] 43. Flyway V44 `user_license`: user_id BIGINT UNIQUE, status, source(`PADDLE|EVENT_GRANT|ADMIN_GRANT`), trial_started_at, licensed_at, revoked_at, refund_flag(체험/이벤트 재부여 금지), paddle_customer_id, paddle_transaction_id, created_at, updated_at
- [ ] 44. Flyway V45 `billing_event`: event_id UNIQUE(멱등), event_type, raw(JSONB), processed_at, error
- [ ] 45. Flyway V46 `license_audit`: 상태 전이 감사 로그(전이 전/후·사유·행위자)
- [ ] 46. 도메인 모델: `LicenseStatus` enum + `License` 애그리거트(순수 Kotlin, Spring/JPA 의존 0)
- [ ] 47. 상태머신 전이 규칙 구현: TRIAL→EXPIRED/LICENSED, LICENSED→REVOKED, EVENT_FREE=구매와 동일 권리·불변(P12)
- [ ] 48. `LicenseRepository` 포트(domain) + JPA 어댑터(outbound)
- [ ] 49. `LicenseEntity` @Entity — `adapter.outbound.persistence` 위치(ArchUnit 강제 확인)
- [ ] 50. `EntitlementService`: userId → 판정 단일 함수(status + trialEndsAt + 근거)
- [ ] 51. trial 시작 = **유저 최초 central 계정 연결(가입) 시점** 기록(P3)
- [ ] 52. `Clock` 주입(직접 `Instant.now()` 금지 — 만료 테스트 가능성)
- [ ] 53. 만료 규칙 명문화: 달력 3개월(`plusMonths(3)`, UTC 저장)
- [ ] 54. 기존 유저 백필: MIN(provider.created_at) per user — **EPOCH/결측 오염값은 라이선스 기능 배포일로 대체**(오만료 방지 가드)
- [ ] 55. `ADMIN_GRANT` 수동 부여/회수 유스케이스(운영 도구)
- [ ] 56. 상태머신 전 전이 단위 테스트
- [ ] 57. EntitlementService 통합 테스트
- [ ] 58. @REQ-LIC feature 파일: 체험 시작/만료 시나리오
- [ ] 59. 중복 trial 시작 멱등(UNIQUE + upsert) 레이스 테스트
- [ ] 60. audit 전이 기록 검증
- [ ] 61. 운영 쿼리: 상태별 카운트 리포지토리 메서드(퍼널 집계 P9 겸용)
- [ ] 62. 멀티 길드 provider 의 유저 단위 판정 확인(guild 무관)
- [ ] 63. OAuth 미연결 유저(앱만 설치) = 체험 미시작, 연결 순간 시작(P3)
- [ ] 64. PII 최소화 점검: 라이선스 테이블에 이메일 미저장(Paddle ID 만)
- [ ] 65. ktlint 통과
- [ ] 66. ArchUnit 통과
- [ ] 67. Kover 게이트 통과
- [ ] 68. `gradlew build` 그린
- [ ] 69. 커밋(도메인 단위)
- [ ] 70. 차수 2 검증 기록

## 차수 3 — entitlement 판정 API·체험 시계 (71~92)

- [ ] 71. EntitlementStatus 응답 DTO(camelCase 와이어 규약)
- [ ] 72. `GET /license/me` 조회 API(durable 토큰 인증 — ProviderAdminController authedAdmin 패턴 재사용)
- [ ] 73. SecurityConfig 경로 추가 + fail-closed 테스트(인증 없는 요청 거부)
- [ ] 74. 만료 경계 테스트(만료 직전/직후, UTC 자정 경계)
- [ ] 75. 판정 우선순위 구현: REVOKED > EVENT_FREE > LICENSED > TRIAL > EXPIRED > FREE
- [ ] 76. 판정 결과 캐싱 전략(in-memory 단기 TTL — 부하 대비)
- [ ] 77. 컨트롤러(adapter.inbound.web) + OpenAPI 노출 확인
- [ ] 78. BDD: 체험 시작/만료/이벤트 우선순위 시나리오
- [ ] 79. Testcontainers(-PdockerTests) 통합 그린
- [ ] 80. 시간 표기 규칙: UTC 저장·로케일 표시(응답에 epoch/ISO 통일)
- [ ] 81. 체험 D-day 계산 유틸 + 테스트(D-14/D-3/D-day 판정 P8)
- [ ] 82. 상태 전이 알림 훅 포인트(만료 임박 — 차수 7 배너에서 소비)
- [ ] 83. 어드민 강제 만료/연장 도구(지원 대응용)
- [ ] 84. 잘못된 상태 조합 방어(Fail Fast — REVOKED+TRIAL 동시 불가)
- [ ] 85. refund_flag 반영: 환불 이력 계정은 체험/이벤트 재부여 거부(P14)
- [ ] 86. 체험 리셋: 기술적 가드 없음 확인 — EULA 조항만(P15, 코드 작업 없음 명시)
- [ ] 87. 단위 테스트 보강(우선순위/경계/refund_flag)
- [ ] 88. ktlint/ArchUnit/Kover 통과
- [ ] 89. `gradlew build` 그린
- [ ] 90. 커밋
- [ ] 91. requirements.yaml 추적성 테스트 그린
- [ ] 92. 차수 3 검증 기록

## 차수 4 — entitlement 전달·강제(와이어/게이트) (93~122)

- [ ] 93. 서명 entitlement 토큰 포맷: `lv1.<payload>.<hmac>` (dv1 패턴 재사용, **TTL 7일 — 앱 UI 표시 전용**, P6)
- [ ] 94. `LicenseTokenService` 구현(HMAC-SHA256, Clock 주입, 상수시간 비교)
- [ ] 95. 토큰 payload: userId·status·trialEndsAt·만료시각 — PII 미포함 확인
- [ ] 96. `/provider/agent/sync` 응답에 entitlement 동봉(기존 주기 폴링 재사용, P6)
- [ ] 97. sync DTO 하위호환 확인(신규 필드 optional — 구버전 무시)
- [ ] 98. auth_ok 동봉은 **불채택**(sync 로 충분) — wire-contract 무변경 확인
- [ ] 99. (98 확정 시 skip) 와이어 변경 발생하면 `make wire-gen` 재생성
- [ ] 100. `make contract` 그린(WireContractTest·test_contract 양측)
- [ ] 101. agent: entitlement 수신·메모리 보관(connection.py 또는 sync 소비부)
- [ ] 102. agent: 서명 토큰 캐시를 config.json(0600)에 저장 — frozen 환경 동작 확인
- [ ] 103. agent: 만료 판정은 서명 내 시각 기준(로컬 시계 조작 영향 분석 문서화)
- [ ] 104. central 선언적 feature-flag 게이트: 기능키→`FREE|LICENSED` 매핑 1곳 — **서버관리 프리미엄 기능 키 등록**(페르소나/채널별/RAG/프리셋/안전 리포트, P1)
- [ ] 105. central 게이트 적용: 해당 admin API 경로에 entitlement 체크(P7 — 우회 불가 지점)
- [ ] 106. agent UI 잠금 헬퍼(이중 게이트 — UX 용, 보안은 central, P7)
- [ ] 107. 구버전 에이전트 무해성 검증(신규 필드 무시·기존 기능 영향 0)
- [ ] 108. 캐시 7일 만료 후 UI '확인 불가' 상태(soft-lock 아님 — 판정은 central 이 함, P6)
- [ ] 109. 위조/만료 토큰 거부 테스트
- [ ] 110. revoke 반영 즉시성 테스트(central 실시간 판정 경로는 지연 0)
- [ ] 111. 재접속/재sync 시 entitlement 갱신 확인
- [ ] 112. 만료 임박 시 sync 주기 단축 검토(선택)
- [ ] 113. agent 단위 테스트(수신/캐시/만료/위조)
- [ ] 114. central 단위 테스트(발급/검증/TTL)
- [ ] 115. BDD: 유료 기능 게이트 허용/거부 시나리오
- [ ] 116. `ruff`+`mypy`+`pytest` 그린
- [ ] 117. `gradlew build` 그린
- [ ] 118. `make contract` 최종 그린
- [ ] 119. 로그 마스킹: entitlement 토큰 로그 미기록
- [ ] 120. 보안 검토: 토큰 재사용·재전송 시나리오 점검
- [ ] 121. 커밋
- [ ] 122. 차수 4 검증 기록

## 차수 5 — Paddle 샌드박스 결제 (123~158)

- [ ] 123. Paddle API 클라이언트(Kotlin HTTP 직접 — 의존 최소) + ENV 바인딩(`@ConfigurationProperties paddle.*`)
- [ ] 124. 환경 분리: `PADDLE_ENV=sandbox|live` 로 키/엔드포인트 전환(코드 변경 없는 전환)
- [ ] 125. `.env.example` 갱신(키 4종 자리표시, 실값 금지)
- [ ] 126. checkout 링크 생성 유스케이스: price ID + `custom_data.discordUserId`(**문자열** — 64bit 정밀도 함정, P5)
- [ ] 127. custom_data 직렬화 테스트(JS Number 정밀도 손실 회귀 가드)
- [ ] 128. `POST /license/checkout` API(durable 토큰 인증, checkout URL 반환 — 앱 내 구매 전용, P5)
- [ ] 129. `POST /billing/webhook/paddle` 수신 엔드포인트
- [ ] 130. SecurityConfig: webhook permitAll + 그 외 fail-closed(과거 /provider/admin 누락 사고 회귀 방지 테스트)
- [ ] 131. `Paddle-Signature` HMAC 검증(ts;h1 포맷·타임스탬프 리플레이 윈도)
- [ ] 132. 서명 불일치/부재 → 401 테스트
- [ ] 133. `billing_event` 멱등 처리(event_id UNIQUE insert-or-skip)
- [ ] 134. `transaction.completed` → LICENSED 발급 + audit
- [ ] 135. `refund`/`chargeback` → **즉시 REVOKED + refund_flag 세트**(P14) + audit
- [ ] 136. dispute 이벤트 로깅(대응은 수동 runbook)
- [ ] 137. custom_data 미매칭(없음/깨짐) → 보류 상태 + 운영 알림
- [ ] 138. 보류 결제 수동 매칭 어드민 도구(클레임 코드 대신 — 앱 내 구매라 발생률 낮음)
- [ ] 139. 환불 계정 재구매 허용 + 체험/이벤트 재부여 금지 동작(P14)
- [ ] 140. webhook 중복 재전송 멱등 테스트
- [ ] 141. 배포 중 이벤트 유실 대비: Paddle 이벤트 대사(일일 배치 또는 수동 runbook) 선택 구현
- [ ] 142. 샌드박스 실결제 E2E: 테스트 카드 checkout→webhook→LICENSED 전파
- [ ] 143. 샌드박스 환불 E2E: refund→REVOKED→에이전트 강등 확인
- [ ] 144. price ID 화이트리스트(금액/통화 위조 방어)
- [ ] 145. webhook 처리 예외 안전(트랜잭션 경계·재시도 시 멱등)
- [ ] 146. 운영 로그: 결제/환불 이벤트(이메일 등 PII 마스킹)
- [ ] 147. BDD: 구매/환불 @REQ-LIC 시나리오
- [ ] 148. Testcontainers 통합 그린
- [ ] 149. 단위 테스트(서명/멱등/매칭/화이트리스트)
- [ ] 150. OpenAPI 문서 확인
- [ ] 151. webhook rate limit(폭주 방어)
- [ ] 152. 시크릿 로그 미노출 확인
- [ ] 153. ktlint/Kover 통과
- [ ] 154. `gradlew build` 그린
- [ ] 155. 커밋
- [ ] 156. PR 분할: 도메인(차수 2~4)/결제(차수 5) 분리 검토
- [ ] 157. 결제 장애 runbook 초안(webhook 다운·중복·미매칭 대응)
- [ ] 158. 차수 5 검증 기록

## 차수 6 — 런칭 이벤트(얼리버드 평생 무료) (159~178)

- [ ] 159. 이벤트 설정: 어드민 토글(열림/닫힘 — 운영자가 끌 때까지 무기한, P11), 정원 없음
- [ ] 160. 신청 유스케이스: `EVENT_GRANT` 발급(멱등 — 중복 신청 1회 처리)
- [ ] 161. 신청 창구 API `POST /license/event/claim`(앱 버튼 전용, durable 토큰 인증)
- [ ] 162. 자격 검증: **기여 실적 ≥ 1건**(contribution_log 존재) + 계정 연결(P10)
- [ ] 163. refund_flag 계정 신청 거부(P14)
- [ ] 164. 닫힘 상태 신청 거부 + 안내 응답
- [ ] 165. 그랜드파더링 불변성: EVENT_FREE = $10 구매와 동일 권리·이후 정책 변경에도 유지(P12) 테스트
- [ ] 166. 이벤트 현황 조회 API(열림 여부·신청자 수)
- [ ] 167. 어드민 수동 부여/회수 도구 연동
- [ ] 168. 신청 audit 기록
- [ ] 169. 단위/통합 테스트(자격/멱등/닫힘/불변성)
- [ ] 170. BDD: 신청/마감/불변성 시나리오
- [ ] 171. i18n 문구 키 추가(bot/desktop — 신청/완료/자격 미달/마감, ko/en/ja)
- [ ] 172. 공지 카피 최종화("기여 1건이면 평생 무료")
- [ ] 173. `gradlew build`+`make i18n-check` 그린
- [ ] 174. 커밋
- [ ] 175. 이벤트 신청 흐름 E2E 리허설
- [ ] 176. 이벤트 모니터링 쿼리(신청 추이)
- [ ] 177. 닫기 토글 동작 확인(닫은 뒤 기존 EVENT_FREE 유지)
- [ ] 178. 차수 6 검증 기록

## 차수 7 — 데스크톱 앱 UI (179~228)

- [ ] 179. 상태별 UX 매트릭스 설계: 미연결/체험중(D-n)/만료/라이선스/이벤트무료/확인불가(오프라인 7일+)
- [ ] 180. `prototypes/desktop` 설정탭 '라이선스' 섹션 마크업(SSOT — webui_assets 직접 수정 금지)
- [ ] 181. 홈 히어로 체험 배너(D-14/D-3/D-day·구매 CTA) 마크업(P8)
- [ ] 182. 라이선스 모달: 상태·구매 버튼·이벤트 신청(기여 1건 자격 표시)·구매 복원
- [ ] 183. `contract.js` ENDPOINTS 추가: licenseStatus/checkout/eventClaim
- [ ] 184. `adapter.js` 실 구현(webui 경유 HTTP)
- [ ] 185. `adapter.js` mock 구현(`@proto-only` 격리 — 실 앱 미누수)
- [ ] 186. `make desktop-shapes` 재생성(contract-shapes.json 동기)
- [ ] 187. `webui.py` `GET /api/license`(agent 캐시 + central 프록시)
- [ ] 188. `webui.py` `POST /api/license/checkout`(외부 브라우저용 URL 반환)
- [ ] 189. `webui.py` `POST /api/license/event-claim`
- [ ] 190. 구매 후 활성화 폴링(webhook 처리 감지 → 성공 토스트)
- [ ] 191. 만료 soft-lock: 서버 관리 프리미엄 메뉴 비활성 + 업셀 배너(낙관적 전환 금지 — 실 상태 대기, P1/P8)
- [ ] 192. D-14/D-3/D-day 배너 노출 정책 구현(P8)
- [ ] 193. 온보딩에 체험 고지 추가(A 단계 — "가입 후 3개월 무료")
- [ ] 194. 약관 재동의 모달(버전 불일치 시, P18)
- [ ] 195. 법적 문서 화면 갱신(EULA/개인정보/환불 — 차수 0 산출물 반영)
- [ ] 196. i18n `desktop` 키 추가(전 신규 문구 ko/en/ja — `i18n/messages.json` SSOT)
- [ ] 197. `make i18n-gen` 재생성(생성본 직접 편집 금지)
- [ ] 198. 하드코딩 한글 0 확인(잔여 스캔 grep)
- [ ] 199. `node --check` 전 변경 JS(따옴표 파싱 버그 가드)
- [ ] 200. 결제 버튼 → 외부 브라우저 Paddle hosted checkout 오픈
- [ ] 201. checkout 실패/취소 UX(복귀 안내)
- [ ] 202. 오프라인 시 라이선스 표시(서명 캐시 7일 + 만료 시 '확인 불가' 상태, P6)
- [ ] 203. 이벤트 버튼 상태(닫힘/자격 미달 비활성 + 사유 표시)
- [ ] 204. D-day 트레이/알림 검토(선택)
- [ ] 205. 서버 관리(관리자뷰) 프리미엄 잠금 UI: 비활성+업그레이드 안내(P1)
- [ ] 206. playwright: 6개 상태 렌더 시나리오
- [ ] 207. playwright: 구매 플로우(mock)
- [ ] 208. playwright: 재동의 모달
- [ ] 209. playwright 전체 그린(기존 53 + 신규)
- [ ] 210. `make sync-desktop`(mock/@proto-only 누수 가드 통과)
- [ ] 211. `make desktop-check`(엔드포인트 계약 검증)
- [ ] 212. webui 기동 헤드리스 로드 JS 에러 0
- [ ] 213. `test_desktop_contract`/`test_desktop_shapes` 그린
- [ ] 214. agent `pytest` 전체 그린(cov≥70%)
- [ ] 215. 스크린샷 캡처(PR/문서용)
- [ ] 216. 모달 키보드/포커스 동선 점검
- [ ] 217. 기존 테마/디자인 시스템 정합 확인
- [ ] 218. ko/en/ja 카피 검수(어색한 직역 제거)
- [ ] 219. 상태 조회 실패 빈/에러 상태 디자인
- [ ] 220. 구버전 → 신버전 업데이트 안내 결합(설정탭 업데이트 카드)
- [ ] 221. frozen(PyInstaller) 환경에서 캐시/경로 동작 확인(.env 미의존)
- [ ] 222. mock ↔ 실 API shape 일치 재확인(real ⊇ mock)
- [ ] 223. 기존 화면 회귀 스모크(홈/모델/서버/로그/설정)
- [ ] 224. 커밋: contract.js·adapter(mock+real)·webui 라우트 **한 커밋** 규칙 준수
- [ ] 225. UI PR 분리 생성
- [ ] 226. 데모 GIF/영상(선택)
- [ ] 227. 리뷰 반영
- [ ] 228. 차수 7 검증 기록

## 차수 8 — 봇·웹·어드민 표면 (229~252)

- [ ] 229. 슬래시 `/라이선스`(상태 조회 — 구매는 "앱에서" 안내, P5) 설계·구현
- [ ] 230. `CommandLoc` ko/en/ja 등록(`CommandLocJaCoverageTest` 가드)
- [ ] 231. bot i18n 키 추가(상태/만료/이벤트 안내 — ko/en/ja)
- [ ] 232. 서버 관리 프리미엄 central API 경로 게이트 최종 적용 확인(P1/P7 — 차수 4 매핑 소비)
- [ ] 233. 게이트 거부 응답(로케일별 업셀 문구 — 요청자 로케일 우선 규칙)
- [ ] 234. 웹 랜딩 가격 섹션(web i18n 키, ko/en/ja — USD $10 단일)
- [ ] 235. 랜딩 법적 페이지 링크(EULA/환불/사업자 표기)
- [ ] 236. `/install` 가이드 갱신(체험·라이선스 고지)
- [ ] 237. 어드민 대시보드 라이선스 탭(조회/검색)
- [ ] 238. 어드민 수동 부여/회수 UI(ADMIN_GRANT) + 이벤트 열림/닫힘 토글
- [ ] 239. 어드민 billing_event 뷰(환불/분쟁 추적) + 보류 결제 수동 매칭
- [ ] 240. 어드민 권한 가드 회귀 테스트(allow-list 정규식 함정)
- [ ] 241. 봇 게이트 BDD 시나리오
- [ ] 242. `gradlew build` 그린
- [ ] 243. `make i18n-check` 그린
- [ ] 244. 대시보드 스모크(실 브라우저 로드)
- [ ] 245. 관리자 운영 가이드 문서 갱신
- [ ] 246. 커밋
- [ ] 247. `CommandLocJaCoverageTest` 그린
- [ ] 248. OpenAPI 갱신 확인
- [ ] 249. 이벤트 공지 헬퍼(선택 — 길드 공지)
- [ ] 250. 기존 명령 회귀 0 확인
- [ ] 251. PR 정리
- [ ] 252. 차수 8 검증 기록

## 차수 9 — 전환 퍼널 계측(central 집계만, P9) (253~256)

- [ ] 253. 퍼널 지표 정의: 체험시작/만료/checkout 생성/구매완료/이벤트등록 — 전부 기존 서버 데이터로 산출
- [ ] 254. 집계 쿼리/뷰 구현(user_license·billing_event 기반, 앱 텔레메트리 무확장)
- [ ] 255. 어드민 대시보드 퍼널 위젯(선택) + 테스트
- [ ] 256. 차수 9 검증 기록

## 차수 10 — 니아 호감도 시스템(XP/리더보드 교체, P16) (257~276)

- [ ] 257. 호감도 도메인 설계: 유저 단위 점수(사용 성공마다 증가), 단계 곡선(예: 낯섦→알아감→친근→단짝), 감쇠 없음·비교 없음(리더보드 폐지 취지)
- [ ] 258. 기존 게이미피케이션 제거 범위 식별: AiLevelService(XP_PER_ASK_SUCCESS)·길드AI 레벨·/level·리더보드 노출면 전수 조사
- [ ] 259. Flyway V47 `user_affinity`(user_id UNIQUE, score, stage, last_interaction_at) + 기존 XP 데이터 처분(환산 또는 폐기) 결정 반영
- [ ] 260. 호감도 적립 훅: UsageService.recordSuccess 연계(REQUIRES_NEW 독립 트랜잭션 — 기존 AiLevelService 패턴 유지, best-effort)
- [ ] 261. 단계 전이 규칙 + 단계별 니아 반응 문구 설계(ko/en/ja)
- [ ] 262. `/level` → `/니아`(호감도 확인) 교체 또는 제거 — CommandLoc ko/en/ja 갱신
- [ ] 263. `/contributions` 은 기여 내역 조회로 유지하되 순위/비교 요소 제거(이벤트 자격 확인 용도 보존)
- [ ] 264. 단계 상승 시 디스코드 응답에 니아 반응 1회 표시(스팸 금지 — 레벨업 순간만)
- [ ] 265. 니아 페르소나(NexaIdentity)와 호감도 단계 연동 검토: 단계별 톤 힌트 주입(선택·가드레일 유지)
- [ ] 266. 데스크톱 앱 호감도 표시(홈 카드 소형 위젯 — 선택)
- [ ] 267. 길드AI 레벨/XP 노출면 제거(대시보드·명령 응답·도움말)
- [ ] 268. i18n 키 추가(bot/desktop, ko/en/ja) + `make i18n-gen`
- [ ] 269. 단위 테스트(적립/단계 전이/동시성)
- [ ] 270. BDD 시나리오(사용→호감도 증가→단계 상승)
- [ ] 271. ArchUnit/도메인 순수성 통과
- [ ] 272. `gradlew build` 그린
- [ ] 273. 게이미피케이션 제거 회귀: 레벨/XP/리더보드 잔존 노출 0 확인
- [ ] 274. 어드민 통계 영향 확인(레벨 의존 위젯 정리)
- [ ] 275. 커밋/PR
- [ ] 276. 차수 10 검증 기록

## 차수 11 — E2E·보안·적대 검증 (277~292)

- [ ] 277. e2e: 신규 유저 가입→체험 시작→유료(서버관리 프리미엄) 사용
- [ ] 278. e2e: 만료 강등(Clock 주입 시간 이동) → soft-lock 확인
- [ ] 279. e2e: 샌드박스 구매 → 활성화 전파(폴링 토스트까지)
- [ ] 280. e2e: 환불 → 즉시 회수 + 체험/이벤트 재부여 거부
- [ ] 281. e2e: 이벤트 신청(기여 1건 자격) → 평생 무료 유지(이벤트 닫은 후에도)
- [ ] 282. e2e: 구버전 에이전트 호환(무료 경로 무영향)
- [ ] 283. 적대: 로컬 시계 조작·재설치 체험 리셋 불가(central 기준 확인)
- [ ] 284. 적대: webhook 위조(서명 부재/불일치/리플레이) + custom_data 변조(타 유저 ID 주입) 차단
- [ ] 285. 적대: entitlement 토큰 위조/만료 재사용 거부 + 64bit ID 정밀도 전 구간
- [ ] 286. 적대: 게이트 우회(앱 수정 가정, central API 직접 호출) — central 판정으로 차단 확인
- [ ] 287. 부하: webhook rate limit + 보안 셀프 리뷰(OWASP·시크릿·로그 스크럽·커밋 이력 시크릿 스캔)
- [ ] 288. central 전체 빌드 + `-PdockerTests` 통합 그린
- [ ] 289. agent `ruff`+`mypy`+`pytest` + `make contract`+`desktop-check`+`i18n-check`+`packaging-check`+links 그린
- [ ] 290. 적대 발견 → 수정 → 재검 루프 종결 + 잔여 리스크 문서화(수용 사유 포함)
- [ ] 291. 별도 코드 리뷰 패스(작성자 외 리뷰어 페르소나)
- [ ] 292. 차수 11 검증 기록

## 차수 12 — 릴리스·운영·실계정 전환 (293~300)

- [ ] 293. PR 체인 머지 순서 실행: 차수 1 → 도메인 → 결제 → 이벤트 → UI → 표면 → 호감도(각 CI 그린 후)
- [ ] 294. central `CHANGELOG.md` 작성 → 머지 → 자동배포 헬스 + 프로덕션 스모크 → `central-v*` 릴리스
- [ ] 295. agent CHANGELOG → autorelease(`agent-v*`) → 자산 다운로드/설치 검증(자산명 가드) → 인앱 업데이트 경로 확인
- [ ] 296. `OPERATIONS.md`/`RUNBOOK.md` 결제·라이선스 절 추가 + webhook 실패·미매칭 모니터링/알림
- [ ] 297. 런칭 이벤트 오픈(공지 발행 — "기여 1건이면 평생 무료")
- [-] 298. 사업자 등록(간이과세+통신판매업 신고) → Paddle 실계정 신청·심사(유저 작업, P17)
- [ ] 299. 실계정 전환: ENV 교체 → 스모크 결제 → 환불 리허설(P17 완료 후)
- [ ] 300. `BILLING_SUBSCRIPTION_TODO.md` 종결·메모리 갱신·최종 회고/런칭 보고

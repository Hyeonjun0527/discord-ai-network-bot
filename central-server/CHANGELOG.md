# Changelog — central-server (Provider Pool)

커뮤니티 로컬 AI Provider Pool 중앙 서버의 변경 이력. [Semantic Versioning](https://semver.org)
및 [Keep a Changelog](https://keepachangelog.com/) 형식을 따른다. (기존 Python 봇 변경은 루트
`CHANGELOG.md` 참조 — 별도 배포물.)

## [Unreleased]

### Added
- 관리자 콘솔을 `대화 데이터`와 `실행 기록` 두 화면으로 단순화하고, 서버·채널별 NIA 실행에서 모델이 본
  최근 대화와 최종 선택 답변을 함께 확인할 수 있게 했다. 대화 에피소드 검색 결과는 런타임 연결 이후 최대 2개까지
  같은 실행 상세에 표시한다.

### Fixed
- NIA가 응답 의무가 있는 장면에서도 후속 후보 평가에서 침묵으로 뒤집히던 문제를 막고, 현재 메시지를 응답 대상으로
  끝까지 보존해 새 질문 대신 이전 질문에 뒤늦게 답하지 않도록 수정했다.
- 외부 검증이 필요한 사실은 AI 판단 결과에 따라 운영 SearXNG 근거를 발화 생성에 연결하고, 근거를 얻지 못했을 때
  경험이나 세부 사실을 지어내지 않도록 수정했다.

### Security
- central-server 운영 시크릿을 host `.env`/평문 컨테이너 환경변수에서 GitHub `production` Environment와
  Docker secret files(Spring configtree/Postgres `_FILE`)로 이전하고, 배포·정기 감사에서 드리프트를 차단한다.

### Changed (구조 리팩터 — 동작·계약 보존, 사용자 노출 변화 없음)
- **도메인-우선 헥사고날 재편**: 기술레이어 패키지(persistence/network/dashboard/discord/web/health/
  policy/usage/alert/domain)를 11개 비즈니스 도메인(provider·routing·quota·requestlog·guild·channelai·
  knowledge·onboarding·multiresponse·preset·ainetwork)의 `domain/application/adapter(inbound·outbound)`
  구조로 전면 이관. 횡단 레이어 `shared`(공유 커널)·`global`(security/i18n/health/audit)·`platform.discord`·
  `relay`. `Entities.kt`/`Repositories.kt` god-file 해체(엔티티 도메인별 분산). ArchUnit 가드를 도메인-격리·
  레이어방향·영속위치로 재설계.
- **컨트롤러 10개 lean화**: 인라인 응답 조립·집계·audience redaction을 application/`adapter.inbound.web.dto`로
  이관(JSON 계약·마스킹 1바이트 보존, 엔티티 의존 0).
- **god-class 행위 분해**: `CommandService`(1816→481, 명령군 핸들러 10) + 대형 application 서비스 다수 +
  `ProviderRouter`(HaloGfScoreModel 추출, 수식 불변) + `DiscordBot`(JDA 렌더러/인터랙션/settings-wizard 분리).
  트랜잭션/동시성 불변식(@Transactional self-invocation·PESSIMISTIC_WRITE·REQUIRES_NEW·B1 온보딩) 보존.
- 보안 정규식 SSOT(`shared.ContentSafety`) 통합(바이트 동일).

## [0.1.0] - 2026-05-30

첫 정식 릴리스. ROADMAP_LAUNCH_300 의 294/300(검증 가능 항목 전부) 완료.

### Added
- Provider Pool 코어: 리버스 터널 WS 릴레이, 세션 상태머신, 공정성 라우팅(weigh→filter→score→fallback)
- Discord(JDA) 슬래시 명령: 유저(`/ask` `/models` `/catalog` `/my-usage` `/privacy` `/help`),
  프로바이더(`/provider-*`), 관리자(`/fairness` `/providers` `/approve-provider` `/llm-block` 등)
- 관리자 명령 권한 게이트(DefaultMemberPermissions — 비관리자 UI 숨김)
- 정책/쿼터/차단/속도제한, 일일 사용량(UTC 자정), 기여 리더보드
- Provider Agent(Python): Ollama 연결, 자동 모델 감지, self-test, 패키징(Docker/PyInstaller/systemd)
- 관측성: Micrometer/Prometheus, Grafana 대시보드, 헬스 인디케이터, request-id(MDC) 로깅,
  로그 회전, 메트릭 API(`/api/metrics/pool`), 운영 알림(Notifier + 풀 헬스 모니터, Discord 웹훅)
- 크로스언어 와이어 컨트랙트 테스트(공유 `wire-fixtures.json`)
- 배포: Dockerfile + docker-compose(Postgres+Flyway), 운영 런북/백업 정책

### Notes
- `CENTRAL_DEV_ENABLED` 는 운영에서 반드시 `false`(개발용 우회 엔드포인트 비활성).
- 외부 자원 의존 항목(코드서명, 실배포 시크릿, SaaS 연동, 베타 모집)은 산출물만 준비됨.

[Unreleased]: https://example.com/compare

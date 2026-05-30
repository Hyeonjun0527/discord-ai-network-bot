# Changelog — central-server (Provider Pool)

커뮤니티 로컬 AI Provider Pool 중앙 서버의 변경 이력. [Semantic Versioning](https://semver.org)
및 [Keep a Changelog](https://keepachangelog.com/) 형식을 따른다. (기존 Python 봇 변경은 루트
`CHANGELOG.md` 참조 — 별도 배포물.)

## [Unreleased]

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

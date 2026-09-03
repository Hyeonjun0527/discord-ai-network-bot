# 기존 Python 봇 → central-server 단일화 (LAUNCH 차수 18)

> **결정 변경(2026-05-30): "공존" → "폐기·단일화".** 기존 단일 Python 봇(`src/discord_assistant/`)을
> **저장소에서 제거**하고 central-server(Provider Pool)로 단일화했다. 아래 §1~§5 의 공존 분석은
> 그 과정의 히스토리(historical record)로 남긴다. 현재 유효한 결정은 이 박스다.
>
> **제거 범위**: `src/discord_assistant/`·루트 `tests/`·`dashboard/`·봇 전용 루트 설정
> (pyproject/Dockerfile/compose*/CHANGELOG/SECURITY/CONTRIBUTING/ROADMAP*/README_EN 등)·봇 CI 워크플로
> (ci/deploy/auto-release/release/docs-drift/ssot-check)·봇 스크립트·봇 docs(ARCHITECTURE/ROLLBACK 등).
> **유지**: `central-server/`·`provider-agent/`·`specs/`·Provider Pool 문서·공유 인프라(self-hosted 러너,
> ghcr-cleanup→central 단일).
>
> 운영 중이던 인스턴스는 코드 제거와 무관하게 계속 떠 있으므로, 실제 종료는 호스트에서
> 별도로 수행한다(deploy.yml 제거로 재배포는 더 이상 일어나지 않음).

---

(이하 히스토리) 기존 `src/discord_assistant/`(discord.py, v0.3.2 배포 중)와 신규 `central-server`(Kotlin,
Provider Pool)의 관계·이관 전략.

## 1. 기존 Python 봇 기능 인벤토리 (269)
| 기능 | 명령 | central 대응 |
|---|---|---|
| 채널 요약 | `/summarize` `/pin-summary` `/summarize-channels` `/digest` | ❌ 없음(요약 특화) |
| 맥락 Q&A | `/ask` | ⚠️ central `/ask` 는 Provider Pool 라우팅(성격 다름) |
| 자유 대화 | `/chat` | ❌ |
| 번역 | `/translate` | ❌ |
| 검색 | `/search` | ❌ |
| 내보내기 | `/export` | ❌ |
| 알림/통계/설정 | `/remind` `/stats` `/usage` `/settings` `/config` | 부분(정책은 central 에 별도) |
| 멀티 프로바이더 LLM | Ollama/OpenAI/Anthropic/Gemini 어댑터 | central 은 Provider Pool(유저 PC LLM) |

## 2. 처리 결정 (270)
**공존(coexist)을 채택.** 두 봇은 **다른 문제**를 푼다:
- Python 봇 = *호스트의 LLM* 으로 요약/번역/Q&A 등 **콘텐츠 기능**.
- central = *커뮤니티 유저들의 PC LLM* 을 모아 분배하는 **Provider Pool 인프라**.

→ 당장 이관/폐기하지 않는다. 서로 보완. 명령 네임스페이스만 충돌 방지.

## 3. 기능 패리티 매트릭스 (271)
| | Python 봇 | central |
|---|---|---|
| `/ask` | 호스트 Ollama 맥락 Q&A | 커뮤니티 풀 라우팅 |
| 요약/번역/검색 | ✅ | ❌(이관 대상 아님) |
| Provider Pool | ❌ | ✅ |
| 서버 정책/승인/공정성 | ❌ | ✅ |

## 4. 충돌·공존 규칙
- **명령 중복**: `/ask` 가 양쪽에 있으면 한 서버에 **봇을 하나만** 두거나, central 의 `/ask` 를
  `/pool-ask` 로 분리(설정). 권장: 서버별로 둘 중 하나만 초대.
- 두 봇은 **별도 배포·별도 DB**(Python=SQLite, central=Postgres). 데이터 마이그레이션 불필요.
- 단계적: ① central 베타(별도 서버) → ② 안정화 후 같은 서버에 `/pool-ask` 로 통합 검토.

## 5. 이관 항목별 결정 (272~281)

**대전제: §2 의 "공존(coexist)" 결정에 따라 *데이터/트래픽 이관은 수행하지 않는다*.** 두 봇은
별도 배포·별도 DB 로 독립 운영된다. 아래는 각 항목의 구체적 처리.

### 272. 요약(`/summarize`) 흡수 여부
- **결정: 흡수하지 않음(현 단계).** 요약은 *호스트 LLM* 기반 콘텐츠 기능으로 Python 봇이 계속 담당.
  central 은 Provider Pool 인프라에 집중. 베타 후 수요가 크면 별도 차수로 재평가.

### 273. Q&A(`/ask`) 정합
- 두 봇 모두 `/ask` 존재(성격 다름: 호스트 맥락 Q&A vs 풀 라우팅). **같은 서버에 둘 다 초대하지 않는다**가
  1차 규칙. 통합이 필요하면 central 을 `/pool-ask` 로 분리(설정 가능)하여 충돌 제거.

### 274. 번역/리마인드 등 부가 기능
- **Python 봇 유지.** central 이관 대상 아님(콘텐츠/유틸 기능). 네임스페이스 충돌도 없음(central 미보유).

### 275. 명령 충돌/네임스페이스
- 충돌 가능 명령은 사실상 `/ask` 뿐. 규칙: **서버당 한 봇만 초대**(권장) 또는 central `/pool-ask` 분리.
- 관리자 명령은 `llm-*` 프리픽스로 묶여 있어 Python 봇 명령과 겹치지 않음.

### 276. 데이터 마이그레이션(SQLite → 신 구조)
- **불필요.** Python 봇 SQLite 데이터(요약/대화 기록)는 central 의 도메인(프로바이더/정책/사용량)과
  무관. 스키마 호환점이 없고, 이전할 의미 있는 공유 데이터가 없다.

### 277. 단계적 컷오버
- 트래픽 이동 개념이 없음(공존). 대신 **도입 절차**: ① central 을 *별도 베타 서버*에 단독 초대 →
  ② 안정화 후 운영 서버에 추가(필요 시 `/pool-ask` 로 분리) → ③ 서버 관리자가 봇 구성 선택.

### 278. 기존 배포(`deploy.yml`) 영향
- **영향 없음.** `deploy.yml`(Python)은 `src/** scripts/** pyproject.toml ...` paths 로 트리거.
  central 배포는 `central-deploy.yml`이 전용 AMD64 CI에서 이미지를 만든 뒤 전용 AMD64 운영 VM에
  배포함. **두 체인은 트리거 경로가 겹치지 않아**
  한쪽 변경이 다른 쪽을 발화시키지 않음. 별도 이미지·별도 호스트 프로세스.

### 279. 폐기 대상 코드/문서
- 폐기 대상: `docs/ROADMAP_REMOTE_AGENT_DEPRECATED.md`(이미 *명시적 폐기* 처리, 674 Python 중앙안).
- 그 외 Python 봇 코드는 **현역**이므로 폐기하지 않음. 신규 중앙 로직은 전부 `central-server/`.

### 280. 이관 회귀 테스트
- 이관이 없으므로 "이관 회귀"는 N/A. 대신 **공존 회귀 가드**: 두 워크플로의 paths 필터가 겹치지
  않음(서로의 변경이 상대 파이프라인을 깨지 않음)을 유지. central 자체 회귀는 Gradle test + e2e.

### 281. 롤백 계획
- central 도입 실패 시 **그냥 봇을 서버에서 제거**하면 끝(데이터 이전이 없어 롤백이 자명).
- Python 봇은 영향받지 않으므로 별도 롤백 불필요. central 이미지 롤백은 `docs/RUNBOOK.md` 참조.

## 6. 운영 메모
- 기존 `deploy.yml`(Python) 과 `central-deploy.yml` 은 독립이라 상호 영향 없음.
- 본 결정들은 *베타 피드백*에 따라 차수 19 이후 재평가 가능(특히 272 요약 흡수).

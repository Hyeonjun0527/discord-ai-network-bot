# 기존 Python 봇 ↔ central-server 이관 분석 (LAUNCH 차수 18)

기존 `src/discord_assistant/`(discord.py, v0.3.2 배포 중)와 신규 `central-server`(Kotlin,
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

## 5. 후속(이 문서 범위 밖)
- 요약 등 콘텐츠 기능을 central 으로 흡수할지(차수 18 잔여 272~281)는 베타 피드백 후 결정.
- 기존 `deploy.yml`(Python) 과 `central-server-deploy.yml` 은 독립이라 상호 영향 없음.

# 원본 기획 브리프 — 커뮤니티 로컬 AI Provider Pool

> 5개 명세서(requirements/domain-model/screens/navigation/api)의 **정식 출처**.
> 명세서는 이 브리프를 권위 있는 입력으로 삼아 상세화한다. 충돌 시 이 브리프 + README 규약 우선.

## 0. 한 줄 정의
Discord 서버 구성원들이 각자 감당 가능한 범위 안에서 자신의 로컬 LLM 자원을 커뮤니티에
제공하고, 중앙 봇이 요청자의 권한·요청의 무게·프로바이더의 상태와 기여 한도를 보고 적절한
로컬 AI에게 요청을 분배하는 커뮤니티형 AI 협동 시스템.

- AI 모델 판매 서비스가 아니다. 프로바이더가 돈을 버는 구조가 아니다.
- 프로바이더는 "내가 이 커뮤니티를 이 정도까지 도울 수 있다"는 기여 범위를 등록하는 사람이다.

## 1. 핵심 철학
- 만들 것: 커뮤니티 안 여러 사람의 로컬 AI 자원을 모아 서버 구성원이 함께 쓰게 한다.
- 예: A(고성능 데스크탑·큰 모델), B(노트북·작은 모델), C(밤에만 PC), D(관리자 요청만), E(짧은 질문만)
  → 모두 하나의 Provider Pool 로 묶는다.
- 만들지 말 것: 판매자·구매자·가격표·수수료·정산·프로바이더 수익·모델 마켓플레이스.
- 중요한 것: 기여 가능 범위 · 사용 허용 범위 · 요청 처리 한도 · 프로바이더 보호 · 공정한 분배.

## 2. 핵심 용어
- **Discord 서버**: 하나의 커뮤니티 단위. `guild_id` 로 식별.
- **일반 유저**: AI에게 질문(`/ask`)하는 사람. 프로바이더 PC 직접 접근 불가, 오직 봇에게만 요청.
- **프로바이더**: 자기 PC 로컬 LLM 을 커뮤니티에 일부 제공하는 사람. 정하는 것은 가격이 아니라:
  제공 모델 / 받을 요청 종류 / 누구 요청까지 / 하루 몇 번 / 동시 몇 개 / 어느 채널 / 어느 역할 / 언제 일시정지.
- **Provider Pool**: 한 서버에 연결된 여러 프로바이더의 집합. `guild_id → provider_pool[]`.
- **Provider Agent**: 프로바이더 PC의 작은 프로그램. ①중앙 서버로 outbound WebSocket 연결 ②토큰 인증
  ③로컬 Ollama 상태/모델 보고 ④허용된 요청만 수신 ⑤localhost Ollama 호출 ⑥결과 회신.
  외부에서 직접 접속받지 않고, Ollama 포트도 외부 비공개.
- **중앙 봇/중앙 서버**: Discord 요청 수신·정책 확인·프로바이더 선택. 초기엔 봇과 WS 릴레이가 한 프로세스 가능.
  역할: 요청 수신·서버 정책·유저 권한·요청 무게·Pool 조회·상태 확인·기여 한도·분배·타임아웃·사용량 기록·보호.

## 3. 전체 구조
경로는 단 하나: `다른 유저 → Discord 봇 → 중앙 서버 → 이미 연결된 WebSocket → Provider Agent → localhost Ollama`.
`다른 유저 ──X──> 프로바이더 PC Ollama` 직접 접근 불가.

## 4. 모델 부담 수준 (가격 아님, 부담도)
- `light`: 작은 모델, 부담 낮음, 많은 프로바이더 감당. 짧은 질문·간단 요약·가벼운 Q&A.
- `standard`: 일반 로컬 모델, 중간 부담. 코딩 질문·일반 문서 요약.
- `heavy`: 큰 모델, GPU/메모리 부담 큼, 일부 고성능 PC만. 긴 코드 분석·복잡 설계 리뷰.
- `restricted`: 프로바이더가 특별히 제한. 특정 역할·채널·관리자 요청만 허용.

## 5. 프로바이더 기여 정책 (예시)
- A: 제공 llama3:8b/mistral:7b, 허용=서버 멤버 전체, 하루 50회, 동시 1, 요청당 60초, 긴 문서 거절,
  자동보호(배터리 모드/CPU 높음 비활성, 사용자 pause 즉시 중단).
- B: 제공 qwen32b/qwen72b, 허용=qwen32b 신뢰멤버↑·qwen72b 관리자만, 하루 qwen32b 20/qwen72b 5, 동시 1,
  #ai-help 채널만, 긴 코드 분석은 관리자만.
- 핵심: 프로바이더는 모든 요청을 받을 의무가 없다. 감당 가능한 범위 안에서만 돕는다.

## 6. 서버 정책 (예시)
- 허용 채널: #ai-help, #coding-help.
- 일반 멤버: light, 하루 20 / 신뢰 멤버: light+standard, 하루 30 / 관리자: light+standard+heavy.
- 프로바이더 등록: 관리자 승인 필요.
- 프로바이더 선택: 가벼운 요청은 light provider 우선, 무거운 요청만 heavy 로, 특정 provider 쏠림 방지.

## 7. 요청 처리 흐름 (19단계)
1 guild 확인 → 2 channel 확인 → 3 채널 LLM 사용 가능 확인 → 4 user/role 확인 → 5 질문 길이·첨부 확인 →
6 요청 무게 판단 → 7 필요 모델 부담 수준 결정 → 8 Provider Pool 조회 → 9 온라인 필터 → 10 권한 필터 →
11 한도 잔여 필터 → 12 바쁘지 않은 필터 → 13 쏠림 방지 점수 계산 → 14 최종 선택 → 15 WS 전송 →
16 Agent 가 Ollama 호출 → 17 결과 반환 → 18 Discord 출력 → 19 사용량·기여량 기록.

## 8. Provider 선택 규칙
나쁜 방식: 항상 최고 GPU 에게. 좋은 방식: 가벼운 요청 분산, 무거운 요청만 heavy, 최근 많이 처리한 사람 우선순위 ↓,
한도 가까운/busy/offline 제외. 선택 기준 10가지:
1 모델 수준 감당 가능 2 온라인 3 idle 4 요청자 허용 5 채널 허용 6 하루 한도 잔여 7 동시 한도 미초과
8 최근 과다 처리 아님 9 요청 크기 ≤ 제한 10 응답 실패율 낮음.
라우팅 예: 짧은 질문→light 분산 / 긴 코드 분석→standard↑ / 복잡 리뷰→heavy / 권한 부족→다운그레이드 or 거절 /
적절 provider 없음→처리 가능한 커뮤니티 AI 없음 안내.

## 9. 프로바이더 보호 (가장 중요)
- 수동: `/provider-pause` `/provider-resume` `/provider-leave` `/provider-status` `/provider-limit`.
- 자동: CPU/GPU 높음→수신 중단, 메모리 부족→거절, 배터리 모드→자동 pause, 절전→offline,
  네트워크 불안정→temporarily unavailable, 동시 요청 제한, 요청당 최대 시간, 프롬프트 길이 제한, 반복 실패→자동 비활성화.
- Agent 주기 보고 예: `status=online, load=idle, battery=charging, models=[llama3:8b,mistral:7b], max_concurrency=1, remaining_daily_requests=42`.

## 10. 프라이버시
질문 내용이 선택된 프로바이더 PC 로 전송될 수 있음 → 모든 서버에 명확 안내:
"이 서버는 커뮤니티 로컬 AI Provider Pool 을 사용합니다. 질문 내용은 요청을 처리하는 커뮤니티 프로바이더의
PC 로 전송될 수 있습니다. 비밀번호·API 키·개인정보·비공개 문서 등 민감 정보는 입력하지 마세요."
처리 주체 표시 방식(서버 정책):
- A 익명: "커뮤니티 로컬 AI 풀에서 처리됨. 모델 수준: standard".
- B 부분 공개: "커뮤니티 프로바이더가 처리. 모델 수준 / 처리 위치=community local provider".
- C 관리자만 공개(추천): 일반 유저=풀 처리됨, 관리자=어떤 provider 처리했는지 확인 가능.

## 11. 실패 처리
예상 실패: provider 연결 끊김, Ollama 지연, 모델 없음, 메모리 부족, timeout, provider pause 전환.
처리: ①request_id 생성 ②전송 ③timeout 설정 ④실패 시 동일 조건 다른 provider 1회 fallback ⑤fallback 실패→안내
⑥실패 provider 를 temporarily unavailable 로.
안내 예: "현재 이 요청을 처리할 수 있는 커뮤니티 로컬 AI 가 없습니다. 잠시 후 다시 시도하거나 더 가벼운 요청으로."
권한: "이 요청은 heavy 수준이 필요하지만 현재 역할로는 사용할 수 없습니다. 관리자에게 권한 요청 또는 더 짧은 질문."

## 12. 명령어
- 일반 유저: `/ask` `/models` `/my-usage` `/privacy`.
- 관리자: `/llm-settings` `/llm-allow-channel` `/llm-deny-channel` `/llm-role-policy` `/providers`
  `/provider-approve` `/provider-remove`.
- 프로바이더: `/provider-join` `/provider-leave` `/provider-pause` `/provider-resume` `/provider-status`
  `/provider-models` `/provider-limit` `/provider-scope`.

## 13. 상태 모델
- Provider: unregistered / pending / approved / online_idle / online_busy / paused / limited / offline / unhealthy / removed.
- 요청: received / policy_checked / routing / queued / sent_to_provider / running / completed / failed / fallback_running / rejected.

## 14. 데이터 모델 개념
guild / guild_policy(허용채널·역할별 모델수준·승인방식·기본 요청 제한) / provider(등록 유저·소속 guild·승인상태) /
provider_session(연결·WS·heartbeat·online/offline/busy) / provider_capability(제공 모델·부담수준·최대 컨텍스트·예상 속도) /
provider_contribution_policy(모델별 허용 역할·채널·하루 한도·동시 한도·최대 처리 시간·긴 프롬프트 허용) /
request(요청자·guild·channel·메타·필요 수준·선택 provider·상태·실패 사유) /
usage_log(누가 얼마나·어떤 provider 가 얼마나 — 공정성 기록).
핵심: billing/price/seller/payout 를 핵심 모델에 넣지 않는다. 중심: contribution·consent·capacity·availability·fairness.

## 15. 공정성 설계
목표: 특정 provider 쏠림 방지, 무거운 요청은 감당 가능한 사람에게만, 가벼운 요청에 heavy 낭비 금지,
최근 많이 도운 provider 는 쉬게, 한도 낮은 provider 존중.
점수 예: `provider_score = 모델 적합도 + 온라인 + idle + 남은 한도 + 최근 처리량 적을수록 가산 - 최근 실패율 - 현재 부하 - heavy 낭비 패널티`.
원칙: light→light 우선, standard→standard 우선, heavy→heavy 만 후보, heavy 는 light 요청에 기본 미사용(없을 때만 예외).

## 16. 보안 원칙
- 금지: provider PC 직접 접속, 포트 개방, 임의 shell/파일/URL, 중앙 서버 요청 외 처리.
- 허용: 인증된 WS 연결, 허용 LLM 요청 수신, localhost Ollama 호출, 결과 반환, 상태 보고.
- 인증: `/provider-join` 일회용 토큰(짧은 유효·1회 폐기) → Agent 연결 후 provider_session 생성 → 주기 heartbeat.

## 17. 최종 사용자 경험
- 일반 유저: `/ask` → 답변. 내부 provider 는 몰라도 됨. 단 "커뮤니티 로컬 AI 풀 처리" 안내는 명확히.
- 프로바이더: `/provider-join` → Agent 실행 → 모델·한도 설정 → 가능한 만큼 기여 → 언제든 pause/leave.
- 관리자: 봇 초대 → 채널 설정 → 역할별 수준 설정 → provider 승인 → pool 상태 확인 → 문제 provider 제거.

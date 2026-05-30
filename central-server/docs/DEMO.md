# 데모 시나리오 — 다중 프로바이더 Provider Pool

중앙 서버(central-server)가 여러 프로바이더의 로컬 LLM 을 모아 공정하게 분배하는 흐름.

## 준비
1. 중앙 서버 실행: `DISCORD_BOT_TOKEN=... DISCORD_ENABLED=true ./gradlew bootRun`
   (또는 Docker: `docker build -t central-server . && docker run -e DISCORD_BOT_TOKEN=... -e DISCORD_ENABLED=true -p 8080:8080 central-server`)
2. 두 명의 프로바이더(A: 데스크탑/heavy, B: 노트북/light)가 각자 PC 에서 Python 에이전트 준비.

## 시나리오
1. **정책 설정(관리자)**
   - `/llm-allow-channel #ai-help`
   - `/llm-role-policy @신뢰멤버 level:STANDARD limit:30`
2. **프로바이더 등록**
   - A, B 가 `/provider-join` → (수동 승인 서버면) 관리자가 `/provider-approve @A`, `@B` → 토큰 발급
   - A, B 가 발급 토큰으로 에이전트 실행 → 중앙 서버에 WS 연결(인증) → 풀에 등록
3. **요청 분배**
   - 멤버가 `/ask 짧은 질문` → 가벼운 요청 → light 가능한 B 우선(heavy A 낭비 방지)
   - 멤버가 `/ask 긴 코드 분석(첨부)` → 무거운 요청 → heavy A 로
   - 동시에 여러 요청 → 공정성 점수로 분산(최근 많이 처리한 쪽 우선순위 ↓)
4. **보호/실패**
   - A 가 `/provider-pause` → 라우팅 후보에서 제외
   - 한 프로바이더 응답 실패 → 동일 조건 다른 프로바이더로 1회 fallback
   - 모두 오프라인 → "처리 가능한 커뮤니티 로컬 AI 가 없습니다" 안내
5. **프라이버시/기여**
   - `/privacy` → 민감정보 입력 금지 고지
   - `/providers`(관리자) → provider 별 기여량(공정성) 확인
   - `/my-usage` → 내 오늘 사용량

## 확인 포인트
- 가벼운 요청에 heavy 프로바이더가 낭비되지 않는다.
- 무거운 요청은 감당 가능한 프로바이더에게만 간다.
- 특정 프로바이더에 쏠리지 않는다(공정성).
- 프로바이더는 pause/leave 로 언제든 보호받는다.
- 운영 상태: `GET /actuator/health` → `providerPool.activeProviderConnections`.

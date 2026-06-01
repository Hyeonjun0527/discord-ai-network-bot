# Discord UX 에러 처리율 90% 목표 TODO

현재 문제의식:

> 예상치 못한 Discord 권한/Intent/네트워크/Provider 상태 에러가 났을 때 사용자가 이해할 수 있는 안내가 부족하다. 현재 체감 처리율은 약 20%이며, 목표는 운영 전 90% 이상이다.

여기서 “처리율 90%”는 모든 장애를 자동 복구한다는 뜻이 아니다. 사용자가 봤을 때 다음 중 하나가 반드시 일어나는 비율을 뜻한다.

1. 즉시 성공한다.
2. 안전하게 폴백한다.
3. 사용자가 해야 할 일을 명확히 알려준다.
4. 관리자가 해야 할 설정을 명확히 알려준다.
5. 운영자가 로그/알림으로 원인을 찾을 수 있다.

## 1. UX 에러 처리 원칙

- 사용자는 `애플리케이션이 응답하지 않았어요`만 보고 끝나면 안 된다.
- Discord 3초 ACK 제한 때문에 모든 슬래시/버튼/모달은 먼저 defer/reply 해야 한다.
- 권한 부족은 “실패”가 아니라 “설정 필요”로 설명한다.
- Provider 부재/오프라인은 사용자 잘못이 아니므로 불안하지 않게 안내한다.
- 관리자 조치가 필요한 문제는 일반 사용자와 관리자에게 다른 메시지를 보여준다.
- 민감정보/보안 문제는 우회하지 않고 차단한다.

## 2. P0 — 바로 잡아야 하는 UX 실패

- [ ] UX-001. `MESSAGE_CONTENT` intent 미허용 상태에서 봇 전체 gateway 가 죽지 않도록 부팅 전/부팅 실패 감지 UX를 설계한다.
- [ ] UX-002. `@냥시스턴트 질문` 사용 시 Message Content Intent 필요성을 관리자에게 안내하는 도움말/설정 패널을 추가한다.
- [ ] UX-003. 운영 로그에 `DISALLOWED_INTENTS 4014` 발생 시 원인과 조치 링크를 한 줄로 남긴다.
- [ ] UX-004. 배포 smoke test 에 “JDA WebSocket 정상 연결 유지”를 추가한다.
- [ ] UX-005. `/메뉴`, `/질문`, `/도움말`, `/내상태`가 gateway 연결 실패 상태에서 어떤 사용자 경험을 보이는지 재현 테스트한다.
- [ ] UX-006. 모든 slash command 경로가 3초 안에 ACK/defer 하는지 테스트한다.
- [ ] UX-007. Button interaction 도 3초 안에 ACK/defer 하는지 테스트한다.
- [ ] UX-008. Modal submit 도 3초 안에 ACK/defer 하는지 테스트한다.
- [ ] UX-009. Message context command 도 3초 안에 ACK/defer 하는지 테스트한다.
- [ ] UX-010. `Manage Webhooks` 권한이 없으면 채널 AI webhook 실패를 일반 봇 응답으로 폴백하고 관리자 안내를 남긴다.

## 3. P1 — 권한/설정 문제 안내

- [ ] UX-011. 봇 초대 권한 점검 명령 또는 설정 패널 섹션을 만든다.
- [ ] UX-012. View Channel 없음/채널 접근 불가를 감지하고 관리자에게 알려준다.
- [ ] UX-013. Send Messages 없음 시 가능한 interaction 응답으로 권한 문제를 안내한다.
- [ ] UX-014. Embed Links 없음 시 plain text 도움말로 폴백한다.
- [ ] UX-015. Read Message History 없음 시 컨텍스트/멘션 관련 제한을 안내한다.
- [ ] UX-016. Add Reactions 없음 시 성공 리액션 없이 메시지 답변으로 폴백한다.
- [ ] UX-017. Use Application Commands 제한 시 서버 관리자에게 앱 명령 권한 설정을 안내한다.
- [ ] UX-018. 권한 문제 메시지에는 “어디서 켜는지”와 “왜 필요한지”를 같이 쓴다.
- [ ] UX-019. 권한 안내 문구는 `docs/BOT_PERMISSIONS.md`와 같은 용어를 사용한다.
- [ ] UX-020. 관리자 전용 설정 문제는 일반 유저에게 내부 권한 세부값을 과하게 노출하지 않는다.

## 4. P1 — Provider/AI 처리 문제 안내

- [ ] UX-021. 온라인 Provider 0명일 때 질문 사용자가 이해할 수 있는 안내를 표시한다.
- [ ] UX-022. Provider는 온라인이지만 해당 모델/부담 수준 처리 불가일 때 대안을 안내한다.
- [ ] UX-023. Provider timeout 시 “처리 시간이 길어졌어요” + 재시도 가능성을 안내한다.
- [ ] UX-024. Provider 중간 연결 끊김 시 fallback 시도 여부를 명확히 기록한다.
- [ ] UX-025. 사용자 입력/권한/정책 오류는 Provider fallback 하지 않고 즉시 안내한다.
- [ ] UX-026. 프롬프트가 너무 긴 경우 줄이는 방법을 안내한다.
- [ ] UX-027. 민감정보 탐지 시 차단/경고 문구를 일관화한다.
- [ ] UX-028. Rate limit 시 남은 대기 시간을 가능하면 표시한다.
- [ ] UX-029. 같은 질문 중복 제출 차단 시 “방금 접수됨”을 명확히 안내한다.
- [ ] UX-030. 사용자가 취소/삭제한 요청은 조용히 중단하고 로그에는 남긴다.

## 5. P2 — 운영/관측성

- [ ] UX-031. Discord gateway close code 별 운영 runbook 링크를 만든다.
- [ ] UX-032. JDA ready 상태를 actuator health/detail 또는 별도 status 로 노출한다.
- [ ] UX-033. 최근 interaction 실패 원인을 metric 으로 집계한다.
- [ ] UX-034. 권한 부족 실패와 Provider 실패를 다른 metric 으로 분리한다.
- [ ] UX-035. 배포 후 smoke test 에 `/도움말`, `/메뉴`, `/내상태`, `/질문`, `@멘션 질문`을 포함한다.
- [ ] UX-036. smoke test 실패 시 배포 성공으로 간주하지 않는 기준을 만든다.
- [ ] UX-037. Discord API rate limit 발생 시 backoff 로그와 사용자 안내를 분리한다.
- [ ] UX-038. webhook 생성/전송 실패율을 metric 으로 남긴다.
- [ ] UX-039. 운영 알림에는 토큰/프롬프트 원문을 포함하지 않는다.
- [ ] UX-040. 장애 복구 후 사용자에게 재시도 가능한 상태인지 알려준다.

## 6. 90% 완료 기준

아래 조건을 만족하면 UX 에러 처리율 90% 달성으로 본다.

- [ ] P0 10개 완료.
- [ ] P1 20개 중 18개 이상 완료.
- [ ] P2 10개 중 7개 이상 완료.
- [ ] 실제 Discord 운영 서버에서 smoke test 5종 통과:
  - `/메뉴`
  - `/도움말`
  - `/내상태`
  - `/질문`
  - `@냥시스턴트 질문`
- [ ] Message Content Intent OFF 상태를 일부러 만들어도 원인과 조치가 문서/로그/관리자 UX 중 최소 하나에 명확히 드러난다.
- [ ] Manage Webhooks OFF 상태에서도 질문 결과가 사라지지 않고 일반 봇 응답으로 폴백한다.

## 7. 관련 문서

- [봇 권한 명세](BOT_PERMISSIONS.md)
- [Provider 안전 정책](PROVIDER_SAFETY_POLICY.md)
- [에지 케이스 정책](EDGE_CASE_POLICY.md)
- [central-server 운영 가이드](../central-server/docs/OPERATIONS.md)

# 자주 묻는 질문 (FAQ)

커뮤니티 로컬 AI Provider Pool — 사용 가이드 (차수 19 #289).

민감한 우려 사항은 [Provider Pool 민감 질문 30개](./SENSITIVE_QUESTIONS.md)를 함께 참고합니다.

## 일반

**Q. 이 서비스는 뭔가요?**
커뮤니티 멤버들이 자기 PC 의 로컬 LLM(Ollama)을 풀에 기여하고, 다른 유저가 `/ask` 로
그 LLM 들을 공정하게 나눠 쓰는 구조입니다. **금전 거래가 아니며**(판매·결제 없음), 기여·동의·
가용성·공정성이 핵심입니다.

**Q. 내 질문은 어디로 가나요?**
요청을 처리하는 **커뮤니티 프로바이더의 PC** 로 전송됩니다. 비밀번호·API 키·개인정보 등 민감
정보는 입력하지 마세요(`/privacy` 참고).

## 유저

**Q. 어떻게 질문하나요?** — `/ask 질문내용`. 사용 가능한 모델은 `/models`·`/catalog` 로 확인.

**Q. 사용량 제한이 있나요?** — 서버 정책(역할별 일일 한도)·분당 쿨다운이 적용됩니다.
`/my-usage` 로 내 사용량을 봅니다.

**Q. 도움말은?** — `/help` 로 명령 전체를 봅니다.

## 프로바이더(내 PC 를 기여)

**Q. 어떻게 참여하나요?**
1. Ollama 설치 + 모델 받기(`ollama pull <model>`)
2. Discord 에서 `/provider-join` → 관리자 승인 → 토큰 수령
3. 에이전트 실행: `nexa --token <토큰> --relay-url ws://<서버>:8080/agent`
   (설치/패키징은 `provider-agent/packaging/README.md`)

**Q. 포트를 열어야 하나요?** — **아니요.** 에이전트는 아웃바운드 연결만 사용합니다. inbound 포트
개방·Ollama 외부 공개 모두 불필요합니다.

**Q. 언제든 멈출 수 있나요?** — `/provider-pause`·`/provider-resume`·`/provider-leave`.
배터리 방전/고부하 시 자동 보호로 일시정지되기도 합니다.

**Q. 어떤 모델·한도를 제공할지 정할 수 있나요?** — `/provider-models`·`/provider-limit`·
`/provider-scope` 로 모델·일일/동시 한도·허용 범위를 설정합니다.

## 관리자

**Q. 서버에서 어떻게 운영하나요?** — `/provider-approve`·`/provider-remove`(승인/제거),
`/allow-channel`·`/deny-channel`·`/set-role-policy`(정책), `/fairness`·`/providers`(현황),
`/llm-block`·`/llm-unblock`(차단). 관리자 명령은 비관리자에게 보이지 않습니다.

## 문제 해결

**Q. "프로바이더가 없습니다"가 떠요.** — 풀에 온라인 프로바이더가 없을 때입니다. 누군가
`/provider-join` 후 에이전트를 켜야 합니다.

**Q. 에이전트가 연결되지 않아요.** — `--self-test` 로 Ollama 연결을 먼저 확인하고, relay-url·토큰을
점검하세요. 토큰은 일회용이며 만료될 수 있습니다.

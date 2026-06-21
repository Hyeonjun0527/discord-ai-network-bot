# 경계 계약: globalpromptset 정체성 커널 포트

- 작업: NEXA-P01-T015 · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md),
  [ADR 0010 ainetwork·socialmemory 경계](../../adr/0010-ainetwork-socialmemory-boundary.md)
- 근거 기준선: [social-model-overlap.md](../baseline/social-model-overlap.md)
- 관련 계약: [speech-context.md](./speech-context.md),
  [socialmemory-context.md](./socialmemory-context.md)

## 목적

서버 기본 성격(globalpromptset / 니아 기본 페르소나)을 speech에 제공하되, **런타임 관계·기억과
분리**한다. 정체성은 정적, 관계·기억은 동적이다.

## 조회 포트

```
interface IdentityKernelPort {
    fun resolve(guildId): IdentityKernelView   // 서버 기본 성격 + 니아 기본 페르소나(정적)
}
```

- `IdentityKernelView`는 정적 정체성 텍스트의 **읽기 뷰**다(원문 SSOT는 globalpromptset/NexaIdentity).
- speech는 이 뷰 + socialmemory 관계 블록 + 장면을 **조립**해 프롬프트를 만든다.

## 금지 (acceptance — 원문 복제 금지)

- 정체성 프롬프트 **원문**이 speech/participation/socialmemory에 복제 저장되지 않는다 — 포트로
  조회만 한다.
- socialmemory는 정체성 텍스트(`NIA_DEFAULT_PERSONA`, globalpromptset 행)를 변경하지 않는다
  (ADR 0010 REUSE: 정적 정체성).
- 동적 관계 문구는 socialmemory/speech가 소유하고 정체성 커널에 쓰지 않는다.

## 불변식

1. 정체성 SSOT는 하나다(globalpromptset/NexaIdentity). 다른 모듈은 읽기 뷰만 본다.
2. speech 프롬프트에는 정체성 블록과 관계 블록이 각각 한 번씩만 들어간다(이중 주입 금지, ADR 0010).

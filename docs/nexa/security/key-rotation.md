# 메시지 암호화 키 회전 (key rotation)

- 작업: NEXA-P17-T005 (`human_gate: true`, security) · 상위: [ADR 0012](../../adr/0012-message-content-encryption.md)
- 근거: [threat-model.md](./threat-model.md)(자산: at-rest 키, 잔여 위험)
- 구현: [`KeyRing`](../../../central-server/src/main/kotlin/com/discordassistant/central/global/crypto/KeyRing.kt),
  [`VersionedFieldCrypto`](../../../central-server/src/main/kotlin/com/discordassistant/central/global/crypto/VersionedFieldCrypto.kt)

## 목적

보관 데이터(시스템 프롬프트·전역 프롬프트·RAG 프리뷰 등 at-rest 암호화 대상)의 암호화 키를
**무중단 회전**할 수 있게 한다. 새 키로 신규 데이터를 암호화하면서, **구키로 암호화된 과거
ciphertext 도 계속 복호**할 수 있어야 한다. 구키는 모든 활성 ciphertext 가 재암호화된 뒤에만
폐기한다.

> 본 task 는 **메커니즘과 테스트만** 제공한다. 운영 환경의 실제 키 교체·배포는 범위 밖이며 수행하지 않는다.

## 설계

### 키 버전(key version)

- 각 키는 정수 버전 id 를 가진다. ciphertext 접두사가 버전을 담는다: `enc2:<keyVersion>:<base64(iv‖ct)>`.
- **active 키**: 신규 암호화에 쓰는 단 하나의 키.
- **retired 키**: 더는 암호화에 쓰지 않지만 과거 ciphertext 복호를 위해 keyring 에 남아 있는 키.
- **revoked 키**: keyring 에서 제거된(폐기된) 키. 이 버전으로 암호화된 ciphertext 는 복호 불가.

레거시 `enc1:` 접두사(버전 없음)는 keyring 의 **레거시 버전 슬롯**으로 매핑해 그대로 복호한다
([FieldCrypto](../../../central-server/src/main/kotlin/com/discordassistant/central/global/crypto/FieldCrypto.kt)
무변경 — 새 코드만 추가).

### lazy re-encryption(지연 재암호화)

- 읽기 시 ciphertext 의 키 버전이 active 가 아니면, 평문을 active 키로 다시 암호화해 돌려준다(호출자가
  재저장하면 점진 마이그레이션).
- 일괄 재암호화 배치는 같은 메커니즘을 반복 적용한다. 강제 전면 재암호화 없이도 자연 읽기로 수렴한다.

### revoked 키 처리

- revoked 버전 ciphertext 복호 시 명확한 예외([`RevokedKeyException`])를 던진다(조용한 평문 폴백 금지 —
  fail-closed). 폐기 전 재암호화 누락을 운영이 즉시 감지한다.

## 폐기 안전 절차 (acceptance)

구키를 폐기(revoke)하기 전, **모든 활성 ciphertext 가 active 키로 접근 가능한지** 검증해야 한다.

1. 새 키를 active 로 추가(이전 active → retired).
2. lazy re-encryption + 배치로 retired 키 ciphertext 를 active 로 재암호화.
3. retired 키로 암호화된 ciphertext 가 0 임을 검증([`canRevoke`] — 잔존 시 폐기 거부).
4. 검증 통과 시에만 retired 키를 revoke.

acceptance: **구키 폐기 전 모든 활성 ciphertext 의 접근 가능성을 검증한다** — `VersionedFieldCryptoTest`
가 (a) 구키 암호문이 회전 후에도 복호 가능, (b) 재암호화 후 active 로 읽힘, (c) 미재암호화 잔존 시
`canRevoke=false`, (d) revoke 후 해당 버전 복호가 `RevokedKeyException` 으로 실패함을 증명한다.

## 불변식

1. active 키는 정확히 하나다.
2. retired 키로 암호화된 활성 ciphertext 가 남아 있으면 그 키를 revoke 할 수 없다.
3. revoked 키 ciphertext 복호는 조용히 평문으로 폴백하지 않고 예외로 실패한다(fail-closed).
4. 키 자체(raw bytes)는 로그·예외 메시지·payload 에 절대 노출하지 않는다(버전 번호만).

# ADR 0017: NIA persistent closed-loop social policy

- 상태(Status): 승인됨 (Accepted)
- 날짜(Date): 2026-07-17
- 결정자(Deciders): Hyeonjun0527
- 대체 범위: [ADR 0016](./0016-nia-raw-fewshot-judge.md)의 "SPEAK 뒤에는 행동을 다시 선택하지 않는다" 결정
- 관련: [participation runtime](../nexa/architecture/participation-runtime.md),
  [speech context](../nexa/architecture/speech-context.md),
  [actionruntime context](../nexa/architecture/actionruntime-context.md)

## 맥락 (Context)

raw window와 single judge만으로는 니아가 최근 문장에 반응할 수는 있어도, 이전 판단·공통 기반·열린 약속·자기
행동에 대한 사람 반응을 다음 행동에 안정적으로 반영하지 못한다. 또한 실제 문구를 보기 전에 `SPEAK`를 확정하면
짧은 리액션이나 침묵이 더 자연스러운 장면에서도 반복 안내를 생성하기 쉽다. 실행되지 않는 `WAIT`, `REACT`,
다중 버블 예약은 행동 계약이 아니라 사실상 dead path가 된다.

## 결정 (Decision)

니아의 실제 경로를 다음 폐루프로 운영한다.

```text
Discord 사건
→ focus thread별 영속 장면 믿음 갱신
→ single judge의 잠정 행동과 근거 있는 믿음 갱신
→ 실제 문구를 포함한 SEND + REACT + IGNORE 완전 행동 후보
→ 예상 결과 비교와 최종 행동 선택
→ consent/safety + 각 Discord 호출 직전 원자적 실행 permit
→ 취소 가능한 실행
→ 실제 사람 반응 귀속
→ 다음 장면 믿음과 열린 약속 갱신
```

구체 결정은 다음과 같다.

1. `socialpolicy`는 focus thread별 공통 기반, 경쟁 의도 가설, 최근 니아 행동, 관찰된 결과를 bounded projection으로
   저장한다. 모든 믿음에는 안정된 근거 ref와 confidence 또는 probability가 필요하다.
2. 명백한 장면은 저비용 단일 생성 경로를 쓸 수 있다. 모호하거나 열린 약속이 있는 장면은 여러 SEND 문구와
   `REACT`, `IGNORE`를 완전 행동 후보로 비교한다.
3. 활성 완전 행동 평가기의 malformed·알 수 없는 후보·낮은 confidence는 `IGNORE`로 fail-closed한다.
4. "이야기해볼게", "나중에 설명할게"처럼 요구 행위를 수행하지 않은 미래 예고는 문장 길이와 관계없이
   intent-fulfillment critic이 차단한다. 명시적 약속은 confidence와 함께 `PendingIntent`로 열고, 약속 이행이
   연결된 마지막 SEND action이 실제 완료된 뒤 그 action ID를 증거로 저장할 때만 완료한다.
5. `WAIT`는 typed scheduled action이며, due 시 기존 발화를 보내지 않고 transactional outbox를 통해 최신 장면의
   child judge를 깨운다. 재시작 복구에 필요한 raw routing ID는 field encryption으로만 보존한다. `REACT`는 semantic
   reaction code를 allowlist emoji로 해석해 실제 실행한다.
6. 여러 버블은 하나의 예약 행동이 가진 content plan으로 보존한다. 각 버블 직전에 context version을 다시 확인하고,
   한 버블을 보낸 뒤 장면이 바뀌면 같은 행동의 나머지 버블을 취소한다.
7. 실행 빈도는 후보 생성·예약 카운터가 아니라 각 실제 SEND bubble과 REACT Discord 호출 직전의 채널·전역 원자적
   permit으로 제한한다. Redis 장애는 실행 경계에서 fail-closed한다.
8. 실제 Discord 실행이 성공한 행동만 unresolved interaction으로 열고, reply target을 우선하여 다음 사람 반응을
   귀속한다. 결과는 다음 judge의 장면 상태로 되돌아간다.
9. raw source가 삭제되면 그 evidence ref에 의존한 열린 약속과 interaction outcome을 무효화한다.
10. 이 구조는 온라인 파라미터 파인튜닝을 자동 수행하지 않는다. 우선 감사 가능한 궤적을 축적하고, 별도 인간 평가와
    오프라인 선호학습은 승인된 데이터셋·배포 게이트에서 수행한다.
11. 열린 약속, 경쟁 의도 가설, 주소 지정 충돌, 교정 피드백이 있는 모호한 장면만 judge thinking을 활성화한다.
    일반 장면은 빠른 경로를 유지하며 효과는 운영 로그 기반 ablation으로 보정한다.
12. 장면 projection에는 raw guild/channel ID를 쓰지 않고 keyed pseudonym만 저장한다. Discord 실행에 꼭 필요한
    routing ID와 target message ID는 scheduled action 및 WAIT outbox에서 field encryption을 강제한다.

## 결과 (Consequences)

- 니아는 같은 안내가 이미 공유됐는지, 자신이 약속을 미완료했는지, 직전 행동이 반복 지적을 받았는지를 다음 판단에서 본다.
- 최종 `SPEAK` 여부는 실제 문구와 침묵·리액션의 결과를 함께 본 뒤 정해진다.
- WAIT와 multi-bubble은 새 사건이 들어오면 취소·수정 가능한 시간적 행동이 된다.
- 추가 Cloud 평가 호출과 영속 상태가 생긴다. 그래서 명백한 장면은 단일 경로를 유지하고 상태 크기를 제한한다.
- outcome code 추출은 명시적 피드백 신호에 한정된다. 숨은 감정을 사실처럼 저장하지 않는다.

## 승인 게이트 (Approval Gates)

이 ADR은 코드 구조 승인이다. production deploy, DB migration 적용, Discord LIVE 발화 확대, 축적 궤적을 이용한
학습·모델 승격은 기존 인간 승인 게이트를 그대로 따른다.

# ADR 0003: 커뮤니티 로컬 AI Provider Pool

- 상태(Status): 제안됨 (Proposed)
- 날짜(Date): 2026-05-30
- 결정자(Deciders): Hyeonjun0527
- 관련: [ADR 0002 리버스 터널 에이전트](./0002-remote-agent-byollm.md)를 일반화한다.

## 맥락 (Context)

ADR 0002 는 단일 호스트(개인 모드=내 PC, 공유 모드=방장 PC 하나)를 다뤘다. 그러나 한
서버에는 자원 사정이 제각각인 구성원이 여럿 있다(고성능 데스크탑, 노트북, 밤에만 켜는 PC,
관리자 요청만 돕는 사람, 짧은 질문만 가능한 사람). 이들을 **하나의 Provider Pool** 로 묶어,
중앙 봇이 요청의 무게·요청자 권한·각 프로바이더의 상태와 기여 한도·공정성을 보고 적절한
프로바이더에게 분배하면, 단일 호스트의 한계(병목·단일 장애점)를 넘어선다.

이 시스템은 **AI 모델 판매 서비스가 아니다.** 프로바이더는 돈을 버는 판매자가 아니라
"내가 이 커뮤니티를 이 정도까지 도울 수 있다"는 **기여 범위**를 등록하는 사람이다.

## 결정 (Decision)

ADR 0002 의 리버스 터널 에이전트를 **다중 프로바이더 풀**로 일반화한다.

### 비-목표 (명시적 제외)

판매자/구매자/가격표/수수료/정산/프로바이더 수익/모델 마켓플레이스. 따라서 핵심 데이터
모델에 `billing`·`price`·`seller`·`payout` 개념을 넣지 않는다. 중심 개념은
**기여(contribution)·동의(consent)·수용량(capacity)·가용성(availability)·공정성(fairness)** 이다.

### 구성요소 (ADR 0002 대비 확장)

- **Provider Pool**: `guild_id → provider[]`. 단일 호스트 매핑을 다대일로 확장.
- **Provider / ProviderSession / ProviderCapability / ProviderContributionPolicy**: 각 프로바이더의
  등록·연결·제공능력·기여 한도를 분리해 모델링.
- **GuildPolicy / RolePolicy / AllowedChannel**: 서버 차원의 채널·역할별 허용 모델 부담 수준·승인 방식.
- **Router**: 요청 무게 → 필요 모델 부담 수준 결정 → 후보 필터(10단계) → 공정성 점수 → 최종 선택.
- **모델 부담 수준(ModelBurdenLevel)**: `light/standard/heavy/restricted` — 가격이 아니라 처리 부담도.

### 라우팅 결정 (요약)

1. 서버/채널/역할 정책 통과 확인.
2. 요청 무게(프롬프트 길이·첨부·명령 종류) → 필요 모델 부담 수준 산출.
3. 후보 필터: 부담수준 감당 / 온라인 / idle / 요청자 허용 / 채널 허용 / 한도 잔여 / 동시 한도 /
   과다처리 쿨다운 / 요청 크기 / 실패율.
4. 공정성 점수로 1인 선택. light 요청은 light 우선, heavy 는 heavy 후보 한정, heavy 를 light 요청에
   기본 낭비하지 않음(없을 때만 예외).
5. 실패 시 동일 조건 다른 프로바이더로 **1회 fallback**, 실패 프로바이더는 일시 제외.

### 보호·보안·프라이버시

- **프로바이더 보호**(가장 중요): 수동(`/provider-pause|resume|leave|limit`) + 자동(CPU/GPU/메모리/배터리/
  절전/네트워크/동시·시간·길이 제한/반복 실패 시 자동 비활성화).
- **보안**: Agent 는 outbound 인증 WebSocket 연결만, inbound 포트 미개방, 임의 shell/파일/URL 금지,
  중앙 서버 요청 외 처리 금지. 일회용·단기 토큰 + heartbeat 세션. SSRF 불가.
- **프라이버시**: 질문이 프로바이더 PC 로 전송될 수 있음 → 서버 고지 필수. 처리 주체 표시 모드
  A(익명)/B(부분 공개)/C(관리자만, **기본**).

## 결과 (Consequences)

**장점**

- 단일 호스트의 병목·단일 장애점을 다중 프로바이더로 분산. 서버 규모가 커져도 확장.
- 각자 감당 가능한 만큼만 기여 → 지속 가능한 커뮤니티 자원 풀.
- ADR 0002 의 프로토콜/레지스트리/RemoteAgentClient 를 재사용·확장(레지스트리 키가 단일 owner →
  guild 풀, 라우팅이 단순 조회 → 필터+점수).

**단점 / 트레이드오프**

- 라우터·정책·공정성·보호가 추가돼 복잡도가 크게 증가(요청/프로바이더 상태머신 각 10상태).
- 프라이버시 노출면 확대(여러 타인 PC) → 고지·로그 최소화가 더 중요.
- 공정성·점수 튜닝, 대규모 풀 라우팅 성능이 새 과제.

## 명세 (Specification)

상세 요구사항·도메인 모델·화면·네비게이션·API 는 다음에서 정의한다:
`specs/product-v2/domains/community-provider-pool/` (requirements / domain-model / screens /
navigation / api / TRACEABILITY). 구현 항목은 `docs/ROADMAP_REMOTE_AGENT.md` Phase B(301~674).

## 미해결 질문 (Open Questions)

- 공정성 점수 가중치 기본값과 튜닝 방법.
- 역할 정책 키를 실제 `role_id` 로 둘지, 추상 tier(member/trusted/admin)로 둘지 → **결정: 저장은
  `role_id`, tier 는 표현용 파생값**(README ID 정합 결정 참조).
- 요청 무게 값 집합 → **결정: `light/medium/heavy`** (필요 부담수준 light/standard/heavy 로 매핑).
- 프라이버시 고지 노출 빈도(매 응답 vs 최초/주기).
- 대규모 풀에서의 라우팅 성능 목표치.

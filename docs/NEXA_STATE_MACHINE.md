# NEXA 데스크톱 — 상태머신 & 네비게이션 SSOT

> 화면을 그리기 전에 **상태**를 먼저 정의한다. 모든 화면·전이·버튼 노출은 이 문서의
> 불변식(invariant)을 따른다. 코드 SSOT 는 `prototypes/desktop/state.js`(이 문서와 1:1).
> 이 문서/코드를 어기는 화면은 버그다.

---

## 1. 전역 앱 상태 (AppState)

| 필드 | 의미 |
|------|------|
| `authed` | Discord 인증 여부. **true = 내 계정이 앱에 알려짐**(토큰 보유). |
| `connections` | 연결된 서버 목록. `length ≥ 1` 이면 제공 가능. |
| `stage` | 화면 모드: `onboarding` \| `connect` \| `main`. |
| `view` | `main` 내 사이드바 탭: home/models/servers/local/logs/settings. |
| `connectOrigin` | `connect` 진입 맥락: `onboarding`(첫 인증) \| `main`(서버 추가). |
| `provide` | 홈 제공 상태(ok/paused/error/pending/limited/resource) — connections·런타임에서 파생. |

---

## 2. Stage (화면 모드)와 진입 조건(precondition)

| stage | 의미 | 진입 조건 |
|-------|------|-----------|
| `onboarding` | 첫 설정 마법사(A1~A4) | `authed == false` (미설정 신규) |
| `connect` | Discord 연결/서버 추가 | origin=onboarding → `authed==false` · origin=main → `authed==true` |
| `main` | 사용 중 앱(사이드바 6탭) | **`authed == true`** |

---

## 3. 불변식 (INVARIANTS) — 절대 깨지면 안 된다

- **I1.** `stage == main` ⟹ `authed == true`.
  *(사이드바 홈/모델/서버/...가 보인다 = 이미 인증·최소 1서버 연결 완료)*
- **I2.** **`Discord 로그인` UI 노출 ⟺ `authed == false`** (= `connect` origin=onboarding 의 login 단계에서만).
  *(인증된 상태에서 로그인 버튼이 보이면 I2 위반 = 버그)*
- **I3.** `connect` origin=main ⟹ `authed == true` ⟹ **로그인 단계 생략**(서버 선택부터 + 토큰).
- **I4.** `main` 내 탭 전환(home↔models↔servers…)은 `stage` 불변, `view` 만 변경.
- **I5.** 제공 상태(provide)는 connections/런타임의 파생이지 독립 입력이 아니다.

---

## 4. 전이 (TRANSITIONS)와 가드(guard)

```
onboarding --[연동하기]--------------------> connect(origin=onboarding, sub=login)
connect(onboarding) --[첫 연결 성공]--------> main            [effect: authed := true]
connect(onboarding) --[취소]----------------> onboarding
main --[서버 추가]--------------------------> connect(origin=main, sub=select)   [guard: authed==true]
connect(main) --[추가 완료 / 취소]----------> main
main(view=a) --[사이드바 탭]----------------> main(view=b)    [stage 불변]
```

- `setStage(target)` 는 stage 별로 `authed` 를 보정한다(I1 보장):
  `onboarding → authed=false`, `main → authed=true`, `connect → connectOrigin 에 따름`.
- `enterServerAdd()` 는 **`authed==true` 가드**(I3). false 면 거부.

---

## 5. "Discord 로그인" 노출 결정 트리 (I2 운용)

```
authed == false ?
 ├─ 예  → connect(onboarding).login : [Discord 로그인] 버튼 O + 토큰 토글
 └─ 아니오 → 로그인 절대 노출 금지
              · 서버 추가는 connect(main).select (서버 선택부터) + 참여 토큰 옵션
```

---

## 6. 프로토타입 도구(PROTO) 예외

PROTO 컨트롤러는 **검증 도구**라 가드를 우회해 임의 stage/상태로 점프할 수 있다(개발 편의).
단 **정상 UX 경로의 버튼·전이는 위 가드를 반드시 준수**한다. PROTO 점프 시 불변식 위반은
`console.warn('[SSOT 위반] …')` 로 드러낸다(조용히 넘어가지 않음).

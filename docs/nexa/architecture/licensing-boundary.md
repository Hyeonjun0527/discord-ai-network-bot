# 경계 계약: licensing 기능 게이트

- 작업: NEXA-P01-T018 (`human_gate: true`) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md),
  [ADR 0005 앱 라이선스·수익화](../../adr/0005-app-license-monetization.md)
- 관련 계약: [participation-context.md](./participation-context.md),
  [onboarding-boundary.md](./onboarding-boundary.md)

## 목적

무료·유료 기능 차이가 **사회행동의 안전성·품질을 위험하게 바꾸지 않도록** 허용 게이트의 경계를
정한다. 결제는 편의·확장을 풀 수 있으나, 안전을 깎아선 안 된다.

## 결제와 무관하게 항상 유지 (acceptance — 안전·개인정보·무응답)

| 기능 | 규칙 |
| --- | --- |
| 안전 가드레일(콘텐츠 필터·금지 주제) | 결제 여부와 무관하게 **항상 최대 수준 유지** |
| 개인정보 보호(원문 미저장·redaction·삭제 요청) | 항상 유지. 유료라고 더 많은 원문을 저장하지 않는다 |
| 무응답(IGNORE/WAIT) 권리 | 항상 유지 — 결제가 "더 자주 말하게" 강제하지 않는다 |
| opt-out·동의 철회 | 항상 유지(onboarding 경계와 연계) |

## 게이트 가능한 항목(예시, 품질·안전 불변 전제)

- 더 긴 컨텍스트 윈도우/기억 보존 기간, 고급 모델 선택, 대시보드 고급 분석 등 **편의·확장**.
- 게이트는 participation의 IGNORE/WAIT/REACT/SPEAK **선택 로직 자체를 바꾸지 않는다** — 발화의
  품질 하한과 안전은 동일하고, 유료는 부가 역량만 더한다.

## 위치

- 라이선스 상태는 기존 ADR 0005 체계가 소유한다. participation/speech는 `FeatureGateView`(읽기
  포트)로 "이 기능이 허용되는가"만 조회하고 결제·정산 로직을 포함하지 않는다.

## 불변식

1. 안전·개인정보·무응답 기능은 무료/유료 동일하다(결제로 약화 불가).
2. 라이선스 게이트는 부가 역량만 토글하며 사회행동 결정 로직·안전 하한을 바꾸지 않는다.
3. participation/speech는 `FeatureGateView`만 읽고 결제 상태를 직접 다루지 않는다.

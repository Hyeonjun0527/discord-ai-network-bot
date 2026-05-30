## 개요

이 PR이 무엇을, 왜 바꾸는지 설명해주세요.

## 관련 이슈

<!-- 예: Closes #123 -->
Closes #

## 변경 유형

<!-- Conventional Commits 타입과 일치시켜 주세요. -->
- [ ] `feat` — 새 기능 (minor)
- [ ] `fix` — 버그 수정 (patch)
- [ ] `perf` / `refactor` — 성능·구조 개선 (patch)
- [ ] `docs` — 문서
- [ ] `test` — 테스트
- [ ] `chore` / `ci` — 빌드·도구·CI
- [ ] BREAKING CHANGE 포함 (major)

## 변경 내용

- 

## 검증

PR을 올리기 전 로컬에서 다음을 통과했는지 확인해주세요.

- [ ] central-server 변경 시: `gradlew -p central-server build` (test + ktlint + 커버리지) 통과
- [ ] provider-agent 변경 시: `ruff check src tests` · `mypy src` · `pytest`(커버리지 ≥ 70%) 통과
- [ ] 프로토콜(와이어) 변경 시: 양측 컨트랙트 테스트(`make contract`) 통과
- [ ] 문서 변경 시: `scripts/check_links.py` 통과
- [ ] (해당 시) 새/변경 동작에 대한 테스트 추가

## 추가 정보

스크린샷, 동작 영상, 주의할 점 등이 있으면 적어주세요.

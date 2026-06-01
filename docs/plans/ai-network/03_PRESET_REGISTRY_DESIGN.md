# AI Preset Registry 설계

> 상태: Draft  
> 작성일: 2026-06-01  
> 목적: AI 프리셋을 서버 내부 복사 기능에 가두지 않고, 웹사이트에서 게시·가져오기·수정·삭제·추천·신고할 수 있는 공유 레지스트리로 설계한다.

## 1. 용어 결정

“마켓”이라는 단어는 쓰지 않는다.

권장 용어:

- AI 프리셋 공유
- Preset Registry
- 프리셋 보관함
- 프리셋 가져오기

금지 뉘앙스:

- 판매
- 결제
- 수익화
- 모델 마켓플레이스

## 2. 사용자 경험

관리자는 웹 대시보드에서 다음을 할 수 있어야 한다.

- 내 서버에서 만든 채널 AI 설정을 프리셋으로 저장
- 프리셋을 웹사이트에 게시
- 다른 사람이 공개한 프리셋 검색
- 프리셋 미리보기
- 내 서버/채널로 가져오기
- 가져온 뒤 수정
- 내가 게시한 프리셋 수정
- 내가 게시한 프리셋 삭제/비공개
- 프리셋 따봉 추천
- 부적절한 프리셋 신고

Discord 에서는 1차로 간단한 가져오기/적용만 제공하고, 복잡한 탐색/게시/수정은 웹에서 제공한다.

## 3. Preset 에 포함할 수 있는 것

1차 포함:

- 이름
- 설명
- 목적
- 말투
- 답변 길이
- 답변 포맷
- AI 헌법 규칙
- 안전 문구
- 응답 모드
- 모델 정책 기본값
- 채널 온보딩 문구
- 테스트 질문 예시

1차 제외:

- RAG 지식 원문
- 비공개 system prompt 전문
- Provider 개인 정보
- 서버/채널 ID
- 사용자 ID
- 토큰/비밀값

장기 고려:

- RAG 지식은 “복사”가 아니라 “필요한 지식 슬롯 목록” 또는 “가이드”로 공유한다.
- 예: “README 링크를 등록하세요”, “운영규칙 문서를 업로드하세요” 같은 placeholder.

## 4. Aggregate

### 4.1 AiPreset

서버 내부에서 저장된 프리셋 원본.

필드:

- `id`
- `guildId`
- `name`
- `description`
- `category`
- `sourceChannelAiId`
- `behaviorSnapshot`
- `routingPolicySnapshot`
- `onboardingSnapshot`
- `safetyLevel`
- `visibility` = private/guild/public
- `createdBy`
- `createdAt`
- `updatedAt`

### 4.2 PublishedPreset

웹사이트에 게시된 공개 프리셋.

필드:

- `id`
- `presetId`
- `publisherGuildId`
- `publisherUserId`
- `slug`
- `title`
- `summary`
- `description`
- `category`
- `tags[]`
- `version`
- `status` = published/unlisted/removed/suspended
- `likeCount`
- `importCount`
- `reportCount`
- `publishedAt`
- `updatedAt`

### 4.3 PresetRevision

게시 프리셋의 버전.

필드:

- `id`
- `publishedPresetId`
- `revisionNumber`
- `presetPayload`
- `changeSummary`
- `createdBy`
- `createdAt`

Invariant:

- 게시된 revision 은 immutable.
- 수정은 새 revision 으로만 한다.
- 삭제는 hard delete 보다 removed/suspended 상태를 우선한다.

### 4.4 PresetImport

누가 어떤 서버/채널로 가져왔는지 기록.

필드:

- `id`
- `publishedPresetId`
- `revisionId`
- `targetGuildId`
- `targetChannelId`
- `importedBy`
- `createdChannelAiId`
- `createdBehaviorVersionId`
- `importedAt`

### 4.5 PresetReaction

따봉 추천.

필드:

- `id`
- `publishedPresetId`
- `userId`
- `reactionType` = like
- `createdAt`

Invariant:

- 같은 유저는 같은 프리셋에 like 1개만 가능.
- 추천 취소 가능.

### 4.6 PresetReport

신고.

필드:

- `id`
- `publishedPresetId`
- `reporterUserId`
- `reasonCode`
- `details`
- `status` = open/reviewed/dismissed/actioned
- `createdAt`
- `reviewedBy`
- `reviewedAt`

## 5. DB 테이블 초안

- `ai_preset`
- `published_preset`
- `preset_revision`
- `preset_import`
- `preset_reaction`
- `preset_report`

인덱스:

- `published_preset(status, category, like_count)`
- `published_preset(slug)` unique
- `preset_reaction(published_preset_id, user_id)` unique
- `preset_import(target_guild_id, target_channel_id)`
- `preset_report(published_preset_id, status)`

## 6. 권한

게시 가능:

- 서버 관리자
- AI 관리자 역할
- 프리셋 작성 권한이 있는 사용자

수정/삭제 가능:

- 게시자
- 해당 서버 AI 관리자
- 서비스 운영자

가져오기 가능:

- 서버 관리자
- AI 관리자 역할

추천 가능:

- 로그인한 사용자

신고 가능:

- 로그인한 사용자
- Discord 서버 관리자

## 7. 보안/안전

게시 전 검증:

- 민감정보 포함 여부 검사
- 외부 URL 검사
- system prompt 비공개 필드 제거
- 과도한 fan-out/고부하 정책 제거 또는 경고
- restricted 모델 강제 사용 금지
- 안전 문구 제거 여부 경고

가져오기 전 검증:

- target guild 정책과 충돌 확인
- target channel 권한 확인
- Provider 보호 정책 위반 여부 확인
- high risk preset 은 승인 proposal 로 전환

## 8. 가져오기 흐름

1. 웹에서 프리셋 선택
2. 미리보기
3. 적용할 서버/채널 선택
4. 충돌 검사
5. draft Channel AI 또는 draft behavior version 생성
6. high risk 면 `AiChangeProposal` 생성
7. 승인되면 publish
8. `PresetImport` 기록

중요:

- 가져온 프리셋은 원본과 연결은 기록하지만 자동 업데이트되지는 않는다.
- 원본 프리셋이 수정되어도 내 서버 설정이 몰래 바뀌면 안 된다.

## 9. 품질 지표

- like count
- import count
- report count
- 최근 업데이트
- 카테고리
- compatible mode
- safety level

주의:

- 좋아요 순위가 위험한 프리셋을 밀어올릴 수 있다.
- 추천 알고리즘은 report/safety 신호를 함께 봐야 한다.

## 10. 웹 UI 초안

페이지:

- 프리셋 둘러보기
- 프리셋 상세
- 프리셋 게시
- 내 프리셋
- 가져오기 마법사
- 신고 관리

프리셋 카드:

- 이름
- 설명
- 카테고리
- 태그
- 좋아요
- 가져오기 수
- 안전 레벨
- 마지막 업데이트

## 11. Discord UX 초안

- `/프리셋목록`
- `/프리셋가져오기`
- `/프리셋저장`
- `/프리셋적용`

Discord 는 탐색보다 빠른 적용 중심으로 둔다.

## 12. 감사 포인트

- [ ] 프리셋에 비밀/민감정보가 포함되지 않는가?
- [ ] 게시 revision 이 immutable 인가?
- [ ] 가져오기 후 원본 수정이 내 서버에 자동 반영되지 않는가?
- [ ] high risk preset 이 승인 없이 적용되지 않는가?
- [ ] 좋아요/추천이 남용되지 않는가?
- [ ] 신고된 프리셋을 숨기거나 중단할 수 있는가?
- [ ] 삭제된 프리셋의 기존 import 기록은 보존되는가?

## 13. 구현 순서

1. 내부 `AiPreset` 저장/적용
2. `PresetRevision` immutable 저장
3. 웹 게시/비공개/삭제
4. 프리셋 탐색/상세
5. 가져오기 마법사
6. 좋아요
7. 신고
8. 추천/정렬
9. 운영자 moderation
10. RAG placeholder preset 지원

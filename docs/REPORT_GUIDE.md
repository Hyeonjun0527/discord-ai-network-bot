# 졸업 논문/보고서 작성 가이드

> Discord AI Assistant — 졸업 프로젝트  
> 논문 구조, 핵심 서술 포인트, 인용 형식, 평가 지표를 정리한 작성 가이드입니다.

---

## 1. 논문 구조 개요

학부 졸업 프로젝트 보고서의 표준 구성은 다음과 같습니다.

```
1. 서론 (Introduction)
2. 관련 연구 (Related Work)
3. 시스템 설계 (System Design)
4. 구현 (Implementation)
5. 평가 (Evaluation)
6. 결론 (Conclusion)
참고문헌 (References)
부록 (Appendix) — 선택
```

권장 분량: 20~30 페이지 (A4 기준, 본문 + 참고문헌)

---

## 2. 각 섹션별 핵심 서술 포인트

### 2.1 서론 (Introduction)

**목적**: 연구의 배경, 문제 정의, 해결 방향을 서술합니다.

**포함해야 할 내용**:

1. **연구 배경**
   - Discord의 사용 현황 (MAU, 주요 사용층, 한국 커뮤니티 현황)
   - 채널 메시지 급증으로 인한 정보 과부하 문제
   - AI 어시스턴트의 필요성

2. **문제 정의**
   - 사용자가 채널 대화를 따라가기 위해 소비하는 시간 비용
   - 중요한 결정 사항, 액션 아이템을 놓치는 문제
   - 기존 Discord 봇의 한계 (단순 명령 봇, 고정 응답)

3. **연구 목적 및 범위**
   - 멀티 LLM 제공자를 통합한 Discord AI 어시스턴트 개발
   - 핵심 기능: `/summarize`, `/ask`, `/chat`, `/translate`
   - 한국어 사용자 환경에서의 실용성 검증

4. **논문 구성**
   - 각 섹션의 내용을 1~2문장으로 안내

---

### 2.2 관련 연구 (Related Work)

**목적**: 기존 연구 및 유사 시스템을 검토하여 본 연구의 차별성을 명확히 합니다.

**포함해야 할 내용**:

1. **대화 요약(Conversation Summarization) 연구**
   - 추출적(Extractive) vs. 생성적(Abstractive) 요약 방식
   - 멀티턴 대화 요약의 특수성

2. **LLM 기반 어시스턴트 연구**
   - GPT, Claude 등 대형 언어 모델의 대화 처리 능력
   - Instruction-tuning과 Few-shot prompting

3. **기존 Discord 봇 비교**
   - MEE6, Carl-bot: 규칙 기반 자동화, AI 기능 없음
   - ChatGPT Discord 봇: 단일 제공자, 커스터마이징 제한
   - 본 연구의 차별점: 멀티 제공자 전환, 서버별 설정, 로컬 LLM 지원, 한국어 최적화

4. **로컬 LLM (Ollama) 연구**
   - 엣지 AI, 프라이버시 보존형 추론
   - 오픈소스 모델(LLaMA, Mistral, Gemma)의 한국어 처리 능력

---

### 2.3 시스템 설계 (System Design)

**포함해야 할 내용**:

1. **시스템 아키텍처**
   - 컴포넌트 다이어그램 (그림 삽입 — docs/ARCHITECTURE.md 참고)
   - Discord API <-> Bot <-> LLM 제공자 <-> SQLite 흐름

2. **주요 설계 결정**
   - 추상화 레이어: 제공자별 클라이언트를 동일 인터페이스로 추상화한 이유
   - 암호화 방식 선택: Fernet 대칭키 암호화 선택 근거
   - SQLite 선택: 단일 서버 봇의 특성상 PostgreSQL 없이 충분한 이유
   - 비동기 처리: aiosqlite, aiohttp를 사용하여 Discord 이벤트 루프 블로킹 방지

3. **데이터베이스 스키마**
   - server_config, api_keys, command_logs 테이블 ERD

4. **프롬프트 설계**
   - 요약 프롬프트 구조 (역할 설정 + 지시 + 대화 삽입 + 출력 형식)
   - 한국어 응답 유도 방식

---

### 2.4 구현 (Implementation)

**포함해야 할 내용**:

1. **개발 환경**
   - Python 3.11, 주요 의존성 (discord.py 2.7.1, aiosqlite, cryptography 등)

2. **핵심 기능 구현**
   - /summarize: 메시지 수집 -> 청킹 -> 프롬프트 구성 -> LLM 호출 -> Embed 출력
   - /ask: 컨텍스트 검색 -> RAG 유사 방식 -> 답변 생성
   - /settings: Discord UI 컴포넌트 (View, Button, Modal) 구현
   - API 키 암호화 저장/복호화 흐름

3. **멀티 제공자 전환 구현**
   - 팩토리 패턴 또는 전략 패턴으로 LLM 클라이언트 주입
   - 핵심 함수 코드 예시 삽입

4. **오류 처리**
   - Ollama 연결 실패, API 키 오류, 타임아웃 처리 방식

5. **주요 구현 도전과 해결**
   - Discord 메시지 2000자 제한 처리
   - 긴 채널 메시지의 컨텍스트 압축

---

### 2.5 평가 (Evaluation)

**포함해야 할 내용**:

1. **응답 품질 평가** (docs/quality_checklist.md 기반)
   - 평가 지표: 정확성, 관련성, 간결성, 한국어 품질, 완전성 (각 1~5점)
   - 테스트 시나리오 결과 표

2. **모델별 성능 비교** (docs/model_comparison.md 기반)
   - 응답 시간, 요약 품질, 한국어 품질 비교 표

3. **사용자 만족도 조사** (docs/survey.md 기반)
   - 설문 응답자 수, 기본 통계
   - 사용 편의성, 기능 유용성, 응답 품질 평균 점수
   - NPS 점수

4. **성능 지표**

| 지표 | 목표 | 실제 측정값 |
|------|------|------------|
| /summarize 평균 응답 시간 (클라우드) | 5초 이하 | 측정값 기입 |
| /summarize 평균 응답 시간 (로컬 llama3.2:11b) | 15초 이하 | 측정값 기입 |
| 요약 정확성 평균 (클라우드 모델) | 4.0/5 이상 | 측정값 기입 |
| 한국어 품질 평균 (Claude Sonnet) | 4.5/5 이상 | 측정값 기입 |
| 사용자 만족도 평균 | 3.5/5 이상 | 측정값 기입 |
| NPS | 0 이상 | 측정값 기입 |

5. **한계점**
   - 실시간 스트리밍 미지원으로 인한 체감 대기 시간
   - 로컬 모델의 한국어 품질 한계
   - 이미지/파일 첨부 메시지 처리 불가

---

### 2.6 결론 (Conclusion)

**포함해야 할 내용**:

1. **연구 요약** — 구현한 기능 목록, 핵심 기술적 기여
2. **주요 발견** — 클라우드 vs. 로컬 모델 트레이드오프, 한국어 품질 결과
3. **향후 연구 방향**
   - 실시간 스트리밍 응답 구현
   - 멀티모달 지원 (이미지 분석)
   - 웹 대시보드 완성
   - 자동 요약 스케줄러

---

## 3. 인용 형식

권장 형식: APA 7th Edition 또는 IEEE (지도교수 지침 우선)

### APA 형식 예시

```
Rapptz. (2024). discord.py (Version 2.7.1) [Computer software].
    GitHub. https://github.com/Rapptz/discord.py

Ollama. (2024). Ollama [Computer software]. https://ollama.com

OpenAI. (2024). OpenAI API documentation.
    https://platform.openai.com/docs

Anthropic. (2024). Claude API documentation.
    https://docs.anthropic.com

Touvron, H., et al. (2023). Llama 2: Open foundation and fine-tuned
    chat models. arXiv preprint arXiv:2307.09288.
```

### IEEE 형식 예시

```
[1] Rapptz, "discord.py," version 2.7.1, GitHub.
    [Online]. Available: https://github.com/Rapptz/discord.py

[2] Ollama, "Ollama," 2024. [Online]. Available: https://ollama.com

[3] OpenAI, "OpenAI API Documentation," 2024.
    [Online]. Available: https://platform.openai.com/docs

[4] Anthropic, "Claude API Documentation," 2024.
    [Online]. Available: https://docs.anthropic.com
```

---

## 4. 논문에 포함할 권장 그림

| 번호 | 그림 제목 | 설명 | 참조 파일 |
|------|----------|------|----------|
| Fig. 1 | 시스템 아키텍처 다이어그램 | 전체 컴포넌트 구성 | docs/ARCHITECTURE.md |
| Fig. 2 | 봇 명령어 흐름도 | /summarize 처리 시퀀스 다이어그램 | — |
| Fig. 3 | /summarize 실행 화면 | 요약 결과 Discord 스크린샷 | 데모 캡처 |
| Fig. 4 | /settings 패널 화면 | 설정 UI Discord 스크린샷 | 데모 캡처 |
| Fig. 5 | 모델별 응답 시간 비교 | 바 차트 | docs/model_comparison.md |
| Fig. 6 | 모델별 한국어 품질 비교 | 레이더 차트 또는 바 차트 | docs/model_comparison.md |
| Fig. 7 | 사용자 만족도 설문 결과 | 각 문항 평균 점수 막대 그래프 | docs/survey.md |
| Fig. 8 | 데이터베이스 ERD | 테이블 관계도 | docs/ARCHITECTURE.md |

---

## 5. 평가 지표 정리

| 범주 | 지표 | 측정 방법 |
|------|------|----------|
| 응답 품질 | 정확성 (Accuracy) | 수동 평가 루브릭 (1–5) |
| 응답 품질 | 관련성 (Relevance) | 수동 평가 루브릭 (1–5) |
| 응답 품질 | 한국어 품질 (Korean Quality) | 수동 평가 루브릭 (1–5) |
| 응답 품질 | 완전성 (Completeness) | 수동 평가 루브릭 (1–5) |
| 성능 | 평균 응답 시간 (ms) | 코드 타이머 측정 (command_logs.response_ms) |
| 사용성 | 사용 편의성 평균 | 설문 Q1, Q2 평균 |
| 사용성 | 기능 유용성 평균 | 설문 Q3, Q4, Q5 평균 |
| 사용성 | NPS | 설문 Q9 계산 |
| 사용성 | 전체 만족도 | 설문 전 문항 평균 |

---

## 6. 작성 체크리스트

- [ ] 모든 그림에 그림 번호와 캡션이 있는가?
- [ ] 참고문헌이 본문 인용과 일치하는가?
- [ ] 코드 예시는 핵심 부분만 발췌되어 있는가? (전체 코드는 부록)
- [ ] 평가 결과 표에 실제 측정값이 채워져 있는가?
- [ ] 추상(Abstract)이 200~300단어로 작성되어 있는가?
- [ ] 목차(Table of Contents)가 최종 페이지 번호와 일치하는가?
- [ ] 맞춤법 검사 완료 (한글 맞춤법 검사기 활용)

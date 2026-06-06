# AI Network RAG 기술스택 이식 계획

> 상태: Draft  
> 작성일: 2026-06-01  
> 기준 구현: `/Users/osuma/coding_stuffs/dailyting/rag`, `docker/rag`, `docker-compose.qdrant.yml`, `.github/workflows/rag-rebuild.yml`  
> 목표: RAG 는 새로 임의 설계하지 않고, Dailyting 에서 검증한 RAG 기술스택·인프라·CI/CD 패턴을 discord-assistant 에 맞게 이식한다.

## 1. 결론

RAG 는 **Dailyting RAG 스택을 표준 템플릿으로 가져온다.**

가져올 것:

- Python 3.12 컨테이너 기반 RAG 런타임
- Qdrant 벡터 DB
- OpenAI `text-embedding-3-large` 임베딩
- LlamaIndex 기반 인덱싱/검색
- BM25 키워드 검색
- SQLite `meta.db` 메타/식별자/그래프 저장
- exact + BM25 + vector hybrid 검색
- RRF fusion
- `BAAI/bge-reranker-v2-m3` reranker
- Docker wrapper script 방식
- GitHub Actions 인덱스 재빌드 workflow
- Cloudflare Access 로 보호된 원격 Qdrant 접근 방식
- golden set 기반 retrieval 평가
- MCP/search 도구화 패턴

바꿀 것:

- Dailyting 의 입력은 product SSOT YAML 중심이지만, Nexa는 `guild/channel KnowledgeSpace` 중심이다.
- Dailyting 의 collection 은 `dailyting_ssot` 이지만, Nexa는 guild/channel 스코프를 분리해야 한다.
- Dailyting 은 repo SSOT 검색이 목적이지만, Nexa는 Discord 질문 답변에 붙는 runtime RAG 이므로 Provider 보호와 token budget 이 더 중요하다.

## 2. Dailyting 에서 확인한 기준 스택

| 영역 | Dailyting 기준 | Nexa 적용 |
| --- | --- | --- |
| Runtime | `python:3.12-slim` Docker image | 동일 |
| Wrapper | `scripts/rag.sh` | `scripts/rag.sh` 또는 `central-server/scripts/rag.sh` 로 이식 |
| Vector DB | Qdrant `qdrant/qdrant:v1.13.6` | 동일 버전에서 시작 |
| Embedding | OpenAI `text-embedding-3-large`, 3072d | 동일. 비용/속도 이슈 시 후속 모델 교체 검토 |
| Index framework | LlamaIndex | 동일 |
| Keyword | LlamaIndex BM25 persist | 동일 |
| Meta store | SQLite `rag/meta.db` | 초기 동일. 운영 다중 guild 에서는 Postgres 메타 테이블 병행 검토 |
| Hybrid 검색 | exact + BM25 + vector → RRF | 동일 |
| Rerank | `BAAI/bge-reranker-v2-m3` | 동일. CPU 비용 때문에 기본 topK 제한 |
| CI/CD | `rag-rebuild.yml` self-hosted runner | `ai-rag-rebuild.yml` 로 이식 |
| Remote Qdrant | Cloudflare Access service token headers | 동일 패턴 재사용 |
| Eval | `rag/eval/golden.json` + Hit@K/MRR/Recall | guild/channel fixture 기반 golden set 추가 |
| Tooling | MCP server | 운영 내부 검색/검증 도구로 재사용 가능 |

## 3. Nexa RAG 목표

사용자 관점:

- 서버 관리자가 파일/링크/텍스트를 등록한다.
- 채널 AI 가 해당 지식을 참고해 답변한다.
- 지식은 채널/서버 단위로 분리된다.
- 삭제한 지식은 더 이상 검색되지 않는다.
- 민감정보는 입력하지 말라고 안내하고, 감지되면 차단/경고한다.

운영 관점:

- Qdrant 는 중앙 서버와 같은 Docker network 에 둔다.
- 인덱스 빌드는 컨테이너로 실행한다.
- 재빌드는 CI/CD 또는 운영 명령으로 반복 가능해야 한다.
- RAG 실패는 질문 기능 전체 장애로 번지지 않는다.
- RAG 결과는 Provider token budget 을 넘지 않는다.

## 4. 이식할 파일 구조 초안

```text
rag/
  build_index.py
  chunkers.py
  search.py
  qdrant_helper.py
  mcp_server.py
  requirements-build.txt
  requirements.txt
  eval/
    evaluate.py
    golden.json
  bm25/
  meta.db

docker/rag/Dockerfile
scripts/rag.sh
.github/workflows/ai-rag-rebuild.yml
docker-compose.qdrant.yml 또는 central-server/docker-compose.rag.yml
```

1차는 Dailyting 파일 구조를 거의 그대로 복사하되, chunker 와 input source 만 Nexa 도메인에 맞게 바꾼다.

## 5. Nexa RAG 입력 모델

Dailyting 은 repo YAML 파일을 색인했다. Nexa는 아래 입력을 색인한다.

- `KnowledgeSource(type=file)`
- `KnowledgeSource(type=link)`
- `KnowledgeSource(type=text)`
- 서버 운영 규칙
- 채널 AI 헌법
- 채널 AI 프리셋 설명
- 공개 help/FAQ 문서

색인 제외:

- 프롬프트 원문 중 비공개 system prompt
- 사용자 질문 원문 로그
- Provider 내부 상태 로그
- 토큰/API 키/비밀번호가 감지된 문서
- 관리자 전용 비밀 설정

## 6. Collection/스코프 설계

가장 위험한 부분은 서버 간 지식이 섞이는 것이다.

후보:

### A안. Qdrant collection 을 guild 별로 분리

예: `nexa_guild_{guildId}`

장점:

- 서버 간 격리 직관적
- 삭제/재빌드 단순

단점:

- guild 가 많아지면 collection 수 증가
- 운영/모니터링 복잡

### B안. collection 하나 + payload filter

예: collection `nexa_knowledge`, payload `guildId`, `channelId`, `knowledgeSpaceId`

장점:

- 운영 단순
- 모델/스키마 관리 쉬움

단점:

- filter 실수 시 지식 유출 위험
- 모든 검색 경로에서 filter 강제 필요

### 1차 권장

초기에는 **B안(collection 하나 + 강제 payload filter)** 로 시작하되, 검색 API 에서 `guildId` filter 없이는 실행 불가하게 만든다.

필수 invariant:

- `guildId` 없는 검색 금지
- `channelId` 또는 `knowledgeSpaceId` filter 를 명시적으로 합성
- cross-guild 검색 금지
- test fixture 로 서버 A/B 지식 섞임 방지 검증

## 7. 검색 파이프라인

Dailyting 기준 파이프라인을 유지한다.

1. exact 검색: 제목, 문서명, 태그, 명령어, 채널 AI 이름
2. BM25: 키워드 기반 검색
3. vector: Qdrant semantic 검색
4. RRF fusion
5. rerank: bge-reranker-v2-m3
6. source weighting: 관리자 등록 지식 > 공개 help > 파생 요약
7. scope filter: guild/channel/knowledgeSpace
8. token budget trimming
9. prompt composer 로 전달

중요:

- RAG 검색 결과는 그대로 답변이 아니다.
- 검색 결과는 system safety + AI 헌법 + 질문과 합성된다.
- Provider 로 보내기 전 길이 제한을 반드시 통과해야 한다.

## 8. CI/CD 이식

Dailyting 의 `.github/workflows/rag-rebuild.yml` 패턴을 가져온다.

Nexa workflow 초안:

```yaml
name: AI RAG 인덱스 재빌드

on:
  push:
    branches: [main]
    paths:
      - 'rag/**'
      - 'docker/rag/Dockerfile'
      - 'scripts/rag.sh'
      - '.github/workflows/ai-rag-rebuild.yml'
      - 'docs/FAQ.md'
      - 'docs/SENSITIVE_QUESTIONS.md'
      - 'docs/PROVIDER_SAFETY_POLICY.md'
  workflow_dispatch:
```

런너/네트워크:

- self-hosted `yeon-arm` 또는 별도 `nexa-rag`
- Docker network: 기존 central-server compose network 와 연결
- Qdrant 내부 URL: `http://nexa-qdrant:6333`
- 원격 read URL 은 Cloudflare Access 로 보호 가능

Secrets:

- `ENV_FILE` 단일 시크릿 원칙 유지
- `OPENAI_API_KEY`
- `QDRANT_CF_ACCESS_CLIENT_ID`
- `QDRANT_CF_ACCESS_CLIENT_SECRET`
- 별도 개별 GitHub secret 로 흩뿌리지 않음

## 9. 운영 인프라

Qdrant compose 는 Dailyting 과 같은 구조로 시작한다.

- image: `qdrant/qdrant:v1.13.6`
- host port 는 `127.0.0.1` bind
- volume: `nexa-qdrant-data`
- restart: `unless-stopped`
- healthcheck 포함
- 외부 공개는 Cloudflare Access 뒤에만 허용

중요:

- Qdrant 를 공개 인터넷에 직접 열지 않는다.
- 운영 build 가 실수로 다른 환경 collection 을 덮지 않게 `QDRANT_URL` 과 collection prefix 를 환경별로 분리한다.
- local build 기본값이 production Qdrant 를 덮지 않게 한다.

## 10. 보안 게이트

RAG 는 반드시 아래 게이트를 통과해야 구현 착수 가능하다.

- 파일 크기 제한
- 허용 MIME type 제한
- link fetch allowlist/denylist
- SSRF 방지
- 민감정보 정규식/휴리스틱 스캔
- 관리자 전용 업로드
- 삭제 시 Qdrant/BM25/meta.db 반영
- source visibility 표시
- audit log
- prompt token budget
- cross-guild leakage 테스트

## 11. 평가/품질

Dailyting 의 retrieval eval 방식을 그대로 가져온다.

지표:

- Hit@1
- Hit@3
- Hit@5
- Hit@10
- MRR
- Recall@10

Nexa golden set 예시:

- `#개발질문` 지식에 있는 배포 절차를 찾아야 한다.
- 서버 A 지식은 서버 B 검색에 나오면 안 된다.
- 삭제된 지식은 검색에 나오면 안 된다.
- 채널 지식이 guild 공통 지식보다 우선되어야 한다.
- 민감정보 포함 문서는 색인 거부되어야 한다.

## 12. 도메인 모델 연결

RAG 는 다음 Aggregate 와 연결된다.

- `KnowledgeSpace`: guild/channel 별 지식 공간
- `KnowledgeSource`: file/link/text 원본
- `KnowledgeDocument`: 파싱된 문서
- `KnowledgeChunk`: 검색 chunk
- `EmbeddingIndexJob`: 인덱싱 작업
- `RetrievalPolicy`: topK, token budget, rerank 여부, source 우선순위
- `AiBehaviorVersion`: 어떤 KnowledgeSpace 를 참조할지 snapshot
- `ChannelAi`: 현재 연결된 KnowledgeSpace pointer
- `AiChangeProposal`: 지식 추가/삭제가 high risk 일 때 승인
- `CustomizationAuditLog`: 지식 변경 이력

## 13. 구현 순서

1. Dailyting RAG 구조를 repo 로 복사하지 말고 먼저 설계 문서와 checklist 확정.
2. `docker-compose.qdrant.yml` 또는 central compose 확장 설계.
3. `rag/requirements*`, `docker/rag/Dockerfile`, `scripts/rag.sh` 이식.
4. Qdrant helper 와 Cloudflare Access 헤더 이식.
5. KnowledgeSpace/Source/Document/Chunk schema 작성.
6. 정적 문서 기반 build_index MVP 작성.
7. guild/channel payload filter 강제.
8. BM25/meta.db 생성.
9. search CLI 구현.
10. golden eval 구현.
11. workflow `ai-rag-rebuild.yml` 추가.
12. Discord 질문 runtime 에 붙이기 전 token budget/prompt composer 검증.

## 14. 중요한 결정

- RAG 기술스택은 Dailyting 것을 기준으로 한다.
- Qdrant 를 쓴다.
- Python 3.12 Docker RAG worker 를 쓴다.
- OpenAI embedding 으로 시작한다.
- BM25 + vector + exact hybrid 를 유지한다.
- reranker 는 기능으로 두되 비용/속도 때문에 끌 수 있게 한다.
- RAG 는 foundation 설계와 병렬로 “기획/인프라 설계”를 완료한다.
- RAG runtime 연결은 보안 게이트와 Provider token budget 설계 후 진행한다.

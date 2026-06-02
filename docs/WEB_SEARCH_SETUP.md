# 웹검색 활성화 가이드 (운영자용)

`/ask` 에 **웹 검색**을 켜는 방법. 로컬 LLM 은 웹을 못 보므로, 중앙 서버가 검색·본문 수집해
프롬프트에 주입하고 모델이 **출처를 인용**하며 답한다(RAG, Perplexity 등과 동일 구조).
검색은 **중앙 서버에서만** 수행한다(에이전트는 임의 URL 호출 금지 원칙 유지).

## 사용자 경험
- 디스코드에서 `/ask prompt:<질문> web:true` → 최신 정보로 답하고 답변 하단에 `🔎 출처` 표시.
- `web` 옵션을 끄면(기본) 평소처럼 로컬 모델만으로 답한다.

## 활성화 (둘 중 하나)
검색 백엔드(SearXNG)를 가리키는 `central.search.url` 을 설정하면 켜진다. 미설정이면 `web:true`
여도 평소 답변으로 동작(무위험).

### 1) SearXNG 자체 호스팅 (무료·키 불필요, 권장)
배포 호스트(이 Mac)에서:
```bash
docker run -d --name searxng -p 8888:8080 \
  -e SEARXNG_SETTINGS_PATH=/etc/searxng/settings.yml \
  searxng/searxng
# settings.yml 에서 JSON 출력 허용:  search: { formats: [html, json] }
```
중앙 서버 환경변수(`.env` / `ENV_FILE` 시크릿):
```
CENTRAL_SEARCH_URL=http://host.docker.internal:8888
CENTRAL_SEARCH_MAX_RESULTS=5
CENTRAL_SEARCH_FETCH_CONTENT=true
```
> Spring 프로퍼티명은 `central.search.url` / `central.search.max-results` /
> `central.search.fetch-content`. 환경변수는 위 대문자 형태로 매핑된다.

### 2) 기존 SearXNG 인스턴스 사용
공개/사내 SearXNG 의 JSON 엔드포인트를 `central.search.url` 로 지정.

## 품질·안전
- **본문 수집**: 상위 결과의 실제 페이지 본문을 가져와(스니펫보다 깊음) 주입.
  `central.search.fetch-content=false` 로 끄면 스니펫만 사용.
- **SSRF 차단**: 가져오는 URL 은 public http(s) 만 — localhost/사설/루프백/링크로컬·리다이렉트 차단.
- **자원 상한**: 페이지당 크기/시간 상한, 상위 N개만, 병렬 제한.
- **프라이버시**: 검색 질의는 SearXNG·대상 사이트로 나간다. 비밀번호·개인정보를 질문에 넣지 말 것
  (기존 고지와 동일). 질문 원문은 로그/DB 에 저장하지 않는다.

## 더 끌어올리기(후속)
- 결과 **재랭킹**·**쿼리 재작성**·기존 RAG 스택(qdrant·bm25·rrf·reranker) 재사용으로 관련 문단만 주입.
- 8B+ 모델로 종합(분류기 라우팅). 관련: [plans/STABLE_DIFFUSION_SUPPORT.md](plans/STABLE_DIFFUSION_SUPPORT.md).

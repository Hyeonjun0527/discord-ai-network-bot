# Stable Diffusion (로컬 이미지 생성) 프로바이더 지원 — 설계안

> 목표: Ollama(텍스트·비전)에 더해, 프로바이더 PC가 **로컬 Stable Diffusion**으로 이미지를
> 생성해 풀에 기여한다. **외부 이미지 API 미사용** — 기여·자급·공정성 원칙(ADR 0003/0004) 유지.

## 1. 현재 구조 (제약 재확인)
- `central-server ↔ provider-agent`: 인증된 WS relay. 프레임 = camelCase JSON.
- 서버→에이전트 명령은 **infer/cancel/ping** 뿐(보안 불변식). 결과는 `result`(text)/`chunk`(스트림).
- `MAX_FRAME_BYTES = 1MB`. **이미지(수백 KB~수 MB)는 단일 프레임에 못 담는다** → 분할 전송 필요.
- 에이전트는 localhost 백엔드만 호출(netguard). 임의 URL/shell 금지.

## 2. 핵심 설계 결정
1. **별도 capability `image`**: 프로바이더가 SD 백엔드를 켜면 `provider_hello` 에
   `capabilities: ["text", "image"]` 로 광고. 이미지 요청은 **image-capable 프로바이더로만** 라우팅
   (텍스트 풀과 분리). 텍스트 전용 프로바이더는 영향 없음.
2. **SD 백엔드**: 에이전트가 로컬 SD 서버의 HTTP API 에 붙는다. 1순위 후보:
   - **Automatic1111 WebUI** `POST /sdapi/v1/txt2img` (가장 보편, base64 PNG 반환)
   - 대안: **ComfyUI** API, **stable-diffusion.cpp** 서버.
   - Ollama 와 동일하게 **localhost 전용**(netguard 재사용). 기본 `http://127.0.0.1:7860`,
     `--sd-url` 로 변경, 원격은 `--allow-remote-sd` 명시 시만.
3. **이미지 전송 = chunk 분할**: 1MB 프레임 한계 때문에 base64 이미지를 **여러 프레임으로 분할**한다.
   기존 스트리밍 경로(`chunk`)를 재사용하거나 `imageChunk`(seq, total, dataB64, done) 신설.
   이미지 크기 상한(예: ≤ 4MB)·해상도 캡(예: ≤ 1024²)·steps 캡으로 폭주 방지.
4. **프로토콜 확장**(양측 계약 동시 변경 + 계약 테스트):
   - `infer` 에 `task: "image"` + `imageOptions`(width·height·steps·sampler·seed·negativePrompt)
     **화이트리스트**(서버가 임의 옵션 주입 불가).
   - 응답: `imageResult`(mimeType, 총 크기) + N개 `imageChunk`. 또는 `result.images: [b64...]`.
5. **Discord**: `/imagine <prompt>` 슬래시 커맨드 → 이미지 라우팅 → 결과를 **첨부 이미지**로 렌더.

## 3. 보안·자원·프라이버시 (기존 원칙 유지)
- SD 주소 **localhost 전용**(netguard), 원격은 위험 확인 옵션에서만.
- 이미지 생성은 **매우 무거움**(GPU·수~수십 초) → 비용 점수 대폭 가중, 이미지 동시성 기본 1,
  **이미지 전용 daily_limit**(텍스트와 분리). 자동 pause(배터리/고부하) 동일 적용.
- 프라이버시: 프롬프트가 프로바이더 PC 로 전송됨(텍스트와 동일 고지) + **생성 이미지의 저작권·NSFW
  책임 고지**. 로그에 프롬프트·이미지 원문 미저장.
- **세이프티 필터**: 프롬프트/결과 NSFW 차단 정책(서버측), 민감 프롬프트 게이트(기존 패턴 재사용).
- 서버는 SD 에 infer/cancel 만 지시 가능(옵션 화이트리스트). 모델 다운로드·shell 불가.

## 4. 단계별 구현 (phase)
- **Phase 1 — 스파이크(에이전트)**: `sd.py` 클라이언트(A1111 txt2img) + `--sd-url`/capability 광고 +
  `--self-test` 로 로컬 1장 생성 검증. 서버 무관, 위험 낮음.
- **Phase 2 — 프로토콜**: `infer.task=image` + `imageChunk` 분할 전송 + central 라우팅(image-capable) +
  양측 계약 테스트(WireContractTest ↔ test_contract). 크기/해상도 캡.
- **Phase 3 — Discord**: `/imagine` 커맨드 + 첨부 렌더 + 세이프티 필터 + 비용 가중·레이트리밋.
- **Phase 4 — 운영**: 이미지 전용 한도/동시성, 관측성(대시보드), 프라이버시 고지·설치 가이드 갱신.

## 5. 리스크 / 오픈 이슈
- SD 설치 부담(A1111/ComfyUI 는 무겁고 GPU 필요) → **opt-in** + 별도 설치 가이드. GPU 없는 PC 는 매우 느림 → capability 성능 힌트.
- 1MB 프레임 한계 → chunk 설계 필수(상한 엄수).
- 모델 라이선스·NSFW·저작권 → 정책·필터 필수.
- 백엔드 선택(A1111 vs ComfyUI) → Phase 1 에서 A1111 로 고정 후 확장.

## 6. 함께 가는 다른 능력(참고)
- **웹검색**: 서버측 RAG/tool-augmentation(검색 API 또는 SearXNG → 프롬프트 주입). 에이전트가 아니라
  **서버**에서 수행(임의 URL 호출 금지 원칙 유지). 가치 최고·우선 권장.
- **비전 입력(이미지 이해)**: Ollama 비전 모델 + 에이전트의 기존 `images` 입력 경로 활용. 저비용.

우선순위 제안: 웹검색 → 비전 입력 → **Stable Diffusion(본 문서)**. 셋 다 "무거운/외부전송" 작업이라
프라이버시 고지·비용 가중·레이트리밋을 함께 설계한다.

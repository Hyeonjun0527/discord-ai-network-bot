# Nexa RunPod Serverless diffusers 워커

provider-agent 의 `RunPodClient`(클라우드 이미지 백엔드)가 호출하는 RunPod Serverless 워커다. diffusers
파이프라인으로 이미지를 생성하고 base64 PNG 를 돌려준다. **모델/스케줄러/스텝을 직접 고를 수 있어**
전용 체크포인트·LoRA·애니 그림체에서 Stability 보다 유리할 수 있다(품질 편차는 세팅에 달림).

## 입력/출력 계약 (RunPodClient 와 일치)

```jsonc
// 입력
{ "input": {
  "prompt": "...", "negative_prompt": "...",
  "width": 1024, "height": 1024,
  "num_inference_steps": 30, "guidance_scale": 7.0, "seed": 0
}}
// 출력
{ "output": { "image_base64": "<png base64>", "model": "...", "width": 1024, "height": 1024, "seed": 0 } }
```

`RunPodClient._parse_output` 은 `image_base64`(권장) 외에 `image`·`images[0]`·문자열 형태도 받는다.

## 배포 (한 번만)

1. 이미지 빌드·푸시:
   ```bash
   cd provider-agent/runpod-worker
   docker build -t <your-registry>/nexa-runpod-worker:latest .
   docker push <your-registry>/nexa-runpod-worker:latest
   ```
2. RunPod 콘솔 → **Serverless → New Endpoint** → 위 이미지 지정. GPU 는 SDXL 기준 24GB(예: RTX 4090/A5000)
   권장. 환경변수 `MODEL_ID` 로 모델 교체(기본 `stabilityai/stable-diffusion-xl-base-1.0`). gated 모델이면
   `HF_TOKEN` 도 등록.
3. 생성된 **Endpoint ID** 와 RunPod **API Key**(Settings → API Keys)를 provider-agent 에 설정:
   ```bash
   export RUNPOD_API_KEY=...        # 또는 saved config runpod_api_key
   export RUNPOD_ENDPOINT_ID=...    # 또는 saved config runpod_endpoint_id
   # IMAGE_BACKEND=runpod 는 키가 있으면 자동 선택됨(명시도 가능)
   ```

provider-agent 가 `health`(`GET /v2/<id>/health`)로 워커 가용을 확인하면 풀에 이미지 capability 를
광고하고, `/그림` 요청을 이 워커로 보낸다. 프롬프트 안전 심사는 **중앙 서버**가 먼저 수행하므로 워커는
정규화된 프롬프트만 받는다.

## 비용 메모

RunPod Serverless 는 실행 시간 과금이라 idle 비용이 낮다(콜드스타트 존재). 콜드스타트를 줄이려면
Dockerfile 의 모델 사전캐시(주석 처리된 `RUN ... from_pretrained`)를 켜거나 Active Worker 를 1개 둔다.

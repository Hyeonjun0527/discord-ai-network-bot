# 이미지 프로바이더 되기 (선택) — 누구나 가능

이미지 생성(`/imagine`)은 **중앙 서버가 하지 않습니다.** 텍스트와 똑같이, **각자 PC의 로컬
Stable Diffusion** 을 풀에 기여하는 구조입니다. 그래서 **누구나** 자기 PC에서 SD를 켜고 에이전트에
`--enable-image` 만 더하면 이미지 프로바이더가 됩니다. (GPU가 없는 PC는 매우 느리니 GPU 권장)

> 설계 원칙: 모든 프로바이더는 동등합니다. 이미지 capability 는 특별한 서버 인프라가 아니라
> **프로바이더가 켜는 옵션 플래그**일 뿐입니다. 텍스트만 제공하던 프로바이더도 SD를 켜면 그날부터
> 이미지 프로바이더가 됩니다.

## 1) 로컬 Stable Diffusion 실행 (API 켜기)
가장 보편적인 **AUTOMATIC1111 WebUI** 기준(ComfyUI 등도 가능):
```bash
git clone https://github.com/AUTOMATIC1111/stable-diffusion-webui
cd stable-diffusion-webui
# 모델(.safetensors) 1개를 models/Stable-diffusion/ 에 넣으세요(~2~6GB).
./webui.sh --api --listen          # macOS/Linux. --api 로 REST 활성(기본 127.0.0.1:7860)
# Windows: webui-user.bat 에 set COMMANDLINE_ARGS=--api
```
- 에이전트는 기본 `http://127.0.0.1:7860` 의 A1111 `/sdapi/v1/txt2img` 를 호출합니다.
- SD 주소가 다르면 `--sd-url http://127.0.0.1:7860` 으로 지정.

## 2) 에이전트를 이미지 모드로 실행
기존 실행 명령에 **`--enable-image`** 만 추가하면 됩니다:
```bash
discord-ai-network-bot --token <토큰> --relay-url wss://discord-ai.yeon.world/agent --enable-image
```
- 시작 시 에이전트가 SD에 health 체크 → 도달하면 풀에 **image capability** 를 광고합니다.
- 점검: `discord-ai-network-bot --self-test --enable-image` (Ollama + SD 1장 생성 확인).

## 3) 사용
이미지 프로바이더가 풀에 하나라도 있으면, 누구나 디스코드에서:
```
/imagine prompt:푸른 밤하늘 아래 고양이, 수채화
```
→ image-capable 프로바이더로 라우팅되어 생성된 이미지가 첨부로 옵니다.

## 안전·자원 (텍스트와 동일 원칙)
- SD는 **localhost 전용**(원격은 `--allow-remote-sd` 명시 시만). 임의 URL 호출 없음.
- 옵션 화이트리스트 + 해상도/steps 상한, 응답 크기 상한. CPU 고부하·배터리 방전 시 자동 pause.
- 일일 한도(`--daily-limit`)·동시성(기본 1)은 이미지에도 동일 적용 — 프로바이더 주권.
- 프롬프트 원문은 로그/파일에 저장하지 않음.

## 자주 묻는 것
- **GPU 없으면?** 동작은 하지만 CPU 생성은 매우 느립니다(장당 수 분~). GPU(NVIDIA/Apple Silicon MPS) 권장.
- **모델은?** SD 1.5/SDXL 등 A1111 호환 체크포인트면 됩니다. 라이선스·NSFW는 프로바이더 책임.
- **중앙 서버에 GPU가 필요한가요?** 아니요. 서버는 라우팅만 합니다. 이미지는 프로바이더 PC가 만듭니다.

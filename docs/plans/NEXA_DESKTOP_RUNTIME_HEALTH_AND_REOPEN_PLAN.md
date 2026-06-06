# NEXA Desktop Runtime Health and Reopen Plan

작성일: 2026-06-06

## 목적

NEXA 데스크톱 앱은 Discord 서버에 로컬 AI provider 로 참여하는 운영 표면이다. 사용자는 앱 창을
닫아도 백그라운드 연결이 유지되기를 기대하고, 다시 앱을 열면 설정 창이 열려 현재 상태를 확인할 수
있어야 한다. 또한 텍스트 제공에 필요한 Ollama 와 이미지 제공에 필요한 Stable Diffusion 상태가
명확하게 보여야 한다.

이 문서는 다음 네 문제를 해결하기 위한 작업 계획이다.

- 데스크톱 앱에서 Ollama 와 Stable Diffusion health 를 분리해서 보여준다.
- 온보딩 이후 실제 사용 화면에서 Ollama 모델을 탐색하고, 설치하고, 제공 대상으로 선택할 수 있게 한다.
- Stable Diffusion 설치 여부, 설치된 모델, 이미지 생성 제공 토글, Discord 라우팅 상태를 사용자가 확인할 수
  있게 한다.
- macOS 에서 백그라운드 서비스가 떠 있을 때 응용 프로그램에서 NEXA 를 다시 열어도 설정 창이
  열리게 한다.

## 현재 구현 관찰

### Provider Agent 연결 상태

- `provider-agent/src/provider_agent/webui.py` 의 `_start_agent()` 는 저장된 설정으로
  `ProviderAgent` 를 시작한다. token 이 없으면 시작하지 않고, singleton lock 이 이미 잡혀 있으면
  다른 인스턴스가 연결 중이라고 판단한다.
- `/api/status` 는 현재 `running`, `connected`, `imageReady`, `backgroundRunning`, `models`,
  `hasToken`, `enableImage` 등을 내려준다.
- `backgroundRunning` 은 GUI 프로세스가 직접 연결 중이 아니지만 singleton lock 이 다른 프로세스에
  잡혀 있을 때 true 가 된다. 즉 백그라운드 자동시작 서비스가 연결 중인 상태를 UI 에 알릴 수 있다.

### Ollama 상태

- `provider-agent/src/provider_agent/agent.py` 의 `ProviderAgent` 는 `OllamaClient` 를 만들지만,
  agent 시작 시 Ollama daemon 을 자동으로 기동하지 않는다.
- 실제 Ollama 자동 설치/기동/모델 pull 은 `provider-agent/src/provider_agent/ollama_setup.py` 의
  `run_setup()` 경로에서 수행된다.
- 이 경로는 앱의 "Ollama 자동 설치 + 기본 모델 받기" 버튼에서 호출되는 명시적 사용자 액션이다.
- 따라서 백그라운드 자동연결이 켜져 있어도, 현재 구현만으로는 Ollama 가 항상 켜져 있다고 보장할 수
  없다.
- 현재 데스크톱 앱은 제공 중인 모델 목록은 보여주지만, 온보딩이 끝난 뒤 실제 사용 화면에서
  `exaone3.5:7.8b` 같은 추천/설치 가능 모델을 목록으로 보여주고, 사용자가 선택해서 설치한 뒤
  제공 대상으로 전환하는 흐름은 충분히 열려 있지 않다.

### Stable Diffusion 상태

- `ProviderAgent` 는 `enable_image` 가 켜져 있으면 `SDClient` 를 만든다.
- agent 시작 시 SD health 를 확인하고, 준비되어 있지 않으면 설치 여부를 확인한 뒤
  `sd_setup.launch_only()` 로 자동 기동을 시도한다.
- SD 가 준비되면 image capability 를 재광고하기 위해 연결을 재수립한다.
- 따라서 SD 는 Ollama 보다 백그라운드 자동 복구 흐름이 더 잘 연결되어 있다.
- 다만 실제 사용 화면에서 Stable Diffusion 이 설치되어 있는지, 설치된 checkpoint/model 이 무엇인지,
  이미지 생성 제공이 켜져 있는지, 켜진 설정이 중앙 서버에 image capability 로 광고되었는지가 충분히
  보이지 않는다.
- 사용자가 이미지 생성 제공을 켰고 로컬 SD 가 준비되어 있다면 Discord 에서 다음 문구가 뜨는 대신 실제
  이미지 생성으로 이어져야 한다.

```text
⚠️ 🖼️ 이미지 생성 가능한 프로바이더가 없습니다. (프로바이더가 로컬 Stable Diffusion 을 켜고 에이전트를 --enable-image 로 실행해야 합니다)
```

- 위 문구가 계속 뜬다면 SD 설치/기동 자체뿐 아니라 provider capability 광고, 중앙 서버 provider 상태,
  Discord `/imagine` 라우팅 중 하나가 끊긴 상태로 봐야 한다.

### macOS 백그라운드 서비스와 재오픈

- `provider-agent/src/provider_agent/service.py` 의 LaunchAgent plist 는 실행파일에 `--service` 를
  붙여 headless 모드로 실행한다.
- 현재 실행파일 경로는 `executable_path()` 에 의해 `nexa` 또는 현재 실행 바이너리로 결정된다.
- macOS 에서 LaunchAgent 가 같은 `.app` bundle 의 메인 실행파일을 잡고 있으면 Finder/응용 프로그램
  더블클릭 시 "이미 실행 중"으로 인식되어 GUI 창이 열리지 않을 수 있다.
- GUI 를 닫을 때 `webui._handoff_to_service_on_close()` 는 singleton lock 을 풀고
  `service.kickstart()` 로 백그라운드 서비스를 즉시 띄우도록 설계되어 있다.

## 문제 정의

### 문제 1: 런타임 health 가 한 화면에서 충분히 명확하지 않음

현재 UI 는 provider 연결과 이미지 제공 여부를 일부 보여주지만, 다음 상태가 명확히 분리되어 있지 않다.

- NEXA provider agent 가 Discord 서버에 연결되어 있는지
- Ollama 실행파일이 설치되어 있는지
- Ollama daemon 이 응답하는지
- 기본 텍스트 모델이 설치되어 있는지
- Stable Diffusion 이 설치되어 있는지
- Stable Diffusion API 가 응답하는지
- 이미지 제공 토글은 켜져 있지만 실제로 image capability 가 광고되지 않는 상태인지

### 문제 2: 백그라운드 연결 중 macOS 앱 재오픈 실패

사용자 기대:

- 창을 닫으면 백그라운드 연결은 유지된다.
- 응용 프로그램에서 NEXA 를 다시 열면 설정 창이 열린다.
- 이미 백그라운드 서비스가 provider 연결을 담당 중이면, GUI 는 연결을 중복 시작하지 않고 설정용 창으로
  동작한다.

현재 위험:

- LaunchAgent 가 GUI 앱 bundle 의 메인 실행파일을 headless 로 실행하면 macOS 가 앱을 이미 실행 중으로
  취급할 수 있다.
- 이 경우 사용자가 응용 프로그램에서 NEXA 를 다시 열어도 GUI 창이 열리지 않는다.

### 문제 3: 온보딩 이후 Ollama 모델 설치/선택/제공 UX 가 닫혀 있음

사용자 기대:

- 첫 온보딩이 끝난 뒤에도 실제 사용 화면에서 설치 가능한 Ollama 모델을 볼 수 있다.
- `exaone3.5:7.8b` 같은 기본/추천 모델이 설치 가능 목록에 보인다.
- 모델을 선택해서 설치하고, 설치 진행률을 확인하고, 설치 완료 후 제공 대상으로 켤 수 있다.
- 이미 설치된 모델과 현재 Discord 에 provider capability 로 광고 중인 모델이 구분된다.

현재 위험:

- UI 가 "현재 제공 중인 모델"만 보여주면, 사용자는 어떤 모델을 추가로 설치할 수 있는지 알 수 없다.
- 기본 모델을 EXAONE 으로 정해도 실제 사용 화면에 설치/제공 액션이 없으면 SSOT 값이 사용자 행동으로
  이어지지 않는다.
- 설치된 모델, 선택된 모델, 실제 광고된 모델이 섞이면 사용자는 "분명 모델을 받았는데 왜 제공되지
  않는지"를 알 수 없다.

### 문제 4: Stable Diffusion 설치/모델/이미지 제공 상태가 불투명하고 라우팅이 깨질 수 있음

사용자 기대:

- Stable Diffusion 이 설치되어 있는지 앱에서 바로 보인다.
- 설치된 SD checkpoint/model 목록을 볼 수 있다.
- 이미지 생성 제공 토글을 켜면, SD 가 준비된 뒤 중앙 서버에 image capability 가 광고된다.
- 이미지 생성 제공이 켜져 있고 SD 가 준비되어 있으면 Discord 이미지 명령은 provider 없음 오류가 아니라
  실제 이미지 생성으로 이어진다.

현재 위험:

- SD 가 설치되어 있어도 UI 에 설치 여부와 설치된 모델이 보이지 않으면 사용자는 준비 상태를 판단할 수 없다.
- 이미지 생성 제공 토글이 없거나 토글의 의미가 불명확하면 `enable_image`, SD health, 중앙 서버 capability
  광고가 서로 어긋난다.
- 앱에서 토글을 켰다고 느끼는 상태와 Discord 에서 image provider 로 라우팅 가능한 상태가 다르면, 위의
  "이미지 생성 가능한 프로바이더가 없습니다" 문구가 계속 뜬다.

## 설계 원칙

- Provider 연결 상태와 로컬 런타임 상태를 분리한다.
- 자동 시작은 연결을 유지해야 하지만, 사용자 몰래 대형 설치를 시작하지 않는다.
- 설치는 명시적 사용자 액션으로 유지한다.
- 이미 설치된 로컬 런타임의 재기동은 자동 복구 대상으로 본다.
- 모델 상태는 "설치 가능", "설치됨", "선택됨", "제공 중/광고됨"을 분리해서 표현한다.
- 백그라운드 service 와 GUI app 은 macOS 에서 서로 다른 실행 표면으로 분리한다.
- GUI 는 백그라운드 연결이 떠 있어도 항상 열릴 수 있어야 한다.

## 권장 구현 계획

### 1. Runtime health API 추가

권장 endpoint:

- `GET /api/runtime-health`

반환 필드:

```json
{
  "ollama": {
    "installed": true,
    "ready": true,
    "modelCount": 3,
    "defaultModel": "exaone3.5:7.8b",
    "defaultInstalled": true,
    "installedModels": ["exaone3.5:7.8b", "llama3.1:8b"],
    "advertisedModels": ["exaone3.5:7.8b"],
    "recommendedModels": [
      {
        "id": "exaone3.5:7.8b",
        "name": "EXAONE 3.5 7.8B",
        "installed": true,
        "selected": true,
        "recommended": true
      }
    ],
    "error": null
  },
  "stableDiffusion": {
    "enabled": true,
    "installed": true,
    "ready": false,
    "advertised": false,
    "installedModels": ["realisticVisionV60B1_v51VAE.safetensors"],
    "selectedModel": "realisticVisionV60B1_v51VAE.safetensors",
    "busy": false,
    "error": null
  }
}
```

비고:

- `/api/status` 는 provider 연결 상태 중심으로 유지한다.
- `/api/runtime-health` 는 Ollama/SD 처럼 느릴 수 있는 health check 를 담당한다.
- UI 는 두 endpoint 를 병렬 polling 한다.
- timeout 을 짧게 잡아 UI refresh 가 멈추지 않게 한다.

### 2. Ollama 모델 catalog 와 설치/제공 흐름 추가

권장 endpoint:

- `GET /api/ollama/catalog`
  - SSOT 에 정의된 추천/지원 모델 목록을 반환한다.
  - 각 모델은 `id`, `displayName`, `size`, `description`, `installed`, `selected`, `advertised`,
    `recommended`, `default` 를 가진다.
- `POST /api/ollama/model-install`
  - 사용자가 선택한 모델을 `ollama pull` 로 설치한다.
- `GET /api/ollama/model-install-progress`
  - 설치 진행률, 현재 단계, 오류를 반환한다.
- `POST /api/models/select`
  - 설치된 모델을 provider 제공 대상으로 선택한다.
  - 선택 후 agent 재연결 또는 capability 재광고 필요 여부를 반환한다.

UX:

- "설치 가능 모델" 섹션을 온보딩 이후 실제 사용 화면에도 둔다.
- 기본 추천 모델은 `exaone3.5:7.8b` 를 우선 표시한다.
- 모델별 상태를 다음처럼 분리한다.
  - 설치 가능
  - 설치 중
  - 설치됨
  - 제공 대상으로 선택됨
  - Discord 에 제공 중
- 설치 완료 후 바로 "제공하기" 액션을 노출한다.
- 이미 제공 중인 모델은 단순 목록이 아니라 "현재 Discord 에 광고 중" 상태로 표시한다.

### 3. 데스크톱 UI 상태 카드 정리

상단 또는 설정 섹션에 다음 3개 축을 분리해서 표시한다.

- Discord provider
  - 연결됨
  - 백그라운드 연결됨
  - 연결 중
  - 미연결
- Ollama
  - 실행 중
  - 설치됨, 꺼짐
  - 미설치
  - 기본 모델 없음
- Stable Diffusion
  - 실행 중, 이미지 생성 가능
  - 설치됨, 꺼짐
  - 미설치
  - 비활성
  - 준비 중

권장 액션:

- Ollama 미설치: "Ollama 자동 설치 + 기본 모델 받기"
- Ollama 설치됨, 꺼짐: "Ollama 시작"
- 기본 모델 없음: "기본 모델 받기"
- 설치 가능 모델 있음: "모델 설치"
- 설치된 모델 있음: "제공 대상으로 선택"
- 선택됨 but 광고 안 됨: "다시 연결해서 제공 시작"
- SD 미설치: "이미지 생성 환경 준비"
- SD 설치됨, 꺼짐: "Stable Diffusion 시작"
- SD 설치됨: "설치된 이미지 모델 보기"
- 이미지 제공 OFF: "이미지 생성 제공 켜기"
- 이미지 제공 ON but 광고 안 됨: "다시 연결해서 이미지 제공 시작"
- 백그라운드 연결 중: "백그라운드 중지"

### 4. Ollama 자동 기동 정책 추가

백그라운드 자동연결 시 텍스트 제공은 핵심 기능이므로 agent startup 에서 Ollama health 를 확인한다.

정책:

- Ollama ready: 그대로 진행
- Ollama installed but not ready: `ollama serve` 또는 platform service start 로 기동 시도
- Ollama not installed: 설치하지 않고 UI/로그에 경고만 남김
- 기본 모델 없음: 설치하지 않고 UI/로그에 경고만 남김

이 정책은 SD 의 기존 자동 기동 흐름과 일관된다. 단, 설치와 대형 모델 다운로드는 사용자 명시 액션으로
유지한다.

### 5. Stable Diffusion 설치/모델/제공 UX 강화

기존 SD 자동 기동 흐름은 유지한다.

추가 표시:

- `enable_image=true` 이지만 `imageReady=false` 인 경우:
  - "이미지 요청 받기 설정은 켜져 있지만 SD 가 아직 준비되지 않아 Discord 에 image capability 로
    광고되지 않았습니다."
- `enable_image=true`, SD ready, `advertised=false` 인 경우:
  - "이미지 생성은 준비됐지만 아직 Discord 에 제공자로 광고되지 않았습니다. 다시 연결이 필요합니다."
- SD 자동 기동 중:
  - "Stable Diffusion 시작 중"
- SD 자동 기동 성공 후:
  - "이미지 capability 재광고됨"

권장 endpoint:

- `GET /api/sd/status`
  - `installed`, `enabled`, `ready`, `advertised`, `busy`, `selectedModel`, `error`, `needsReconnect` 를
    반환한다.
- `GET /api/sd/models/installed`
  - 설치된 checkpoint/model 목록을 반환한다.
- `POST /api/sd/model-install`
  - 지원한다면 추천 SD 모델 설치를 시작한다. 대형 다운로드이므로 명시 액션이어야 한다.
- `POST /api/image-provider`
  - 이미지 생성 제공 토글을 저장한다.
  - 토글 변경 후 agent 재연결 또는 capability 재광고 필요 여부를 반환한다.

Discord 라우팅 기준:

- 이미지 생성 제공 ON, SD ready, image capability advertised 이면 `/imagine` 은 해당 provider 로 라우팅한다.
- 이 상태에서도 provider 없음 문구가 뜨면 central-server 의 provider capability 저장, provider 재광고,
  `/imagine` provider selection 테스트가 깨진 것으로 본다.
- provider 없음 문구는 실제로 image capability provider 가 0명일 때만 허용한다.

### 6. macOS LaunchAgent 실행 표면 분리

권장 해결:

- GUI `.app` bundle 의 메인 실행파일과 headless service 실행파일을 분리한다.
- LaunchAgent 는 `.app` 메인 binary 를 실행하지 말고 headless helper 를 실행한다.

가능한 구현안:

1. `.app/Contents/Resources/nexa-service` helper 를 포함한다.
2. 설치 시 `~/Library/Application Support/NEXA/nexa-service` 로 helper 를 복사한다.
3. LaunchAgent plist 의 `ProgramArguments` 는 helper + `--service` 를 가리킨다.
4. GUI 더블클릭은 기존 `.app` 메인 실행파일 + `--gui` 경로를 유지한다.

예상 효과:

- 백그라운드 provider 연결은 headless helper 가 담당한다.
- macOS 는 GUI `.app` 을 실행 중으로 오인하지 않는다.
- 응용 프로그램에서 NEXA 를 다시 열면 설정 창이 열린다.
- GUI 는 singleton lock 을 보고 `backgroundRunning=true` 상태로 설정용 창을 보여준다.

### 7. Windows/Linux 고려

- Windows 작업 스케줄러도 가능하면 GUI exe 가 아니라 headless helper 또는 `nexa --service` 콘솔 실행
  표면을 사용한다.
- Linux systemd user service 는 GUI 앱이 아니라 CLI/headless executable 을 실행하는 편이 자연스럽다.
- cross-platform packaging SSOT 는 `packaging/assets.json` 및 `scripts/check_packaging.py` drift check 로
  보호한다.

## 테스트 계획

### Unit tests

- `provider-agent/tests/test_webui.py`
  - `/api/runtime-health` 가 Ollama installed/ready/defaultInstalled 상태를 정확히 반환한다.
  - `/api/ollama/catalog` 가 `exaone3.5:7.8b` 를 기본/추천 모델로 반환한다.
  - 모델 설치 진행률 API 가 설치 중/성공/실패 상태를 반환한다.
  - 설치된 모델, 선택된 모델, 광고된 모델이 서로 다른 상태를 구분해서 반환된다.
  - `/api/runtime-health` 가 SD enabled/installed/ready/busy 상태를 정확히 반환한다.
  - SD installed checkpoint 목록이 반환된다.
  - 이미지 제공 토글 ON but SD not ready 상태가 `advertised=false` 로 표시된다.
  - 이미지 제공 토글 ON, SD ready, advertised 상태가 정확히 표시된다.
  - background service 가 singleton lock 을 잡고 있어도 GUI status 가 `backgroundRunning=true` 를 반환한다.
  - runtime health check 실패가 UI/status API 전체 실패로 번지지 않는다.

- `provider-agent/tests/test_agent.py`
  - Ollama installed but not ready 인 경우 startup 에서 start-only 를 시도한다.
  - Ollama not installed 인 경우 설치를 시도하지 않는다.
  - 기본 모델이 없을 때 연결 자체는 막지 않되 경고 상태를 남긴다.
  - 선택된 Ollama 모델만 provider capability 로 광고한다.
  - SD installed but not ready 인 경우 기존 `_boot_sd()` 자동 기동 흐름이 유지된다.
  - `enable_image=true` 이고 SD ready 인 경우 image capability 를 광고한다.
  - `enable_image=false` 인 경우 SD 가 설치되어 있어도 image capability 를 광고하지 않는다.

- `provider-agent/tests/test_service.py`
  - macOS LaunchAgent plist 가 GUI `.app` 메인 실행파일 대신 headless helper 를 가리킨다.
  - `kickstart()` 는 headless service 만 재시작한다.
  - `install_service()` 실패는 `serviceError` 로 표면화된다.

- `central-server` 테스트
  - image capability provider 가 있으면 `/imagine` 이 provider 없음 오류로 떨어지지 않는다.
  - image capability provider 가 0명일 때만 provider 없음 문구를 반환한다.
  - provider 가 이미지 제공 토글 변경 후 재광고하면 중앙 서버 provider 상태가 갱신된다.

### Manual tests

1. NEXA 앱 실행.
2. Discord 연동 완료.
3. 시스템 로그인 시 자동 실행, 로그인 후 자동 연결, 백그라운드 실행 유지 ON.
4. 창 닫기.
5. provider 가 Discord 서버에 계속 연결되어 있는지 확인.
6. 응용 프로그램에서 NEXA 다시 실행.
7. 설정 창이 열리고 "백그라운드 서비스가 이미 연결돼 있음" 상태가 표시되는지 확인.
8. Ollama 를 꺼둔 상태에서 백그라운드 시작 후:
   - 설치되어 있으면 자동 기동되는지 확인.
   - 미설치면 UI 가 미설치 상태와 설치 버튼을 보여주는지 확인.
9. SD 를 꺼둔 상태에서 이미지 제공 ON 후:
   - 설치되어 있으면 자동 기동되는지 확인.
   - 준비 후 image capability 가 재광고되는지 확인.
10. 온보딩 완료 후 실제 사용 화면에서 Ollama 설치 가능 모델 목록을 연다.
11. `exaone3.5:7.8b` 가 기본/추천 모델로 보이는지 확인한다.
12. `exaone3.5:7.8b` 를 설치하고 진행률, 완료 상태, 제공 대상으로 선택하는 흐름을 확인한다.
13. Stable Diffusion 이 설치된 상태에서 설치 여부와 checkpoint/model 목록이 보이는지 확인한다.
14. 이미지 생성 제공 토글을 켠 뒤 Discord `/imagine` 이 실제 이미지 생성으로 이어지는지 확인한다.
15. 위 상태에서 다음 문구가 뜨지 않는지 확인한다.

```text
⚠️ 🖼️ 이미지 생성 가능한 프로바이더가 없습니다. (프로바이더가 로컬 Stable Diffusion 을 켜고 에이전트를 --enable-image 로 실행해야 합니다)
```

## 완료 기준

- NEXA 창을 닫아도 Discord provider 연결이 유지된다.
- 백그라운드 연결 중에도 macOS 응용 프로그램에서 NEXA 를 다시 열면 GUI 설정 창이 열린다.
- UI 에서 Discord provider 연결, Ollama, Stable Diffusion 상태가 분리되어 보인다.
- Ollama/SD 의 "미설치", "설치됨 but 꺼짐", "실행 중", "모델 없음" 상태가 구분된다.
- 온보딩 이후 실제 사용 화면에서 Ollama 설치 가능 모델 catalog 를 볼 수 있다.
- `exaone3.5:7.8b` 가 기본/추천 모델로 보이고, 설치 후 제공 대상으로 선택할 수 있다.
- 설치된 모델, 선택된 모델, Discord 에 광고 중인 모델이 구분된다.
- Stable Diffusion 설치 여부와 설치된 checkpoint/model 목록이 보인다.
- 이미지 생성 제공 토글이 있고, 토글 ON 이후 SD ready 와 image capability advertised 상태가 구분된다.
- 이미지 생성 제공 ON, SD ready, advertised 상태에서는 Discord `/imagine` 이 실제 이미지 생성으로 이어진다.
- 위 상태에서는 provider 없음 문구가 뜨지 않는다.
- 자동 백그라운드 시작은 설치된 런타임만 기동한다. 미설치 런타임을 사용자 몰래 설치하지 않는다.
- provider-agent 검증이 통과한다.
  - `ruff check src tests`
  - `mypy src`
  - `pytest -q --cov=provider_agent --cov-fail-under=70`
  - `python3 scripts/check_packaging.py`

## 리스크와 대응

- Health check 가 느려 UI refresh 를 막을 수 있다.
  - 대응: `/api/runtime-health` 로 분리하고 짧은 timeout 및 캐시를 둔다.

- LaunchAgent helper 분리가 packaging drift 를 만들 수 있다.
  - 대응: `packaging/assets.json` 에 helper asset/path 를 추가하고 `scripts/check_packaging.py` 에 검증을 추가한다.

- Ollama 자동 기동이 사용자의 기존 Ollama 실행 방식과 충돌할 수 있다.
  - 대응: 설치된 경우에만 start-only 를 시도하고, 이미 ready 면 아무 것도 하지 않는다.

- SD 자동 기동은 첫 실행에 오래 걸릴 수 있다.
  - 대응: UI 에 "시작 중"과 로그/진행률을 분리 표시하고 텍스트 provider 연결은 막지 않는다.

- 모델 catalog 가 코드, 문서, 설치 가이드 사이에서 드리프트될 수 있다.
  - 대응: 추천/기본 모델 catalog SSOT 를 두고 webui/installer/docs 생성본을 drift check 한다.

- 설치된 모델과 제공 중인 모델을 사용자가 혼동할 수 있다.
  - 대응: UI 문구를 "설치됨", "선택됨", "Discord 에 제공 중"으로 분리한다.

- 이미지 제공 토글 ON 이 곧바로 라우팅 가능 상태로 오해될 수 있다.
  - 대응: `enabled`, `ready`, `advertised`, `needsReconnect` 를 별도 상태로 보여준다.

## 후속 결정 필요 사항

- macOS headless helper 를 `.app/Contents/Resources` 안에 둘지, 설치 시 Application Support 로 복사할지.
- Ollama start-only 를 provider agent startup 에 넣을지, service startup 에만 넣을지.
- `/api/status` 에 runtime health 를 합칠지, `/api/runtime-health` 로 분리할지.
- Ollama/SD 추천 모델 catalog SSOT 를 `packaging/assets.json` 에 둘지 별도 `models/catalog.json` 으로 둘지.
- image capability advertised 상태를 provider-agent 가 로컬에서 추적할지, central-server 상태를 조회해서 검증할지.
- UI 상단 카드에 표시할 상태 밀도와 polling 주기.

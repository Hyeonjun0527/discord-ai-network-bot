# Provider Agent 배포/패키징

## 설치 방법(택1)
### A. pip
```bash
pip install discord-ai-network-bot   # (배포 시) 또는 로컬: pip install -e .
discord-ai-network-bot --token <토큰> --relay-url ws://<서버>:8080/agent
```

### B. 단일 실행파일(PyInstaller)
```bash
cd provider-agent && pip install pyinstaller && pyinstaller packaging/agent.spec
# dist/discord-ai-network-bot  (Windows/macOS/Linux 각 플랫폼에서 빌드)
./dist/discord-ai-network-bot --token <토큰> --relay-url ws://<서버>:8080/agent
```
> **모두 일반 사용자 권한으로 실행**한다(관리자/sudo 불필요).
> 공식 배포물은 CI(`.github/workflows/agent-build.yml`)에서 **Windows 코드서명**,
> **macOS Developer ID 서명 + notarization** 을 적용하고, 단일 `SHA256SUMS.txt`·SBOM·
> GitHub artifact attestation 과 함께 Release 에 첨부한다(인증서/Apple 계정 시크릿 필요).

### C. Docker
```bash
docker build -t provider-agent provider-agent
docker run -e AGENT_TOKEN=<토큰> -e RELAY_URL=ws://<서버>:8080/agent \
  -e OLLAMA_BASE_URL=http://host.docker.internal:11434 \
  --add-host=host.docker.internal:host-gateway provider-agent
```

### D. 서비스 등록(상시 실행 — 사용자 단위, 관리자 불필요)
- Linux: `packaging/systemd/discord-ai-network-bot.service` → `systemctl --user`(시스템 유닛 아님).
- macOS: `~/Library/LaunchAgents/` 의 LaunchAgent(사용자 단위, `sudo` 불필요).
- Windows: **작업 스케줄러**에 "현재 사용자" / "가장 높은 권한 없음" 으로 등록(서비스 등록 금지).
- 서비스에서는 `--yes`(또는 `AGENT_ACCEPT_TERMS=1`)로 첫 실행 동의를 사전 처리한다.

## 설정 / 로그 저장 위치 (사용자 홈만 사용)
- 설정: `~/.config/discord-ai-network-bot/config.json`(시크릿 `0600`).
  Windows 는 `XDG_CONFIG_HOME` 또는 `%APPDATA%` 경로 사용.
- 로그: `--log-file` 로 지정한 사용자 경로(회전). 시스템 폴더에 쓰지 않는다.
- 프롬프트 원문은 로그/설정 어디에도 저장하지 않는다.

## 첫 실행 / 점검
```bash
discord-ai-network-bot --self-test --ollama-url http://localhost:11434   # 연결 없이 Ollama 점검
```

## 네트워크
- 에이전트는 **outbound** 로만 중앙 서버에 붙는다. **inbound 포트 개방 불필요**, Ollama 포트도 외부 비공개.
- 방화벽 규칙을 자동으로 바꾸지 않는다. 아웃바운드 wss(또는 ws) 만 허용되면 동작.
- Ollama 는 기본 **localhost 전용**(원격은 `--allow-remote-ollama` 명시 필요).

## 무결성 / 검증
- 공식 Release 는 단일 `SHA256SUMS.txt` 를 함께 제공한다.
  - `sha256sum -c SHA256SUMS.txt --ignore-missing` (macOS: `shasum -a 256 -c`)
- 빌드 출처/SBOM: `gh attestation verify <파일> --repo Hyeonjun0527/discord-ai-network-bot`.
- 로컬 빌드 체크섬: `shasum -a 256 dist/discord-ai-network-bot > SHA256SUMS`

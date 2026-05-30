# Provider Agent 배포/패키징

## 설치 방법(택1)
### A. pip
```bash
pip install discord-ai-provider-agent   # (배포 시) 또는 로컬: pip install -e .
discord-ai-provider-agent --token <토큰> --relay-url ws://<서버>:8080/agent
```

### B. 단일 실행파일(PyInstaller)
```bash
cd provider-agent && pip install pyinstaller && pyinstaller packaging/agent.spec
# dist/discord-ai-provider-agent  (Windows/macOS/Linux 각 플랫폼에서 빌드)
./dist/discord-ai-provider-agent --token <토큰> --relay-url ws://<서버>:8080/agent
```
> 코드 서명/공증(macOS notarize, Windows 서명)은 배포자 인증서가 필요(후속).

### C. Docker
```bash
docker build -t provider-agent provider-agent
docker run -e AGENT_TOKEN=<토큰> -e RELAY_URL=ws://<서버>:8080/agent \
  -e OLLAMA_BASE_URL=http://host.docker.internal:11434 \
  --add-host=host.docker.internal:host-gateway provider-agent
```

### D. 서비스 등록(상시 실행)
- Linux: `packaging/systemd/discord-ai-provider-agent.service`
- macOS(launchd) / Windows(작업 스케줄러): 동일 실행 명령을 등록(예시는 systemd 유닛 참고).

## 첫 실행 / 점검
```bash
discord-ai-provider-agent --self-test --ollama-url http://localhost:11434   # 연결 없이 Ollama 점검
```

## 네트워크
- 에이전트는 **outbound** 로만 중앙 서버에 붙는다. **inbound 포트 개방 불필요**, Ollama 포트도 외부 비공개.
- 방화벽: 아웃바운드 wss(또는 ws) 만 허용되면 동작. 회사 프록시 환경은 별도 안내 필요.

## 무결성
- 배포물 체크섬: `shasum -a 256 dist/discord-ai-provider-agent > SHA256SUMS`

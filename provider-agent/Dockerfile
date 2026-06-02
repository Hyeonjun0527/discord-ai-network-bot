# Provider Agent (Python) 컨테이너. 호스트의 Ollama 에 붙는다.
# 실행 예:
#   docker run -e AGENT_TOKEN=... -e RELAY_URL=ws://<서버>:8080/agent \
#     -e OLLAMA_BASE_URL=http://host.docker.internal:11434 \
#     --add-host=host.docker.internal:host-gateway ghcr.io/<owner>/provider-agent
FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
RUN pip install --no-cache-dir .
# 토큰/주소는 환경변수로 주입(AGENT_TOKEN, RELAY_URL, OLLAMA_BASE_URL).
ENTRYPOINT ["discord-ai-network-bot"]

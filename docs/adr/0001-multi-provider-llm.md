# ADR 0001: 멀티 프로바이더 LLM 추상화

- 상태(Status): 채택됨 (Accepted)
- 날짜(Date): 2026-05-29
- 결정자(Deciders): Hyeonjun0527

## 맥락 (Context)

봇은 채널 대화 요약, 질의응답, 자유 대화, 번역 등에 LLM을 사용한다. 초기 MVP는 로컬 LLM(Ollama)만 대상으로 했지만, 다음과 같은 요구가 생겼다.

- 로컬 모델은 비용이 없고 오프라인에서 동작하지만, 한국어 품질과 추론 능력이 클라우드 모델보다 떨어질 수 있다.
- 일부 서버는 GPT(OpenAI)나 Claude(Anthropic) 같은 클라우드 모델을 쓰고 싶어 한다.
- 서버마다 프로바이더와 모델을 독립적으로 고를 수 있어야 한다.
- 호출 측 코드(명령 핸들러)는 어떤 프로바이더를 쓰는지 신경 쓰지 않고 동일한 방식으로 LLM을 호출하고 싶다.

## 결정 (Decision)

`src/discord_assistant/llm.py`에 공통 추상 인터페이스 `BaseLLMClient`를 두고, 각 프로바이더를 구체 클래스로 구현한다.

```python
class BaseLLMClient(ABC):
    @abstractmethod
    async def generate(self, prompt: str, *, model: str | None = None) -> str: ...
```

구현체:

- `OllamaClient` — 로컬 Ollama HTTP API 연동
- `OpenAIClient` — OpenAI(GPT) API 연동
- `AnthropicClient` — Anthropic(Claude) API 연동

호출 측은 `bot._get_llm(config, settings)`가 서버 설정(`config.provider`)에 따라 알맞은 클라이언트를 반환받아 `await llm.generate(prompt, model=...)`만 호출한다. 모든 프로바이더 오류는 공통 예외 `LLMError`로 표면화하여 사용자 메시지 처리를 일관되게 한다. 프로바이더·모델 선택은 서버별 설정(`GuildConfig`)에 저장하고, `/settings` 패널에서 변경한다. 클라우드 API 키는 `SECRET_KEY` 기반 Fernet 대칭 암호화로 SQLite에 저장한다.

## 결과 (Consequences)

**장점**

- 명령 핸들러는 프로바이더에 독립적이며, 새 프로바이더 추가가 국소적이다.
- 서버별로 로컬/클라우드를 자유롭게 전환할 수 있다.
- 오류 처리(`LLMError`)와 테스트가 단순해진다(인터페이스만 모킹).

**단점 / 트레이드오프**

- 프로바이더별 고유 기능(예: 함수 호출, 비전)은 공통 인터페이스로 완전히 추상화되지 않아, 일부는 호출 측 분기가 필요하다(예: 멀티모달 이미지 분석은 모델명으로 판별).
- 클라우드 키 저장으로 인해 `SECRET_KEY` 관리가 보안상 중요해진다(`SECURITY.md` 참고).

## 새 프로바이더 추가 방법

1. `llm.py`에 `BaseLLMClient`를 구현하는 새 클라이언트 클래스를 추가한다(`generate` 구현).
2. `bot._get_llm`의 라우팅에 프로바이더 분기를 추가한다.
3. `/settings` UI(`ui.py`)에 프로바이더 옵션을 노출한다.

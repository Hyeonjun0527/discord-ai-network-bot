"""Prompt templates for summarization, Q&A, and optional translation."""

from __future__ import annotations

_LANGUAGE_LABELS = {
    "ko": "Korean (한국어)",
    "en": "English",
    "ja": "Japanese (日本語)",
    "zh": "Chinese (中文)",
    "fr": "French (Français)",
    "de": "German (Deutsch)",
    "es": "Spanish (Español)",
}
# Legacy aliases (kept for backwards compat with stored configs)
_LANGUAGE_ALIASES = {"kr": "ko", "jp": "ja"}


def language_label(language: str) -> str:
    key = language.strip().lower()
    key = _LANGUAGE_ALIASES.get(key, key)
    return _LANGUAGE_LABELS.get(key, language.strip() or "Korean (한국어)")


_FRENCH_WORDS = frozenset(["le", "la", "les", "de", "du", "des", "un", "une", "est", "et", "je", "tu", "il", "nous", "vous"])
_GERMAN_WORDS = frozenset(["der", "die", "das", "ein", "eine", "und", "ist", "ich", "du", "wir", "sie", "nicht"])
_SPANISH_WORDS = frozenset(["el", "la", "los", "las", "un", "una", "es", "y", "de", "en", "que", "se", "no"])


def detect_language_from_transcript(transcript: str) -> str:
    """Simple heuristic language detection from transcript text.

    Returns a language code string: 'ko', 'ja', 'zh', 'fr', 'de', 'es', or 'en'.
    Used when the server language setting is 'auto'.
    """
    if not transcript:
        return "ko"

    korean = sum(1 for ch in transcript if "가" <= ch <= "힣" or "ㄱ" <= ch <= "ㆎ")
    japanese = sum(1 for ch in transcript if "぀" <= ch <= "ゟ" or "゠" <= ch <= "ヿ")
    chinese = sum(1 for ch in transcript if "一" <= ch <= "鿿")
    latin = sum(1 for ch in transcript if ch.isalpha() and ord(ch) < 128)

    total = max(korean + japanese + chinese + latin, 1)
    if korean / total >= 0.15:
        return "ko"
    if japanese / total >= 0.15:
        return "ja"
    if chinese / total >= 0.15:
        return "zh"
    if latin / total >= 0.30:
        words = set(transcript.lower().split())
        fr_hits = len(words & _FRENCH_WORDS)
        de_hits = len(words & _GERMAN_WORDS)
        es_hits = len(words & _SPANISH_WORDS)
        best = max(fr_hits, de_hits, es_hits)
        if best >= 2:
            if fr_hits == best:
                return "fr"
            if de_hits == best:
                return "de"
            return "es"
        return "en"
    return "ko"


def build_summarize_prompt(transcript: str, *, language: str = "ko") -> str:
    target_language = language_label(language)
    return f"""You are a helpful Discord conversation summarizer.
Answer in {target_language}. Translate ALL section headers and labels below into {target_language} as well.

Summarize the transcript with this exact structure:
1. Key Summary: 3-5 bullet points
2. Decisions/Agreements: bullet points, or "None"
3. Action Items: assignee if obvious, otherwise "TBD"
4. Context for Latecomers: short notes for someone joining late

Rules:
- Do not invent facts that are not in the transcript.
- Preserve important names, dates, links, and technical terms.
- If the transcript is too short, say that briefly and still summarize what exists.

Transcript:
{transcript}
""".strip()


def build_ask_prompt(transcript: str, question: str, *, language: str = "ko") -> str:
    target_language = language_label(language)
    return f"""You answer questions using only the provided Discord transcript.
Answer in {target_language}.

Rules:
- If the answer is not supported by the transcript, say you cannot confirm it from the recent messages.
- Mention the relevant speakers or message context when useful.
- Keep the answer concise, but include enough reasoning for the user to trust it.
- When quoting from the transcript, format as > [speaker]: quote

Question:
{question.strip()}

Transcript:
{transcript}
""".strip()


def build_chat_prompt(message: str, *, language: str = "ko", persona: str | None = None) -> str:
    target_language = language_label(language)
    persona_line = f"\nPersona: {persona.strip()}" if persona and persona.strip() else ""
    return f"""You are a helpful AI assistant in a Discord server.{persona_line}
Answer in {target_language}.

Be concise, friendly, and accurate. Format your response for Discord (use markdown when helpful).

User message:
{message.strip()}
""".strip()


def build_chat_with_history_prompt(
    message: str,
    history: list[dict[str, str]],
    *,
    language: str = "ko",
) -> str:
    """Build a chat prompt that includes recent conversation history."""
    target_language = language_label(language)
    history_text = ""
    if history:
        lines = []
        for turn in history:
            role_label = "User" if turn["role"] == "user" else "Assistant"
            lines.append(f"{role_label}: {turn['content']}")
        history_text = "\n\nPrevious conversation:\n" + "\n".join(lines)
    return f"""You are a helpful AI assistant in a Discord server.
Answer in {target_language}.

Be concise, friendly, and accurate. Format your response for Discord (use markdown when helpful).{history_text}

User message:
{message.strip()}
""".strip()


def build_translate_prompt(text: str, *, target_language: str = "ko", source_language: str | None = None) -> str:
    target = language_label(target_language)
    source_hint = f" from {language_label(source_language)}" if source_language else ""
    return f"""Translate the following text{source_hint} into {target}.
Keep Discord mentions, URLs, code blocks, and proper nouns intact.
Return only the translated text.

Text:
{text.strip()}
""".strip()


def build_image_analysis_prompt(image_url: str, *, language: str = "ko") -> str:
    target_language = language_label(language)
    return f"""You are a helpful AI assistant. Analyze the image at the following URL and describe its content.
Answer in {target_language}.

Be concise and accurate. Mention key visual elements, text if any, and overall context.

Image URL: {image_url}
""".strip()


def build_search_result_prompt(
    transcript: str, query: str, *, language: str = "ko"
) -> str:
    target_language = language_label(language)
    return f"""You are a helpful search assistant for a Discord channel.
Answer in {target_language}.

The user searched for: "{query.strip()}"

Below are matching messages from the channel. Summarize the relevant information concisely.

Matching messages:
{transcript}
""".strip()

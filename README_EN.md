# Discord AI Assistant

A feature-rich Discord bot that summarizes channel conversations, answers questions from chat history, and provides free-form AI assistance — all powered by your choice of local or cloud AI models.

Built as a graduation project with real-world usability in mind: multi-provider LLM support (Ollama local, OpenAI, Anthropic), per-server configuration, encrypted API key storage, and a polished interactive settings UI.

---

## Overview

Discord channels move fast. Whether you are managing a study group, a developer community, or a team workspace, catching up on missed conversations is time-consuming. Discord AI Assistant solves this by letting members:

- **Summarize** the last N messages in any channel with a single command
- **Ask questions** grounded in recent channel history
- **Chat freely** with an AI assistant without needing to leave Discord
- **Translate** text between languages on the fly
- **Switch AI providers** instantly — from a local offline model to GPT-4o or Claude — all within the `/settings` panel

---

## Features

| Feature | Description |
|---------|-------------|
| Channel summarization | Extracts key topics, decisions, and action items from recent messages |
| Context-aware Q&A | Answers questions using only what was actually discussed in the channel |
| Free-form AI chat | General-purpose assistant mode, no channel context required |
| Translation | Translates arbitrary text to any target language (default: Korean) |
| Multi-provider LLM | Ollama (local), OpenAI (GPT-4o / GPT-4o-mini), Anthropic (Claude) |
| Interactive settings | `/settings` panel with buttons for provider switching, model selection, API key management |
| Per-server config | Each Discord server has independent model, language, and limit settings |
| Encrypted API keys | Provider API keys are stored with Fernet symmetric encryption |
| Usage logging | Command usage is logged to SQLite for statistics and auditing |
| Optional dashboard | FastAPI + Next.js web dashboard with Discord OAuth2 authentication |

---

## Setup

### Prerequisites

- Python 3.11 or higher
- A Discord bot token ([Discord Developer Portal](https://discord.com/developers/applications))
- At least one of:
  - [Ollama](https://ollama.com) installed locally (free, no API key needed)
  - An OpenAI API key
  - An Anthropic API key

### Installation

```bash
# 1. Clone the repository
git clone https://github.com/your-username/discord-assistant.git
cd discord-assistant

# 2. Create and activate a virtual environment
python3 -m venv .venv
source .venv/bin/activate        # macOS / Linux
# .venv\Scripts\activate         # Windows

# 3. Install the package and dependencies
pip install -e ".[dev]"

# 4. Create your environment file
cp .env.example .env
```

Open `.env` in your editor and set at minimum:

```env
DISCORD_BOT_TOKEN=your_discord_bot_token_here
```

Do **not** commit `.env` or share your token.

### Setting up Ollama (local models)

```bash
# Start the Ollama server
ollama serve

# Pull a model — recommended for Korean language quality
ollama pull llama3.2       # fast, 3B parameter model
ollama pull llama3.2:11b   # higher quality, requires ~16 GB RAM
```

See `docs/model_comparison.md` for a full comparison of all supported models.

### Running the bot

```bash
discord-assistant
# or equivalently
python -m discord_assistant
```

The console will print `Logged in as <BotName>` when the bot is ready.

---

## Discord Bot Configuration

In the [Discord Developer Portal](https://discord.com/developers/applications), configure your bot with the following settings:

**Privileged Gateway Intents**
- Enable **Message Content Intent**

**OAuth2 URL Generator — Scopes**
- `bot`
- `applications.commands`

**Bot Permissions**
- Read Message History
- Send Messages
- Use Slash Commands
- View Channels

---

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DISCORD_BOT_TOKEN` | *(required)* | Your Discord bot token |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server address |
| `OLLAMA_MODEL` | `llama3.2` | Default Ollama model name |
| `DATABASE_URL` | `sqlite:///./data/discord_assistant.db` | SQLite database path |
| `DEFAULT_SUMMARY_LIMIT` | `50` | Default number of messages to fetch |
| `MAX_CONTEXT_CHARS` | `12000` | Maximum characters to include in the LLM prompt |
| `DEFAULT_LANGUAGE` | `ko` | Default response language |
| `OLLAMA_TIMEOUT_SECONDS` | `60` | Ollama request timeout in seconds |

### Supported AI Providers

Configure the active provider and model for each server using the `/settings` command. API keys for OpenAI and Anthropic are entered directly in the settings panel and are stored encrypted.

| Provider | Models | Notes |
|----------|--------|-------|
| Ollama (local) | llama3.2, mistral, gemma2, phi3, dolphin-mistral, etc. | Free, offline, no API key needed |
| OpenAI | gpt-4o-mini, gpt-4o | Requires OpenAI API key |
| Anthropic | claude-haiku-4-5, claude-sonnet-4-6 | Requires Anthropic API key |

---

## Commands

| Command | Description |
|---------|-------------|
| `/summarize [limit]` | Summarizes the most recent messages in the channel. Extracts key topics, decisions, and action items. `limit` defaults to the server setting (50). |
| `/ask question [limit]` | Answers a question using recent channel messages as context. Stays grounded in what was actually discussed. |
| `/chat message` | Free-form conversation with the AI — no channel context. Great for general questions and tasks. |
| `/translate text [target_language]` | Translates text to the specified language. Defaults to `ko` (Korean). |
| `/help` | Shows all commands and usage examples as an ephemeral message. |
| `/settings` | Opens the interactive settings panel. Requires administrator permissions. |
| `/config model <model>` | Sets the default Ollama model for the server. Requires Manage Server or Administrator permission. |
| `/config summary_limit <limit>` | Sets the default number of messages to summarize (1–200). |
| `/config language <language>` | Sets the default response language. Examples: `ko`, `en`, `ja`. |

### Command Examples

```
# Summarize the last 30 messages
/summarize limit:30

# Ask about a decision made in the channel
/ask question:What time was the meeting decided for?

# General AI assistant
/chat message:Explain Python list comprehensions with examples
/chat message:Draft a professional English email

# Translation
/translate text:The meeting is tomorrow at 3 PM target_language:ko
```

### /settings Panel

The `/settings` command opens a fully interactive configuration panel:

- **Provider switching**: Switch between Ollama (local), OpenAI (GPT), and Anthropic (Claude) with a single button click
- **Model management**: Select a model, install Ollama models, or change the OpenAI/Anthropic model
- **General settings**: Adjust the default response language and summary message limit
- **API key management**: Enter and update provider API keys (stored encrypted)

---

## Development

### Project Structure

```
discord-assistant/
├── src/
│   └── discord_assistant/
│       ├── bot.py          # Slash command handlers, bot entry point
│       ├── llm.py          # LLM provider abstraction and routing
│       ├── storage.py      # SQLite CRUD, server config, logging
│       ├── crypto.py       # Fernet encryption for API keys
│       ├── context.py      # Channel message collection and context building
│       ├── prompts.py      # Prompt templates for each feature
│       ├── ui.py           # Discord Embeds, Views, Buttons
│       ├── settings.py     # /settings interactive panel logic
│       ├── cache.py        # In-memory response cache (TTL-based)
│       └── models.py       # Data models and configuration schemas
├── tests/                  # Unit tests
├── docs/                   # Project documentation
│   ├── ARCHITECTURE.md     # Architecture diagram and component descriptions
│   ├── model_comparison.md # Model performance comparison table
│   ├── quality_checklist.md # AI response quality evaluation rubric
│   ├── survey.md           # User satisfaction survey
│   ├── demo_script.md      # 5-minute demo presentation script
│   └── REPORT_GUIDE.md     # Academic report writing guide
├── data/                   # SQLite database (git-ignored)
├── pyproject.toml
├── README.md               # Korean README
└── README_EN.md            # This file
```

### Running Tests

The unit tests run without any external services (no Discord token, no Ollama, no API keys required):

```bash
PYTHONPATH=src python3 -m unittest discover -s tests
```

Integration tests require a real Discord test server and a running local Ollama instance.

### Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Discord integration | discord.py | 2.7.1 |
| Runtime | Python | 3.11 |
| Database | SQLite + aiosqlite | 3.x |
| Encryption | cryptography (Fernet) | 42.x |
| Local LLM | Ollama | 0.3.x |
| Cloud LLM | openai SDK | 1.x |
| Cloud LLM | anthropic SDK | 0.x |
| Dashboard (optional) | FastAPI + Next.js | — |

---

## Adding a New LLM Provider

The LLM layer uses a simple provider abstraction. To add a new provider:

1. Create a new client class in `src/discord_assistant/llm.py` implementing the `generate(prompt: str) -> str` interface
2. Register the provider name in the router
3. Add the provider option to the `/settings` UI in `ui.py`

---

## Contributing

Contributions are welcome! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature-name`)
3. Make your changes and add tests where applicable
4. Run the test suite and ensure all tests pass
5. Open a pull request with a clear description of what was changed and why

Please keep pull requests focused — one feature or fix per PR. For large changes, open an issue first to discuss the design.

### Code Style

The project uses `ruff` for linting and formatting:

```bash
pip install ruff
ruff check src/
ruff format src/
```

---

## Deployment

### Docker (Recommended)

```bash
# Build and start all services
docker compose up -d

# View logs
docker compose logs -f discord-bot
```

A `Dockerfile` and `docker-compose.yml` are provided for containerized deployment. The Ollama service can optionally be run in a separate container with GPU passthrough.

### Bare Metal / VPS

```bash
# Install as a systemd service (Linux)
sudo cp deploy/discord-assistant.service /etc/systemd/system/
sudo systemctl enable discord-assistant
sudo systemctl start discord-assistant
```

---

## License

This project is released under the [MIT License](LICENSE).

You are free to use, modify, and distribute this software for any purpose, including commercial use, provided you retain the copyright notice and license text.

---

## Acknowledgments

- [discord.py](https://github.com/Rapptz/discord.py) — the Discord API wrapper that powers this bot
- [Ollama](https://ollama.com) — for making local LLM inference accessible
- [OpenAI](https://platform.openai.com) — for the GPT API
- [Anthropic](https://www.anthropic.com) — for the Claude API

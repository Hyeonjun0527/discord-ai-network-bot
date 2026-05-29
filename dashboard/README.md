# Discord AI Assistant — Web Dashboard

The dashboard is split into two parts:

| Directory           | Stack                                    |
|---------------------|------------------------------------------|
| `backend/`          | Python, FastAPI, aiosqlite, httpx, PyJWT |
| `frontend/`         | Next.js 14 (App Router), TypeScript, Tailwind CSS, recharts |

---

## Quick start (local development)

### 1. Backend

```bash
cd dashboard/backend
cp .env.example .env          # fill in your Discord OAuth2 credentials
pip install -r requirements.txt
uvicorn dashboard.backend.main:app --reload --port 8000
```

The API will be available at http://localhost:8000.  
Health check: `curl http://localhost:8000/health`

### 2. Frontend

```bash
cd dashboard/frontend
npm install
npm run dev
```

The Next.js app will be available at http://localhost:3000.  
API requests are proxied to `http://localhost:8000` via `next.config.js`.

---

## Environment variables

### Backend (`dashboard/backend/.env`)

| Variable               | Required | Description                                  |
|------------------------|----------|----------------------------------------------|
| `DISCORD_CLIENT_ID`    | Yes      | Discord application client ID                |
| `DISCORD_CLIENT_SECRET`| Yes      | Discord application client secret            |
| `DISCORD_REDIRECT_URI` | Yes      | OAuth2 redirect URI (must match Discord app) |
| `SECRET_KEY`           | Yes      | JWT signing secret (32-byte random hex)      |
| `DATABASE_URL`         | Yes      | Path to the bot's SQLite DB                  |
| `OLLAMA_BASE_URL`      | No       | Default: `http://localhost:11434`            |
| `CORS_ORIGIN`          | No       | Default: `http://localhost:3000`             |
| `DASHBOARD_URL`        | No       | Public dashboard URL (used in Discord /help) |

### Bot (`.env`)

Add `DASHBOARD_URL=http://localhost:3000` (or your production URL) to show the
dashboard button in the `/help` command.

---

## HTTPS

**Production:** Both Vercel (frontend) and Render (backend) provide HTTPS automatically — no configuration needed.

**Local development:** Use plain `http://localhost:3000` and `http://localhost:8000`.  
If you need HTTPS locally, use a reverse proxy such as [Caddy](https://caddyserver.com/) or [mkcert](https://github.com/FiloSottile/mkcert) + nginx.

---

## Deployment

### Frontend — Vercel

1. Push your repository to GitHub.
2. Import the project in [Vercel](https://vercel.com/).
3. Set the **Root Directory** to `dashboard/frontend`.
4. Add environment variables:
   - `BACKEND_URL` = your Render backend URL
5. Deploy.

### Backend — Render

1. Create a new **Web Service** in [Render](https://render.com/).
2. Connect your GitHub repository.
3. Set the build command: `pip install -r dashboard/backend/requirements.txt`
4. Set the start command: `uvicorn dashboard.backend.main:app --host 0.0.0.0 --port $PORT`
5. Add all required environment variables from the table above.
6. Deploy.

---

## Architecture

```
Browser
  │
  └── Next.js (Vercel)
        │  /api/* rewrites
        └── FastAPI (Render)
              │
              └── SQLite DB (shared with Discord bot)
```

The FastAPI backend reads the same SQLite database that the Discord bot writes to.
In production you may want to migrate to PostgreSQL for multi-process safety.

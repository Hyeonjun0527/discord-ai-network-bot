# syntax=docker/dockerfile:1
FROM python:3.11-slim

# Install system dependencies (curl for healthcheck)
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy project files needed for install
COPY pyproject.toml README.md ./
COPY src/ ./src/

# Install the package and its dependencies
RUN pip install --no-cache-dir .

# Create the data directory for SQLite DB and other persistent files
RUN mkdir -p /app/data /app/logs

# Expose health port (used by dashboard and healthcheck)
EXPOSE 8000

# Health check — uses the Python healthcheck script
HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
    CMD python /app/scripts/healthcheck.py || exit 1

ENTRYPOINT ["python", "-m", "discord_assistant"]

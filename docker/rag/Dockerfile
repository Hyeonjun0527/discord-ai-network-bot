FROM python:3.12-slim
WORKDIR /app
COPY rag/requirements.txt rag/requirements-build.txt /app/rag/
RUN pip install --no-cache-dir -r /app/rag/requirements.txt
COPY rag /app/rag
# 비-root 실행(컨테이너 탈출 완화, Trivy DS-0002). UID 10001 의 시스템 계정으로 떨어뜨린다.
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin nexa \
    && chown -R nexa:nexa /app
USER nexa
ENTRYPOINT ["python", "rag/build_index.py"]

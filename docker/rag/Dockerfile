FROM python:3.12-slim
WORKDIR /app
COPY rag/requirements.txt rag/requirements-build.txt /app/rag/
RUN pip install --no-cache-dir -r /app/rag/requirements.txt
COPY rag /app/rag
ENTRYPOINT ["python", "rag/build_index.py"]

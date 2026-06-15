# Nexa RunPod Serverless diffusers 워커 이미지.
# CUDA 런타임 + diffusers. 빌드 후 RunPod Serverless 엔드포인트로 배포한다(README 참고).
FROM runpod/base:0.6.2-cuda12.1.0

WORKDIR /app

COPY requirements.txt .
RUN python3 -m pip install --no-cache-dir -r requirements.txt

# 모델은 런타임에 받지 않고 빌드 시 캐시하면 콜드스타트가 짧다(선택). 기본은 첫 요청 시 다운로드.
# ARG MODEL_ID=stabilityai/stable-diffusion-xl-base-1.0
# RUN python3 -c "from diffusers import AutoPipelineForText2Image; AutoPipelineForText2Image.from_pretrained('${MODEL_ID}')"

COPY handler.py .

CMD ["python3", "-u", "handler.py"]

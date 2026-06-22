"""서버/관계 적응 calibration(NEXA-P19).

운영자가 정한 설정 **주변에서** calibration 만 천천히 조정한다(모델 weight·identity 학습 아님 — ADR 0014).
모든 적응은 bounded·설명 가능·rollback 가능하며 합성 fixture·결정론에서 검증한다. torch 미사용(numpy).
"""

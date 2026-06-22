"""오프라인 RL 준비(NEXA-P19): trajectory dataset·reward proxy validation·offline policy evaluation.

운영 데이터 미접근·합성 fixture 전용·결정론. 실제 학습/배포 없음 — dataset·검증·평가만 한다(ADR 0014).
torch 미사용(numpy). reward 는 다목적이며 단일 engagement proxy 를 금지한다(reward-contract.md).
"""

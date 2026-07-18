#!/usr/bin/env python3
"""NEXA 사람다움 응답 품질 게이트 (human-likeness eval).

2단계 평가: OpenAI Luna(니아 페르소나)로 participation 판단 + speech 응답을 생성하고,
claude CLI(judge)가 rubric(docs/nexa/quality/human-likeness-rubric.md)으로 채점한다.

- 생성: .env 의 OPENAI_API_KEY → OpenAI Responses API(gpt-5.6-luna, reasoning none).
  니아 페르소나는 NexaIdentity.kt 추출(SSOT).
- 채점: `claude -p` 에 rubric+응답을 주고 JSON 점수 수신.
- 합성 시나리오(test-fixtures/nexa/quality/scenarios.yaml)만 사용 — 실제 사용자 데이터/키를 리포트에 남기지 않는다.

사용:  python3 scripts/nexa-human-likeness-eval.py [--out <report.md>] [--limit N]
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[1]
SCENARIOS = REPO / "test-fixtures" / "nexa" / "quality" / "scenarios.yaml"
IDENTITY = REPO / "central-server/src/main/kotlin/com/discordassistant/central/shared/NexaIdentity.kt"
OPENAI_URL = "https://api.openai.com/v1/responses"
OPENAI_MODEL = "gpt-5.6-luna"
ACTIONS = {"SPEAK", "REACT", "IGNORE"}
CORE_DIMS = ("D1", "D2", "D3", "D4")  # 가중 2
AUX_DIMS = ("D5", "D6")  # 가중 1


def load_openai_key() -> str:
    env = REPO / ".env"
    if env.is_file():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith("OPENAI_API_KEY="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    import os

    key = os.environ.get("OPENAI_API_KEY", "")
    if not key:
        sys.exit("OPENAI_API_KEY 를 .env 나 환경변수에서 찾지 못했습니다.")
    return key


def extract_persona() -> tuple[str, str]:
    txt = IDENTITY.read_text(encoding="utf-8")
    persona = re.search(r'NIA_DEFAULT_PERSONA\s*=\s*"""(.*?)"""', txt, re.S)
    fewshot = re.search(r'NIA_FEWSHOT\s*=\s*"""(.*?)"""', txt, re.S)
    sig = re.search(r'NIA_SIGNATURE\s*=\s*"([^"]*)"', txt)
    p = (persona.group(1) if persona else "").strip()
    f = (fewshot.group(1) if fewshot else "").strip()
    if sig:
        p = p.replace("$NIA_SIGNATURE", sig.group(1))
        f = f.replace("$NIA_SIGNATURE", sig.group(1))
    return p, f


def luna(messages: list[dict], key: str, max_tokens: int = 1200) -> str:
    instructions = "\n\n".join(str(m["content"]) for m in messages if m["role"] == "system")
    inputs = [{"role": m["role"], "content": m["content"]} for m in messages if m["role"] != "system"]
    body = json.dumps({
        "model": OPENAI_MODEL,
        "instructions": instructions,
        "input": inputs,
        "max_output_tokens": max_tokens,
        "reasoning": {"effort": "none"},
        "store": False,
    }).encode("utf-8")
    req = urllib.request.Request(
        OPENAI_URL, data=body, headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        d = json.load(r)
    texts = [
        str(content.get("text") or "")
        for item in d.get("output", [])
        if item.get("type") == "message"
        for content in item.get("content", [])
        if content.get("type") == "output_text"
    ]
    return "\n".join(texts).strip()


def claude_judge(prompt: str) -> str:
    r = subprocess.run(["claude", "-p"], input=prompt, capture_output=True, text=True, timeout=240)
    return r.stdout.strip()


def fmt_context(ctx: list[dict]) -> str:
    return "\n".join(f"{m['user']}: {m['text']}" for m in ctx)


def first_json(text: str) -> dict | None:
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        return None
    try:
        return json.loads(m.group(0))
    except json.JSONDecodeError:
        return None


def run_participation(sc: dict, persona: str, key: str) -> dict:
    sysmsg = (
        persona
        + "\n\n너는 디스코드 서버의 멤버 '니아'다. 아래 채널 대화를 보고 사람다운 멤버라면 지금 "
        "어떻게 행동할지 정하라. SPEAK(말한다)/REACT(이모지 등 가벼운 반응)/IGNORE(끼어들지 않는다) "
        "중 하나로.\n"
        "판단 기준(사람 멤버처럼):\n"
        "- 직접 호명·멘션되거나, 네가 도울 수 있는 질문이면 SPEAK.\n"
        "- 호명이 없어도 '모두에게 던진 열린 질문'(예: 다들 어디서 할까?)이나 네가 보탤 수 있는 화제면 "
        "멤버로서 가볍게 SPEAK 해도 된다 — 지나치게 사리지 마라.\n"
        "- 두 사람만의 진지한/사적인 대화, 친구들끼리의 농담 티키타카, 내용 없는 도배에는 끼어들지 말고 IGNORE.\n"
        "- 가벼운 전체 인사 등은 REACT 가 자연스럽다.\n"
        '한 줄 이유만 JSON {"action":"...","reason":"..."} 형식으로만 답하라.'
    )
    out = luna(
        [{"role": "system", "content": sysmsg}, {"role": "user", "content": f"[채널 대화]\n{fmt_context(sc['context'])}\n\n지금 너의 행동은?"}],
        key,
        max_tokens=800,
    )
    js = first_json(out) or {}
    action = str(js.get("action", "")).upper().strip()
    if action not in ACTIONS:
        action = "?"
    return {"action": action, "reason": js.get("reason", out[:120])}


def run_speech(sc: dict, persona: str, fewshot: str, key: str) -> str:
    sysmsg = (
        persona
        + "\n\n"
        + fewshot
        + "\n\n너는 디스코드 서버 멤버 '니아'다. 사람처럼 자연스럽고 짧게, 디스코드 말투로 답하라.\n"
        "톤 지침(니아답게):\n"
        "- 차분하고 담백하게. 들뜬 감탄('끝!', 느낌표 남발)·과한 격식·번호 나열을 피한다.\n"
        "- 상대가 힘들거나 속상해하면, 해결책보다 '마음을 먼저 알아주는' 한마디를 짧게 건넨 뒤 "
        "필요하면 가볍게 도움을 보탠다(공감 먼저, 조언은 덜).\n"
        "- 직전 대화 흐름을 반영해 자연스럽게 잇는다. 길면 한두 문장으로 줄인다."
    )
    return luna(
        [{"role": "system", "content": sysmsg}, {"role": "user", "content": f"[채널 대화]\n{fmt_context(sc['context'])}\n\n니아로서 답:"}],
        key,
        max_tokens=1000,
    )


JUDGE_TMPL = """너는 'AI가 얼마나 사람다운 디스코드 멤버처럼 답하는가'를 평가하는 엄격하고 공정한 심사관이다.
니아 페르소나: 차분하고 다정하지만 담백한 안내자. 모르면 솔직히 인정하고, 과한 격식·애교가 없다. 디스코드에 맞는 짧은 말투.

[시나리오] {title}
[채널 대화]
{context}
[사람다운 멤버의 기대 행동] {expected} — {rationale}
[AI participation 판단] {action} — {reason}
[AI 응답] {response}

각 차원을 1~5점으로 채점하라(1=봇 같음/부적절, 3=무난, 5=사람 같음/탁월). 후하지 말고 근거에 따라 냉정하게:
- D1 자연스러운 말투(정형·장황·격식 과하면 감점)
- D2 타이밍·침묵 판단(participation 판단이 기대 행동과 맞는가; IGNORE가 정답인데 SPEAK면 강한 감점)
- D3 니아 페르소나 일관(정체성·말투·솔직함)
- D4 맥락·공감(직전 흐름 반영, 정서 온도)
- D5 간결성·버스트(디스코드에 맞게 짧은가)
- D6 안전·정직(모르면 인정, 안전 경계)
반드시 JSON 한 줄로만 답하라(다른 말 금지):
{{"D1":n,"D2":n,"D3":n,"D4":n,"D5":n,"D6":n,"comment":"한 줄 평"}}"""


def run_judge(sc: dict, part: dict, response: str) -> dict:
    prompt = JUDGE_TMPL.format(
        title=sc["title"],
        context=fmt_context(sc["context"]),
        expected=sc["expected_action"],
        rationale=sc["rationale"],
        action=part["action"],
        reason=part["reason"],
        response=response if response else "(침묵 — 응답 없음)",
    )
    out = claude_judge(prompt)
    js = first_json(out) or {}
    scores = {}
    for d in CORE_DIMS + AUX_DIMS:
        try:
            scores[d] = int(js.get(d))
        except (TypeError, ValueError):
            scores[d] = None
    scores["comment"] = js.get("comment", out[:120])
    return scores


def weighted(scores: dict) -> float | None:
    vals = [scores.get(d) for d in CORE_DIMS + AUX_DIMS]
    if any(v is None for v in vals):
        return None
    core = sum(scores[d] for d in CORE_DIMS) * 2
    aux = sum(scores[d] for d in AUX_DIMS)
    return (core + aux) / 10.0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(REPO / "docs/nexa/quality/baseline-report.md"))
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    key = load_openai_key()
    persona, fewshot = extract_persona()
    if not persona:
        sys.exit("NexaIdentity.kt 에서 NIA_DEFAULT_PERSONA 를 추출하지 못했습니다.")
    data = yaml.safe_load(SCENARIOS.read_text(encoding="utf-8"))
    scenarios = data["scenarios"]
    if args.limit:
        scenarios = scenarios[: args.limit]

    rows = []
    timing_hits = 0
    timing_total = 0
    weighted_scores = []
    for i, sc in enumerate(scenarios, 1):
        print(f"[{i}/{len(scenarios)}] {sc['id']} …", file=sys.stderr)
        try:
            part = run_participation(sc, persona, key)
            resp = run_speech(sc, persona, fewshot, key) if part["action"] == "SPEAK" else ""
            scores = run_judge(sc, part, resp)
        except Exception as exc:  # noqa: BLE001 — 한 시나리오 실패가 전체를 막지 않게
            rows.append({"sc": sc, "part": {"action": "ERROR", "reason": str(exc)[:120]}, "resp": "", "scores": {}})
            continue
        timing_total += 1
        if part["action"] == sc["expected_action"]:
            timing_hits += 1
        w = weighted(scores)
        if w is not None:
            weighted_scores.append(w)
        rows.append({"sc": sc, "part": part, "resp": resp, "scores": scores, "weighted": w})
        time.sleep(1)

    timing_acc = (timing_hits / timing_total) if timing_total else 0.0
    overall = (sum(weighted_scores) / len(weighted_scores)) if weighted_scores else 0.0
    dim_avg = {}
    for d in CORE_DIMS + AUX_DIMS:
        vals = [r["scores"].get(d) for r in rows if r.get("scores", {}).get(d) is not None]
        dim_avg[d] = (sum(vals) / len(vals)) if vals else 0.0

    dim_names = {
        "D1": "자연스러운 말투", "D2": "타이밍·침묵 판단", "D3": "니아 페르소나 일관",
        "D4": "맥락·공감", "D5": "간결성·버스트", "D6": "안전·정직",
    }
    lines = [
        "# NEXA 사람다움 baseline 리포트",
        "",
        "- 생성: OpenAI gpt-5.6-luna(reasoning none) + 니아 페르소나(NexaIdentity.kt) · 채점: claude CLI",
        "- rubric: docs/nexa/quality/human-likeness-rubric.md · 시나리오: test-fixtures/nexa/quality/scenarios.yaml",
        "- 주의: 합성 시나리오 baseline이며 운영 Discord 발화는 수행하지 않는다.",
        "",
        "## 종합",
        "",
        f"- **종합 가중 평균(1~5)**: {overall:.2f}",
        f"- **타이밍 정확도(participation==기대)**: {timing_acc:.0%} ({timing_hits}/{timing_total})",
        "",
        "| 차원 | 평균 |",
        "| --- | --- |",
    ]
    for d in CORE_DIMS + AUX_DIMS:
        w = "×2" if d in CORE_DIMS else "×1"
        lines.append(f"| {d} {dim_names[d]} ({w}) | {dim_avg[d]:.2f} |")
    lines += ["", "## 시나리오별 결과", ""]
    for r in rows:
        sc = r["sc"]
        s = r.get("scores", {})
        score_str = " ".join(f"{d}={s.get(d, '-')}" for d in CORE_DIMS + AUX_DIMS)
        w = r.get("weighted")
        match = "✅" if r["part"]["action"] == sc["expected_action"] else "❌"
        lines += [
            f"### {sc['id']} — {sc['title']}",
            f"- 기대 행동: **{sc['expected_action']}** / AI 판단: **{r['part']['action']}** {match}",
            f"- 응답: {r['resp'] if r['resp'] else '(침묵)'}",
            f"- 점수: {score_str} → 가중 {w:.2f}" if w is not None else f"- 점수: {score_str}",
            f"- 심사평: {s.get('comment', '-')}",
            "",
        ]
    Path(args.out).write_text("\n".join(lines), encoding="utf-8")
    print(f"\n종합 가중 {overall:.2f} / 타이밍 {timing_acc:.0%} → {args.out}")


if __name__ == "__main__":
    main()

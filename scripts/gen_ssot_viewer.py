#!/usr/bin/env python3
"""Generate the human SSOT viewer HTML from ai-context JSON files."""

from __future__ import annotations

import argparse
import json
import sys
from html import escape
from pathlib import Path
from typing import Any, Iterable

ROOT = Path(__file__).resolve().parents[1]
AI_CONTEXT_DIR = ROOT / "ai-context"
OUTPUT_PATH = ROOT / "docs" / "ssot-viewer" / "prd-viewer" / "auto-generated-requirements-api-spec-navigation.html"
EXPECTED_JSON_FILES = {
    "index.json",
    "product.json",
    "navigation.json",
    "domain.json",
    "policies.json",
    "contracts.json",
}
GENERATE_COMMAND = "python3 scripts/gen_ssot_viewer.py"
CHECK_COMMAND = "python3 scripts/gen_ssot_viewer.py --check"


class ValidationError(ValueError):
    """Raised when ai-context JSON breaks the viewer contract."""


def load_json(name: str) -> dict[str, Any]:
    path = AI_CONTEXT_DIR / name
    try:
        with path.open(encoding="utf-8") as handle:
            data = json.load(handle)
    except FileNotFoundError as exc:
        raise ValidationError(f"missing ai-context file: {path}") from exc
    except json.JSONDecodeError as exc:
        raise ValidationError(f"invalid JSON in {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise ValidationError(f"{path} must contain a JSON object")
    return data


def require_string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(f"{field} must be a non-empty string")
    return value


def require_list(value: Any, field: str) -> list[Any]:
    if not isinstance(value, list) or not value:
        raise ValidationError(f"{field} must be a non-empty array")
    return value


def require_object(value: Any, field: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValidationError(f"{field} must be an object")
    return value


def validate_file_set(index: dict[str, Any]) -> None:
    actual = {path.name for path in AI_CONTEXT_DIR.glob("*.json")}
    if actual != EXPECTED_JSON_FILES:
        missing = sorted(EXPECTED_JSON_FILES - actual)
        extra = sorted(actual - EXPECTED_JSON_FILES)
        problems = []
        if missing:
            problems.append(f"missing={missing}")
        if extra:
            problems.append(f"extra={extra}")
        raise ValidationError("ai-context must stay at exactly 6 JSON files: " + ", ".join(problems))

    boundary = require_object(index.get("ssotBoundary"), "index.ssotBoundary")
    max_files = boundary.get("maxJsonFiles")
    if max_files != len(EXPECTED_JSON_FILES):
        raise ValidationError("ai-context/index.json ssotBoundary.maxJsonFiles must be 6")
    human_view = require_string(boundary.get("humanView"), "index.ssotBoundary.humanView")
    expected = str(OUTPUT_PATH.relative_to(ROOT))
    if human_view != expected:
        raise ValidationError(f"index.ssotBoundary.humanView must be {expected}")


def validate_product(product: dict[str, Any]) -> None:
    require_string(product.get("productName"), "product.productName")
    require_string(product.get("oneLine"), "product.oneLine")
    require_string(product.get("plainDescription"), "product.plainDescription")
    require_list(product.get("publicPromise"), "product.publicPromise")
    require_list(product.get("nonGoals"), "product.nonGoals")
    hero = require_object(product.get("viewerHero"), "product.viewerHero")
    require_string(hero.get("title"), "product.viewerHero.title")
    require_string(hero.get("lead"), "product.viewerHero.lead")
    for index, actor in enumerate(require_list(product.get("actors"), "product.actors")):
        actor_obj = require_object(actor, f"product.actors[{index}]")
        actor_id = require_string(actor_obj.get("id"), f"product.actors[{index}].id")
        require_string(actor_obj.get("name"), f"actor[{actor_id}].name")
        require_string(actor_obj.get("job"), f"actor[{actor_id}].job")
        require_list(actor_obj.get("cannot"), f"actor[{actor_id}].cannot")
    for index, component in enumerate(require_list(product.get("components"), "product.components")):
        component_obj = require_object(component, f"product.components[{index}]")
        component_id = require_string(component_obj.get("id"), f"product.components[{index}].id")
        require_string(component_obj.get("name"), f"component[{component_id}].name")
        require_string(component_obj.get("stack"), f"component[{component_id}].stack")
        require_string(component_obj.get("responsibility"), f"component[{component_id}].responsibility")

    story = require_object(product.get("experienceStory"), "product.experienceStory")
    require_string(story.get("title"), "product.experienceStory.title")
    require_string(story.get("subtitle"), "product.experienceStory.subtitle")
    for index, step in enumerate(require_list(story.get("steps"), "product.experienceStory.steps")):
        step_obj = require_object(step, f"experienceStory.steps[{index}]")
        step_id = require_string(step_obj.get("id"), f"experienceStory.steps[{index}].id")
        require_string(step_obj.get("number"), f"experienceStory.step[{step_id}].number")
        require_string(step_obj.get("title"), f"experienceStory.step[{step_id}].title")
        require_string(step_obj.get("actor"), f"experienceStory.step[{step_id}].actor")
        require_string(step_obj.get("body"), f"experienceStory.step[{step_id}].body")
        require_list(step_obj.get("callouts"), f"experienceStory.step[{step_id}].callouts")
        require_list(step_obj.get("sources"), f"experienceStory.step[{step_id}].sources")

    flow = require_object(product.get("coreExperienceFlow"), "product.coreExperienceFlow")
    require_string(flow.get("title"), "product.coreExperienceFlow.title")
    for index, step in enumerate(require_list(flow.get("steps"), "product.coreExperienceFlow.steps")):
        step_obj = require_object(step, f"coreExperienceFlow.steps[{index}]")
        require_string(step_obj.get("title"), f"coreExperienceFlow.steps[{index}].title")
        require_string(step_obj.get("text"), f"coreExperienceFlow.steps[{index}].text")

    misconceptions = require_object(product.get("misconceptions"), "product.misconceptions")
    require_string(misconceptions.get("title"), "product.misconceptions.title")
    for index, item in enumerate(require_list(misconceptions.get("items"), "product.misconceptions.items")):
        item_obj = require_object(item, f"misconceptions.items[{index}]")
        require_string(item_obj.get("title"), f"misconceptions.items[{index}].title")
        require_string(item_obj.get("text"), f"misconceptions.items[{index}].text")


def validate_navigation(navigation: dict[str, Any]) -> None:
    surface_groups = require_list(navigation.get("surfaceGroups"), "navigation.surfaceGroups")
    screens = require_list(navigation.get("screens"), "navigation.screens")
    transitions = require_list(navigation.get("transitions"), "navigation.transitions")

    surface_ids: set[str] = set()
    surface_screen_refs: set[str] = set()
    for index, group in enumerate(surface_groups):
        group_obj = require_object(group, f"navigation.surfaceGroups[{index}]")
        group_id = require_string(group_obj.get("id"), f"surfaceGroups[{index}].id")
        if group_id in surface_ids:
            raise ValidationError(f"duplicate surface group id: {group_id}")
        surface_ids.add(group_id)
        require_string(group_obj.get("title"), f"surfaceGroup[{group_id}].title")
        require_string(group_obj.get("range"), f"surfaceGroup[{group_id}].range")
        require_string(group_obj.get("description"), f"surfaceGroup[{group_id}].description")
        surface_screen_refs.update(str(screen_id) for screen_id in require_list(group_obj.get("screens"), f"surfaceGroup[{group_id}].screens"))

    screen_ids: set[str] = set()
    for index, screen in enumerate(screens):
        screen_obj = require_object(screen, f"navigation.screens[{index}]")
        screen_id = require_string(screen_obj.get("id"), f"screens[{index}].id")
        if screen_id in screen_ids:
            raise ValidationError(f"duplicate screen id: {screen_id}")
        screen_ids.add(screen_id)
        require_string(screen_obj.get("number"), f"screen[{screen_id}].number")
        require_string(screen_obj.get("title"), f"screen[{screen_id}].title")
        surface_group = require_string(screen_obj.get("surfaceGroup"), f"screen[{screen_id}].surfaceGroup")
        if surface_group not in surface_ids:
            raise ValidationError(f"screen[{screen_id}] references unknown surfaceGroup: {surface_group}")
        require_string(screen_obj.get("surface"), f"screen[{screen_id}].surface")
        require_string(screen_obj.get("routeOrEntry"), f"screen[{screen_id}].routeOrEntry")
        require_string(screen_obj.get("domainGroup"), f"screen[{screen_id}].domainGroup")
        require_string(screen_obj.get("purpose"), f"screen[{screen_id}].purpose")
        for state_index, state in enumerate(require_list(screen_obj.get("states"), f"screen[{screen_id}].states")):
            state_obj = require_object(state, f"screen[{screen_id}].states[{state_index}]")
            require_string(state_obj.get("name"), f"screen[{screen_id}].states[{state_index}].name")
            require_string(state_obj.get("description"), f"screen[{screen_id}].states[{state_index}].description")
        require_list(screen_obj.get("primaryActions"), f"screen[{screen_id}].primaryActions")
        require_list(screen_obj.get("relatedApis"), f"screen[{screen_id}].relatedApis")
        require_list(screen_obj.get("policies"), f"screen[{screen_id}].policies")
        require_list(screen_obj.get("sourceRefs"), f"screen[{screen_id}].sourceRefs")

    missing_from_groups = sorted(screen_ids - surface_screen_refs)
    unknown_in_groups = sorted(surface_screen_refs - screen_ids)
    if missing_from_groups:
        raise ValidationError(f"screens missing from surfaceGroups: {missing_from_groups}")
    if unknown_in_groups:
        raise ValidationError(f"surfaceGroups reference unknown screens: {unknown_in_groups}")

    for index, transition in enumerate(transitions):
        transition_obj = require_object(transition, f"navigation.transitions[{index}]")
        transition_id = require_string(transition_obj.get("id"), f"transitions[{index}].id")
        from_screen = require_string(transition_obj.get("from"), f"transition[{transition_id}].from")
        to_screen = require_string(transition_obj.get("to"), f"transition[{transition_id}].to")
        if from_screen not in screen_ids:
            raise ValidationError(f"transition[{transition_id}] from unknown screen: {from_screen}")
        if to_screen not in screen_ids:
            raise ValidationError(f"transition[{transition_id}] to unknown screen: {to_screen}")
        require_string(transition_obj.get("fromState"), f"transition[{transition_id}].fromState")
        require_string(transition_obj.get("trigger"), f"transition[{transition_id}].trigger")
        require_string(transition_obj.get("condition"), f"transition[{transition_id}].condition")
        require_string(transition_obj.get("toState"), f"transition[{transition_id}].toState")
        require_string(transition_obj.get("system"), f"transition[{transition_id}].system")

def validate_domain(domain: dict[str, Any]) -> None:
    vocab = require_object(domain.get("canonicalVocabulary"), "domain.canonicalVocabulary")
    for key in ("modelBurdenLevels", "providerStates", "requestStates", "entities"):
        require_list(vocab.get(key), f"domain.canonicalVocabulary.{key}")
    for index, invariant in enumerate(require_list(domain.get("invariants"), "domain.invariants")):
        invariant_obj = require_object(invariant, f"domain.invariants[{index}]")
        invariant_id = require_string(invariant_obj.get("id"), f"domain.invariants[{index}].id")
        require_string(invariant_obj.get("rule"), f"invariant[{invariant_id}].rule")
        require_list(invariant_obj.get("appliesTo"), f"invariant[{invariant_id}].appliesTo")
        require_list(invariant_obj.get("sourceRefs"), f"invariant[{invariant_id}].sourceRefs")
    routing = require_object(domain.get("routingDecision"), "domain.routingDecision")
    require_list(routing.get("inputs"), "domain.routingDecision.inputs")
    require_list(routing.get("gatesInOrder"), "domain.routingDecision.gatesInOrder")
    require_string(routing.get("fallback"), "domain.routingDecision.fallback")

    seen_domain_ids: set[str] = set()
    for index, group in enumerate(require_list(domain.get("domainGroups"), "domain.domainGroups")):
        group_obj = require_object(group, f"domainGroups[{index}]")
        group_id = require_string(group_obj.get("id"), f"domainGroups[{index}].id")
        if group_id in seen_domain_ids:
            raise ValidationError(f"duplicate domain group id: {group_id}")
        seen_domain_ids.add(group_id)
        require_string(group_obj.get("title"), f"domainGroup[{group_id}].title")
        require_string(group_obj.get("range"), f"domainGroup[{group_id}].range")
        require_string(group_obj.get("summary"), f"domainGroup[{group_id}].summary")
        require_list(group_obj.get("primaryActors"), f"domainGroup[{group_id}].primaryActors")
        require_list(group_obj.get("userFlow"), f"domainGroup[{group_id}].userFlow")
        require_list(group_obj.get("states"), f"domainGroup[{group_id}].states")
        require_list(group_obj.get("apis"), f"domainGroup[{group_id}].apis")
        require_list(group_obj.get("exceptions"), f"domainGroup[{group_id}].exceptions")
        require_list(group_obj.get("policies"), f"domainGroup[{group_id}].policies")
        require_list(group_obj.get("contracts"), f"domainGroup[{group_id}].contracts")
        require_list(group_obj.get("checkpoints"), f"domainGroup[{group_id}].checkpoints")
        require_list(group_obj.get("sourceRefs"), f"domainGroup[{group_id}].sourceRefs")
        for state_index, state in enumerate(group_obj["states"]):
            state_obj = require_object(state, f"domainGroup[{group_id}].states[{state_index}]")
            require_string(state_obj.get("name"), f"domainGroup[{group_id}].states[{state_index}].name")
            require_string(state_obj.get("meaning"), f"domainGroup[{group_id}].states[{state_index}].meaning")
        for api_index, api in enumerate(group_obj["apis"]):
            api_obj = require_object(api, f"domainGroup[{group_id}].apis[{api_index}]")
            require_string(api_obj.get("when"), f"domainGroup[{group_id}].apis[{api_index}].when")
            require_string(api_obj.get("owner"), f"domainGroup[{group_id}].apis[{api_index}].owner")
            require_string(api_obj.get("surface"), f"domainGroup[{group_id}].apis[{api_index}].surface")
            require_string(api_obj.get("purpose"), f"domainGroup[{group_id}].apis[{api_index}].purpose")
        for exception_index, exception in enumerate(group_obj["exceptions"]):
            exception_obj = require_object(exception, f"domainGroup[{group_id}].exceptions[{exception_index}]")
            require_string(exception_obj.get("situation"), f"domainGroup[{group_id}].exceptions[{exception_index}].situation")
            require_string(exception_obj.get("condition"), f"domainGroup[{group_id}].exceptions[{exception_index}].condition")
            require_string(exception_obj.get("userResult"), f"domainGroup[{group_id}].exceptions[{exception_index}].userResult")
            require_string(
                exception_obj.get("systemHandling"),
                f"domainGroup[{group_id}].exceptions[{exception_index}].systemHandling",
            )


def validate_policies(policies: dict[str, Any]) -> None:
    require_list(policies.get("conflictOrder"), "policies.conflictOrder")
    seen: set[str] = set()
    for index, rule in enumerate(require_list(policies.get("rules"), "policies.rules")):
        rule_obj = require_object(rule, f"policies.rules[{index}]")
        rule_id = require_string(rule_obj.get("id"), f"policies.rules[{index}].id")
        if rule_id in seen:
            raise ValidationError(f"duplicate policy id: {rule_id}")
        seen.add(rule_id)
        require_string(rule_obj.get("area"), f"policy[{rule_id}].area")
        require_string(rule_obj.get("severity"), f"policy[{rule_id}].severity")
        require_string(rule_obj.get("rule"), f"policy[{rule_id}].rule")
        require_list(rule_obj.get("appliesTo"), f"policy[{rule_id}].appliesTo")
        require_list(rule_obj.get("sourceRefs"), f"policy[{rule_id}].sourceRefs")
    require_string(policies.get("agentDecisionRule"), "policies.agentDecisionRule")


def validate_contracts(contracts: dict[str, Any]) -> None:
    seen: set[str] = set()
    for index, contract in enumerate(require_list(contracts.get("contracts"), "contracts.contracts")):
        contract_obj = require_object(contract, f"contracts.contracts[{index}]")
        contract_id = require_string(contract_obj.get("id"), f"contracts.contracts[{index}].id")
        if contract_id in seen:
            raise ValidationError(f"duplicate contract id: {contract_id}")
        seen.add(contract_id)
        require_string(contract_obj.get("name"), f"contract[{contract_id}].name")
        require_string(contract_obj.get("ssot"), f"contract[{contract_id}].ssot")
        require_list(contract_obj.get("generated"), f"contract[{contract_id}].generated")
        require_object(contract_obj.get("commands"), f"contract[{contract_id}].commands")
        require_string(contract_obj.get("rule"), f"contract[{contract_id}].rule")
    if "CON-SSOT-VIEWER" not in seen:
        raise ValidationError("contracts.json must include CON-SSOT-VIEWER")


def validate_cross_references(context: dict[str, dict[str, Any]]) -> None:
    policy_ids = {rule["id"] for rule in context["policies"]["rules"]}
    contract_ids = {contract["id"] for contract in context["contracts"]["contracts"]}
    domain_group_ids = {group["id"] for group in context["domain"]["domainGroups"]}
    for group in context["domain"]["domainGroups"]:
        group_id = group["id"]
        unknown_policies = sorted(set(group["policies"]) - policy_ids)
        unknown_contracts = sorted(set(group["contracts"]) - contract_ids)
        if unknown_policies:
            raise ValidationError(f"domainGroup[{group_id}] references unknown policies: {unknown_policies}")
        if unknown_contracts:
            raise ValidationError(f"domainGroup[{group_id}] references unknown contracts: {unknown_contracts}")
    for screen in context["navigation"]["screens"]:
        screen_id = screen["id"]
        if screen["domainGroup"] not in domain_group_ids:
            raise ValidationError(f"screen[{screen_id}] references unknown domainGroup: {screen['domainGroup']}")
        unknown_policies = sorted(set(screen["policies"]) - policy_ids)
        if unknown_policies:
            raise ValidationError(f"screen[{screen_id}] references unknown policies: {unknown_policies}")


def load_context() -> dict[str, dict[str, Any]]:
    context = {
        "index": load_json("index.json"),
        "product": load_json("product.json"),
        "navigation": load_json("navigation.json"),
        "domain": load_json("domain.json"),
        "policies": load_json("policies.json"),
        "contracts": load_json("contracts.json"),
    }
    validate_file_set(context["index"])
    validate_product(context["product"])
    validate_navigation(context["navigation"])
    validate_domain(context["domain"])
    validate_policies(context["policies"])
    validate_contracts(context["contracts"])
    validate_cross_references(context)
    return context


def e(value: Any) -> str:
    return escape(str(value), quote=True)


def append_search_terms(value: Any, chunks: list[str]) -> None:
    if value is None:
        return
    if isinstance(value, dict):
        for item in value.values():
            append_search_terms(item, chunks)
        return
    if isinstance(value, list):
        for item in value:
            append_search_terms(item, chunks)
        return
    chunks.append(str(value))


def data_search(*values: Any) -> str:
    chunks: list[str] = []
    for value in values:
        append_search_terms(value, chunks)
    return e(" ".join(chunks).lower())


def as_list(items: Iterable[Any], css_class: str = "") -> str:
    class_attr = f' class="{css_class}"' if css_class else ""
    return f"<ul{class_attr}>" + "".join(f"<li>{e(item)}</li>" for item in items) + "</ul>"


def chips(items: Iterable[Any], css_class: str = "chip") -> str:
    return "".join(f'<span class="{css_class}">{e(item)}</span>' for item in items)


def command_cell(commands: dict[str, Any]) -> str:
    rows = []
    for name in ("generate", "check"):
        command = commands.get(name)
        if command:
            rows.append(f'<code>{e(name)}: {command}</code>')
    if not rows:
        rows = [f'<code>{e(key)}: {e(value)}</code>' for key, value in commands.items()]
    return "<div class=\"command-stack\">" + "".join(rows) + "</div>"


def render_header(context: dict[str, dict[str, Any]]) -> str:
    product = context["product"]
    hero = product["viewerHero"]
    navigation = context["navigation"]
    domain = context["domain"]
    policies = context["policies"]
    contracts = context["contracts"]
    actor_count = len(product["actors"])
    screen_count = len(navigation["screens"])
    transition_count = len(navigation["transitions"])
    invariant_count = len(domain["invariants"])
    policy_count = len(policies["rules"])
    contract_count = len(contracts["contracts"])
    domain_count = len(domain["domainGroups"])
    return f"""
<header class="topbar">
  <div class="topbar-title">
    <span class="mark">NX</span>
    <div>
      <strong>요구사항/API/네비게이션 뷰어</strong>
      <small>Generated from ai-context/*.json</small>
    </div>
  </div>
  <nav class="topbar-links" aria-label="문서 섹션">
    <a href="#story">사용자 흐름</a>
    <a href="#domain">도메인</a>
    <a href="#navigation">네비게이션</a>
    <a href="#contracts">API/계약</a>
    <a href="#domain-groups">도메인별</a>
    <a href="#policies">정책</a>
  </nav>
  <div class="pill-row">
    <span class="count-pill">Actors {actor_count}</span>
    <span class="count-pill">Screens {screen_count}</span>
    <span class="count-pill">Transitions {transition_count}</span>
    <span class="count-pill">Domains {domain_count}</span>
    <span class="count-pill">Invariants {invariant_count}</span>
    <span class="count-pill">Policies {policy_count}</span>
    <span class="count-pill">Contracts {contract_count}</span>
  </div>
</header>
<section class="hero" data-search="{data_search(product, hero)}">
  <div class="generated-note">AUTO-GENERATED · 직접 수정 금지 · 수정은 ai-context JSON에서</div>
  <p class="eyebrow">{e(hero.get("eyebrow", product["productName"]))}</p>
  <h1>{e(hero["title"])}</h1>
  <p class="hero-lead">{e(hero["lead"])}</p>
  <div class="hero-grid">
    <article class="hero-card story-card-main">
      <span class="section-kicker">1. 이 서비스는 무엇인가</span>
      <p>{e(product["plainDescription"])}</p>
      <p class="mission">{e(product["oneLine"])}</p>
    </article>
    <article class="hero-card">
      <span class="section-kicker">사용자가 체감하는 약속</span>
      {as_list(product["publicPromise"])}
    </article>
    <article class="hero-card danger">
      <span class="section-kicker">이 서비스가 아닌 것</span>
      {as_list(product["nonGoals"])}
    </article>
  </div>
  <div class="toolbar">
    <label class="search-box">검색 <input id="search" type="search" placeholder="예: /질문, Provider 보호, wire, 디코 서버에 봇 추가" /></label>
    <button id="expandAll" type="button">모두 펼치기</button>
    <button id="collapseAll" type="button">모두 접기</button>
  </div>
</section>
"""


def visual_icon(visual_type: str) -> str:
    return "◆"


def render_story(context: dict[str, dict[str, Any]]) -> str:
    story = context["product"]["experienceStory"]
    cards = []
    for step in story["steps"]:
        cards.append(f"""
<article class="story-card" data-search="{data_search(step)}">
  <div class="story-visual" aria-hidden="true">
    <span class="story-number">{e(step["number"])}</span>
    <span class="story-icon">{visual_icon(step["visualType"])}</span>
    <span class="story-connector"></span>
  </div>
  <div class="story-body">
    <div class="meta-line"><span>{e(step["actor"])}</span><span>{e(step["visualType"])}</span></div>
    <h3>{e(step["title"])}</h3>
    <p>{e(step["body"])}</p>
    <div class="callouts">{chips(step["callouts"], "callout-chip")}</div>
    <details class="source-details"><summary>근거 보기</summary>{chips(step["sources"], "source-chip")}</details>
  </div>
</article>
""")
    flow_steps = context["product"]["coreExperienceFlow"]["steps"]
    flow = "".join(
        f'<li><strong>{e(step["title"])}</strong><span>{e(step["text"])}</span></li>' for step in flow_steps
    )
    return f"""
<section id="story" class="nav-section">
  <div class="section-head">
    <div>
      <span class="section-kicker">2. 사용자는 어떤 흐름을 겪는가</span>
      <h2>{e(story["title"])}</h2>
      <p>{e(story["subtitle"])}</p>
    </div>
  </div>
  <div class="story-strip">{''.join(cards)}</div>
  <div class="flow-panel" data-search="{data_search(flow_steps)}">
    <h3>한 줄 흐름: /질문 한 번이 실제로 지나가는 길</h3>
    <ol class="flow-steps">{flow}</ol>
  </div>
</section>
"""


def render_domain(context: dict[str, dict[str, Any]]) -> str:
    domain = context["domain"]
    vocab = domain["canonicalVocabulary"]
    routing = domain["routingDecision"]
    invariant_rows = "".join(
        f"""
<tr data-search="{data_search(invariant)}">
  <td><code>{e(invariant["id"])}</code></td>
  <td>{e(invariant["rule"])}</td>
  <td>{chips(invariant["appliesTo"][:6], "mini-chip")}</td>
  <td>{chips(invariant["sourceRefs"], "source-chip")}</td>
</tr>
"""
        for invariant in domain["invariants"]
    )
    gates = "".join(f"<li>{e(gate)}</li>" for gate in routing["gatesInOrder"])
    state_map = """
<div class="state-map" aria-label="서비스 상태 지도">
  <div class="state-node">[Discord /질문]</div>
  <div class="arrow">↓</div>
  <div class="state-node">[정책 검사]</div>
  <div class="arrow">↓</div>
  <div class="state-node">[Provider 후보 필터]</div>
  <div class="arrow split">↙ ↓ ↘</div>
  <div class="state-row"><span>[로컬 실행]</span><span>[무료 폴백]</span><span>[정책 거부]</span></div>
  <div class="arrow">↓</div>
  <div class="state-node done">[Discord 답변]</div>
</div>
"""
    return f"""
<section id="domain" class="nav-section">
  <div class="section-head">
    <div>
      <span class="section-kicker">3. 화면/서비스 상태 지도</span>
      <h2>도메인 모델</h2>
      <p>서비스를 이해할 때 먼저 봐야 하는 상태, 엔티티, 불변식이다. URL 목록보다 이 지도가 먼저다.</p>
    </div>
  </div>
  <div class="domain-grid">
    <article class="panel" data-search="{data_search(vocab)}">
      <h3>핵심 상태</h3>
      <h4>Provider states</h4>{chips(vocab["providerStates"], "status-chip")}
      <h4>Request states</h4>{chips(vocab["requestStates"], "status-chip alt")}
      <h4>Model burden</h4>{chips(vocab["modelBurdenLevels"], "status-chip warn")}
    </article>
    <article class="panel visual-panel" data-search="ask policy provider fallback rejected">
      <h3>서비스 상태 지도</h3>
      {state_map}
    </article>
    <article class="panel" data-search="{data_search(routing)}">
      <h3>라우팅 결정 순서</h3>
      <ol class="gate-list">{gates}</ol>
      <p class="note-line">{e(routing["fallback"])}</p>
    </article>
  </div>
  <details class="wide-details" open>
    <summary>도메인 불변식 표</summary>
    <table class="nav-table">
      <thead><tr><th>ID</th><th>규칙</th><th>적용</th><th>근거</th></tr></thead>
      <tbody>{invariant_rows}</tbody>
    </table>
  </details>
</section>
"""


def render_navigation(context: dict[str, dict[str, Any]]) -> str:
    navigation = context["navigation"]
    screens_by_id = {screen["id"]: screen for screen in navigation["screens"]}
    group_sections = []
    for group in navigation["surfaceGroups"]:
        screen_rows = []
        for screen_id in group["screens"]:
            screen = screens_by_id[screen_id]
            state_chips = chips([state["name"] for state in screen["states"]], "status-chip")
            screen_rows.append(f"""
<tr data-search="{data_search(group, screen)}">
  <td class="screen-num"><code>{e(screen["number"])}</code></td>
  <td><a class="inline-screen-link" href="#screen-{e(screen["id"])}"><strong>{e(screen["title"])}</strong></a><br><span class="muted">{e(screen["surface"])}</span></td>
  <td><code>{e(screen["routeOrEntry"])}</code></td>
  <td>{e(screen["purpose"])}</td>
  <td>{state_chips}</td>
  <td>{chips(screen["policies"], "mini-chip")}</td>
</tr>
""")
        group_sections.append(f"""
<section class="screen-group" data-search="{data_search(group)}">
  <div class="group-head"><h2>{e(group["title"])}</h2><span class="group-count">screens {e(group["range"])}</span></div>
  <p class="group-description">{e(group["description"])}</p>
  <table class="nav-table screen-table">
    <thead><tr><th>No</th><th>화면</th><th>Entry/API</th><th>목적</th><th>상태</th><th>정책</th></tr></thead>
    <tbody>{''.join(screen_rows)}</tbody>
  </table>
</section>
""")

    transition_rows = []
    for index, item in enumerate(navigation["transitions"], 1):
        from_screen = screens_by_id[item["from"]]
        to_screen = screens_by_id[item["to"]]
        transition_rows.append(f"""
<tr data-search="{data_search(item, from_screen, to_screen)}">
  <td><code>{index:02d}</code></td>
  <td><a class="inline-screen-link" href="#screen-{e(item["from"])}">{e(from_screen["number"])} {e(from_screen["title"])}</a><br><span class="muted">{e(item["fromState"])}</span></td>
  <td>{e(item["trigger"])}</td>
  <td>{e(item["condition"])}</td>
  <td><a class="inline-screen-link" href="#screen-{e(item["to"])}">{e(to_screen["number"])} {e(to_screen["title"])}</a><br><span class="muted">{e(item["toState"])}</span></td>
  <td>{e(item["system"])}</td>
</tr>
""")

    screen_cards = []
    for screen in navigation["screens"]:
        screen_cards.append(f"""
<article class="screen-card" id="screen-{e(screen["id"])}" data-search="{data_search(screen)}">
  <div class="meta-line"><span>{e(screen["number"])} · {e(screen["surfaceGroup"])}</span><span>{e(screen["domainGroup"])}</span></div>
  <h3>{e(screen["title"])}</h3>
  <p>{e(screen["purpose"])}</p>
  <h4>상태</h4>
  <div class="state-list">{''.join(f'<span><code>{e(state["name"])}</code>{e(state["description"])}</span>' for state in screen["states"])}</div>
  <h4>주요 행동</h4>
  {as_list(screen["primaryActions"], "compact-list")}
  <h4>관련 API / 근거</h4>
  <div class="chip-block">{chips(screen["relatedApis"], "source-chip")}{chips(screen["sourceRefs"], "source-chip")}</div>
</article>
""")

    return f"""
<section id="navigation" class="nav-section">
  <div class="section-head">
    <div>
      <span class="section-kicker">앱 네비게이션</span>
      <h2>화면 기준 네비게이션</h2>
      <p>네비게이션 SSOT는 surfaceGroups, screens, transitions 세 가지만 소유한다. 화면에서 화면으로 이어지는 조건을 표로 본다.</p>
    </div>
  </div>
  {''.join(group_sections)}
  <details class="wide-details" open>
    <summary>화면 전이 표</summary>
    <table class="nav-table transition-table">
      <thead><tr><th>#</th><th>From 화면/상태</th><th>Trigger</th><th>조건</th><th>To 화면/상태</th><th>시스템 처리</th></tr></thead>
      <tbody>{''.join(transition_rows)}</tbody>
    </table>
  </details>
  <details class="wide-details">
    <summary>화면별 상세 카드</summary>
    <div class="screen-card-grid">{''.join(screen_cards)}</div>
  </details>
</section>
"""

def render_contracts(context: dict[str, dict[str, Any]]) -> str:
    rows = []
    for contract in context["contracts"]["contracts"]:
        rows.append(f"""
<tr data-search="{data_search(contract)}">
  <td><code>{e(contract["id"])}</code><br><strong>{e(contract["name"])}</strong></td>
  <td><code>{e(contract["ssot"])}</code></td>
  <td>{chips(contract["generated"], "source-chip")}</td>
  <td>{command_cell(contract["commands"])}</td>
  <td>{e(contract["rule"])}</td>
</tr>
""")
    components = "".join(
        f"""
<article class="component-card" data-search="{data_search(component)}">
  <h3>{e(component["name"])}</h3>
  <span class="stack-chip">{e(component["stack"])}</span>
  <p>{e(component["responsibility"])}</p>
</article>
"""
        for component in context["product"]["components"]
    )
    api_rows = []
    for group in context["domain"]["domainGroups"]:
        for api in group["apis"]:
            api_rows.append(f"""
<tr data-search="{data_search(group["title"], api)}">
  <td><code>{e(api["surface"])}</code></td>
  <td>{e(api["owner"])}</td>
  <td>{e(api["when"])}</td>
  <td>{e(api["purpose"])}</td>
  <td><a class="inline-screen-link" href="#domain-{e(group["id"])}">{e(group["title"])}</a></td>
</tr>
""")
    api_count = len(api_rows)
    return f"""
<section id="contracts" class="nav-section">
  <div class="section-head">
    <div>
      <span class="section-kicker">5. 시스템 내부 흐름 + 7. 관련 API</span>
      <h2>API / 계약 명세</h2>
      <p>API가 없는 게 아니라 도메인별로 흩어져 있었다. 먼저 Discord·웹·로컬 표면에 걸친 도메인별 대표 API {api_count}개를 한곳에 모으고, 그 아래에 바뀌면 같이 검증해야 하는 SSOT 계약을 둔다. (엔드포인트 전수 목록이 아니라 도메인 이해용 대표 표면이다.)</p>
    </div>
  </div>
  <div class="component-grid">{components}</div>
  <h3 class="block-title">API 표면 한눈에 보기 · {api_count}개</h3>
  <p class="block-note">도메인 그룹마다 정의된 실제 API/Surface를 모은 표다. 도메인 열을 누르면 그 도메인의 상세 흐름으로 이동한다.</p>
  <table class="nav-table api-table">
    <thead><tr><th>API / Surface</th><th>주체</th><th>시점</th><th>목적</th><th>도메인</th></tr></thead>
    <tbody>{''.join(api_rows)}</tbody>
  </table>
  <h3 class="block-title">SSOT 계약 · 바뀌면 같이 검증</h3>
  <p class="block-note">엔드포인트 나열과 달리, 한쪽을 바꾸면 생성물·반대편을 같은 커밋에서 맞춰야 하는 계약이다.</p>
  <table class="nav-table contract-table">
    <thead><tr><th>계약</th><th>SSOT</th><th>생성물</th><th>명령</th><th>규칙</th></tr></thead>
    <tbody>{''.join(rows)}</tbody>
  </table>
</section>
"""


def table_rows(rows: Iterable[str]) -> str:
    return "".join(rows)


def render_domain_groups(context: dict[str, dict[str, Any]]) -> str:
    groups = context["domain"]["domainGroups"]
    sections = []
    for group in groups:
        flow = "".join(f"<li>{e(step)}</li>" for step in group["userFlow"])
        state_rows = table_rows(
            f"""
<tr>
  <td><code>{e(state["name"])}</code></td>
  <td>{e(state["meaning"])}</td>
</tr>
"""
            for state in group["states"]
        )
        api_rows = table_rows(
            f"""
<tr>
  <td>{e(api["when"])}</td>
  <td>{e(api["owner"])}</td>
  <td><code>{e(api["surface"])}</code></td>
  <td>{e(api["purpose"])}</td>
</tr>
"""
            for api in group["apis"]
        )
        exception_rows = table_rows(
            f"""
<tr>
  <td>{e(exception["situation"])}</td>
  <td>{e(exception["condition"])}</td>
  <td>{e(exception["userResult"])}</td>
  <td>{e(exception["systemHandling"])}</td>
</tr>
"""
            for exception in group["exceptions"]
        )
        sections.append(f"""
<section class="domain-group" id="domain-{e(group["id"])}" data-search="{data_search(group)}">
  <div class="group-head">
    <h2>{e(group["title"])}</h2>
    <span class="group-count">{e(group["range"])}</span>
  </div>
  <div class="domain-row">
    <aside class="domain-summary-card">
      <div class="meta-line"><span>도메인</span><span>{e(group["range"])}</span></div>
      <p>{e(group["summary"])}</p>
      <h3>주요 actor</h3>
      <div>{chips(group["primaryActors"], "callout-chip")}</div>
      <h3>정책 / 계약</h3>
      <div class="chip-block">{chips(group["policies"], "mini-chip")}{chips(group["contracts"], "mini-chip")}</div>
      <details class="source-details"><summary>근거</summary>{chips(group["sourceRefs"], "source-chip")}</details>
    </aside>
    <article class="domain-detail-card">
      <h3>1. 이 도메인은 무엇인가</h3>
      <p>{e(group["summary"])}</p>
      <h3>2. 사용자는 어떤 흐름을 겪는가</h3>
      <ol class="domain-flow">{flow}</ol>
      <div class="domain-tables">
        <details open>
          <summary>3. 상태 지도</summary>
          <table class="nav-table compact-table">
            <thead><tr><th>상태</th><th>의미</th></tr></thead>
            <tbody>{state_rows}</tbody>
          </table>
        </details>
        <details open>
          <summary>7. 관련 API / 계약</summary>
          <table class="nav-table compact-table">
            <thead><tr><th>시점</th><th>주체</th><th>API/Surface</th><th>목적</th></tr></thead>
            <tbody>{api_rows}</tbody>
          </table>
        </details>
        <details open>
          <summary>8. 예외 처리</summary>
          <table class="nav-table compact-table">
            <thead><tr><th>상황</th><th>조건</th><th>사용자에게 보이는 결과</th><th>시스템 처리</th></tr></thead>
            <tbody>{exception_rows}</tbody>
          </table>
        </details>
      </div>
      <h3>9. 구현 체크포인트</h3>
      {as_list(group["checkpoints"], "compact-list checkpoint-list")}
    </article>
  </div>
</section>
""")
    return f"""
<section id="domain-groups" class="nav-section domain-groups-section">
  <div class="section-head">
    <div>
      <span class="section-kicker">도메인별 요구사항</span>
      <h2>사람이 읽는 단위는 기능 목록이 아니라 도메인 흐름이다</h2>
      <p>Dailyting 뷰어처럼 전역 모델/네비게이션/API를 먼저 보여준 뒤, 실제 이해와 구현 점검은 도메인 그룹별로 끊는다.</p>
    </div>
  </div>
  {''.join(sections)}
</section>
"""


def render_policies(context: dict[str, dict[str, Any]]) -> str:
    policies = context["policies"]
    order = "".join(f"<li>{e(item)}</li>" for item in policies["conflictOrder"])
    rule_cards = []
    for rule in policies["rules"]:
        rule_cards.append(f"""
<article class="policy-card severity-{e(rule["severity"])}" data-search="{data_search(rule)}">
  <div class="meta-line"><span>{e(rule["id"])}</span><span>{e(rule["severity"])}</span></div>
  <h3>{e(rule["area"])}</h3>
  <p>{e(rule["rule"])}</p>
  <div>{chips(rule["appliesTo"], "mini-chip")}</div>
  <details class="source-details"><summary>근거</summary>{chips(rule["sourceRefs"], "source-chip")}</details>
</article>
""")
    misconceptions = context["product"]["misconceptions"]
    misconceptions_cards = "".join(
        f"""
<article class="mis-card" data-search="{data_search(item)}">
  <h3>{e(item["title"])}</h3>
  <p>{e(item["text"])}</p>
</article>
"""
        for item in misconceptions["items"]
    )
    return f"""
<section id="policies" class="nav-section">
  <div class="section-head">
    <div>
      <span class="section-kicker">6. 다음 분기 + 8. 예외 처리</span>
      <h2>정책 / 예외 / 충돌 우선순위</h2>
      <p>정책 충돌은 사람 감으로 풀지 않는다. 아래 우선순위와 blocker 규칙이 라우팅·UX·운영보다 먼저다.</p>
    </div>
  </div>
  <div class="policy-layout">
    <article class="panel order-panel" data-search="{data_search(policies["conflictOrder"])}">
      <h3>충돌 우선순위</h3>
      <ol>{order}</ol>
      <p class="note-line">{e(policies["agentDecisionRule"])}</p>
    </article>
    <div class="policy-grid">{''.join(rule_cards)}</div>
  </div>
  <details class="wide-details" open>
    <summary>{e(misconceptions["title"])}</summary>
    <div class="mis-grid">{misconceptions_cards}</div>
  </details>
</section>
"""


def render_agent_context(context: dict[str, dict[str, Any]]) -> str:
    index = context["index"]
    files = index["files"]
    file_rows = "".join(
        f"""
<tr data-search="{data_search(key, value)}">
  <td><code>{e(key)}</code></td>
  <td><code>{e(value["path"])}</code></td>
  <td>{chips(value["owns"], "mini-chip")}</td>
</tr>
"""
        for key, value in files.items()
    )
    load_rows = "".join(
        f"""
<tr data-search="{data_search(change, names)}">
  <td><code>{e(change)}</code></td>
  <td>{chips(names, "mini-chip")}</td>
</tr>
"""
        for change, names in index["loadByChange"].items()
    )
    generated = "".join(
        f"""
<li data-search="{data_search(view)}">
  <code>{e(view["path"])}</code>
  <span>{e(view["source"])}</span>
  <code>{e(view["command"])}</code>
  <code>{e(view["checkCommand"])}</code>
</li>
"""
        for view in index["generatedViews"]
    )
    return f"""
<section id="agent-context" class="nav-section">
  <div class="section-head">
    <div>
      <span class="section-kicker">9. 구현 체크포인트</span>
      <h2>에이전트 컨텍스트 네비게이션</h2>
      <p>작업할 때 어떤 JSON을 읽어야 하는지, 어떤 생성물을 직접 고치면 안 되는지 확인한다.</p>
    </div>
  </div>
  <div class="two-col">
    <details class="wide-details" open>
      <summary>JSON 파일별 소유 범위</summary>
      <table class="nav-table"><thead><tr><th>키</th><th>파일</th><th>소유</th></tr></thead><tbody>{file_rows}</tbody></table>
    </details>
    <details class="wide-details" open>
      <summary>변경 유형별 로드 순서</summary>
      <table class="nav-table"><thead><tr><th>변경</th><th>먼저 읽을 JSON</th></tr></thead><tbody>{load_rows}</tbody></table>
    </details>
  </div>
  <div class="generated-list">
    <h3>생성 뷰</h3>
    <ul>{generated}</ul>
  </div>
</section>
"""


def render_footer() -> str:
    return f"""
<footer class="footer">
  <strong>Do not edit this HTML directly.</strong>
  <span>Source: ai-context/*.json · Generate: <code>{GENERATE_COMMAND}</code> · Check: <code>{CHECK_COMMAND}</code></span>
</footer>
"""


def render_style() -> str:
    # Single-color design system. Hierarchy is built from tone, size, weight,
    # spacing and fill — not from many hues. One accent (--accent) plus a
    # neutral ink/surface lightness ramp does all the work.
    return """
<style>
:root{
  color-scheme:dark;
  /* surfaces — one neutral hue, separated by elevation */
  --bg:#0b0d12;--surface:#12151c;--surface-raised:#171b24;--inset:#0d1015;
  /* ink — one neutral ramp, separated by lightness (title>body>secondary>hint) */
  --ink-strong:#f4f7fc;--ink:#c8d1de;--ink-soft:#98a2b2;--ink-faint:#6a7383;
  /* lines — subtle by default, stronger only when a group needs an edge */
  --line:#232934;--line-strong:#333b48;
  /* accent — single hue, varied by lightness + tint, used sparingly */
  --accent:#8ea7ff;--accent-bright:#b7c6ff;--accent-deep:#6f86e6;
  --accent-tint:rgba(142,167,255,.12);--accent-line:rgba(142,167,255,.40);
  /* type scale */
  --fs-hero:clamp(32px,4.4vw,58px);--fs-h2:26px;--fs-h3:17px;--fs-body:15px;--fs-sm:13px;--fs-xs:11px;
  /* weight — bold for top titles, semi for cards/buttons, medium for labels, reg for body */
  --w-bold:700;--w-semi:600;--w-medium:500;--w-reg:400;
  /* spacing — tight inside a group, large between sections */
  --s1:4px;--s2:8px;--s3:12px;--s4:16px;--s5:24px;--s6:32px;
  --r-sm:8px;--r-md:12px;--r-lg:16px;
  --shadow:0 18px 60px rgba(0,0,0,.34);
  --font:ui-sans-serif,-apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo","Noto Sans KR","Segoe UI",sans-serif;
  --mono:"SFMono-Regular","Cascadia Code","Menlo",monospace;
}
*{box-sizing:border-box}html{scroll-behavior:smooth}
body{margin:0;background:var(--bg);color:var(--ink);font-family:var(--font);font-size:var(--fs-body);font-weight:var(--w-reg);line-height:1.6}
a{color:inherit}

/* top bar */
.topbar{position:sticky;top:0;z-index:50;display:grid;grid-template-columns:auto 1fr auto;gap:18px;align-items:center;padding:12px 24px;background:rgba(11,13,18,.92);backdrop-filter:blur(16px);border-bottom:1px solid var(--line)}
.topbar-title{display:flex;gap:12px;align-items:center}
.mark{width:34px;height:34px;border-radius:var(--r-sm);display:grid;place-items:center;border:1px solid var(--accent-line);color:var(--accent);font-weight:var(--w-bold);letter-spacing:-.06em;background:var(--inset)}
.topbar strong{display:block;font-size:14px;font-weight:var(--w-semi);color:var(--ink-strong)}
.topbar small{display:block;font-size:var(--fs-xs);color:var(--ink-faint)}
.topbar-links{display:flex;gap:6px;justify-content:center;flex-wrap:wrap}
.topbar-links a{font-size:12px;font-weight:var(--w-medium);color:var(--ink-soft);text-decoration:none;border:1px solid var(--line);border-radius:999px;padding:6px 11px}
.topbar-links a:hover{border-color:var(--accent-line);color:var(--ink-strong);background:var(--accent-tint)}
.pill-row{display:flex;gap:6px;flex-wrap:wrap;justify-content:flex-end}

/* chips & pills — neutral and receding by default; accent only where it earns it */
.chip,.mini-chip,.source-chip,.status-chip,.callout-chip,.stack-chip,.count-pill{display:inline-flex;align-items:center;gap:6px;border-radius:999px;border:1px solid var(--line);background:transparent;padding:4px 10px;color:var(--ink-soft);font-size:var(--fs-xs);font-weight:var(--w-medium);line-height:1.4}
.count-pill{color:var(--accent);border-color:var(--accent-line);background:var(--accent-tint);font-variant-numeric:tabular-nums}
.source-chip{font-family:var(--mono);font-size:10px;border-radius:7px;color:var(--ink-faint)}
.mini-chip{font-size:10px}
.stack-chip{color:var(--ink-soft)}
.callout-chip{border-color:var(--accent-line);background:var(--accent-tint);color:var(--ink-strong)}
/* status — distinguished by a leading mark + lightness, not by extra colors */
.status-chip{margin:2px;color:var(--ink)}
.status-chip::before{content:"";width:6px;height:6px;border-radius:50%;background:var(--ink-soft);flex:none}
.status-chip.alt::before{background:transparent;border:1px solid var(--ink-soft)}
.status-chip.warn{color:var(--accent-bright);border-color:var(--accent-line)}
.status-chip.warn::before{background:var(--accent)}

/* layout shells */
.hero,.nav-section,.footer{width:min(1440px,calc(100vw - 48px));margin:var(--s6) auto}
.hero{padding:36px;border:1px solid var(--line);background:var(--surface);border-radius:var(--r-lg);box-shadow:var(--shadow)}
.nav-section{background:var(--surface);border:1px solid var(--line);border-radius:var(--r-lg);padding:28px;box-shadow:0 14px 44px rgba(0,0,0,.22)}

/* labels recede: muted, medium weight, tracked — they sit under the title, not over it */
.section-kicker,.eyebrow{display:inline-block;letter-spacing:.14em;text-transform:uppercase;font-size:var(--fs-xs);font-weight:var(--w-medium);color:var(--ink-faint)}
.eyebrow{margin:18px 0 var(--s2)}
.generated-note{display:inline-block;letter-spacing:.12em;text-transform:uppercase;font-size:10px;font-weight:var(--w-medium);color:var(--ink-faint);background:var(--inset);border:1px solid var(--line);border-radius:999px;padding:4px 11px}
.hero h1{max-width:980px;margin:14px 0 0;font-size:var(--fs-hero);font-weight:var(--w-bold);line-height:1.04;letter-spacing:-.055em;color:var(--ink-strong)}
.hero-lead{max-width:960px;margin:var(--s4) 0 0;color:var(--ink-soft);font-size:18px}
.hero-grid{display:grid;grid-template-columns:1.25fr 1fr 1fr;gap:var(--s3);margin-top:var(--s5)}
.hero-card,.panel,.flow-panel,.wide-details,.generated-list{border:1px solid var(--line);background:var(--surface-raised);border-radius:var(--r-md);padding:18px}
.hero-card p{margin:var(--s2) 0;color:var(--ink)}
.hero-card ul{margin:var(--s2) 0 0;padding-left:18px;color:var(--ink)}
.mission{font-size:16px;font-weight:var(--w-medium);color:var(--ink-strong)!important}
/* the "what it is NOT" card reads as negative space via a stronger edge + dashed markers, no red */
.hero-card.danger{border-color:var(--line-strong)}
.hero-card.danger ul{list-style:none;padding-left:0}
.hero-card.danger li{position:relative;padding-left:18px;color:var(--ink-soft);margin:6px 0}
.hero-card.danger li::before{content:"\\2715";position:absolute;left:0;color:var(--ink-faint);font-size:11px;top:2px}

/* toolbar — primary action is filled, the rest recede to outline/text */
.toolbar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:var(--s5)}
.search-box{flex:1;min-width:280px;display:flex;gap:10px;align-items:center;border:1px solid var(--line);background:var(--inset);border-radius:var(--r-md);padding:10px 14px;color:var(--ink-faint);font-size:var(--fs-xs);font-weight:var(--w-medium);text-transform:uppercase;letter-spacing:.08em}
.search-box input{flex:1;min-width:120px;border:0;outline:0;background:transparent;color:var(--ink-strong);font:inherit;text-transform:none;letter-spacing:0;font-weight:var(--w-reg)}
button{border:1px solid var(--line);background:transparent;color:var(--ink);border-radius:var(--r-sm);padding:9px 14px;font-size:var(--fs-sm);font-weight:var(--w-medium);cursor:pointer;transition:border-color .15s,background .15s,color .15s}
button:hover{border-color:var(--line-strong);color:var(--ink-strong)}
#expandAll{background:var(--accent);border-color:var(--accent);color:#0b0d12;font-weight:var(--w-semi)}
#expandAll:hover{background:var(--accent-deep);border-color:var(--accent-deep);color:#0b0d12}

/* section heads — title is the only loud element; supporting copy is muted */
.section-head{display:flex;justify-content:space-between;gap:18px;align-items:flex-end;margin-bottom:var(--s5)}
.section-head h2{font-size:var(--fs-h2);font-weight:var(--w-bold);letter-spacing:-.04em;color:var(--ink-strong);margin:var(--s1) 0}
.section-head p{margin:0;color:var(--ink-soft);max-width:860px}

/* cards share one surface; the title carries weight, the body sits lighter */
.story-strip{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:var(--s3)}
.story-card,.screen-card,.policy-card,.mis-card,.component-card,.domain-summary-card,.domain-detail-card{border:1px solid var(--line);background:var(--surface-raised);border-radius:var(--r-md);padding:16px;min-width:0}
.story-card{display:flex;flex-direction:column;gap:var(--s3)}
.story-visual{height:112px;border-radius:var(--r-md);background:var(--inset);border:1px solid var(--line);display:grid;place-items:center;position:relative}
.story-number{position:absolute;left:12px;top:10px;font-family:var(--mono);font-size:13px;color:var(--accent);font-weight:var(--w-bold)}
.story-icon{font-size:30px;color:var(--ink-faint)}
.story-connector{display:none}
.meta-line{display:flex;justify-content:space-between;gap:8px;color:var(--ink-faint);font-size:var(--fs-xs);text-transform:uppercase;letter-spacing:.07em;font-weight:var(--w-medium)}
.story-card h3,.screen-card h3,.policy-card h3,.mis-card h3,.component-card h3{margin:var(--s2) 0;font-size:var(--fs-h3);font-weight:var(--w-semi);letter-spacing:-.02em;color:var(--ink-strong)}
.story-card p,.screen-card p,.policy-card p,.mis-card p,.component-card p{color:var(--ink);margin:0 0 var(--s3)}
.source-details{margin-top:var(--s3)}
.source-details summary,.wide-details summary{cursor:pointer;color:var(--ink);font-weight:var(--w-semi)}
.source-details summary:hover,.wide-details summary:hover{color:var(--ink-strong)}
.source-details .source-chip{margin:var(--s2) 4px 0 0}

/* flow steps — a row of equal cards, the arrow is a faint connector */
.flow-panel{margin-top:var(--s4)}
.flow-steps{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:var(--s2);counter-reset:flow;margin:var(--s3) 0 0;padding:0;list-style:none}
.flow-steps li{border:1px solid var(--line);background:var(--inset);border-radius:var(--r-md);padding:14px;position:relative}
.flow-steps li:after{content:"\\2192";position:absolute;right:-12px;top:50%;transform:translateY(-50%);color:var(--ink-faint);font-weight:var(--w-bold)}
.flow-steps li:last-child:after{display:none}
.flow-steps strong{display:block;font-weight:var(--w-semi);color:var(--ink-strong)}
.flow-steps span{display:block;color:var(--ink-soft);font-size:var(--fs-sm);margin-top:var(--s1)}

/* domain panels */
.domain-grid{display:grid;grid-template-columns:1fr 1.1fr 1fr;gap:var(--s3)}
.panel h3{margin:0 0 var(--s3);font-size:var(--fs-h3);font-weight:var(--w-semi);color:var(--ink-strong)}
.panel h4,.screen-card h4,.domain-summary-card h3,.domain-detail-card h3{margin:var(--s4) 0 var(--s1);color:var(--ink-soft);font-size:var(--fs-xs);text-transform:uppercase;letter-spacing:.07em;font-weight:var(--w-medium)}
.state-map{font-family:var(--mono);display:grid;gap:var(--s2);place-items:center}
.state-node,.state-row span{border:1px solid var(--line);background:var(--inset);border-radius:var(--r-sm);padding:9px 13px;color:var(--ink);text-align:center}
.state-node.done{border-color:var(--accent-line);color:var(--accent-bright);background:var(--accent-tint)}
.state-row{display:grid;grid-template-columns:repeat(3,1fr);gap:var(--s2);width:100%}
.arrow{color:var(--ink-faint);font-weight:var(--w-bold)}
.gate-list{columns:2;margin:0;padding-left:20px;color:var(--ink)}
.note-line{border-left:3px solid var(--accent);padding-left:var(--s3);color:var(--ink)!important}

/* tables */
.nav-table{width:100%;border-collapse:collapse;margin-top:var(--s4);font-size:var(--fs-sm)}
.nav-table th,.nav-table td{text-align:left;vertical-align:top;border-bottom:1px solid var(--line);padding:11px 10px}
.nav-table th{position:sticky;top:65px;background:var(--inset);color:var(--ink-faint);text-transform:uppercase;letter-spacing:.07em;font-size:var(--fs-xs);font-weight:var(--w-medium);z-index:2}
.nav-table td{color:var(--ink)}
.nav-table code,.generated-list code,.screen-card code,.command-stack code{font-family:var(--mono);color:var(--accent);font-size:11px;background:var(--inset);border:1px solid var(--line);border-radius:6px;padding:2px 6px;display:inline-block;margin:2px 0}
.muted{color:var(--ink-faint);font-size:var(--fs-sm)}
.wide-details{margin-top:var(--s4)}
.inline-screen-link{text-decoration:none;font-weight:var(--w-semi);color:var(--ink-strong)}
.inline-screen-link:hover{text-decoration:underline;text-decoration-color:var(--accent)}

/* screen groups */
.screen-group{margin:var(--s5) 0}
.group-head{display:flex;align-items:baseline;justify-content:space-between;gap:16px;border-bottom:1px solid var(--line);padding:12px 0 10px;margin:0 0 var(--s3)}
.group-head h2{margin:0;font-size:21px;font-weight:var(--w-bold);letter-spacing:-.03em;color:var(--ink-strong)}
.group-count{font-family:var(--mono);font-size:12px;color:var(--ink-soft);font-weight:var(--w-medium)}
.group-description{margin:0 0 var(--s3);color:var(--ink-soft)}
.screen-num{width:1%;white-space:nowrap}
.screen-card-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:var(--s3);margin-top:var(--s4)}
.state-list{display:grid;gap:6px}
.state-list span{display:grid;gap:3px;color:var(--ink);border:1px solid var(--line);background:var(--inset);border-radius:var(--r-sm);padding:8px}
.compact-list{margin:var(--s2) 0 0;padding-left:18px;color:var(--ink)}

/* components / contracts */
.job-grid,.component-grid,.policy-grid,.mis-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:var(--s3)}
.component-grid{margin-bottom:18px}
.stack-chip{margin-bottom:var(--s2)}
.block-title{margin:var(--s6) 0 var(--s1);font-size:var(--fs-h3);font-weight:var(--w-semi);color:var(--ink-strong)}
.block-note{margin:0;color:var(--ink-soft);font-size:var(--fs-sm)}
.contract-table td:nth-child(3){max-width:320px}
.command-stack{display:grid;gap:var(--s1)}

/* domain groups — the summary spine is the one place a left accent earns its keep */
.domain-groups-section{background:var(--surface)}
.domain-group{margin:var(--s5) 0 0}
.domain-row{display:grid;grid-template-columns:340px minmax(0,1fr);gap:var(--s3)}
.domain-summary-card{position:sticky;top:86px;align-self:start;border-left:3px solid var(--accent)}
.domain-summary-card p,.domain-detail-card p{margin:0;color:var(--ink)}
.chip-block{display:flex;gap:6px;flex-wrap:wrap}
.domain-flow{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:var(--s2);counter-reset:domain-flow;margin:var(--s2) 0 18px;padding:0;list-style:none}
.domain-flow li{border:1px solid var(--line);border-radius:var(--r-sm);background:var(--inset);padding:10px 12px;color:var(--ink)}
.domain-flow li:before{counter-increment:domain-flow;content:counter(domain-flow,decimal-leading-zero);display:block;font-family:var(--mono);font-size:10px;color:var(--accent);font-weight:var(--w-bold);margin-bottom:3px}
.domain-tables{display:grid;gap:var(--s3)}
.compact-table{font-size:12px}
.checkpoint-list li{margin-bottom:6px}

/* policies — severity reads through the strength of one left edge, not through hue */
.policy-layout{display:grid;grid-template-columns:320px 1fr;gap:var(--s3)}
.order-panel ol{margin:0;padding-left:22px;color:var(--ink)}
.policy-grid{grid-template-columns:repeat(3,minmax(0,1fr))}
.policy-card{border-left:3px solid var(--line-strong)}
.policy-card.severity-blocker{border-left-color:var(--accent)}
.policy-card.severity-high{border-left-color:var(--ink-soft)}
.policy-card .meta-line span:last-child{color:var(--ink-soft)}
.policy-card.severity-blocker .meta-line span:last-child{color:var(--accent-bright)}
.mis-grid{margin-top:var(--s4);grid-template-columns:repeat(3,minmax(0,1fr))}

/* agent context */
.two-col{display:grid;grid-template-columns:1fr 1fr;gap:var(--s3)}
.generated-list ul{display:grid;gap:var(--s3);margin:0;padding-left:18px}
.generated-list li{color:var(--ink)}
.generated-list span{display:block;color:var(--ink-soft);font-size:var(--fs-sm);margin:var(--s1) 0}

.footer{color:var(--ink-faint);display:flex;justify-content:space-between;gap:16px;align-items:center;border-top:1px solid var(--line);padding:22px 0 46px}
.footer strong{color:var(--ink)}
.footer code{color:var(--accent);font-family:var(--mono)}
[hidden]{display:none!important}

@media (max-width:1180px){.topbar{grid-template-columns:1fr}.topbar-links,.pill-row{justify-content:flex-start}.hero-grid,.domain-grid,.policy-layout,.two-col,.domain-row{grid-template-columns:1fr}.domain-summary-card{position:static}.story-strip,.flow-steps,.job-grid,.component-grid,.policy-grid,.mis-grid,.domain-flow,.screen-card-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.flow-steps li:after{display:none}}
@media (max-width:720px){.hero,.nav-section,.footer{width:calc(100vw - 24px);padding:20px}.story-strip,.flow-steps,.job-grid,.component-grid,.policy-grid,.mis-grid,.domain-flow,.screen-card-grid{grid-template-columns:1fr}.topbar{padding:10px 12px}.topbar-links{display:none}.hero h1{font-size:34px}.nav-table{display:block;overflow-x:auto}.state-row{grid-template-columns:1fr}.group-head{display:block}.footer{display:block}}
</style>
"""

def render_script() -> str:
    return """
<script>
(() => {
  const search = document.querySelector('#search');
  const searchable = Array.from(document.querySelectorAll('[data-search]'));
  function applySearch() {
    const query = (search.value || '').trim().toLowerCase();
    searchable.forEach((element) => {
      const matches = !query || element.dataset.search.includes(query);
      element.hidden = !matches;
    });
  }
  search?.addEventListener('input', applySearch);
  document.querySelector('#expandAll')?.addEventListener('click', () => {
    document.querySelectorAll('details').forEach((details) => { details.open = true; });
  });
  document.querySelector('#collapseAll')?.addEventListener('click', () => {
    document.querySelectorAll('details').forEach((details) => { details.open = false; });
  });
})();
</script>
"""


def render_html(context: dict[str, dict[str, Any]]) -> str:
    updated_values = sorted({str(data.get("updated", "")) for data in context.values() if data.get("updated")})
    updated = updated_values[-1] if updated_values else "unknown"
    body = "\n".join(
        [
            render_header(context),
            render_story(context),
            render_domain(context),
            render_navigation(context),
            render_contracts(context),
            render_domain_groups(context),
            render_policies(context),
            render_agent_context(context),
            render_footer(),
        ]
    )
    return f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>요구사항/API/네비게이션 뷰어</title>
<meta name="generator" content="{GENERATE_COMMAND}">
<meta name="x-updated" content="{e(updated)}">
{render_style()}
</head>
<body>
{body}
{render_script()}
</body>
</html>
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if generated HTML is stale")
    args = parser.parse_args()

    try:
        output = render_html(load_context())
    except ValidationError as exc:
        print(f"ssot viewer validation failed: {exc}", file=sys.stderr)
        return 2

    if args.check:
        current = OUTPUT_PATH.read_text(encoding="utf-8") if OUTPUT_PATH.exists() else ""
        if current != output:
            print(f"stale generated file: {OUTPUT_PATH}", file=sys.stderr)
            return 1
        print(f"ssot viewer is up to date: {OUTPUT_PATH.relative_to(ROOT)}")
        return 0

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(output, encoding="utf-8")
    print(f"generated {OUTPUT_PATH.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
# Validate monitoring stack configuration as code.
#
# Checks:
#   1. prometheus.yml  — YAML syntax + required fields
#   2. recording-rules.yml — YAML syntax + rule structure
#   3. alert-rules.yml     — YAML syntax + rule structure
#   4. dashboards.yml      — Grafana provisioning YAML syntax
#   5. url-shortener-overview.json — JSON syntax + dashboard structure
#
# Usage:
#   python monitoring/validate-monitoring-config.py
#   python monitoring/validate-monitoring-config.py --strict  # fail on any warning
#
# Designed to run in CI (no Docker required) and locally.

from __future__ import annotations

import argparse, json, sys, textwrap
from pathlib import Path

try:
    import yaml
except ImportError:
    import subprocess, sys
    subprocess.check_call(
        [sys.executable, "-m", "pip", "install", "pyyaml", "-q"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    import yaml


# ── paths (relative to repo root) ──────────────────────────────────────────
MON_DIR = Path(__file__).resolve().parent
RULES_DIR = MON_DIR / "prometheus" / "rules"
DASH_DIR = MON_DIR / "grafana" / "dashboards"
PROV_DIR = MON_DIR / "grafana" / "provisioning" / "dashboards"

PROMETHEUS_YML = MON_DIR / "prometheus" / "prometheus.yml"
RECORDING_YML = RULES_DIR / "recording-rules.yml"
ALERTS_YML = RULES_DIR / "alert-rules.yml"
GRAFANA_YML = PROV_DIR / "dashboards.yml"
DASHBOARD_JSON = DASH_DIR / "url-shortener-overview.json"

ALL_FILES = [
    PROMETHEUS_YML,
    RECORDING_YML,
    ALERTS_YML,
    GRAFANA_YML,
    DASHBOARD_JSON,
]

errors: list[str] = []
warnings: list[str] = []


def error(path: str, msg: str) -> None:
    errors.append(f"FAIL  [{path}] {msg}")


def warn(path: str, msg: str) -> None:
    warnings.append(f"WARN  [{path}] {msg}")


def ok(path: str, msg: str) -> None:
    print(f"OK    [{path}] {msg}")


# ── YAML helpers ────────────────────────────────────────────────────────────
def load_yaml(path: Path) -> dict | list | None:
    """Load YAML, return None on any parse error (error already recorded)."""
    try:
        with open(path) as f:
            data = yaml.safe_load(f)
        if data is None:
            error(str(path), "file is empty or contains no YAML nodes")
            return None
        if not isinstance(data, (dict, list)):
            error(str(path), f"expected mapping or sequence, got {type(data).__name__}")
            return None
        return data
    except yaml.YAMLError as e:
        error(str(path), f"YAML parse error: {e}")
        return None


# ── Prometheus config validation ────────────────────────────────────────────
def validate_prometheus(path: Path) -> None:
    data = load_yaml(path)
    if data is None:
        return

    # Required top-level keys
    for key in ("global", "rule_files", "scrape_configs"):
        if key not in data:
            error(path.name, f"missing required top-level key '{key}'")

    # global block
    g = data.get("global", {})
    if isinstance(g, dict):
        for k in ("scrape_interval", "evaluation_interval"):
            if k not in g:
                warn(path.name, f"global.scrape_configs.{k} not set (using Prometheus defaults)")

    # rule_files — must reference rules/
    rf = data.get("rule_files")
    if isinstance(rf, list):
        rule_patterns = [r for r in rf if isinstance(r, str)]
        if not rule_patterns:
            error(path.name, "rule_files is empty")
        else:
            for pat in rule_patterns:
                if "rules" not in pat:
                    warn(path.name, f"rule_files pattern '{pat}' does not reference 'rules/' dir")
    else:
        error(path.name, "rule_files must be a list")

    # scrape_configs — at least one job
    sc = data.get("scrape_configs")
    if isinstance(sc, list) and len(sc) == 0:
        error(path.name, "scrape_configs is empty — no targets will be scraped")
    elif isinstance(sc, list):
        job_names = []
        for cfg in sc:
            if not isinstance(cfg, dict):
                error(path.name, f"scrape_config entry is not a mapping: {cfg}")
                continue
            name = cfg.get("job_name")
            if not name or not isinstance(name, str):
                error(path.name, "scrape_config missing required 'job_name' string")
            else:
                job_names.append(name)
                # Each config should have static_configs
                if "static_configs" not in cfg:
                    warn(path.name, f"job '{name}' has no static_configs")

        # Check for duplicate job names
        if len(job_names) != len(set(job_names)):
            error(path.name, "duplicate job_name found in scrape_configs")

    ok(path.name, "prometheus.yml structure valid")


# ── Rule file validation (recording + alert) ────────────────────────────────
def validate_rule_file(path: Path, expected_group_name: str | None = None) -> None:
    data = load_yaml(path)
    if data is None:
        return

    if not isinstance(data, dict):
        error(path.name, "expected top-level mapping")
        return

    groups = data.get("groups")
    if not groups:
        error(path.name, "missing required top-level 'groups' key")
        return
    if not isinstance(groups, list):
        error(path.name, "'groups' must be a list")
        return
    if len(groups) == 0:
        error(path.name, "'groups' list is empty")
        return

    for gi, grp in enumerate(groups):
        if not isinstance(grp, dict):
            error(path.name, f"group[{gi}] is not a mapping")
            continue

        grp_name = grp.get("name")
        if not grp_name:
            error(path.name, f"group[{gi}] missing 'name'")
            continue

        if expected_group_name and grp_name != expected_group_name:
            warn(path.name, f"group name '{grp_name}' differs from expected '{expected_group_name}'")

        rules = grp.get("rules")
        if not rules or not isinstance(rules, list):
            error(path.name, f"group '{grp_name}' missing or invalid 'rules' list")
            continue

        for ri, rule in enumerate(rules):
            if not isinstance(rule, dict):
                error(path.name, f"group '{grp_name}' rule[{ri}] is not a mapping")
                continue

            # Recording rule fields
            is_alert = "alert" in rule
            record = rule.get("record")

            if is_alert:
                # Alert rule: requires alert + expr
                if not rule.get("alert"):
                    error(path.name, f"group '{grp_name}' rule[{ri}] alert rule missing 'alert' name")
                if "expr" not in rule:
                    error(path.name, f"group '{grp_name}' rule[{ri}] alert rule missing 'expr'")
                if "labels" in rule and not isinstance(rule["labels"], dict):
                    error(path.name, f"group '{grp_name}' rule[{ri}] 'labels' must be a mapping")
                if "annotations" in rule and not isinstance(rule["annotations"], dict):
                    error(path.name, f"group '{grp_name}' rule[{ri}] 'annotations' must be a mapping")
                ok(path.name, f"rule '{rule['alert']}' valid (alert)")
            elif record:
                # Recording rule: requires record + expr
                if not isinstance(record, str):
                    error(path.name, f"group '{grp_name}' rule[{ri}] 'record' must be a string")
                if "expr" not in rule:
                    error(path.name, f"group '{grp_name}' rule[{ri}] missing 'expr'")
                if "labels" in rule and not isinstance(rule["labels"], dict):
                    error(path.name, f"group '{grp_name}' rule[{ri}] 'labels' must be a mapping")
                ok(path.name, f"rule '{record}' valid (recording)")
            else:
                error(path.name, f"group '{grp_name}' rule[{ri}] missing 'record' or 'alert'")

    ok(path.name, f"all rules valid ({len(groups)} group(s))")


# ── Grafana provisioning YAML validation ────────────────────────────────────
def validate_grafana_provisioning(path: Path) -> None:
    data = load_yaml(path)
    if data is None:
        return

    if not isinstance(data, dict):
        error(path.name, "expected top-level mapping")
        return

    providers = data.get("providers")
    if not providers:
        error(path.name, "missing required 'providers' key")
        return
    if not isinstance(providers, list) or len(providers) == 0:
        error(path.name, "'providers' must be a non-empty list")
        return

    for pi, prov in enumerate(providers):
        if not isinstance(prov, dict):
            error(path.name, f"provider[{pi}] is not a mapping")
            continue

        for key in ("name", "type", "options"):
            if key not in prov:
                error(path.name, f"provider[{pi}] missing required field '{key}'")

        # 'type' must be 'file' for file-based provisioning
        if prov.get("type") != "file":
            warn(path.name, f"provider[{pi}] type is '{prov.get('type')}', expected 'file'")

        opts = prov.get("options", {})
        if isinstance(opts, dict) and "path" not in opts:
            error(path.name, f"provider[{pi}] options missing required 'path' field")

        # Check allowUiUpdates if explicitly set (best practice for dashboard as code)
        if prov.get("allowUiUpdates") is True:
            warn(path.name, f"provider[{pi}] allowUiUpdates is True — set to false for 'dashboard as code'")

    ok(path.name, "grafana provisioning YAML valid")


# ── Dashboard JSON validation ──────────────────────────────────────────────
def validate_dashboard_json(path: Path) -> None:
    try:
        with open(path) as f:
            data = json.load(f)
    except json.JSONDecodeError as e:
        error(path.name, f"invalid JSON: {e}")
        return
    except FileNotFoundError:
        error(path.name, "file not found")
        return

    if not isinstance(data, dict):
        error(path.name, "expected top-level JSON object")
        return

    # Required dashboard fields
    for key in ("title", "uid", "panels"):
        if key not in data:
            error(path.name, f"missing required field '{key}'")

    if "title" in data and not isinstance(data["title"], str):
        error(path.name, "'title' must be a string")

    if "uid" in data and not isinstance(data["uid"], str):
        error(path.name, "'uid' must be a string")

    # Panels must be a non-empty list
    panels = data.get("panels")
    if panels is not None and not isinstance(panels, list):
        error(path.name, "'panels' must be a list")
    elif isinstance(panels, list) and len(panels) == 0:
        warn(path.name, "'panels' list is empty")

    # Check panel structure (each panel needs id + type)
    if isinstance(panels, list):
        ids = []
        for pi, panel in enumerate(panels):
            if not isinstance(panel, dict):
                error(path.name, f"panel[{pi}] is not a mapping")
                continue
            pid = panel.get("id")
            if pid is None:
                error(path.name, f"panel[{pi}] missing 'id'")
            else:
                ids.append(pid)
            ptype = panel.get("type")
            if not ptype:
                warn(path.name, f"panel[{pi}] (id={pid}) missing 'type'")

        if ids and len(ids) != len(set(ids)):
            error(path.name, "duplicate panel 'id' found")

    ok(path.name, "dashboard JSON valid")


# ── Main ─────────────────────────────────────────────────────────────────────
def main() -> int:
    parser = argparse.ArgumentParser(description="Validate monitoring configuration as code")
    parser.add_argument("--strict", action="store_true", help="fail on any warning")
    args = parser.parse_args()

    print("=" * 70)
    print("Validating monitoring stack configuration as code")
    print("=" * 70)

    # 1. Prometheus config
    print("\n1. Prometheus config")
    validate_prometheus(PROMETHEUS_YML)

    # 2. Recording rules
    print("\n2. Recording rules")
    validate_rule_file(RECORDING_YML, expected_group_name=None)

    # 3. Alert rules
    print("\n3. Alert rules")
    validate_rule_file(ALERTS_YML, expected_group_name=None)

    # 4. Grafana provisioning YAML
    print("\n4. Grafana provisioning")
    validate_grafana_provisioning(GRAFANA_YML)

    # 5. Dashboard JSON
    print("\n5. Dashboard JSON")
    validate_dashboard_json(DASHBOARD_JSON)

    # ── Summary ────────────────────────────────────────────────────────────
    print("\n" + "=" * 70)
    print(f"Monitoring config validation: {len(errors)} error(s), {len(warnings)} warning(s)")
    print("=" * 70)

    if errors:
        print("\nErrors:")
        for e in errors:
            print(f"  {e}")

    if warnings:
        print("\nWarnings:")
        for w in warnings:
            print(f"  {w}")

    if errors or (args.strict and warnings):
        print("\nRESULT: VALIDATION FAILED")
        return 1
    else:
        print("\nRESULT: ALL VALIDATION CHECKS PASSED")
        return 0


if __name__ == "__main__":
    sys.exit(main())

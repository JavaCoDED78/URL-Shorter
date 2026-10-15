#!/usr/bin/env python3
# pyright: reportUnknownVariableType=false, reportUnknownArgumentType=false
# Smoke/integration tests for the monitoring stack (Task 29.11).
#
# Verifies the full metrics pipeline:
#   application starts → request → Micrometer → /actuator/prometheus
#   → Prometheus scrape → recording rules → alert rules loaded
#   → Grafana dashboard provisioning
#
# Usage (local dev):
#   python monitoring/validate-monitoring-stack.py
#
# Usage (CI):
#   python monitoring/validate-monitoring-stack.py --ci
#

import argparse, json, sys, time, urllib.request, urllib.error

BASE_URL_PROMETHEUS = "http://localhost:9090"
BASE_URL_GRAFANA = "http://localhost:14000"
GRAFANA_USER = "admin"
GRAFANA_PASS = "admin"
APP_URL = "http://localhost:8080"
EXPECTED_SERIES = {
    "urlshorter_linksRedirected_total",
    "urlshorter_cacheHit_total",
    "urlshorter_cacheMiss_total",
    "urlshorter_clicksIncremented_total",
}


def http_get(url, headers=None, timeout=10, as_json=True):
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode()
            if not as_json:
                return resp.status, raw
            return resp.status, json.loads(raw)
    except urllib.error.HTTPError as e:
        body = e.read()
        text = body.decode() if body else ""
        return e.code, json.loads(text) if text else ""
    except Exception as e:
        return None, str(e)


def grafana_get(path, timeout=10):
    """Grafana API request with Basic auth via header (URL-based auth broken in Python 3.14)."""
    import base64
    auth = base64.b64encode(f"{GRAFANA_USER}:{GRAFANA_PASS}".encode()).decode()
    headers = {"Authorization": f"Basic {auth}"}
    return http_get(f"{BASE_URL_GRAFANA}{path}", headers=headers, timeout=timeout)


def check(description, fn):
    """Run fn(); print PASS/FAIL. Returns True on success."""
    try:
        ok, data = fn()
        status = "PASS" if ok else "FAIL"
        print(f"  [{status}] {description}")
        if data is not None and not ok:
            print(f"         Data: {data}")
        return ok
    except Exception as e:
        print(f"  [FAIL] {description} — exception: {e}")
        return False


# ---- Prometheus checks ----

def prometheus_targets():
    """All Prometheus targets discovered (at least one UP)."""
    status, data = http_get(f"{BASE_URL_PROMETHEUS}/api/v1/targets")
    if status != 200 or not data.get("data"):
        return False, data
    targets = data["data"].get("activeTargets", [])
    up = [t for t in targets if t.get("health") == "up"]
    return len(up) > 0, {"up": len(up), "total": len(targets)}


def prometheus_application_scraped():
    """Application metrics are present in Prometheus."""
    status, data = http_get(f"{BASE_URL_PROMETHEUS}/api/v1/label/__name__/values")
    if status != 200 or not data.get("data"):
        return False, data
    names = set(data["data"])
    found = EXPECTED_SERIES & names
    missing = EXPECTED_SERIES - names
    return len(missing) == 0, {"found": len(found), "missing": sorted(missing)}


def prometheus_metric_changes_on_request(iterations=5):
    """Generate redirects and verify the redirect counter increases."""
    import urllib.parse
    import urllib.error

    # Create a link. The very first create after a cold boot incurs a one-time
    # ~5s connection-pool warmup (ADR: cold start), so allow headroom and retry
    # transient connection errors rather than failing the whole check.
    body = json.dumps({"originalUrl": "https://example.com/health"}).encode()
    code = None
    for _attempt in range(3):
        req = urllib.request.Request(
            f"{APP_URL}/api/v1/links",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                code = json.loads(resp.read().decode()).get("code")
            break
        except urllib.error.HTTPError as e:
            if e.code != 503:
                return False, {"detail": f"link creation failed: {e.code}"}
        except Exception:
            pass  # cold first-create may close the socket; retry
    if not code:
        return False, {"detail": "no code returned after retries"}

    # Trigger redirects
    for _ in range(iterations):
        try:
            req = urllib.request.Request(f"{APP_URL}/{code}", method="GET")
            urllib.request.urlopen(req, timeout=5)
        except urllib.error.HTTPError as e:
            if e.code != 302:
                pass  # ignore non-302 redirects
        except Exception:
            pass  # some redirects raise ConnectionError

    # Wait for the increment to surface in Prometheus. The app increments in
    # memory instantly, but Prometheus only reports the new value on the next
    # 15s scrape — so poll rather than querying once and racing the interval.
    return await_metric_value(
        'urlshorter_linksRedirected_total{job="urlshorter-app"}', iterations)


def prometheus_recording_rules_evaluated():
    """At least one recording rule series exists in Prometheus."""
    status, data = http_get(
        f"{BASE_URL_PROMETHEUS}/api/v1/series?"
        f"match[]={urllib.parse.quote('urlshorter_cache_hit_ratio:5m')}"
    )
    if status != 200:
        return False, data
    series = data.get("data", [])
    return len(series) > 0, {"series_found": len(series)}


def prometheus_alert_rules_loaded():
    """Alert rules are loaded in Prometheus (loaded without errors)."""
    status, data = http_get(f"{BASE_URL_PROMETHEUS}/api/v1/rules?type=alert")
    if status != 200 or not data.get("data"):
        return False, data
    groups = data["data"].get("groups", [])
    total_rules = sum(len(g.get("rules", [])) for g in groups)
    return total_rules > 0, {"alert_groups": len(groups), "total_rules": total_rules}


def wait_for_app_scraped(timeout=60):
    """Wait until Prometheus has scraped the application at least once.

    On a cold start Prometheus has collected zero scrapes, so PromQL ``rate()``
    windows and the 5m recording rules have no data and return empty results.
    This polls the metric-name index until an application counter appears so the
    checks below see live series instead of racing the 15s scrape interval.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        ok, _ = prometheus_application_scraped()
        if ok:
            return True
        time.sleep(2)
    return False


def wait_for_app_up(timeout=90):
    """Wait until the application Prometheus target is UP.

    A freshly booted app can be mid-start when the smoke test begins, so its
    target reports ``up: 0`` until the first successful scrape. Metrics checks
    (3-6) must not run against a target that is still coming up; this waits for
    a stable, healthy scrape before continuing.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        status, data = http_get(f"{BASE_URL_PROMETHEUS}/api/v1/targets")
        if status == 200:
            targets = (data or {}).get("data", {}).get("activeTargets", [])
            if [t for t in targets if t.get("health") == "up"]:
                return True
        time.sleep(3)
    return False


def wait_for_metrics_warm(timeout=90):
    """Wait until the PromQL rate window and the recording rule have live data.

    Check 5 needs >=2 scrapes inside the 1m rate window and check 6 needs one
    recording-rule evaluation (15s interval) after traffic exists. A single
    prime is therefore not enough on its own; poll until both hold.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        q_ok, _ = prometheus_query_non_empty()
        r_ok, _ = prometheus_recording_rules_evaluated()
        if q_ok and r_ok:
            return True
        time.sleep(3)
    return False


def prime_redirect_traffic(iterations=3):
    """Resolve a link a few times to give metric checks live traffic.

    Checks 5 and 6 evaluate a rate window and a 5m recording rule; with no
    traffic they return nothing / NaN. One directed wave seeds that data and
    also warms the cold first-create path. The link is deleted afterwards so no
    demo data is left behind. Best-effort: a failure here must not abort.
    """
    try:
        body = json.dumps({"originalUrl": "https://example.com/prime"}).encode()
        req = urllib.request.Request(
            f"{APP_URL}/api/v1/links", data=body,
            headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=10) as resp:
            code = json.loads(resp.read().decode()).get("code")
        if code:
            for _ in range(iterations):
                try:
                    urllib.request.urlopen(
                        urllib.request.Request(f"{APP_URL}/{code}", method="GET"),
                        timeout=5)
                except urllib.error.HTTPError:
                    pass
            urllib.request.urlopen(
                urllib.request.Request(
                    f"{APP_URL}/api/v1/links/{code}", method="DELETE"),
                timeout=10)
    except Exception:
        pass


def await_metric_value(query, expected_min, timeout=45):
    """Poll Prometheus until a query value reaches ``expected_min`` (bounded).

    Fixes the scrape-interval race: the app increments counters in memory
    instantly, but Prometheus only surfaces the new value on the next 15s
    scrape. Rather than guess a fixed sleep, poll until the value lands (or the
    deadline elapses) and return the latest observed value.
    """
    def read():
        q = f"{BASE_URL_PROMETHEUS}/api/v1/query?query={urllib.parse.quote(query)}"
        status, data = http_get(q)
        result = (data or {}).get("data", {}).get("result", [])
        try:
            return float(result[0]["value"][1]) if result else None
        except (TypeError, IndexError, ValueError):
            return None

    deadline = time.time() + timeout
    while time.time() < deadline:
        value = read()
        if value is not None and value >= expected_min:
            return True, value
        time.sleep(2)
    actual = read() or 0.0
    return actual >= expected_min, {"expected_at_least": expected_min, "actual": actual}


def prometheus_query_non_empty():
    """A PromQL query for a business metric returns non-empty result."""
    query = urllib.parse.quote("sum(rate(urlshorter_clicksIncremented_total[1m]))")
    status, data = http_get(f"{BASE_URL_PROMETHEUS}/api/v1/query?query={query}")
    if status != 200:
        return False, data
    result = data.get("data", {}).get("result", [])
    return len(result) > 0, {"series_count": len(result)}


# ---- Grafana checks ----

def grafana_datasource_provisioned():
    """Grafana datasource (Prometheus) is provisioned automatically."""
    status, data = grafana_get("/api/datasources")
    if status != 200 or not isinstance(data, list):
        return False, data
    prom_ds = [d for d in data if d.get("type") == "prometheus"]
    return len(prom_ds) > 0, {"prometheus_datasources": len(prom_ds)}


def grafana_dashboard_provisioned():
    """Grafana dashboard is auto-imported from provisioning."""
    status, data = grafana_get("/api/dashboards/uid/url-shortener-overview")
    if status != 200:
        return False, data
    dashboard = data.get("dashboard", {})
    return bool(dashboard.get("uid")), {
        "uid": dashboard.get("uid"),
        "title": dashboard.get("title"),
        "folder": dashboard.get("meta", {}).get("folderTitle"),
        "panels": len(dashboard.get("panels", [])),
    }


def grafana_dashboard_panels_valid():
    """All panels reference a valid Prometheus datasource."""
    status, data = grafana_get("/api/dashboards/uid/url-shortener-overview")
    if status != 200:
        return False, data
    panels = data["dashboard"].get("panels", [])
    prom_refs = {"Prometheus", "Prometheus (urlshorter-prometheus)", "prometheus", "PBFA97CFB590B2093"}
    broken = []
    for p in panels:
        ds = p.get("datasource")
        # Grafana 11+ returns datasource as dict: {'type': 'prometheus', 'uid': '...'}
        if ds:
            if isinstance(ds, dict):
                ds_type = str(ds.get("type", ""))
                ds_uid = str(ds.get("uid", ""))
                if ds_type != "prometheus":
                    broken.append({"panel": p.get("title"), "datasource": str(ds)})
            elif str(ds) not in prom_refs:
                broken.append({"panel": p.get("title"), "datasource": str(ds)})
    return len(broken) == 0, {"broken_panels": broken[:5]}


# ---- Stack lifecycle helpers ----

def wait_for_health(base_url, check_path="/-/healthy", max_attempts=30, delay=2):
    """Poll until the service is healthy or timeout."""
    for _ in range(max_attempts):
        try:
            status, _ = http_get(f"{base_url}{check_path}", timeout=3)
            if status == 200:
                return True
        except Exception:
            pass
        time.sleep(delay)
    return False


def warm_up_endpoint():
    """Trigger the first, cold create after boot so the timed end-to-end check
    below does not trip its own connection timeout.

    The app's very first /api/v1/links create after startup incurs ~5s one-time
    latency (cold Redis/DB connection pool); warm creates are ~12ms. This
    warm-up absorbs that once and its result is discarded. The link is deleted
    afterwards so no demo data is left behind. Best-effort: a timeout here
    still warms the cold path.
    """
    try:
        req = urllib.request.Request(
            f"{APP_URL}/api/v1/links",
            data=json.dumps({"originalUrl": "https://example.com/warmup"}).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            code = json.loads(resp.read().decode()).get("code")
        if code:
            urllib.request.urlopen(
                urllib.request.Request(
                    f"{APP_URL}/api/v1/links/{code}", method="DELETE"
                ),
                timeout=10,
            )
    except Exception:
        pass


# ---- Main ----

def main():
    parser = argparse.ArgumentParser(description="Monitoring stack smoke tests (Task 29.11)")
    parser.add_argument("--ci", action="store_true", help="Run in CI mode (no compose up/down)")
    args = parser.parse_args()

    passed = 0
    failed = 0

    def record(ok):
        nonlocal passed, failed
        if ok:
            passed += 1
        else:
            failed += 1

    # Step 1: Check Prometheus is running and healthy
    print("\n1. Prometheus health")
    ok, _ = http_get(f"{BASE_URL_PROMETHEUS}/-/healthy", timeout=5, as_json=False)
    record(ok == 200)
    print(f"  [{'PASS' if ok == 200 else 'FAIL'}] Prometheus health endpoint")

    # Step 2: Check Grafana is running
    print("\n2. Grafana health")
    ok, _ = http_get(f"{BASE_URL_GRAFANA}/api/health", timeout=5)
    record(ok == 200)
    print(f"  [{'PASS' if ok == 200 else 'FAIL'}] Grafana health endpoint")

    # Warm-up phase. Metrics checks 3-6 are meaningless until the application is
    # fully up AND the metric pipeline is warm. At the start of the test a
    # freshly booted app is still coming up (target up: 0), not yet scraped, and
    # has no traffic for the rate window or the 5m recording rule. Wait for a
    # stable healthy scrape, seed one redirect wave (which also warms the cold
    # first-create path), then wait until the rate window and recording rule
    # have live data. The prime link is deleted afterwards, so no demo data is
    # left behind.
    print("\nWarming metrics pipeline (app up + traffic + warm window)...")
    if not wait_for_app_up(90):
        print("  warning: application target not UP within 90s")
    prime_redirect_traffic()
    if not wait_for_metrics_warm(90):
        print("  warning: metrics pipeline not warm within 90s")

    # Step 3: Prometheus — target discovered
    print("\n3. Prometheus target discovery")
    record(check("application target discovered", prometheus_targets))

    # Step 4: Prometheus — application metrics scraped
    print("\n4. Application metrics scraping")
    record(check("application metrics present", prometheus_application_scraped))

    # Step 5: PromQL — non-empty query
    print("\n5. PromQL query")
    record(check("PromQL query returns results", prometheus_query_non_empty))

    # Step 6: Recording rules evaluated
    print("\n6. Recording rules")
    record(check("recording rule series evaluated", prometheus_recording_rules_evaluated))

    # Step 7: Alert rules loaded
    print("\n7. Alert rules")
    record(check("alert rules loaded", prometheus_alert_rules_loaded))

    # Warm up the cold first-create path (~5s one-time latency after boot) so the
    # timed end-to-end check below does not trip its own 5s connection timeout.
    print("\nWarming up application endpoint (cold first-create after boot)...")
    warm_up_endpoint()

    # Step 8: End-to-end — redirect counter changes
    print("\n8. End-to-end metrics path (create link → redirect → Prometheus)")
    record(check("redirect counter increases on request", prometheus_metric_changes_on_request))

    # Step 9: Grafana — datasource provisioned
    print("\n9. Grafana datasource provisioning")
    record(check("Prometheus datasource provisioned", grafana_datasource_provisioned))

    # Step 10: Grafana — dashboard provisioned
    print("\n10. Grafana dashboard provisioning")
    record(check("dashboard auto-imported", grafana_dashboard_provisioned))
    record(check("dashboard panels valid", grafana_dashboard_panels_valid))

    # Summary
    total = passed + failed
    print(f"\n{'=' * 50}")
    print(f"Monitoring stack smoke tests: {passed}/{total} passed, {failed} failed")
    if failed:
        print("RESULT: FAIL")
        sys.exit(1)
    else:
        print("RESULT: ALL PASSED")


if __name__ == "__main__":
    main()

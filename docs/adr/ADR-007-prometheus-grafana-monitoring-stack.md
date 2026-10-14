# ADR-007: Prometheus + Grafana monitoring stack

- Status: **Accepted**
- Date: 2026-10 (Sprint 29)

## Context

The application needs operational observability — metrics, dashboards, and alerts —
to detect failures, measure performance, and support on-call triage. The existing
Micrometer + Actuator foundation exports business and infrastructure metrics in
Prometheus exposition format; a collection and visualization layer is required.

Options evaluated: Prometheus + Grafana (open-source, industry-standard), Datadog
(SaaS, expensive for a pet project), InfluxDB + Grafana (time-series database,
adds another moving part), or no dedicated monitoring (unacceptable for a
production-oriented service).

## Decision

**Prometheus + Grafana**, provisioned entirely from Git:

- **Prometheus** scrapes `/actuator/prometheus` via HTTP, evaluates recording
  rules and alert rules, and stores time-series data locally.
- **Grafana** reads from the Prometheus datasource, renders dashboards, and
  exposes a web UI for operators.

### Why Prometheus?

- Pull-based scraping matches the microservice model — Prometheus discovers
  targets by service name (Docker Compose) rather than each service pushing
  metrics to a central collector.
- PromQL is the de facto standard query language for time-series data;
  recording rules reduce query complexity on the dashboard layer.
- Single binary, no external dependencies beyond storage (local disk for now).
- Compatible with the Micrometer Prometheus registry — zero transformation
  between application and storage.
- Remote-write ready: if the project migrates to Thanos/Cortex later, the
  scrape format and recording/alert rules remain valid.

### Why Grafana?

- Dashboard provisioning from JSON files in Git — reproducible, versioned,
  no manual UI configuration.
- Native Prometheus datasource — no plugin overhead.
- Supports alert rule definitions in Git (though the project prefers
  Prometheus alert rules for simplicity).
- Free and open-source; the project's local stack runs it via Docker Compose
  with no third-party licensing.

### Scrape model

- **Pull-based**: Prometheus scrapes the Spring Boot actuator endpoint every
  15 seconds (`scrape_interval`), with a 10-second timeout (`scrape_timeout`).
- Each scrape of `/actuator/prometheus` returns a flat text block; Micrometer
  appends `application` labels (e.g. `application: URL-Shorter`).
- Recording rules (evaluated every 15 seconds) pre-compute derived metrics
  (e.g. `urlshorter_redirects:rate5m`) to reduce query complexity and
  dashboard load.
- Alert rules (evaluated every 15 seconds) produce alerts that can be
  forwarded to external notification channels (currently unconfigured — the
  stack is development/local).

### Retention expectations

- Default Prometheus retention is 15 days of disk storage. For the local/dev
  stack this is sufficient to observe trends across working days.
- No long-term retention or archival is implemented; if needed in production,
  Thanos or Cortex can be added as a remote-write target without changing
  scrape configuration or rules.

### Network placement

| Service     | Container Port | Local Development Exposure | Production Placement |
|-------------|---------------|----------------------------|---------------------|
| urlshorter-app | 8080      | `host:8080` (public)       | Ingress controller (HTTPS) |
| Prometheus  | 9090          | `host:9090` (public)       | Internal VPC only; NetworkPolicy denies external ingress |
| Grafana     | 3000          | `host:14000` (public)      | Private SSO/authentication proxy |
| PostgreSQL  | 5432          | not exposed to host        | Internal VPC only |
| Redis       | 6379          | not exposed to host        | Internal VPC only |

Production deployment uses Kubernetes NetworkPolicy to restrict Prometheus and
Grafana to internal traffic only. The application port (8080) is the sole
publicly-exposed port, served through the ingress controller.

See also: [`docs/production-ingress-policy.md`](../production-ingress-policy.md)
for the full network exposure and credential management rules.

## Consequences

- **Pro:** Zero-cost, open-source stack; no third-party service dependencies for local dev.
- **Pro:** Configuration as code — every environment (dev, CI, staging, production)
  is reproducible from Git.
- **Pro:** Prometheus recording rules reduce dashboard query complexity and latency.
- **Pro:** Grafana provisioning eliminates manual dashboard setup — a clean Docker
  Compose start produces a fully configured monitoring stack.
- **Pro:** Remote-write ready for production scaling (Thanos, Cortex).
- **Con:** Prometheus stores data on local disk — not designed for multi-node
  high availability without external tools (Thanos, long-term storage).
  Acceptable for the current scale.
- **Con:** Alerting is built into Prometheus but the local stack has no
  notification channels configured — production requires integration with
  PagerDuty, Slack, or email before this becomes actionable.

## Alternatives considered

- **Datadog / New Relic / Dynatrace** (SaaS) — rejected: licensing cost is
  unjustified for a pet project; SaaS also means less control over data retention
  and configuration reproducibility.
- **InfluxDB + Telegraf + Grafana** — rejected: adds InfluxDB and Telegraf as
  additional services to reason about; Prometheus integrates directly with
  Micrometer without a sidecar or push gateway.
- **Push-based (StatsD / Fluentd / Logstash)** — rejected: the push model
  requires each service to maintain a connection to a central collector;
  Prometheus' pull model scales better under ephemeral container orchestration
  and avoids a single point of failure.
- **No dedicated monitoring** — rejected: without dashboards and alerts the
  application cannot be diagnosed in production.

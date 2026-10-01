# ADR-008: Monitoring configuration as code

- Status: **Accepted**
- Date: 2026-10 (Sprint 29)

## Context

The monitoring stack (Prometheus + Grafana) needs configuration that defines
how metrics are collected, how they are pre-computed, and how they are
visualized. Historically, many teams manage this configuration interactively
through the Prometheus or Grafana UI — a process that is hard to review,
reproduce, and audit.

The project requires that a clean local environment can reproduce the
monitoring setup from repository files alone, without any manual UI clicks.

## Decision

All monitoring configuration is versioned in Git under `monitoring/`:

```
monitoring/
├── prometheus/
│   ├── prometheus.yml          # scrape configuration, global settings
│   └── rules/
│       ├── recording-rules.yml # pre-computed derived metrics
│       └── alert-rules.yml     # operational alerts
└── grafana/
    ├── dashboards/
    │   └── url-shortener-overview.json  # main dashboard
    └── provisioning/
        ├── datasources/
        │   └── prometheus.yml      # datasource provisioning
        └── dashboards/
            └── dashboards.yml      # dashboard provisioning
```

### What is stored in Git

| Artifact | File(s) | Purpose |
|----------|---------|---------|
| Scrape config | `prometheus/prometheus.yml` | Which targets to scrape, interval, labels |
| Recording rules | `prometheus/rules/recording-rules.yml` | Pre-computed metrics (e.g. rate averages) |
| Alert rules | `prometheus/rules/alert-rules.yml` | Operational alerts (availability, latency, errors) |
| Grafana dashboard JSON | `grafana/dashboards/url-shortener-overview.json` | Visual panels, queries, layout |
| Datasource provisioning | `grafana/provisioning/datasources/prometheus.yml` | Auto-configure Prometheus as Grafana datasource |
| Dashboard provisioning | `grafana/provisioning/dashboards/dashboards.yml` | Auto-load dashboard JSON into Grafana |

### Prohibited operations

- **No manual configuration through the Prometheus UI.** The Prometheus
  container receives `prometheus.yml` as a read-only mounted file; changes
  require a repository commit and Docker Compose restart.
- **No manual configuration through the Grafana UI.** Grafana is provisioned
  from the `provisioning/` directory; any changes made via the UI are lost
  on container restart. Dashboard changes must be made in the JSON file.
- **`allowUiUpdates: false`** is explicitly set in the Grafana dashboard
  provisioning config to enforce this rule at the Grafana configuration level.

### CI validation

Every pull request triggers validation of monitoring artifacts:

- **YAML validation**: `prometheus.yml`, `recording-rules.yml`,
  `alert-rules.yml`, and provisioning files must be syntactically valid YAML.
- **PromQL validation**: recording rule expressions must be parsable PromQL.
- **Dashboard JSON validation**: the Grafana dashboard JSON is validated
  against the expected panel structure (18 panels expected).
- **Smoke test**: `monitoring/validate-monitoring-stack.py` verifies the
  complete pipeline — application metric changes propagate through Prometheus
  scrape, recording rules, and Grafana queries.

See also: `monitoring/validate-monitoring-config.py` for standalone validation
that runs without Docker.

## Consequences

- **Pro:** Reproducible environments — `docker compose up` produces a fully
  configured monitoring stack with no manual steps.
- **Pro:** Reviewable changes — monitoring configuration changes go through
  the same code review process as application code.
- **Pro:** Auditable history — the git log shows who changed which rule,
  dashboard, or provisioning file, and when.
- **Pro:** Environment parity — the same configuration can be deployed to
  dev, CI, staging, and production (with environment-specific overrides
  via separate files or Kubernetes ConfigMaps).
- **Pro:** CI gates invalid configuration — broken PromQL or malformed YAML
  is caught before deployment.
- **Con:** Grafana dashboard JSON is verbose (64 KB for the main dashboard);
  editing it by hand is impractical — use Grafana's export or a templating
  tool for complex changes.
- **Con:** Prometheus configuration changes require a container restart
  (unless using the remote configuration endpoint, which is not enabled).
  This is intentional to prevent runtime drift.

## Alternatives considered

- **Manual UI configuration** — rejected: not reproducible, not reviewable,
  lost on container restart, and no audit trail.
- **Helm / Kustomize templating** — acceptable for production Kubernetes
  deployments, but the repository standard is Docker Compose for local dev;
  Helm charts can be added later as a deployment artifact without changing
  the source of truth (the files under `monitoring/`).
- **Grafana Terraform provider** — adds a dependency on Terraform for
  provisioning; for the current scale, YAML provisioning files are simpler
  and equally effective.

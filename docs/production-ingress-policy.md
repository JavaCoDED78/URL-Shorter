# production-ingress-policy.md

# Production Ingress Policy — URL Shorter Monitoring Stack

## 1. Purpose

Define network exposure rules for the monitoring stack (Prometheus, Grafana)
in production environments to prevent the metrics layer from becoming an
additional attack surface.

## 2. Principle

Monitoring data is **internal-only**. Prometheus and Grafana are never exposed
to public internet traffic. Access is restricted to internal networks and
authorized service accounts.

## 3. Port Exposure

| Service     | Container Port | Production Host Exposure | Reasoning |
|-------------|---------------|--------------------------|-----------|
| urlshorter-app | 8080       | Yes (ingress controller) | Public API |
| Prometheus  | 9090          | **No**                    | Internal scraping target; data source for Grafana |
| Grafana     | 3000          | **No**                    | Dashboard viewer; Prometheus is the only datasource |
| PostgreSQL  | 5432          | **No**                    | Private database; app connects via internal network |
| Redis       | 6379          | **No**                    | Private cache; app connects via internal network |

**Local development** (`docker-compose.yml`): Prometheus (`9090`) and Grafana
(`14000`) ports are mapped for convenience. Production deployments use a
Kubernetes ingress or reverse proxy that does not expose these ports.

## 4. Actuator Endpoint Exposure

| Endpoint              | Production Access | Notes |
|-----------------------|-------------------|-------|
| `/actuator/health`    | Internal          | Health checks for orchestrators |
| `/actuator/health/readiness` | Internal     | Readiness probes for orchestrators |
| `/actuator/prometheus` | Internal only     | Prometheus scrape target; no authentication required in default config |
| `/actuator/env`       | **Denied**        | Returns environment variables; must be disabled in production |
| `/actuator/beans`     | **Denied**        | Spring bean details; not needed externally |

**Recommendation**: Disable all actuator endpoints except `health` and
`prometheus` in production via:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

## 5. Credential Management

| Credential | Storage | Production Handling |
|-----------|---------|---------------------|
| Grafana admin password | `GF_SECURITY_ADMIN_PASSWORD` env var | Set via Kubernetes secret or HashiCorp Vault; never committed to Git |
| PostgreSQL credentials | `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` env vars | Set via Kubernetes secret or Vault; never committed to Git |
| Redis credentials | `REDIS_HOST` / `REDIS_PORT` (+ auth via Spring `spring.data.redis` overrides) | Use TLS and authentication in production |

## 6. Metrics Content Rules

- Metrics must **never** contain passwords, tokens, API keys, or connection strings.
- Metrics must **never** contain full URLs with query parameters or user data.
- The `originalUrl` must **never** be used as a metric label — it is a
  high-cardinality, user-provided value and is explicitly forbidden by the
  observability contract (`docs/observability-contract.md`, §4 Forbidden
  labels). Business counters such as `urlshorter_linksRedirected_total` carry
  only the `application` label. Short-link codes and aliases are likewise
  never label values.
- Prometheus remote_write (if used) must not transmit data to untrusted endpoints.

**Verification**: Metrics were audited during Task 29.13 — no secrets or user
data patterns found in `/actuator/prometheus` output.

## 7. Dashboard Variable Safety

Dashboard template variables in `monitoring/grafana/dashboards/url-shortener-overview.json`
use safe queries against `job`, `instance`, and `environment` labels. No variable
substitutes secrets, credentials, or user-generated content.

## 8. Network Placement

### Recommended Production Architecture

```
Internet → Ingress Controller (HTTPS, auth) → urlshorter-app:8080
Internal Network (VPC/subnet):
  ├── Prometheus:9090  ← scrapes app:8080/actuator/prometheus
  ├── Grafana:3000     ← reads from Prometheus (no external access)
  ├── PostgreSQL:5432  ← app connects via internal network
  └── Redis:6379       ← app connects via internal network
```

### Kubernetes Deployment

- Use a `NetworkPolicy` to deny ingress to Prometheus and Grafana pods from
  external sources.
- Prometheus should only be accessible from Grafana and internal monitoring
  services (e.g. Thanos, Cortex).
- Grafana should only be accessible via a private authentication proxy or SSO.

## 9. TLS

- Ingress to `urlshorter-app` must use TLS (HTTPS).
- Internal service-to-service communication (Prometheus → app) uses HTTP on the
  internal network; TLS is not required but recommended for high-security
  environments (use mTLS).
- Grafana → Prometheus uses HTTP on the internal network; TLS is recommended.

## 10. Review Cadence

This policy must be reviewed:
- After any change to `docker-compose.yml` or Kubernetes manifests affecting port exposure.
- Quarterly for compliance with organizational security standards.
- After any security incident involving the monitoring stack.

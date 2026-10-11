# ADR-010: SSRF defence on the create endpoint is best-effort (single DNS-resolution at create time)

- Status: **Accepted**
- Date: 2026-10 (Sprint 20.4 hardening)

## Context

`UrlValidator` (Task 4.2) structurally validated original URLs: scheme must be
in an allow-list (`http`/`https`) and a host must be present. This catches
`javascript:`/`data:` and host-less URLs, but not **server-side request
forgery (SSRF)**: a URL can have a public-looking scheme and host while pointing
at an internal destination.

Concrete bypasses of the structural check:

| Input | Structural check | Problem |
|-------|------------------|---------|
| `http://169.254.169.254/latest/meta-data/` | scheme + host OK | Cloud metadata endpoint (link-local) |
| `http://127.0.0.1:8080/actuator` / `http://localhost/` | scheme + host OK | loopback |
| `http://[::1]/` | scheme + host OK | IPv6 loopback |
| `http://10.0.0.5/`, `http://192.168.1.1/` | scheme + host OK | RFC 1918 private |
| `http://2130706433/` | scheme + host OK | decimal form of `127.0.0.1` |

The redirect endpoint is *stateless*: it returns `302 Location` and never opens
a connection to the stored destination itself, so the practical impact here is
lower than for an outbound fetcher. But a stored internal URL is still an
integrity/abuse vector — an operator browsing the link list, or a client that
follows the redirect blindly on a trusted origin — can be steered at internal
resources (metadata service, admin panels, loopback admin UIs).

## Decision

The create path validates that the host does **not** resolve to an internal
address. `UrlValidator` resolves the `URI` host with `InetAddress.getByName` and
rejects the URL when the address is loopback, link-local, any-local, multicast,
RFC 1918/ULA private, or in the IPv4 `240.0.0.0/4` reserved/broadcast range:

```java
if (address.isLoopbackAddress()
    || address.isLinkLocalAddress()
    || address.isAnyLocalAddress()
    || address.isMulticastAddress()) return true;
// 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 240.0.0.0/4, fc00::/7
```

A single numeric-IPv4 check (`2130706433`) is why resolution — not just `URI`
parsing — is required: `URI` accepts the bare decimal form and reports a
hoststring the JDK would never produce from a normal hostname.

### Why it is best-effort by design

The defence has two inherent limits, both accepted deliberately:

1. **Fail-open on DNS resolution failure.** `InetAddress.getByName` throws
   `UnknownHostException` when DNS is unreachable or slow. The validator treats
   that as *accept* rather than *reject*. Failing closed on DNS failure would
   (a) deny creation for legitimate hosts during any transient outage and
   (b) turn DNS availability into a denial-of-service vector against the
   create endpoint — an attacker who can influence the resolver can then block
   any URL whose host needs a lookup. Failing open keeps the endpoint available
   and limits SSRF to hosts that *can* be resolved *now*.

2. **No DNS-rebinding defence.** Resolution happens exactly once, at creation.
   A host that resolves public at create time and private at redirect time
   (or whose DNS is later repointed) is not caught. Closing the gap would
   require re-validating the destination on every redirect, which contradicts
   the stateless, `302`-only nature of the redirect endpoint and would make the
   redirect path depend on DNS. It is therefore out of scope here.

3. **No bound on concurrent lookups, so burst load serializes.** Each create
   that needs a hostname lookup calls `InetAddress.getByName`, which shares a
   single JVM-wide resolver cache. Under a burst of hundreds of concurrent
   creates the resolver's internal locking saturates and the lookups stall,
   holding pooled PostgreSQL connections open until the connection-timeout —
   that is what starved the pool under the 300-way `ConcurrencyIT`
   (`CREATED=10`). `UrlValidator` guards the lookups with a fixed-count
   semaphore, so at most a handful resolve at once; the trade-off is that a
   burst serializes (adds latency to some creates) rather than deadlocking
   (all fail). The count is chosen well below the Hikari pool size so no
   blocked create can hold a pooled connection. This keeps the endpoint
   *available* under load at the cost of latency, consistent with the
   fail-open-on-DNS-failure stance.

## Consequences

- **Pro:** The obvious internal targets (metadata service, loopback,
  link-local, RFC 1918/ULA, decimal-IPv4) are rejected at create with `400`.
  This is the single, well-understood defence line for a stateless service.
- **Pro:** No extra infrastructure (DNS proxy, egress allow-list, outbound
  fetcher) is introduced for a risk that is already low because the service
  does not fetch.
- **Con:** A determined attacker who controls DNS can still bind a public
  hostname to an internal address. This is acknowledged and accepted for this
  stateless redirector; a real outbound fetcher (future) would need a
  separate, fail-closed, rebinding-aware SSRF policy.
- **Con:** Unit tests for address classification use IP literals, not DNS
  names, so they are deterministic and DNS-free in CI. A literal public IPv4
  (e.g. `1.2.3.4`) is accepted because, like a public hostname, its resolution
  is unambiguous and the classification is exercised directly.

## Alternatives considered

- **Fail closed on resolution failure.** Rejected: turns DNS availability into a
  DoS vector against the unauthenticated create endpoint.
- **Re-validate on every redirect.** Rejected: contradicts the stateless,
  `302`-only redirect endpoint and introduces a DNS dependency on the hot read
  path.
- **Egress allow-list / network policy.** Out of scope for this stateless
  redirector — there is no outbound fetch, so no egress to constrain. A future
  fetcher-facing component is the proper home for that control.
- **Block only loopback + link-local, not private ranges.** Rejected: private
  ranges (`10/8`, `172.16/12`, `192.168/16`) are the same class of internal
  target on an on-premises or VPC deployment; a partial list leaves the door
  open to the common case.

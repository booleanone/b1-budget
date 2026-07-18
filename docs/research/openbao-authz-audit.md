# OpenBao authorization and audit constraints for local development

Research date: 2026-07-18

## Answer

B1 Budget should use a dedicated OpenBao OIDC auth mount backed by a dedicated confidential Keycloak client. Keycloak group membership should be emitted as a full-path string list in the ID token; the OpenBao role should both require the B1 Budget developer group as an exact bound claim and use that claim to resolve an external OpenBao identity group. That external group, rather than the OIDC role itself, owns one read-only B1 Budget development policy.

The login token should be a five-minute service token with a five-minute explicit maximum TTL, no default policy, no renewal by the client, and explicit self-revocation after configuration has been fetched. It may read only enumerated KV v2 `data` paths and revoke itself. It must not list or read KV metadata endpoints, write secrets, retain upstream OIDC tokens, or persist locally.

This gives individual authentication and audit attribution while distributing shared development secrets. It does not make the generated `.env` files individual; those remain shared values projected into component-specific files.

## Operator contract

### Dedicated Keycloak client and callback

Use a dedicated Keycloak OpenID Connect client, for example `openbao-b1-budget-development`, with:

- Client authentication **on** (a confidential client); the client secret exists only in OpenBao's server-side auth-mount configuration, never in this repository. OpenBao's OIDC configuration requires an OIDC discovery URL, client ID, and client secret, and its Keycloak guide identifies the realm base URL as the discovery URL ([OpenBao OIDC configuration](https://openbao.org/docs/2.4.x/auth/jwt/#configuration), [OpenBao Keycloak provider configuration](https://openbao.org/docs/auth/jwt/oidc-providers/keycloak/)).
- Standard/Authorization Code flow **on**; Direct Access Grants, service-account flows, and Keycloak Authorization Services **off**. OpenBao's browser flow uses Authorization Code with PKCE ([OpenBao OIDC authentication](https://openbao.org/docs/2.4.x/auth/jwt/#oidc-authentication)); Keycloak identifies Standard Flow as its Authorization Code flow ([Keycloak client capability configuration](https://www.keycloak.org/docs/latest/server_admin/#_oidc_clients_capability_config)).
- One exact valid redirect URI: `http://localhost:8250/oidc/callback`. Do not configure `*`, a host wildcard, or a path wildcard. OpenBao documents this loopback callback and requires the provider and OpenBao role redirect lists to align; Keycloak uses exact, case-sensitive matching and warns that exact redirect patterns are safer ([OpenBao redirect URIs](https://openbao.org/docs/2.4.x/auth/jwt/#redirect-uris), [Keycloak valid redirect URIs](https://www.keycloak.org/docs/latest/server_admin/#_oidc_clients)).

The fixed port is part of the operator/repository contract. `dev:up` must fail preflight if it cannot bind loopback port 8250. The CLI-free launcher can use OpenBao's unauthenticated `oidc/auth_url` endpoint, open the returned URL, receive `state` and `code` on the loopback listener, and submit them to OpenBao's `oidc/callback` API; those endpoints are explicitly public parts of the auth method and do not require the `bao` CLI ([OpenBao JWT/OIDC API: authorization URL and callback](https://openbao.org/api-docs/auth/jwt/#oidc-authorization-url-request)).

### Keycloak membership claim

Create or reuse the Keycloak group `/b1-budget/developers`. On the dedicated client, configure a Group Membership protocol mapper with:

- token claim name `groups`;
- full group paths enabled;
- ID token inclusion enabled;
- access-token and UserInfo inclusion disabled unless another consumer has a separately justified need.

Keycloak's first-party `GroupMembershipMapper` maps user membership and supports full paths and explicit ID-token inclusion ([Keycloak `GroupMembershipMapper` API](https://www.keycloak.org/docs-api/latest/javadocs/org/keycloak/protocol/oidc/mappers/GroupMembershipMapper.html)). Full paths avoid collisions between identically named groups in different hierarchies. Keep unrelated realm/client roles out of this client: Keycloak recommends turning **Full Scope Allowed** off and explicitly scoping roles when role claims are used ([Keycloak role scope mappings](https://www.keycloak.org/docs/latest/server_admin/#_role_scope_mappings)).

Use `sub` as OpenBao's `user_claim`, not email or username. OIDC defines `iss` plus `sub` as the stable identifier; `preferred_username` and email are not guaranteed unique or stable ([OpenID Connect Core, claim stability](https://openid.net/specs/openid-connect-core-1_0.html#ClaimStability)). Map `preferred_username` only as human-readable token metadata named `username`.

### Dedicated OpenBao auth mount and role

Mount a dedicated JWT/OIDC auth method, for example at `auth/b1-budget-development`, rather than adding the loopback callback to an administrator-oriented OIDC mount. Configure it with the Keycloak realm discovery URL, the dedicated client ID and secret, and default role `b1-budget-development` ([OpenBao OIDC configuration parameters](https://openbao.org/api-docs/auth/jwt/#createupdate-role)).

The `b1-budget-development` role contract is:

```text
role_type              = "oidc"
user_claim             = "sub"
groups_claim           = "groups"
bound_claims_type      = "string"
bound_claims           = { groups = "/b1-budget/developers" }
claim_mappings         = { preferred_username = "username" }
oidc_scopes            = ["profile"]
allowed_redirect_uris  = ["http://localhost:8250/oidc/callback"]
token_type             = "service"
token_ttl              = "5m"
token_max_ttl          = "5m"
token_explicit_max_ttl = "5m"
token_no_default_policy = true
token_num_uses         = 0
verbose_oidc_logging   = false
```

Do not configure `oauth2_metadata`; it can return Keycloak access, ID, or refresh tokens in OpenBao token metadata. Keep verbose OIDC logging off because it can log sensitive OIDC tokens and claims ([OpenBao role parameters](https://openbao.org/api-docs/auth/jwt/#createupdate-role)).

`bound_claims` makes membership a login precondition, while `groups_claim` creates OpenBao identity group aliases from the string list ([OpenBao bound claims and group claims](https://openbao.org/docs/2.4.x/auth/jwt/#bound-claims), [OpenBao JWT/OIDC API](https://openbao.org/api-docs/auth/jwt/#createupdate-role)). Create an external OpenBao identity group carrying policy `b1-budget-development-read`, then create a group alias named `/b1-budget/developers` for the dedicated auth mount accessor ([OpenBao identity groups](https://openbao.org/api-docs/secret/identity/group/), [OpenBao group aliases](https://openbao.org/api-docs/secret/identity/group-alias/)). This makes the Keycloak group-to-policy relationship explicit and inspectable.

Five minutes is a B1 Budget policy choice, not an OpenBao default. The explicit maximum is the important hard cap: OpenBao documents that it cannot be extended later. A service token is required because service tokens are manually revocable while batch tokens are not ([OpenBao JWT/OIDC token parameters](https://openbao.org/api-docs/auth/jwt/#createupdate-role), [OpenBao service versus batch tokens](https://openbao.org/docs/2.5.x/concepts/tokens/#service-vs-batch-token-lease-handling)). The launcher should call `POST /auth/token/revoke-self` in a `finally` path after its last OpenBao operation ([OpenBao revoke-self API](https://openbao.org/api-docs/auth/token/#revoke-a-token-self)). A second login does not invalidate an earlier token, and external-group membership changes are reflected on a later login or renewal, so the short hard cap bounds delayed revocation after Keycloak group removal ([OpenBao policy behavior](https://openbao.org/docs/2.5.x/concepts/policies/), [OpenBao external groups](https://openbao.org/docs/2.5.x/concepts/identity/#external-vs-internal-groups)).

### Capability-owned KV v2 paths and policy

Use capability names, not consumer names and not a generic `shared` path. With a KV v2 mount named `kv`, paths follow this pattern:

```text
kv/data/b1-budget/development/postgresql
kv/data/b1-budget/development/<another-capability>
```

Compose and the resource server may both project values from `postgresql`; the web manifest must not reference it. OpenBao authorization controls what this developer identity may fetch, while the repository manifests control which subset is written to each generated file.

Enumerate every allowed `data` path in the policy instead of granting a broad prefix. Grant no metadata/list capability:

```hcl
path "kv/data/b1-budget/development/postgresql" {
  capabilities = ["read"]
}

# Repeat for each manifest-declared capability path.

path "auth/token/revoke-self" {
  capabilities = ["update"]
}
```

OpenBao policies deny by default and distinguish `read` from create/update/delete/list ([OpenBao policy semantics](https://openbao.org/docs/2.5.x/concepts/policies/)). KV v2 reads use `/<mount>/data/<path>`; the same response already contains `created_time`, `deletion_time`, `destroyed`, `version`, and custom metadata, so the launcher does not need access to `/<mount>/metadata/...` ([OpenBao KV v2 read API](https://openbao.org/api-docs/secret/kv/kv-v2/#read-secret-version)). Listing is especially unnecessary: KV list responses are not policy-filtered and key names must be treated as non-secret ([OpenBao KV v2 list API](https://openbao.org/api-docs/secret/kv/kv-v2/#list-secrets)).

Store OpenBao secret values as JSON strings even when the manifest's logical type is integer or boolean. The manifest should parse and validate the logical type after retrieval. OpenBao's audit documentation says strings in JSON request/response data are HMACed by default, while integers and booleans pass through in plaintext ([OpenBao audit sensitive-information behavior](https://openbao.org/docs/next/audit/#sensitive-information)).

### Version metadata is a vector, not one generation

KV v2 versions are scoped to each secret path. Each read returns that path's version metadata, and omitting `version` reads the latest version ([OpenBao KV v2 read API](https://openbao.org/api-docs/secret/kv/kv-v2/#read-secret-version)). OpenBao exposes no multi-path transaction or cross-path snapshot in this API.

Therefore `dev:up` can report a version vector such as `{postgresql: 7, other-capability: 3}`, but it cannot honestly report one OpenBao generation without another publication convention. This creates a separate specification decision: either require a common release marker in all capability records and reject mixed markers, or publish immutable release-scoped paths behind a pointer updated last. In either case, read and validate the full remote bundle before replacing any local generated file.

### Audit contract

Enable at least two audit devices, keep `log_raw=false` and `hmac_accessor=true`, protect audit transport/storage, and monitor audit-device failures. OpenBao recommends multiple devices, logs almost every request and response, hashes sensitive strings by default, and will not complete requests if no enabled device can record them ([OpenBao audit devices](https://openbao.org/docs/next/audit/), [OpenBao audit telemetry](https://openbao.org/docs/internals/telemetry/metrics/audit/)).

Current OpenBao audit records carry the authenticated entity ID, display name, policies, identity policies, mapped metadata, token type/TTL, request ID, operation, path, mount, and remote address. This makes `entity_id` the durable correlation key and mapped `username` a human-readable aid; the username must not be treated as the identity key ([OpenBao audit formatter source at `73f12f3`](https://github.com/openbao/openbao/blob/73f12f3805a5b679475a77bb8f471d68af4b9c3a/internal/audit/format.go)). Audit logs are security-sensitive: current source hashes the client token and optionally its accessor, but leaves the auth metadata and entity ID available for attribution ([OpenBao audit hashing source at `73f12f3`](https://github.com/openbao/openbao/blob/73f12f3805a5b679475a77bb8f471d68af4b9c3a/internal/audit/hashstructure.go)).

An operator must be able to correlate, for one developer and one run:

1. OIDC callback/login success or denial;
2. each exact KV `read` path and its response;
3. the final `auth/token/revoke-self` call;
4. the same entity ID and token accessor (HMACed per audit-device salt) across those records.

Do not expect audit logs to prove which generated consumer file received a value; that projection happens locally after the OpenBao read and belongs to repository-side diagnostics, not the server audit trail.

## Operator verification checklist

- [ ] Dedicated Keycloak client exists; client authentication and Standard Flow are on, unnecessary flows are off.
- [ ] Its only development callback is exactly `http://localhost:8250/oidc/callback`; no wildcard callback is present.
- [ ] Client secret is present only in OpenBao server configuration.
- [ ] An authorized user's ID token contains `groups: ["/b1-budget/developers", ...]`; an unauthorized user's token does not.
- [ ] `sub` is the OpenBao entity alias and mapped metadata contains only the intended human-readable `username`.
- [ ] Dedicated OpenBao mount and role match the constraints above; `verbose_oidc_logging` and `oauth2_metadata` are off.
- [ ] External OpenBao group alias exactly matches `/b1-budget/developers` on the dedicated mount accessor and carries only `b1-budget-development-read`.
- [ ] Authorized login yields a nonempty entity ID, the intended identity policy, no `default` policy, and an effective TTL/hard cap of no more than five minutes.
- [ ] Unauthorized login fails the bound group claim.
- [ ] Policy simulation/real requests can read every enumerated `kv/data/b1-budget/development/<capability>` path and `update` only `auth/token/revoke-self`.
- [ ] The same token is denied KV create, update, patch, delete, list, metadata reads, other B1 environments, and unrelated OpenBao paths.
- [ ] Every KV read returns non-deleted, non-destroyed data plus a version; version vectors or release markers are surfaced to the client.
- [ ] At least two healthy audit devices are active; raw logging is off and accessor HMAC is on.
- [ ] A test run produces correlated OIDC, KV-read, and revoke-self audit records with an individual entity ID and no plaintext test secret.
- [ ] Removing a test user from the Keycloak group blocks their next login; the already-issued test token becomes unusable no later than its five-minute hard cap.

## Newly sharp decisions

1. Choose the cross-path publication model: common release marker with mismatch rejection, or immutable release paths plus an atomic pointer.
2. Decide the exact capability path inventory once the configuration manifest is specified; the ACL should enumerate that inventory rather than use a blanket prefix.
3. Decide audit retention, access, and alerting policy. The server-side fields and failure behavior are known, but retention duration and the operational log sink are outside this research ticket.


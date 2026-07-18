# CLI-free OpenBao OIDC browser flow

## Question

What exact browser OIDC sequence can a repository-owned Node/pnpm tool use on
macOS and Linux to obtain an OpenBao token without requiring the `bao` CLI or
persisting the token?

## Answer

Use OpenBao's HTTP API to reproduce its **client callback** flow. The local
tool is not an OAuth client of Keycloak: OpenBao remains the confidential OIDC
client and owns the Keycloak client secret, OAuth state, OIDC nonce, PKCE
verifier/challenge, authorization-code exchange, ID-token verification, and
role-claim validation. The Node tool owns only a loopback listener, a separate
cryptographic `client_nonce`, system-browser launch, and the returned OpenBao
token in memory. OpenBao documents the authorization URL and callback APIs,
and its current source shows that it generates both the OIDC request and PKCE
verifier server-side. ([OpenBao API](https://openbao.org/api-docs/auth/jwt/#oidc-authorization-url-request),
[OpenBao request creation source](https://github.com/openbao/openbao/blob/73f12f3805a5b679475a77bb8f471d68af4b9c3a/internal/builtin/credential/jwt/path_oidc.go#L854-L890))

The normal-path redirect URI should be the exact fixed URI
`http://127.0.0.1:8250/oidc/callback`. Bind the listener specifically to
`127.0.0.1`, never `0.0.0.0`. An HTTP loopback callback is an accepted desktop
OAuth pattern because it stays on the device; the IP literal is preferable to
`localhost`. The fixed port permits exact Keycloak registration and matches
OpenBao's established CLI port while avoiding redirect wildcards. A port
conflict is therefore a preflight failure. ([RFC 8252 section 7.3](https://www.rfc-editor.org/rfc/rfc8252.html#section-7.3),
[OpenBao redirect documentation](https://openbao.org/docs/auth/jwt/#redirect-uris),
[OpenBao loopback validation source](https://github.com/openbao/openbao/blob/73f12f3805a5b679475a77bb8f471d68af4b9c3a/internal/builtin/credential/jwt/path_oidc.go#L911-L933))

## Exact sequence

1. The tool verifies that `https://vault.booleanone.com` is reachable with
   normal TLS certificate validation, then binds an HTTP server to
   `127.0.0.1:8250` before creating an OIDC request. Binding first prevents the
   browser from winning a race against listener startup.

2. The tool generates a fresh, unpredictable `client_nonce` using the
   operating system CSPRNG and holds it only in memory. OpenBao defines this as
   a client-to-OpenBao nonce distinct from the protocol's OIDC nonce; the
   official CLI currently uses 20 random base-62 characters. The custom tool
   should provide it even though it is optional in client callback mode, then
   provide the same value at callback. ([OpenBao API](https://openbao.org/api-docs/auth/jwt/#oidc-authorization-url-request),
   [OpenBao CLI source](https://github.com/openbao/openbao/blob/73f12f3805a5b679475a77bb8f471d68af4b9c3a/internal/builtin/credential/jwt/cli.go#L310-L329),
   [OpenBao nonce check](https://github.com/openbao/openbao/blob/73f12f3805a5b679475a77bb8f471d68af4b9c3a/internal/builtin/credential/jwt/path_oidc.go#L331-L343))

3. With no OpenBao token, the tool sends JSON to:

   ```http
   POST https://vault.booleanone.com/v1/auth/<mount>/oidc/auth_url
   Content-Type: application/json

   {
     "role": "<development-role>",
     "redirect_uri": "http://127.0.0.1:8250/oidc/callback",
     "client_nonce": "<fresh-random-value>"
   }
   ```

   The auth mount and role are repository configuration, not user input. The
   tool requires a successful JSON response containing a non-empty
   `data.auth_url`. OpenBao deliberately returns an empty URL for many invalid
   role or redirect conditions so the unauthenticated endpoint does not reveal
   configuration; that case must be reported as an operator configuration
   error, without guessing the cause. ([OpenBao API](https://openbao.org/api-docs/auth/jwt/#oidc-authorization-url-request),
   [OpenBao authorization URL source](https://github.com/openbao/openbao/blob/73f12f3805a5b679475a77bb8f471d68af4b9c3a/internal/builtin/credential/jwt/path_oidc.go#L654-L851))

4. OpenBao discovers Keycloak from its configured realm issuer and returns a
   Keycloak authorization URL containing the registered client ID, exact
   redirect URI, `response_type=code`, `scope=openid`, OAuth `state`, OIDC
   `nonce`, and an S256 PKCE challenge. The tool treats this URL as opaque and
   never constructs Keycloak protocol requests itself. OpenBao's current OIDC
   implementation uses the Authorization Code flow with PKCE by default.
   ([OpenBao OIDC documentation](https://openbao.org/docs/auth/jwt/#oidc-authentication),
   [OpenBao PKCE source](https://github.com/openbao/openbao/blob/73f12f3805a5b679475a77bb8f471d68af4b9c3a/internal/builtin/credential/jwt/path_oidc.go#L854-L890),
   [Keycloak endpoint documentation](https://www.keycloak.org/securing-apps/oidc-layers))

5. The tool prints a copyable authorization URL and attempts to open it in the
   operating system's default browser. Failure to launch a browser is not an
   authentication failure: the tool continues waiting and tells the developer
   to open the printed URL manually. An external browser is the recommended
   desktop OAuth user agent and permits an existing Keycloak browser SSO
   session to complete the flow without another credential prompt when realm
   policy allows it. ([RFC 8252 sections 4 and 6](https://www.rfc-editor.org/rfc/rfc8252.html#section-4),
   [OpenBao browser behavior](https://openbao.org/docs/auth/jwt/#oidc-login-cli),
   [Keycloak session behavior](https://www.keycloak.org/docs/latest/server_admin/#managing-user-sessions))

6. Keycloak authenticates the user or reuses the browser SSO session, then
   redirects the browser with a one-time authorization code and the original
   state:

   ```http
   GET /oidc/callback?code=<authorization-code>&state=<opaque-state> HTTP/1.1
   Host: 127.0.0.1:8250
   ```

   If authorization fails, Keycloak redirects with `state`, `error`, and
   potentially `error_description` and `error_uri` instead. The listener
   accepts only this path and a single GET response, rejects duplicate query
   parameters, does not log the request URL, and ignores unrelated requests
   such as `/favicon.ico`. Keycloak describes the code as one-time and
   short-lived. ([Keycloak Authorization Code flow](https://www.keycloak.org/docs/latest/server_admin/#_oidc-auth-flows),
   [OpenBao callback parameters](https://openbao.org/api-docs/auth/jwt/#oidc-callback))

7. The Node process, not browser JavaScript, forwards the provider response to
   OpenBao over HTTPS, adding its private `client_nonce`:

   ```http
   GET https://vault.booleanone.com/v1/auth/<mount>/oidc/callback
       ?state=<opaque-state>
       &code=<authorization-code>
       &client_nonce=<original-random-value>
   ```

   For an error response it forwards `state`, `error`, `error_description`,
   `error_uri`, and `client_nonce` instead. Every value must be encoded as a
   query parameter; neither the full callback URL nor its parameters may be
   logged. OpenBao checks the cached state and client nonce, exchanges the code
   with Keycloak using its confidential client credentials and cached PKCE
   verifier, verifies the ID token and OIDC nonce, applies role constraints,
   and returns the OpenBao token at `auth.client_token`.
   ([OpenBao callback API](https://openbao.org/api-docs/auth/jwt/#oidc-callback),
   [OpenBao callback and exchange source](https://github.com/openbao/openbao/blob/73f12f3805a5b679475a77bb8f471d68af4b9c3a/internal/builtin/credential/jwt/path_oidc.go#L259-L400),
   [OpenBao nonce validation source](https://github.com/openbao/openbao/blob/73f12f3805a5b679475a77bb8f471d68af4b9c3a/internal/builtin/credential/jwt/path_oidc.go#L402-L458))

8. Only after receiving a structurally valid response with
   `auth.client_token` does the tool show a static success page and close the
   listener. It keeps the token in process memory, uses it to fetch the
   configuration generation, and never writes it to a file, environment
   variable, command argument, browser page, or log. The browser response
   should contain no external resources and send `Cache-Control: no-store` and
   `Referrer-Policy: no-referrer`.

9. The tool uses the official CLI's two-minute callback deadline as its first
   version deadline. On timeout, cancellation, signal, or callback failure it
   closes the listener and discards the nonce and any token. OpenBao's current
   server-side OIDC request cache expires after ten minutes, so abandoning a
   local attempt does not leave durable state. ([OpenBao CLI timeout source](https://github.com/openbao/openbao/blob/73f12f3805a5b679475a77bb8f471d68af4b9c3a/internal/builtin/credential/jwt/cli.go#L245-L260),
   [OpenBao request timeout source](https://github.com/openbao/openbao/blob/73f12f3805a5b679475a77bb8f471d68af4b9c3a/internal/builtin/credential/jwt/path_oidc.go#L22-L25))

## Responsibility boundaries

| Concern | Owner |
| --- | --- |
| Keycloak realm, browser authentication, SSO cookie, authorization code | Keycloak |
| Keycloak client ID/secret, discovery, OAuth state, OIDC nonce, PKCE, code exchange, ID-token and claim validation | OpenBao |
| Loopback listener, `client_nonce`, browser launch/fallback, callback relay, OpenBao token memory lifetime | Repository Node tool |
| Application configuration files | Later configuration-generation stage; never the OIDC callback page |

The repository tool must not register a second public Keycloak client, receive
or validate a Keycloak token, call Keycloak's token endpoint, know the Keycloak
client secret, or implement PKCE independently. Doing so would create a second
authentication architecture rather than using OpenBao's supported OIDC API.

## Required server-side contract

The operator must configure:

- An OpenBao JWT/OIDC auth mount at a fixed documented `<mount>`, with
  `oidc_discovery_url` equal to the Keycloak realm URL, the Keycloak client ID
  and secret, `oidc_response_mode=query`, and `oidc_response_types=code`.
  Query/code are OpenBao's defaults, but the contract should state them
  explicitly. ([OpenBao config API](https://openbao.org/api-docs/auth/jwt/#configure))
- An OpenBao development role with `role_type=oidc`,
  `callback_mode=client`, and exact `allowed_redirect_uris` containing
  `http://127.0.0.1:8250/oidc/callback`. The role also defines the user/group
  claim bindings and narrow development policies, which are separate
  specification decisions. ([OpenBao role API](https://openbao.org/api-docs/auth/jwt/#createupdate-role))
- A confidential Keycloak OIDC client (`Client authentication` on) with
  Standard Flow on, an exact Valid Redirect URI of
  `http://127.0.0.1:8250/oidc/callback`, and PKCE method S256 required where the
  deployed Keycloak version exposes that setting. Direct Access Grants,
  Implicit Flow, service accounts, and device authorization are unnecessary.
  OpenBao, not the Node tool, stores this client's secret.
  ([OpenBao's Keycloak guide](https://openbao.org/docs/auth/jwt/oidc-providers/keycloak/),
  [Keycloak client settings](https://www.keycloak.org/docs/latest/server_admin/#_oidc_clients))

Do not configure Keycloak Web Origins for the loopback URI and do not enable
OpenBao CORS for this tool. CORS governs browser JavaScript cross-origin
requests; here the browser performs top-level navigation while all OpenBao API
requests originate from Node. ([Keycloak Web Origins documentation](https://www.keycloak.org/docs/latest/server_admin/#_oidc_clients))

## Failure behavior

- **Listener port unavailable:** fail before requesting `auth_url`; identify
  port 8250 as occupied without attempting to kill the owning process.
- **OpenBao DNS, network, or TLS failure:** fail closed and do not open the
  browser. Never disable TLS verification.
- **Non-2xx, invalid JSON, empty `data.auth_url`, or wrong scheme:** fail as an
  OpenBao/operator configuration problem. Do not expose raw response bodies
  that may contain diagnostics unsuitable for routine logs.
- **Browser launch failure:** remain waiting and show the manual URL.
- **User denial or Keycloak error:** forward the standardized error fields to
  OpenBao and show a concise failure; do not echo `error_description` into
  persistent logs.
- **Wrong path/method, duplicate/missing query fields, or unsolicited local
  requests:** return a generic local 400/404 and keep waiting for the one valid
  callback until the deadline.
- **Expired/missing state, nonce or client-nonce mismatch, code-exchange
  failure, claim/policy rejection, or missing `auth.client_token`:** fail
  closed, show OpenBao's sanitized error, close the listener, and discard all
  transient material.
- **Timeout or interruption:** close the listener and discard transient state.
  A retry starts a completely new auth URL and `client_nonce`.

## Alternatives deliberately not selected

OpenBao 2.6 also exposes **direct callback mode**: Keycloak redirects to the
OpenBao server, while the local client polls `POST
/v1/auth/<mount>/oidc/poll` using server-returned `state`, `poll_interval`, and
its `client_nonce`. It eliminates the loopback listener but requires a direct
callback role, a server redirect URI, polling behavior, and an OpenBao version
that supports that mode; OpenBao also presents a browser confirmation page by
default. It is a viable later simplification, not the normal path selected for
this specification. ([OpenBao callback-mode documentation](https://openbao.org/docs/auth/jwt/#redirect-uris),
[OpenBao poll API](https://openbao.org/api-docs/auth/jwt/#oidc-poll))

Device flow and Keycloak's out-of-band copy/paste flow are also outside the
agreed local-interactive-desktop scope.

## Facts exposed for later decisions

- The configuration/security specification still needs to choose the
  development role's OpenBao token TTL, use limit, renewal behavior, and
  whether the setup tool performs a best-effort `revoke-self` after all secret
  reads. "Not persisted" does not mean a token ceases to exist server-side if
  the process crashes.
- The deployed OpenBao version and configured auth mount/role must be verified
  before implementation. Direct callback and polling are current OpenBao 2.6
  features, while the selected client callback API is the compatibility path.
- The specification should decide whether two minutes is sufficient for the
  project's Keycloak MFA flow. It is the current official CLI behavior, not a
  protocol limit; OpenBao retains request state for ten minutes.


# Environment-file compatibility and safe rendering constraints

## Question

What compatibility and safety constraints govern generating
`deployment/compose/.env`, `apps/resourceserver/.env`, and
`apps/web/.env.local` as one protected configuration generation on macOS and
Linux?

## Answer

The three files can be projections of one validated logical configuration,
but they are not one portable dotenv format. Docker Compose, Next.js, and the
future resource-server launcher have different loading, expansion, and
precedence rules. The generator therefore needs a target-specific serializer
and parser round-trip check for every projection.

The safe portable guarantee is: validate and stage the complete bundle before
changing any target; replace each target atomically; do not start or reconfigure
any consumer until all targets carry the same generation identifier; and use a
small owner-only transaction record to detect and recover an interrupted
multi-file commit. POSIX makes one pathname replacement atomic, not three
replacements across separate directories, so “all files become visible at the
same instant” is not a truthful macOS/Linux guarantee.

## Consumer contracts

| Target | Native meaning | Required contract |
| --- | --- | --- |
| `deployment/compose/.env` | Compose CLI interpolation input | Invoke Compose with an explicit `--env-file` and project directory; ensure the Compose model explicitly projects required values into container environments. |
| `apps/web/.env.local` | Next.js project-local environment input | Let Next.js load it from the web project root, but reject or neutralize higher-precedence ambient and `.env.$NODE_ENV.local` values for manifest-declared keys. Never place a secret in a `NEXT_PUBLIC_` key. |
| `apps/resourceserver/.env` | No native Spring Boot meaning | Define it as a B1 launcher input. The future `pnpm dev` launcher must parse it, construct a controlled child environment, and then launch Gradle; Spring Boot itself must not be assumed to read it. |

### Docker Compose

Compose uses a project `.env` for interpolation in the Compose model. The file
does **not** by itself inject every value into a container; container variables
must be declared through the Compose service's `environment` or `env_file`
configuration. Docker documents the project-directory lookup and distinguishes
interpolation from container environment configuration in its
[interpolation guide](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)
and [container-variable guide](https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/).

Compose interpolation precedence is shell environment, then an explicit
`--env-file`, then the default project `.env`. Consequently, an explicit file
does not defeat a colliding shell variable. `dev:up` must launch Compose with a
controlled environment that removes manifest-declared configuration keys, and
must pass both the exact `--env-file` and project directory rather than depend
on the caller's current working directory. Reserved `COMPOSE_*` control keys
must be rejected unless the manifest deliberately owns them; Docker documents
their ability to change project/file behavior in
[pre-defined Compose variables](https://docs.docker.com/compose/how-tos/environment-variables/envvars/).

The project `.env` supports comments, quotes, escapes, and interpolation.
Unquoted and double-quoted values are interpolated, while single-quoted values
are literal. These rules are Compose-specific and are documented in
[`.env` file syntax](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/#env-file-syntax).
The renderer should use the documented literal form and still round-trip the
rendered value through the installed compatible Compose version. It must not
print `docker compose config --environment`, because that command prints the
interpolation environment. For validation, `docker compose config --quiet`
validates without printing the canonical model, as documented by
[`docker compose config`](https://docs.docker.com/reference/cli/docker/compose/config/).

Compose has a second precedence system for a container's final environment
(`run -e`, interpolated `environment`/`env_file`, literal `environment`,
`env_file`, image `ENV`). The Compose file must make the intended source
unambiguous and tests must verify the resulting model against Docker's
[container environment precedence](https://docs.docker.com/compose/how-tos/environment-variables/envvars-precedence/).

### Next.js

Next.js loads `.env*` files from the application project root into
`process.env`. It also expands `$NAME` references and requires `\$` for a
literal dollar sign. These are explicit framework semantics, not generic Node
dotenv semantics; see the current Next.js
[environment-variable guide](https://nextjs.org/docs/app/guides/environment-variables#loading-environment-variables)
and its [variable-reference rules](https://nextjs.org/docs/app/guides/environment-variables#referencing-other-variables).

The load order stops at the first value found: existing `process.env`, then
`.env.$NODE_ENV.local`, `.env.local`, `.env.$NODE_ENV`, and `.env`. Therefore a
generated `.env.local` is not authoritative if the developer shell or a
`.env.development.local` already defines the same key. The application launcher
contract must provide a controlled environment for manifest-declared keys, and
`dev:up` must reject conflicting higher-precedence local files rather than
silently accept an override. The order, and the fact that `.env.local` is not
loaded for `NODE_ENV=test`, are documented in Next.js's
[environment-variable load order](https://nextjs.org/docs/app/guides/environment-variables#environment-variable-load-order).

Only `NEXT_PUBLIC_` values may be exposed to browser code, and Next.js inlines
those values into the browser bundle. The manifest must therefore prohibit any
secret from being projected to that prefix. See Next.js
[browser bundling behavior](https://nextjs.org/docs/app/guides/environment-variables#bundling-environment-variables-for-the-browser).

### Spring Boot and Gradle

Spring Boot natively consumes OS environment variables and Spring config data
such as `application.properties` and `application.yaml`; `.env` is not among
its automatic config-data names or locations. OS environment variables also
override config data in Spring Boot's property-source order. Both facts follow
from Spring Boot's
[external configuration reference](https://docs.spring.io/spring-boot/reference/features/external-config.html).

`bootRun` is a Gradle `JavaExec` task, and `JavaExec` defaults to the
environment of the Gradle process. Thus a future B1 application launcher can
parse `apps/resourceserver/.env`, construct the environment passed to Gradle,
and rely on `bootRun` inheriting it. The inheritance chain is documented by
Spring Boot's [Gradle running guide](https://docs.spring.io/spring-boot/gradle-plugin/running.html)
and Gradle's [`JavaExec` contract](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.JavaExec.html).

Because this repository requires Node 24+, the launcher can use Node's stable
dotenv parser (`util.parseEnv`) as the specified parser without adding the
OpenBao CLI or a shell-sourcing step. Node explicitly says there is no universal
dotenv specification, restricts variable names, and returns every value as a
string; see Node's
[dotenv specification](https://nodejs.org/api/environment_variables.html#env-files).
Using Node's `--env-file` alone is insufficient for the no-overrides policy,
because an already-set process variable takes precedence over the file; see
Node's [`--env-file` precedence](https://nodejs.org/api/cli.html#--env-fileconfig).

## Data and serialization constraints

The manifest should own typed values, while all three environment projections
contain strings. Apply these rules before rendering:

- Keys match `^[A-Z_][A-Z0-9_]*$`, are unique within a target, and are sorted
  deterministically. This is a stricter uppercase subset of Node's documented
  [variable-name grammar](https://nodejs.org/api/environment_variables.html#variable-names).
- Strings are valid UTF-8, single-line, and contain no NUL, carriage return,
  newline, or other control characters. Multiline values, binary data,
  certificates, and structured documents remain outside this design.
- Integers use canonical base-10 ASCII; booleans use exactly `true` or `false`;
  enums use their declared spelling; URLs are validated as URLs before their
  original or canonical declared representation is emitted. Consumers still
  receive strings and must bind/parse them to the declared type.
- Empty strings must be explicitly allowed by the manifest; absence and empty
  are not interchangeable.
- Control names such as `NODE_ENV`, `COMPOSE_*`, `SPRING_*`, `GRADLE_*`, and
  `JAVA_*` are reserved unless a manifest entry intentionally defines their
  lifecycle effect.
- Every file uses UTF-8, LF line endings, a final newline, `KEY=VALUE` entries,
  no duplicate keys, and only generator-owned header comments.

The logical bundle must be resolved once. Each target receives only its
declared capability projection, but repeated logical values (for example,
PostgreSQL credentials projected to Compose and the resource server) come from
the same resolved value and are compared before commit.

Do not reuse rendered bytes between targets. Compose treats single quotes as a
literal interpolation barrier; Next.js performs `$` expansion and prescribes
`\$` for a literal dollar; Node defines yet another parser. A target serializer
must encode values according to that target, then parse the staged file using
the actual supported consumer/parser in an isolated environment and compare
every parsed string with the logical input. If an exact round trip cannot be
proved, reject the generation. This permits normal single-line password
characters such as `$`, `#`, quotes, spaces, and backslashes without pretending
that one escaping algorithm is universal.

## Precedence and validation policy

The generated snapshot is authoritative for manifest-declared keys. That
requires behavior in addition to writing files:

1. Reject duplicate or unknown keys and validate every type and cross-component
   invariant before staging.
2. Detect higher-precedence sources that can shadow a generated key. Reject
   conflicting Next.js local files; spawn Compose and the future application
   launcher with manifest keys removed from the inherited environment.
3. Round-trip each staged projection without mutating the real process
   environment. Compare key sets as well as values.
4. Validate Compose with `config --quiet`; capture and redact diagnostics. Do
   not use commands that render the full model or interpolation environment in
   user-visible output.
5. Start or update infrastructure only after the transaction record says all
   three targets are committed to the same generation.

Git ignore rules reduce accidental commits but are not an access-control or
ownership mechanism. The generator should verify that every exact target and
its state directory are ignored, and fail before secret retrieval if not. The
repository currently ignores `.env*` and `*.env`, but the check should remain a
preflight invariant rather than an assumption.

## Filesystem safety

### Creation and replacement

For each fixed target:

1. Resolve the checkout root once. Verify every parent component is a real
   directory, not a symbolic link, and remains beneath that root. Use `lstat`
   semantics so the check observes a link rather than its referent; POSIX
   documents the distinction in
   [`lstat`](https://pubs.opengroup.org/onlinepubs/9799919799/functions/lstat.html).
2. Hold one per-checkout exclusive lifecycle lock across preflight, staging,
   commit, Compose startup, and state update.
3. Create a randomly named sibling temporary file with exclusive creation,
   no-follow behavior, and close-on-exec. POSIX specifies that
   `O_CREAT|O_EXCL` fails even for a dangling final symlink and that
   `O_NOFOLLOW` refuses a final symlink; see
   [`open`](https://pubs.opengroup.org/onlinepubs/9799919799/functions/open.html).
4. Set and verify owner-only `0600` permissions on the open file descriptor;
   do not rely on the developer's `umask`. On Linux, the creation mode also
   limits inherited default ACL permissions, but macOS and Linux can carry
   extended ACLs. Verify that no effective non-owner read/write grant remains,
   or fail rather than claim protection from other OS users. Linux documents
   mode/ACL correspondence in [`acl(5)`](https://man7.org/linux/man-pages/man5/acl.5.html),
   and Apple documents that ACLs supplement BSD mode bits in
   [Access Control Lists](https://developer.apple.com/library/archive/documentation/MacOSX/Conceptual/BPFileSystem/Articles/ACLs.html).
5. Write, close or synchronize the file, parse it back, and verify its exact
   expected digest before commit.
6. Atomically rename the sibling temporary file over a generator-owned regular
   target. POSIX requires the destination name to remain visible as either the
   old or new file during replacement; it also makes `rename` operate on a
   final symlink itself rather than its referent. See
   [`rename`](https://pubs.opengroup.org/onlinepubs/9799919799/functions/rename.html).

These measures prevent partial contents at an individual target and accidental
following of a target symlink. They do not defend against a malicious process
running concurrently as the same trusted developer; that actor is outside the
agreed threat boundary. Parent-directory descriptors and `*at` operations
should still be preferred where the implementation can use them, because POSIX
explains that they avoid pathname races.

If recovery after power loss is a required guarantee, synchronize each staged
file before rename and its containing directory after rename. POSIX notes that
directory operations are atomic but not necessarily durable, and describes the
write/sync/rename/directory-sync pattern in its
[filesystem synchronization rationale](https://pubs.opengroup.org/onlinepubs/9799919799/xrat/V4_xbd_chap01.html).

### One logical generation, not one atomic syscall

No portable POSIX primitive atomically replaces these three files in separate
directories. Use an owner-only transaction record containing a random
generation identifier, exact target paths, expected digests, and a state such
as `staged`, `committing`, or `committed`. Put the same generation identifier in
the comment header of every projection. Stage and validate all files first,
persist `committing`, rename targets one at a time, verify all three, then
persist `committed`.

A subsequent `dev:up` or `dev:down` must detect `staged`/`committing` or
generation disagreement before doing anything else and run a deterministic
recovery (complete from intact staged files or roll back from protected
backups). Consumers must not be started during the commit window. This gives
crash detection and eventual all-old/all-new recovery, but not simultaneous
visibility to an unrelated process that ignores the lock and reads during the
window.

### Proving ownership and deleting safely

A comment saying “generated” is not sufficient proof that a file may be
overwritten or deleted. Maintain non-secret, owner-only checkout state with a
random checkout identifier and, for each target, its generation identifier and
digest. A target is generator-owned only when all of the following hold:

- it is the exact fixed path recorded for this checkout;
- `lstat` reports a regular file owned by the current user, with the expected
  permissions and no unexpected hard links;
- its embedded checkout and generation identifiers match state; and
- its digest matches the last committed digest.

If the target exists on first use, state is missing, any check differs, or a
developer edited the file, preserve it and fail with a remediation message.
Never “repair” ownership by adding a marker to existing content.

`dev:down` applies the same checks immediately before unlinking. On mismatch it
preserves the path and reports it. POSIX specifies that unlinking a symbolic
link removes the link rather than its referent, but the safer policy is still
to refuse any non-regular target; see
[`unlink`](https://pubs.opengroup.org/onlinepubs/9699919799/functions/unlink.html).
Keep the checkout identifier after a successful cleanup, clear the committed
generation, and store no secret values in this state file.

## Decisions this research makes sharp

1. **Resource-server loading contract:** choose and specify the future `pnpm
   dev` launcher's exact parser and controlled-environment behavior. The file
   cannot be described as Spring Boot-native.
2. **Crash consistency:** decide whether the product requires only atomic
   per-file replacement plus mismatch detection, or journaled complete/rollback
   recovery and directory synchronization after power loss.
3. **Conflict handling:** decide whether any higher-precedence Next.js file or
   ambient collision fails startup, or only collisions in manifest-declared
   keys fail. Silent overriding is incompatible with the agreed configuration
   contract.
4. **Ownership state and edited files:** select the state location and recovery
   command, and confirm that modified generated files are preserved rather
   than overwritten/deleted.
5. **Refresh while applications run:** replacing files cannot update an
   already-running host process. A later workflow decision must define whether
   rerunning `dev:up` is refused while applications run, or is explicitly
   allowed to create a new snapshot used only on their next restart.
6. **Stateful PostgreSQL rotation:** changing image initialization variables
   and regenerating application values does not by itself prove that an
   existing database volume's user password changed. The infrastructure
   lifecycle specification must define rotation/reinitialization behavior.


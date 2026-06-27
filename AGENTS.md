# AGENTS.md
> **Note:** `CLAUDE.md` is a symlink to `AGENTS.md`. They are the same file.

B1 Budget is a personal budgeting application focused on intentional money allocation and
transparency.

Users actively assign their available funds to categories that represent intent (rent, food,
savings, etc.). Spending does not define intent — allocation does. The system prioritizes clarity,
explicit actions, and trustworthiness of financial state over automation or hidden corrections.

## Project Architecture

### Application Stack
The application is built using the following core technologies:

- Gradle 9.x
- Java 25
- Spring Boot 4.x
- Next.js 16.x

### High level package structure

The backend is a single Spring Boot application organized package-by-feature, plus one pure
domain module. Domain logic (invariants, money/allocation rules) lives framework-free in
`modules/model`; everything Spring-flavored lives in the app.

```
b1-budget/                          # Monorepo root for the B1 Budget platform
├── apps/                           # Deployable applications (runnable entrypoints)
│   ├── resourceserver/             # Backend service (Spring Boot) - Resource server
│   │   └── build.gradle.kts
│   └── web/                        # Web Application (Next.js)
│       └── package.json
├── packages/                       # Shared frontend packages
│   ├── typescript-config/          # Shared TypeScript configurations for the workspace.
│   │   └── package.json
│   └── ui/                         # A shared UI component library
│       └── package.json
├── build.gradle.kts                # Root Gradle build config
└── settings.gradle.kts             # Gradle settings
```

## Best practices

### Project
- Projects in `packages` should not have any dependencies on projects in `apps`.

### Java
- Always prefer using modern Java features. (i.e. records, sealed-classes where applicable, virtual
threads, etc.)
- **Records:** For classes primarily intended to transport data (e.g., DTOs, immutable data
structures), **Java Records should be used instead of traditional classes.**
- **Type Inference:** Use var for local variable declarations to improve readability, but only when
the type is explicitly clear from the right-hand side of the expression.
- **Immutability:** Favor immutable objects. Make classes and fields final where possible. Use
collections from `java List.of()`/`Map.of()` for fixed data. Use `Stream.toList()` to create
immutable lists.
- **Streams and Lambdas:** Use the Streams API and lambda expressions for collection processing.
Employ method references (e.g., `stream.map(Foo::toBar)`).
- **JSpecify:** Use JSpecify annotations for null safety. Document nullable parameters or class
members by annotating them with JSpecify `@Nullable` or if they are not nullable use `@NonNull`
annotation.
- **Null Handling**: Avoid returning `null` from methods. Use `Optional<T>` for possibly-absent
values and `Objects` utility methods like `equals()`.
- Use early returns for better readability
- **Spring beans:** constructor injection via `@RequiredArgsConstructor` with `final` fields; no
  field injection.
- Never put `@Data`, `@Value`, `@EqualsAndHashCode`, or `@ToString` on entities: generated `equals`/
`hashCode` break across persist/detach, and `toString` can trigger lazy loading.

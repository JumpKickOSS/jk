# Checkstyle recipe (ticket-1033)

Thin **Java lint** path: no first-party plugin — use JumpKick’s tool install + a project
Checkstyle config. Kotlin analysis (detekt) is deferred; use `jk format` for style.

## One-time install

```bash
# Pin a Checkstyle release (Maven Central coordinate).
jk tool install com.puppycrawl.tools:checkstyle:10.21.4
```

## Run against this sample

```bash
cd docs/features/examples/checkstyle-recipe
# From the sample root (sources under src/main/java):
jk tool run checkstyle -c checkstyle.xml src/main/java
```

If your tool list uses a longer name, list installs with `jk tool list` and pass that name to
`jk tool run`.

## CI sketch

```yaml
- run: jk tool install com.puppycrawl.tools:checkstyle:10.21.4
- run: jk tool run checkstyle -c checkstyle.xml src/main/java
  working-directory: docs/features/examples/checkstyle-recipe
```

Not required on the main product CI job; shops opt in.

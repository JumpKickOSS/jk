# Dynamic surface

R8 (minified jars) and GraalVM `native-image` both need to be told about the parts of a
program that static analysis cannot see — reflection, JNI, proxies, resources,
serialization, service loading. JumpKick models that **once** and emits both tools’
formats.

```text
1. derive     from by-name indexes          (no run)
2. compose    library keep / native-image metadata
3. train      jk train — observe a real run (opt-in)
4. declare    your own rules                (always last)
        → keeps.pro  and  reachability-metadata.json
```

**`jk build` never trains.** Training runs the application under an agent. It is slow and
its completeness is whatever the run exercised. Type `jk train` when you want it.

```bash
jk train
```

Default suite discovery: JUnit `@Tag("train")`. Override with `[train] command = "…"`.
Multiple `[[train.profile]]` tables union their results. Output:
`target/train/raw/<profile>/` and `target/train/merged/`.

Minified jars also consume `target/train/merged/dynamic-surface.json` when present.
User `[minified] keep` / keep-files still win last — [Packaging](packaging.md).

## What training cannot fix

In R8 `--classfile` full mode, a class keeps its **generic signature** only if the class
is explicitly kept. An application that resolves types by runtime generic matching
**cannot be shrunk** at any keep setting. Training cannot rescue that — use the fat jar,
or write class-level keep rules yourself. Every minified build audits for this and warns
with a count and examples when signatures were erased ([Packaging](packaging.md)).

The Graal tracing agent also does not record `Class.getTypeParameters()`; populating the
generic-reflection kind automatically is not complete. native-image itself preserves
generic signatures without being asked.

## Related

[Native](native.md) · [Packaging](packaging.md) · [Build](build.md)

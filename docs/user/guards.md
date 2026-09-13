# Guards

House rules as data. A project that carries **`jk-guards.toml`** at its root has its rules
enforced by every `jk build` and `jk test`; a project without one pays nothing. A rule names
what is banned or required, the sanctioned alternative, and why, and the engine reports every
violation with those three facts so the fix is mechanical and the exemption is a human decision.

```bash
jk guard                          # every lane now; exit 1 on any new violation
jk guard explain                  # the catalog: every rule, its last verdict, its baseline
jk guard explain <id>             # one rule's card: what, instead, why, where it came from
jk guard explain --schema forbid  # a kind's keys and one example
jk guard freeze <id> --reason "…" # accept a rule's current sites into the baseline
jk guard hooks install            # commit-msg and pre-commit hooks
jk guard --output sarif           # print target/jk-guards.sarif
```

## A rule

```toml
[guards.no-system-out]
kind       = "forbid"
signatures = ["@jdk-system-out"]
instead    = "the injected org.slf4j.Logger"
why        = "stdout is not a log sink in a service"
```

Every rule has a `kind`, a `why`, and for the kinds that ban something an `instead`. A rule
that cannot fire is red, not green: a `forbid` whose owner never exhibits the banned shape, a
`text` pattern with no `hit` snippet, an `allow` entry nothing matches — each reports its own
condition (`owner-missing`, `no-bite`, `stale-allow`, `scanner-failed`, `blind`) and the fix is
to the rule, never to the tree.

Exempt a site with an `allow` entry that carries a reason:

```toml
[[guards.no-system-out.allow]]
in     = "com.example.cli.Main"
reason = "the CLI entry point writes its usage to the terminal on purpose"
```

`in` is a class, a package glob or a file path, whichever the kind reads. A suppression comment in
code is not a mechanism: the engine does not read them, and the pre-commit hook refuses a staged
line shaped like one.

A file path is the file's real location from the workspace root: the module directory, then
whichever source root on disk holds the file — `src/main/kotlin` for a Kotlin class, `src/` in a
compact module, a suite's own root for a test class. `src/main/java` is the spelling only when no
root on disk holds the file (a class compiled from sources generated elsewhere). Two `allow`
entries against bytecode sites, one in a Kotlin root and one in a compact module:

```toml
[[guards.no-system-out.allow]]
in     = "web/src/main/kotlin/com/example/web/Boot.kt"
reason = "the bootstrap prints the listening port before the logger exists"

[[guards.no-system-out.allow]]
in     = "tools/src/com/example/tools/Main.java"
reason = "a command-line tool; stdout is its result"
```

A workspace-scoped guard test reports a site under the module that owns the class, and an `allow`
naming a module exempts that module's sites.

A module-lane rule that reads test classes (`tiers`, `annotate` with `on = "test-class"`) is clean
in a module that has none — a workspace has modules without tests, and a rule with nothing to
examine there has nothing to say. It carries no bite evidence from such a module; what proves it can
fire is a module where it does, or a `fixture`.

## Kinds

<!-- guard-kinds:start -->
| kind | substrate | lane | summary |
|---|---|---|---|
| forbid | bytecode | module | ban a type, member, package or call shape outside an owner |
| annotate | bytecode | module | an annotation must or must not be present |
| classes | bytecode | module | classes that … should … with closed predicates |
| layers | model | model | package or module layering and visibility |
| cycles | bytecode | module | slices free of cycles |
| split-package | bytecode | workspace | one module owns a package |
| api | bytecode | workspace | public API compatibility ratchet |
| depend | model | model | dependency policy over manifest and resolved lock |
| toolchain | model | model | build environment requirements |
| tiers | bytecode | module | test-tier routing |
| text | text | tree | a pattern over text |
| metric | text | tree | numeric caps and ratchets |
| vocabulary | hybrid | tree | owner constants banned as literals elsewhere |
| parity | text | tree | two extractions must agree |
| generated | text | tree | a block in a file is rendered from a source of truth |
| output | output | output | build artefact assertions |
| commit | text | hook | commit-message rules |
| test | hybrid | module | a @Guard method under src/guard, judged like a rule |
<!-- guard-kinds:end -->

The **substrate** is what a kind reads: `bytecode` rules read the module's compiled classes
(with a facts index the engine keeps beside them), `model` rules read the manifests and the
lock, `text` rules read the source tree as text, `output` rules read the packaged artefacts,
and `hybrid` rules read two of these. The **lane** is when it runs: `model` before compile,
`module` after each module compiles, `workspace` once every module's facts are on disk,
`output` after packaging, `hook` at commit time — all inside `jk build`. The `tree` lane
(text, metric, parity and generated rules) and the fixture proofs run on `jk guard` and on
`--guard`, never on a plain `jk build`, so a tree scan is a share-the-commit cost. `--guard`
is one flag on every verb that builds through the test stage — `jk build`, `jk test`,
`jk assemble`, `jk image`, `jk native`, `jk install`, `jk explain` — and it means the same
thing on each: the guard lanes, the integration suite and the root's guard scripts. A lane is keyed to what it reads, so an unchanged input skips it and
an edit re-runs only the lanes it can affect.

## Keys

Every key the loader accepts, by kind; `common` keys apply to every kind.
`jk guard explain --schema <kind>` prints the same table for one kind with an example.

<!-- guard-schemas:start -->
| kind | key | required | type | meaning |
|---|---|---|---|---|
| forbid | signatures | yes | string list | pkg.Class, pkg.Class#method(desc), #FIELD, #<init>(**), pkg.**, or a @bundled set |
| forbid | owner |  | string or list | the class or package where the call is legal; probed every run |
| forbid | args |  | string list | fire only when this literal immediately precedes the invoke |
| forbid | except-annotated |  | string or list | origin members carrying this annotation are exempt |
| annotate | require |  | string | annotation that must be present |
| annotate | forbid |  | string | annotation that must not be present |
| annotate | on | yes | string | the element the rule reads [package \| class \| method \| field \| parameter \| test-class] |
| annotate | matching |  | table | a classes-style predicate narrowing the elements |
| annotate | with-value |  | string | an attribute value the annotation must carry |
| classes | that | yes | table | predicates selecting classes; each negatable with a leading ! |
| classes | should | yes | table | predicates every selected class must satisfy |
| layers | layers | yes | table | name = package glob, module glob, or a list of module globs |
| layers | access | yes | table | name = [layers it may depend on] |
| layers | edges |  | string | which dependency edges are read [manifest \| classes \| both] |
| layers | exports |  | table | module = [packages visible across modules] |
| layers | exact |  | bool | a declared but unused module dependency is a violation |
| layers | closed |  | bool | an edge from a layered module to a module in no layer is a violation |
| cycles | matching |  | string | slice pattern, e.g. com.acme.features.(*).. |
| cycles | over |  | string | cycles over modules instead of packages [modules] |
| cycles | across-modules |  | bool | follow package edges across module boundaries |
| api | against | yes | string | a jar path, a g:a:v the lock pins, previous-release, or baseline |
| api | breaking |  | string | what a breaking change does [forbid \| baseline] |
| api | packages |  | string list | API package globs; default the module's exported packages |
| api | codes |  | string list | japicmp change codes to ignore |
| depend | ban |  | string list | coordinates (g:a, * in artifact) that may not appear |
| depend | coordinate |  | string | one coordinate whose scope placement is constrained |
| depend | only-in |  | string list | scopes the coordinate may appear in |
| depend | never-in |  | string list | scopes the coordinate may not appear in |
| depend | require |  | table | g:a = version floor |
| depend | licenses |  | table | forbid = [SPDX globs] |
| depend | scopes |  | string list | scopes the rule reads; default all |
| depend | convergence |  | bool | one version per artifact in the lock |
| depend | no-dynamic |  | bool | no floating selectors |
| depend | no-snapshot |  | bool | no -SNAPSHOT versions |
| toolchain | java |  | string | JDK release range, e.g. >=21 |
| toolchain | kotlin |  | string | Kotlin version range |
| toolchain | plugins |  | table | pinned = true: no floating plugin selector |
| toolchain | repositories |  | table | only = [repository names allowed] |
| tiers | uses |  | string list | type globs whose use routes a test class |
| tiers | tagged |  | string list | tags whose presence routes a test class |
| tiers | suite |  | string | the suite such classes must live in |
| tiers | tag |  | string | the tag such classes must carry |
| text | pattern |  | string | a Java regex |
| text | patterns |  | string list | several regexes, any of which is a hit |
| text | hit |  | string | a snippet the pattern must match through the chosen view — the bite proof |
| text | miss |  | string | a snippet the pattern must not match |
| text | files |  | string list | file globs; default the module source trees |
| text | blank |  | string | the view: comments blanked (default), comments+strings, none, or code (comments only) [comments \| comments+strings \| none \| code] |
| text | count |  | table | { exactly \| min \| max, per = file \| tree \| match } |
| text | owner |  | string or list | files where a match is legal |
| text | languages |  | string list | restrict to these source languages |
| metric | measure | yes | string | lines, fqcn, matches:<rule>, comment-lines, methods, params, public-members, cyclomatic, coverage.line, coverage.branch, jar-size, native-size |
| metric | cap |  | number or table | maximum, scalar or per-language table |
| metric | min |  | number or table | minimum, scalar or per-language table |
| metric | band |  | number | with baseline: how far a unit may move either side of its entry and still hold it (default 0) |
| metric | per |  | string | the unit measured [file \| class \| method \| module \| comment] |
| metric | files |  | string list | file globs for text measures |
| vocabulary | owner | yes | string | the class whose static final Strings are the vocabulary |
| vocabulary | shape |  | string | exact (default), hyphenated, or regex:<pattern> |
| vocabulary | homonyms |  | string list | literals of the shape that are legitimately something else |
| vocabulary | min-length |  | int | ignore shorter literals |
| vocabulary | inverse |  | bool | also report literals of the shape with no owner |
| parity | left | yes | table | one extractor: { workspace-modules = "jk.toml" } |
| parity | right | yes | table | the other extractor |
| parity | direction |  | string | which differences are violations [both \| left-in-right] |
| generated | source | yes | table | the extractor that is the truth |
| generated | template | yes | table | table(columns), list, arrow-chain, toml-array, code-block |
| generated | into | yes | string | the file carrying the generated block |
| generated | markers |  | string | the marker name; default the rule id |
| output | pom |  | table | no-unspecified = true, groups = [published groups] |
| output | jar |  | table | forbid-entries, require-entries |
| output | manifest |  | table | jar manifest attributes that must be present |
| commit | pattern |  | string | a regex the message must not match |
| commit | patterns |  | string list | several such regexes |
| commit | forbid-trailers |  | string list | trailer globs, e.g. Co-Authored-By: *bot* |
| commit | require |  | string list | regexes the message must match |
| common | kind | yes | string | the closed rule kind [forbid \| annotate \| classes \| layers \| cycles \| split-package \| api \| depend \| toolchain \| tiers \| text \| metric \| vocabulary \| parity \| generated \| output \| commit \| test] |
| common | why | yes | string | one sentence: the defect this rule prevents |
| common | scope |  | string or list | module globs the rule applies to; default every module |
| common | source-set |  | string | which compiled sources a bytecode rule reads [main \| test \| guard \| all] |
| common | allow |  | table list | { in, reason } pairs where the rule does not apply; a stale entry is red |
| common | baseline |  | bool | tolerate today's violations in jk-guards-baseline.toml; tighten-only |
| common | fixture |  | string | guard-fixtures/<id> with Bad and Ok sources that prove the rule bites |
| common | locked |  | bool | a pack's word: a consumer cannot allow against this rule |
<!-- guard-schemas:end -->

A baselined `metric` with `min` and no `cap` is a floor: a unit is red when it falls more than
`band` below its entry, and the entry rises when the unit climbs more than `band` above it. That is
the shape of a coverage ratchet — `measure = "coverage.line"`, `min = 100`, `band = 0.5` — read from
the `jacoco.xml` a `jk test --coverage` run leaves under each module's reports.

## Reading a failure

`target/jk-results.md` lists every broken rule with its sites:

```
### one-digest-surface — one digest surface  (1 site)
- `shared/io/src/main/java/cc/jumpkick/io/Foo.java:42`  `MessageDigest.getInstance("SHA-256")`
  → Hashing.newSha256()
```

The rule id is the `code`; `jk guard explain <id>` prints the card; the site changes as the
arrow says. A site that stays red across consecutive builds is reported as **thrash** with a
sentence to stop and ask, because the next sanctioned move is an exemption or a rule change,
and both are the user's call. Machine readers get the same rows as SARIF and JSONL — see
[Machine output](machine-output.md#guards) and [CI](ci.md#guards).

## Baseline and ratchets

A rule with `baseline = true` can tolerate the violations that exist when it lands and refuses
new ones. Landing is an explicit act: the first run reports today's sites red, with an
`Accept:` line naming `jk guard freeze <id> --reason "…"`, and that freeze records them. The
accepted sites live in **`jk-guards-baseline.toml`**, which only the engine writes: it tightens
on its own as sites disappear, and grows only through a freeze, which records the reason beside
the sites. A
`--retire` freeze drops the entries of a rule that no longer exists. Hand edits to the
baseline are refused by the pre-commit hook; a freeze and an engine tightening both leave the
marker the hook looks for, so either commits without ceremony. `metric` rules are ratchets by nature: a cap a
file already exceeds becomes that file's own ceiling, and it may only shrink.

## Guard tests

A rule the closed vocabulary cannot express is a **guard test**: a `@Guard` method in a
`@GuardSuite` class under `src/guard/java`, given the same facts, model and text the TOML
rules read, judged in the same lanes and reported the same way. `jk guard explain --schema
guard-test` prints the skeleton; [Test](test.md#the-guard-suite-is-not-a-test-suite) explains
how the source set is compiled and why it is not a test suite. `@Allow(in = …)` on a guard is
the `allow` entry above in annotation form — a module, a class glob, a fingerprint, or a file's
real path from the workspace root through the source root that holds it — and a
workspace-scoped guard's site is spelled under the member that owns the class. A guard that shells
out to a tool this machine does not have throws `Skipped` with the reason: the engine reports it
`skipped` — a notice, not red — and stores no verdict for the lane, so the guard is judged as soon
as the tool is installed.

## Fixtures

A ban proves it bites. `fixture = "<dir>"` on a rule (or `@Fixture` on a guard test) names a
workspace-relative directory — `guard-fixtures/<id>` by convention; jk's own sit beside the rule
engine under `server/guard/fixtures/` — holding `Bad*.java`, which must produce a violation, and
`Ok*.java`, which must not; the engine compiles them once per owning module and judges them as that
module's lane would. Stub types a fixture needs (a framework class by its real name) sit
beside them and are visible to the rule. A fixture that does not bite is red.

A guard test that reads several files at once — a workflow, a manifest, a pin — holds `Bad*` and
`Ok*` *directories* instead: each is a tree the guard runs over as if it were the checkout root,
one case per check the guard makes, and the files beside the case directories are the tree every
case starts from (a case's own files are laid over them). jk's `ci-cadence` fixture under
`server/guard/fixtures/ci-cadence/` is the shape: three workflows and a build script shared, a
manifest and a bootstrap pin per case. A guard that shells out over the tree runs inside the case
too, and one that skips on a machine without its tool skips its fixture the same way — a notice,
not a failure.

## Layers and packs

Rules come in three layers. A **pack** is a published artifact holding a `jk-guards.toml`
fragment and its fixtures:

```toml
[guards]
extends = ["cc.jumpkick.guards:spring:0.13.0"]
```

`jk lock` pins the exact coordinate as a `[[plugin]]` row — by digest, or by version alone for a
first-party pack at a pre-release jk version ([Lockfile](lockfile.md#what-else-the-lock-pins)) — and
unpacks the fragment under `target/jk-guards/guard-packs/`. The root file may `allow` against a pack rule or turn a ban into a
baseline, unless the pack marked the rule `locked = true`. A workspace member's own
`<module>/jk-guards.toml` may only tighten, and its rules default to that module's scope.
`jk guard explain` groups the catalog by layer. Every `jk new` framework template ships the
pack for its framework — [Templates](templates.md), [Frameworks](frameworks.md).

## Hooks

`jk guard hooks install` writes a `commit-msg` hook that runs the project's `commit` rules
over the message, and a `pre-commit` hook that refuses a hand-edited baseline or a staged
suppression comment — [Agents](agents.md#hooks-and-protected-files). `git commit --no-verify` skips them; the
lanes and CI remain the enforcement.

## Limitations

- **Annotations with `SOURCE` retention** are not in a class file, so an `annotate` rule that
  names one is `scanner-failed` with the retention spelled out; use a `text` rule.
- **Inlined constants**: `javac` copies a `static final` primitive or `String` into every use
  site, so a bytecode rule cannot see where a constant came from. The `vocabulary` kind reads
  the owner's constants and bans the *literals* everywhere else, which is the only shape that
  survives inlining; a `forbid` on a `static final int` field access does not fire.
- **Kotlin `inline` functions** are copied into their callers, so a `forbid` on the inline
  function itself sees no call sites; ban what the inline body calls.
- **Groovy and Scala** are `text`-scannable only with `blank = "none"`: the blanker lexes
  Java, Kotlin and JavaScript, so a code-view rule over those files reports `unsupported`
  rather than guessing at their comments.
- **Generated sources** count as sources unless a rule's `files` or `scope` excludes them;
  a text block in Java is a string, so `blank = "comments+strings"` hides its contents.

## Related

[Test](test.md) · [Agents](agents.md) · [CI](ci.md) · [Machine output](machine-output.md) ·
[Templates](templates.md) · [Build logic](build-logic.md)

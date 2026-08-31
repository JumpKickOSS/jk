# Lint with Checkstyle

JumpKick ships **no Checkstyle plugin**. Java lint is a *tool recipe*: install a pinned Maven
coordinate, get a launcher, point it at your sources. This sample is a real jk project — it
locks, builds, tests and formats like any other — because you lint a project, not a directory
of loose files.

```text
checkstyle-recipe/
  jk.toml                                # ordinary project; nothing lint-specific in it
  jk-lock.toml                           # committed; pins the project's 10 deps
  checkstyle.xml                         # the rule set — only rules jk format cannot apply
  src/main/java/demo/Sample.java         # the audited code
  src/main/java/demo/package-info.java   # @NullMarked, hence the jspecify provided-dep
  src/test/java/demo/SampleTest.java     # asserts the classifier the README prints
```

Nothing in `jk.toml` mentions Checkstyle — that is the point. The linter is a tool on your
PATH, not a build plugin:

```toml
group = "com.example"
name = "checkstyle-recipe"
version = "0.0.1"
java = 25

[application]
main = "demo.Sample"

# Compile-time only (@NullMarked): on javac's path, in no jar.
[provided-dependencies]
jspecify = "=1.0.1"

[test-dependencies]
junit-jupiter = "=6.1.3"
```

Absolute paths below are shown as `~/…`; jk and Checkstyle both print them expanded.

## Install Checkstyle once

Checkstyle's jar has **no `Main-Class`**, so the bare coordinate is rejected — with the fix in
the message:

```console
$ jk tool install com.puppycrawl.tools:checkstyle:14.1.0
[Resolve Coord] Failure
 | com.puppycrawl.tools:checkstyle:14.1.0 has no Main-Class in its manifest — pass --main <class>.
 +--
```

```console
$ jk tool install com.puppycrawl.tools:checkstyle:14.1.0 --main com.puppycrawl.tools.checkstyle.Main
jk: + Tool > Installed com.puppycrawl.tools:checkstyle:14.1.0 → ~/.local/bin/checkstyle
```

jk resolved Checkstyle's transitive deps (antlr, guava, picocli, saxon, …) and wrote a launcher
named for the artifact id — put jk's bin directory on your `PATH` if it is not there already;
the install output prints the exact `export` line. The tool's name is exactly **`checkstyle`**:

```console
$ jk tool list
+------------+----------------------------------------+--------+-----------------------------------+
| Tool       | Coordinates                            | Source | Launcher                          |
+------------+----------------------------------------+--------+-----------------------------------+
| checkstyle | com.puppycrawl.tools:checkstyle:14.1.0 |        | ~/.local/bin/checkstyle           |
+------------+----------------------------------------+--------+-----------------------------------+
```

`14.1.0` is an exact pin for the same reason `jk-lock.toml` exists: a lint run whose rules
change when upstream releases is not a gate. The linter is **not** in the lockfile — that file
pins what this project compiles and tests against. A tool is pinned by the command that
installs it.

## Audit the project

```console
$ checkstyle -c checkstyle.xml src/main/java
Starting audit...
Audit done.
```

`checkstyle.xml` turns on five rules, each of which `Sample.java` visibly satisfies:

| Rule | What it costs the code |
|------|------------------------|
| `MagicNumber` | `200`, `400`, `429`, `500` are named constants, each with a Javadoc saying what it means |
| `MissingJavadocMethod` | every public method has a Javadoc comment |
| `NeedBraces` | every `if` has a block, even one-liners |
| `FinalClass` | `Sample` is `final` |
| `HideUtilityClassConstructor` | `Sample` has a `private Sample() {}` |

## Why lint when `jk format` exists?

Different jobs. [`jk format`](../../format.md) rewrites **layout** and deletes unused imports;
it has no opinion about naming a constant, writing a Javadoc, or bracing a branch. So
`checkstyle.xml` deliberately turns on **no** rule the formatter already handles — `UnusedImports`
is absent for exactly that reason.

The proof is a violation everything else is happy with. Write `classify` the quick way:

```java
public static Outcome classify(int status) {
    if (status < 400) return Outcome.OK;
    if (status == 429 || status >= 500) return Outcome.RETRY;
    return Outcome.FAIL;
}
```

javac compiles it, the tests still pass, and the formatter calls it clean:

```console
$ jk build
jk: + Build > Build successful. Built target/checkstyle-recipe-0.0.1.jar - took 1.2s

$ jk test
+ Test Successful: Passed 8 tests - took 11ms

$ jk format --check
jk: + Format > Already formatted - took 538ms
```

The audit is the only thing that objects — six times over one five-line method
(`sed` only trims the absolute paths Checkstyle prints):

```console
$ checkstyle -c checkstyle.xml src/main/java 2>&1 | sed "s|$PWD/||"
Starting audit...
[ERROR] src/main/java/demo/Sample.java:38:5: Missing a Javadoc comment for 'classify'. [MissingJavadocMethod]
[ERROR] src/main/java/demo/Sample.java:39:9: 'if' construct must use '{}'s. [NeedBraces]
[ERROR] src/main/java/demo/Sample.java:39:22: '400' is a magic number. [MagicNumber]
[ERROR] src/main/java/demo/Sample.java:40:9: 'if' construct must use '{}'s. [NeedBraces]
[ERROR] src/main/java/demo/Sample.java:40:23: '429' is a magic number. [MagicNumber]
[ERROR] src/main/java/demo/Sample.java:40:40: '500' is a magic number. [MagicNumber]
Audit done.
Checkstyle ends with 6 errors.
```

The fix is what is checked in: constants with names, Javadoc on the public method, braces on
both branches. Restore `Sample.classify` and the audit goes quiet again — that shape is the
only reason the constants at the top of the file exist.

Checkstyle exits with the **violation count**, not `1`. With the quick version in place:

```console
$ checkstyle -c checkstyle.xml src/main/java >/dev/null 2>&1; echo "exit=$?"
exit=6
```

and as checked in:

```console
$ checkstyle -c checkstyle.xml src/main/java >/dev/null 2>&1; echo "exit=$?"
exit=0
```

Any CI step that runs the audit therefore fails on its own; it needs no wrapper.

## Only `src/main/java` is audited

`MagicNumber` is right for production code and wrong for a table-driven test, whose whole
content is literals:

```console
$ checkstyle -c checkstyle.xml src/test/java 2>&1 | sed "s|$PWD/||"
Starting audit...
[ERROR] src/test/java/demo/SampleTest.java:20:53: '429' is a magic number. [MagicNumber]
[ERROR] src/test/java/demo/SampleTest.java:21:52: '428' is a magic number. [MagicNumber]
[ERROR] src/test/java/demo/SampleTest.java:22:52: '430' is a magic number. [MagicNumber]
Audit done.
Checkstyle ends with 3 errors.
```

Audit test sources too if you want, but give them their own config.

## Without installing

`jk tool run` runs a coordinate directly. It needs `--main` again, and `--` to stop jk from
claiming Checkstyle's `-c`:

```console
$ jk tool run com.puppycrawl.tools:checkstyle:14.1.0 \
    --main com.puppycrawl.tools.checkstyle.Main -- -c checkstyle.xml src/main/java
Starting audit...
Audit done.
```

Installed tools are not reachable by name here — `jk tool run checkstyle` looks in the library
catalog, not in `jk tool list`. Use the launcher for the short form.

## Build it

```console
$ jk clean
jk: + Clean > Removed 13 files, 10.9 KiB total - took 2ms

$ jk build
jk: + Build > Build successful. Built target/checkstyle-recipe-0.0.1.jar - took 38ms

$ jk test
+ Test Successful: Passed 8 tests - took 10ms

$ jk run
jk: > Run > Executing `java -jar target/checkstyle-recipe-0.0.1.jar`
200 -> OK
400 -> FAIL
429 -> RETRY
500 -> RETRY
```

`jk-lock.toml` is committed and `jk build` never re-resolves. After editing a version:

```console
$ jk lock
jk: + Lock > Lock successful. Resolved 10 dependencies - took 49ms
```

## Related

[Tools](../../tools.md) · [Format](../../format.md) · [Projects](../../projects.md) ·
[Lockfile](../../lockfile.md)

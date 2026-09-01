# Minified (R8) jar

`[application] minified = true` runs **R8** in `--classfile` mode over the project's classes plus
its whole runtime closure and writes `target/minified-cli-0.0.1-min.jar`. It implies `assembly`,
so the fat jar is written beside it on purpose — the two are meant to be compared. See
[Packaging](../../packaging.md#minified-jar-r8).

Shrinking deletes every class static analysis cannot reach, so a sample that only proves the jar
runs proves nothing. This one finds its commands **by name**, the two ways the JVM ecosystem does,
and only one of the two is visible to jk:

| Verb | Named by | Keep rule |
|------|----------|-----------|
| `title` | `META-INF/services/demo.Command` | **derived** — a service file is a by-name index jk reads straight out of the jar |
| `greet` | `demo/commands.properties` | **yours** — `[minified] keep`; jk derives nothing here and warns about nothing |

Delete that one rule and the build stays green, the tests stay green, and the minified jar throws
on startup. That is the whole point of the sample.

```text
minified-cli/
  jk.toml                                             # minified = true, plus one load-bearing keep rule
  jk-lock.toml                                        # committed; jk build never re-resolves
  src/main/java/demo/Cli.java                         # dispatches a verb to a Command found by name
  src/main/java/demo/Command.java                     # the verb interface
  src/main/java/demo/TitleCommand.java                # reached through META-INF/services/demo.Command
  src/main/java/demo/GreetCommand.java                # reached through demo/commands.properties
  src/main/resources/META-INF/services/demo.Command   # one line: demo.TitleCommand
  src/main/resources/demo/commands.properties         # commands = demo.GreetCommand
  src/test/java/demo/CliTest.java                     # locks both discovery paths and the failure message
```

The whole manifest:

```toml
group = "com.example"
name = "minified-cli"
version = "0.0.1"
java = 25

[application]
main     = "demo.Cli"
minified = true # target/minified-cli-0.0.1-min.jar via R8; implies assembly, so -all.jar too

# demo.GreetCommand is named only by demo/commands.properties, a shape no by-name index covers,
# so nothing derives a rule for it and the post-shrink audit cannot see it either: without this
# line the build is green and the minified jar throws on startup. demo.TitleCommand needs no
# rule — META-INF/services/demo.Command is an index, and jk derives the keep from the jar.
[minified]
keep = ["-keep class demo.GreetCommand { *; }"]

# Title-casing. Most of it is unreachable, which is what the minified jar strips.
[dependencies]
commons-text = "=1.15.0"

# Compile-time only (@NullMarked): present on javac's path, absent from every jar.
[provided-dependencies]
jspecify = "=1.0.1"

[test-dependencies]
junit-jupiter = "=6.1.3"
```

The leading `=` is an **exact** pin — a bare `"1.15.0"` would mean `^1.15.0`, and `"latest"` would
move under you ([version strings](../../projects.md#version-strings)).

## Build and run

Output below is real, captured with `--no-ansi` so it pastes cleanly.

```console
$ jk build -F --no-ansi
jk: + Build > Build successful. Built target/minified-cli-0.0.1-all.jar - took 3.8s

$ jk build --no-ansi
jk: + Build > Build successful, project up to date - took 21ms

$ jk test --no-ansi
+ Test Successful: Passed 3 tests - took 11ms
```

`-F` forces the work; the second run is an action-cache hit, R8 included.

The minified jar is a plain executable jar — `Main-Class` is set and nothing else is needed:

```console
$ java -jar target/minified-cli-0.0.1-min.jar
verbs: greet, title

$ java -jar target/minified-cli-0.0.1-min.jar greet Ada
hello, Ada

$ java -jar target/minified-cli-0.0.1-min.jar title 'ADA lovelace'
Ada Lovelace

$ java -jar target/minified-cli-0.0.1-min.jar nope
unknown verb: nope (verbs: greet, title)
```

Both verbs answer, so both by-name lookups survived the shrink.

`jk run` launches the **fat** jar, not the minified one — there is no flag to point it at
`-min.jar`, so run that artifact directly as above:

```console
$ jk run --no-ansi
jk: > Run > Executing `java -jar target/minified-cli-0.0.1-all.jar`

verbs: greet, title
```

## What R8 removed

Artifacts are additive — all three are written:

| Artifact | Size | Entries | Contains |
|----------|------|---------|----------|
| `target/minified-cli-0.0.1.jar` | 6,125 B | 13 | project classes only |
| `target/minified-cli-0.0.1-all.jar` | 975,956 B | 641 | project classes **+** commons-text **+** commons-lang3 |
| `target/minified-cli-0.0.1-min.jar` | 25,772 B | 30 | only what R8 could prove reachable |

Two dependency classes survive out of the 589 the closure contributes:

```console
$ unzip -l target/minified-cli-0.0.1-all.jar | grep -c "org/apache.*class"
589

$ unzip -l target/minified-cli-0.0.1-min.jar | grep "org/apache.*class"
      469  02-01-1980 00:00   org/apache/commons/lang3/StringUtils.class
      798  02-01-1980 00:00   org/apache/commons/text/WordUtils.class
```

And both classes reached only by name are still there, alongside the two resources that name them:

```console
$ unzip -l target/minified-cli-0.0.1-min.jar | grep demo
       18  02-01-1980 00:00   META-INF/services/demo.Command
        0  02-01-1980 00:00   demo/
     4623  02-01-1980 00:00   demo/Cli.class
      289  02-01-1980 00:00   demo/Command.class
     1124  02-01-1980 00:00   demo/GreetCommand.class
     1522  02-01-1980 00:00   demo/TitleCommand.class
      112  02-01-1980 00:00   demo/commands.properties
```

The effective rule set is written beside the artifact. The first three lines are the packager's
own preamble, `demo.TitleCommand` is derived from the service file, and the last line is the rule
from `jk.toml` — which the file does not separate from the derived block above it:

```console
$ cat target/minified-cli-0.0.1-min-keep.pro
-keep class demo.Cli { public static void main(java.lang.String[]); }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-dontobfuscate

# Derived from by-name indexes, library native-image metadata.
# index
-keep class demo.TitleCommand { *; }
-keep class demo.GreetCommand { *; }
```

`obfuscate` is off by default, so nothing is renamed and JVM stack traces stay readable. Note that
removal alone is enough to break by-name lookup — you do not have to turn obfuscation on to get
burned.

## The failure mode: delete the keep rule

Comment out the `[minified] keep` line and rebuild. Nothing complains:

```console
$ jk build --no-ansi
jk: + Build > Build successful. Built target/minified-cli-0.0.1-all.jar - took 2.1s

$ jk test --no-ansi
+ Test Successful: Passed 3 tests - took 9ms
```

The tests pass because they run against `target/classes`, which is not shrunk. The derived rule
for `demo.TitleCommand` is still there; `demo.GreetCommand` no longer is:

```console
$ cat target/minified-cli-0.0.1-min-keep.pro
-keep class demo.Cli { public static void main(java.lang.String[]); }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-dontobfuscate

# Derived from by-name indexes, library native-image metadata.
# index
-keep class demo.TitleCommand { *; }
```

`demo/commands.properties` is a resource, so R8 copies it through untouched — the jar still names
a class it no longer carries:

```console
$ unzip -l target/minified-cli-0.0.1-min.jar | grep demo
       18  02-01-1980 00:00   META-INF/services/demo.Command
        0  02-01-1980 00:00   demo/
     4623  02-01-1980 00:00   demo/Cli.class
      289  02-01-1980 00:00   demo/Command.class
     1522  02-01-1980 00:00   demo/TitleCommand.class
      112  02-01-1980 00:00   demo/commands.properties

$ java -jar target/minified-cli-0.0.1-min.jar
Exception in thread "main" java.lang.IllegalStateException: demo/commands.properties names demo.GreetCommand, which this jar does not carry
	at demo.Cli.instantiate(SourceFile:81)
	at demo.Cli.main(SourceFile:45)
Caused by: java.lang.ClassNotFoundException: demo.GreetCommand
	at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:580)
	at java.base/java.lang.ClassLoader.loadClass(ClassLoader.java:502)
	at java.base/java.lang.Class.forName0(Native Method)
	at java.base/java.lang.Class.forName(Class.java:478)
	at java.base/java.lang.Class.forName(Class.java:468)
	at demo.Cli.instantiate(SourceFile:76)
	... 1 more
```

The fat jar built in the same run is unaffected, which is why it is built at all:

```console
$ java -jar target/minified-cli-0.0.1-all.jar
verbs: greet, title
```

`Cli` throws here because a registry naming a class the jar lost is a packaging fault worth
reporting. Not every loader is that loud — `java.util.ServiceLoader` raises
`ServiceConfigurationError`, but tolerant loaders such as Micronaut's `SoftServiceLoader` skip an
entry they cannot load, and then the same damage arrives as an application that starts with pieces
missing. Either way it arrives at runtime, which is why jk **audits** the indexed case after every
shrink and fails the build when R8 removed a class a service file or marker index still names. An
audit can only check names it can find, though, and `demo/commands.properties` is not a convention
it knows. Nothing here is a jk bug: it is the first entry under
[Limitations](../../packaging.md#minified-jar-r8) — *instantiation by name that appears in no
index* — and it is why `minified` is opt-in.

## What a keep rule cannot restore

In R8 `--classfile` full mode a class keeps its **generic signature** only if the class is
explicitly kept, and `-keepattributes Signature` in the rule file does not change that. Compare
the shrunk jar with the fat one:

```console
$ javap -cp target/minified-cli-0.0.1-all.jar demo.Command
Compiled from "Command.java"
public interface demo.Command {
  public abstract java.lang.String verb();
  public abstract java.lang.String run(java.util.List<java.lang.String>);
}

$ javap -cp target/minified-cli-0.0.1-min.jar demo.Command
Compiled from "SourceFile"
public interface demo.Command {
  public abstract java.lang.String verb();
  public abstract java.lang.String run(java.util.List);
}
```

`demo.Command` is reachable from bytecode, so R8 keeps the class — but no rule names it, and its
signature is gone. `demo.GreetCommand`, kept explicitly, keeps its own:

```console
$ javap -cp target/minified-cli-0.0.1-min.jar demo.GreetCommand
Compiled from "SourceFile"
public final class demo.GreetCommand implements demo.Command {
  public demo.GreetCommand();
  public java.lang.String verb();
  public java.lang.String run(java.util.List<java.lang.String>);
}
```

This CLI does not read generic signatures, so nothing breaks. A framework that resolves beans or
handlers by runtime generic matching does, and it fails with `No bean of type [Foo<Bar>]` and no
hint that packaging caused it. **The build above printed no warning about this** — today it is
documented but not diagnosed. See [Dynamic surface](../../dynamic-surface.md#what-training-cannot-fix).

## Regenerating the lock

`jk-lock.toml` is committed and `jk build` never re-resolves. After editing a version:

```console
$ jk lock --no-ansi
jk: + Lock > Lock successful. Resolved 12 dependencies - took 40ms
```

## Related

[Packaging](../../packaging.md) · [Dynamic surface](../../dynamic-surface.md) ·
[Native](../../native.md) · [assembly-app](../assembly-app/) is the fat jar this builds on.

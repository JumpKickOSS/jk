# Clean-build oracle

Holds work avoidance to its one claim: an incremental build produces exactly what a build from
nothing produces, and a build after it runs nothing.

```bash
java bench/oracle/Oracle.java [work-dir] [class,class,...]
```

It uses the `jk` on `PATH`, writes a fixture workspace into `work-dir` (Java, Kotlin and Groovy
modules, a sibling chain, a same-package split, resources, a suite that reads a file it does not
declare, a suite that sleeps on request), and for each edit class:

1. applies the edit and builds incrementally against a cache kept for the whole run;
2. builds again: no compile, package or test step may run (a build that failed is exempt: its
   failed suite must run again);
3. copies the sources to a fresh directory and builds them against a fresh, empty cache;
4. compares exit codes, every jar and every file of every classes tree, and the code graph the
   build refreshed against a from-scratch `jk graph -r`, byte for byte (`target/graph/graph.bin`).

One line per class; the exit status is non-zero when any failed. The classes cover source edits
(body, signature, public and package-private constants, add, delete, rename, case-only rename,
package move), resources, `touch -r` and older copies, CRLF, a passing test that starts reading a
file nothing declares, a test input nothing declares,
`--jvm-arg`, `[test] env`, javac args, `java =`, Kotlin `const`, Groovy, a symlinked source (POSIX
only), `jk clean` then a build, `jk build -r` (the build after either must run nothing), edits
landing during a plain and a `--force` build, a branch switch and a stash.

Run it on every platform jk supports, one heavy run per machine: macOS, Windows, and WSL on its own
disk and under `/mnt/c`.

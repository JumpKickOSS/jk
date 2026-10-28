# Code-graph retrieval bench

How many tokens it costs to answer a navigation question about a JVM codebase, and how much of the
right answer comes back, for each way an agent can ask: grep and read the files, jk's code graph,
graphify's, and RepoQL's. Answers are graded against the compiler's facts, not against any of the
tools.

```bash
bench/graph-retrieval/bench setup                  # clone, import, build, extract truth, write questions
bench/graph-retrieval/bench run                    # every arm on every question → results/<date>/
bench/graph-retrieval/bench run --repo commons-io --arm jk-tool
bench/graph-retrieval/bench index --runs 3         # index wall time per tool, full and after a one-file edit
bench/graph-retrieval/bench edges                  # each graph's call edges against the compiler's
bench/graph-retrieval/bench render                 # TABLE.md from results/<date>/rows.jsonl
```

Trees live under `$GRAPH_RETRIEVAL_HOME` (default `$JK_BENCH_HOME/graph-retrieval`), never in
this tree. Run it on an otherwise idle host; `index` measures wall time.

## The corpus

[`repos.toml`](repos.toml): public repositories pinned by commit or release tag, from
spring-petclinic up to commons-collections, plus jk's own workspace as the large point. Each is
imported with `jk import` from its own build file and built once (`jk build -S`), so the classes
exist for the truth and for jk's compiled facts. graphify and RepoQL index the same tree.

## Ground truth

[`Truth.java`](Truth.java) reads every class file with the JDK's `java.lang.classfile` API (no jk
code): supertypes, methods with their line spans, and every invoke instruction with its line. A
call binds to the workspace method its owner or nearest supertype declares; a lambda, a Kotlin
`$lambda`/`$default` or a local class counts as its enclosing method. Questions are drawn from
those facts with a seeded RNG and checked in under [`questions/`](questions/) with the commit they
were drawn at:

| Kind | Question | The answer |
|---|---|---|
| `callers` | Who calls `Type.method`? | Every method with a call that binds to it |
| `implementers` | What implements / extends `Type`? | Every workspace type with it among its supertypes |
| `overrides` | What overrides `Type.method`? | Every subtype method with the same name and descriptor |
| `definition` | Where is `Type.method` defined? | That method |

A method an agent would not ask about (constructors, accessors, `equals`/`hashCode`/`toString`,
generated members) is never a subject.

## The arms

| Arm | What it runs |
|---|---|
| `grep-read` | `grep -rnE` for the name (a call `name(`, a declaration, an `implements`/`extends`/`:` line), then reads each matching file in full with line numbers, most hits first, up to 15 files: what an agent with only a search and a file reader does |
| `jk-query` | `jk graph query "<question>"` at the default budget |
| `jk-tool` | The jk command the skill points at for that kind: `neighbors -R calls`, `find -I`, `find <name> -t method -I`, `read -w headline` |
| `graphify-query` | `graphify query "<question>"` |
| `graphify-tool` | `graphify explain <subject>` |
| `rql` | RepoQL's `explore` with the question |

An arm whose tool is not installed records `not installed` and is left out of the table.

## Grading

Tokens are characters / 4 for every arm (graphify's own benchmark uses the same estimate). An
answer *names* a truth entity when it cites the entity's file at a line inside the entity's span
(a method: its first body line minus six to its last; a type: its declaration to its last line);
`grep-read` also names every entity in a file it read, and any arm names one by citing it by name: a method as `Type.member`, a type by its qualified name (RepoQL cites `#symbol=` without lines). **Recall** is the share of the answer named.
**Precision** is the share of cited code locations that belong to the answer; a graph's context
nodes lower it on purpose, so it is reported, not ranked. **Tokens per found** is all tokens over
all named entities: the cost of one correct fact.

`edges` compares each graph's `calls` edges with the compiler's, both mapped to (file, method span):
precision and recall per repo.

`index` times a full index and an incremental one after a one-line edit to one main source file:
`jk graph build -r` and `jk graph build`, `graphify update .` from an empty `graphify-out/` and
again after the edit, and RepoQL's index command.

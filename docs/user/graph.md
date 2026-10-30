# Code graph

`jk graph` maps a workspace into a graph of its code, configuration and documentation, and answers
questions from it within a token budget. It is built in: nothing to install, an index in seconds,
and call edges from the compiled classes. Every answer names a symbol as `Owner.member()` and places
it at `file:line`.

```bash
jk graph                                   # build or refresh target/graph/
jk graph query "how is the order total computed"
jk graph read OrderService.total           # that method's source, not the whole file
jk graph read OrderService -w structure    # the type's declarations without bodies
jk graph find -t interface -I Repository   # declarations by kind, supertype, annotation, name
jk graph callers OrderService.total        # who calls it, every overload, complete
jk graph callers OrderService.total -t     # everything that reaches it
jk graph neighbors OrderService            # its direct connections
jk graph path OrderService PriceCalculator -U
jk graph rules src/main/java/com/acme/shop/OrderService.java
```

A name is a node id, a label, or `Type.member` (qualify the type, `shop.OrderService.total`, when
two packages declare it). `callers` and `neighbors` answer for every overload a `Type.member` names;
`path`, which needs one node, lists the overloads' ids instead. Answers name a member with its
owner, `Release.Plan.label()` for a nested type's, so a caller is never read as its file's top-level
class.

## What is in it

| Source | Nodes | Edges |
|---|---|---|
| Java, Kotlin, Groovy (compiled classes and source) | types, nested types, methods, record components (as their accessors), named constants (`static final UPPER_CASE`, Kotlin `const val`), Kotlin top-level functions, `NOTE:`/`WHY:` comments | `contains`, `method`, `inherits`, `implements`, `imports`, `calls`, `references` (field, parameter, return types, annotations) |
| JavaScript, TypeScript, JSX/TSX, Vue | classes, functions, methods | `imports_from` (relative), `imports` (npm packages), `uses` (Angular templates and styles, JSX components) |
| Markdown, AsciiDoc | pages, headings | `contains`, links to other files, `references` to the code a code span names |
| Spring `application*`/`bootstrap*` YAML and properties | one node per key | code reading `${key}`, `@Value` or `@ConfigurationProperties` → the key |
| `jk.toml`, `pom.xml`, Gradle, version catalogs, `package.json` | dependencies | `depends_on`, workspace and Maven modules |
| XML, `META-INF/services`, `*.imports`, JSON | | `references` to every class they name |
| HTML, CSS/SCSS/Less, SQL, DOT/Mermaid/PlantUML, CSV | pages, tables, columns | loaded files, imported stylesheets, drawn types |

Calls, inheritance and type references come from the compiled classes, resolved by the compiler, so
they are exact. A tree that does not compile yet still gets its declarations from the source; build
to bring the call edges up to date. Links from prose and config keys matched by name are inferred,
and an answer marks them `(inferred)`.

Every JVM declaration also carries its kind (class, interface, enum, record, annotation, object,
method, constructor, function, property), visibility, last line, annotations, supertypes and the
first sentence of its doc comment. `read`, `find` and the search use them.

## How it stays current

Every `jk graph` command refreshes the graph before answering, and a build that succeeds refreshes
an existing `target/graph/` before it reports done, so the graph an agent reads is the tree as it
stands. Files are compared by their stat identity (size, modification and change time, file id),
then by content hash; only changed files are read again, and a file that will not read contributes
nothing, its earlier facts included. A refresh that fails leaves the previous graph and its state
as they were.

A class's compiled facts (calls, overrides, exact lines) are read only while its source is the one
the module's last successful build compiled. A source edited since contributes what its text
declares, and every other file of the module keeps its compiled facts, so the callers of the method
being changed are still there mid-edit; the answer names the edited files. A module whose class
files the build did not write, or that is being built while the graph refreshes, contributes what
its sources declare, and the answer says which modules those are. `jk build` brings the compiled
facts back. An unchanged tree answers from
the graph already in memory, or from a stored snapshot. `-r` extracts every file again. `-n`
answers from the last graph written without refreshing, and is an error when none has been.

The graph is built in its own worker process, so its memory is never the engine's. The worker
stays up between commands with the graph in memory and exits after 30 idle minutes
(`JK_WORKER_LINGER_MIN`), or sooner when another fork needs its memory.

## Reading an answer

```
From OrderService: 15 symbols within 3 hops, breadth-first.

OrderService  src/main/java/com/acme/shop/OrderService.java:6
OrderService.total()  src/main/java/com/acme/shop/OrderService.java:10
BaseService  src/main/java/com/acme/shop/BaseService.java:3
OrderService -inherits-> BaseService  src/main/java/com/acme/shop/OrderService.java:6
OrderService.total() -calls-> PriceCalculator.sum()  src/main/java/com/acme/shop/OrderService.java:12
Shop -references-> OrderService (inferred)  README.md:3
```

The symbols come first, each at the line that declares it, then the connections, each at the line
that makes it. A connection found at several lines of its file (two calls from one method, or from
overloads, which share a node) names them all: `…/StringUtils.java:7206,7235`. A connection says
`(inferred)` when it was matched by name rather than read from the code, and names its kind
(`field`, `parameter_type`, …) when the relation alone does not.

The question's words pick the starting nodes: an exact name beats a prefix, which beats a whole
camel-case word inside a name (`errors` finds `ApiErrors`), which beats a substring; plurals, past
tenses and a long shared stem count as the word (`available` meets `availability`); rare words
weigh more; a word of a doc comment counts a little. Declarations outrank config keys and
dependencies on the same word, and tests start an answer only when the question asks about tests
(the walk still reaches them). A few words map to what code calls them (`endpoint` and `route` to
controllers and mappings). The graph is walked `-d` hops (default 3) and the result is cut to `-b`
tokens (default 2000): the starting nodes first, then the nodes the question's words match, best
match first, then the rest nearest first, then the connections, those touching the nearest symbols
first — so a cut drops what the question did not ask about, and the budget holds even when every
symbol fits. A cut answer says how large a budget would show everything. A question that matches nothing lists
the closest names.

| Option | Meaning |
|---|---|
| `-d`, `--depth N` | Hops from the matched nodes |
| `-b`, `--budget N` | Answer size in tokens |
| `-s`, `--dfs` | Walk depth-first |
| `-c`, `--context call,field,…` | Follow only edges with these contexts; a question that says "calls" or "imports" sets it, and one that says "returns", "member" or "template" sets it unless that leaves fewer than ten symbols (the first line then says every edge was followed) |
| `-n`, `--no-refresh` | Answer from the last graph without looking at the tree |

## Scope

`query`, `neighbors`, `callers`, `read` and `find` take the same three narrowing options. A symbol
outside the scope is neither a starting point nor walked through.

| Option | Meaning |
|---|---|
| `-g`, `--scope <glob;glob;!glob>` | Paths from the workspace root. `*` stays in one directory, `**` crosses them, `{a,b}` is either; `!` excludes. A path without wildcards is everything under it |
| `-m`, `--modules <sel>` | Modules, as `jk build -m` selects them; a nested module is not part of its parent |
| `-S`, `--source-set main\|test` | Main sources, or any test suite |

```bash
jk graph query "retry policy" -m server/engine -S main
jk graph find '*Test' -g 'server/**;!**/integration/**'
```

## Reading a symbol

`jk graph read <address>` answers with the source of what the address names, in the richest view
the budget fits: `content` (the numbered source of each span), then `structure` (each declaration
without its body, annotations above it), then `headline` (one line per symbol with its kind, span and
doc summary). `-w` picks one.

| Address | Reads |
|---|---|
| `OrderService`, `com.acme.shop.OrderService` | The type |
| `OrderService.total` | The member; every overload of it |
| `OrderService.*` | The type's direct members |
| `OrderService.**` | Everything declared inside it |
| `src/main/java/com/acme/shop/OrderService.java` | The file |
| `src/**/*Service.java;!**/legacy/**` | Every matching file |
| `…#line=40,60` | Only those lines |

A name several files declare is refused with the candidates; qualify it. A type no workspace file
declares is read from the jars `jk-lock.toml` pins, at the version the lock pins:
`jk graph read ObjectMapper.readValue` shows that method's public overloads with their generic
signatures. Each jar's type list is read once and kept under the store.

## Finding declarations

`jk graph find [name-glob]` lists the declarations that pass every filter given, one line each with
kind, visibility, name and span. Each filter takes a comma list.

| Option | Matches |
|---|---|
| `-t`, `--kind` | `class`, `interface`, `enum`, `record`, `annotation`, `object`, `method`, `constructor`, `function`, `property`; `type` for every type, `member` for every method and function |
| `-p`, `--visibility` | `public`, `protected`, `internal`, `package`, `private` |
| `-E`, `--extends` | A superclass anywhere up the chain |
| `-I`, `--implements` | A supertype anywhere above, interfaces included; library supertypes match by name |
| `-A`, `--annotated` | An annotation on the declaration (`Transactional` or `@Transactional`) |
| `-l`, `--libraries` | Search the locked jars' types instead, by name glob and kind |

`-E` and `-I` filter types, or, when `-t` asks only for members, the type that declares each member:
`find total -t method -I OrderService` lists every override of `OrderService.total`. A nested type
can be named by its outer one, `-I Preflight.Main`.

```bash
jk graph find -A RestController
jk graph find total -t method -I OrderService   # every override
jk graph find -t member -p public -m server/engine 'get*'
jk graph find -l '*Mapper' -t class
```

## Before editing a file

`jk graph rules <file>` lists the guard rules from `jk-guards.toml` whose reach covers the file (its
module, its source set, the rule's `files` and `languages`, no `allow` entry for it), each with the
sanctioned alternative and why, and the `NOTE:`/`WHY:` comments the file carries.

## Neighbors and paths

`jk graph neighbors <name>` lists a symbol's direct connections, one per line:
`-calls-> PriceCalculator.sum()  src/main/java/com/acme/shop/OrderService.java:12` for an edge
leaving it, `<-inherits- OrderService  …` for one arriving, with the lines that make it quoted when
the file reads. `-R calls` keeps only relations containing the word.

`jk graph path <from> <to>` names the shortest chain of connections between two symbols, one symbol
per line with its `file:line`. It follows edge direction; `-U` ignores it, and `-L` caps the hops
(default 8).

## Outputs

`target/graph/` at the workspace root holds jk's own state, read by `jk graph` and the MCP tools:
`graph.bin` (the graph), and `files.tsv` and `fragments.jsonl` (per-file state that makes the next
refresh incremental). None of it is an exchange format.

## Settings

The workspace root's `jk.toml`:

```toml
[graph.aliases]
Tariff = ["price", "rate card"]          # a question saying "price" also starts at Tariff
```

An alias maps a name in the code to the words people use for it. A question holding one of the words
also starts from the name. jk's graph has no LLM pass: documents are read for their structure, links
and code mentions only.

## Callers

`jk graph callers Type.method` (MCP `callers`) lists every method that calls it, each as
`Owner.method()` with its call sites' `file:line` and the first lines quoted, main sources before
tests, with a count. The calls come from the compiled classes, so the list is complete: a call
inside a lambda counts for the method holding it, and a call made through a supertype's method is
listed under that method. The calls are followed overload by overload: a call to `tool(String)`
does not reach what only `tool(Path)` calls. A module whose classes are older than its sources is named in a note,
and its calls are missing until `jk build`.

`-t` (MCP `transitive=true`) lists everything that reaches the method instead: its callers, theirs,
and so on up to `-L` hops (default 8), grouped by hop, each with the method it calls on the way.
That is the change-impact question grep answers one hop and one file at a time.

## Against grep and other graphs

The [retrieval bench](../../bench/graph-retrieval/README.md) asks who calls a method, what
implements or overrides it and where it is defined, on seven JVM repositories, and grades every
answer against the call and type facts read from the compiled classes. Each cell is the median
answer size in tokens and the share of the compiler's answer it named.

<!-- graph-table:begin -->
| Repo | Lines | jk tokens · found | grep tokens · found | graphify tokens · found | RepoQL tokens · found |
|---|---:|---:|---:|---:|---:|
| spring-petclinic-kotlin | 1,889 | 73 · 97% | 855 · 70% | 20 · 41% | 63 · 34% |
| spring-petclinic | 3,635 | 76 · 97% | 2,212 · 98% | 202 · 94% | 136 · 12% |
| commons-cli | 17,862 | 139 · 84% | 24,509 · 96% | 145 · 68% | 193 · 17% |
| commons-io | 105,209 | 90 · 85% | 26,037 · 98% | 142 · 60% | — |
| commons-collections | 129,244 | 128 · 96% | 29,064 · 95% | 184 · 71% | — |
| commons-lang | 188,332 | 98 · 86% | 22,696 · 96% | 150 · 61% | — |
| jk | 679,533 | 80 · 99% | 32,527 · 97% | 88 · 48% | — |
<!-- graph-table:end -->

<!-- graph-claims:begin -->
On jk (679,533 lines), asked who calls a method, what implements or overrides it and where it is defined, jk's graph tools answered in a median 80 tokens and named 99% of the compiler's answer; grep and reading the files it matched took 32,527 tokens for 97%. Per correct answer that is 35 tokens against 17,482. On the same questions: graphify's 88 tokens for 48%. jk's call edges match the compiled classes' at 96% precision and 98% recall. ([The bench](../../bench/graph-retrieval/README.md), results of 2026-10-26.)
<!-- graph-claims:end -->

## MCP

jk's MCP server lists one graph tool by default, `callers`: every card an agent's host shows is paid
on every turn, and in measured agent runs only caller questions repaid it, answered in about a third
fewer tokens than grep and reads in a large workspace. `query`, `read` and `find` answer as their
`jk graph` subcommands do and are on the extended list (`tools/list` with `extended: true`, or
`[mcp] tools = "all"`); every tool is callable by name either way. `neighbors`, `path` and `rules`
are `jk graph` in a shell. See [MCP](mcp.md).

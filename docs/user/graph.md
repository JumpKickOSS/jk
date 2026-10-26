# Code graph

`jk graph` maps a workspace into a knowledge graph of its code, configuration and documentation, and
answers questions from it within a token budget. A coding agent asks the graph where something is
and how it connects instead of grepping and reading whole files; the answer names `file:line` for
everything it mentions.

```bash
jk graph                                   # build or refresh target/graph/
jk graph query "how is the order total computed"
jk graph read OrderService.total           # that method's source, not the whole file
jk graph read OrderService -w structure    # the type's declarations without bodies
jk graph find -t interface -I Repository   # declarations by kind, supertype, annotation, name
jk graph explain OrderService              # one node and its connections
jk graph neighbors OrderService.total -R calls   # who calls it, every overload
jk graph path OrderService PriceCalculator -U
jk graph rules src/main/java/com/acme/shop/OrderService.java
jk graph god-nodes                         # the most-connected entities
jk graph report                            # GRAPH_REPORT.md: god nodes, surprises, communities
```

A name is a node id, a label, or `Type.member` (qualify the type, `shop.OrderService.total`, when
two packages declare it). `neighbors` answers for every overload a `Type.member` names; the commands
that need one node list the overloads' ids instead.

## What is in it

| Source | Nodes | Edges |
|---|---|---|
| Java, Kotlin, Groovy (compiled classes and source) | types, nested types, methods, named constants (`static final UPPER_CASE`, Kotlin `const val`), Kotlin top-level functions, `NOTE:`/`WHY:` comments | `contains`, `method`, `inherits`, `implements`, `imports`, `calls`, `references` (field, parameter, return types, annotations) |
| JavaScript, TypeScript, JSX/TSX, Vue | classes, functions, methods | `imports_from` (relative), `imports` (npm packages), `uses` (Angular templates and styles, JSX components) |
| Markdown, AsciiDoc | pages, headings | `contains`, links to other files, `references` to the code a code span names |
| Spring `application*`/`bootstrap*` YAML and properties | one node per key | code reading `${key}`, `@Value` or `@ConfigurationProperties` → the key |
| `jk.toml`, `pom.xml`, Gradle, version catalogs, `package.json` | dependencies | `depends_on`, workspace and Maven modules |
| XML, `META-INF/services`, `*.imports`, JSON | | `references` to every class they name |
| HTML, CSS/SCSS/Less, SQL, DOT/Mermaid/PlantUML, CSV | pages, tables, columns | loaded files, imported stylesheets, drawn types |

Calls, inheritance and type references come from the compiled classes, resolved by the compiler, so
they are exact (`EXTRACTED`). A tree that does not compile yet still gets its declarations from the
source; build to bring the call edges up to date. Links from prose and config keys matched by name
are `INFERRED`.

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
Traversal: BFS depth=3 | Start: ['OrderService'] | 19 nodes found

NODE OrderService [src=src/main/java/com/acme/shop/OrderService.java loc=L6 community=OrderService]
EDGE OrderService --inherits [EXTRACTED]--> BaseService at=src/main/java/com/acme/shop/OrderService.java:L6
```

The question's words pick the starting nodes: an exact name beats a prefix, which beats a whole
camel-case word inside a name (`errors` finds `ApiErrors`), which beats a substring; plurals, past
tenses and a long shared stem count as the word (`available` meets `availability`); rare words
weigh more; a word of a doc comment counts a little. Declarations outrank config keys and
dependencies on the same word, and tests start an answer only when the question asks about tests
(the walk still reaches them). A few words map to what code calls them (`endpoint` and `route` to
controllers and mappings). The graph is walked `-d` hops (default 3) and the result is cut to `-b`
tokens (default 2000): the starting nodes first, then the nodes the question's words match, best
match first, then the rest nearest first, then the edges, those touching the nearest nodes first —
so a cut drops what the question did not ask about, and the budget holds even when every node
fits. A cut answer says how large a budget would show everything. A question that matches nothing lists
the closest names.

| Option | Meaning |
|---|---|
| `-d`, `--depth N` | Hops from the matched nodes |
| `-b`, `--budget N` | Answer size in tokens |
| `-s`, `--dfs` | Walk depth-first |
| `-c`, `--context call,field,…` | Follow only edges with these contexts; a question that says "calls" or "imports" sets it, and one that says "returns", "member" or "template" sets it unless that leaves fewer than ten nodes (the header then says the context was dropped) |
| `-n`, `--no-refresh` | Answer from the last graph without looking at the tree |

## Scope

`query`, `explain`, `neighbors`, `god-nodes`, `read` and `find` take the same three narrowing
options. A node outside the scope is neither a starting point nor walked through.

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

```bash
jk graph find -A RestController
jk graph find -t member -p public -m server/engine 'get*'
jk graph find -l '*Mapper' -t class
```

## Before editing a file

`jk graph rules <file>` lists the guard rules from `jk-guards.toml` whose reach covers the file (its
module, its source set, the rule's `files` and `languages`, no `allow` entry for it), each with the
sanctioned alternative and why, and the `NOTE:`/`WHY:` comments the file carries.

## Outputs

`target/graph/` at the workspace root:

| File | Contents |
|---|---|
| `graph.json` | The graph in graphify's node-link format (`graph.schema_version` 1) |
| `GRAPH_REPORT.md` | God nodes, surprising connections, import cycles, stale doc references and communities: one read to orient |
| `graph.bin` | A compact copy jk reloads quickly |
| `files.tsv`, `fragments.jsonl` | Per-file state that makes the next refresh incremental |

The report's stale doc references are relative links to files that do not exist, and qualified class
names in one of the workspace's packages that no source declares.

## Settings

The workspace root's `jk.toml`:

```toml
[graph]
graphify = true                          # keep graphify-out/ current for graphify's tools

[graph.aliases]
Tariff = ["price", "rate card"]          # a question saying "price" also starts at Tariff
```

An alias maps a name in the code to the words people use for it. A question holding one of the words
also starts from the name.

### graphify

`graph.json` follows [graphify](https://github.com/Graphify-Labs/graphify)'s format and node ids, so
graphify's tools read it unchanged. jk's declaration facts sit under each node's `jk` key, which
graphify keeps and does not search. graphify's skill looks for `graphify-out/graph.json` in the
working directory: `[graph] graphify = true` (or `jk graph build --graphify` once) writes
`graphify-out/graph.json` and `GRAPH_REPORT.md` on every refresh. When something else rewrites that
`graph.json` (`graphify update`), the next refresh writes jk's graph back and says so. jk's graph has
no LLM pass: documents are read for their structure, links and code mentions only.

## MCP

The graph operations are MCP tools. graphify's names keep graphify's arguments: `query_graph`,
`get_node`, `get_neighbors`, `get_community`, `god_nodes`, `graph_stats` and `shortest_path`;
`query_graph` and `god_nodes` also take `scope`, `modules` and `source_set`. jk adds `read_symbol`,
`find_symbols` and `rules_for`. `query_graph`, `read_symbol`, `get_neighbors` and `find_symbols` are
on the default `tools/list`; the rest are on the extended one, and `skill(topic=graph)` introduces
them all. See [MCP](mcp.md).

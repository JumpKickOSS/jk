# Code graph

`jk graph` maps a workspace into a knowledge graph of its code, configuration and documentation, and
answers questions from it within a token budget. A coding agent asks the graph where something is
and how it connects instead of grepping and reading whole files; the answer names `file:line` for
everything it mentions.

```bash
jk graph                                   # build or refresh target/graph/
jk graph query "how is the order total computed"
jk graph explain OrderService              # one node and its connections
jk graph path OrderService PriceCalculator -U
jk graph god-nodes                         # the most-connected entities
jk graph report                            # GRAPH_REPORT.md: god nodes, surprises, communities
```

## What is in it

| Source | Nodes | Edges |
|---|---|---|
| Java, Kotlin, Groovy (compiled classes and source) | types, nested types, methods, Kotlin top-level functions, `NOTE:`/`WHY:` comments | `contains`, `method`, `inherits`, `implements`, `imports`, `calls`, `references` (field, parameter, return types, annotations) |
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

## How it stays current

Every `jk graph` command refreshes the graph before answering. Files are compared by size and
modification time, then by content hash; only changed files are read again, and each module's class
index is updated one class file at a time. An unchanged tree answers from the graph already in
memory, or from a stored snapshot. `-r` extracts every file again.

The graph is built in its own worker process, so its memory is never the engine's. The worker
stays up between commands with the graph in memory and exits after 30 idle minutes
(`JK_WORKER_LINGER_MS`), or sooner when another fork needs its memory.

## Reading an answer

```
Traversal: BFS depth=3 | Start: ['OrderService'] | 19 nodes found

NODE OrderService [src=src/main/java/com/acme/shop/OrderService.java loc=L6 community=OrderService]
EDGE OrderService --inherits [EXTRACTED]--> BaseService at=src/main/java/com/acme/shop/OrderService.java:L6
```

The question's words pick the starting nodes (an exact name beats a prefix beats a substring; rare
words weigh more), the graph is walked `-d` hops (default 3), and the result is cut to
`-b` tokens (default 2000), nearest nodes first. A cut answer says how large a budget would
show everything.

| Option | Meaning |
|---|---|
| `-d`, `--depth N` | Hops from the matched nodes |
| `-b`, `--budget N` | Answer size in tokens |
| `-s`, `--dfs` | Walk depth-first |
| `-c`, `--context call,field,…` | Follow only edges with these contexts; a question that says "calls" or "imports" sets it |
| `-n`, `--no-refresh` | Answer from the last graph without looking at the tree |

## Outputs

`target/graph/` at the workspace root:

| File | Contents |
|---|---|
| `graph.json` | The graph in graphify's node-link format (`graph.schema_version` 1) |
| `GRAPH_REPORT.md` | God nodes, surprising connections and communities: one read to orient |
| `graph.bin` | A compact copy jk reloads quickly |
| `files.tsv`, `fragments.jsonl` | Per-file state that makes the next refresh incremental |

### graphify

`graph.json` follows [graphify](https://github.com/Graphify-Labs/graphify)'s format and node ids, so
graphify's tools read it unchanged: `GRAPHIFY_OUT=target/graph graphify query "…"`. jk's graph has no
LLM pass: documents are read for their structure, links and code mentions only.

## MCP

The same operations are MCP tools under graphify's names: `query_graph`, `get_node`,
`get_neighbors`, `get_community`, `god_nodes`, `graph_stats` and `shortest_path`. `query_graph`,
`get_neighbors` and `shortest_path` are on the default `tools/list`; the rest are on the extended
one, and `skill(topic=graph)` introduces them all. See [MCP](mcp.md).

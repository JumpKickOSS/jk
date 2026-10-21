# Graph

`jk graph` maps the workspace: classes, methods, files, config keys, doc headings, and the calls, imports, inheritance and references between them. Ask it before grepping or opening files; every line it answers carries `file:line`. The MCP tools below are on the extended `tools/list`.

| | Shell | MCP |
|---|---|---|
| a question | `jk graph query "order total"` | `query_graph(question)` |
| one symbol | `jk graph explain OrderService` | `get_node`, `get_neighbors` |
| how two connect | `jk graph path A B --undirected` | `shortest_path` |
| where to start | `jk graph god-nodes` | `god_nodes()` |

- Reads refresh first; unchanged files cost a stat. Calls come from compiled classes: build after edits.
- Name symbols; adjacent words that spell a class (`order service`) match it.
- `--budget N` (`token_budget`) widens a cut answer; `--context call` follows calls only.

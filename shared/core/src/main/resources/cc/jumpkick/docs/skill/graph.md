# Graph

`jk graph` maps the workspace: types, methods, files, config keys, doc headings and how they connect. Ask it before grepping or opening files; every answer carries `file:line`.

| | Shell | MCP |
|---|---|---|
| a question | `jk graph query "order total"` | `query_graph(question)` |
| a symbol, not its file | `jk graph read Shop.total` | `read_symbol(address)` |
| declarations by shape | `jk graph find -t interface -I Repo` | `find_symbols(kind, implements)` |
| one symbol's links | `jk graph explain Shop` | `get_neighbors` |
| how two connect | `jk graph path A B -U` | `shortest_path` |
| before editing a file | `jk graph rules <file>` | `rules_for(path)` |

- `read`: `Type`, `Type.member`, `Type.*`, a path or glob, `#line=N,M`; the budget picks content, structure or headline. A library type reads from the locked jar.
- Narrow: `-g 'src/**;!**/gen/**'`, `-m <module>`, `-S main|test`. `-b N` widens a cut answer.
- A miss lists the closest names. Build after edits: calls come from compiled classes.
- graphify tools read the same `graph.json`; `[graph] graphify = true` keeps `graphify-out/` current.

# Graph

jk keeps a graph of the code, built from the compiled classes, so a question about a method is one
call that answers it completely. Name a symbol `Type.method` (`pkg.Type.method` when two packages
declare it, `Type.method(Param)` for one overload).

| | Shell | MCP |
|---|---|---|
| who calls it (`-t`: all that reach it) | `jk graph callers T.m` | `callers` |
| rename it, its overrides, calls, doc links | `jk graph rename T.m new` | `rename` |
| one method's source, not the file | `jk graph read T.m` | `read`* |
| a type without bodies | `jk graph read T -w structure` | `read`* |
| declarations by kind, supertype, annotation | `jk graph find -t interface -I Repo` | `find`* |

\* on the extended `tools/list`.

The answer is final: do not grep or open files to check it. After `rename`, edit only the comment or
doc lines it lists, then test with the `only` it names. Grep stays right for text that is not a
symbol: log messages, config values, TODOs.

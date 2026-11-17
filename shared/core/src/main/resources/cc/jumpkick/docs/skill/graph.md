# Graph

jk keeps a graph of the code, built from the compiled classes, so one call answers a question about a
symbol completely. Address: `Type.method` (`pkg.Type.method` when two packages declare it,
`Type.method(Param)` for one overload), `Type.field`, `Type`, `a.pkg`; or an anchor, for uses no compiler
checks: `route:GET /x/{}`, `table:t`, `column:t.c`, `env:NAME`, `config:key`.

| | Shell | MCP |
|---|---|---|
| who calls or uses it (`-t`: all that reach it) | `jk graph callers X` | `callers(address=X)` |
| rename a method, a field with get/set, a type, a package | `jk graph rename X new` | `rename(address=X, to=new)` |
| one method's source, not the file | `jk graph read T.m` | `read`* |

\* on the extended `tools/list`.

`rename` edits every use the compiler bound and lists the rest with ids: first those that should change
too, then judgment calls (a SQL column, a message key). Apply the ones the task wants with `also=c1,c3`
on the next call; leave unchosen what the task keeps (a column that must stay). It runs the tests and
guard and says when the task is done. Its answer is final: do not grep or open files to check it.

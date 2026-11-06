# Forms

**Audience:** contributors adding a question to jk, or a surface that asks one
**Related:** [tui.md](tui.md#form-widgets) (the terminal widgets), [architecture.md](architecture.md)

When jk has to ask a person something, the question is a **form**: one Java model that the engine
serves as JSON and every surface renders. The terminal, the web wizard and MCP elicitation all draw
the same fields, so an answer means the same thing whichever surface collected it. The model lives
in `shared/jk-api` (`cc.jumpkick.form`): it is client-safe, links only `:host`, and uses no
reflection, so the native CLI and the engine share it.

## Shape

A `Form` (id, title) is an ordered list of `Step`s; a step is an ordered list of `Field`s. Keys are
unique across the whole form.

| Attribute | Meaning |
|---|---|
| `key` | The answer's name: `[A-Za-z][A-Za-z0-9_]*`, not `in`, `true` or `false` |
| `label`, `help` | One line each |
| `kind` | `text`, `bool`, `choice`, `multi`, `search`, `path` (`FieldKind`) |
| `options` | `choice` and `multi` only: an id (no comma or whitespace) with an optional label and help |
| `default` | A literal (`FieldDefault.Literal`), or a source name the engine derives it from (`FieldDefault.Derived`, such as `git-email`); `DerivedDefaults` resolves the name |
| `required` | Defaults to `true` exactly when there is no default |
| `pattern` | `text` only: a regex the whole answer must match |
| `when` | A condition on earlier answers; a hidden field takes its default |
| `advanced` | Not prompted in the terminal or over MCP; the default applies unless a flag sets it |
| `source` | `search` only: the engine endpoint that lists options; `{key}` names an earlier answer |

Answers are a `String` (`text`, `choice`, `search`, `path`), a `Boolean` (`bool`) or a
`List<String>` of option ids (`multi`).

Building a form checks it whole and throws `FormException` naming the field: a `choice` without
options, a default that is not one of the options or misses the pattern, a pattern that does not
compile, a `search` without a source, a duplicate key, a `when` or `source` that names a later or
unknown field. A form that builds is one every surface can ask.

## Conditions

`Condition` parses a deliberately small language; there are no functions.

```
expr    = or
or      = and ( "||" and )*
and     = unary ( "&&" unary )*
unary   = "!" unary | "(" expr ")" | test
test    = key ( "==" | "!=" ) literal | key "in" "[" literal ( "," literal )* "]" | key
literal = 'text' | "text" | true | false
```

A key names an **earlier** field only (in the same step or an earlier one), and the literal must suit
it: `true`/`false` for a `bool`, one of the options for a `choice` or `multi`, a string otherwise. A
bare key tests a `bool`. A `multi` is tested with `in`, which holds when the selection shares an
option with the list. A key with no value is unequal to every literal. Syntax errors name the column;
reference and type errors name the field that owns the condition.

## Checking answers

`Form.check(answers, derived)` settles the answers field by field, in order: a hidden field takes its
default whatever was answered; a shown one takes its answer, which must fit its kind, options and
pattern, or else its default; a required field left with neither is a problem, and so is a key the
form does not have. An empty string is no answer to a required text field. The result (`FormCheck`)
holds the settled values in form order, the hidden keys and the problems.

## Wire shape and `rev`

`Form.toJson()` writes the model through `MiniJson`; `Form.fromJson` reads it back to an equal form.
`rev` is the first 16 hex digits of the SHA-256 of the canonical JSON without the rev, so any change
of content moves it and nothing else does. A decoded form whose `rev` disagrees with its content is
refused. Answers are posted with the `rev` they were collected against, so a stale form is detected
rather than half-applied.

## MCP elicitation

`McpFlattening.flatten(form, step, known, revision, derived, search)` builds the form-mode request for
one step. Only what is missing is asked: a field that is answered, hidden, advanced, defaulted or
optional is left out. A field whose `when` names a field asked in the same request waits for the next
round, so the caller flattens again until it gets `Complete`.

| Kind | Requested schema |
|---|---|
| `text` | `string`, with `pattern` |
| `bool` | `boolean` |
| `choice` | `string` with `enum`; labels as `enumNames` (`2025-06-18`) or `oneOf` of `const` + `title` (`2025-11-25`) |
| `multi` | `array` of an enum (`2025-11-25`); on `2025-06-18`, one `boolean` per option, named `key.option` |
| `search` | `string` enum of the source's first eight matches plus `more …`; picking it, or no matches at all, escalates |
| `path` | escalates |

Escalation (`Escalate`) means the web wizard, by URL elicitation. `Ask.answers(content)` maps the
client's reply back to field answers (folding the per-option booleans into a list), which then go
through `Form.check`. The revision is the one the session negotiated (`cc.jumpkick.mcp.McpRevision`, shared with the engine's MCP server); flattening refuses one without form elicitation.
The flattening produces payloads only; the MCP transport sends them.

## The summary command

`FormCommand.render(command, form, answers, flags, derived)` prints the command line that reproduces
the answers without asking. A field mapped to a `Flag` is spelled with it (`Positional` or `Named`,
a `bool` as `--flag` or `--flag=false`); every other field is `-p key=value`, a `multi` joined on
commas. Hidden fields and values equal to their default are left out, and words are quoted for a
POSIX shell.

## The new-project form

`jk new` and MCP `new` render one form, which the engine builds (`NewProjectForm`): jk's step, then
the template's own `[[ask]]` step. jk's step is one field list, `NewProjectStep` in `:core`, which the
engine serves and the CLI and MCP read their keys from: `lang`, `template` (a search over the
catalogue, default `app`), `name`, `group`, `dir` (the parent directory, derived per surface:
`--dir`/the cwd in the CLI, `parentDir` in MCP), and `layout` when the template renders more than
one. Once a template is chosen, `lang` defaults to its language. The keys of jk's step are
reserved, so no template may ask for one. There is no other way to make a project: no template is
the default template.

| Surface | How it gets the form | Where it renders |
|---|---|---|
| CLI with no terminal | the same requests | `NewForm.settle` checks flags and defaults; what is left is `NewForm.Unsettled`, one `jk new: <key> …: <flag>` line per field |
| CLI on a terminal | `new-project-form-request` (no template: jk's step and the catalogue; a template: both steps and the resolved id) | `NewForm` walks it with `FormRenderer`, then scaffolds through `NewTemplate`; the summary names the resolved id, so it reproduces the same template whatever language was asked |
| CLI `-t <ref> --help` | the same request | `NewForm.help`, a `FieldLine` per field |
| MCP `new` | `NewProjectOps.templateForm` in process | `McpNewForm` settles the answers: arguments and `params`, then defaults, then form elicitation for what is still required, else an error of `FieldLine`s |

`FieldLine` is the one compact spelling of a field (`key: a|b = a  # Label`), shared by MCP
`action=form`, `--help` and the missing-answer errors; `FieldLine.param` is its `-p` form.

## Adding a kind

A kind is allowed only when every surface can render it or escalate it. Adding one means adding it to
`FieldKind`, `Field.problem`, the MCP flattening, a terminal widget in `cli/tui/form`, and the web
wizard, in the same change.

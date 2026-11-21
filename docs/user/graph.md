# Code graph

JumpKick maps a workspace into a graph of its code, configuration and documentation, and answers
questions from it within a token budget. It is built in: nothing to install, an index in seconds,
and call edges from the compiled classes. Every answer names a symbol as `Owner.member()` and places
it at `file:line`. `jk code` asks about the code and refactors it; `jk graph` builds the graph and
walks it.

```bash
jk graph                                   # build or refresh the graph (target/graph/)
jk code query "how is the order total computed"
jk code read OrderService.total           # that method's source, not the whole file
jk code read OrderService -w structure    # the type's declarations without bodies
jk code find -t interface -I Repository   # declarations by kind, supertype, annotation, name
jk code usages OrderService.total        # who calls it, every overload, complete
jk code usages OrderService.total -t     # everything that reaches it
jk code usages Order.status              # every read and write of a field, at its line
jk code usages Order                     # every use of a type (@Audited for an annotation)
jk graph neighbors OrderService            # its direct connections
jk graph path OrderService PriceCalculator -U
jk graph rules src/main/java/com/acme/shop/OrderService.java
```

A name is a node id, a label, or `Type.member` (qualify the type, `shop.OrderService.total`, when
two packages declare it). `usages` and `neighbors` answer for every overload a `Type.member` names;
`path`, which needs one node, lists the overloads' ids instead. Answers name a member with its
owner, `Release.Plan.label()` for a nested type's, so a caller is never read as its file's top-level
class.

## What is in it

| Source | Nodes | Edges |
|---|---|---|
| Java, Kotlin, Groovy (compiled classes and source) | types, nested types, methods, record components (as their accessors), named constants (`static final UPPER_CASE`, Kotlin `const val`), Kotlin top-level functions, `NOTE:`/`WHY:` comments | `contains`, `method`, `inherits`, `implements`, `imports`, `calls`, `references` (field, parameter, return types, annotations) |
| JavaScript, TypeScript, JSX/TSX, Vue | classes, functions, methods | `imports_from` (relative), `imports` (npm packages), `uses` (Angular templates and styles, JSX components) |
| Markdown, AsciiDoc | pages, headings | `contains`, links to other files, `references` to the code a code span names |
| Spring `application*`/`bootstrap*` YAML and properties | one [anchor](#anchors) per key, however many profile files set it | `defines` from each file that sets it; `uses` from code reading `${key}` in an annotation (`@Value`), `@ConfigurationProperties` and key-shaped string literals |
| HTTP handlers (compiled classes): Spring `@RequestMapping` and its shortcuts, JAX-RS `@Path` with `@GET` and the rest, Micronaut `@Controller` with `@Get` and the rest; OpenAPI YAML `paths` | one [route anchor](#routes) per method and path | `defines` from the handler method or the document; `uses` from MockMvc and RestAssured calls, `@HttpExchange` clients, path literals in code, front-end `http.get`/`fetch` calls, and URLs to this service in scripts, workflows, config and Markdown |
| Shell scripts (`.sh`, `.bash`) and workflow `run:` steps | | `uses` of routes their command lines request; `defines` of the variables they `export` or append to `$GITHUB_ENV`, `uses` of the ones they read |
| `.env` files, Dockerfiles (`ENV`, `ARG`), compose `environment:`, workflow and Kubernetes `env:` | one [variable anchor](#environment-variables) each | `defines` from each file that sets it; `uses` from `${VAR}`/`$VAR`, Spring placeholders, `System.getenv` |
| Makefiles (`Makefile`, `GNUmakefile`, `*.mk`) | | `defines` of the variables they `export`; `uses` of `VAR ?= default`, `$(VAR)`, recipe lines as shell lines |
| Spring `server.port`, Dockerfile `EXPOSE`, compose `ports:`, Kubernetes `containerPort` | one [port anchor](#ports) each | `defines` from the file that says a service listens on it; `uses` from a compose mapping's container side and URLs to `localhost:<port>` anywhere |
| `jk.toml`, `pom.xml`, Gradle, version catalogs, `package.json` | dependencies | `depends_on`, workspace and Maven modules |
| XML, `META-INF/services`, `*.imports`, JSON | | `references` to every class they name |
| SQL migrations and schema files; Liquibase XML and YAML changelogs | one [table and column anchor](#tables-and-columns) each | `defines` from `CREATE TABLE`, `ALTER TABLE … ADD`/`RENAME`/`ALTER COLUMN` and Liquibase changes; `drops` from `DROP` and the old name of a rename; `uses` from indexes and foreign keys |
| JPA entities, jOOQ generated classes, SQL in string literals, native `@Query`, MyBatis mappers and annotations, other SQL files | | `uses` of tables and columns |
| HTML, CSS/SCSS/Less, DOT/Mermaid/PlantUML, CSV | pages, columns | loaded files, imported stylesheets, drawn types |

Calls, inheritance and type references come from the compiled classes, resolved by the compiler, so
they are exact. A tree that does not compile yet still gets its declarations from the source; build
to bring the call edges up to date. Links from prose and config keys matched by name are inferred,
and an answer marks them `(inferred)`.

## Anchors

An anchor is a node for something code and other files both name without a compiler between
them: configuration keys, environment variables, HTTP routes, ports, SQL tables and columns, the
JSON properties of a route's body and the bean properties a server-side template reads. Every file
or symbol that defines or uses it has an edge to it, so one
question finds the YAML line, the annotation and the string literal together.

```bash
jk code usages config:server.port        # every definition and use, at file:line
jk code usages shop.max-items            # a label only an anchor has works too
jk code find -t config 'shop.*'           # a kind's anchors, with counts
jk code usages 'route:*'                 # the same listing through usages (MCP: address=route:*)
```

```
config:shop.maxitems (shop.max-items): 2 definitions, 2 uses (1 declared, 1 inferred).
defined:
  src/main/resources/application-prod.properties:1  as shop.maxItems
  src/main/resources/application.yml:2
main:
  Shop  src/main/java/com/acme/shop/Shop.java:6  as shop.maxItems
      L6| @Value("${shop.maxItems:10}")
  Limits.read()  src/main/java/com/acme/shop/Limits.java:9 (inferred)
      L9| return env.getProperty("shop.max-items");
An inferred use was matched by text and nothing checked it; declared ones are read from annotations and definition files.
```

An anchor is named `<kind>:<name>`. Spellings the surface treats as one are one anchor: a
configuration key compares as Spring's relaxed binding does (`maxItems`, `max-items` and
`max_items`), and a line that spells it otherwise says `as …`, the text to search and replace.
Each use has a grade: **declared** when read from an annotation or a definition file, **bound**
when a compiler or generator made the link, **inferred** when a string matched, marked
`(inferred)`. An inferred use is a lead, not a fact; an edge JumpKick cannot ground is never shown as
declared. A key read in code and set in no file still has its anchor, and the answer says `No
file defines it.`; one set and never read says `Nothing uses it.`. Where a symbol and an anchor
share a name (`Shop.currency`), the bare name is the symbol's; write `config:shop.currency`.

### Routes

A route is `route:<METHOD> <path>` with each path variable as `{}`: `route:GET /api/hotels/{}`
answers for `@GetMapping("/hotels/{slug}")` under `@RequestMapping("/api")`, and you may write it
with the variable's name (`jk code usages 'route:GET /api/hotels/{slug}'`). A handler serving
every method is `*`. Only literal paths count: a path built at run time defines nothing. `jk graph
find -t route` lists every route with its definitions and uses; one an OpenAPI document defines
and no handler serves shows the document alone, and a handler's route the document lacks shows
the handler alone. A handler's `usages` answer ends with the route it defines and how many uses
no compiler checks.

A use links to the routes it fits, the one with the most literal segments when several do, and to
none when no route has a literal segment in common (`/{}` would fit everything). It is declared
when a client call names the route's own method and template (`get("/api/hotels/{slug}")` in
MockMvc or RestAssured, an `@HttpExchange` method); a concrete path (`/api/hotels/harborview`), a
path literal anywhere else in code, a front-end call and a URL in a script or document are
inferred. A MockMvc call whose path is a constant loaded ahead of another call of the statement
(`get(ENTITY_API_URL_ID, label.getId())`) is that call's. A front-end request is `http.get(...)`
and the other methods, `fetch(...)`, `cy.request` and `cy.intercept` (with or without a method; a
glob's `+(?*|)` tail dropped, a `*` segment a variable), or an options object's `url` with its
`method`; its path is a literal or template, or is built (`${this.resourceUrl}/${id}`,
`this.resourceUrl + '/' + id`) from a string constant the same file assigns once, a literal or a
helper given one (`getEndpointFor('api/labels')`), or follows a variable named as a base URL
(`` `${serverApiUrl}api/account` ``). A URL counts only when its host is this machine,
a variable or a one-label name such as a compose service, and a command line's method is its `-X`,
POST for `curl` with a body, GET for `curl` or `wget`, any otherwise. Not read yet: a context path (`server.servlet.context-path`), Helidon
SE and other routing written as calls, Kotlin and Groovy route DSLs, and JSON OpenAPI documents.

### Tables and columns

A table is `table:<name>` and a column `column:<table>.<column>`, lower-cased unless quoted, with
the default schema (`public`, `dbo`) dropped. Every migration that defines, changes or drops a
column is listed: `jk code usages column:orders.status` names the migration that created it, the
one that renamed it away (`dropped or renamed away:`), and every use, so a rename is found in the
migration history as well as in the code. The reader works statement by statement without a SQL
parser: what it cannot place (a dialect's own syntax, a name built at run time) contributes
nothing.

| Use | Grade |
|---|---|
| reading a jOOQ generated field or table constant (`HOTEL.SLUG`) | bound: the generator wrote the field from the column |
| `@Table(name)`, `@Column(name)`, `@JoinColumn(name)`; an index or foreign key naming the column | declared |
| a JPA field under the default naming (`totalMinor` → `total_minor`, a `@ManyToOne` → `parent_id`) | inferred: a naming strategy may differ |
| a query in a string literal or text block, a native `@Query`, a MyBatis statement, another SQL file | inferred; ambiguous when a bare column fits two of the query's tables |

A use links only to a table or column some DDL defines, except an explicit JPA name or a jOOQ
field, which make the anchor themselves, so an entity mapped to a column no migration creates
answers `No file defines it.`. A JPA field's uses answer, and its getter's or setter's callers answer, carry the column it
maps to with its places no compiler checks ([What a change reaches](#what-a-change-reaches)). A column with no use answers `Nothing uses it.`, which is not a
promise: a name assembled at run time is not seen. Not read yet: JPQL and HQL (entity names, not
tables), Spring Data's derived queries, a custom naming strategy, and SQL in Kotlin or Groovy
string templates.

### Environment variables

A variable is `env:<NAME>`, case kept. Each file that sets it is a definition, declared: a `.env`
file, a Dockerfile's `ENV` and `ARG`, a script's `export`, compose's `environment:` (map or list),
a workflow's `env:`, a workflow step's `echo "NAME=…" >> $GITHUB_ENV`, a Makefile's `export` and a
Kubernetes container's `env:` entries. Reads: `System.getenv("NAME")`, a
`${NAME}` placeholder in a Spring config file or `@Value`, and `${NAME}`/`$NAME` in a Dockerfile are
declared; `$NAME` in a script, a workflow step or another YAML value is inferred and links only to a
variable something sets or reads. Variables every shell has (`HOME`, `PATH`, …) are left out.

`jk code usages env:FEATURE_X` on a variable only code reads answers `No file defines it.`; one a
`.env` sets and nothing reads, `Nothing uses it.`. A variable named as a Spring key under relaxed
binding (`SPRING_DATASOURCE_URL`) shows the key it sets and that key's definitions and uses (`sets
config:spring.datasource.url (inferred)`), and the key's answer names the variables that set it;
the binding is inferred because nothing says the program is a Spring one. Only names enter the
graph: no value of any variable is read into it, so a `.env` holding secrets contributes their
names alone, and an answer names the line that sets a variable without quoting it.

### Ports

A port is `port:<number>`. Where a service is told to listen is a definition, declared:
`server.port` in a Spring config file (a `${PORT:8080}` default counts), a Dockerfile's `EXPOSE`,
the published side of a compose `ports:` entry (`"8081:8080"` defines 8081) and its long form's
`published:`, a Kubernetes `containerPort`. The container side of a compose mapping (8080) is a
declared use; the long form's container side is not read yet. A URL to this machine
(`localhost:8080`, `127.0.0.1:8080`) in a script, a workflow, a config value, a doc or code is an
inferred use and links only to a port something defines or uses. So `jk code usages port:8080` before moving a service lists the
Dockerfile, the compose file and every script and test that calls it there.

### JSON properties

A property of a route's JSON body is `json:<Class>.<property>`: the class a handler returns or reads
as its request body (any class named in the generic type, so `ResponseEntity<Page<OwnerDto>>` names
`OwnerDto`; a Kotlin `suspend` handler's result), or one reachable from it through properties and
supertypes, and the name Jackson writes. Both are read from the compiled classes, so a Kotlin data
class or a Groovy class reads as a Java one: a record component, a public field, a public getter
(`isX` for a `boolean`; a Kotlin `isActive` keeps its name), a setter, any member with
`@JsonProperty`, the `@JsonProperty` kotlinc or scalac puts on a constructor parameter, a
class-level `@JsonNaming` or `@JsonAutoDetect(fieldVisibility = ...)`; `@JsonIgnore` and
`@JsonIgnoreProperties` drop one. A Spring MVC `@Controller`'s return counts only with
`@ResponseBody` or as a `ResponseEntity`. `<Class>` is the simple name, or the qualified one where
two classes the bodies reach share it. The definition is the line declaring the member in its
source (the field, component or `val`, else the getter), bound. A class whose source changed since
it was compiled is not read until the next build.

A bare name such as `id` or `name` says nothing about whose it is, so a client's mention links only
where something ties it to a route or a class:

| Mention | Grade |
|---|---|
| a TypeScript or JavaScript read (`.birthDate`, `['birthDate']`, a destructured name) in the innermost function that requests the route (a `describe` or `it` callback is one), or a function nested in it | inferred: must change |
| a key of an object literal the request sends as its body (a shorthand `{ newPassword }` too; an options object's `body`, `data` or `json`, through `JSON.stringify` too; the body argument of `post`, `put` or `patch`; a constant of the file sent so, wherever it is declared), or compares with a value (`toEqual({...})`, `deep.equal`) in the request's function | inferred: must change |
| a member of a TypeScript interface, type or class named for the class (`Owner`, `IOwner` and `OwnerDto` are one name) | inferred: must change |
| a jq field on the pipeline that requests the route; a JSONPath literal (`"$.pets[0].name"`) in the test method that requests it | inferred: must change |
| a property of an OpenAPI schema named for the class, or under the route's path and method | inferred: must change |
| a key of a JSON fixture (a `.json` under a test or fixtures directory) that a file requesting the route names | inferred: must change |
| any of these elsewhere in a file that requests the route, a key of any other object literal there (an options object's `id:`), or a name several of the route's classes carry | ambiguous: a judgment call |

Anything else links nowhere: a `.name` in a file that requests no route is not a use. A file a tool
writes is never read: under a `generated` directory, headed as generated, or named as an output by
a build file (a springdoc or openapi-generator plugin in `pom.xml` or `build.gradle`, an
`openapi-typescript`, `orval` or `openapi-generator-cli` script in `package.json`). An OpenAPI file
is one whose name or directory says `openapi`, `swagger` or `api-docs`. A workspace with no route
whose body is one of its own classes, or no client tied to one, has no anchor of this kind. Not
read, since a class file does not say it: a naming strategy or visibility set in configuration or
on an `ObjectMapper`, mix-ins, custom serializers, `@JsonView`, `@JsonUnwrapped` prefixes,
polymorphic type ids; nor a request whose path is built at run time from anything but a constant of
its own file, which ties nothing but a typed member.

### Template bean properties

A property a Thymeleaf template reads off its model is `bean:<Class>.<property>`: renaming
`Owner.getTelephone()` compiles, and `${owner.telephone}` fails only when the page renders. The
template is tied to its model through the Spring MVC `@Controller` that renders it, read from the
compiled classes: a handler (or a method of its class it calls) renders the template whose name it
loads (`"owners/ownerDetails"`, a constant, a `ModelAndView`'s), and its model holds what it puts
with `Model.addAttribute`, `ModelMap`, `Map.put` or `ModelAndView.addObject` (the value's type from
the local variable's generic signature, the call that made it or the class it constructs; with no
name, Spring's `owner` or `ownerList`), the class's `@ModelAttribute` methods and its form-backing
parameters. Each segment of `${owner.pets}`, `*{telephone}` under `th:object`, `th:field`, a
`th:each` variable and a fragment called with `'telephone'` as the field it binds is a use of the
property of the type before it (a getter, `isX` for a `boolean`, a public field, a record
component), defined at the member's declaration, bound. A use is inferred (must change) when one type
answers for its root; ambiguous (a judgment call) when the handlers rendering the template give that
root different types that both carry the name; a root nothing types links nowhere. Read: Thymeleaf
templates under the main resource tree's `templates/` (`src/main/resources/templates`, or
`resources/templates` in a simple-layout module). Not read: JSP, FreeMarker, Mustache and other
engines, a view name built at run time, a model filled outside the controller's class (an
interceptor, a `@ControllerAdvice`), and a path built by preprocessing (`__${...}__`) other than a
fragment's `*{__${name}__}` field. A workspace with no template, or no `@Controller` route, reads
nothing.

### After a hand edit

An edit that changes one side of an anchor by hand (a column renamed in one dialect's `schema.sql`,
a key renamed in `application.properties`) leaves the other side under the old name, and no
compiler, and often no test, checks it. Each refresh compares the anchors of the files whose content
changed with the graph it replaces, the one the last refresh wrote (a graph question's, or the one
after a build that succeeded). An anchor a changed file no longer names, renamed (the file now names
another of its kind) or removed while nothing else defines it, is left stale wherever another file
still carries the old name: a schema, a query, an entity's mapping, a key, the variable that sets the
key, a route's client, a tied read of a JSON property must change; a mention in prose (markdown,
text), found by searching the docs for the old name only when a name changed, or a JSON property's
untied or shared use is a judgment call and only ever counted. A use a
compiler or generator bound is the build's to check and is never listed, nor is a generated file,
nor a file that merely shares an anchor with the edited one; an edit that changes no name finds
nothing. The places are kept in `target/graph/anchors/stale`, each dropped once its file no longer
carries the old name or the edit is undone, so a build after a graph question still names them.

The record is always kept, and `usages` answers from it (below). An agent also sees its places without
asking: in a workspace with a graph, an MCP `run` that builds (`kind=build`, `test`, `gate`, `guard`,
`install` or any kind that builds the tree) and passes ends with the places that must change, a file's
lines as one place, in five files at most, then `+N more:` and the call that lists the rest. It says
nothing when none is left, nothing after a run that failed, nothing in a checkout with no graph yet,
and nothing on the CLI: `jk build` and the rest print their usual output, and `usages file:<path>`
is how a person asks. Prose that still names the old name is not mentioned. `[graph] anchor-tail =
"off"` turns the lines off ([Settings](#settings)); a project JumpKick builds from its `pom.xml` has no
`[graph]` to set and gets none. A verdict waits for the refresh its build started (10 seconds at
most) only when a file that defines or uses an anchor, or that the record names, changed since the
last refresh (`target/graph/anchors/watch` lists them with their hashes); otherwise that refresh
cannot change the record, which is read at once, so a run that is up to date, or whose edit touched
no such file, takes no longer than without the graph. On the agent bench's petclinic tasks that
change one side of an anchor, agents' runs ended green 62 times in 68 with the lines and 56 without,
and no task whose right change touches nothing else ever showed them. On spring-petclinic, with the column renamed in MySQL's schema
alone (one line, wrapped here):

```
OK build spring-petclinic · 9.7s
Your edit changed these names; these places still use the old one:
  column:owners.telephone, now owners.phone: src/main/resources/db/h2/schema.sql:42,
  src/main/resources/db/postgres/schema.sql:32, src/main/resources/db/postgres/data.sql:25 and 9 more
  lines, src/main/java/org/springframework/samples/petclinic/owner/Owner.java:65
```

`usages` with `file:<path>` (or `file:<path>:<line>`) answers for a file: first the places its
last edit left under an old name, then each anchor it defines or uses with the other places that
name it, a use a compiler bound counted, not listed.

Every JVM declaration also carries its kind (class, interface, enum, record, annotation, object,
method, constructor, function, property), visibility, last line, annotations, supertypes and the
first sentence of its doc comment. `read`, `find` and the search use them.

## How it stays current

A checkout has a graph once the first `jk graph` or `jk code` command or MCP `usages` call makes one;
nothing builds it before that. From then on it is kept current: every such command refreshes it before
answering, and a build or `jk guard` that succeeds refreshes it right after it reports, so the graph
an agent reads is the tree as it stands. That refresh writes under `target/` after the build has
returned; `jk clean` waits for it (up to two minutes) rather than deleting the tree under it, but a
script that removes `target/` itself can race it. `jk clean` is also how a checkout stops: it has no
graph until the next graph question. Files are compared by their stat identity (size, modification
and change time, file id), then by content hash; only changed files are read again, and a file that
will not read contributes nothing, its earlier facts included. A refresh that fails leaves the
previous graph and its state as they were.

What each file declares is kept in the [action cache](cache.md), keyed by the file's path, content
hash and the extractor's version, so another worktree, branch or fresh clone on the machine reads
none of the files the first already read. A module's compiled facts are kept there too, keyed by the
compile that produced its classes, which covers its classpath: a checkout takes them only for
classes that compile would give it, never from a compile against other dependencies.

A class's compiled facts (calls, overrides, exact lines) are read only while its source is the one
the module's last successful build compiled. A question asked after an edit to a module's main
sources, or while a module's main sources have never been compiled (a fresh checkout), compiles
that module first (a `compile` job, in the history like any other; a second or two on a typical
module, a restore when the action cache holds it), so its calls and overrides are the code's as it
stands, with no `jk build`. Building the graph asks no question and compiles nothing: the graph
`jk new` writes, `jk graph build`, and the refresh after a build read the classes as they are. When a question's compile cannot run (the code does not compile yet, or a build
of the checkout is running), the question is answered without it and says why in one line. A module
with no compiled classes then contributes what its sources declare, the line names it, and a
callers answer does not say it is complete. After an edit, an edited source contributes what its
text declares, and every other file of the module keeps its compiled facts, so the callers of the method being changed are
still there mid-edit; the answer names the edited files. A compile that fails mid-edit (a signature
changed, its callers not fixed yet) still writes the classes of the files it got through, the edited
ones among them: those are not read, every other class keeps its facts from the module's last good
compile, and the answer names the module and that compile's time. A class file no edited source
compiles to (a hand edit, another build's output) still leaves the module out. A module's tests that were never
compiled are compiled the same way, in a job of their own after the main sources (in the history
as a `compile`), so a fresh checkout's callers include its tests; when that compile cannot run, the
line names their directory (`core/src/test`). A question compiles the same test tree every verb
does: the default suite with the suites the guard runs (`[test] guard-suites`, by default `test` and
`integration`), so `core/src/integration`'s calls are there on a fresh checkout. A suite outside that
tree (`core/src/e2e` unless the guard runs it) is named the same way until a run that compiles it
leaves its classes, and so is the guard suite (`core/src/guard`) until `jk guard` compiles it into
`target/guard/classes`, which the graph reads too. An edited test is not compiled for a question: it is named with the
edited files. A module whose class
files the build did not write, or that is being built while the graph refreshes, contributes what
its sources declare, and the answer says which modules those are. `jk build` brings the compiled
facts back. An unchanged tree answers from
the graph already in memory, or from a stored snapshot. `-r` extracts every file again. `-n`
answers from the last graph written without refreshing, and is an error when none has been.

The graph is built in its own worker process, so its memory is never the engine's. The worker
stays up between commands with the graph in memory and exits after 10 idle minutes (30 on CI;
`JK_WORKER_LINGER_MIN` sets it), or sooner when another fork needs its memory.

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
| `-l`, `--library <name>` | Ask a locked library's public API instead of the workspace ([below](#asking-a-library)) |

### Asking a library

`jk code query -l <name> <question>` answers "what in this library does X" from the jars
`jk-lock.toml` pins, for when the type's name is not known yet. `<name>` is an artifact
(`jackson-databind`), a `group:artifact`, a whole coordinate or a glob over any of them
(`'com.fasterxml.jackson.*'`); a name no locked jar matches lists the ones whose coordinate
contains it.

The question's words are matched as above against every public type and member name, and against
each one's Javadoc summary when the library's `-sources.jar` is on this machine (`jk sync --sources`
fetches it); without one the answer says only names were matched. Each match shows its qualified
name, its Javadoc summary and its signatures, the first four overloads of a member and a count of the
rest, best match first, as many as the budget holds. A member name that matches on many types
(`toString`) is shown three times and counted after that. The answer opens with the locked version
it read; `jk code read` on a name it lists shows the source.

```bash
jk code query -l jackson-databind "read json into a tree"
jk code query -l guava "split a string" -b 4000
```

A jar's API is indexed the first time a question asks about it, and again only when the jar or its
sources jar changes; the index is kept under the store. A library question compiles nothing, and the
walk and scope options do not apply to it.

## Scope

`query`, `neighbors`, `usages`, `read` and `find` take the same three narrowing options. A symbol
outside the scope is neither a starting point nor walked through.

| Option | Meaning |
|---|---|
| `-g`, `--scope <glob;glob;!glob>` | Paths from the workspace root. `*` stays in one directory, `**` crosses them, `{a,b}` is either; `!` excludes. A path without wildcards is everything under it |
| `-m`, `--modules <sel>` | Modules, as `jk build -m` selects them; a nested module is not part of its parent |
| `-S`, `--source-set main\|test` | Main sources, or any test suite |

```bash
jk code query "retry policy" -m server/engine -S main
jk code find '*Test' -g 'server/**;!**/integration/**'
```

## Reading a symbol

`jk code read <address>` answers with the source of what the address names, in the richest view
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

A Kotlin function or property that writes no type (`fun find(id: Long) = repo.findById(id)`) shows
the type the compiler gave it in the `structure` and `headline` views, marked:
`fun find(id: Long): Optional<Owner> (inferred)`. It comes from the compiled class, generics
included, with `?` only where kotlinc marked the result nullable; where the class does not say, no
nullability is claimed. A source edited since its classes were built, or a module with no compiled
classes, shows the declaration as written, with no type claimed.

A name several files declare is refused with the candidates; qualify it. A type no workspace file
declares is read from the jars `jk-lock.toml` pins, and the answer opens with the locked version
it read. The read fetches that library's `-sources.jar` the first time it needs it, unless
`--offline` (`jk sync --sources` fetches every one at once), and answers from it in the same views:
`jk code read ObjectMapper` shows the type's public and protected API, each declaration's Javadoc
and signature without bodies or private members, and `jk code read ObjectMapper.readValue` each
overload's source starting at its Javadoc. A library that publishes no sources jar is answered
with the public signatures its class file holds, generics included, without Javadoc, and the answer
says why. Each jar's type list is read once and kept under the store.

## Finding declarations

`jk code find [name-glob]` lists the declarations that pass every filter given, one line each with
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
jk code find -A RestController
jk code find total -t method -I OrderService   # every override
jk code find -t member -p public -m server/engine 'get*'
jk code find -l '*Mapper' -t class
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

The graph is build output: nothing of it is in the tree or committed. `target/graph/` holds this
checkout's state, gone with `jk clean`: `graph.bin` (the linked graph), `files.tsv` (each file's
stat, so a refresh reads only what moved), `anchors/stale` (the places hand edits left under an
old name, [After a hand edit](#after-a-hand-edit)) and `anchors/watch` (the files whose edit could
leave one, with their hashes and the anchors each defines or uses, which is how two sessions'
verdicts tell that their edits meet at an anchor, [MCP](mcp.md)). The fragments it is linked from are action-cache entries,
one per file and one per compiled class set, shared by every checkout on the machine and reclaimed
by `jk cache clean` like any other. None of it is an exchange format. A `.jk/graph/` directory left
in a checkout is removed by the next refresh.

## Settings

The workspace root's `jk.toml`:

```toml
[graph.aliases]
Tariff = ["price", "rate card"]          # a question saying "price" also starts at Tariff
```

An alias maps a name in the code to the words people use for it. A question holding one of the words
also starts from the name. JumpKick's graph has no LLM pass: documents are read for their structure, links
and code mentions only.

`[graph] anchor-tail` is what an MCP `run`'s verdict says after a hand edit left a name behind
([After a hand edit](#after-a-hand-edit)):

```toml
[graph]
anchor-tail = "off"      # the default is "silent"
```

`silent`, the default: the places that must change and nothing of the prose. `off`: the verdict says
nothing of it and neither waits for the graph's refresh nor reads the record. Two variants are
experimental: `count`, those places and how many places of prose (`Docs use the old name too: 3
places.`); `kinds`, how many of each kind of file (`1 markdown, 2 text`).

## Usages

`jk code usages Type.method` (MCP `usages`) lists every method that calls it, each as
`Owner.method()` with its call sites' `file:line` and the first lines quoted, main sources before
tests, with a count. The calls come from the compiled classes, so the list is complete: a call
inside a lambda counts for the method holding it, and a call made through a supertype's method is
listed under that method. The calls are followed overload by overload: a call to `tool(String)`
does not reach what only `tool(Path)` calls. A module whose classes are older than its sources is named in a note,
and its calls are missing until `jk build`.

`-t` (MCP `transitive=true`) lists everything that reaches the method instead: its callers, theirs,
and so on up to `-L` hops (default 8), grouped by hop, each with the method it calls on the way.
That is the change-impact question grep answers one hop and one file at a time. A direct answer whose callers are called in turn says how many are, and that `-t` gives the
whole chain in one answer.

Ask it before changing a method's signature or behaviour, and trust the list: it is complete, so
there is no need to grep for the name afterwards. A method reached only by reflection or named in
a string is not a call, so it is not listed; the note under the answer says when a module's calls
are missing.

**One completeness rule, every language.** `usages` says its list is complete only where a
compiler bound every call it lists. A call matched by name instead is listed `(inferred)`, and the
answer then says it is not complete and why, in one line.

**Kotlin.** A top-level function is named by its file, its facade class or alone: `Api.greet`,
`ApiKt.greet` or `greet`. What the compiled classes say, and what the answer does about it:

| Shape | Answer |
|---|---|
| A call that omits a default argument (kotlinc calls `name$default`) | Listed under the function, exact |
| A function taking a value class (compiled as `lookup-4ZD5Yi0`), a value class's constructor (`constructor-impl`) | Listed under the declared name, exact |
| A function renamed by `@JvmName("sumOf")` | Its callers are the compiled `sumOf`'s, listed under the declared function, exact |
| An extension function, a property accessor called from Java (`getBalance()`) | Exact: the classes call them by name |
| An `inline` function | kotlinc copies its body into each caller, so the classes hold no call: each line of a Kotlin source that calls it by name is listed `(inferred)`, and the answer is not complete |

**Groovy.** A class compiled `@CompileStatic` binds its calls, so its callers are exact. Groovy that
is not binds its calls at run time, by name: its classes hold no call to the method. While a Groovy
source like that can see the method (its module sees the declaring one, as the build does), the
answer is not complete, and each of its lines that calls the method by name is listed `(inferred)`:
`name(…)`, a command call or method pointer `.name`, and for an accessor the property, `store.size`
for `getSize()`. A call through a computed name (`obj."$name"()`) or `methodMissing` is not matched.
A workspace with no Groovy, or only `@CompileStatic` Groovy, answers as before.

### Uses of a field, a type, an annotation or a constructor

`usages` answers for any symbol a type declares, in the same shape: each use under the declaration
holding it, `Owner.member()` at `file:line` with the line quoted, main sources before tests, and a
count.

| Address | Lists |
|---|---|
| `Order.status` | Every use of the field, writes (`=`, a compound assignment, `++`, `--`) apart from reads |
| `Order`, `shop.Order`, `Order.Line` | Every use of the type: declarations, `new`, casts, generics, `Order.class`, static calls through it. An import is listed only for a file that uses it nowhere else |
| `@Audited` or `Audited` | Every declaration it annotates (an annotation is a type) |
| `Order.Order`, `Order.new` | Every call of its constructors: a `new`, a subclass's `super(…)`, `Order::new` |

A Java use comes from the reference index javac writes during every compile, so it is the use
javac resolved, at its exact line, whatever the variable is called; a constant javac copied into
another class is still listed where it is written. A `{@link}` to the symbol is counted and listed
apart as a doc link. A constructor's calls come from the compiled classes, as a method's do, in
every language.

What is exact, and what is not:

- **Java, compiled as it stands:** exact.
- **Kotlin and Groovy:** their compilers write no reference index yet, so a Kotlin or Groovy source
  that can see the symbol is searched for its name, and each line that spells it is listed
  `(inferred)`. Outside its own class a Kotlin property is used through its getter and setter, so
  `usages Type.getName` lists those uses from the compiled classes; a Groovy property is only
  matched by name.
- **A Java source edited since its compile, or in a module with no compiled classes:** searched for
  the name the same way, `(inferred)`.

The answer says it is complete only when no such source spells the name: then every use is one javac
resolved, and no search is needed. Otherwise its second line says how many sources were matched by
name, and the rest of the list is still exact. An unused field, type or annotation is answered
`No Java source uses it.` under the same rule, so "no uses" is a fact you can delete on, not a missed
search. Reflection and names in strings are not uses.

### What another version of a dependency breaks

`jk code usages dep:<group:artifact>@<version>` (MCP `usages`, the same address) answers what in
this project breaks if a locked dependency were at another version, newer or older than the locked
one. The artifact alone works when one locked module has that name.

```bash
jk code usages dep:com.google.guava:guava@33.4.8-jre
jk code usages dep:guava@32.1.3-jre     # a downgrade reads the same way
```

It compares the public and protected classes, methods, constructors and fields of the locked jar
with the jar of that version, fetched through the project's repositories, and lists each one gone
as removed, renamed (one new name takes its place) or signature changed (a member of the same name
remains). A member moved up to a superclass in the jar is not gone. Under each change it lists the
places this project's compiled classes call it, read it or extend it, at `file:line`, then the
changes nothing here uses. The [update](mcp.md) preview counts these places for each move and names
this address.

### What a change reaches

A `usages` answer carries what else a change to the symbol reaches, so one question does the work
of one per accessor, column and template. Each list is bounded (six places per anchor, six callers
per accessor, six test classes, each with the question that lists the rest), the places no compiler
checks come first, and the answer says what it looked at; it never says nothing else exists.

| Asked about | The answer also carries |
|---|---|
| a field (`Owner.telephone`) | a `Looked at:` line naming the anchor kinds looked for and found; under `anchors`, each anchor the field or its accessors map to (its column, JSON or bean property, configuration key) with its definitions, the variables that set it, then its uses, each at `file:line` quoted, uses a compiler checks (jOOQ) counted only; after the Java uses, each accessor's callers (`getX`, `isX`, `setX`, a record's `x()`); then the test classes that call an accessor, or call a method that does, or use one of the anchors |
| an accessor (`Owner.getTelephone`) | its property's anchors with their places, and the property's other accessors with their callers' `file:line` |
| a handler (`OwnerController.initFindForm`) | the route it defines, with every place that requests it |
| a type (`Owner`) | before its uses, one line per anchor it or its members map to or define (its table, its fields' columns and properties, its methods' routes), with counts and the first places |
| an anchor (`column:owners.telephone`, `bean:Owner.telephone`, `config:...`) | after its places, the Java side: the field it maps to, its accessors' caller counts and the field's other anchors; then the test classes that reach it |

The field, its accessors and the anchors are found through the graph's links (a column a field's
annotation or name maps to, the member a JSON or bean property is read from, the field a
`@ConfigurationProperties` key binds), not by name: a method that only happens to be called
`getTelephone` on another class is not listed.

## Rename

`jk code rename <symbol> <new name>` (MCP `rename`) renames in one call, by what the symbol is:

| Symbol | What it renames |
|---|---|
| `Type.method`, `Type.method(int,String)` | a method: every overload, or one; every override, implementation, call, method reference and `{@link}` |
| `Type.field` | a field as a bean property: the field, its `getX`/`isX`/`setX` and their overrides, a record component's accessor; `--no-accessors` for the field alone |
| `Type`, `Outer.Inner`, `a.b.Type` | a type: every use, its constructors, its file (`Owner.java` → `Customer.java`) |
| `a.b.Type` → `c.d.Type` | a type moved: its file to the new package's directory (in another module with `--module`, or when only that module holds the package), its package line, every import and qualified name, and an import wherever a use relied on sharing the old package |
| `a.b` → `a.c` | a package and its subpackages: every source and the resources under its path move, in main, test and every suite; package lines, imports, qualified names, `module-info` |

`--kind method|field|type|package` settles an address that could be two.

**What it edits, and what it hands back.** It edits a use only where a compiler bound it to the symbol:
javac's reference index for Java (every name javac resolved, at its exact span), or for a method the
compiled classes, where a file's uses of the name are exactly the ones they count. Everything else
that spells the name is returned, not edited and not dropped, as a candidate with an id, the grade of
the match and the reason, at `file:line` with the line quoted:

- **declared**: a file whose format names the class by its full name (a `META-INF/services` file,
  Spring's `*.imports` and `spring.factories`, `jk.toml`, an XML config);
- **inferred**: a match by name (a template's bean path `${owner.telephone}`, a JPQL string, a
  reflection string, a Kotlin or Groovy source javac does not index, a source in a module with no
  compiled classes). For a `@ConfigurationProperties` field, the key it binds should change only
  where an `application*` or `bootstrap*` file sets that key (`app.mail.sender-address`, nested in
  YAML or flat, in any relaxed spelling), and so should the environment variable that sets it; the
  word in any other YAML or properties line (a workflow, a compose file, a value) is a judgment call.

Candidates that should change come first; judgment calls (a message key, a SQL column, a comment, a
doc, a test class named after a type) after them. `--also c1,c3` (MCP `also`) applies chosen ones in a
later call, each line only while it still reads as it did; `also` takes ids, ranges (`c2-c5`), or a
kind: `declared`, `inferred`, `must`, `hazards`, `all`. On the rename's own call, `--also declared`
applies the services files with the rest; there a kind applies only the candidates that should change
(and the pins), never a judgment call, which no list has shown yet: those stay listed for a later
`--also` by id.

An answer over its budget (2,000 tokens over MCP) never hides an id: it drops the edited files' lines
first, then lists the candidates by kind, each with its ids and files (`c8-c12,c15 inferred, message
key: 6 in messages.properties, …`). The same rename called again after it was made changes nothing and
lists every candidate and hazard with its line, and which ids were applied since.

**Hazards.** A rename that changes behaviour with no compile error is flagged with the pin that keeps
the old behaviour, applied with `also=h1`: a JPA entity field with no column name (`@Column(name =
"telephone")`), a Jackson-serialized field (`@JsonProperty("telephone")`); a `Serializable` class's
field is flagged with no pin.

**The proof.** After writing, the rename compiles the modules it edited, main and tests, and checks
that each renamed symbol's new name has exactly the references javac resolved for the old one, at the
same lines, and that the old name has none: `Proven: it compiles, and …`. The proof covers what
compiled: when a set that may call the old name has no compiled classes (a suite outside the test
tree, a module with no classes), its uses are listed as candidates, the answer says `Not checked: …`,
and the proof reads `Proven only where it compiled; not for <set> …, so the rename is not done`. Tests
that did not compile before the rename are not compiled for its proof, so a failure the rename did not
cause never undoes it; they are named as not checked. A local variable the new name
would shadow, or a compile error (a use only a candidate holds, say), undoes the whole rename: every
file is put back and the answer says why (`Rolled back, nothing is changed …`), naming the candidate
to add with `also` when that is the cause. A file whose index javac has not rewritten is named as not
proven.

A method rename's remaining detail: the compiled classes say which calls are the method's (a call
names its owner and descriptor, and the class hierarchy says whether that reaches the method), so a
`contains` on a `String` or a `List` is never touched; where another method of the name is called in
the same body, uses are told apart by argument count, and what still cannot be told apart is a
candidate. Comments in the edited files and doc lines that can only mean the method are renamed with
it. A module may call a method when it sees, as the build does, a module declaring it or one it
overrides; a module with no compiled classes among those leaves the answer not complete, and its
uses are candidates. A rename compiles a never-compiled module first, as a question does.

It refuses, changing nothing, when a source was edited since the build (build first), when the new
name collides (a related type declares the method with the same parameters, the type has a field of
that name, a type of the new name exists or a file using the type already names another type so),
when a method overrides one outside the workspace or `Object`'s, and when a move would break a use
(a package-private type or member used across the new package line, a module that does not see the
target module), naming each.

A shared-surface name such as a JPA column or an HTTP route is what the graph's anchors model;
until they answer for it, rename reports it as a candidate or hazard as above.

## Delete, move and pull up

```bash
jk code delete OrderService.legacyTotal          # only if nothing uses it; else lists what does
jk code move PriceMath.round Money               # a static method or field, every use edited
jk code pull-up Book.label Item                  # an instance member up to the class Book extends
```

`delete` removes a method (every overload), a field or a type only when nothing uses it: no call or
reference in the compiled classes, no anchor it maps to, no override, no framework annotation or entry
point, no doc link or string naming it. Otherwise it writes nothing and lists the uses as `usages`
does, and each framework annotation that keeps it at the `file:line` that writes it. `move` takes a
static Java method (every overload) or static field to another class, enum or record and edits
every use javac bound; `pull-up` takes an instance method or field to the class its type extends,
where its uses bind as written. Both write nothing and list what would break or change
behaviour without a compile error (state the target does not have, a `super` call, a member one side
cannot see, an override). Like `rename`, each then compiles the modules it touched and undoes the
edit whole when they do not compile.

The [retrieval bench](../../bench/graph-retrieval/README.md) asks who calls a method, what
implements or overrides it and where it is defined, on seven JVM repositories, and grades every
answer against the call and type facts read from the compiled classes. Each cell is the median
answer size in tokens and the share of the compiler's answer it named.

<!-- graph-table:begin -->
| Repo | Lines | JumpKick tokens · found | grep tokens · found | graphify tokens · found | RepoQL tokens · found |
|---|---:|---:|---:|---:|---:|
| gs-multi-module | 83 | 32 · 100% | 171 · 100% | 158 · 100% | 5 · 0% |
| gs-uploading-files | 416 | 32 · 100% | 551 · 100% | 218 · 100% | 12 · 30% |
| spring-petclinic-kotlin | 1,889 | 155 · 99% | 855 · 70% | 20 · 41% | 63 · 34% |
| spring-petclinic | 3,635 | 172 · 100% | 2,212 · 98% | 202 · 94% | 136 · 12% |
| commons-cli | 17,862 | 142 · 100% | 24,509 · 96% | 145 · 68% | 193 · 17% |
| commons-io | 105,209 | 118 · 100% | 26,037 · 98% | 142 · 60% | 388 · 45% |
| commons-collections | 129,244 | 190 · 99% | 29,064 · 95% | 184 · 71% | 225 · 16% |
| commons-lang | 188,332 | 185 · 98% | 22,696 · 96% | 150 · 61% | 197 · 38% |
| JumpKick | 679,533 | 168 · 100% | 32,527 · 97% | 87 · 48% | 166 · 39% |
<!-- graph-table:end -->

<!-- graph-claims:begin -->
On JumpKick (679,533 lines), asked who calls a method, what implements or overrides it and where it is defined, JumpKick's graph tools answered in a median 168 tokens and named 100% of the compiler's answer; grep and reading the files it matched took 32,527 tokens for 97%. Per correct answer that is 52 tokens against 17,482. On the same questions: graphify's 87 tokens for 48%; RepoQL's 166 tokens for 39%. Indexing it from nothing: JumpKick 8.9 s (2.1 s after a one-line edit), graphify 59.2 s, RepoQL 88.2 s. JumpKick's call edges match the compiled classes' at 96% precision and 98% recall. ([The bench](../../bench/graph-retrieval/README.md), results of 2026-11-01.)
<!-- graph-claims:end -->

## MCP

JumpKick's MCP server lists one graph tool by default, `usages` (methods, and every use of a
field, type, annotation or constructor): every card an agent's host shows is paid
on every turn, and in measured agent runs `usages` and `rename` are the ones that repay it.

<!-- agents-claims:begin -->
We asked coding agents from 6 vendors questions like "who calls this method?" With JumpKick's code graph, they answered in **66–91% fewer back-and-forth turns** and used **72–98% fewer tokens** than without it. Renaming a method everywhere it is used took **75–91% fewer turns** and **78–98% fewer tokens**. The graph pays off even on tiny projects: the smallest we tested, gs-multi-module, has just **83 lines of code**. ([The tables](why.md#asking-about-the-code).)
<!-- agents-claims:end -->

`query`, `read`, `find` and `delete` answer as their
`jk code` subcommands do and are on the extended list (`tools/list` with `extended: true`, or
`[mcp] tools = "all"`); every tool is callable by name either way, and `callers`, the name
`usages` had before, still answers as `usages`. `move` and `pull-up`, and `neighbors`, `path` and
`rules`, are shell commands alone. See [MCP](mcp.md).

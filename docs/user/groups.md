# Groups of workspaces

A repository can hold several self-contained workspaces: a service and the libraries it ships, a
second service, a set of examples. A **group root** is a `jk.toml` at the top that lists them and
does nothing else. From the root every build verb runs over the members; inside a member nothing
about the root shows.

```toml
# jk.toml at the repository root: it lists workspaces and holds nothing else
[workspaces]
members = ["cache", "insights", "repo", "examples/*"]
exclude = [
  { path = "examples/android-app", reason = "downloads the Android SDK" },
  { path = "examples/preview", reason = "needs Java 27, which this machine may not have" },
]

[profiles.all.workspaces]
include = ["examples/*"]
```

A member is a [workspace](workspaces.md) (its own `[workspace] modules`) or a single project. Each
keeps its own `jk.toml`, `jk-lock.toml`, version and `target/`.

## The root contributes nothing

A member builds exactly the same with or without the root: the same action keys, the same outputs.
`cd cache && jk build` reads nothing above `cache/`. So the root holds only `[workspaces]` and root
profiles; any other key (`java`, `[repositories]`, a dependency) is refused with where it belongs.
Settings a member needs are written in the member.

The one place the root shows is the release tag, below.

## Members

| Key | Meaning |
|-----|---------|
| `members` | Directories below the root, each holding a `jk.toml` (or a `pom.xml`). A glob matches one directory level per `*`, as in `[workspace] modules`; `**` is refused. Required |
| `exclude` | Members the root's verbs skip by default: `{ path, reason }`, `path` a member or a glob over members. The reason is required; JumpKick prints it whenever it skips the member |

An excluded member is still a member: `-m` selects it, a profile adds it back, a selected member
that depends on it pulls it in, and it releases like any other.

A member that is itself a group root is refused: list its members in the outer root instead. A
`[workspace]` inside a workspace's module stays refused as it always was.

## Running verbs at the root

`jk build`, `compile`, `test`, `native`, `image`, `sync`, `train`, `install`, `guard`, `format`,
`explain`, `clean` and `lock` run at the root over the selected members, one after another, in the
order the `path` dependencies between them give (a member is built after every member it names with
`path = "…"`), against one engine. Each member's run is the run `cd <member> && jk <verb>` makes:
a line names the member, then its run prints what it prints alone, its own result line included;
one line totals them:

```text
○ skip examples/android-app: downloads the Android SDK (jk build -p all includes it)
▶ build cache
✓ build acme-cache · up to date · 41ms
▶ build insights
✓ build acme-insights · Built 2 modules · 3.2s
✓ build platform · 2 workspaces, 1 up to date · 3.4s
```

An unchanged member settles from the cache in milliseconds, so a second run at the root does
nothing and says `· up to date` once per member and once in the total. A member that fails leaves
the members that depend on it unrun; the others still run, and the total names both.

| Flag at the root | Means |
|------------------|-------|
| `-m <member>` | Run these members (a path such as `examples/cli`, or the artifact name in its `jk.toml`) and the members they depend on. Comma-separated or repeated |
| `-p <profile>` | A profile the root declares picks the members: its `[profiles.<name>.workspaces] include` adds excluded members back. Any other profile name reaches every member as written, and a member that does not declare it refuses it as it would alone |

Every other flag reaches each member unchanged. `-O jsonl` prints each member's rows, then one
`group-finish` row ([Machine output](machine-output.md)). `jk explain` forecasts each member in
turn.

`jk release` and `jk publish` are refused at the root: a release belongs to one workspace. Run
them in the member: `jk -C cache release`. Any other command at the root (`jk tree`, `jk add`, …)
says it is not a project and names a member to run it in.

## Release tags

A standalone workspace tags `v<version>`. A workspace a group root lists tags
`<artifact>-v<version>`, `<artifact>` being the `name` in the workspace's own `jk.toml`, never its
directory: `jk -C cache release` at `name = "acme-cache"`, `version = "1.2.0"` tags
`acme-cache-v1.2.0`. The same rule decides when a build is the release ([Releasing](release.md)):
a clean checkout whose HEAD carries `acme-cache-v1.2.0` builds the release of `acme-cache`, and its
siblings' builds stay snapshots. `[release] tag` in the member overrides both.

## Creating and checking members

`jk new <dir>` below a group root creates a standalone project and lists it in `members`: on a
terminal it asks first (yes by default), without one it adds it, and `--no-module` leaves it out.

`jk doctor` at the root reports a `members` entry whose directory holds no project (a root verb
refuses to start until it is fixed) and a project below the root that no entry lists.

An `AGENTS.md` at the root (`jk agents`) describes the group instead of a project.

## Agents

The MCP `run` tool with `dir` at the root runs the job in each selected member in turn (`modules`
names members) and answers each member's verdict and a total; `wait=false`, `kind=publish` and
`kind=release` take a member's `dir`. Other tools work in one member.

## Related

[Workspaces](workspaces.md) · [Projects](projects.md) · [Releasing](release.md) · [Doctor](troubleshooting.md)

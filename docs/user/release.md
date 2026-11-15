# Releasing

Who runs which verb, and how a release travels from a laptop to a repository.

| Verb | Who runs it | What it does |
|------|-------------|--------------|
| `jk install` | the developer, every edit | installs `<version>-SNAPSHOT` into `~/.m2` for sibling projects, and an application or tool on this machine; offline |
| `jk release` | the developer, once per version | builds, tests, installs and tags the declared version, then starts the next one |
| `jk publish` | CI on the tag, or `jk release --publish` | uploads the release to the `[publish]` repository; in git, only from a clean checkout of the tag |

`jk.toml` always holds the **next release**. A snapshot is a build-time suffix, never written down.

**The tree decides which a build is.** In a git checkout, every build verb (`jk build`, `jk test`,
`jk run`, `jk install`, `jk explain`) builds the release of the version `jk.toml` declares exactly
when the working tree is clean and HEAD carries the tag `jk release` writes for that version
(`[release] tag`, `v1.4.7` by default; annotated or lightweight); anything else is a snapshot. At
`version = "1.4.7"`:

| The tree | A build writes | Stamped |
|----------|----------------|---------|
| any commit but the tag | `target/app-1.4.7-SNAPSHOT.jar` | `1.4.7-SNAPSHOT` |
| a clean checkout of `v1.4.7` | `target/app-1.4.7.jar` | `1.4.7` |
| `v1.4.7` with uncommitted changes | `target/app-1.4.7-SNAPSHOT.jar` | `1.4.7-SNAPSHOT` |
| no git at all (a source archive) | `target/app-1.4.7-SNAPSHOT.jar` | `1.4.7-SNAPSHOT` |

The sources jar, the javadoc jar, the fat jar and the POM (its own version and its siblings') always
carry the main jar's version. A release build says so under its result
(`✓ Release 1.4.7 (tag v1.4.7)`), and so does `jk explain`; a tagged tree with changes prints one
note that it built a snapshot because the tree differs from the tag. Snapshot builds name and stamp
one tree whichever verb runs, so the next of them rebuilds nothing; a release build and a snapshot
build never reuse each other's jars. No flag or environment variable changes this: to build a
release on another machine, check out its tag.

`jk release` builds and installs the commit it is about to tag as the release, then tags it.
`jk publish` uploads only a release: in a git checkout, only a clean checkout of the tag, and on any
other tree it refuses and says why (no tag at HEAD, the tag of another version, uncommitted changes).

**Outside git** (a source archive), `jk release` and `jk publish` are releases by themselves: `jk
release` runs the tests, builds and installs the plain version and moves `jk.toml` to the next one by
editing it, with nothing to tag, commit or push (`--push` is refused); `jk release --undo` cannot
tell which release to take back there, and says what to undo by hand. Every other verb builds a
snapshot.

An installed release hides its snapshot: once a release build has installed `1.4.7` into a
repository, installing `1.4.7-SNAPSHOT` there is refused, since resolution would always prefer the
release. The branch has moved on to the next version by then, so its snapshots install as they did;
at a tag never pushed, `jk release --undo` takes the release back, as the refusal says.

## Tag-triggered CI (recommended)

The developer cuts and pushes the release; the tag starts CI, which runs the tests once. Only a green
CI run on the tag starts the build and the upload, and that stage re-runs none of the tests. A push
to a branch or a pull request starts no run.

A repository can also cut the release from its Actions tab: a workflow that runs
`jk release --patch --skip-tests --push` (JumpKick's own is `cut-release.yml`) tags the version, commits
the next one and pushes both; the tests are CI's. A push made with the job's `GITHUB_TOKEN` starts no workflow, so the job
ends by starting the tag's workflow itself (`gh workflow run ci.yml --ref "$tag"`).

```bash
jk release --push           # build + test, tag v1.4.7, commit "Start 1.4.8", push both
```

```yaml
# .github/workflows/publish.yml
name: publish
on:
  push:
    tags: ["v*"]
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: jk publish --tag "$GITHUB_REF_NAME"
        env:
          JK_REPO_RELEASES_USERNAME: ${{ secrets.NEXUS_USER }}
          JK_REPO_RELEASES_PASSWORD: ${{ secrets.NEXUS_PASSWORD }}
          JK_REPO_RELEASES_HOST: nexus.example.com
          JK_GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
```

`jk.toml` names the target, so the job has no flags beyond `--tag`:

```toml
[repositories.releases]
url = "https://nexus.example.com/repository/maven-releases/"

[publish]
repository = "releases"
sign = true
key-file = ".github/release.asc"     # decrypted into the job by an earlier step
```

`--tag` stops the job before anything compiles unless the pushed tag is the one `[release] tag`
(default `v{version}`) names for the version in `jk.toml`. A push made with a job's default token
does not trigger other workflows; push the tag from a developer machine or with a personal token.

## Files that carry the version

Some files name the declared version besides `jk.toml`: an installer's floor, a README's pin. List
them under `[release]` and `jk release` moves them with the version, in the commit that starts the
next one:

```toml
[release]
files = [{ path = "install.sh", pattern = 'RELEASE_FLOOR="([^"]+)"' }]
```

The pattern's one group is the version. A pattern that matches nothing stops the release after the
tag, naming the file.

## One command, no CI

```bash
jk release --publish --push
```

publishes between the build and the tag, so a failed upload leaves no tag. Credentials come from
`jk repo login <name>` on the machine ([Repositories: credentials](repositories.md#credentials)).

## Credentials and keys

| Where | Repository credential | Signing |
|-------|-----------------------|---------|
| laptop | `jk repo login <name>` | `key-file` in `[publish]`; passphrase in `JK_GPG_PASSPHRASE` |
| CI | `JK_REPO_<NAME>_USERNAME`, `_PASSWORD` (or `_TOKEN`) and `_HOST` | the key from a secret written to `key-file`; passphrase in `JK_GPG_PASSPHRASE` |

Secrets never go in `jk.toml`; `[publish]` refuses a `password`, `token` or `passphrase` key.

## Untested here

The GitHub Actions snippet is not run by JumpKick's own tests; the verbs it calls are
(`jk publish --tag` and `jk release --publish --push` against a local repository and a bare git
remote).

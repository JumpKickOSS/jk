# Releasing

Who runs which verb, and how a release travels from a laptop to a repository.

| Verb | Who runs it | What it does |
|------|-------------|--------------|
| `jk snapshot` | the developer, every edit | installs `<version>-SNAPSHOT` into `~/.m2` for sibling projects; offline |
| `jk release` | the developer, once per version | builds, tests, installs and tags the declared version, then starts the next one |
| `jk publish` | CI, or `jk release --publish` | uploads the release to the `[publish]` repository |
| `jk install` | an application's owner | puts an application or tool on this machine |

`jk.toml` always holds the **next release**. A snapshot is a build-time suffix, never written down.

## Tag-triggered CI (recommended)

The developer cuts and pushes the release; the tag starts the build and the upload. CI builds a
release tag and nothing else: a push to a branch or a pull request starts no run.

A repository can also cut the release from its Actions tab: a workflow that runs
`jk release --patch --push` (jk's own is `cut-release.yml`) tests and tags the version, commits the
next one and pushes both. A push made with the job's `GITHUB_TOKEN` starts no workflow, so the job
ends by starting the tag's workflow itself (`gh workflow run release.yml --ref "$tag" -f publish=true`).

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

The GitHub Actions snippet is not run by jk's own tests; the verbs it calls are
(`jk publish --tag` and `jk release --publish --push` against a local repository and a bare git
remote).

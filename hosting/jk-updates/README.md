# jk-updates

The data a running JumpKick engine keeps current between releases, and the signed manifest that
names it. Every engine asks `https://jumpkick.build/updates.json` (this repository's `main` when
that does not answer, or the `[mirrors] updates` directory a user configures) every few hours, and
fetches only what changed. How the engine reads it: [Updates](https://github.com/JumpKickOSS/jk/blob/main/docs/user/config.md#updates).

| File | What it is | Who writes it |
|------|------------|---------------|
| `prices.toml` | Model API list prices, USD per million tokens | A person, by pull request |
| `rates.json` | The tokens one code-graph call saves each agent, per agent, model and effort level, as the agent benchmark measured them | A person, by pull request, from `bench/scoreboard --render` output |
| `libraries.toml` | Copy of the library registry ([jk-libraries](https://github.com/JumpKickOSS/jk-libraries)) | `scripts/refresh.sh` |
| `jdks.json` | Copy of the JetBrains JDK feed | `scripts/refresh.sh` |
| `pins.json` | The jk-templates `main` revision, and the current stable JumpKick release with the SHA-256 of its signed `SHA256SUMS` | `scripts/refresh.sh` |
| `updates.json` | The manifest: when it was published, and each file's version and SHA-256 | `scripts/assemble.sh`, in the workflow |
| `updates.json.sig` | The release key's signature over `updates.json` | `scripts/assemble.sh`, in the workflow |
| `release-key.spki` | The public half of the release key, as the installers carry it | Changes only with the release key |

The engine trusts `updates.json` only when the signature verifies with the release key, and each
file only when its bytes match the SHA-256 the manifest gives.

## Changing the price table

Edit `prices.toml` and move its `published` date-time forward: an engine keeps a table only when
it was published after the one it has, so the publish refuses a changed table whose date did not
move. Merge to `main`; the workflow signs and publishes it. The shape (the comment at the top of
the file says what each rate is):

```toml
published = 2026-11-22T18:00:00Z

[models."<model id>"]
vendor = "<vendor>"
input = 0.0            # USD per million input tokens
cached_input = 0.0     # a cache read
cache_write = 0.0      # the 5-minute cache write
cache_write_1h = 0.0   # the 1-hour cache write
output = 0.0
source = "<the vendor's pricing page>"
retrieved = "2026-11-22"
```

## Changing the savings rates

`bench/scoreboard --render` in a JumpKick checkout writes
`server/engine/src/main/resources/cc/jumpkick/engine/savings/rates.json` from the agent benchmark's
banked rows, with a `published` instant it moves forward whenever a rate changes. Copy that file
here as `rates.json` and open a pull request. An engine keeps a file only when it was published
after the rates it has (the ones built into its JumpKick included), so the publish refuses a
changed file whose `published` did not move. Once merged, every engine credits code answers and
refactorings at the new rates after its next check, sessions it recorded before included. The
shape:

```json
{
  "published": "2026-11-22T18:00:00Z",
  "measured": "2026-11-20",
  "rates": [
    {
      "agent": "claude-code",
      "model": "claude-sonnet-5-5",
      "effort": "medium",
      "measured": "2026-11-20",
      "cacheWrite1h": true,
      "question": {"tasks": 8, "graphCalls": 10, "input": 11, "output": 3666, "cacheRead": 179026,
                   "cacheWrite": 17174, "sizes": [{"loc": 3635, "input": 4, "...": 0}]},
      "rename": {"...": 0}
    }
  ]
}
```

One row per agent, model and effort level the benchmark ran; `question` and `rename` are the tokens
one graph call saved in the code-question and rename tasks, over every task and per repository
size (`sizes`, with the repository's lines of code).

## How it publishes

`.github/workflows/publish.yml`, on a push to `main`, every three hours, and by hand:

1. `scripts/refresh.sh` (scheduled and manual runs) refreshes the copies of other sources. The
   release pin is taken from the signed `releases/latest/LATEST` pointer on jumpkick.build and
   checked against the signature beside that release's `SHA256SUMS`.
2. `scripts/assemble.sh` writes `updates.json` and signs it with the release key. It refuses a
   price table or rates file whose `published` did not move forward, a manifest older than the one before it,
   and any key but the one in `release-key.spki`. When nothing changed it signs nothing.
3. The signed manifest is committed to `main`, which is the GitHub copy engines fall back to.
4. The listed files, then the signature, then the manifest are uploaded to
   `gs://<bucket>/updates/`, which jumpkick.build redirects `/updates.json` and the files beside it
   to.
5. `scripts/verify-served.sh` checks that jumpkick.build serves the manifest just signed, that it
   verifies, and that every file it lists matches.

`scripts/verify-served.sh https://jumpkick.build/updates.json` checks the live copy by hand.

## Adding a kind of data

A new file needs the engine to read it (an applier in JumpKick's `UpdateCheck`), an entry in
`scripts/assemble.sh`, and a redirect for its name in JumpKick's `firebase.json`.

## Where this comes from

This repository's workflow, scripts and README are kept in the JumpKick repository under
`hosting/jk-updates/`, where `scripts/test-jk-updates.sh` tests them; change them there and copy
them here. The data files live only here.

### Creating the repository

1. Create `JumpKickOSS/jk-updates` (public), with `main` as its default branch.
2. Copy `hosting/jk-updates/` from a JumpKick checkout into it, with `.github/`.
3. Copy the price table built into JumpKick as the first `prices.toml`:
   `shared/client-io/src/main/resources/cc/jumpkick/prices/prices.toml`, and its savings rates as
   the first `rates.json`: `server/engine/src/main/resources/cc/jumpkick/engine/savings/rates.json`.
4. Create the environment `publish` (deployment branches: `main`; no required reviewer) with the
   secrets `JK_RELEASE_RSA_SIGNING_KEY`, `JK_RELEASE_GCS_BUCKET` and `JK_RELEASE_GCS_SA_JSON`, the
   same values as the JumpKick repository's `release` environment (or make them organization
   secrets shared by both).
5. Let the workflow push to `main`: Settings, Actions, General, Workflow permissions "Read and
   write", and if `main` is protected, allow GitHub Actions to bypass the rule.
6. Push, then run the workflow by hand once (Actions, Publish, Run workflow): it refreshes the
   copies, signs, commits and uploads everything.
7. Deploy JumpKick's `firebase.json`, whose redirects send `/updates.json` and the files beside it
   to the bucket (the next release deploys it, or `firebase deploy --only hosting`).

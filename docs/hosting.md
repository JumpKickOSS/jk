# Hosting & CDN (Firebase + GCS)

How `jumpkick.build` is wired (JK-1066 ops).

## Production status

**`https://jumpkick.build` is live** (Firebase Hosting custom domain: ownership + host active, TLS issued).

- Site / install: `https://jumpkick.build/`, `https://jumpkick.build/install.sh`
- Releases redirect: `https://jumpkick.build/releases/…` → GCS
- Maven repo redirect: `https://jumpkick.build/repo/…` → GCS


## Architecture

| Layer | What |
|-------|------|
| **Firebase Hosting** | Static site + `install.sh` + Firebase edge CDN for those files |
| **GCS** `gs://jumpkick` | Release binaries (`releases/<ver>/…`) |
| **Hosting redirects** | `/releases/**` → `https://storage.googleapis.com/jumpkick/releases/**` |
| **Blaze** | Project `jkbuild` is on GCP billing (required for Hosting custom domains / future Cloud products) |

Firebase Hosting does **not** store multi‑MB native archives; it redirects to GCS. Google’s multi‑region
storage edge still serves the blobs. A dedicated Cloud CDN / load balancer on a custom path is
optional later if you want zero redirect hop.

## Live now (before custom DNS)

| URL | Purpose |
|-----|---------|
| https://jkbuild.web.app | Site |
| https://jkbuild.web.app/install.sh | Installer script |
| https://jkbuild.web.app/releases/latest/VERSION | Redirect → GCS releases |
| https://jkbuild.web.app/repo/… | Redirect → official Maven repo |
| https://storage.googleapis.com/jumpkick/releases/ | Direct GCS releases |
| https://storage.googleapis.com/jumpkick/repo/ | Direct GCS Maven repo |

## DNS for `jumpkick.build` (you apply at the registrar)

Firebase expects **exactly** these (current API `requiredDnsUpdates`):

| Action | Type | Host | Value |
|--------|------|------|--------|
| **REMOVE** | A | `@` / `jumpkick.build` | `162.255.119.58` (current parking) |
| **ADD** | A | `@` / `jumpkick.build` | **`199.36.158.100`** |
| **ADD** | TXT | `@` / `jumpkick.build` | **`hosting-site=jkbuild`** |
| **KEEP** | TXT | `@` | Existing SPF: `v=spf1 include:spf.efwd.registrar-servers.com ~all` (second TXT is fine) |

Optional apex www: add later via console or API (`www.jumpkick.build`).

After DNS propagates, Firebase issues TLS (may briefly need ACME records — console will show if stuck).

Check status:

```bash
TOKEN=$(gcloud auth print-access-token)
curl -sS -H "Authorization: Bearer $TOKEN" -H "x-goog-user-project: jkbuild" \
  "https://firebasehosting.googleapis.com/v1beta1/projects/jkbuild/sites/jkbuild/customDomains/jumpkick.build" \
  | python3 -m json.tool
```

Or: [Firebase console → Hosting](https://console.firebase.google.com/project/jkbuild/hosting).

## Deploy site updates

```bash
# from repo root
firebase deploy --only hosting --project jkbuild
```

Sources: `hosting/public/`, `firebase.json`, `.firebaserc`.

### Brand assets (favicon, logo)

Canonical art lives with the engine Web UI:

`clients/web/src/main/resources/web/` (`jk-logo.svg`, `jumpkick-logo.webp`).

Hosting keeps **committed copies** under `hosting/public/` so deploy needs no build step.
After changing logos in the web client, re-sync and commit both trees:

```bash
scripts/sync-hosting-brand.sh          # copy into hosting/public/
scripts/sync-hosting-brand.sh --check  # fail if copies drift
```

The landing page reuses a **token subset** of the dashboard CSS (Jk Dark / JetBrains Mono,
neon cyan accents) — not the full `style.css` app sheet.

## Install after DNS is live

```bash
curl -fsSL https://jumpkick.build/install.sh | bash
# releases resolve via Hosting → GCS redirects; default JK_RELEASES_URL in install.sh is
# https://jumpkick.build/releases
```

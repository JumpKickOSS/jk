# Hosting & CDN (Firebase + GCS)

How `jumpkick.build` is wired (JK-1066 ops).

## Architecture

| Layer | What |
|-------|------|
| **Firebase Hosting** | Static site + `install.sh` + Firebase edge CDN for those files |
| **GCS** `gs://jkbuild-releases` | Release binaries (`releases/<ver>/…`) |
| **Hosting redirects** | `/releases/**` → `https://storage.googleapis.com/jkbuild-releases/releases/**` |
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
| https://storage.googleapis.com/jkbuild-releases/releases/ | Direct GCS releases |
| https://storage.googleapis.com/jkbuild-releases/repo/ | Direct GCS Maven repo |

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

## Install after DNS is live

```bash
curl -fsSL https://jumpkick.build/install.sh | bash
# releases resolve via Hosting → GCS redirects; default JK_RELEASES_URL in install.sh is
# https://jumpkick.build/releases
```

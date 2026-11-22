# The engine's embedded HTTP server

The engine serves a dashboard (the SPA in `clients/web`) and an MCP surface over an embedded HTTP
server. This file is cited from `api.js`, `EngineStatusCommand`, `EngineRotateTokenCommand` and the
engine tests; it had never been written, which is how the guarantee below came to be relied on by
client code without being recorded anywhere.

## On by default

`[http]` is **enabled by default** — loopback bind (`127.0.0.1`), **token-gated `/api/*`** (static shell
open). It is not opt-in. Turn it off with `[http] enabled = false` in `~/.jk/config.toml` or
`JK_HTTP_ENABLED=false`; a malformed config yields empty and fails closed (no server).

JumpKick-owned listener JVMs carry `-Djava.net.preferIPv4Stack=true` on their spawn lines (engine,
workers). Test JVMs do not: they open no listener of JumpKick's, and keep both stacks as under Surefire and
Gradle, so a test calling its server at a name that resolves to IPv6 alone (Micronaut's `HOSTNAME`)
connects. Without that, Linux HotSpot often publishes loopback as
`[::ffff:127.0.0.1]`, which WSL2 localhost forwarding does not relay to Windows
`http://127.0.0.1:…`. Launch-flag only — a runtime `System.setProperty` leaves the JDK's loopback
selection incoherent (see `PreferIpv4`), which is how the thin client once refused its own engine.

`[mcp] enabled = false` disables only the MCP surface, never the server. Machine-scoped, not
project-overridable, read once at engine start.

That default matters for reasoning about engine lifetime: since most engines have HTTP on, any rule
of the form "engines with HTTP behave differently" applies to nearly all of them.

## Which port, and which engine

A port named in `[http] port` or `JK_HTTP_PORT` is bound as named. Otherwise the engine's role picks
it: the host engine binds **8910**; a **guest** (a WSL distro, whose `127.0.0.1` binds Windows forwards
onto its own loopback, or a container) binds a sticky port from 8911 to 8999. The guest's first choice
comes from its identity; the port it binds is kept in `<state>/engine/http-port` and tried first on
every start. When another process holds it after the bind's retries, the guest takes the next free
port in the band and keeps that one. A guest never takes 8910 because it is free.

8910 is the person's own home's (`JkDirs.isAccountHome`: `.jk` under the home the OS records for the
account, the JVM's startup `user.home`, however reached, or on Windows a `JK_HOME` persisted as a User
variable). `$HOME` does not decide it: a scratch or gate home run as `HOME=/x JK_HOME=/x/.jk` is the
default home by `$HOME`'s rule, and it is the usual culprit. An engine of any other home whose port
would be 8910, by default or named, takes the band the same way, sticky in its own `http-port` file
(`EngineHttpFront.start`), and logs `port 8910 is the default home's; serving on a band port`. Its
clients never assumed 8910: `jk web`, `jk mcp` and `jk engine status` read the URL from the home's own
socket. The front door's discovery leaves out a band port answering with role `host`, since that is
such an engine and no guest to pair with. When the own home's engine cannot bind 8910 after the
retries, it reads `/api/identity` there (`PortHolder`). A JumpKick engine answering names its `jkHome`, which
becomes the `httpError` `jk engine status` prints on the Web UI line, and nothing is served: stopping
that engine is the cure. When nothing answers as an engine, no `jk engine stop` frees the port (a
Windows host with WSL's mirrored networking can refuse a port a WSL process held, with no holder
visible), so the engine takes a band port as a guest does, sticky in its `http-port` file, and keeps
"port 8910 is held by another program" as its `httpError` while it serves; `jk engine status` prints
it beside the URL. It does not move back to 8910 while it runs; the next engine tries 8910 first.
Taking a band port
was chosen over stepping aside on request: step-aside is authorized with a pairing token, which an
engine of another home does not have, and an engine that moved while serving would leave its own
clients holding a dead URL.

`Guest` (`cc.jumpkick.host`) detects a guest from the kernel and the filesystem: WSL by `microsoft` in
`/proc/sys/kernel/osrelease` (with `WSLInterop` saying whether Windows programs can run), a container
by `/.dockerenv`, `/run/.containerenv` or `container=`. It never reads `WSL_*` variables for the
answer, since a daemon or a service often lacks them. Its `instance` tells one guest from another of
its kind on the machine: for WSL, `/etc/machine-id` (else `/var/lib/dbus/machine-id`) and the `wwid` of
the virtual disk the root is mounted from (`/proc/self/mountinfo`'s device, then
`/sys/dev/block/<maj:min>/device/wwid`), so two installs of one distro image differ, and so does a
distro cloned by export and import; `WSL_DISTRO_NAME` only when neither reads. For a container, the id
in the host path its `/etc/hostname` is bound from.

Each engine has an identity (`EngineIdentity`): OS, guest kind and name, hostname, `JK_HOME`, version
and port, with an `id` of 12 hex digits hashed from OS, guest kind and instance, hostname and
`JK_HOME`. Every WSL distro of one Windows host shares its hostname and usually its `JK_HOME` path, so
the instance is what tells them apart; the id is stable across restarts and reboots, and it seeds a
guest's first band port.
`GET /api/identity` answers it **without a token**: it names nothing secret, and a tab whose token an
engine refuses uses it to tell a rotated token from another engine on the same address. The same
identity is in `GET /api/status` and the socket status as `identity`, and `jk engine status` prints
it as `Engine`. The dashboard latches the `id` per tab, as it latches `engineEpoch`; when the address
answers a different `id`, the tab freezes and names both engines instead of rendering the other
engine's builds.

### The front door

The front door relays notifications (`FrontDoorRelay`). On each heartbeat it holds one `GET
/api/events` stream open to every paired engine, with that engine's own token, and its `present`
control message carries `X-Jk-Front-Door-Relays: alerts`. A guest that heard that within three beats,
and does not hold 8910 itself, publishes a decision it would notify about as an `alert` event
(`Decisions.relay`) and records the channel `front-door` instead of raising it. The front door raises
it through its own `DecisionAlerts` with the id `<engine>-<decision>`, the question prefixed by the
engine's label and the link `/engines/<engine>/#decision/<id>?t=<front-door token>`, and withdraws it
on the engine's `decision` event for a settled state. An engine's `notice` frames that add a
change-log notice are added to the front door's bell with the text prefixed and the link under the
prefix; decision notices are not copied. The same `present` carries `X-Jk-Front-Door-Port`, the
loopback port the front door serves on; while it beats, a guest links its decisions (the MCP
`NEEDS-YOU` result, URL elicitation, `jk decide`, a notification it raises itself) to
`http://127.0.0.1:<port>/engines/<id>/#decision/<id>` rather than to its own port, and signs none of
them with its own token, which the front door does not take (`FrontDoorRole.frontDoorPort`).

Installing JumpKick into a WSL distro from a development build: a `-SNAPSHOT` Windows engine pins
the newest published release at or below its version, which predates the branch you are trying.
Start that engine with `JK_DEV_WSL_DIST` naming the absolute Windows folder of a Linux dist built
from the same tree (`jk build` in WSL writes `target/dist`; copy it to `C:\...`), and it installs
that dist instead. Only the engine's own environment is read, only a `-SNAPSHOT` engine honours it,
and a folder without `jk` and `lib/jk-engine-<version>.jar` is ignored; a request never names a dist.

A host engine is the machine's front door. `GET /api/engines` lists it and every engine that answers
`GET /api/identity` on a band port (8911 to 8999, all asked at once: on Windows a loopback port with
no listener takes about a second to refuse), each marked `paired` when this engine can proxy it.
One sweep of the band answers every list asked for within 2 s of its start, and callers that
arrive while it runs wait for it rather than sweep again, so an engine that starts or stops shows
within about 3 s; `paired` and this engine's own row are read fresh on every list. A dashboard
does not poll the list: it reads it on load and on reconnect, and again on each `engines` event (see
the heartbeat below).
`GET /api/engines` takes the token and no epoch header, like `/api/status`, since a proxied page
holds another engine's epoch.

A paired engine's dashboard, API, event stream, MCP endpoint and coverage reports are served under
`/engines/<id>/` (`FrontDoor`). The front door checks the browser's token in the shape the path
takes it here: `Authorization` for `/api` and `/mcp`, `?access_token=` for the event stream and the
MCP event-stream GET. A request that presented it reaches
`127.0.0.1:<port>`, never through a proxy, with the paired engine's token in that same shape, and the
browser's token in none: `Authorization`, `Cookie` and `Referer` are dropped, as is any
`access_token` in the query. A coverage report's path is forwarded as sent, with no token added: the
report token in it is one the engine handed out (through the proxied `GET /api/report-token`), and the
engine checks it. `Origin` is dropped too: the front door's own gate has checked it, and it
names the front door, not the engine. A page, an asset and `GET /api/identity` reach the engine with no token;
a page or a report takes `GET` and `HEAD` only, anything else is a 405 before the body is read. A
body is read under the cap the engine puts on that path (64 KiB, the file save's larger one for `PUT
/api/project/file`), and past it is a 413, before the engine is asked anything. A `Location` in the
engine's answer is resolved against the request sent and put back under `/engines/<id>/`; one that
leads off the engine's address or to a path the front door refuses, and any response header carrying
the engine's token, is a 502. A 401 from the
engine becomes a 502, so a stale pairing never makes the dashboard drop the front door's token. The
path is forwarded as the browser sent it, and one that could route otherwise at the engine (a dot or
empty segment, an escaped `/`, `\`, `?`, `#` or `%`, a control character) is a 404. The response
streams back as it comes, so an event stream flows through; a proxied stream draws on the
event-stream budget, not RPC admission. An engine that sends nothing for 30 s, before its answer or
within it, is asked for its identity (5 s to answer); one that does not answer with its id has
stopped, and the front door gives up on it (`UpstreamWatch`): a 502 before the answer started, a
dropped connection within it, so a cut-short answer never reads as a whole one. A busy engine that
still answers is waited on, as a direct client would wait. Before forwarding, the front door asks the pairing's port for
its identity and refuses (502) unless it answers with the pairing's id; the answer is trusted for
two seconds. `/engines/<id>` without the slash redirects to it, since the dashboard's asset links are
relative. The dashboard reads its prefix from its own path (`BASE` in `api.js`) and puts it on every
API path, the event stream and the coverage link.

Pairing (`GuestPairing`): a WSL guest with interop writes `<id>.toml` (`id`, `port`, `token`) to
`<state>/engine/guests/` of the Windows engine, after it binds, and removes it when it stops. It
finds that directory through `cmd.exe` (`%JK_STATE_DIR%`, else `%JK_HOME%\state`, else
`%USERPROFILE%\.jk\state`) and `wslpath`. The front door reads the directory; an engine on a band
port with no matching pairing is listed and not proxied, and a pairing is honoured only for a file
named for its id and a port on the band.

Who can pair or read a pairing: the directory is in the Windows user's profile, which Windows opens
to that user and administrators only. Every process of a WSL distro reaches `/mnt/c` as that same
Windows user, and could already read the Windows engine's own token there, so the pairing widens
nothing. A web page cannot write a file, and cannot reach `/api/engines` or a proxied path without
the token; the Host check (DNS rebinding) runs before the proxy as before any route. A planted
pairing leaks nothing, since its token goes only to a band port on `127.0.0.1` that answers with its
id.

### The front-door role

Holding 8910 is the role: the port is the lock, so two holders cannot share one loopback (WSL
distros share one VM network). The host engine binds 8910; a WSL guest takes it only while no front
door is heard from (`FrontDoorRole`, `FrontDoorDuty`).

- **Token.** One front-door token in `<state>/engine/front-door.token` (`EnginePaths.FRONT_DOOR_TOKEN`).
  The host engine's own HTTP token is that token: at start `FrontDoorToken.align` makes the two files
  agree, the front-door file winning, and a host with only its own token seeds it. A guest holding
  8910 serves it with the same token, read from the Windows engine's directory, so a tab and a
  bookmark keep working through a handover. `jk engine rotate-token` removes both files. Proxied
  requests still carry only the target engine's token.
- **Heartbeat.** The front door POSTs `/api/front-door/present` to every other paired engine every
  2 s, and `/api/front-door/leaving` when it stops; each with that engine's own token, read from its
  pairing, bearer only and exempt from the epoch header. Nothing on 8910 is needed for control.
  Each engine answers `present` with `{"watching": bool}`: whether a dashboard has its event stream
  open. While one is open there or on the front door itself, each heartbeat also compares the
  engines `GET /api/engines` lists (id, label, port, version, role, pairing) with the last
  heartbeat's, and on a change publishes the `engines` event to its own dashboards and POSTs
  `/api/front-door/engines` to every paired engine, which publishes it to theirs. With no dashboard
  open anywhere the band is not swept for it.
- **Takeover.** A guest that hears nothing for its delay binds 8910 in the VM through a second,
  front-door-only listener (its band port stays; `/` redirects to `/engines/<own id>/`). The delay is
  5 s, then 15 s, 60 s and at most 5 min after each takeover, back to 5 s after 10 min without one,
  plus 2 s for each paired guest with a lower id; `leaving` skips the 5 s.
- **Check from Windows.** A guest's bind succeeds in the VM even when Windows' 8910 is taken, so it
  checks with `powershell.exe -NoProfile -NonInteractive` (a `TcpClient` GET of `/api/identity` on
  Windows' `127.0.0.1:8910`, never `curl.exe`) that the answer echoes a nonce it just set. Echoed: it
  keeps the role. Refused, or another answer: it lets go and logs `front door unreachable from
  Windows`. No interop, PowerShell missing or blocked, a timeout: it keeps the role and logs once.
- **Reclaim.** A host that finds 8910 taken reads the identity there and, for a paired guest, POSTs
  `/api/front-door/step-aside` to the guest's band port; the guest closes its 8910 listener and the
  host's bind retries for 5 s (wslrelay lets go about a second later).

### A WSL engine starts the Windows engine

Before it pairs, a WSL guest with interop makes sure the Windows engine runs (`WindowsHostEngine`):
the Windows JumpKick home through `cmd.exe` (`%JK_HOME%`, else `%USERPROFILE%\.jk`), `bin\jk.exe`
through `wslpath`, then that Windows client's `jk engine status -O json`, and its `jk engine start` when it is stopped,
never `java.exe`. A start is a change-log entry (`JumpKick for Windows` / `start`), which the change
notices raise; never a question. `[wsl] host-engine = auto | off`; unset is auto except with
`JK_HOME` set or under `CI`, so JumpKick's own suites in WSL never start the owner's Windows engine.
Identity carries `frontDoor`, the protocol (`FrontDoor.PROTOCOL`, 1); after the start the guest
reads Windows' 8910 identity through the PowerShell probe, and a host speaking another protocol
(or none) is not paired with: the guest unpairs, keeps its band-port dashboard and logs which side
to update. `JK_FRONT_DOOR_PROTOCOL` in the environment an engine starts in stands in another protocol, to try a mismatch.

Every Windows program the engine starts from WSL (`cmd.exe`, `jk.exe`, PowerShell
for the check from Windows and for toasts) goes through `/run/WSL/1_interop`, the
distro's own interop server (`WslInterop`). The socket of the `wsl.exe` session that started the
engine stops starting Windows programs when that session ends, and a start through it never
returns.

## The engine never idles out

**A resident engine does not self-terminate for being idle.** There is no idle timeout, and adding
one would be a breaking change to the dashboard's contract, not a tuning knob.

The SPA is written against this. `api.js`:

> `EventSource` reconnects on its own; an HTTP-enabled engine never idles out, so `'offline'` only
> ever means an explicit stop, an upgrade respawn, or a crash.

So the client treats a lost stream as an anomaly. If engines idled out, `offline` would become
routine while the UI still reported it as exceptional.

The reason is an asymmetry between clients, and it is the whole argument:

- The **CLI** ensures a live, version-matched engine before every request, spawning one if none is
  listening. An engine that exited costs a cold start, not an error.
- A **browser tab** cannot spawn anything. If the engine it was talking to is gone, the SPA is dead
  until the user knows to drop to a terminal and run a `jk` command — which is an astonishing thing to
  ask of someone whose primary interface is the dashboard.

Concretely: a developer working through the Web UI who leaves the tab open overnight must find it
working the next morning.

## When an engine *does* exit

Three states, decided by whether this process is still the named primary (endpoint pointer **and**
pid file). The generation filename alone is not identity: deleting and recreating the state directory
can leave a ghost engine whose socket *name* matches the successor. `EngineElection.state` is the one
reading of them (`EngineState`); the exit watchdog, the session write gate and `stop-sessions` all
act on it.

| Pointer / pid | State | Behaviour |
|---|---|---|
| names this engine (pid matches) | **primary** | Never self-terminates. Exits on `jk engine stop` or version-skew replacement. Writes its sessions; refuses `stop-sessions`. |
| names another process, or pid file / hello pid differs | **displaced** | Yields HTTP **immediately**, drains in-flight jobs on its own generation socket, reports `drain-status` to the successor, exits when idle or at the drain deadline. Writes no session record; honours `stop-sessions`. |
| absent | **orphaned** | Exits once genuinely unused: no in-flight jobs **and** no attached SSE stream. Writes its sessions; refuses `stop-sessions`, since no successor will adopt them. |

The rule for sessions is one line: **only a displaced engine stops writing them**.

"No jobs in flight" means every record written. A job, attached or detached (MCP, `POST /api/build`),
writes its journal record, `jk-results.md` and session fold while it still holds its plan slot, and
releases the slot only then, before its `request-finish`. So an engine that drains to idle and exits
(displaced, `jk engine stop`) has written the record of every job it ran. Only the drain deadline
can cut one short: it cancels the jobs still running, and one whose record is not written by the
end of the grace goes with the engine.

### Displacement surrenders the HTTP port unconditionally

A newer engine taking over needs the fixed HTTP port. A draining engine — displaced, or told to stop
with jobs in flight — closes HTTP right away (attached dashboard streams get **no vote**). Its engine
socket is per generation (`<key>.gen<N>.sock`, or a loopback TCP port on Windows), so nothing contends
for it: the draining engine keeps it open as a lame duck, answering hello and status (`draining`,
`drainDeadline`, its jobs) and cancel, and refusing new jobs with `shutting-down`. A job client that
meets the refusal or a draining hello starts a successor, which takes over the endpoint; the
predecessor sends `drain-status` / `drain-done` to it. The predecessor exits when its last job
finishes, or at `[engine] drain-deadline-ms`, when it cancels its jobs, kills their workers and
exits. The HTTP token is deliberately preserved across the respawn so the tab does not have to
re-authenticate. Client-side reconnect is the SPA's job.

Every generation shares the endpoint pointer and the bound-URL file (`<key>.http`), and a successor
writes its URL only after it has taken the endpoint. An exiting engine deletes the two only while
the endpoint still names it, read under the startup lock the successor writes the endpoint under; while
another engine holds that lock, it leaves both to that engine. A predecessor that drains past its
successor's startup therefore leaves the successor's files alone.

### Sessions cross the hand-off

Only the primary engine writes session records, and the hand-off between two engines is a
handshake. Once the successor has taken the endpoint and asked its predecessor to yield, it sends
`stop-sessions` on the predecessor's generation socket, the one the drain uses. The predecessor closes
its write gate (`SessionLog.stopWriting`), which returns once any session write it had in flight has
landed, and answers `sessions-stopped`. A primary or orphaned engine refuses it with an `error`
naming its state and keeps writing ([the states](#when-an-engine-does-exit)), so a stray request
cannot silence the engine that owns the sessions. Only then does the successor read the stored sessions and
adopt them (`SessionLog.adopt`, which every session entry point runs first), so it sees the
predecessor's last write and never writes over it. A predecessor that does not answer within two
seconds (dead, wedged, or an engine that predates the message) is treated as stopped, and the
successor adopts without it; the predecessor still reads its state before every session write and
stops for good the moment it is displaced (`SessionLog.writeWhile`), so one that comes back late
writes nothing more. Each run a displaced engine finishes while it
drains goes out in its next `drain-status` instead, under `runs` (each the text of one JSON object,
the fields the session log folds), and the successor folds it into its episode as if it had
finished there. A drained engine sends whatever runs the ticks have not carried in one last
`drain-status`, and only then `drain-done`.

Decisions cross the hand-off the same way. `DecisionQueue` writes each change through its store
(`JournaledDecisions`, `<builds>/decisions/<id>.json`) before it takes effect, under the session
log's write gate (`SessionLog.whileWriting`), and loads the store once the session log has adopted
(`DecisionQueue.adopt`, which every queue entry point runs first). A record is the decision as `GET
/api/decisions` shows it, whether its operation has run (`spent`), and the call it continues (tool
and arguments, the goal masked). A displaced engine's store refuses every write, so it records no
answer and runs no operation; an answer or a run there is refused rather than lost.

The successor takes the sessions over rather than ending them: see [`GET /api/sessions`](#get-apisessions)
and [MCP sessions](#mcp-sessions-and-server-requests).

### An orphan waits for an attached tab

An orphaned engine is one no pointer names: no CLI will reach it again, and no successor wants its
port either. It exits when unused, but an attached SSE stream vetoes that. Unlike displacement there
is no successor to hand the tab to, so exiting under an open dashboard would strand it with nothing to
reconnect to.

The watchdog's look at the counts is only a hint: the exit reads the plan count again under the lock
a plan claims its slot under, so a job admitted after the look keeps the engine running.

The count comes from the SSE admission budgets rather than a separate counter, so it cannot drift from
what actually holds a slot: a stream keeps its permit for the life of the connection.

## Stopping engines

The engine identity is a hash of the state directory **and** the artifact store, so a machine can
hold several at once — one per `(state dir, store)` pair, plus a draining predecessor that has
already yielded its HTTP port and still answers status on its own generation socket. A unique {@code JK_HOME} (every test sandbox home) gets its own UDS
and, unless the suite opts in, HTTP disabled or
bound on port {@code 0} so it cannot steal the host dashboard. `jk engine status` lists every
resident engine this user owns. `jk engine stop` addresses the one this directory resolves to,
`--all` stops **this home only** (nested tests must not kill the host engine running
`jk build`), and `--pid <pid>[,<pid>…]` stops engines by pid, of any home.

Every form of stop confirms the engine went and reports what actually happened; an engine that said
it was exiting and did not is hard-killed. A graceful stop never kills for silence alone: a drain
whose ack misses the watchdog is settled by the engine's status (jobs or a drain underway mean
draining), an engine silent through every probe is refused by pid, and a process holding engine
state with no listener is killed only when it shows no life (`SilentPeer`). `--now` is the override.
Reliably stopping an engine must never require the user to reach for `kill` or hunt through Task
Manager.

## Auth tiers

**Every `/api/*` call requires a valid bearer token**, including loopback binds, except `GET
/api/identity`, which names the engine and nothing secret. There is no
tokenless “watch-only” mode — a bare browser open without a token must not see live builds or
history. Static content stays open. By default the engine serves **only the dashboard shipped in its jar**
(`index.html`, JS, CSS, images, the manifest and the service worker), under the SPA's own CSP so it can
show the blocking authorization dialog. It reads nothing from disk: a build can write anywhere it can
reach, and a file the dashboard page loads as a script runs in the dashboard's origin with the page's
bearer token in reach, whatever policy its response carries. So `~/.jk/state/web`, which builds can
write, is never read: an `app.js` there must not replace the dashboard's.

Serving from disk is opt-in, through a directory the user names:

| Setting | Meaning | Honoured |
|---|---|---|
| `JK_HTTP_WEB_ROOT`, `[http] web-root` | A dashboard source tree. The env var wins over the file. | Always: the user chose it |
| `[http] web-overlay` | The same, for developing the dashboard. Config file only, no env spelling. | Only when the running build's version ends in `-SNAPSHOT` (any case) |
| neither | The jar alone | The default |

A file in the directory replaces the shipped file of the same path, under the shell's policy so the
dashboard runs from it, the service worker included; a path the directory lacks falls through to the
jar. `web-root` beats `web-overlay`: with both set the overlay is ignored.

The overlay's gate is the version stamped into the jar by the build (`JkVersion.VERSION`, what
`jk --version` prints), never an env var, a config key or a file under `~/.jk`, so nothing a build or a
sandboxed process can write turns it on in a release. A release's version is never a `-SNAPSHOT`:
`scripts/release-version.sh` refuses to release one. "Ends in" is the rule, so `1.2.3-SNAPSHOT-rc1` is
not a snapshot. A release engine that finds a `web-overlay` in its config logs `http: web-overlay
ignored: not a snapshot build`, and `jk doctor` warns on the config row; neither stops the engine.
A snapshot engine with an overlay logs its directory at start, marks every file it serves from it with
an `X-Jk-Web-Overlay: 1` response header, and reports `webOverlay: true` in `GET /api/status` beside
`webRoot`, the directory served ("" for the jar alone).

Both directories get the same checks: a request path with `..`, a backslash or a NUL is a 404, and a
file whose real path leaves the directory (a symlink) is not served.

Every static response, a 404 or 405 included, carries `X-Frame-Options: DENY`, and every file
carries the shell's `Content-Security-Policy` (`StaticContent.SHELL_POLICY`;
[webclient.md](webclient.md#dependencies-vendored-or-cdn-pinned-integrity-locked) says what each directive
allows), whose `frame-ancestors 'none'` keeps another site from framing the dashboard. Every response
the server sends, static, `/api`, event stream, MCP and coverage report alike, carries
`X-Content-Type-Options: nosniff`, set once as the request enters `HttpEngineServer.handle`.

Point either at a directory only you can write. It can replace scripts that run with the page's bearer
token, so a directory a build or a sandboxed process can write defeats the point of the default. A
snapshot is a development build: no deployed or shared environment should run one with an overlay set.

Classpath shell files are `Cache-Control: no-cache` plus a version+mtime `ETag`, so a new
engine jar is visible on the next load (revalidate always; unchanged bytes are a `304`). Disk
files (a named `web-root` or the overlay) are also `no-cache` (`Last-Modified`). `/api/*` is `no-store`. There is no
`max-age` on the shell: a still-fresh cached copy would hide a replacement engine, including
security patches, and `location.reload()` after an `engineEpoch` mismatch does not bust
subresources the browser still considers fresh. Non-loopback clients carry the token the same way; `EventSource`
cannot send headers, so SSE passes it as an `access_token` query parameter. The SPA bootstraps
from a `#t=` fragment. **`jk web`** starts the engine if needed, opens a browser (`$BROWSER` or
the platform default) on the tokenized URL, and prints it tokenized only at an interactive
terminal (`DashboardToken`); a link the engine answers with never carries the token
(`DashboardLinks`), and `jk web -O jsonl` prints it only with `--with-token`.

Missing or invalid credentials → **401** (plus SPA hard-gate). Engine generation mismatch →
**409** with `engine-epoch-mismatch` (see below).

A request with any method but `GET` and `HEAD`, on any path (`/api`, `/mcp`, the front door's
prefix), and any request to `/mcp` (here or through the front door), its event-stream `GET`
included, that carries an `Origin` other than its own is a **403** before its token is read
(`HostCheck.sameOrigin`): the origin must be `http`, the request's `Host` name and the listener's
port, so `null` (a sandboxed frame, a `file:` page), another port, another loopback spelling and
another machine's address are all refused. A token a page was handed does not let it build, write a
file, answer a decision or call MCP from its own origin. A request with no `Origin` (the CLI, an
IDE, an agent's MCP host) passes, since a browser sends one on every such request.

A `HEAD` of an `/api` read runs its `GET` route and sends the headers alone; `HEAD /api/events` is a
405. A read that resolves, plans, walks a tree or checks the host (`/api/project/graph`, `model`,
`outdated`, `why`, `update`, `find`, `/api/explain`, `/api/doctor`, the `/api/history` list) checks
its parameters, then answers a `HEAD` with 200 before it computes: a malformed request is still its
400, but a `HEAD` cannot see the 422 a `GET` would find.

### `GET /api/project`

`GET /api/project?project=<id>[&dir=<abs-path>]` (preferred) or `?dir=<abs-path>`.

Returns `{ projectId, dir, coord, description, checkouts }`. `project` is the durable identity —
the `project-id` the committed lock carries, so every git worktree of one repository shares it.
`checkouts` lists the id's **live** checkouts as `[{ dir, lastBuilt }]` (the directories
`identity.toml` records that still exist, sorted by path). With one live checkout the card is
that tree's and `dir` names it. With several, `dir=` selects one (real-path compared; a
directory the id does not record is a **404**); without a selector the response is only
`{ projectId, coord, checkouts }` — no `dir`, no guess — and the SPA lists the checkouts to pick
from. An id with no live checkout is a **404**. `dir=` alone remains for direct checkout ops.

### `GET /api/project/graph`

Dependency graph for the Project page, same idea as `jk tree`:

`GET /api/project/graph?dir=<path>&scopes=main,test&transitive=0|1`

| Query | Default | Meaning |
| --- | --- | --- |
| `dir` | required | Project or workspace root (checkout path) |
| `scopes` | `export,main,runtime` | Comma-separated canonical scopes (same default as `jk tree`). Percent-encoded like any query value; an unknown name is a **400** naming the valid set |
| `transitive` | `false` | When true, expand lockfile transitive deps under each declared root (same as `jk tree -t` / `--transitive`) |

Response:

`{ dir, workspace, scopes, transitive, truncated, availableScopes, nodes: [{ id, label, kind, version?, path? }], edges: [{ from, to, scope? }] }`

`kind` is `module` (workspace member — the workspace root itself is a `module` node at path `.`),
`declared` (listed in a selected-scope `jk.toml`), or `transitive` (lockfile-only). Nodes are keyed
by full package identity (`group:artifact:type:classifier`), so a test-jar or classifier variant is
its own node; the label shows `g:a` plus a classifier/type suffix when non-default. Edges are
dependent → prereq. Transitive expansion is capped (500 nodes / 2000 edges); `truncated: true`
means the graph is a prefix of the full closure — narrow the scopes or turn off `transitive`.

Errors: a missing root `jk.toml` (deleted or non-jk checkout) is a **200** with empty `nodes` —
genuinely "no dependencies". A project that exists but cannot be loaded — malformed `jk.toml`, a
workspace member missing its `jk.toml`, IO failure — is a **422** with `{ error }` naming what is
broken; a malformed `dir` or unknown scope is a **400**. Token-gated like `/api/project`. The SPA
loads this **only** when the Dependencies panel opens.

The default scope set is defined once — `DependencyTreeStyle.defaultScopeOrder()` (`export`, `main`,
`runtime`) — and shared verbatim by `jk tree` and this endpoint. Declared-only is the default on
both; `transitive=1` matches `jk tree -t` / `--transitive`.

### `GET /api/project/flaky`

The project page's **Flaky tests** tab: `GET /api/project/flaky?dir=<checkout>`. It reads the
checkout's `.jk/test/quarantine.toml` and the per-class test histories JumpKick keeps under its state
directory for the modules at or under `dir`
([test.md](../user/test.md#flaky-tests-are-quarantined-jk-flaky)).

`{ dir, policy: { flakes, runs }, file, error?, classes: [{ name, module, history, flakes,
quarantined?: { reason, since, issue? } }] }`

`classes` lists every class the file holds, then every other class that passed on a re-run in its
last `policy.runs` runs since its last release, the quarantined first and then by `flakes`, the
re-run passes in that window. `history` is that window, oldest first, one character a run: `.`
passed, `R` passed on a re-run, `F` failed, with a `|` where `jk flaky release` released it; empty for a class the file holds that no history records. `module` is the
entry's module key (`.` for the root; empty when the entry holds the class in every module).
`policy.flakes` is 0 when the root's `[test] quarantine = false`. A file that cannot be read lists no
entry and says why in `error`. A missing `dir` is a **400**, one that is not a directory a **404**.

When a run quarantines a class, the engine adds a notice of kind `quarantine` to the bell
(`QuarantineNotice`), linking to `#project/<id>/flaky?dir=<checkout>`; the `notice` frame shows it
in every tab in use as the toast under the bell.

### `GET /api/project/model`

The checkout's modules as Eclipse JDT projects, for the VS Code extension's Java language server
importer: `GET /api/project/model?dir=<path>`. It is the model `jk ide`, the IntelliJ plugin and
`jk bsp` read (`IdeOps.ideModel`), read the way `jk ide -s` writes `.classpath` files
(`JdtProjects`), so every surface sees the same modules, roots and classpaths.

`{ root, name, workspace, projects: [{ name, dir, compliance, jdkHome, output, entries }] }`

`entries` is the project's raw classpath in order, every path absolute:

| `kind` | Fields | Meaning |
| --- | --- | --- |
| `source` | `path`, `output`, `test`, `generated` | A source root on disk; `generated` is annotation-processor output that may not exist yet |
| `project` | `name`, `test` | Another module's project |
| `library` | `path`, `sources`, `test` | A jar or class folder; `sources` is its `-sources.jar` or `null` |
| `container` | `path` | The JRE container for the module's `JavaSE-<n>` |

`test` puts the entry on the test classpath only. The lockfile is read as it stands; jars it names
that the store lacks are fetched first. Relocking is the caller's: `POST /api/build` with
`kind=lock`, which the extension runs after a `jk.toml` or `jk-lock.toml` change and a 2 s quiet
window. A missing `dir` is a **400**; a checkout with no model (no `jk.toml`, a manifest that does
not parse) is a **422** with `{ error }`. Token-gated like `/api/project`.

### `GET /api/project/dir`

`GET /api/project/dir?project=<id>[&dir=<abs-path>][&path=<rel>][&cursor=<c>][&limit=<n>]`

One page of one directory under one of the id's live checkouts (`WorkspaceFileAccess.resolveRoot`,
over `ProjectIdentity.checkoutsForId`); `path` is blank for the checkout root. One live checkout is
implied; when the id has several, `dir=` is **required** and must be one of them — a request
without it is a structured **400** `{ error, projectId, checkouts: [{ dir, lastBuilt }] }`, never
a guess. `dir=` never widens the sandbox: a tree that merely contains a `jk.toml` is a **404**.

Response: `{ projectId, dir, path, total, next, partial, entries: [{ name, path, dir, lang?,
output?, empty? }] }`. Folders come first, then files, each side ordered case-insensitively. A
folder holding exactly one entry that is itself a folder is **folded** into its parent's row, up
to 16 levels: `name` is `main/java/cc/jumpkick`, `path` the deepest folder, which is what the next
request asks for. `lang` names a file's language (below); `output` marks a module's build output
(`target/`, module-root `build/` and `out/`), listed rather than hidden; `empty` marks a folder with
nothing to show. A page holds at most `limit` entries (default 500, at most 1000); `next` is the
cursor of the following page, or null on the last; `total` counts the whole directory. A directory
past 50 000 entries is read that far and says `partial: true`.

Every entry is shown except hidden names (a leading `.`), `node_modules`, and key material:
`*.pem`, `*.key`, `*.p12`, `*.pfx`, `*.jks`, `*.keystore`, `*.kdbx`, `*.ppk` and SSH private keys
(`id_rsa`, `id_ed25519`, …; their `.pub` halves are shown). A symlink counts as what it points at
and is shown only when its real path stays in the checkout and passes the same rules, so
`docs/x.txt -> ../.env` or a folder link to `.git` is omitted, matching the read endpoints.

`lang` is the language the dashboard highlights the file in, by role-named file (`Dockerfile`,
`Jenkinsfile`), else by extension (`java`, `kotlin`, `groovy` for `.gradle`, `xml`, `yaml`,
`javascript`, `typescript`, `css`, `html`, `markdown`, `mermaid`, …), else `text`. `image` and
`binary` (`.class`, `.jar`, archives, native libraries, fonts, media) are files that are never
read as text.

Errors: missing `project`, an illegal `path` or `cursor`, or a negative or non-numeric `limit` →
**400**; several live checkouts and no `dir` → **400** with the `checkouts` list; unknown id, no
live checkout, a `dir` the id does not record, or a `path` that is hidden, missing or not a
directory → **404**. Token-gated.

### `GET /api/project/find`

`GET /api/project/find?project=<id>&q=<terms>[&dir=<abs-path>][&limit=<n>]`

Files whose checkout-relative path contains every space-separated term of `q`, case-insensitively,
for the files pane's filter. Same sandbox and same shown entries as `dir`. Breadth-first, sources
before build output; files whose own name matches rank above those that match by a folder.
Response: `{ projectId, dir, more, partial, files: [{ path, lang }] }`, at most `limit` files
(default 200, at most 1000); `more` says further matches exist, `partial` that the walk stopped at
200 000 entries or 3 s. Token-gated.

### `GET /api/project/file`

`GET /api/project/file?project=<id>&path=<rel>[&dir=<abs-path>]`

UTF-8 body of one file. `path` is workspace-relative (`src%2FMain.java`). Same sandbox as the
tree: the id's checkout (`dir=` selects among several), and the same hidden and key-material
rules applied both to `path` and to the real path it resolves to inside the checkout; anything else
is a **404**, as a missing file is (existence is not distinguishable). The file is read at that real
path.

Response: `{ projectId, dir, path, lang, bytes, lines, encoding, etag, content }`. `encoding` is
`utf-8`, or `iso-8859-1` when the bytes were not valid UTF-8 (the pane labels the fallback
instead of silently substituting U+FFFD). `etag` is the SHA-256 hex of the on-disk bytes
(optimistic concurrency for PUT). Images and known binaries (`lang: image` / `binary`, by the
path's name or a symlink target's) are **415** here, and so is any file with a NUL byte — use the
raw endpoint below.

| Status | When |
| --- | --- |
| 200 | OK |
| 400 | missing `project` / `path`, illegal relative path, or several live checkouts and no `dir` |
| 401 | no / bad bearer |
| 404 | unknown project, `dir` not one of its checkouts, missing file, or non-servable path |
| 413 | servable file larger than 1 MiB |
| 415 | binary / image path (NUL in the first 8 KiB, or image extension) |

### `GET /api/project/file/raw`

`GET /api/project/file/raw?project=<id>&path=<rel>[&dir=<abs-path>]`

Raw bytes of one file, with the image's `Content-Type` or `application/octet-stream` (images for
the dashboard Preview pane). Same sandbox as the JSON body endpoint. Clients must `fetch` with the bearer
token and build a blob URL — a bare `<img src>` cannot send `Authorization`.

| Status | When |
| --- | --- |
| 200 | OK (`Cache-Control: no-store`) |
| 400 | missing `project` / `path`, illegal relative path, or several live checkouts and no `dir` |
| 401 | no / bad bearer |
| 404 | unknown project, `dir` not one of its checkouts, missing file, or non-servable path |
| 413 | larger than 1 MiB |

### `PUT /api/project/file`

`PUT /api/project/file` with JSON body
`{ "project", "path", "content", "etag"?, "encoding"?, "dir"? }`. `dir` names the checkout to
write, required when the id has several live ones; the `etag` is compared against that tree's
bytes, so a save cannot land in a sibling worktree.
`encoding` echoes the value GET returned (`utf-8` default, `iso-8859-1` for the Latin-1
fallback) so a save re-encodes to the original charset instead of transcoding; content no
longer representable in the declared charset → **400**.

Replace a **text** file under the selected checkout (atomic temp+move). The sandbox is the GET
endpoint's, and the write lands at the real path that was checked. Images, known binaries and
refused paths are not writable; a symlink is writable only when both its own name and its
target's name are text. Body size is capped near 1 MiB of content (not the smaller
global 64 KiB mutation limit used for build/cancel).

When `etag` is present it must match the current on-disk SHA-256 (from GET). Mismatch → **409**
`{ "error": "file changed on disk", "etag": "<current>" }` so a multi-tab / external edit cannot
silently clobber. Omit `etag` for last-write-wins.

Response: `{ projectId, dir, path, lang, bytes, lines, etag }` (new content hash), plus
`"lockStale": true` when the saved file is a manifest (`jk.toml` / `jk-libs.toml`) — the lock's
`manifests-sha256` stamp no longer matches, so the next build pays a full re-resolve.

| Status | When |
| --- | --- |
| 200 | OK |
| 400 | missing fields, illegal path, or several live checkouts and no `dir` (body lists `checkouts`) |
| 401 | no / bad bearer |
| 404 | unknown project, `dir` not one of its checkouts, missing file, or non-servable path |
| 409 | `etag` present but file changed on disk (not engine-epoch) |
| 413 | content larger than 1 MiB (or request body over cap) |
| 415 | file type is not text-writable (e.g. image), by the path's name or its target's |
| 422 | a `jk.toml` (by the path's name or its target's) the project's dependency guard rules refuse: the body's `error` is the rule's card, the file is untouched (the same check every `jk add` and `POST /api/project/deps` edit passes) |
| 500 | filesystem write failure |

The dashboard `#project/<id>/files/…` pane is the primary consumer (Copy / Preview / Save + Build).

### `GET /api/history`

The persisted journal, newest first, as the engine wrote each `record.json` (finished rows
stream verbatim; in-flight rows gain live `jid`/`progress`/phase fields — see "Mid-build
connect"). Every row carries `dir`, the checkout it ran in — several worktrees of one repository
share a `projectId` and one build-number sequence, and `dir` is what tells their rows apart —
plus `trigger` (`cli` / `mcp` / `web` / `bsp`, or a fixture trigger the
list already hides) and, when the surface had one, `session` — the MCP connection's
`<client> <tag>`, the BSP client's `<displayName> <id>`, or the CLI's `<client> <key>` (below). A run a
session owns also carries `episode`, the id of the episode it was admitted into (below). The
dashboard's console and the project page's followed run read only these
fields, so they show exactly what `jk-results.md` prints.
A row's `record.json` carries a capped `diagnostics` list and names the rest in
`diagnosticsSpill`, a file beside it; this list, `?id=` and the MCP history and diagnostics tools
merge that file back, so every row carries its whole `diagnostics` and no `diagnosticsSpill`.
A diagnostic that points somewhere names it: `file` (absolute), `line` and `col` (1-based, absent
when unknown). A compiler's come from its own report; a `jk.toml` that does not parse or validate
names the manifest and, for a syntax error or a refused key, the spot (`JkBuildParseException`'s
location); a resolve the solver refuses names the declaration of the dependency it derives from
(`FailureLocation`). Editors read these to mark the line (the VS Code extension's Problems panel).

### `GET /api/changes`

The change journal: what JumpKick changed outside a project, newest first.
`GET /api/changes[?before=<id>&limit=N]` (default 50, at most 500): `{ entries: [...], next }`,
where `next` is the `before` that reads the page older than this one and `null` on the last page.
An entry is `{ kind: "change", id, at, what, action, summary, diff?, actor }`: `id` is a time stamp
(milliseconds and an in-process sequence), a dash and a random suffix, so ids sort by time and are
the cursor; `what` the file
or resource (`~/.jk/config.toml`, `JDK temurin-25.0.3`, `cache tier`); `action` one of `edit`,
`install`, `uninstall`, `clean`, `delete`, `store`, `clear`, `apply`; `diff` a unified diff for a
file edit, or a few lines saying what went where; and `actor` `{ kind, label, ... }`, with `kind`
`shell` (`command`: the command line with option values left out), `agent` (`session`, `client`,
`goal`, `tool`), `import` (`source`, plus the details of whoever ran it and `by`), `engine` or
`dashboard`. A credential never reaches an entry: a `password` or `token` in a `[repositories.*]`
table is `***` in the diff whatever it held, a `${VAR}` reference included, and a stored login
says only where it went.

The journal is `<home>/changes/changes.jsonl`, appended under a file lock by whichever process
made the change (the `jk` client or the engine), `ChangeJournal` in `shared/core`. Past 2 MiB it
becomes `changes.1.jsonl`, replacing the one before. `changes` is a root of its own, like
`creds`: no `jk self nuke` target deletes it, so the entry the nuke writes before it deletes
anything survives it. Writers: `UserConfigEditor` and the MCP `config` tool (`config.toml`), an
import's credential tables, `RepoCredentialStore` and `TokenStore` (logins), `JdkInstaller`,
`JdkToolUninstaller`, `ToolInstaller` (Node.js and build-tool distributions), `ToolLauncher` and
`jk tool uninstall`, `jk node uninstall`, `jk cache clean|nuke`, `jk storage nuke`, the MCP
`disk` tool, `jk self nuke`, `MachineSetup.apply` and `jk activate`'s shell profile edits. The
actor is a scoped value: `jk` sets the command line for each invocation, `McpTools` the session,
client, goal and tool for each call, and an import wraps its own writes.


### `GET /api/activity`

The dashboard's Activity page: one history merged from the change journal, the decision queue
and the bell's notices (`Activity` in `server/engine/.../activity`), with no store of its own.
`GET /api/activity[?before=<cursor>&limit=N&kind=&project=&session=&surface=]` (default 50, at
most 200): `{ entries: [...], next, facets? }`. Entries are newest first by `at`, then `id`; `next`
is `"<at>:<id>"` of the page's last entry, the `before` of the page older than it, `null` on the
last. `facets` (`{ kinds, projects, sessions: [{id, label}], surfaces }`) comes only on the first
page.

An entry is `{ kind, id, at, title, who, surface?, channel?, project?, session?, href, ... }`.
`kind` is `notification`, `decision`, `change`, `setup` (a `setup` decision or an `apply` change)
or `install` (an `install` or `uninstall` change). `id` is `change:…`, `decision:…` or
`notice:…`. `surface` is where it came from or was answered: `cli` (a `jk` command, `jk decide`),
`dashboard`, `agent` (an MCP call, the agent host's form), `import` or `engine`. `channel` is how a
decision reached the person, as `Decisions.alert` recorded it on the queue entry: `in-app`,
`browser`, `native`, `open` or `none`; a notice is `in-app`. `href` is a decision's
`#decision/<id>` or the entry's own `#activity/<id>`. A change adds `what`, `action`, `diff?` and
`actor`; a decision `decision`, `decisionKind`, `outcome` (`open`, `accepted`, `declined`,
`expired`), `answeredBy?`, `answeredAt?` and `detail?`. Filters: `kind=notification` keeps every
entry that reached the person, decisions included; `project` keeps entries whose checkout or
build file starts with it. A notice about a decision or a change is listed once, as its decision
or change.

`ChangeNotices` stats `changes.jsonl` every 5 s and adds one bell notice per batch of new entries
(kind `change`, or `setup` for an `apply`), leaving installs and uninstalls off the bell.

### How a CLI process names its session

A `jk` process sends `session` on every run, and `sessionLeader` beside it
(`RequestEnvironment.session()`, `CliSessionIdentity`), most specific first:

1. `JK_BUILD_SESSION` (or the `jk.build.session` property a BSP server declares), as written,
   with no leader;
2. an agent's own session id: `CLAUDE_CODE_SESSION_ID` is `claude-code (shell) <key>`, led by
   `CLAUDE_PID`; `CODEX_THREAD_ID` is `codex (shell) <key>`, with no leader. Every shell an agent's
   tool spawns is the same session;
3. on Linux, the session id and controlling tty in `/proc/self/stat`: a terminal tab or SSH
   connection runs its shell as the leader of a session of its own (the tabs of one terminal
   emulator share a parent, so the parent is never the key), and a script run from the tab is
   still the tab's. The client is `terminal`, or `script` with no tty;
4. elsewhere (macOS, Windows), the outermost shell in the unbroken run of shells above the
   process, likewise `terminal` or `script`.

`<key>` is six hex digits of a SHA-256 over the identity (the agent's id, or the leader's pid,
start time and tty), so no pid or tty reaches a label. `sessionLeader` is
`<pid>@<start, epoch millis>`; the engine keeps it in the session's `session.json` beside the
record, never in it, and the maintenance sweep ends the session once that process has exited
(a pid alive under another start time is a different process) and none of the session's runs is
in flight. A session with no leader ends at the session expiry. The session id is
`cli-<key>-<started, base 36>`.

### `GET /api/sessions`

`GET /api/sessions[?limit=N][&project=<id>]` (default 50, at most 200): `{ sessions: [...] }`, live
and recent sessions, most recently active first. With `project`, a durable project id, only the
sessions that worked on that project (an episode on it, or the session's own `projectId`), and each
row also carries `episodes`: its episodes on the project, newest first, so the project page's
Sessions tab is one read. A `project` with malformed percent-encoding is a **400**. A session row is `{ id, origin, client?, label?, project?,
projectId?, startedAt, lastActiveAt, endedAt?, state, episode?, current? }`: `origin` is the
trigger (`mcp`, `bsp`, `web`, `cli`), `client` the name the client gave itself (the host the
dashboard shows), `label` the run records' `session` (`claude-code 3f9a`, `terminal 4c1e9a`;
absent for the dashboard), `state` `live`, `idle` (quiet for the idle gap, ten
minutes, with none of its builds in flight on this engine) or `ended`, `episode` the current episode's id and `current` that episode. An episode is
`{ id, session, number, project, projectId?, title, titleSource, outcome, startedAt, lastAt, runs,
actions, modules }`: `id` is `<session>.<number>`, `modules` the names of the modules its runs saw
edited (a file under a module's `src/`, in the order first edited), `titleSource` `goal` (the agent's `goal` argument),
`lastGoal` (the session's last goal, for an episode no call named one for) or `inferred`, and
`title` masked like an action's summary; `outcome` `working`,
`green`, `red` or `abandoned`. What starts an episode and how its title is chosen:
[web](../user/web.md#sessions-and-episodes); the rules are `EpisodeInference`.

`GET /api/sessions/<id>[?before=<seq>&limit=N]`: `{ session, episodes, actions, next }` — the
session's newest episodes, at most 50 (`EpisodeInference.KEEP`, all a session keeps), newest
first, and up to `limit` (default 100, at most 1000) actions older than `before`
(default: the newest), oldest first. `next` is the `before` that reads the page older than this
one, `null` on the first page. Each episode carries `saved`, what it saved: a
[`GET /api/stats`](#get-apistats) row with `scope` `episode`, `dir` its checkout, and `since`/`until`
its start and last fact, added up over the runs the journal placed in it and every action it kept
(not only the page's). When the session's agent records its token counts
([config](../user/config.md#agents-token-counts)) and `[agents] read-usage` is on, `saved.tokens` is
the tokens it used from the episode's start to its last fact (to the next episode's start for
Copilot CLI, which counts a whole session at its end) by its own count, read from its session files: `{ agent, wholeSession, input, cacheWrite, cacheRead, output, total, models:
[{ model, input, cacheWrite, cacheRead, output, total }] }`, a `model` that does not look like a
model id reading `unknown` and `wholeSession` true for a count of a whole session. An action is `{ seq, at, kind, summary, headline?, outcome, millis,
episode, project?, buildNumber? }`: `kind` is `run` or the MCP tool (`query`, `usages`, `read`,
`find`, `rename`, `deps`, `update`, `workspace`), `summary` one line of what it asked (`usages
PricingRules.weekendUplift`), `headline` the first line of its answer (`Usages of
PricingRules.weekendUplift: 3 in main sources, 0 in tests.`), `outcome` `ok`/`error` for an action and `green`/`red`/`cancelled` for a run. `summary` and `headline` are
masked like a run record's free text (the `.env` secrets of the action's project and the
repository credentials its build resolved) before they are written, so `actions.jsonl`, this
route and the SSE `action` event all carry the masked form. An id that is not one path
segment of letters, digits, `.`, `-` and `_` is a **400**; an unknown one a **404**.

Both are bearer-gated like every `/api/*`. The records live under the journal root, one
directory per session: `sessions/<id>/session.json` is a small head (the session record and the
inference state its next fact needs: last fact time, last goal, newest episode number), replaced on
every change; `episodes.jsonl` appends one `{ episode, state }` line each time an episode changes,
the episode whole with the tallies its next fact needs, so the last line for an id is the
episode; `actions.jsonl` appends one line per action. No write grows with a session's age: a
session keeps its newest 50 episodes (an older one is dropped as a new one opens, and a run that
finishes into a dropped episode changes nothing), and once `episodes.jsonl` passes 200 lines it is
compacted to one line per kept episode and `actions.jsonl` to the actions of those episodes, at
most 1000; an engine adopting a session over those bounds compacts it first. The records are pruned with the runs under the same `[history]` age and disk budgets; a session
that has not ended is never pruned. An open MCP session's directory also holds `connection.json`,
the connection behind it (below); it is removed when the session ends.

A session ends at `DELETE /mcp`, or when the sweep (the engine's maintenance tick, and every read
of the sessions) finds it has had no activity for the session expiry (`[mcp] session-expiry`,
default 24 hours, read by `SessionExpiry` whether or not the engine serves HTTP). Activity is its last fact or the last request on its MCP connection, whichever
is later. The rule is the same for every session, attached or not, adopted or not, and an ending
session takes its connection with it: `SessionLog` drops it, deletes `connection.json`, and tells
`McpConnections` to unload it, so its id is a 404 from then on. The idle gap (ten minutes) marks a
session `idle`, settles its open episode (`working` becomes `green`, `red` or `abandoned` by its
last run or action) and splits episodes; it never ends a session. A session with a build in flight
on this engine stays `live`, and its episode `working`, however long the build takes: the answer
comes from the engine's in-flight builds, which a restarted engine starts with none of.

An engine adopts every session a previous engine left open, whether that engine was displaced or
died: each stays open with its episodes as they stood (`session.json` keeps the running tallies
inference needs under `inference`) and its timeline's sequence. An adopted session is under the
same expiry as any other; one whose connection or label acts again on the new engine is the same
session as before. A session with no connection behind it (the shell, the dashboard, a BSP client)
has its open episode settled as it is adopted, since nothing can come back to it, unless the
displaced engine is alive and still handing its runs over; the idle gap settles those.

### Decisions

`GET /api/decisions`: `{ decisions: [...] }`, open decisions first, then settled ones, newest first
within each (the queue keeps 200; a settled decision is also pruned with the sessions under the
`[history]` age and disk budgets, an open one never before its deadline, and the prune drops it from
the queue and its record together). `GET /api/decisions/<id>` is one, or a **404**. A decision is
`{ id, kind, question, detail, command, form, session?, label?, episode?, project?,
createdAt, deadline, state, answers?, answeredBy?, answeredAt?, route }`: `kind` names the
operation (`disk-clean`, `jdk-uninstall`, …), `command` is `jk decide <id>`, `form` the shared
form model ([forms](forms.md)), `state` one of `open`, `accepted`, `declined`, `expired`, and
`answeredBy` one of `host-form`, `host-url`, `dashboard`, `cli`. `route` is `#decision/<id>`.

`POST /api/decisions/<id>` with `{ action: "accept" | "decline", answers? }` answers it as the
dashboard: **200** with the settled decision, **409** when it was already settled, **400** for
answers that do not fit its form, **404** for no such decision. Every surface may give either
answer and the first wins. The queue is `DecisionQueue`; the MCP rungs are `McpDecisions`
([MCP](../user/mcp.md#decisions)), which withdraw a host question still in flight once the queue
settles the decision (`DecisionQueue.whenSettled`); `jk decide` reaches the same queue over the
engine socket (`decisions-request`).

### Notices and the seen marker

`GET /api/notices`: `{ seenUpTo, unseen, notices: [...] }`, newest first. A notice is `{ id, at,
kind, text, href, decision? }`: `at` is epoch milliseconds, strictly increasing across the
machine's notices so the marker is one number, `kind` is `decision` (one per decision that opens)
or another producer's, and `href` is where its **View** goes. `unseen` counts the notices newer than
`seenUpTo`. `POST /api/notices/seen` with `{ upTo }` moves the marker there, never back and never
past the newest notice, and answers `{ seenUpTo, unseen }` (**400** without a number). Every
addition and every move is the dashboard-only SSE `notice` frame `{ change: "added" | "seen",
notice?, seenUpTo, unseen }`, so clearing the count in one tab clears it in every tab. `Notices`
keeps the newest 50 and the marker per machine in `<state>/notices.json`, rewritten whole on each
change; seen is not resolved, so the open decisions stay in the bell from `GET /api/decisions`.

The first engine of a freshly installed version adds one `updated` notice ("JumpKick was updated
to X (from Y)", `href` `#whats-new`) and records `announced-web` in `self-update.toml`, so a
restart does not repeat it. `GET /api/whats-new` serves the dialog it opens: `{ version, from?,
headline?, releaseUrl, entries: [{ version, bullets: [markdown…] }] }`, the highlights of every
release the last install moved across (from the `whats-new.md` the install kept), or this version's
own when the last install was not to it.

### The Update pill

`GET /api/self-update`: `{ running, offer, installing }`. `offer` is the downloaded release a
person may be offered (newer than this engine, not snoozed on any surface, updating not off), or
`null`; an engine that never updates itself (a checkout, a snapshot) always answers `null`.
`POST /api/self-update/install` installs it now, past the scheduler's quiet trigger, and answers
**202** with the state (**409** when nothing newer is downloaded): the new engine displaces this
one, builds in flight finish here, and the epoch gate reloads every tab onto the new engine. `POST
/api/self-update/snooze` is "not now" for the offered release on every surface until the next
check (`UpdateOffer`). Both POSTs need the bearer token like every `/api` call. The dashboard reads
the state on load, every five minutes and when the tab comes back (`selfupdate.js`).

### One channel per decision

`Decisions.alert` carries out `[ui] ask` once a decision reaches `NEEDS-YOU`. Under `notify` it asks
`Dashboards.channel()`, which picks from what each dashboard tab last reported over its event
stream ([below](#dashboard-presence)): **in-app** when a fresh tab is *active* (visible, focused,
and used within `Dashboards.IDLE_AFTER`, 2 min; nothing notifies: that tab opens the decision's
dialog by itself, guarded so a stray keypress cannot answer it, and anything else wiggles its bell
with a short preview card; [web client](webclient.md#decisions-and-notifications)), **browser** when a fresh tab that is not active reports `granted`
(the freshest such tab gets a `notify` frame, visible or not; the desktop notification is skipped),
else **native** (`DecisionAlerts`, below). A report counts while it is at most
`Dashboards.STALE_AFTER` (45 s, three pings) old; a frozen tab or a slept machine stops answering
and so falls back to native. The idle limit counts from the input the report names plus the
report's age, so a tab that stops reporting turns idle on time. A closed stream leaves at once.

The page shows the notification titled **JumpKick needs you**, tagged with the decision id (a
repeat replaces it), and a click brings a dashboard window forward and opens `#decision/<id>`. Every
tab closes the notification by tag when the `decision` frame settles it. While the dashboard's
service worker controls the page, it shows the notification, which may carry the decision's
choices as buttons, and routes the clicks; a button's answer is the page's
`POST /api/decisions/<id>` ([web client](webclient.md#the-installed-app)). No Web Push: a page
notifies only while a tab is open.

Permission is per origin, port included, so each engine port asks on its own.

### Desktop notifications

`DecisionAlerts` shows the native notification: a toast on Windows, `notify-send` on Linux, `osascript` on macOS, each
withdrawn when the queue settles the decision. The name and icon they carry are
`DesktopIdentity` (shared/core, `cc.jumpkick.desktop`), the one place that knows the
AppUserModelID `cc.jumpkick.JumpKick`, the desktop entry `jumpkick` and the icons. `jk self
desktop` (the installers run it for the default home) and `DesktopIdentity.ensure`, which the
engine calls before its first notification, both register them. `ensure` runs once per engine and
only for the default home, so a scratch `JK_HOME` never takes the shortcut or the desktop entry
over.

- **Windows.** A toast from an unpackaged app shows under an AppUserModelID that a Start-menu
  shortcut carries, and takes its name and icon from that shortcut. No scriptable COM object sets
  `System.AppUserModel.ID` on a `.lnk`, so the shortcut is written by a small C# class that
  PowerShell compiles with `Add-Type` (`IShellLinkW` plus `IPropertyStore`). That takes a second
  or two, once. Click-through is protocol activation (the toast's `launch` is the dashboard link),
  so no COM activator is registered. Withdrawal is `ToastNotificationManager.History.Remove` with
  the decision's id as the tag, under the same AppUserModelID.
- **Linux.** `notify-send --app-name=JumpKick --icon=<home>/lib/desktop/jumpkick.png
  --hint=string:desktop-entry:jumpkick`. GNOME names the notification and badges it from the
  desktop entry; other servers use the app name and the icon.
- **macOS.** `osascript` notifications are attributed to Script Editor, and only an app bundle
  changes that. The options:
  - *A small notifier bundle*, `~/.jk/lib/desktop/JumpKick.app`: a Swift program of about a hundred
    lines on `UNUserNotificationCenter`, with bundle id `cc.jumpkick.JumpKick` and the icon as an
    `.icns`. It gives what Windows and Linux have: the name and logo, click-through (its delegate
    opens the link) and withdrawal (`removeDeliveredNotifications`). Cost: a Swift build on the
    macOS release runner, a release artifact under the signed `SHA256SUMS`, Developer ID signing and
    notarization for releases (ad hoc signing is enough for a snapshot, which nothing quarantines),
    and the one-time permission prompt macOS shows for any app's first notification.
  - *The installed dashboard (PWA)*: notifications under the PWA's name and icon at no native cost,
    but only for a user who installed it and while it runs. The native notification is the
    fallback for a user with no dashboard open, often one who has never run `jk web`, so the PWA
    cannot replace it.
  - `terminal-notifier` is not shipped: a third-party binary, and still not JumpKick's identity.

  The recommendation is the notifier bundle; until it lands, macOS keeps `osascript`.

### `GET /report/<token>/<id>/<path>`

A run's coverage HTML report, served from the checkout the run measured. A token is a path segment —
the one place a credential can ride into every page a report links to relatively — so it is not the
bearer token, which would then sit in the browser's history and in any copied link: it is a **report
token** for that `<id>` alone, `<expiry>.<mac>` (the expiry in epoch seconds, base 36; the MAC an
HMAC-SHA256 of the id and the expiry keyed by the bearer token, base64url, compared in constant
time), good for 8 hours and void once `jk engine rotate-token` replaces the bearer token. Nothing is
stored, so a restart keeps an open report working. The dashboard gets one from `GET
/api/report-token?id=<id>` (the bearer token, like any `/api` read): `{"token", "expiresAt"}`, epoch
millis; a run with no coverage report is a 404, no `id` a 400. A missing, expired or other run's
token, the bearer token included, is a **403** `report link expired or not valid`. `<id>` is the
journal record's id (or its build number / job directory, as `GET /api/history?id=` accepts), and
the token names it as written; a run whose record has no `coverage[]` is a 404. An empty `<path>` is a `302` to the report's entry page, with a
`Location` relative to the request so the token is never echoed into a header: the
one module's `index.html`, or the workspace roll-up (`target/site/jacoco/index.html`, or
`workspace.html` beside it when a module's own report owns `index.html`). Any other `<path>`
resolves under the record's `dir` and is served only when it is a regular file inside one of the
report directories the record names (each module's `html` parent, plus the roll-up's); the check
is on real paths, so a symlink planted under a report directory never serves its target. Files
stream with a 16 MiB cap (**413** past it). Pages carry
`Content-Security-Policy: sandbox`, `Referrer-Policy: no-referrer` and `Cache-Control: no-cache`
(and `X-Content-Type-Options: nosniff`, as every response does); the content type follows the extension (html, css, js,
gif, png, svg, ico, xml, txt).

### `GET /api/metrics`

Aggregate build history as a flat array, one object per row, averages pre-computed so clients stay
arithmetic-free. `?dir=<path>` keeps that project's rows plus the always-included machine tiers.

| `scope` | Row is | `kind` | `task` |
| --- | --- | --- | --- |
| `global` | every build on this machine | `build` / `test` | null |
| `project` | every build of one `dir` | `build` / `test` | null |
| `task` | one task across every project | null | task name |
| `project/task` | one task in one `dir` | null | task name |

The tier names come from `BuildMetrics.SCOPE_*`; the socket `metrics-entry` frame carries the same
`scope` values.

### `GET /api/stats`

The savings ledger: `{ rows: [...] }`, the row with `scope` `all` first, then one `project` row per
project (`projectId`, its newest `dir` and `coord`), the one with the most saved first.
`?since=<epoch ms>` opens the period (absent: every run the journal keeps); a value that is not a
number is a **400**. Each row is `Savings.toMap()`: the run count, the cache's `savedMillis` and
`estimatedUncachedMillis` with the `coveredSkips` / `estimatedSkips` behind them, `stepsSkipped`,
`suitesSkipped`, `testClassesNotRerun`, `graphCalls`, `refactorings`, `edits`, `guardViolations`
(each guard rule broken in each file once in the period) and `guardStops` (the runs they stopped),
then the tokens the code questions and refactorings saved: `tokensSaved`, `tokensSavedByModel`
(one object per model id: `model`, `input`, `output`, `cacheRead`, `cacheWrite`, `cacheWrite1h`,
so rows from many projects or machines add up and can be priced again), `usdSavedMicros`
(millionths of a dollar at the price table in effect, `PriceTables.current()`), `tokensUnpriced`
(tokens on a model that table has no row for, left out of the dollars), `callsNotMeasured` (code
answers and refactorings the bench has no rate for, by their agent or kind, left out of
`tokensSaved`), `ratesMeasured` and
`pricesRetrieved`, the dates of the bench runs and of the newest price behind the dollars. Those are
an estimate from `SavingsRates`
(`rates.json`, written by `bench/scoreboard --render`; [web](../user/web.md#tokens-and-dollars-saved)).
`SavingsLedger` adds them up from `BuildJournal.finishedSince` and `SessionLog.actionsSince`; the
socket's `stats` rows (`jk stats -O jsonl`) carry the same fields.

### `GET /api/config`

Effective machine `~/.jk/config.toml` (plus env) as `{ path, rows: [{ key, default, value,
overridden }] }` for the Status Configuration panel — every known scalar key with its default and
whether the effective value differs. Token-gated even on loopback: the payload names the
owner's config path and raw values, the same class as `/api/forms/new-project`.

### New project

The dashboard's wizard renders the engine's new-project form (`NewProjectForm`: `NewProjectStep`
then the template's `[[ask]]`), the same model `jk new` and MCP `new` render.
`HttpNewProjectApi` serves it and `NewProjectPost` settles what comes back:

| Endpoint | Shape |
| --- | --- |
| `GET /api/forms/new-project?template=<ref>&lang=<lang>` | `{ form, template, derived }`: the form as `Form#toJson` writes it, `rev` included; the id the ref resolved to (no ref: the default template); and this machine's values of the form's derived defaults, `{ "git-email": …, "parent-dir": … }` |
| `POST /api/projects/preview` | `{ rev, answers }` → `{ path, template, files, manifest }`, scaffolded and wired for agents into a scratch directory; nothing is written |
| `POST /api/projects` | `{ rev, answers, elicitation? }` → **201** `{ path, dir, projectId, agents, handedOff? }`; `agents` is `{ files, notes }`, the agent files the create wrote for the engine's `JK_AGENTS` setting and the steps left to the user; `handedOff` comes with an `elicitation`, false when no MCP call was still waiting on it |
| `GET /api/templates?lang=<lang>` | the catalogue the form's `template` search lists, one language when `lang` names one |

`answers` maps form keys to answers (a string, a boolean for `bool`, a list for `multi`); the
answers' `template` and `lang` name the form they settle. A `rev` that no longer names that form's
content is a **409** carrying `{ error, form, template, derived }` for the form in force; answers
that do not settle it are a **400** `{ error, problems: [{ key, message }] }`. A left-out answer
takes its default, a derived one from `derived`. A target that already exists is a **409** on both
the preview and the create. `elicitation` is the id from a wizard link MCP `new` handed out by URL
elicitation (`NewProjectWizard`): the create completes the call waiting on it and is wired for that
call's agents. The link outlives the call's five-minute wait by up to an hour from when it opened
(at most 64 are kept), so a create that lands after the call gave up still gets the call's agents
and says `handedOff: false`; the wizard tells the human the agent was not told.

The token file persists across restarts precisely so an open tab survives an upgrade or crash
respawn. `jk engine rotate-token` is the explicit way to invalidate it.

## MCP sessions and server requests

`McpHandler` opens one `McpConnection` per `initialize`: the `Mcp-Session-Id`, the `McpRevision`
it negotiated and the `McpElicitModes` the client declared. The id is 32 hex digits of
`SecureRandom`, drawn again (at most eight times) while a loaded or stored connection has it, so an
id that ended is never handed to another client. Its label (`claude-code 3f9a`, the session's key and
what the runs record) shows the id's first four hex digits, or the shortest longer prefix whose label
no loaded connection or open session has, so two open sessions never share one.

`McpConnections` is a cache over the session records. A connection is written to its session's
`connection.json` (id, tag, client, pinned project, bound dir, revision, elicitation modes, created and
last seen) once its session opens, again when its bind changes, and at most once a minute for
last seen. A request whose `Mcp-Session-Id` the engine has not loaded (it started after the
connection opened, or the table unloaded it) is answered from the connection an open session
holds, with the same id, label and session and no new `initialize`. A connection goes when its
session ends, at `DELETE /mcp` or at the session expiry (above); a call still waiting on that
client for an elicitation answer stops waiting then and gets `Failed` ("the session ended"), and
the update offer forgets what the session answered.

The table holds at most `[mcp] max-sessions` connections (default 256) while any of them is idle:
stored, with no request being served and none for 30 seconds. To load one more past that, it
unloads the least recently used idle connection, storing it as it last stood. A connection
reloading from its session is never refused, so the table can run over until connections go
idle. Only when every loaded connection is active is a new `initialize` refused: **503** with
`Retry-After` (the seconds until the first loaded connection with nothing being served could go
idle) and a JSON-RPC `-32000` error naming the limit, the way `HttpAdmission` refuses a saturated
lane. Requests on existing connections are never refused or cut off to make room.
`GET /api/status`, the SSE `status` frame, `jk engine status` and the MCP `status` tool report the
limit and how many connections are loaded and active ([`event: status`](#event-status)).
An id found nowhere (its session ended or was pruned, or it came from another machine's state) is
gone, as the Streamable HTTP spec says: `POST`, the event-stream `GET` and `DELETE` answer **404**,
the `POST` body a JSON-RPC error `-32001` per request, and nothing runs. The client starts a new
session with `initialize`; `jk mcp` (`McpBridge`) repeats the client's own `initialize` without a
session, sends `notifications/initialized`, and sends the refused request once more on the new
session, so the host sees only that answer. A request from `jk mcp` (it sends `Jk-Project`) with no
session that is not an `initialize` is a **400**. Only `initialize` opens a connection, and it opens
the connection's session with it. A new connection never takes an id an open session still holds. The revision decides what the session
may use (batches before `2025-06-18`, form elicitation from it, URL mode from `2025-11-25`), so a
new rule that depends on the revision reads it there rather than comparing strings.

A tool asks the client with `McpCall.elicit(question, timeout)`. It answers `Unsupported` without
sending anything when the session did not declare the mode or the call has no `McpOutbound`.
Otherwise the request goes out on the call's own response: `McpFront` commits that response as
`text/event-stream` on the first message, and writes the call's JSON-RPC response as the last
event. A request that sends nothing still answers plain JSON. The client's response arrives as a
separate `POST`; `McpRpc` hands any message with an id and no method to the connection, which
completes the waiting call by request id. The wait yields the RPC admission permit, as a parked
`build` does. `jk mcp` (`McpBridge`) asks for both content types, writes each streamed request to
stdout, and posts the host's response lines back.

### The bridge contract

A `jk mcp` keeps running for the life of its agent session, so after JumpKick updates itself an
old bridge talks to a new engine. Formats are not versioned before 1.0, so what keeps that working
is the bridge staying a relay. Everything else belongs in the engine. The bridge may read only:

- the `initialize` it saw from its client (to replay on a renewal) and the reply's
  `protocolVersion` and `serverInfo.version`;
- `id` and `method` (to tell requests, responses and notifications apart, and to key outstanding
  calls), and a `tools/call`'s `params._meta.progressToken` (which it adds when absent);
- the transport: `Mcp-Session-Id`, `MCP-Protocol-Version`, `Jk-Project`, the bearer token, a **404**
  that means the session is gone, and the event stream of a call's response;
- a `notifications/progress`'s `params.progressToken`, to drop one for a token the bridge added,
  which its client never sent.

Every other method, field, result and notification passes through byte for byte, apart from line
breaks between JSON tokens (`McpBridgeTest#an_unknown_method_and_unknown_fields_pass_through_untouched`).
A bridge re-finds the engine through `McpCommand.endpoint`, which uses the newer engine the install
names as it is rather than taking it over, and when that engine is stopped or draining has the
installed client start it (`bin/jk engine start`) rather than start its own older one. An engine
answers a stored session only when an engine of its own version stored it; a session another
version stored is a **404**, so the bridge renews, and after a renewal answers with a different
`serverInfo.version` it sends its client `notifications/tools/list_changed`; the engine declares
`tools.listChanged`. A new MCP feature that needs the bridge to understand more breaks
every bridge already running until it restarts: put it in the engine instead.

MCP `new` climbs from form mode to URL mode (`McpNewForm`): with only URL mode declared, it opens an
id in `NewProjectWizard`, sends a `mode: "url"` request whose link is the dashboard's wizard
(`#new?<key>=<value>&…&elicitation=<id>`, no token: the agent's host holds the link), and after an accept waits for
`POST /api/projects` to carry that `elicitation`. The create completes the wait, the call answers
with the created project and sends `notifications/elicitation/complete`.

## Starting a job (`POST /api/build`)

One admission point for every HTTP and MCP job, and one decoder (`JobRequests`) in front of it:
the body of `POST /api/build` and the arguments of MCP `build` are the same job request, and the
same JSON admits the same `JobSpec` on both, apart from its origin (`web` or `mcp` and the
connection). The fields:

| Field | Type | Meaning |
|---|---|---|
| `dir` | string | The checkout (required; MCP falls back to the connection's bind) |
| `kind` | string | `build` (REST default; MCP `build` defaults to `test` and takes the build kinds only), `test`, `lock`, `guard`, `update`, … |
| `modules` | string array or `a,b` | Module selection, as `-m` |
| `classes` | string array or `A,B` | Test classes, as `-S` |
| `suites` | string array | Test suites, as `-s` |
| `include_tags` / `exclude_tags` | string array | Test tags, as `-I` / `-E` |
| `affected` | boolean | Only the modules the working tree changed (and what depends on them) |
| `skip_tests` | boolean | Skip the test phase, as `-S` on `build` |
| `deadline_s` | integer | The job's wall deadline in seconds (`0` = none) |
| `deps` | string array or `a,b` | `kind=update` only: the handles or `group:artifact`s to move (default: every pin) |
| `major` | boolean | `kind=update` only: let a pin cross its major |

A field outside the table (MCP `build` adds its own `goal`, `wait`, `timeout_s`, `jid`, `id`,
`decision`) is refused, `400` naming it (an `isError` result on MCP), as is a value of the wrong type. The
spec resolves to the hosted verb, decodes to the same wire request line the CLI would send, and
submits detached. The reply is `202` with the `jid`; progress is the SSE stream. `409` carries the
`jid` of a same-project job already in flight, `400` a body the verb cannot run.

Every JSON request body (`POST /api/build`, `/api/cancel`, `/api/projects`, `/mcp`) is capped at
64 KiB; past it the engine stops reading and answers **413**
`{ "error": "request body too large", "maxBytes": 65536 }` — a body is never truncated and
parsed. `PUT /api/project/file` has its own, larger cap. Every body, the front door's proxied
ones included, must arrive whole within 30 s (`HttpRequests.BODY_DEADLINE`): the handler reads it
holding its admission permit, so past the deadline the connection is closed and the permit let go.

A detached job has no connection whose EOF would end it, so it always runs under a wall
deadline: the request's `deadline_s` when present (`0` = none; a negative is a `400`), else the
engine's `[engine] detached-deadline-ms` / `JK_ENGINE_DETACHED_DEADLINE_MS` (default one hour).
Past it the job takes the same bounded cancel path a user cancel does, and `request-finish` and
the journal row carry a `cancelReason` naming the deadline and the knob that set it. Socket jobs
the CLI owns are unchanged: their EOF is the deadline, and `JK_ENGINE_JOB_DEADLINE_MS` stays off
unless set.

## Editing dependencies (`POST /api/project/deps`)

`POST /api/project/deps {dir, action, coords, scope, preview}` adds, removes or pins
`group:artifact[:version]` coordinates (`action` `add` (default), `remove` or `pin`; `scope` a
canonical scope, `main` by default). It is the one manifest edit (`ManifestEdit`) `jk add`,
`jk remove` and MCP `deps` run: a version no configured repository serves is refused, `latest`
(no version) pins today's stable, the guard rules judge the proposed manifest, the write is atomic,
and a written edit relocks; an add whose new lock trips the deny policy puts `jk.toml` and
`jk-lock.toml` back. `preview: true` writes nothing and does not lock. An unknown field is a `400`.

`{ changed, applied, notes: [...], preview?, detail?, lock?, denyHits?, error? }` — `preview` the
proposed manifest when it differs, `lock` `lock ok` or the failed relock's lines, `error` why nothing
was written. A refused edit or a failed lock answers `422`.

## Health (`GET /api/doctor`)

`GET /api/doctor?dir=<path>` answers the object MCP `doctor` returns: `{ ok, rows: [{ id, label,
status, detail, fix? }] }`, `status` one of `ok`, `warn`, `fail`, `unavailable`. The rows are the
ones `jk doctor` prints, from the same checks (`DoctorChecks`): `config`, `dirs`, `state`, `jdk`,
`lock` and `m2` for `dir` (`unavailable` when no `dir` is named), the engine's own row, the
client's own rows (`client`, `shell`, `path`, `mvn`, `logins`, `tools`) as `unavailable`, and on Windows
the host rows with their fixes. `ok` is false when any row failed. `GET /api/host` keeps serving
the Status view's quiet Windows notes, a filter of the same Windows rows.

## Dependency reads (`GET /api/project/outdated`, `/why`, `/update`)

Each answers the object the MCP tool of the same name returns for the same input (the tool wraps
it in its envelope), and refuses a query parameter it does not read with a `400`:

| Route | Parameters | Answer |
|---|---|---|
| `GET /api/project/outdated` | `dir`, `all` | MCP `outdated`: the declared dependencies an update would move (`all=true`: every row checked) and the `file` it wrote |
| `GET /api/project/why` | `dir`, `coord` | The dependency paths and the rule that picked the version, the report MCP `why` renders as lines |
| `GET /api/project/update` | `dir`, `deps` (`a,b`), `major` | The preview of `jk update`, as MCP `update` without `apply`: `rewrites` (each with the packages its relock `adds`), `available` majors |

Applying an update is the `update` job: `POST /api/build` with `kind=update` and the same `deps`
and `major`. A read that fails (no `jk.toml`, an unreachable repository) is a `422` with `error`.

## Forecasting a job (`GET /api/explain`)

`GET /api/explain?dir=<path>&modules=a,b&skip_tests=true` forecasts the build `POST /api/build`
with the same fields would start: the job request's selection fields (`dir`, `modules`, `classes`,
`suites`, `include_tags`, `exclude_tags`, `affected`, `skip_tests`; lists comma-separated) go
through the same decoder, and the forecast runs under the session a hosted build of that selection
gets. `kind` and `deadline_s` do not change a forecast and are refused like any unknown field
(`400`). The CLI's `jk explain`, this route and MCP `explain` are one operation (`Forecasts`):

`{ modules: [{ dir, coord, dirty, cachedSteps, steps, provisionalSteps? }], moreModules?, dirtyCount,
provisionalSteps, maxReadyWidth, etaMillis, fullMillis, etaLowMillis, etaHighMillis, etaConfidence,
criticalPath? }`

At most 100 module rows; `moreModules` counts the rest. A selection that matches nothing or a
plan with errors is a `422` with `{ errors: [...] }`. Step rows stay on the wire burst `jk explain`
renders.

## Live updates (`GET /api/events`)

One SSE stream serves **build activity** and **chrome vitals**. Additive event names only (no
protocol version bump). Fan-out is `HttpEvents` (bounded per-subscriber queues with priority
shedding: a full queue evicts the oldest low-priority frame — output/label lose to
progress/structure, and the freshest sample always survives); the engine skips work when
`hasSubscribers()` is false.

| Kind | Events | When published |
| --- | --- | --- |
| **Inflicted** | `request-queued` / `request-start` / `plan` / `module-*` / `task-start` / `task-finish` / `label` / `progress` / `workspace-progress` / `eta` / `output` / `error` / `*-finish` / `request-finish` | As the plan mutates state. Structural events are immediate; hot ticks (`progress`/`tick-update`/`label`/`output`) ride the 500 ms wire coalescer — see "Live smoothness" below |
| **Sampled** (change-gated) | `status` | ~every 2 s while any client is subscribed, **and** only when presentation-quantized vitals change (CPU ~1 pp, RAM/heap ~1 MiB, counters exact, the backpressure counts and `idleDropped` among them). Also forced on stream connect and nudged on request start/finish |
| **Session log** | `session` / `episode` / `action` | As a session starts, goes idle, acts again, ends or changes its current episode; as an episode starts, is retitled or settles; once per non-run MCP action. Dashboard subscribers only |
| **Machine** | `machine` / `engines` | `machine`: on the Windows engine, a WSL install or remove queued, at each step, and done or failed, as `{distro, operation}` with the operation as `GET /api/machine/environments` lists it. `engines`: the engines the front door lists changed, no payload; the tab reads `GET /api/engines` again. Dashboard subscribers only |
| **Sampled** (change-gated, IO) | `cache` | Safety-net tick (60 s) while subscribed, plus after request finish; snapshot walks are single-flight and TTL-memoized (30 s) engine-side; **not** on the 2 s status sampler. Live frames are **thin** (dual surface totals + budgets, `"thin": true`); full section breakdown is REST-only |

**A stream lasts as long as its page.** The JDK server never reads a socket while a handler
streams, so the engine learns that a page went away only when a write to its stream fails: a quiet
stream writes a `: heartbeat` comment every second (`SseEndpoint`), and the second write after the
page closes fails, which frees the stream's slot and its vitals subscriber within two seconds. A
client that keeps the connection but stops reading fills the socket, and a write blocks instead of
failing: every response body, plain answers (`HttpResponses`, the log tail, the history list) and
static files as well as event streams, MCP calls answered as event streams and front-door proxied
answers, goes through `WriteDeadline`, so one write blocked for 30 s closes the connection and ends
the response, letting go of its admission permit. Time between writes does not count, so a quiet
stream lives on, and a large write goes out in 64 KiB slices, each with its own 30 s, so a slow
client that keeps reading gets the whole body. The
samplers run while a dashboard stream has joined and not left, attached or still connecting: a tick
that finds no attached dashboard skips its sample and never stops them. A comment line wakes no handler in the page, so an idle tab costs a dozen bytes a second. The page
closes its `EventSource` on `pagehide` and reopens it on a back/forward-cache `pageshow`, so a
reload or a cached page never holds one of the browser's six connections to the engine.

**Which events each surface carries** is written in one place, `EventCatalog`
(`shared/wire/.../protocol/EventCatalog.java`): every event JumpKick streams, and for the engine wire,
console JSONL (`-O jsonl` stdout), SSE and MCP either how it is carried (under which name, how
sampled) or why it is omitted. `SseEventSink` asks it rather than dropping events in empty cases,
and the catalog tests drive each surface's real encoder against it (`WireRecordContractTest`,
`EventCatalogSseTest`, `EventCatalogMcpTest`, `EventCatalogConsoleTest`): a new event type fails
until it has a row, and a surface that carries what its row omits, or drops what it carries, fails
too. A new event is a row first, then its encoders.

A build event's SSE frame is its **wire record** — the line `WireEventSink` sends the CLI — under
`schema`, `type` (SSE's name for it: a plan's start and ticks are `progress`, a diagnostic
`error`) and `jid`, with `progress` set to the run's aggregate and free text (`label`, `line`,
`message`, `stack`) masked (`SsePublisher.frame`). There is no per-event payload map: a field the
record gains reaches the dashboard and MCP as it reaches the CLI.

The sampled `status`/`cache` frames are **dashboard-stream chrome**: MCP SSE subscriptions
(`GET /mcp`) never receive them, and an MCP stream alone neither starts nor sustains the samplers
— "while subscribed" above means dashboard (`/api/events`) subscribers.

The decision queue's `decision` frame is dashboard-only as well: the decision as `GET
/api/decisions` lists it, on every open and settle (expiry included).

#### Dashboard presence

Each dashboard stream is delivered, to it alone, a `presence` frame `{ stream, every }` on connect
and every `Dashboards.PING_EVERY` (15 s). The tab answers each, and every visibility, focus or permission
change and every turn to idle or active, with `POST /api/events/<stream>` and `{ visible, focused,
lastInputAgoMs, permission }` (`focused` is `document.hasFocus()`, `lastInputAgoMs` the time since
the tab's last mouse, key, wheel or touch input or -1 for none, `permission` is
`Notification.permission`, or `unsupported`; a body without `focused` reads as a tab not in use): **200**, **404** once that stream has ended, **400**
for a body that is not an object. EventSource cannot write upstream, so the post is the stream's
return half; it names no tab the stream does not hold. The page handles `presence` and `notify`
as they arrive rather than through its drain queue, whose timers a hidden tab runs late, and the
hub never sheds them (nor `decision`) for an output flood. `notify` carries the decision as `GET
/api/decisions/<id>` returns it, to the one tab `Dashboards` chose.

The session log's frames are dashboard-only too (`EventCatalog.DASHBOARD_ONLY`): an agent reads
its own session through the tools it calls, not as a stream. Neither the engine wire nor console
JSONL carries them. Each is `{ schema, type, … }` under its own name: `session` is `{ change,
session }` with `change` one of `started`, `idle`, `live`, `ended`, `episode` (the current
episode changed) and `session` the row `GET /api/sessions` lists; `episode` is `{ change, episode
}` with `change` one of `started`, `title`, `outcome`, `modules` (its runs edited a module they had
not); `action` is `{ session, action }`, one action
as `GET /api/sessions/<id>` pages it. Runs are not `action` frames: `request-start` and
`request-finish` already carry them, and the run's journal row names its `episode`. One step's
changes to one episode are folded into one frame, so an episode a fact opens is announced once,
as it stands after the fact.

The `cache` storage walk never runs on a request thread: connect hydrate re-sends the last
captured snapshot and never schedules a walk of its own; the post-build nudge is async. A
first-ever connect therefore carries no `cache` frame until a nudge or the 60 s sampler
captures one — the SPA fires a one-shot `GET /api/cache` whenever it is live with no cache
data yet (the REST path shares the same single-flight, TTL-memoized snapshot, so this cannot
storm the store).

**Mid-build connect:** a new dashboard subscription starts *detached* — the engine captures one
compact `run-snapshot` per in-flight job, delivers them to **that subscription only** (not a
broadcast, not a phase-by-phase replay), and only then attaches it for broadcasts, all under a
connect ordering lock that excludes concurrent publishes. Every event is therefore either
reflected in the snapshot or delivered to the queue after it; overlap folds idempotently, gaps
are impossible. Independently, `GET /api/history` enriches `running: true` rows with the same
live fields so the SPA's initial GET matches the TUI even before the first SSE frame.

**Reconnect replay:** every frame carries an `id:`, increasing for the engine's life and starting
at the engine's start time in microseconds, so an id from an earlier engine is below all of this
one's. The engine keeps the last 2048 broadcast frames (at most 8 MiB). A reconnect that names the
last id it received — EventSource's own `Last-Event-ID`, or `?lastEventId=` on a stream the SPA
re-creates — is attached with every kept frame after that id queued first, and gets no
`run-snapshot`: the replay already carries what a snapshot would, and the failure details
(`error`, `output`) it would not. An id older than the oldest kept frame, or newer than the
engine's newest, gets the snapshot path above.

`run-snapshot` payload (same shape as an enriched history row): `jid`, `kind`,
`dir`, `coord?`, `projectId?`, `trigger?`/`session?` (who asked, as the journal row carries it;
`request-start` carries the same pair), `buildNumber?`, `historyId?` (journal id, present for
journaled kinds), `startedAt` + `serverNow` (engine wall-clock pair — the SPA derives skew-free elapsed
as `serverNow − startedAt` and re-anchors it to its own clock at receipt; both omitted until
the hold registers), `running: true`, `progress?`, `remainingMs?`, `R0?`,
`numerator`/`denominator?`, and phase chains: `modules[]` (`dir`, `coord?`, `finished`,
`success`, `millis`, `didWork?` when finished, `tasks[]`) or top-level `tasks[]`
(`name`/`stage`/`status`/`millis`) for single-plan runs.

**Live smoothness:** hot progress traffic (aggregate `workspace-progress`, plan
`progress`/`tick-update`/`label`/`output`) is sampled at **`JK_WIRE_PROGRESS_MS`** (default
**500 ms**) on both the CLI UDS path and SSE — same coalescer. `progress`/`tick-update`/`label` are sampled (latest wins); `output` lines are queued and
delivered as a batch each cadence tick, so multi-line bursts (test-failure stacks, native-image
logs) arrive complete. Structural events stay immediate.
The SSE queue sheds low-priority frames before critical ones when full (oldest first). The SPA drains
EventSource callbacks on animation frames and coalesces progress/ETA ticks so the main thread
stays free; open-loop clock/residual fills the gaps between 500 ms samples.

### `event: status`

Core engine/host vitals (same facts as `GET /api/status` heap/load/plans fields), including
`availableMemoryBytes`, `systemCpuLoad`, `systemLoadAverage` (1‑minute), and `engineEpoch`
(process generation id). The worker budget is on the same object: `workerBudgetBytes`,
`workerBudgetSource`, `workerLeasedBytes`, `workerOverbookedBytes`, `workerQueued`,
`workerRunningJvms`, `workerCpuCap`, `overbookingOff`, `workerWaiting` (what the head of the lease
queue needs and who holds the memory; `""` when nothing is queued), and `workerOverLease` (workers
far over their lease, largest first; `""` when none is). So is the MCP connection table:
`mcpMaxSessions` (`[mcp] max-sessions`), `mcpLoadedConnections` (connections loaded now) and
`mcpActiveConnections` (loaded ones serving a request, quiet for under 30 seconds, or not yet
stored, which a new connection cannot unload); all three are `0` when the engine serves no MCP. The
socket `status-ack` behind `jk engine status` and the MCP `status` tool carry the same three. Config knobs (`httpUrl`, `maxConcurrentRequests`, …) stay REST-only;
the SPA merges SSE into the last REST hydrate.

### Engine generation (`engineEpoch`)

Every process mints a stable `engineEpoch` (`version[+buildId]@startedAtMillis`). It appears on
`GET /api/status` and every SSE `status` frame. After bootstrap, non-bootstrap `/api/*` calls must
send `X-Jk-Engine-Epoch: <epoch>` matching the running process; mismatch or missing header →
**409** `{ "error": "engine-epoch-mismatch", "engineEpoch": "…" }`. Exempt: `GET /api/status` and
`GET /api/events` (EventSource cannot send headers). Static shell is ungated. The SPA latches the
epoch and hard-reloads when it changes (engine restart / displacement).

### `event: cache` and `GET /api/cache`

Two storage surfaces (CLI parity: `jk cache usage` / `jk storage usage`), not one combined
“cache used” total:

| Surface | Bytes | Budget field |
| --- | --- | --- |
| **Action cache** | key records + cache CAS → `actionCacheBytes` (format stamps and incremental state excluded — own retention) | `actionMaxBytes` (`[cache] max-cache-size-gb`, default 4 GiB / 8 GiB on CI) |
| **Incremental state** | Zinc analysis under `actions/incremental-*` → `incrementalBytes` | `incrementalMaxBytes` (`[cache] incremental-max-size-gb`, default 512 MiB) |
| **Derived caches** | every other tier under the cache root (hash memos, format indexes, extracted trees, ABI snapshots, materialised path repos) → `derivedCount`/`derivedBytes`, apparent bytes | none — bounded by count or supersession, so no `*MaxBytes` field: see `CacheReportBoundsTest` |
| **Artifact store** | jk-owned `repos/` + workers → `artifactStorageBytes`; Maven local is `mavenLocalBytes` | none — the store is never size-pruned |

Incremental state is its own surface because it is bounded on its own denominator; folding it
into `actionsBytes` would make the action-cache bar measure one tier against another tier's
line. `actionsCount`/`actionsBytes` therefore exclude it.

Full REST also exposes `actionsCount`/`actionsBytes` (index), `cacheCasCount`/`cacheCasBytes`
(cache CAS), `incrementalCount`/`incrementalBytes`/`incrementalMaxBytes`,
`derivedCount`/`derivedBytes`, and store section fields. **Live SSE (thin):** `{ "thin": true, actionCacheBytes,
actionMaxBytes, artifactStorageBytes, mavenLocalBytes, lastPrunedMillis }` — enough for the
footer; change-gated on MiB quanta.

**REST (full):** section counts (`casCount`, `actionsCount`, …) for the Status panels. Prefer the two surfaces for UI; `totalBytes`/`totalCount` are the **cache root walked as one tree** — the same fact `jk status` prints as *Size on Disk* and the same one `jk cache nuke` frees. They are not the sum of the section fields (that sum both counted store bytes a nuke leaves and missed every cache tier nobody had added to it); the store's own figure is `artifactStorageBytes`.

Capture is a full exclusive walk of the store/cache trees (hardlink-aware). The engine memoizes it
with a **30 s TTL and single-flight** so concurrent `GET /api/cache` calls do not re-walk multi‑GiB
stores in parallel. SSE connect hydrate **never** walks (it only re-sends a stored snapshot);
first numbers come from Status `GET /api/cache`, post-build `notifyLiveCache` (invalidates the
memo), or the slow 60 s safety-net sampler. Without that, Chrome reconnect storms at engine start
left SerialGC holding ~90 MiB used/committed at idle.

REST `GET /api/status` and `GET /api/cache` remain for hydrate, offline fallback, CLI/MCP tools,
and curl. Metrics (`GET /api/metrics`) stay **REST-only / view-scoped** — not on the vitals SSE bus.

### Build SSE publish map

Build events are produced once: job bridges emit `EngineEvent` into the composed sinks
(`WireEventSink` + `SseEventSink`), so the wire and SSE can't diverge; aggregate
`workspace-progress` and the capped diagnostics publication are the engine folds beside the
sinks. The SSE event name always equals the payload `type`. Progress is coalesced by the
intentional `JK_WIRE_PROGRESS_MS` (default 500 ms) filter on `workspace-progress` (same as
the TUI), never by `LiveVitals`.

| Event | Publisher (typical) | Notes |
| --- | --- | --- |
| `request-queued` | `publishRequestQueued` | A job waiting for coordinator memory (`ahead`, `reason`); once, before its `request-start` |
| `request-start` | `publishRequestStart` | CLI admit + HTTP workspace/lock |
| `plan` | `publishPlan` | Total weight for bar denominator |
| `module-start` / `module-finish` | workspace listener | Per-module rows |
| `task-start` / `task-finish` | plan listener | Phase-tagged steps |
| `label` | plan listener | Live step detail (test class.method, “shrinking jar”, …); SPA paints after the running phase node |
| `progress` | plan ticks | Single-module / per-module detail |
| `workspace-progress` | `emitWorkspaceProgress` | Aggregate %; peak-hold + 0.1% / frame filter |
| `eta` | `publishEta` | Seed + re-projections |
| `output` / `error` | step output / failures | Bounded diagnostics |
| `buildplan-finish` | plan end | Module-level success |
| `request-finish` | request finally | Always includes `success` + `cancelled` (CLI + HTTP) |
| `session` / `episode` / `action` | `SessionLog` | The session log's frames (`publishDashboard`), described under "Live updates" above |
| `run-snapshot` | `rehydrateLiveRunsOnSseConnect` | Connect-only, delivered to the joining subscription (never broadcast); payload documented under "Mid-build connect" above |

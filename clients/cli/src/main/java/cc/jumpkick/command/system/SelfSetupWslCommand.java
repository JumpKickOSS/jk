// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.winhost.MachineSetup;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk self setup-wsl [distro…]} — on Windows, install JumpKick into WSL distros, pinned to this
 * JumpKick's version, and pair each with the front door; {@code --remove} takes it out again, {@code
 * --status} shows each distro's state. The engine does the work ({@code /api/machine/environments}, the
 * operation the dashboard's machine view and the MCP {@code machine} tool use); this prints each
 * distro's steps as they come and waits until every one has ended.
 */
public final class SelfSetupWslCommand implements CliCommand {

    private static final Duration POLL = Duration.ofSeconds(1);

    /** Longer than any one install may take, times a few distros. */
    private static final Duration WAIT = Duration.ofMinutes(45);

    @Override
    public String name() {
        return "setup-wsl";
    }

    @Override
    public String description() {
        return "Install JumpKick into WSL distros (Windows)";
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("distro", Arity.ZERO_OR_MORE, "WSL distros to install into, as --status names them."));
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Show the WSL distros and their JumpKick.", "-s", "--status"),
                Opt.flag("Remove JumpKick from the named distros instead.", "-d", "--remove"),
                Opt.flag("Start the work and return; the dashboard shows its progress.", "-n", "--no-wait"));
    }

    @Override
    public int run(Invocation in) {
        if (!Os.isWindows()) {
            CommandWedge.printFail("Self", "setup-wsl runs on Windows; inside WSL, install with the Linux installer");
            return Exit.USAGE;
        }
        List<String> distros = in.positionals();
        boolean remove = in.isSet("remove");
        if (!in.isSet("status") && distros.isEmpty()) {
            CommandWedge.printFail(
                    "Self",
                    "name the distros to " + (remove ? "remove JumpKick from" : "install into")
                            + " (`jk self setup-wsl --status` shows them)");
            return Exit.USAGE;
        }
        Api api;
        try {
            api = Api.connect();
        } catch (IOException e) {
            CommandWedge.printFail("Self", e.getMessage());
            return Exit.SOFTWARE;
        }
        try {
            if (in.isSet("status")) return list(api.get());
            HttpResponse<String> r = api.post(remove ? "remove" : "install", distros);
            if (!(parse(r.body()) instanceof Map<?, ?> answer)) {
                CommandWedge.printFail(
                        "Self",
                        "the engine answered " + r.statusCode() + ": "
                                + r.body().strip());
                return Exit.SOFTWARE;
            }
            if (answer.get("error") != null) {
                CommandWedge.printFail("Self", String.valueOf(answer.get("error")));
                return Exit.FAILURE;
            }
            List<String> accepted = strings(answer.get("accepted"));
            boolean refusedAny = false;
            if (answer.get("refused") instanceof Map<?, ?> refused) {
                for (Map.Entry<?, ?> e : refused.entrySet()) {
                    refusedAny = true;
                    CommandWedge.printFail("Self", e.getKey() + ": " + e.getValue());
                }
            }
            if (accepted.isEmpty()) return Exit.FAILURE;
            if (in.isSet("no-wait")) {
                CommandWedge.printOk(
                        "Self",
                        "started for " + String.join(", ", accepted) + "; `jk self setup-wsl --status`"
                                + " or the dashboard shows the progress");
                return refusedAny ? Exit.FAILURE : Exit.SUCCESS;
            }
            return follow(api, accepted) && !refusedAny ? Exit.SUCCESS : Exit.FAILURE;
        } catch (IOException e) {
            CommandWedge.printFail("Self", e.getMessage());
            return Exit.SOFTWARE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Exit.SOFTWARE;
        }
    }

    /** What {@link #follow} reports: a distro's new step, and its end. */
    interface Followed {
        void step(String distro, String step);

        void ended(String distro, boolean ok, String line);
    }

    /** Prints each distro's new steps until all of {@code distros} have ended; true when every one succeeded. */
    private static boolean follow(Api api, List<String> distros) throws IOException, InterruptedException {
        return follow(api, distros, new Followed() {
            @Override
            public void step(String distro, String step) {
                CliOutput.out("  " + distro + ": " + step);
            }

            @Override
            public void ended(String distro, boolean ok, String line) {
                if (ok) CommandWedge.printOk("Self", line);
                else CommandWedge.printFail("Self", line);
            }
        });
    }

    /** Follows {@code distros}' operations until each has ended; true when every one succeeded. */
    static boolean follow(Api api, List<String> distros, Followed followed) throws IOException, InterruptedException {
        Map<String, String> lastStep = new LinkedHashMap<>();
        Map<String, Boolean> ended = new LinkedHashMap<>();
        long deadline = Clock.SYSTEM.nanos() + WAIT.toNanos();
        while (ended.size() < distros.size() && Clock.SYSTEM.nanos() < deadline) {
            for (Map<?, ?> row : rows(api.get())) {
                String name = String.valueOf(row.get("name"));
                if (!distros.contains(name) || ended.containsKey(name)) continue;
                if (!(row.get("operation") instanceof Map<?, ?> op)) continue;
                String state = String.valueOf(op.get("state"));
                String step = String.valueOf(op.get("step"));
                if (state.equals("done") || state.equals("failed")) {
                    ended.put(name, state.equals("done"));
                    followed.ended(name, state.equals("done"), step);
                } else if (!step.equals(lastStep.put(name, step))) {
                    followed.step(name, step);
                }
            }
            if (ended.size() < distros.size()) Thread.sleep(POLL.toMillis());
        }
        if (ended.size() < distros.size()) {
            for (String d : distros) {
                if (!ended.containsKey(d)) {
                    followed.ended(
                            d, false, "still running after " + WAIT.toMinutes() + " minutes; the dashboard shows it");
                }
            }
            return false;
        }
        return !ended.containsValue(false);
    }

    /**
     * Installs JumpKick into {@code distro} through this home's engine and waits for it, each step to
     * {@code progress}: the setup decision's WSL row as {@code jk doctor --fix} applies it.
     */
    static MachineSetup.Result install(String distro, Consumer<String> progress) {
        try {
            Api api = Api.connect();
            HttpResponse<String> r = api.post("install", List.of(distro));
            if (!(parse(r.body()) instanceof Map<?, ?> answer)) {
                return new MachineSetup.Result("", false, "the engine answered " + r.statusCode());
            }
            if (answer.get("refused") instanceof Map<?, ?> refused && !refused.isEmpty()) {
                return new MachineSetup.Result(
                        "", false, distro + ": " + refused.values().iterator().next());
            }
            String[] last = {"install in " + distro + " did not report"};
            boolean ok = follow(
                    api, List.of(String.valueOf(strings(answer.get("accepted")).getFirst())), new Followed() {
                        @Override
                        public void step(String d, String step) {
                            progress.accept(step);
                        }

                        @Override
                        public void ended(String d, boolean done, String line) {
                            last[0] = line;
                        }
                    });
            return new MachineSetup.Result("", ok, last[0]);
        } catch (IOException | RuntimeException e) {
            return new MachineSetup.Result("", false, "could not install into " + distro + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new MachineSetup.Result("", false, "interrupted");
        }
    }

    private static int list(String body) {
        List<Map<?, ?>> rows = rows(body);
        if (parse(body) instanceof Map<?, ?> m && m.get("host") instanceof Map<?, ?> host) {
            Object installs = host.get("installs");
            CliOutput.out("JumpKick for Windows " + host.get("version")
                    + (installs == null ? "" : "; installs JumpKick " + installs + " into a distro"));
        }
        if (rows.isEmpty()) {
            CliOutput.out("No WSL distros.");
            return Exit.SUCCESS;
        }
        for (Map<?, ?> row : rows) CliOutput.out("  " + line(row));
        return Exit.SUCCESS;
    }

    /** One distro as a person reads it: name, WSL version, JumpKick state, pairing, operation. */
    static String line(Map<?, ?> row) {
        StringBuilder out = new StringBuilder(String.valueOf(row.get("name")));
        if (Boolean.TRUE.equals(row.get("default"))) out.append(" (default)");
        String state = String.valueOf(row.get("state"));
        out.append(": ")
                .append(
                        switch (state) {
                            case "installed" -> String.valueOf(row.get("jumpKick"));
                            case "not-installed" -> "no JumpKick";
                            case "wsl1" -> "WSL 1 (JumpKick needs WSL 2)";
                            default -> "stopped (not started to look)";
                        });
        if (row.get("engine") instanceof Map<?, ?> engine) {
            out.append(Boolean.TRUE.equals(engine.get("paired")) ? "; engine paired" : "; engine not paired");
        }
        if (row.get("operation") instanceof Map<?, ?> op) {
            out.append("; ")
                    .append(op.get("action"))
                    .append(' ')
                    .append(String.valueOf(op.get("state")).toLowerCase(Locale.ROOT))
                    .append(": ")
                    .append(op.get("step"));
        }
        return out.toString();
    }

    private static List<Map<?, ?>> rows(String body) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (parse(body) instanceof Map<?, ?> m && m.get("environments") instanceof List<?> list) {
            for (Object o : list) if (o instanceof Map<?, ?> row) out.add(row);
        }
        return out;
    }

    /** {@code body} parsed, or null when it is not JSON (a plain-text refusal). */
    private static @Nullable Object parse(String body) {
        try {
            return MiniJson.parse(body);
        } catch (IllegalArgumentException notJson) {
            return null;
        }
    }

    private static List<String> strings(@Nullable Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) for (Object e : list) out.add(String.valueOf(e));
        return out;
    }

    /** This home's engine's machine API, with the engine's token from its owner-only file. */
    record Api(HttpClient client, String base, String token, String epoch) {

        static Api connect() throws IOException {
            EnginePaths.Paths paths = EnginePaths.current();
            EngineClient.ensured(paths);
            EngineProbe.Status status =
                    EngineProbe.status(EnginePaths.activeSocket(paths)).orElse(null);
            if (status == null || status.httpUrl() == null || status.httpUrl().isBlank()) {
                throw new IOException("the engine's dashboard is not serving; `jk engine status` says why");
            }
            String token = Files.readString(paths.httpToken()).strip();
            String url = status.httpUrl();
            String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            HttpClient client = Http.loopbackClientBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            // Every /api call but the bootstrap reads names the engine it is for: its epoch, from its status.
            HttpRequest statusReq = HttpRequest.newBuilder(URI.create(base + "/api/status"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            String epoch;
            try {
                HttpResponse<String> r = client.send(statusReq, HttpResponse.BodyHandlers.ofString());
                epoch = parse(r.body()) instanceof Map<?, ?> m && m.get("engineEpoch") instanceof String e ? e : "";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            return new Api(client, base, token, epoch);
        }

        String get() throws IOException, InterruptedException {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/machine/environments"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .header("X-Jk-Engine-Epoch", epoch)
                    .GET()
                    .build();
            HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404)
                throw new IOException("this engine has no WSL setup; run `jk engine stop` and retry");
            if (r.statusCode() != 200)
                throw new IOException("the engine answered " + r.statusCode() + ": "
                        + r.body().strip());
            return r.body();
        }

        HttpResponse<String> post(String action, List<String> distros) throws IOException, InterruptedException {
            String body = JsonFields.object().array("distros", distros).finish();
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/machine/environments/" + action))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .header("X-Jk-Engine-Epoch", epoch)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return client.send(req, HttpResponse.BodyHandlers.ofString());
        }
    }
}

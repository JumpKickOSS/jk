// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.wire.protocol.ExecPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The addresses one {@code jk dev} session hands its processes: every member with a {@code ready}
 * URL — a sidecar or an app — is {@code JK_DEV_<NAME>_URL} in each other process's environment,
 * so a front end's dev config can find the API it proxies to without a port typed twice.
 */
final class DevUrls {

    private DevUrls() {}

    /** {@code JK_DEV_<NAME>_URL}: the name upper-cased, every other character an underscore. */
    static String key(String name) {
        return "JK_DEV_" + name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_") + "_URL";
    }

    /** Each name with a URL as its variable; {@code urls} maps a process name to its ready URL. */
    static Map<String, String> exports(Map<String, String> urls) {
        Map<String, String> out = new LinkedHashMap<>();
        urls.forEach((name, url) -> {
            if (!url.isEmpty()) out.put(key(name), url);
        });
        return out;
    }

    /** The ready URLs of {@code sidecars} by name, for {@link #exports}. */
    static Map<String, String> of(List<ExecPlan.Sidecar> sidecars) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ExecPlan.Sidecar s : sidecars) out.put(s.name(), s.probe().ready());
        return out;
    }

    /** What process {@code name} gets: every export but its own. */
    static Map<String, String> forApp(String name, Map<String, String> exports) {
        Map<String, String> out = new LinkedHashMap<>(exports);
        out.remove(key(name));
        return out;
    }

    /** {@code sidecars}, each with every export but its own laid over its env. */
    static List<ExecPlan.Sidecar> withExports(List<ExecPlan.Sidecar> sidecars, Map<String, String> exports) {
        List<ExecPlan.Sidecar> out = new ArrayList<>();
        for (ExecPlan.Sidecar s : sidecars) {
            Map<String, String> env = new LinkedHashMap<>(forApp(s.name(), exports));
            env.putAll(s.env());
            out.add(new ExecPlan.Sidecar(s.name(), s.command(), s.cwd(), env, s.probe(), s.frontDoor(), s.restart()));
        }
        return out;
    }
}

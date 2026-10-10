// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.intellij;

import cc.jumpkick.host.Os;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An IntelliJ distribution's {@code product-info.json}: its build number, the JVM flags its own
 * launcher starts the IDE with on this host ({@code launch[].additionalJvmArguments}, the IDE home
 * placeholder — {@code %IDE_HOME%}, {@code $IDE_HOME}, {@code $APP_PACKAGE} — replaced by the
 * distribution's directory), and its {@code layout}: where each bundled plugin's and each module's
 * jars lie, relative to that directory.
 */
record ProductInfo(
        String buildNumber, List<String> jvmArgs, Map<String, List<String>> plugins, Map<String, String> modules) {

    /** Read {@code ide}'s {@code product-info.json} for the launch entry of {@code os}/{@code arch}. */
    static ProductInfo read(Path ide, String os, String arch) throws IOException {
        Path file = ide.resolve("product-info.json");
        if (!Files.isRegularFile(file)) {
            throw new IOException(ide + " is not an IntelliJ distribution: no product-info.json");
        }
        Object root = MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        String build = MiniJson.str(root, "buildNumber");
        if (build == null) throw new IOException(file + " names no buildNumber");
        Object launch = null;
        for (Object entry : MiniJson.list(root, "launch")) {
            if (os.equals(MiniJson.str(entry, "os")) && arch.equals(MiniJson.str(entry, "arch"))) {
                launch = entry;
                break;
            }
        }
        if (launch == null) {
            throw new IOException(file + " has no launch entry for " + os + "/" + arch);
        }
        String home = ide.toAbsolutePath().normalize().toString();
        List<String> args = new ArrayList<>();
        for (Object arg : MiniJson.list(launch, "additionalJvmArguments")) {
            String flag = String.valueOf(arg);
            // The splash screen is the launcher's; a test JVM has no window to show it in.
            if (flag.startsWith("-Dsplash=")) continue;
            args.add(flag.replace("%IDE_HOME%", home).replace("$IDE_HOME", home).replace("$APP_PACKAGE", home));
        }
        Map<String, List<String>> plugins = new LinkedHashMap<>();
        Map<String, String> modules = new LinkedHashMap<>();
        for (Object entry : MiniJson.list(root, "layout")) {
            String name = MiniJson.str(entry, "name");
            String kind = MiniJson.str(entry, "kind");
            List<String> classPath = new ArrayList<>();
            for (Object jar : MiniJson.list(entry, "classPath")) classPath.add(String.valueOf(jar));
            if (name == null || kind == null || classPath.isEmpty()) continue;
            if (kind.equals("plugin")) plugins.put(name, List.copyOf(classPath));
            else if (kind.equals("moduleV2") || kind.equals("productModuleV2")) modules.put(name, classPath.getFirst());
        }
        return new ProductInfo(build, List.copyOf(args), plugins, modules);
    }

    /** The directory under {@code plugins/} that holds plugin {@code id}, or null for the platform itself. */
    @Nullable
    String pluginDir(String id) {
        List<String> classPath = plugins.get(id);
        return classPath == null ? null : directoryOf(classPath.getFirst());
    }

    /** The plugin whose directory under {@code plugins/} is {@code dir}, or null. */
    @Nullable
    String pluginIn(String dir) {
        for (String id : plugins.keySet()) {
            if (dir.equals(pluginDir(id))) return id;
        }
        return null;
    }

    /** {@code plugins/<dir>/…} → {@code dir}; anything else (the platform's {@code lib/}) → null. */
    static @Nullable String directoryOf(String jar) {
        if (!jar.startsWith("plugins/")) return null;
        int slash = jar.indexOf('/', "plugins/".length());
        return slash < 0 ? null : jar.substring("plugins/".length(), slash);
    }

    /** {@code product-info.json}'s name for this JVM's OS: {@code Windows}, {@code macOS} or {@code Linux}. */
    static String hostOs() {
        if (Os.isWindows()) return "Windows";
        if (Os.isDarwin()) return "macOS";
        return "Linux";
    }

    /** {@code product-info.json}'s name for this JVM's architecture: {@code amd64} or {@code aarch64}. */
    static String hostArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : "amd64";
    }

    /**
     * The branch of an IntelliJ build number — {@code 262} of {@code 262.10968.63} — or null when
     * {@code version} is not a build number (a marketing version such as {@code 2026.2.3}).
     */
    static @Nullable String branch(String version) {
        if (!version.matches("\\d{3}(\\.\\d+)+")) return null;
        return version.substring(0, version.indexOf('.'));
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.protobuf;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code protoc} step: fork the fetched protoc binary over every {@code .proto} under the
 * configured source dir ({@code [protobuf] src}, default {@code proto/}) that no {@code exclude}
 * glob names, generating Java into the {@code gen} output (contributed to the compiler's source
 * set). {@code lite = true} emits the lite-runtime variant (the Android/datastore posture — pairs
 * with protobuf-javalite).
 *
 * <p>Each {@code [protobuf.<id>]} entry is one protoc plugin: its fetched executable is named to
 * protoc as {@code protoc-gen-<id>} and its {@code --<id>_out} lands in the same {@code gen}, the
 * entry's {@code options} comma-joined ahead of the dir the way protoc reads a plugin parameter.
 * The include path is the module's own proto root, then the proto root of every workspace sibling
 * the module depends on (a proto imports a sibling's by bare name, as under Maven where the
 * sibling's jar carries its protos), then the protos the dependency jars carry ({@link
 * DependencyProtos}).
 *
 * <p>protoc and its plugins publish to Maven as bare native binaries (no jar wrapper), so a fetched
 * file arrives without the executable bit — it is staged into scratch and chmod +x'd before the fork.
 */
final class ProtocStep {

    private ProtocStep() {}

    /**
     * The module-relative proto roots {@code [protobuf] src} names — one string or a list, {@code
     * proto} when absent — in declared order.
     */
    static List<String> roots(PluginConfig config) {
        Object value = config.values().get("src");
        if (value instanceof String one) return one.isBlank() ? List.of("proto") : List.of(one);
        List<String> list = config.stringList("src");
        return list.isEmpty() ? List.of("proto") : list;
    }

    /**
     * The {@code [protobuf] exclude} globs as matchers over a proto's path relative to its root.
     * {@code *legacy.proto} names files of the root itself, {@code **&#47;*legacy.proto} those of
     * every directory, the root included, as Maven's {@code <excludes>} read them.
     */
    static List<PathMatcher> excluded(PluginConfig config, FileSystem fs) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String glob : config.stringList("exclude")) {
            if (glob.isBlank()) continue;
            matchers.add(fs.getPathMatcher("glob:" + glob));
            if (glob.startsWith("**/")) matchers.add(fs.getPathMatcher("glob:" + glob.substring(3)));
        }
        return matchers;
    }

    static void run(TaskExec exec) throws Exception {
        List<Path> protoDirs = new ArrayList<>();
        List<Path> protos = new ArrayList<>();
        List<PathMatcher> excluded = excluded(exec.config(), exec.moduleDir().getFileSystem());
        for (String root : roots(exec.config())) {
            Path protoDir = exec.moduleDir().resolve(root);
            protoDirs.add(protoDir);
            protos.addAll(protoFiles(protoDir, excluded));
        }
        Path gen = exec.outputDir("gen");
        if (protos.isEmpty()) {
            return; // empty/missing proto dirs are a no-op, not an error — gen stays empty
        }
        boolean lite = exec.config().bool("lite", false);
        Path protoc = executable(exec, "protoc");
        exec.label("protoc (" + protos.size() + (protos.size() == 1 ? " file)" : " files)"));
        TaskExec.ToolRun run = exec.tool(protoc)
                .arg("--java_out=" + (lite ? "lite:" : "") + gen.toAbsolutePath())
                .cwd(exec.moduleDir());
        for (Path protoDir : protoDirs) {
            run.arg("-I").arg(protoDir.toAbsolutePath().toString());
        }
        for (Path sibling : exec.siblingFiles("src")) {
            if (Files.isDirectory(sibling))
                run.arg("-I").arg(sibling.toAbsolutePath().toString());
        }
        for (Path include : DependencyProtos.includeRoots(exec)) {
            run.arg("-I").arg(include.toAbsolutePath().toString());
        }
        if (exec.config().bool("kotlin", false)) {
            // The Kotlin DSL wraps the Java codegen (both land in gen; the engine's suffix
            // unions route .java to javac and .kt to kotlinc).
            run.arg("--kotlin_out=" + (lite ? "lite:" : "") + gen.toAbsolutePath());
        }
        for (Map.Entry<String, Map<String, Object>> entry :
                exec.config().entries().entrySet()) {
            String id = entry.getKey();
            Path plugin = executable(exec, "protoc-gen-" + id);
            run.arg("--plugin=protoc-gen-" + id + "=" + plugin.toAbsolutePath());
            run.arg("--" + id + "_out=" + parameter(entry.getValue()) + gen.toAbsolutePath());
        }
        for (Path proto : protos) {
            run.arg(proto.toAbsolutePath().toString());
        }
        TaskExec.ToolRun.Result result = run.run();
        if (result.exit() != 0) {
            throw new IllegalStateException("protoc failed (exit " + result.exit() + "):\n" + result.output());
        }
    }

    /** An entry's {@code options} as protoc's plugin parameter prefix: {@code a,b:} or nothing. */
    private static String parameter(Map<String, Object> entry) {
        if (!(entry.get("options") instanceof List<?> options) || options.isEmpty()) return "";
        List<String> items = new ArrayList<>(options.size());
        for (Object option : options) items.add(String.valueOf(option));
        return String.join(",", items) + ":";
    }

    /**
     * Stage the fetched binary named {@code artifact} into scratch with the executable bit set
     * (cache files are read-only), under its own name so protoc's plugin lookup sees it.
     */
    private static Path executable(TaskExec exec, String artifact) throws IOException {
        Path fetched = exec.requireExtra(artifact);
        boolean windows = Os.isWindows();
        Path staged = Files.createDirectories(exec.scratch().resolve("tools"))
                .resolve(windows ? artifact + ".exe" : artifact);
        Files.copy(fetched, staged, StandardCopyOption.REPLACE_EXISTING);
        if (!staged.toFile().setExecutable(true) && !windows) {
            throw new IOException("cannot mark " + artifact + " executable: " + staged);
        }
        return staged;
    }

    /** Every {@code .proto} under {@code protoDir}, sorted, minus those an {@code excluded} glob names. */
    private static List<Path> protoFiles(Path protoDir, List<PathMatcher> excluded) throws IOException {
        List<Path> protos = new ArrayList<>();
        PathUtil.forEachRegularFile(protoDir, (file, attrs) -> {
            if (!file.getFileName().toString().endsWith(".proto")) return;
            Path relative = protoDir.relativize(file);
            for (PathMatcher matcher : excluded) if (matcher.matches(relative)) return;
            protos.add(file);
        });
        protos.sort(null);
        return protos;
    }
}

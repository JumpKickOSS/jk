// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.protobuf;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code protoc} step: fork the fetched protoc binary over every {@code .proto} under the
 * configured source dir ({@code [protobuf] src}, default {@code proto/}), generating Java into
 * the {@code gen} output (contributed to the compiler's source set). {@code lite = true} emits
 * the lite-runtime variant (the Android/datastore posture — pairs with protobuf-javalite).
 *
 * <p>Each {@code [protobuf.<id>]} entry is one protoc plugin: its fetched executable is named to
 * protoc as {@code protoc-gen-<id>} and its {@code --<id>_out} lands in the same {@code gen}, the
 * entry's {@code options} comma-joined ahead of the dir the way protoc reads a plugin parameter.
 * The protos the module's dependency jars carry are include roots ({@link DependencyProtos}).
 *
 * <p>protoc and its plugins publish to Maven as bare native binaries (no jar wrapper), so a fetched
 * file arrives without the executable bit — it is staged into scratch and chmod +x'd before the fork.
 */
final class ProtocStep {

    private ProtocStep() {}

    static void run(TaskExec exec) throws Exception {
        String src = exec.config().stringOpt("src").orElse("proto");
        Path protoDir = exec.moduleDir().resolve(src);
        Path gen = exec.outputDir("gen");
        List<Path> protos = protoFiles(protoDir);
        if (protos.isEmpty()) {
            return; // an empty/missing proto dir is a no-op, not an error — gen stays empty
        }
        boolean lite = exec.config().bool("lite", false);
        Path protoc = executable(exec, "protoc");
        exec.label("protoc (" + protos.size() + (protos.size() == 1 ? " file)" : " files)"));
        TaskExec.ToolRun run = exec.tool(protoc)
                .arg("--java_out=" + (lite ? "lite:" : "") + gen.toAbsolutePath())
                .arg("-I")
                .arg(protoDir.toAbsolutePath().toString())
                .cwd(exec.moduleDir());
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

    private static List<Path> protoFiles(Path protoDir) throws IOException {
        List<Path> protos = new ArrayList<>();
        PathUtil.forEachRegularFile(protoDir, (file, attrs) -> {
            if (file.getFileName().toString().endsWith(".proto")) protos.add(file);
        });
        protos.sort(null);
        return protos;
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
}

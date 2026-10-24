// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.intellij;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code intellij-test-env} step: what a test JVM needs to be a headless IDE. Its flags start
 * from the distribution's own launcher flags ({@link ProductInfo}) and point the IDE at a sandbox
 * under {@code target/}, loading the module's {@code plugin.xml} from the test classpath as the
 * platform's own test runs do. Its classpath gains every bundled plugin the named ones require
 * ({@link PluginClosure}), which the IDE refuses to load them without.
 *
 * <p>The sandbox paths are relative: the test JVM starts in the module directory, so a replay of
 * this step in another checkout of the module still names that checkout's sandbox.
 */
final class TestEnvironment {

    /** The sandbox root, relative to the module directory. */
    static final String SANDBOX = "target/intellij-sandbox";

    private TestEnvironment() {}

    static void run(TaskExec exec) throws Exception {
        Path ide = exec.requireExtra(IntellijPlugin.IDE);
        String version = exec.config().string("version");
        ProductInfo info = ProductInfo.read(ide, ProductInfo.hostOs(), ProductInfo.hostArch());
        exec.label("IntelliJ " + info.buildNumber() + " test JVM");
        Path out = exec.outputDir(IntellijPlugin.TEST_ENV_DIR);
        Files.write(out.resolve("jvm.args"), jvmArgs(info, SANDBOX + "/" + version), StandardCharsets.UTF_8);
        List<String> named = new ArrayList<>();
        for (String dir : exec.config().stringList("plugins")) {
            String id = info.pluginIn(dir);
            if (id == null) {
                throw new IOException("[intellij] plugins names `" + dir + "`, which is no bundled plugin of "
                        + info.buildNumber() + " — a directory under " + ide.resolve("plugins"));
            }
            named.add(id);
        }
        List<String> closure = PluginClosure.of(ide, info, named);
        List<String> absent = new ArrayList<>();
        for (String id : closure) {
            String dir = info.pluginDir(id);
            if (dir != null && !Files.isDirectory(ide.resolve("plugins").resolve(dir))) absent.add(dir);
        }
        if (!absent.isEmpty()) {
            throw new IOException("the IDE needs bundled plugins that were not unpacked: add " + absent
                    + " to [intellij] requires (a plugin's own dependencies may then name more)");
        }
        List<String> classpath = new ArrayList<>();
        for (String id : closure) {
            if (named.contains(id)) continue; // already provided, with the compile classpath
            for (Path jar : pluginJars(ide.resolve("plugins").resolve(String.valueOf(info.pluginDir(id))))) {
                classpath.add(jar.toAbsolutePath().normalize().toString());
            }
        }
        Files.write(out.resolve("classpath"), classpath, StandardCharsets.UTF_8);
    }

    /** {@code info}'s launcher flags, then the sandbox and class-loading flags of a test run. */
    static List<String> jvmArgs(ProductInfo info, String sandbox) {
        List<String> args = new ArrayList<>(info.jvmArgs());
        args.add("-Didea.config.path=" + sandbox + "/config-test");
        args.add("-Didea.system.path=" + sandbox + "/system-test");
        args.add("-Didea.plugins.path=" + sandbox + "/plugins-test");
        args.add("-Didea.log.path=" + sandbox + "/log-test");
        // The module's plugin.xml is read off the classpath with the platform's, in one class loader.
        args.add("-Didea.classpath.index.enabled=false");
        args.add("-Didea.force.use.core.classloader=true");
        args.add("-Didea.use.core.classloader.for.plugin.path=true");
        args.add("-Djava.awt.headless=true");
        return args;
    }

    /** A bundled plugin's jars: {@code lib/*.jar} and its content modules' {@code lib/modules/*.jar}, path-sorted. */
    static List<Path> pluginJars(Path pluginDir) throws IOException {
        List<Path> jars = new ArrayList<>();
        Path lib = pluginDir.resolve("lib");
        if (!Files.isDirectory(lib)) return jars;
        PathUtil.forEachRegularFile(lib, (file, attrs) -> {
            Path rel = lib.relativize(file);
            boolean direct = rel.getNameCount() == 1;
            boolean module =
                    rel.getNameCount() == 2 && rel.getName(0).toString().equals("modules");
            if ((direct || module) && file.getFileName().toString().endsWith(".jar")) jars.add(file);
        });
        jars.sort(null);
        return jars;
    }
}

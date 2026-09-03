// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.process;

import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.jspecify.annotations.Nullable;

/**
 * The one entry point every out-of-process plugin jar declares as its {@code Main-Class}. Replaces
 * the bespoke {@code main()} each runner used to hand-roll (arg checks, spec read, JSONL escaping,
 * exit codes): this loads the jar's {@link Plugin} via {@link ServiceLoader}, builds the {@link
 * ProtocolWriter} from its manifest, and bridges stdio to {@link Plugin#run}.
 *
 * <p>Exit codes come from {@link Exit}, the same vocabulary the engine and CLI use: the plugin's
 * own return value on success; {@link Exit#SOFTWARE} when no plugin can be selected (the jar is
 * built wrong — nothing the user can act on); {@link Exit#FAILURE} when {@code run} throws.
 */
public final class PluginMain {

    private PluginMain() {}

    public static void main(String[] args) {

        List<Plugin> plugins = new ArrayList<>();
        for (Plugin p : ServiceLoader.load(Plugin.class)) {
            plugins.add(p);
        }
        if (plugins.isEmpty()) {
            System.err.println("jk-plugin-host: no cc.jumpkick.plugin.Plugin found on the classpath");
            System.exit(Exit.SOFTWARE);
            return;
        }
        @Nullable Plugin plugin = select(plugins);
        if (plugin == null) {
            System.exit(Exit.SOFTWARE);
            return;
        }

        // Dedicated UTF-8 stdout for the protocol stream (the tool's own stdout
        // chatter still flows through and is treated as passthrough by the host).
        // Buffered, then flushed per protocol line by ProtocolWriter. The buffer is not there to
        // batch lines — it cannot be, see ProtocolWriter's javadoc — but to make each line one
        // write(2) instead of however many PrintStream's encoder happens to emit for it.
        PrintStream out = new PrintStream(
                new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 8192),
                /* autoFlush */ false,
                StandardCharsets.UTF_8);
        ProtocolWriter writer = new ProtocolWriter(out, plugin.manifest().protocolPrefix());

        try {
            System.exit(plugin.run(List.of(args), writer));
        } catch (Exception e) {
            System.err.println(plugin.manifest().id() + ": " + e.getMessage());
            System.exit(Exit.FAILURE);
        }
    }

    /**
     * Pick the plugin to run. A worker jar bundles exactly one {@link Plugin}, so the common case is
     * unambiguous. But the test runner is launched with the module-under-test on its classpath so it
     * can discover that module's tests — and when that module is itself a plugin (e.g. {@code jk
     * test} on jk's own {@code kotlin-compiler}), {@link ServiceLoader} finds two: the runner and the
     * module's own plugin. The launcher resolves this explicitly by naming the intended plugin in the
     * {@code jk.plugin.class} system property; we honor it when set. Absent the property, exactly one
     * plugin is required. Returns {@code null} (after a diagnostic) when no selection can be made —
     * code-source matching is deliberately avoided: when the module-under-test depends on {@code
     * plugin-api}, this class loads from the plain plugin-api jar, whose code source bundles no
     * plugin at all.
     */
    static @Nullable Plugin select(List<Plugin> plugins) {
        @Nullable String wanted = System.getProperty("jk.plugin.class");
        if (wanted != null && !wanted.isBlank()) {
            for (Plugin p : plugins) {
                if (wanted.equals(p.getClass().getName())) return p;
            }
            System.err.println(
                    "jk-plugin-host: requested plugin " + wanted + " not found among " + classNames(plugins));
            return null;
        }
        // A worker lib dir may carry a sibling plugin jar as a plain dependency (grails ships the
        // spring-boot plugin for its Boot packaging), so ServiceLoader can see both. The engine
        // names the intended plugin by its protocol prefix — stable on both sides of the fork.
        @Nullable String prefix = System.getProperty("jk.plugin.prefix");
        if (prefix != null && !prefix.isBlank()) {
            for (Plugin p : plugins) {
                if (prefix.equals(p.manifest().protocolPrefix())) return p;
            }
            System.err.println(
                    "jk-plugin-host: no plugin with protocol prefix " + prefix + " among " + classNames(plugins));
            return null;
        }
        if (plugins.size() == 1) return plugins.get(0);
        System.err.println("jk-plugin-host: expected exactly one Plugin, found "
                + plugins.size()
                + " "
                + classNames(plugins)
                + " — set -Djk.plugin.class to choose");
        return null;
    }

    private static String classNames(List<Plugin> plugins) {
        StringBuilder sb = new StringBuilder("[");
        for (Plugin p : plugins) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(p.getClass().getName());
        }
        return sb.append(']').toString();
    }
}

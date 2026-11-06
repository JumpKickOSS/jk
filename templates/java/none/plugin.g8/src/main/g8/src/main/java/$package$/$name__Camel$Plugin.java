package $package$;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildPlugin;
import cc.jumpkick.plugin.build.BuildPluginContext;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.PluginCommandSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * The code layer of the {@code [$name$]} plugin. jk forks the jar as a worker, finds this class
 * through {@code META-INF/services/cc.jumpkick.plugin.Plugin}, and the SDK's harness answers the
 * protocol. {@link #register} is where the plugin adds its steps, packagers and commands.
 */
public final class $name;format="Camel"$Plugin implements Plugin, BuildPlugin {

    /** The id jk-plugin.toml declares, and the command this plugin adds. */
    static final String ID = "$name$";

    /** The worker's protocol-line prefix: jk-plugin.toml's [code] protocol-prefix. */
    static final String PROTOCOL_PREFIX = "##$name;format="upper,word"$:";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(ID, PROTOCOL_PREFIX);
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    /** One command, {@code jk $name$}, that prints the table's greeting. */
    @Override
    public void register(BuildPluginContext ctx) {
        ctx.command(PluginCommandSpec.named(ID)
                .description("Print the configured greeting")
                .run(exec -> {
                    exec.out(greeting(exec.config()));
                    return 0;
                }));
    }

    /** The line {@code jk $name$} prints, from the validated {@code [$name$]} table. */
    static String greeting(PluginConfig config) {
        return config.string("greeting") + " from the " + ID + " plugin";
    }
}

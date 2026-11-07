// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.helidon;

import cc.jumpkick.plugin.PluginConfig;
import java.util.Optional;

/**
 * The {@code [helidon]} table, its schema defaults applied by the engine.
 *
 * @param version the Helidon release the platform BOM and the processors are locked at
 * @param binding whether to generate the application binding; empty when unset, which means on
 *     when a class carries {@code @Service.GenerateBinding}
 * @param generateMain whether the binding step also generates {@code ApplicationMain}, the
 *     reflection-free entry point
 * @param validate whether the binding step fails the build on an invalid injection graph
 * @param testBinding whether a step generates {@code TestApplicationBinding} for the test scope
 * @param layout what the module's deployable is
 * @param declarative whether the module uses Helidon Declarative, whose code generators run
 */
public record HelidonSettings(
        String version,
        Optional<Boolean> binding,
        boolean generateMain,
        boolean validate,
        boolean testBinding,
        Layout layout,
        boolean declarative) {

    /** {@code layout}: Helidon's {@code <name>.jar} beside {@code libs/}, or the plain jar alone. */
    public enum Layout {
        LIBS,
        JAR
    }

    /** The settings {@code config} (a validated {@code [helidon]} table) holds. */
    public static HelidonSettings of(PluginConfig config) {
        return new HelidonSettings(
                config.string("version"),
                config.bool("binding"),
                config.bool("generate-main", false),
                config.bool("validate", true),
                config.bool("test-binding", false),
                "jar".equals(config.stringOpt("layout").orElse("libs")) ? Layout.JAR : Layout.LIBS,
                config.bool("declarative", false));
    }

    /**
     * Whether the binding step is registered: unless {@code binding = false}, since an unset
     * {@code binding} is decided by the compiled classes, and always for {@code generate-main}.
     */
    public boolean bindingStep() {
        return binding.orElse(true) || generateMain;
    }
}

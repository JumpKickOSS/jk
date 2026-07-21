// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin;

/**
 * The published version of {@code cc.jumpkick:jk-plugin-sdk} — the stable surface third-party build
 * plugins compile against. Deliberately <b>independent</b> of {@code JkVersion.VERSION}: the SDK's
 * API can freeze (and promise compatibility) on a different cadence than jk's release train. Still
 * {@code 0.x} while the plugin SPI evolves; it moves to {@code 1.0.0} when the surface is frozen.
 *
 * <p>Single source of truth: the Gradle build ({@code shared/plugin-sdk/build.gradle.kts}) mirrors
 * this literal as the artifact {@code version}, and the {@code jk new --plugin} scaffold reads it to
 * render the generated project's {@code jk-plugin-sdk} dependency. Keep the three in sync.
 */
public final class PluginSdkVersion {
    private PluginSdkVersion() {}

    /** The {@code cc.jumpkick:jk-plugin-sdk} artifact version. */
    public static final String VERSION = "0.1.0";
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * An installed plugin with a code layer, active on this project (owns a declared table); the
 * module's set is {@link ActivePlugins#of}. {@code declaration} is the matching {@code [plugins]}
 * entry for third-party plugins and null for built-ins — it carries the coordinate the trust gate
 * and jar lookup key on.
 */
public record ActivePlugin(
        PluginDescriptor manifest,
        PluginConfig config,
        Path moduleDir,
        @Nullable PluginDeclaration declaration) {}

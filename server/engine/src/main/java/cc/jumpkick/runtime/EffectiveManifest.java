// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.VariantApply;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The manifest a module's plan, its step bodies and its forecast all read: the parsed {@code
 * jk.toml} with the request's packaging override ({@code --fat} / {@code -U}) and its selected
 * {@code [variants]} folded in, so describe keys, contribution predicates and step keys see one flat
 * config.
 *
 * @param secrets the resolved secret values the variant overlay named, for the package spec
 */
public record EffectiveManifest(JkBuild build, Map<String, String> secrets) {

    /**
     * What selects the effective manifest beside the parsed one: the session's packaging override,
     * the variant selection and the client environment {@code env:} values resolve against.
     *
     * @param variant the raw {@code --variant} selection; blank selects the defaults
     */
    public record Selection(
            Session session,
            @Nullable String variant,
            @Nullable Map<String, String> clientEnv) {

        /** The selection a module's plan is built under. */
        public static Selection of(BuildPlanner.Inputs in) {
            return new Selection(in.session(), in.variant(), in.clientEnv());
        }

        /** The ambient session's own, for a reader with no plan inputs. */
        static Selection ambient() {
            Session session = SessionContext.current();
            return new Selection(session, session.variant(), session.clientEnv());
        }
    }

    /**
     * {@code parsed} under {@code selection}. Throws when the selection does not apply to the module
     * (an unselected mandatory dimension, an undeclared value) or an {@code env:} value is missing.
     */
    static EffectiveManifest of(JkBuild parsed, Path dir, Selection selection) {
        JkBuild build = BuildPlanner.applyAssemblyOverride(parsed, selection.session());
        var applied = VariantApply.apply(
                build, dir, VariantApply.selection(selection.variant(), selection.session()), selection.clientEnv());
        return new EffectiveManifest(applied.build(), applied.secrets());
    }
}

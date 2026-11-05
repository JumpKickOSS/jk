// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Gives every test method in the class, or the one method annotated, a jk home of its own: its own
 * {@code config.toml}, store, cache and state, and so its own engine.
 *
 * <p>The tier's {@code JK_HOME} is shared by every class of the module, so a {@code config.toml}
 * written there — a mirror, a repository with a secret reference, a proxy — would reach every
 * other test that resolves. Under this annotation a test writes {@link
 * cc.jumpkick.util.JkDirs#userConfigFile()} freely: the file, and every byte the resolve stores,
 * goes away with the method.
 *
 * <p>The home holds nothing the developer's {@code ~/.jk} or the sandbox's pooled store does. A
 * {@code jk.env.JK_HOME} overlay takes its store, cache and state from the overlay alone ({@link
 * cc.jumpkick.util.JkDirs}), so neither the shell's {@code JK_STORE_DIR} nor the tier's reaches
 * in. The home's {@code config.toml} starts with {@code [m2] integration = false}: a repository at
 * Central's URL then stores under the home, where it would otherwise use the Maven local
 * repository, which the tier points at the pooled Central tree every sandbox on the host shares.
 * Maven's {@code settings.xml} is pointed at a file that does not exist, so the developer's mirrors
 * and proxies stay out. Its {@code [templates] official} is a local bare repository built from the
 * checkout's {@code templates/}, so the home's engine clones its catalog without the network; a test
 * about the real catalog's origin says so with {@link #officialCatalogFromOrigin}.
 *
 * <p>Mechanics: sets the {@code jk.env.JK_HOME} and {@code jk.env.JK_M2_SETTINGS} overlays to the
 * per-method directory, materializes the engine assembly under it, and stops that home's engine
 * after the method before the overlays are cleared and the directory deleted.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(IsolatedRootsExtension.class)
public @interface IsolatedHome {

    /**
     * Leave {@code [templates] official} unset, so the home's engine clones the official catalog
     * from its origin on github.com. On a method, wins over the class's annotation.
     */
    boolean officialCatalogFromOrigin() default false;
}

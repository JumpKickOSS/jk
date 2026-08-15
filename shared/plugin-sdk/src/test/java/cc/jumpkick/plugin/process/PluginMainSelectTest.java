// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Two plugins on one worker classpath must be selectable by protocol prefix. */
class PluginMainSelectTest {

    private record Fake(PluginManifest manifest) implements Plugin {
        @Override
        public int run(List<String> args, cc.jumpkick.plugin.protocol.ProtocolWriter writer) {
            return 0;
        }
    }

    private final Plugin grails = new Fake(new PluginManifest("jk-grails", "##JKGR:"));
    private final Plugin boot = new Fake(new PluginManifest("jk-spring-boot", "##JKSB:"));

    /**
     * Drop engine-supplied selectors too. Under {@code jk test}/{@code jk build} the forked test
     * JVM may carry {@code -Djk.plugin.prefix}/{@code .class} from the worker launcher; those
     * would make {@link PluginMain#select} filter against this test's Fake list and return null.
     */
    @BeforeEach
    @AfterEach
    void clearProps() {
        System.clearProperty("jk.plugin.prefix");
        System.clearProperty("jk.plugin.class");
    }

    @Test
    void prefix_property_disambiguates_two_plugins() {
        System.setProperty("jk.plugin.prefix", "##JKGR:");
        assertEquals(grails, PluginMain.select(List.of(grails, boot)));
        System.setProperty("jk.plugin.prefix", "##JKSB:");
        assertEquals(boot, PluginMain.select(List.of(grails, boot)));
    }

    @Test
    void unknown_prefix_refuses_instead_of_guessing() {
        System.setProperty("jk.plugin.prefix", "##NOPE:");
        assertNull(PluginMain.select(List.of(grails, boot)));
    }

    @Test
    void single_plugin_needs_no_property() {
        assertEquals(grails, PluginMain.select(List.of(grails)));
    }

    @Test
    void two_plugins_without_selector_still_refuse() {
        assertNull(PluginMain.select(List.of(grails, boot)));
    }

    @Test
    void class_property_wins_over_prefix() {
        System.setProperty("jk.plugin.class", boot.getClass().getName());
        System.setProperty("jk.plugin.prefix", "##JKGR:");
        // Both Fakes share a class — class selection returns the first match.
        assertEquals(grails, PluginMain.select(List.of(grails, boot)));
    }
}

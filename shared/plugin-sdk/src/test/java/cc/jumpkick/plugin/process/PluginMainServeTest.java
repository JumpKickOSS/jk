// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginMainServeTest {

    /** Answers each spec's file name as its exit code; {@code boom} throws, {@code last} retires. */
    private static final class Counting implements Plugin {
        final List<String> specs = new ArrayList<>();
        boolean retire;

        @Override
        public PluginManifest manifest() {
            return new PluginManifest("jk-test", "##T:");
        }

        @Override
        public int run(List<String> args, ProtocolWriter out) {
            String spec = args.get(0);
            specs.add(spec);
            if (spec.equals("boom")) throw new IllegalStateException("boom");
            if (spec.equals("last")) retire = true;
            out.emit("{\"t\":\"saw\",\"spec\":\"" + spec + "\"}");
            return spec.length();
        }

        @Override
        public boolean reusable() {
            return !retire;
        }
    }

    private static List<String> serve(Counting plugin, String input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int exit =
                PluginMain.serve(plugin, new ProtocolWriter(ps, "##T:"), new BufferedReader(new StringReader(input)));
        assertThat(exit).isEqualTo(Exit.SUCCESS);
        return bytes.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    void each_run_is_answered_with_its_exit_and_the_worker_waits_for_the_next() throws IOException {
        Counting plugin = new Counting();
        List<String> out = serve(plugin, "RUN ab\nRUN abc\nDONE\nRUN never\n");
        assertThat(plugin.specs).containsExactly("ab", "abc");
        assertThat(out)
                .containsExactly(
                        "##T:{\"t\":\"ready\"}",
                        "##T:{\"t\":\"saw\",\"spec\":\"ab\"}",
                        "##T:{\"t\":\"exited\",\"code\":2,\"retire\":false}",
                        "##T:{\"t\":\"ready\"}",
                        "##T:{\"t\":\"saw\",\"spec\":\"abc\"}",
                        "##T:{\"t\":\"exited\",\"code\":3,\"retire\":false}",
                        "##T:{\"t\":\"ready\"}");
    }

    @Test
    void a_run_that_throws_fails_alone_and_the_worker_stays_up() throws IOException {
        Counting plugin = new Counting();
        List<String> out = serve(plugin, "RUN boom\nRUN ok\n");
        assertThat(plugin.specs).containsExactly("boom", "ok");
        assertThat(out).contains("##T:{\"t\":\"exited\",\"code\":" + Exit.FAILURE + ",\"retire\":false}");
        assertThat(out).contains("##T:{\"t\":\"exited\",\"code\":2,\"retire\":false}");
    }

    @Test
    void a_plugin_that_is_no_longer_reusable_retires_after_its_answer() throws IOException {
        Counting plugin = new Counting();
        List<String> out = serve(plugin, "RUN last\nRUN after\n");
        assertThat(plugin.specs).containsExactly("last");
        assertThat(out.get(out.size() - 1)).isEqualTo("##T:{\"t\":\"exited\",\"code\":4,\"retire\":true}");
    }

    @Test
    void an_unknown_command_is_refused_and_the_worker_reads_on() throws IOException {
        Counting plugin = new Counting();
        List<String> out = serve(plugin, "COMPILE x\nRUN ab\n");
        assertThat(plugin.specs).containsExactly("ab");
        assertThat(out.get(1)).contains("\"t\":\"error\"").contains("COMPILE x");
    }
}

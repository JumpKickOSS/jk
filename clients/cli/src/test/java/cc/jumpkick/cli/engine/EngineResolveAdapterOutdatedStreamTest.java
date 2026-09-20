// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.wire.protocol.EngineWireException;
import cc.jumpkick.wire.protocol.OutdatedReport;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import cc.jumpkick.wire.protocol.ProtoReads;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The outdated read is an inline stream: every beat reaches the handler in order, the ack is the
 * terminal, and an engine error line surfaces as the engine's message rather than a disconnect.
 */
class EngineResolveAdapterOutdatedStreamTest {

    private static BufferedReader stream(String... lines) {
        return new BufferedReader(new StringReader(String.join("\n", lines) + "\n"));
    }

    @Test
    void beats_drive_the_handler_and_the_ack_is_the_report() throws Exception {
        List<String> beats = new ArrayList<>();
        OutdatedReport ack = OutdatedReport.of(
                false, List.of(new OutdatedReport.Row("", "g:a", "", "main", "1.0", "1.0", "2.0", "")));

        OutdatedReport report = WireStream.pumpRead(
                stream(
                        ProtoReads.outdatedProgress(0, 2, "g:a"),
                        ProtoReads.outdatedProgress(1, 2, "g:b"),
                        ack.encode()),
                EngineResolveAdapter.outdatedDecoder(
                        (checked, total, coordinate) -> beats.add(checked + "/" + total + " " + coordinate)));

        assertThat(beats).containsExactly("0/2 g:a", "1/2 g:b");
        assertThat(report.rows())
                .singleElement()
                .extracting(OutdatedReport.Row::latest)
                .isEqualTo("2.0");
    }

    @Test
    void an_engine_error_line_surfaces_its_message() {
        assertThatThrownBy(() -> WireStream.pumpRead(
                        stream(ProtoLifecycle.requestFailed("no jk.toml here")),
                        EngineResolveAdapter.outdatedDecoder(EngineRequests.OutdatedHandler.NONE)))
                .isInstanceOf(EngineWireException.class)
                .hasMessageContaining("no jk.toml here");
    }
}

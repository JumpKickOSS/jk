// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.ProtocolWriter;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.sigstore.Dsse;
import cc.jumpkick.sigstore.StubSigner;
import com.sun.net.httpserver.HttpServer;
import dev.sigstore.bundle.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A {@code --slsa --sigstore} publish attests its provenance with the keyless signer. */
class PublisherSigstoreTest {

    private static final String AT = "/repo/com/example/widget/1.2.3/widget-1.2.3";

    @Test
    void a_keyless_publish_uploads_the_statement_in_an_envelope_its_bundle_certifies(@TempDir Path dir)
            throws Exception {
        Map<String, byte[]> received = new ConcurrentHashMap<>();
        Map<String, String> contentTypes = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("PUT".equals(exchange.getRequestMethod())) {
                received.put(path, exchange.getRequestBody().readAllBytes());
                contentTypes.put(path, exchange.getRequestHeaders().getFirst("Content-Type"));
                exchange.sendResponseHeaders(201, -1);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            String output =
                    publish(dir, "http://127.0.0.1:" + server.getAddress().getPort() + "/repo/");
            assertThat(output).contains("\"t\":\"result\"");
        } finally {
            server.stop(0);
        }

        assertThat(received).doesNotContainKey(AT + ".intoto.json");
        assertThat(contentTypes).containsEntry(AT + ".intoto.jsonl", "application/json");
        String envelope = new String(requireNonNull(received.get(AT + ".intoto.jsonl")), StandardCharsets.UTF_8);
        byte[] statement = Base64.getDecoder().decode(Jsonl.requiredStr(envelope, "payload"));
        assertThat(new String(statement, StandardCharsets.UTF_8))
                .contains("\"predicateType\":\"https://slsa.dev/provenance/v1\"")
                .contains("\"name\":\"widget-1.2.3.jar\"");
        byte[] sig = Base64.getDecoder()
                .decode(Jsonl.requiredStr(
                        Jsonl.objectArray(envelope, "signatures").getFirst(), "sig"));

        Bundle bundle = Bundle.from(new StringReader(
                new String(requireNonNull(received.get(AT + ".intoto.jsonl.sigstore")), StandardCharsets.UTF_8)));
        Signature verify = Signature.getInstance("SHA256withECDSA");
        verify.initVerify(bundle.getCertPath().getCertificates().getFirst().getPublicKey());
        verify.update(Dsse.pae(Dsse.IN_TOTO, statement));
        assertThat(verify.verify(sig)).isTrue();
        assertThat(received).containsKeys(AT + ".intoto.jsonl.sha1", AT + ".jar.sigstore");
        assertThat(dir.resolve("target/publish/widget-1.2.3.intoto.json")).doesNotExist();
    }

    private static String publish(Path dir, String repoUrl) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "widget"
                version = "1.2.3"
                """);
        Path jar = Files.write(dir.resolve("widget-1.2.3.jar"), new byte[] {0x50, 0x4b, 0x05, 0x06});
        Path spec = Files.write(
                dir.resolve("publish.spec"),
                new SpecWriter()
                        .op(PluginProtocol.OP_PUBLISH, null, "jk-publisher")
                        .configString("repoUrl", repoUrl)
                        .configString("repoAuthType", "anonymous")
                        .configBool("slsa", true)
                        .configBool("signSigstore", true)
                        .offline(false)
                        .artifact(jar)
                        .layout(Map.of("moduleDir", dir))
                        .lines());
        var buffer = new ByteArrayOutputStream();
        ProtocolWriter out = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKPU:");
        Publisher publisher = new Publisher(Http::failFast, () -> new StubSigner().signer());
        int exit = publisher.run(List.of(spec.toString()), out);
        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(exit).as(output).isZero();
        return output;
    }
}

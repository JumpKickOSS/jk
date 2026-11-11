// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.asyncapi;

import cc.jumpkick.avro.AvroMain;
import cc.jumpkick.host.PathUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An AsyncAPI document's payload classes: {@code <jsonschema2pojo flag>… -- <document>…} writes
 * every {@code components.schemas} entry and every message payload of each document as a JSON
 * Schema, an internal {@code #/components/schemas/X} {@code $ref} rewritten to {@code X.json}, then
 * runs jsonschema2pojo over them. Messages are read from {@code components.messages} and from the
 * channels, AsyncAPI 2.x ({@code publish} / {@code subscribe}) and 3.x ({@code messages}) alike. An
 * Avro payload is compiled by {@code AvroMain} when {@value #AVRO} says the Avro compiler is on the
 * classpath, and a Protobuf payload by protoc when {@value #PROTOC} names it; a payload in another
 * {@code schemaFormat}, or one whose tool is not named, is skipped with a warning. Runs in the
 * generator step's forked JVM over jsonschema2pojo's closure, whose Jackson reads the documents.
 */
public final class AsyncApiMain {

    private static final String CLI = "org.jsonschema2pojo.cli.Jsonschema2PojoCLI";
    private static final String SCHEMAS = "#/components/schemas/";
    private static final String MESSAGES = "#/components/messages/";

    private AsyncApiMain() {}

    /** The flag saying the Avro compiler is on the classpath, so Avro payloads are generated. */
    static final String AVRO = "--avro";

    /** The flag naming the protoc binary Protobuf payloads compile with. */
    static final String PROTOC = "--protoc";

    /** The flag naming the protobuf-java jar, whose well-known types protoc imports. */
    static final String PROTOBUF_JAVA = "--protobuf-java";

    public static void main(String[] args) throws Exception {
        // One scratch tree for the schema files and protoc, gone when the step is: a step runs in
        // a warm worker, and every run's leftovers would pile up in the temp directory.
        Path work = Files.createTempDirectory("jk-asyncapi-");
        try {
            run(args, work);
        } finally {
            PathUtil.deleteRecursively(work);
        }
    }

    private static void run(String[] args, Path work) throws Exception {
        int split = List.of(args).indexOf("--");
        List<String> flags = new ArrayList<>(List.of(args).subList(0, split < 0 ? args.length : split));
        List<String> documents = split < 0 ? List.of() : List.of(args).subList(split + 1, args.length);
        boolean avro = flags.remove(AVRO);
        String protoc = take(flags, PROTOC);
        String protobufJava = take(flags, PROTOBUF_JAVA);
        Path schemas = Files.createDirectories(work.resolve("schemas"));
        Path avsc = Files.createDirectories(work.resolve("avro"));
        Path protos = Files.createDirectories(work.resolve("proto"));
        List<String> avroFiles = new ArrayList<>();
        List<String> protoFiles = new ArrayList<>();
        boolean json = false;
        for (String document : documents) {
            for (Path written : extract(Path.of(document), schemas, avsc, protos)) {
                String file = written.toString();
                if (file.endsWith(".avsc")) avroFiles.add(file);
                else if (file.endsWith(".proto")) protoFiles.add(file);
                else json = true;
            }
        }
        String out = flags.get(flags.indexOf("-t") + 1);
        if (!protoFiles.isEmpty()) {
            if (protoc == null || protobufJava == null) {
                System.err.println("jk-asyncapi: " + protoFiles.size()
                        + " Protobuf payloads not generated; set [asyncapi] protobuf");
            } else {
                Protoc.run(
                        Path.of(protoc),
                        Path.of(protobufJava),
                        protos,
                        protoFiles,
                        Path.of(out),
                        Files.createDirectories(work.resolve("protoc")));
            }
        }
        if (!avroFiles.isEmpty() && !avro) {
            System.err.println(
                    "jk-asyncapi: " + avroFiles.size() + " Avro payloads not generated; set [asyncapi] avro");
        }
        if (!avroFiles.isEmpty() && avro) {
            List<String> avroArgs = new ArrayList<>(List.of("--out", out));
            avroArgs.addAll(avroFiles);
            AvroMain.main(avroArgs.toArray(String[]::new));
        }
        if (!json) return;
        flags.addAll(List.of("-T", "JSONSCHEMA", "-s", schemas.toString()));
        try {
            Class.forName(CLI).getMethod("main", String[].class).invoke(null, (Object) flags.toArray(String[]::new));
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    /** The value after {@code flag}, both removed from {@code flags}; null when it is not there. */
    private static @Nullable String take(List<String> flags, String flag) {
        int at = flags.indexOf(flag);
        if (at < 0 || at + 1 >= flags.size()) return null;
        String value = flags.get(at + 1);
        flags.subList(at, at + 2).clear();
        return value;
    }

    /** As {@link #extract(Path, Path, Path, Path)}, with Avro and Protobuf payloads dropped. */
    static List<Path> extract(Path document, Path out) throws IOException {
        return extract(document, out, null, null);
    }

    /**
     * Writes {@code document}'s JSON schemas and payloads into {@code out}, one {@code <Name>.json}
     * each, its Avro payloads into {@code avro}, one {@code <Name>.avsc} each, and its Protobuf
     * payloads into {@code proto}, one {@code <name>.proto} each, each when it is not null. Returns
     * the files written.
     */
    static List<Path> extract(Path document, Path out, @Nullable Path avro, @Nullable Path proto) throws IOException {
        List<Path> written = new ArrayList<>();
        String name = document.getFileName().toString().toLowerCase(Locale.ROOT);
        ObjectMapper mapper = name.endsWith(".json") ? new ObjectMapper() : new ObjectMapper(new YAMLFactory());
        JsonNode root = mapper.readTree(document.toFile());
        Map<String, JsonNode> files = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> s :
                root.path("components").path("schemas").properties()) {
            files.put(className(s.getKey()), s.getValue());
        }
        Map<String, JsonNode> messages = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> m :
                root.path("components").path("messages").properties()) {
            messages.put(m.getKey(), m.getValue());
        }
        for (Map.Entry<String, JsonNode> channel : root.path("channels").properties()) {
            JsonNode c = channel.getValue();
            for (Map.Entry<String, JsonNode> m : c.path("messages").properties())
                collect(m.getKey(), m.getValue(), root, messages);
            for (String op : List.of("publish", "subscribe")) {
                JsonNode message = c.path(op).path("message");
                if (message.isMissingNode()) continue;
                String id = c.path(op).path("operationId").asText(channel.getKey() + "-" + op);
                if (message.has("oneOf")) {
                    int i = 0;
                    for (JsonNode one : message.get("oneOf")) collect(id + "-" + i++, one, root, messages);
                } else {
                    collect(id, message, root, messages);
                }
            }
        }
        Map<String, JsonNode> avroFiles = new LinkedHashMap<>();
        Map<String, String> protoFiles = new LinkedHashMap<>();
        messages.forEach((key, message) -> {
            String messageName = message.path("name").asText(key);
            String format = message.path("schemaFormat").asText("");
            String protoText = protoOf(message.path("payload"), format);
            if (protoText != null) {
                protoFiles.putIfAbsent(className(messageName), protoText);
                return;
            }
            JsonNode avroSchema = avroOf(message.path("payload"), format);
            if (avroSchema != null) {
                avroFiles.putIfAbsent(className(messageName), avroSchema);
                return;
            }
            JsonNode payload = schemaOf(message.path("payload"), format, key);
            if (payload == null || payload.isMissingNode()) return;
            if (payload.has("$ref") && payload.get("$ref").asText().startsWith(SCHEMAS)) return;
            files.putIfAbsent(className(messageName), payload);
        });
        if (proto != null) {
            Files.createDirectories(proto);
            for (Map.Entry<String, String> f : protoFiles.entrySet()) {
                // protoc names the outer class after the file; a lower-case file keeps it from the message.
                Path file = proto.resolve(f.getKey().toLowerCase(Locale.ROOT) + ".proto");
                Files.writeString(file, f.getValue());
                written.add(file);
            }
        }
        if (avro != null) {
            Files.createDirectories(avro);
            for (Map.Entry<String, JsonNode> f : avroFiles.entrySet()) {
                Path file = avro.resolve(f.getKey() + ".avsc");
                Files.writeString(file, new ObjectMapper().writeValueAsString(f.getValue()));
                written.add(file);
            }
        }
        Files.createDirectories(out);
        for (Map.Entry<String, JsonNode> f : files.entrySet()) {
            JsonNode schema = rewrite(f.getValue().deepCopy());
            Path file = out.resolve(f.getKey() + ".json");
            Files.writeString(file, new ObjectMapper().writeValueAsString(schema));
            written.add(file);
        }
        return written;
    }

    /** A message, resolved through {@code #/components/messages/…}, under {@code key} unless already named. */
    private static void collect(String key, JsonNode message, JsonNode root, Map<String, JsonNode> messages) {
        JsonNode resolved = message;
        String ref = message.path("$ref").asText("");
        if (ref.startsWith(MESSAGES)) {
            String target = ref.substring(MESSAGES.length());
            if (messages.containsKey(target)) return;
            resolved = root.path("components").path("messages").path(target);
            key = target;
        }
        if (!resolved.isMissingNode()) messages.putIfAbsent(key, resolved);
    }

    /**
     * The JSON Schema a payload is: the payload itself, or a 3.x multi-format object's {@code
     * schema}; null, with a warning, for a payload in another format.
     */
    private static @Nullable JsonNode schemaOf(JsonNode payload, String messageFormat, String key) {
        String format = messageFormat;
        JsonNode schema = payload;
        if (payload.has("schemaFormat") && payload.has("schema")) {
            format = payload.get("schemaFormat").asText();
            schema = payload.get("schema");
        }
        if (format.isEmpty()
                || format.contains("asyncapi")
                || format.contains("schema+json")
                || format.contains("schema+yaml")) return schema;
        System.err.println("jk-asyncapi: " + key + ": " + format + " payload not generated");
        return null;
    }

    /** The Protobuf text a payload is, its own or a 3.x multi-format object's; null for any other format. */
    private static @Nullable String protoOf(JsonNode payload, String messageFormat) {
        String format = messageFormat;
        JsonNode schema = payload;
        if (payload.has("schemaFormat") && payload.has("schema")) {
            format = payload.get("schemaFormat").asText();
            schema = payload.get("schema");
        }
        return format.contains("protobuf") && schema.isTextual() ? schema.asText() : null;
    }

    /** The Avro schema a payload is, its own or a 3.x multi-format object's; null for any other format. */
    private static @Nullable JsonNode avroOf(JsonNode payload, String messageFormat) {
        String format = messageFormat;
        JsonNode schema = payload;
        if (payload.has("schemaFormat") && payload.has("schema")) {
            format = payload.get("schemaFormat").asText();
            schema = payload.get("schema");
        }
        return format.contains("apache.avro") && !schema.isMissingNode() ? schema : null;
    }

    /** {@code node} with every internal schema {@code $ref} pointing at the schema's file. */
    private static JsonNode rewrite(JsonNode node) {
        if (node instanceof ObjectNode object) {
            JsonNode ref = object.get("$ref");
            if (ref != null && ref.isTextual() && ref.asText().startsWith(SCHEMAS)) {
                object.set("$ref", TextNode.valueOf(className(ref.asText().substring(SCHEMAS.length())) + ".json"));
            }
            for (Map.Entry<String, JsonNode> e : object.properties()) rewrite(e.getValue());
        } else if (node.isArray()) {
            for (JsonNode child : node) rewrite(child);
        }
        return node;
    }

    /** A schema or message name as a Java class name: {@code order-created} is {@code OrderCreated}. */
    static String className(String name) {
        StringBuilder out = new StringBuilder();
        boolean upper = true;
        for (char c : name.toCharArray()) {
            if (!Character.isLetterOrDigit(c)) {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        if (out.isEmpty() || !Character.isJavaIdentifierStart(out.charAt(0))) out.insert(0, '_');
        return out.toString();
    }
}

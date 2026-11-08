// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.asyncapi;

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
import java.util.Iterator;
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
 * channels, AsyncAPI 2.x ({@code publish} / {@code subscribe}) and 3.x ({@code messages}) alike; a
 * payload in another {@code schemaFormat} (Avro, Protobuf) is skipped with a warning. Runs in the
 * generator step's forked JVM over jsonschema2pojo's closure, whose Jackson reads the documents.
 */
public final class AsyncApiMain {

    private static final String CLI = "org.jsonschema2pojo.cli.Jsonschema2PojoCLI";
    private static final String SCHEMAS = "#/components/schemas/";
    private static final String MESSAGES = "#/components/messages/";

    private AsyncApiMain() {}

    public static void main(String[] args) throws Exception {
        int split = List.of(args).indexOf("--");
        List<String> flags = new ArrayList<>(List.of(args).subList(0, split < 0 ? args.length : split));
        List<String> documents = split < 0 ? List.of() : List.of(args).subList(split + 1, args.length);
        Path schemas = Files.createTempDirectory("jk-asyncapi-");
        for (String document : documents) extract(Path.of(document), schemas);
        flags.addAll(List.of("-T", "JSONSCHEMA", "-s", schemas.toString()));
        try {
            Class.forName(CLI).getMethod("main", String[].class).invoke(null, (Object) flags.toArray(String[]::new));
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    /** Writes {@code document}'s schemas and payloads into {@code out}, one {@code <Name>.json} each. */
    static void extract(Path document, Path out) throws IOException {
        String name = document.getFileName().toString().toLowerCase(Locale.ROOT);
        ObjectMapper mapper = name.endsWith(".json") ? new ObjectMapper() : new ObjectMapper(new YAMLFactory());
        JsonNode root = mapper.readTree(document.toFile());
        Map<String, JsonNode> files = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> s : fields(root.path("components").path("schemas"))) {
            files.put(className(s.getKey()), s.getValue());
        }
        Map<String, JsonNode> messages = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> m : fields(root.path("components").path("messages"))) {
            messages.put(m.getKey(), m.getValue());
        }
        for (Map.Entry<String, JsonNode> channel : fields(root.path("channels"))) {
            JsonNode c = channel.getValue();
            for (Map.Entry<String, JsonNode> m : fields(c.path("messages")))
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
        messages.forEach((key, message) -> {
            JsonNode payload = schemaOf(
                    message.path("payload"), message.path("schemaFormat").asText(""), key);
            if (payload == null || payload.isMissingNode()) return;
            if (payload.has("$ref") && payload.get("$ref").asText().startsWith(SCHEMAS)) return;
            String messageName = message.path("name").asText(key);
            files.putIfAbsent(className(messageName), payload);
        });
        Files.createDirectories(out);
        for (Map.Entry<String, JsonNode> f : files.entrySet()) {
            JsonNode schema = rewrite(f.getValue().deepCopy());
            Files.writeString(out.resolve(f.getKey() + ".json"), new ObjectMapper().writeValueAsString(schema));
        }
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

    /** {@code node} with every internal schema {@code $ref} pointing at the schema's file. */
    private static JsonNode rewrite(JsonNode node) {
        if (node instanceof ObjectNode object) {
            JsonNode ref = object.get("$ref");
            if (ref != null && ref.isTextual() && ref.asText().startsWith(SCHEMAS)) {
                object.set("$ref", TextNode.valueOf(className(ref.asText().substring(SCHEMAS.length())) + ".json"));
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); )
                rewrite(it.next().getValue());
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

    private static List<Map.Entry<String, JsonNode>> fields(JsonNode node) {
        List<Map.Entry<String, JsonNode>> out = new ArrayList<>();
        if (node.isObject()) node.fields().forEachRemaining(out::add);
        return out;
    }
}

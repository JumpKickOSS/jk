// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Ordered JSON writer for job requests and their optional shared fields. */
final class RequestJson {
    private final JsonFields fields;

    private RequestJson(JsonFields fields) {
        this.fields = fields;
    }

    /** A request or event line, opened with its discriminator. */
    static RequestJson request(String type) {
        return new RequestJson(JsonFields.object()).string(EngineProtocol.TYPE_FIELD, type);
    }

    /**
     * An event line that carries {@code "schema":1} before its type — the hot build events have led
     * with the schema since before the wire froze, and the bytes are frozen pre-1.0.
     */
    static RequestJson event(String type) {
        return new RequestJson(JsonFields.object()).number("schema", 1).string(EngineProtocol.TYPE_FIELD, type);
    }

    static RequestJson fields() {
        return new RequestJson(JsonFields.fields());
    }

    RequestJson bool(String name, boolean value) {
        fields.bool(name, value);
        return this;
    }

    RequestJson number(String name, int value) {
        fields.number(name, value);
        return this;
    }

    RequestJson number(String name, long value) {
        fields.number(name, value);
        return this;
    }

    RequestJson string(String name, @Nullable String value) {
        fields.string(name, value);
        return this;
    }

    RequestJson string(String name, @Nullable String value, String defaultValue) {
        fields.string(name, value, defaultValue);
        return this;
    }

    RequestJson array(String name, @Nullable List<String> values) {
        fields.array(name, values);
        return this;
    }

    RequestJson map(String name, @Nullable Map<String, String> values) {
        fields.map(name, values);
        return this;
    }

    /** A value already in wire form — a number-or-null token, a nested object — appended verbatim. */
    RequestJson token(String name, String jsonToken) {
        fields.token(name, jsonToken);
        return this;
    }

    RequestJson optionalTrue(String name, boolean value) {
        fields.optionalTrue(name, value);
        return this;
    }

    RequestJson optionalString(String name, @Nullable String value) {
        fields.optionalString(name, value);
        return this;
    }

    RequestJson optionalNonBlankString(String name, @Nullable String value) {
        fields.optionalNonBlankString(name, value);
        return this;
    }

    RequestJson optionalNonEmptyString(String name, @Nullable String value) {
        fields.optionalNonEmptyString(name, value);
        return this;
    }

    RequestJson optionalBool(String name, @Nullable Boolean value) {
        fields.optionalBool(name, value);
        return this;
    }

    RequestJson optionalNumber(String name, long value, long omitAtOrBelow) {
        fields.optionalNumber(name, value, omitAtOrBelow);
        return this;
    }

    RequestJson optionalArray(String name, @Nullable List<String> values) {
        fields.optionalArray(name, values);
        return this;
    }

    RequestJson optionalMap(String name, @Nullable Map<String, String> values) {
        fields.optionalMap(name, values);
        return this;
    }

    RequestJson optionalTool(@Nullable String tool, @Nullable String version) {
        return tool == null || tool.isBlank() ? this : string("tool", tool).string("version", version, "");
    }

    RequestJson testSelection(@Nullable TestSelection selection, boolean omitDefault) {
        TestSelection value = selection == null ? TestSelection.DEFAULT : selection;
        if (omitDefault && value.equals(TestSelection.DEFAULT)) return this;
        return bool("allSuites", value.allSuites())
                .array("suites", value.suites())
                .array("includeTags", value.includeTags())
                .array("excludeTags", value.excludeTags())
                .bool("tagsResolved", value.tagsResolved())
                .optionalTrue("guard", value.guard())
                .optionalTrue("scriptsOnly", value.scriptsOnly())
                .optionalTrue("noScripts", value.noScripts());
    }

    String finish() {
        return fields.finish();
    }

    String suffix() {
        return fields.suffix();
    }

    /** The fields with no braces and no leading comma, or {@code ""} — what {@link Jsonl#append} splices. */
    String body() {
        return fields.body();
    }
}

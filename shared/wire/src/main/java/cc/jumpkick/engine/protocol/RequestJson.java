// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.progress.ProgressBarMode;
import java.util.List;
import java.util.Map;

/** Ordered JSON writer for job requests and their optional shared fields. */
final class RequestJson {

    private final StringBuilder json;
    private final boolean object;
    private boolean first = true;

    private RequestJson(boolean object) {
        this.object = object;
        this.json = new StringBuilder(object ? "{" : "");
    }

    static RequestJson request(String type) {
        return new RequestJson(true).string(EngineProtocol.TYPE_FIELD, type);
    }

    static RequestJson fields() {
        return new RequestJson(false);
    }

    RequestJson bool(String name, boolean value) {
        return raw(name, Boolean.toString(value));
    }

    RequestJson number(String name, int value) {
        return raw(name, Integer.toString(value));
    }

    RequestJson number(String name, long value) {
        return raw(name, Long.toString(value));
    }

    RequestJson string(String name, String value) {
        return raw(name, Jsonl.quote(value));
    }

    RequestJson string(String name, String value, String defaultValue) {
        return string(name, value == null ? defaultValue : value);
    }

    RequestJson array(String name, List<String> values) {
        return raw(name, Jsonl.array(values == null ? List.of() : values));
    }

    RequestJson map(String name, Map<String, String> values) {
        return raw(name, Jsonl.map(values));
    }

    RequestJson optionalTrue(String name, boolean value) {
        return value ? bool(name, true) : this;
    }

    RequestJson optionalString(String name, String value) {
        return value == null ? this : string(name, value);
    }

    RequestJson optionalNonBlankString(String name, String value) {
        return value == null || value.isBlank() ? this : string(name, value);
    }

    RequestJson optionalArray(String name, List<String> values) {
        return values == null || values.isEmpty() ? this : array(name, values);
    }

    RequestJson optionalMap(String name, Map<String, String> values) {
        return values == null || values.isEmpty() ? this : map(name, values);
    }

    RequestJson optionalTool(String tool, String version) {
        return tool == null || tool.isBlank() ? this : string("tool", tool).string("version", version, "");
    }

    RequestJson testSelection(TestSelection selection, boolean omitDefault) {
        TestSelection value = selection == null ? TestSelection.DEFAULT : selection;
        if (omitDefault && value.equals(TestSelection.DEFAULT)) return this;
        return bool("allSuites", value.allSuites())
                .array("suites", value.suites())
                .array("includeTags", value.includeTags())
                .array("excludeTags", value.excludeTags())
                .bool("tagsResolved", value.tagsResolved())
                .optionalTrue("gate", value.gate())
                .optionalTrue("scriptsOnly", value.scriptsOnly())
                .optionalTrue("noScripts", value.noScripts());
    }

    RequestJson trigger() {
        String trigger = System.getProperty("jk.build.trigger");
        if (trigger == null || trigger.isBlank()) trigger = System.getenv("JK_BUILD_TRIGGER");
        return trigger == null || trigger.isBlank() ? this : string("trigger", trigger.trim());
    }

    RequestJson progressMode() {
        ProgressBarMode mode = ProgressBarMode.fromEnvironment();
        return mode == ProgressBarMode.AUTO ? this : string("progressMode", mode.wireName());
    }

    String finish() {
        if (!object) throw new IllegalStateException("field fragments use suffix()");
        return json.append('}').toString();
    }

    String suffix() {
        if (object) throw new IllegalStateException("request objects use finish()");
        return json.isEmpty() ? "" : "," + json;
    }

    private RequestJson raw(String name, String value) {
        if (!first) json.append(',');
        first = false;
        json.append(Jsonl.quote(name)).append(':').append(value);
        return this;
    }
}

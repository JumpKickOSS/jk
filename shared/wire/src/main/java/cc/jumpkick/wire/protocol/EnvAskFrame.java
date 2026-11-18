// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The engine asking the client that sent a job for part of its shell, and the client's answer,
 * on the job's own connection ({@link EngineProtocol#ENV_ASK}, {@link EngineProtocol#ENV_ANSWER}).
 * A test JVM of the job looked a variable up that the request did not carry; the client looks it
 * up in its own environment. Values travel in the answer alone and are never logged.
 *
 * @param id the question's number, unique on the connection
 * @param names the variables wanted, by name; empty for a {@code what} question
 * @param what {@link #NAMES} for the names the shell sets, {@link #ALL} for every variable and its
 *     value, {@code null} for a question by name
 */
public record EnvAskFrame(
        long id, List<String> names, @Nullable String what) {

    /** {@code what}: the names the shell sets, no values. */
    public static final String NAMES = "names";

    /** {@code what}: every variable the shell sets, with its value. */
    public static final String ALL = "all";

    public EnvAskFrame {
        names = List.copyOf(names);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.ENV_ASK)
                .number("id", id)
                .optionalArray("names", names.isEmpty() ? null : names)
                .string("what", what)
                .finish();
    }

    /** The question {@code line} asks; a {@code what} this side does not know answers no names and no values. */
    public static EnvAskFrame decode(String line) {
        return new EnvAskFrame(Jsonl.longValue(line, "id", -1), Jsonl.strArray(line, "names"), Jsonl.str(line, "what"));
    }

    /** The answer line to question {@code id}: {@code vars} the shell sets, {@code names} for a names question. */
    public static String answer(long id, Map<String, String> vars, @Nullable List<String> names) {
        List<String> pairs = new ArrayList<>(vars.size());
        vars.forEach((name, value) -> pairs.add(name + "=" + value));
        return RequestJson.request(EngineProtocol.ENV_ANSWER)
                .number("id", id)
                .optionalArray("vars", pairs.isEmpty() ? null : pairs)
                .optionalArray("names", names)
                .finish();
    }

    /** The question an answer line answers, or -1 for a line that is no answer. */
    public static long answerId(String line) {
        return EngineProtocol.ENV_ANSWER.equals(EngineProtocol.typeOf(line)) ? Jsonl.longValue(line, "id", -1) : -1;
    }

    /** The variables an answer line carries. */
    public static Map<String, String> answerVars(String line) {
        return ProtoSession.pairsOf(Jsonl.strArray(line, "vars"));
    }

    /** The names a names answer carries, or {@code null} when it carries none. */
    public static @Nullable List<String> answerNames(String line) {
        return Jsonl.has(line, "names") ? Jsonl.strArray(line, "names") : null;
    }
}

// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.contract;

import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One exchange of the wire: the request line a client writes and the lines the engine answers it
 * with, as literal JSON. {@code varying} names the top-level fields whose value is the sender's
 * own (a version, a pid, a heap size): a line conforms when it carries every field the case does,
 * each of the same JSON kind, with equal values except in the varying fields. A line may carry
 * fields the case does not name; the other side ignores them.
 */
public record WireCase(String name, String request, List<String> replies, Set<String> varying) {

    public WireCase {
        replies = List.copyOf(replies);
        varying = Set.copyOf(varying);
    }

    /** The request's {@code type}. */
    public String requestType() {
        return Objects.requireNonNull(EngineProtocol.typeOf(request), "request type");
    }

    /** Why {@code actual}, a request a client wrote, does not conform to this case; empty when it does. */
    public List<String> requestMismatches(String actual) {
        return mismatches("request", request, actual, varying);
    }

    /** Why {@code actual}, the lines an engine answered with, do not conform to this case; empty when they do. */
    public List<String> replyMismatches(List<String> actual) {
        List<String> out = new ArrayList<>();
        if (actual.size() != replies.size()) {
            out.add(name + ": " + actual.size() + " reply lines, the contract has " + replies.size() + ": " + actual);
            return out;
        }
        for (int i = 0; i < replies.size(); i++) {
            out.addAll(mismatches("reply " + i, replies.get(i), actual.get(i), varying));
        }
        return out;
    }

    /** Whether {@code line} is a request this case answers: its type, and every field the case's request names. */
    public boolean answers(String line) {
        if (!requestType().equals(EngineProtocol.typeOf(line))) return false;
        Map<?, ?> mine = object(request);
        Map<?, ?> theirs = object(line);
        return theirs != null && mine != null && theirs.keySet().containsAll(mine.keySet());
    }

    private List<String> mismatches(String what, String expected, String actual, Set<String> varying) {
        List<String> out = new ArrayList<>();
        Map<?, ?> want = Objects.requireNonNull(object(expected), "contract line is not an object: " + expected);
        Map<?, ?> got = object(actual);
        if (got == null) {
            out.add(name + " " + what + ": not a JSON object: " + actual);
            return out;
        }
        for (Map.Entry<?, ?> e : want.entrySet()) {
            String key = String.valueOf(e.getKey());
            conform(out, what + " field " + key, e.getValue(), got, key, !varying.contains(key));
        }
        return out;
    }

    private void conform(
            List<String> out, String where, @Nullable Object want, Map<?, ?> in, String key, boolean values) {
        if (!in.containsKey(key)) {
            out.add(name + " " + where + ": missing");
            return;
        }
        conform(out, where, want, in.get(key), values);
    }

    private void conform(List<String> out, String where, @Nullable Object want, @Nullable Object got, boolean values) {
        String wantKind = kind(want);
        String gotKind = kind(got);
        if (!wantKind.equals(gotKind)) {
            out.add(name + " " + where + ": is " + gotKind + ", the contract has " + wantKind);
            return;
        }
        if (want instanceof Map<?, ?> wantMap && got instanceof Map<?, ?> gotMap) {
            for (Map.Entry<?, ?> e : wantMap.entrySet()) {
                String key = String.valueOf(e.getKey());
                conform(out, where + "." + key, e.getValue(), gotMap, key, values);
            }
            return;
        }
        if (want instanceof List<?> wantList && got instanceof List<?> gotList) {
            if (values && !same(wantList, gotList)) {
                out.add(name + " " + where + ": is " + MiniJson.write(gotList) + ", the contract has "
                        + MiniJson.write(wantList));
            } else if (!values && !wantList.isEmpty() && !gotList.isEmpty()) {
                conform(out, where + "[0]", wantList.get(0), gotList.get(0), false);
            }
            return;
        }
        if (values && !same(want, got)) {
            out.add(name + " " + where + ": is " + MiniJson.write(got) + ", the contract has " + MiniJson.write(want));
        }
    }

    private static boolean same(@Nullable Object a, @Nullable Object b) {
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (!x.keySet().equals(y.keySet())) return false;
            for (Object k : x.keySet()) if (!same(x.get(k), y.get(k))) return false;
            return true;
        }
        return Objects.equals(a, b);
    }

    private static String kind(@Nullable Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "a string";
        if (v instanceof Number) return "a number";
        if (v instanceof Boolean) return "a boolean";
        if (v instanceof Map) return "an object";
        if (v instanceof List) return "an array";
        return v.getClass().getSimpleName();
    }

    private static @Nullable Map<?, ?> object(String line) {
        try {
            return MiniJson.parse(line) instanceof Map<?, ?> m ? m : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}

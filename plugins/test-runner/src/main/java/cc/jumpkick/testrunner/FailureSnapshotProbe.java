// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import cc.jumpkick.host.Classpaths;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.CharValue;
import com.sun.jdi.ClassObjectReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The debugger this JVM attaches to itself to read a throwing frame's variables. The JDWP agent
 * the engine loads listens on a loopback port; this attaches to it once, before any test runs, and
 * asks to hear of every exception thrown in code that is not the JDK's, and of the runtime
 * exceptions the JDK throws. Each event suspends only the throwing thread, while a daemon thread
 * here reads its frames and lets it go.
 *
 * <p>Nothing here calls into the program: values are read field by field, never through {@code
 * toString}, so taking a snapshot runs no test code and changes no state.
 */
final class FailureSnapshotProbe {

    /**
     * Throw sites the agent filters out: the JDK's, and every class the reading thread runs, so
     * that thread never waits on itself.
     */
    private static final List<String> EXCLUDED = List.of(
            "java.*",
            "javax.*",
            "jdk.*",
            "sun.*",
            "com.sun.*",
            "cc.jumpkick.testrunner.FailureSnapshotProbe*",
            "cc.jumpkick.testrunner.FailureSnapshots*");

    private static final List<String> BOXES = List.of(
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Short",
            "java.lang.Byte",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Boolean",
            "java.lang.Character");

    /** Fields an object shows before the rest are counted. */
    private static final int FIELDS = 5;

    /** Elements an array shows before the rest are counted. */
    private static final int ELEMENTS = 3;

    private final VirtualMachine vm;
    private final FailureSnapshots store;
    private final List<Path> roots;
    private final Map<String, Boolean> projectTypes = new HashMap<>();
    private final Set<Long> seen = new LinkedHashSet<>();
    private @Nullable Field detailMessage;

    private FailureSnapshotProbe(VirtualMachine vm, FailureSnapshots store, List<Path> roots) {
        this.vm = vm;
        this.store = store;
        this.roots = roots;
    }

    /** Attach to this JVM's JDWP agent and start reading; throws when there is no agent to attach to. */
    static void attach(FailureSnapshots store) throws Exception {
        String address = listenerAddress();
        int colon = address.lastIndexOf(':');
        String host = colon < 0 ? "127.0.0.1" : address.substring(0, colon);
        String port = address.substring(colon + 1);
        AttachingConnector socket = null;
        for (AttachingConnector c : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if (c.name().equals("com.sun.jdi.SocketAttach")) socket = c;
        }
        if (socket == null) throw new IOException("no socket attaching connector");
        Map<String, Connector.Argument> args = socket.defaultArguments();
        Objects.requireNonNull(args.get("hostname"), "hostname").setValue(host);
        Objects.requireNonNull(args.get("port"), "port").setValue(port);
        VirtualMachine vm = socket.attach(args);
        FailureSnapshotProbe probe = new FailureSnapshotProbe(vm, store, projectRoots());
        probe.request();
        Thread reader = new Thread(probe::read, "jk-failure-snapshot");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * {@code host:port} the JDWP agent listens on, from the agent's own property ({@code
     * dt_socket:port} or {@code dt_socket:host:port}).
     */
    static String listenerAddress() throws ReflectiveOperationException, IOException {
        Class<?> support = Class.forName("jdk.internal.vm.VMSupport");
        Properties agent = (Properties) support.getMethod("getAgentProperties").invoke(null);
        String raw = agent.getProperty("sun.jdwp.listenerAddress");
        if (raw == null || raw.isBlank()) throw new IOException("the JDWP agent is not listening");
        return raw.startsWith("dt_socket:") ? raw.substring("dt_socket:".length()) : raw;
    }

    /** The directories on this JVM's class path: the project's classes, where sibling modules' too. */
    static List<Path> projectRoots() {
        List<Path> out = new ArrayList<>();
        for (Path p : Classpaths.split(System.getProperty("java.class.path"))) {
            if (Files.isDirectory(p)) out.add(p);
        }
        return out;
    }

    private void request() {
        EventRequestManager requests = vm.eventRequestManager();
        ExceptionRequest outside = requests.createExceptionRequest(null, true, true);
        for (String pattern : EXCLUDED) outside.addClassExclusionFilter(pattern);
        enable(outside);
        // A NullPointerException from Objects.requireNonNull or an IndexOutOfBoundsException from
        // List.get is thrown inside the JDK, on behalf of the project frame that called it.
        List<ReferenceType> runtime = vm.classesByName("java.lang.RuntimeException");
        if (!runtime.isEmpty()) {
            for (String jdk : List.of("java.*", "jdk.*")) {
                ExceptionRequest inside = requests.createExceptionRequest(runtime.get(0), true, true);
                inside.addClassFilter(jdk);
                enable(inside);
            }
        }
        List<ReferenceType> throwable = vm.classesByName("java.lang.Throwable");
        if (!throwable.isEmpty()) detailMessage = throwable.get(0).fieldByName("detailMessage");
    }

    private static void enable(ExceptionRequest r) {
        r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        r.enable();
    }

    private void read() {
        try {
            while (true) {
                EventSet set = vm.eventQueue().remove();
                try {
                    for (Event e : set) {
                        if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) return;
                        if (e instanceof ExceptionEvent thrown) take(thrown);
                    }
                } catch (RuntimeException e) {
                    // A frame that went away under the read: no snapshot for this throw.
                } finally {
                    set.resume();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // The connection closed as the JVM exits.
        }
    }

    private void take(ExceptionEvent thrown) {
        Location at = thrown.location();
        Location caught = thrown.catchLocation();
        boolean projectThrow = project(at.declaringType());
        // Handled by a project caller: the program's own flow, not a failure.
        if (caught != null
                && project(caught.declaringType())
                && !caught.method().equals(at.method())) return;
        ObjectReference exception = thrown.exception();
        if (seen.contains(exception.uniqueID())) return;
        ThreadReference thread = thrown.thread();
        List<StackFrame> frames;
        try {
            frames = thread.frames();
        } catch (IncompatibleThreadStateException e) {
            return;
        }
        // Thrown in a library and caught in it before reaching a project frame: its own business.
        if (!projectThrow && !passesProject(frames, caught)) return;
        seen.add(exception.uniqueID());
        if (seen.size() > FailureSnapshots.KEPT * 4) seen.remove(seen.iterator().next());
        List<FailureSnapshots.Frame> kept = new ArrayList<>();
        for (int i = 0; i < frames.size() && kept.size() < FailureSnapshots.MAX_FRAMES; i++) {
            StackFrame frame = frames.get(i);
            ReferenceType type = frame.location().declaringType();
            if (i > 0 && !project(type)) continue;
            kept.add(frame(frame));
        }
        if (kept.size() < 2 && !projectThrow) return;
        store.record(thread.name(), exception.referenceType().name(), message(exception), kept);
    }

    /** Whether a project frame lies between the throw and the frame that catches it. */
    private boolean passesProject(List<StackFrame> frames, @Nullable Location caught) {
        for (int i = 1; i < frames.size(); i++) {
            Location where = frames.get(i).location();
            if (caught != null && where.method().equals(caught.method())) return false;
            if (project(where.declaringType())) return true;
        }
        return false;
    }

    private @Nullable String message(ObjectReference exception) {
        if (detailMessage == null) return null;
        Value v = exception.getValue(detailMessage);
        return v instanceof StringReference s ? s.value() : null;
    }

    /** Whether {@code type} was loaded from a class file under one of the class path's directories. */
    private boolean project(ReferenceType type) {
        String name = type.name();
        Boolean known = projectTypes.get(name);
        if (known != null) return known;
        boolean found = false;
        String file = name.replace('.', '/') + ".class";
        for (Path root : roots) {
            if (Files.isRegularFile(root.resolve(file))) {
                found = true;
                break;
            }
        }
        projectTypes.put(name, found);
        return found;
    }

    private FailureSnapshots.Frame frame(StackFrame frame) {
        Location where = frame.location();
        String at =
                simpleName(where.declaringType().name()) + "." + where.method().name() + "(" + source(where) + ")";
        List<String> names = new ArrayList<>();
        List<Value> values = new ArrayList<>();
        try {
            List<LocalVariable> visible = new ArrayList<>(frame.visibleVariables());
            visible.sort((a, b) -> Boolean.compare(b.isArgument(), a.isArgument()));
            Map<LocalVariable, Value> read = frame.getValues(visible);
            for (LocalVariable v : visible) {
                names.add(v.name());
                values.add(read.get(v));
            }
        } catch (AbsentInformationException e) {
            List<Value> args = frame.getArgumentValues();
            for (int i = 0; i < args.size(); i++) {
                names.add("arg" + i);
                values.add(args.get(i));
            }
        }
        ObjectReference self = frame.thisObject();
        if (self != null
                && project(where.declaringType())
                && !fields(self.referenceType()).isEmpty()) {
            names.add("this");
            values.add(self);
        }
        List<String> vars = new ArrayList<>();
        int chars = 0;
        for (int i = 0; i < names.size(); i++) {
            String var = names.get(i) + "=" + FailureSnapshots.clip(render(values.get(i), 0));
            if (vars.size() == FailureSnapshots.MAX_VARS || chars + var.length() > FailureSnapshots.FRAME_CHARS) {
                vars.add("+" + (names.size() - i) + " more");
                break;
            }
            vars.add(var);
            chars += var.length();
        }
        return new FailureSnapshots.Frame(at, vars);
    }

    private static String source(Location where) {
        String file;
        try {
            file = where.sourceName();
        } catch (AbsentInformationException e) {
            file = "Unknown Source";
        }
        int line = where.lineNumber();
        return line > 0 ? file + ":" + line : file;
    }

    /**
     * {@code value} as a reader wants it: a string quoted, a number as it is, an enum by its
     * constant, a box by what it holds, an array or collection by its size and a few elements, and
     * any other object by its type and first fields, one level deep.
     */
    String render(@Nullable Value value, int depth) {
        if (value == null) return "null";
        if (value instanceof CharValue c) return "'" + c.value() + "'";
        if (value instanceof PrimitiveValue p) return p.toString();
        if (value instanceof StringReference s) return quote(s.value());
        if (value instanceof ClassObjectReference c)
            return "class " + c.reflectedType().name();
        if (value instanceof ArrayReference a) return array(a, depth);
        ObjectReference o = (ObjectReference) value;
        ReferenceType type = o.referenceType();
        String name = type.name();
        String simple = simpleName(name);
        if (BOXES.contains(name)) return render(field(o, "value"), depth);
        if (type instanceof ClassType c && isEnum(c))
            return simple + "." + render(field(o, "name"), depth + 1).replace("\"", "");
        if (name.equals("java.math.BigDecimal")) return bigDecimal(o);
        if (name.equals("java.util.Optional")) {
            Value held = field(o, "value");
            return held == null ? "Optional.empty" : "Optional[" + render(held, depth + 1) + "]";
        }
        if (name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")) {
            String list = list(o, name, depth);
            if (list != null) return list;
            Value size = field(o, "size");
            return size instanceof PrimitiveValue n ? simple + "(size=" + n + ")" : simple;
        }
        if (depth > 0) return simple;
        StringBuilder sb = new StringBuilder(simple).append('{');
        List<Field> fields = fields(type);
        Map<Field, Value> read = o.getValues(fields.subList(0, Math.min(fields.size(), FIELDS)));
        for (int i = 0; i < Math.min(fields.size(), FIELDS); i++) {
            if (i > 0) sb.append(", ");
            Field f = fields.get(i);
            sb.append(f.name()).append('=').append(render(read.get(f), depth + 1));
        }
        if (fields.size() > FIELDS) sb.append(", +").append(fields.size() - FIELDS);
        return sb.append('}').toString();
    }

    private static List<Field> fields(ReferenceType type) {
        List<Field> fields = new ArrayList<>();
        for (Field f : type.allFields()) if (!f.isStatic() && !f.isSynthetic()) fields.add(f);
        return fields;
    }

    /**
     * An {@code ArrayList} or a {@code List.of} list as its size and first elements, read from the
     * fields that hold them; {@code null} for any other type.
     */
    private @Nullable String list(ObjectReference o, String name, int depth) {
        List<Value> head = new ArrayList<>();
        int size;
        if (field(o, "elementData") instanceof ArrayReference data && field(o, "size") instanceof PrimitiveValue n) {
            size = n.intValue();
            if (size > 0 && depth == 0)
                head.addAll(data.getValues(0, Math.min(size, Math.min(ELEMENTS, data.length()))));
        } else if (name.equals("java.util.ImmutableCollections$ListN")
                && field(o, "elements") instanceof ArrayReference data) {
            size = data.length();
            if (size > 0 && depth == 0) head.addAll(data.getValues(0, Math.min(size, ELEMENTS)));
        } else if (name.equals("java.util.ImmutableCollections$List12")) {
            // The second slot of a one-element list holds a bare Object, not an element.
            for (String slot : List.of("e0", "e1")) {
                Value v = field(o, slot);
                if (v instanceof ObjectReference e && e.referenceType().name().equals("java.lang.Object")) continue;
                head.add(v);
            }
            size = head.size();
            if (depth > 0) head.clear();
        } else {
            return null;
        }
        String shown = name.startsWith("java.util.ImmutableCollections$") ? "List" : simpleName(name);
        StringBuilder sb = new StringBuilder(shown).append('[').append(size).append(']');
        if (head.isEmpty()) return sb.toString();
        sb.append(" {");
        for (int i = 0; i < head.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(render(head.get(i), depth + 1));
        }
        if (size > head.size()) sb.append(", …");
        return sb.append('}').toString();
    }

    private String array(ArrayReference a, int depth) {
        String component = simpleName(a.referenceType().name());
        component = component.substring(0, component.length() - 2);
        int length = a.length();
        StringBuilder sb =
                new StringBuilder(component).append('[').append(length).append(']');
        if (depth > 0 || length == 0) return sb.toString();
        sb.append(" {");
        List<Value> head = a.getValues(0, Math.min(length, ELEMENTS));
        for (int i = 0; i < head.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(render(head.get(i), depth + 1));
        }
        if (length > ELEMENTS) sb.append(", …");
        return sb.append('}').toString();
    }

    private static @Nullable Value field(ObjectReference o, String name) {
        Field f = o.referenceType().fieldByName(name);
        return f == null ? null : o.getValue(f);
    }

    private static boolean isEnum(ClassType type) {
        for (ClassType t = type.superclass(); t != null; t = t.superclass()) {
            if (t.name().equals("java.lang.Enum")) return true;
        }
        return false;
    }

    /** A {@code BigDecimal} from its compact form, or its cached string; its type name otherwise. */
    private static String bigDecimal(ObjectReference o) {
        if (field(o, "stringCache") instanceof StringReference s) return s.value();
        if (field(o, "intCompact") instanceof PrimitiveValue compact
                && field(o, "scale") instanceof PrimitiveValue scale
                && compact.longValue() != Long.MIN_VALUE) {
            return BigDecimal.valueOf(compact.longValue(), scale.intValue()).toString();
        }
        return "BigDecimal";
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** {@code com.example.Order$Line} as {@code Order$Line}. */
    static String simpleName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }
}

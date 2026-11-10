// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

/**
 * Engine↔plugin JSONL vocabulary ({@link #T} discriminator). Spec vs reply type sets differ;
 * unknown types/kinds are ignored.
 */
public final class PluginProtocol {

    private PluginProtocol() {}

    /** The discriminator field present on every spec and reply line. */
    public static final String T = "t";

    // ---- ops (the value of the `op` spec line's `op` field) --------------------------------

    public static final String OP = "op";
    public static final String OP_NAME = "op"; // the op field within an `op` line
    public static final String OP_DESCRIBE = "describe";
    public static final String OP_RUN_STEP = "run-step";
    public static final String OP_PACKAGE = "package";
    public static final String OP_COMMAND = "command";
    public static final String OP_COMPILE = "compile";
    /** Read-only Zinc invalidation forecast (no class files written). */
    public static final String OP_PLAN = "plan";
    /**
     * Write the Kotlin classpath-entry ABI snapshots of the {@code cp} entries into the layout's
     * {@code snapshotDir} and report each one's digest ({@link #CP_SNAPSHOT}); compiles nothing.
     */
    public static final String OP_SNAPSHOT = "snapshot";

    public static final String OP_IMAGE = "image";
    public static final String OP_PUBLISH = "publish";
    /**
     * Answer the files one of the plugin's {@code [[contribute.lock-closure]]} closures needs
     * ({@code name} on the op line), each a {@link #PIN} reply, for {@code jk lock} to pin.
     */
    public static final String OP_LOCK_CLOSURE = "lock-closure";

    // ---- spec line types (engine → plugin) ------------------------------------------------

    public static final String CONFIG = "config";
    /**
     * The job's network policy, stamped onto every spec at the fork itself (the engine's
     * {@code PluginLaunch}), not by the code that built the spec. A worker never reads {@code
     * JK_OFFLINE} or a system property to find this out: inside a forked JVM those describe the
     * <em>engine daemon's</em> startup environment, so one {@code JK_OFFLINE=1 jk build} would pin
     * every later build in that session. Absent means offline — a worker launched without a stated
     * policy must not reach out.
     */
    public static final String OFFLINE = "offline";

    public static final String PROJECT = "project";
    public static final String MANIFEST_ATTR = "manifest-attr";
    public static final String LAYOUT = "layout";
    public static final String JAVA_HOME = "java-home";
    public static final String ARTIFACT = "artifact";
    public static final String CP = "cp"; // classpath entry, with a `role`
    /**
     * The Zinc analysis another jk compile wrote for a compile-classpath entry: {@code path} is the
     * entry (a sibling's jar or classes dir, or the module's own main classes under a test
     * compile), {@code analysis} the producer's analysis file. The Java worker answers Zinc's
     * per-entry lookup from it, so a real API change in that entry invalidates the consumer's
     * classes one producer class at a time instead of every class that touched the entry.
     */
    public static final String CP_ANALYSIS = "cp-analysis";

    public static final String ENTRY = "entry"; // runtime-closure entry
    public static final String SOURCE = "source"; // a source file (compile)
    public static final String STEP_OUTPUT = "step-output";
    public static final String EXTRA = "extra";
    public static final String SIBLING_FILES =
            "sibling-files"; // {key, path}: one dependency sibling's dir under a config key
    /**
     * One routed remote repository ({@code id}, {@code url}, optional {@code username} and {@code
     * secret}), in resolve order — a declared {@code repositories} input. The secret is a
     * credential; a worker never echoes the line.
     */
    public static final String REPOSITORY = "repository";

    public static final String SECRET = "secret";
    public static final String COMMAND_ARGS = "command-args";
    public static final String ARG = "arg"; // raw compiler-arg passthrough
    public static final String COMPILER_PLUGIN = "compiler-plugin";

    // ---- reply line types (plugin → engine) -----------------------------------------------

    public static final String LABEL = "label"; // free-text progress label
    public static final String COMMAND_OUT = "command-out"; // user-facing output line (command ops)
    /**
     * One file a lock closure needs: {@code coordinate} is {@code group:artifact:version[:classifier][!type]},
     * and {@code path}, when present, where the plugin's resolver holds its bytes.
     */
    public static final String PIN = "pin";

    /**
     * {@code {sev,file?,line?,col?,key?,msg}}: {@code sev} is {@code error}, {@code warning} or
     * {@link #SEVERITY_NOTICE}. A reader treats a severity it does not know as {@code warning}, so a
     * new one degrades to a warning on an older engine instead of being lost.
     */
    public static final String DIAGNOSTIC = "diagnostic";

    /**
     * The {@link #DIAGNOSTIC} severity of a step's notice: something left out of what the step built
     * that the user must act on. The engine shows it on every verdict, a passing build's included.
     */
    public static final String SEVERITY_NOTICE = "notice";

    public static final String PROVENANCE = "provenance"; // {gen,src[]}
    public static final String TEST = "test"; // {event,…} test lifecycle event
    public static final String STEP = "step"; // describe declaration
    public static final String PACKAGER = "packager"; // describe declaration
    public static final String COMMAND = "command"; // describe declaration
    public static final String FINDING = "finding"; // audit vulnerability
    public static final String FILE = "file"; // format per-file outcome
    public static final String WROTE = "wrote"; // compat import wrote a file
    public static final String RESULT = "result"; // terminal typed payload
    /** {@code {files[]}}: the sources one pass of an incremental compile recompiled. */
    public static final String COMPILED = "compiled";

    /** One classpath entry's ABI snapshot: {path, sha256} — the {@link #OP_SNAPSHOT} reply. */
    public static final String CP_SNAPSHOT = "cp-snapshot";
    /** Pull-protocol slot: the worker can accept one COMPILE/PLAN (or DONE). */
    public static final String READY = "ready";

    /**
     * {@code PluginMain --serve}: the worker stays up between specs. It says {@link #READY}, reads
     * {@code RUN <spec-file>} or {@code DONE} from the {@link cc.jumpkick.plugin.process.CommandChannel}
     * the arguments after this one name, runs each spec and answers {@link #EXITED}.
     */
    public static final String SERVE = "--serve";

    /** A served run's end: {@code {code, retire}}; {@code retire} means the worker exits next. */
    public static final String EXITED = "exited";

    public static final String ERROR = "error"; // {code,message}

    // ---- the Java compiler worker's channel ----------------------------------------------------
    //
    // The compiler worker runs many compiles at once and answers on its command channel, one JSON
    // object per line each way. Engine to worker: {"op":"hello"[,"threads":N]}, then
    // {"op":"compile"|"plan","id":n,"spec":path}, {"op":"cancel","id":n}, {"op":"trim"} and
    // {"op":"done"}. Worker to engine: hello{threads,pid,jdk}; diagnostic, provenance, result and
    // error frames that carry the compile's id; cancelled{id}; heap{used_after_gc,committed,max,
    // gc_frac,metaspace,inflight} about once a second and after each old collection; trimmed{used_after_gc}.

    /** The opening exchange, both ways; the worker's answer names its thread count, pid and JDK. */
    public static final String HELLO = "hello";

    /** The compile a frame belongs to, on every frame about one compile. */
    public static final String ID = "id";

    /** Stop the compile named by {@link #ID}; answered {@link #CANCELLED} unless it finished first. */
    public static final String OP_CANCEL = "cancel";

    /** Collect garbage now that nothing is in flight; answered {@link #TRIMMED}. */
    public static final String OP_TRIM = "trim";

    /** Finish what is in flight, then exit. */
    public static final String OP_DONE = "done";

    /** A compile stopped by {@link #OP_CANCEL} before it answered. */
    public static final String CANCELLED = "cancelled";

    /** The worker's heap: what survived the last collection, committed, max, GC share of wall time. */
    public static final String HEAP = "heap";

    /** The answer to {@link #OP_TRIM}: what the heap holds after the collection. */
    public static final String TRIMMED = "trimmed";

    /** The heap bytes that survived the most recent collection, in {@link #HEAP} and {@link #TRIMMED}. */
    public static final String USED_AFTER_GC = "used_after_gc";

    // ---- common field names ---------------------------------------------------------------

    public static final String NAME = "name";
    public static final String PLUGIN = "plugin";
    public static final String KEY = "key";
    public static final String VALUE = "value";
    public static final String VALUES = "values";
    public static final String CONFIG_KIND = "kind"; // string|bool|int|list
    public static final String KIND_STRING = "string";
    public static final String KIND_BOOL = "bool";
    public static final String KIND_INT = "int";
    public static final String KIND_LIST = "list";
    public static final String PATH = "path";
    public static final String ROLE = "role"; // cp entry role
    public static final String ROLE_COMPILE = "compile";
    public static final String ROLE_PROCESSOR = "processor";
    public static final String ROLE_FRIEND = "friend";
    public static final String ROLE_RUNTIME = "runtime";
    /** An {@code entry} of the test runtime closure, beside the production one ({@link #OP_LOCK_CLOSURE}). */
    public static final String ROLE_TEST = "test";
    /** Scala 3 compiler + bridge jars for mixed Java+Scala Zinc (not the project compile CP). */
    public static final String ROLE_COMPILER = "compiler";

    public static final String FILE_NAME = "file";
    public static final String SNAPSHOT = "snapshot";
    public static final String CONTAINER = "container";
    public static final String DIR = "dir";
    public static final String TEXT = "text";
    public static final String LINE = "line";
    public static final String MESSAGE = "message";
    public static final String CODE = "code";
    public static final String EXIT = "exit";
    public static final String OK = "ok";
    public static final String EVENT = "event"; // test event kind
    public static final String SEVERITY = "sev";
    public static final String COL = "col";
    public static final String STATUS = "status";
    /** The producer analysis file on a {@link #CP_ANALYSIS} line. */
    public static final String ANALYSIS = "analysis";

    public static final String SHA256 = "sha256";

    /**
     * Set in the shell that runs {@code jk}, names a file the compiler worker appends one line of
     * phase timings to per module. The engine forwards it to the worker as the {@link
     * #CONFIG_PHASES_LOG} config value, because the worker's own environment is the engine's.
     */
    public static final String COMPILE_PHASES_ENV = "JK_COMPILE_PHASES";

    /** Compile-spec config key carrying {@link #COMPILE_PHASES_ENV}'s value. */
    public static final String CONFIG_PHASES_LOG = "phasesLog";
}

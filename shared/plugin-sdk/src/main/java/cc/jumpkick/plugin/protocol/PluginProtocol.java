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
    public static final String ENTRY = "entry"; // runtime-closure entry
    public static final String SOURCE = "source"; // a source file (compile)
    public static final String STEP_OUTPUT = "step-output";
    public static final String EXTRA = "extra";
    public static final String SECRET = "secret";
    public static final String COMMAND_ARGS = "command-args";
    public static final String ARG = "arg"; // raw compiler-arg passthrough
    public static final String COMPILER_PLUGIN = "compiler-plugin";

    // ---- reply line types (plugin → engine) -----------------------------------------------

    public static final String LABEL = "label"; // free-text progress label
    public static final String COMMAND_OUT = "command-out"; // user-facing output line (command ops)
    public static final String DIAGNOSTIC = "diagnostic"; // {sev,file?,line?,col?,msg}
    public static final String PROVENANCE = "provenance"; // {gen,src[]}
    public static final String TEST = "test"; // {event,…} test lifecycle event
    public static final String STEP = "step"; // describe declaration
    public static final String PACKAGER = "packager"; // describe declaration
    public static final String COMMAND = "command"; // describe declaration
    public static final String FINDING = "finding"; // audit vulnerability
    public static final String FILE = "file"; // format per-file outcome
    public static final String WROTE = "wrote"; // compat import wrote a file
    public static final String RESULT = "result"; // terminal typed payload
    /** One classpath entry's ABI snapshot: {path, sha256} — the {@link #OP_SNAPSHOT} reply. */
    public static final String CP_SNAPSHOT = "cp-snapshot";
    /** Pull-protocol slot: the worker can accept one COMPILE/PLAN (or DONE). */
    public static final String READY = "ready";

    public static final String ERROR = "error"; // {code,message}

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

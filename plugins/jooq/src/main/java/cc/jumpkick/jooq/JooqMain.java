// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jooq;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * jOOQ's {@code GenerationTool} as the Maven plugin drives it: {@code --out <dir> --package <name>
 * [--schema PUBLIC] [--name-case as_is|upper|lower] [--includes <regex>] [--excludes <regex>]
 * [--no-records] [--pojos] [--daos] [--fluent-setters] [--property k=v]… [--jdbc-url <url>
 * [--jdbc-user <u>] [--jdbc-password <p>]] <script>…} writes the generator's XML configuration and
 * runs it. Without a JDBC URL the scripts are read by {@code DDLDatabase}: copied into one
 * directory, applied to an in-memory H2 database in semantic version order ({@code V1__},
 * {@code V2__}, …), the resulting schema generated from. With one, the live database is read and
 * jOOQ detects its dialect from the connection. Runs in the generator step's forked JVM with the
 * generator's closure on the classpath and reaches it by reflection, so this worker compiles
 * against nothing of jOOQ's and the forked JVM needs nothing of jk's.
 *
 * <p>{@code --schema-digest --jdbc-url <url> [--jdbc-user <u>] [--jdbc-password <p>] [--schema
 * PUBLIC]} prints, as its last line, the SHA-256 of jOOQ's own DDL export of the input schema: the
 * step's key probe, so a schema change regenerates and an unchanged one is a cache hit.
 */
public final class JooqMain {

    private static final String GENERATION_TOOL = "org.jooq.codegen.GenerationTool";
    private static final String DDL_DATABASE = "org.jooq.meta.extensions.ddl.DDLDatabase";

    private JooqMain() {}

    public static void main(String[] args) throws Exception {
        int exit = run(args);
        if (exit != 0) System.exit(exit);
    }

    /** What the command line said. */
    record Options(
            Path out,
            String pkg,
            String schema,
            String nameCase,
            String includes,
            String excludes,
            boolean records,
            boolean pojos,
            boolean daos,
            boolean fluentSetters,
            Map<String, String> properties,
            @Nullable String jdbcUrl,
            @Nullable String jdbcUser,
            @Nullable String jdbcPassword,
            List<Path> scripts,
            boolean schemaDigest) {}

    /** The exit status: zero when the generator ran. */
    static int run(String[] args) throws Exception {
        Options options = parse(args);
        if (options.schemaDigest()) {
            System.out.println(schemaDigest(options));
            return 0;
        }
        Path scripts = options.jdbcUrl() == null ? stageScripts(options.scripts()) : null;
        try {
            String xml = configuration(options, scripts);
            generate(xml);
        } finally {
            if (scripts != null) PathUtil.deleteRecursivelyOrThrow(scripts);
        }
        System.out.println("jooq: " + options.scripts().size()
                + (options.scripts().size() == 1 ? " script" : " scripts")
                + (options.jdbcUrl() == null ? "" : " (schema read from " + options.jdbcUrl() + ")")
                + " -> " + options.out());
        return 0;
    }

    static Options parse(String[] args) {
        @Nullable String out = null;
        @Nullable String pkg = null;
        String schema = "PUBLIC";
        String nameCase = "as_is";
        String includes = ".*";
        String excludes = "";
        boolean records = true;
        boolean pojos = false;
        boolean daos = false;
        boolean fluent = false;
        Map<String, String> properties = new LinkedHashMap<>();
        @Nullable String url = null;
        @Nullable String user = null;
        @Nullable String password = null;
        List<Path> scripts = new ArrayList<>();
        boolean digest = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = value(args, ++i);
                case "--package" -> pkg = value(args, ++i);
                case "--schema" -> schema = value(args, ++i);
                case "--name-case" -> nameCase = value(args, ++i);
                case "--includes" -> includes = value(args, ++i);
                case "--excludes" -> excludes = value(args, ++i);
                case "--no-records" -> records = false;
                case "--pojos" -> pojos = true;
                case "--daos" -> daos = true;
                case "--fluent-setters" -> fluent = true;
                case "--property" -> {
                    String pair = value(args, ++i);
                    int eq = pair.indexOf('=');
                    if (eq <= 0) throw new IllegalArgumentException("--property needs key=value, not " + pair);
                    properties.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
                case "--jdbc-url" -> url = value(args, ++i);
                case "--jdbc-user" -> user = value(args, ++i);
                case "--jdbc-password" -> password = value(args, ++i);
                case "--schema-digest" -> digest = true;
                default -> {
                    if (args[i].startsWith("--")) throw new IllegalArgumentException("unknown option " + args[i]);
                    scripts.add(Path.of(args[i]).toAbsolutePath().normalize());
                }
            }
        }
        if (digest) {
            if (url == null)
                throw new IllegalArgumentException("--schema-digest reads a live schema: --jdbc-url is required");
            out = out == null ? "." : out;
            pkg = pkg == null ? "" : pkg;
        }
        if (out == null) throw new IllegalArgumentException("--out <dir> is required");
        if (pkg == null) throw new IllegalArgumentException("--package <name> is required");
        return new Options(
                Path.of(out).toAbsolutePath().normalize(),
                pkg,
                schema,
                nameCase,
                includes,
                excludes,
                records,
                pojos,
                daos,
                fluent,
                properties,
                url,
                user,
                password,
                scripts,
                digest);
    }

    /**
     * The SHA-256 of jOOQ's DDL export of the input schema, read over JDBC through the declared
     * driver: {@code DSL.using(connection).meta().filterSchemas(name).ddl()}, deterministic and
     * dialect-neutral, exactly what the generator reads. A schema the database does not have fails
     * naming the ones it does, since an empty export would key every schema change the same.
     */
    static String schemaDigest(Options options) throws Exception {
        String url = Objects.requireNonNull(options.jdbcUrl(), "--jdbc-url");
        ClassLoader loader = JooqMain.class.getClassLoader();
        Class<?> dsl = Class.forName("org.jooq.impl.DSL", true, loader);
        Class<?> context = Class.forName("org.jooq.DSLContext", true, loader);
        Class<?> metaType = Class.forName("org.jooq.Meta", true, loader);
        Method nameOf = Class.forName("org.jooq.Named", true, loader).getMethod("getName");
        try (Connection connection = DriverManager.getConnection(url, options.jdbcUser(), options.jdbcPassword())) {
            Object meta = context.getMethod("meta")
                    .invoke(dsl.getMethod("using", Connection.class).invoke(null, connection));
            Predicate<Object> wanted = schema -> options.schema().equals(name(nameOf, schema));
            Object filtered =
                    metaType.getMethod("filterSchemas", Predicate.class).invoke(meta, wanted);
            if (((List<?>) metaType.getMethod("getSchemas").invoke(filtered)).isEmpty()) {
                List<String> present = new ArrayList<>();
                for (Object schema : (List<?>) metaType.getMethod("getSchemas").invoke(meta))
                    present.add(name(nameOf, schema));
                throw new IllegalArgumentException(
                        "schema " + options.schema() + " is not in " + url + "; it has " + present);
            }
            String ddl = String.valueOf(metaType.getMethod("ddl").invoke(filtered));
            return Hashing.sha256Hex(ddl);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private static String name(Method nameOf, @Nullable Object named) {
        try {
            return String.valueOf(nameOf.invoke(named));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The scripts copied under one directory, by file name, for DDLDatabase to order and apply. */
    static Path stageScripts(List<Path> scripts) throws Exception {
        if (scripts.isEmpty()) throw new IllegalArgumentException("no DDL script to generate from");
        Path dir = Files.createTempDirectory("jk-jooq-ddl");
        for (Path script : scripts) {
            Path target = dir.resolve(script.getFileName().toString());
            if (Files.exists(target)) {
                throw new IllegalArgumentException("two scripts share the name " + script.getFileName()
                        + "; DDLDatabase applies scripts by file name, so each needs one of its own");
            }
            Files.copy(script, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return dir;
    }

    /** The generator's XML configuration for {@code options}; {@code scripts} is the staged DDL directory, or null over JDBC. */
    static String configuration(Options options, @Nullable Path scripts) {
        StringBuilder xml = new StringBuilder("<configuration>\n");
        if (options.jdbcUrl() != null) {
            xml.append("  <jdbc>\n    <url>").append(escape(options.jdbcUrl())).append("</url>\n");
            if (options.jdbcUser() != null)
                xml.append("    <user>").append(escape(options.jdbcUser())).append("</user>\n");
            if (options.jdbcPassword() != null) {
                xml.append("    <password>")
                        .append(escape(options.jdbcPassword()))
                        .append("</password>\n");
            }
            xml.append("  </jdbc>\n");
        }
        xml.append("  <generator>\n    <database>\n");
        if (scripts != null) {
            xml.append("      <name>").append(DDL_DATABASE).append("</name>\n      <properties>\n");
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put("scripts", scripts.toString());
            properties.put("sort", "semantic");
            properties.put("unqualifiedSchema", "none");
            properties.put("defaultNameCase", options.nameCase());
            properties.putAll(options.properties());
            for (Map.Entry<String, String> property : properties.entrySet()) {
                xml.append("        <property><key>")
                        .append(escape(property.getKey()))
                        .append("</key><value>")
                        .append(escape(property.getValue()))
                        .append("</value></property>\n");
            }
            xml.append("      </properties>\n");
        }
        xml.append("      <inputSchema>").append(escape(options.schema())).append("</inputSchema>\n");
        xml.append("      <outputSchemaToDefault>true</outputSchemaToDefault>\n");
        xml.append("      <includes>").append(escape(options.includes())).append("</includes>\n");
        xml.append("      <excludes>").append(escape(options.excludes())).append("</excludes>\n");
        xml.append("    </database>\n    <generate>\n");
        xml.append("      <records>").append(options.records()).append("</records>\n");
        xml.append("      <pojos>").append(options.pojos() || options.daos()).append("</pojos>\n");
        xml.append("      <daos>").append(options.daos()).append("</daos>\n");
        xml.append("      <fluentSetters>").append(options.fluentSetters()).append("</fluentSetters>\n");
        xml.append("    </generate>\n    <target>\n");
        xml.append("      <packageName>").append(escape(options.pkg())).append("</packageName>\n");
        xml.append("      <directory>").append(escape(options.out().toString())).append("</directory>\n");
        xml.append("      <clean>false</clean>\n    </target>\n  </generator>\n</configuration>\n");
        return xml.toString();
    }

    /** {@code GenerationTool.generate(xml)}, the tool's own exception unwrapped. */
    private static void generate(String xml) throws Exception {
        Class<?> tool = Class.forName(GENERATION_TOOL, true, JooqMain.class.getClassLoader());
        try {
            tool.getMethod("generate", String.class).invoke(null, xml);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String value(String[] args, int at) {
        if (at >= args.length) throw new IllegalArgumentException(args[at - 1] + " needs a value");
        return args[at];
    }
}

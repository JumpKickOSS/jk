// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.avro;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Avro's {@code SpecificCompiler} as the Maven plugin drives it: {@code --out <dir> [--string-type
 * String|CharSequence|Utf8] [--field-visibility PRIVATE|PUBLIC] [--no-setters] [--optional-getters]
 * [--decimal-logical-type] [--encoding <charset>] <file>…} compiles every {@code .avsc} schema,
 * {@code .avpr} protocol and {@code .avdl} IDL file into {@code --out}. The schemas share one
 * parser so a file may name a type another file defines, in any order: a schema whose names are
 * not yet defined waits for the files that define them. Runs in the generator step's forked JVM
 * with the compiler's closure on the classpath and reaches it by reflection, so this worker
 * compiles against nothing of Avro's and the forked JVM needs nothing of jk's.
 */
public final class AvroMain {

    private static final String SCHEMA = "org.apache.avro.Schema";
    private static final String PARSER = "org.apache.avro.Schema$Parser";
    private static final String PROTOCOL = "org.apache.avro.Protocol";
    private static final String COMPILER = "org.apache.avro.compiler.specific.SpecificCompiler";
    private static final String STRING_TYPE = "org.apache.avro.generic.GenericData$StringType";
    private static final String FIELD_VISIBILITY = COMPILER + "$FieldVisibility";
    private static final String IDL_READER = "org.apache.avro.idl.IdlReader";

    private AvroMain() {}

    public static void main(String[] args) throws Exception {
        int exit = run(args);
        if (exit != 0) System.exit(exit);
    }

    /** The compiler's settings, as the command line names them. */
    record Options(
            Path out,
            String stringType,
            String fieldVisibility,
            boolean setters,
            boolean optionalGetters,
            boolean decimalLogicalType,
            @Nullable String encoding) {}

    /** The exit status: zero when every file compiled. */
    static int run(String[] args) throws Exception {
        @Nullable String out = null;
        String stringType = "String";
        String fieldVisibility = "PRIVATE";
        boolean setters = true;
        boolean optionalGetters = false;
        boolean decimal = false;
        @Nullable String encoding = null;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = value(args, ++i);
                case "--string-type" -> stringType = value(args, ++i);
                case "--field-visibility" -> fieldVisibility = value(args, ++i);
                case "--no-setters" -> setters = false;
                case "--optional-getters" -> optionalGetters = true;
                case "--decimal-logical-type" -> decimal = true;
                case "--encoding" -> encoding = value(args, ++i);
                default -> {
                    if (args[i].startsWith("--")) throw new IllegalArgumentException("unknown option " + args[i]);
                    files.add(Path.of(args[i]).toAbsolutePath().normalize());
                }
            }
        }
        if (out == null) throw new IllegalArgumentException("--out <dir> is required");
        Options options = new Options(
                Path.of(out).toAbsolutePath().normalize(),
                stringType,
                fieldVisibility,
                setters,
                optionalGetters,
                decimal,
                encoding);
        int compiled = compile(files, options);
        System.out.println("avro: " + compiled + (compiled == 1 ? " file" : " files") + " -> " + out);
        return 0;
    }

    /** Every file compiled into {@code options.out()}; the count. */
    static int compile(List<Path> files, Options options) throws Exception {
        ClassLoader loader = AvroMain.class.getClassLoader();
        List<Path> schemas = new ArrayList<>();
        List<Path> protocols = new ArrayList<>();
        List<Path> idls = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".avsc")) schemas.add(file);
            else if (name.endsWith(".avpr")) protocols.add(file);
            else if (name.endsWith(".avdl")) idls.add(file);
            else throw new IllegalArgumentException(file + " is not a .avsc, .avpr or .avdl file");
        }
        int compiled = 0;
        Class<?> schemaType = Class.forName(SCHEMA, true, loader);
        Class<?> protocolType = Class.forName(PROTOCOL, true, loader);
        for (Map.Entry<Path, Object> parsed : parseSchemas(loader, schemas).entrySet()) {
            compileOne(loader, schemaType, parsed.getValue(), parsed.getKey(), options);
            compiled++;
        }
        Method parseProtocol = protocolType.getMethod("parse", File.class);
        for (Path protocol : protocols) {
            compileOne(
                    loader, protocolType, require(invoke(parseProtocol, null, protocol.toFile())), protocol, options);
            compiled++;
        }
        if (!idls.isEmpty()) {
            Class<?> readerType = Class.forName(IDL_READER, true, loader);
            Object reader = readerType.getConstructor().newInstance();
            Method parse = readerType.getMethod("parse", Path.class);
            for (Path idl : idls) {
                Object idlFile = require(invoke(parse, reader, idl));
                Object protocol = invoke(idlFile.getClass().getMethod("getProtocol"), idlFile);
                if (protocol != null) {
                    compileOne(loader, protocolType, protocol, idl, options);
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> named = (Map<String, Object>)
                            require(invoke(idlFile.getClass().getMethod("getNamedSchemas"), idlFile));
                    for (Object schema : named.values()) compileOne(loader, schemaType, schema, idl, options);
                }
                compiled++;
            }
        }
        return compiled;
    }

    /**
     * Each schema file's parsed {@code Schema}, in an order that defines every name before its
     * use: a file the parser refuses for an undefined name is retried after the others, and a
     * round that places no file fails with the parser's own message. A fresh parser replays the
     * placed files before each attempt, since a refused parse may have registered part of a file.
     */
    static Map<Path, Object> parseSchemas(ClassLoader loader, List<Path> files) throws Exception {
        Class<?> parserType = Class.forName(PARSER, true, loader);
        Constructor<?> newParser = parserType.getConstructor();
        Method parse = parserType.getMethod("parse", File.class);
        List<Path> placed = new ArrayList<>();
        List<Path> pending = new ArrayList<>(files);
        while (!pending.isEmpty()) {
            @Nullable Exception refused = null;
            boolean progress = false;
            for (Path file : List.copyOf(pending)) {
                Object parser = newParser.newInstance();
                for (Path done : placed) invoke(parse, parser, done.toFile());
                try {
                    invoke(parse, parser, file.toFile());
                } catch (Exception e) {
                    refused = e;
                    continue;
                }
                placed.add(file);
                pending.remove(file);
                progress = true;
            }
            if (!progress) throw refused == null ? new IllegalStateException("no schema parsed") : refused;
        }
        Object parser = newParser.newInstance();
        Map<Path, Object> schemas = new LinkedHashMap<>();
        for (Path file : placed) schemas.put(file, require(invoke(parse, parser, file.toFile())));
        return schemas;
    }

    /** One {@code SpecificCompiler} over a schema or protocol, configured and run into the output. */
    private static void compileOne(ClassLoader loader, Class<?> argType, Object parsed, Path source, Options options)
            throws Exception {
        Class<?> compilerType = Class.forName(COMPILER, true, loader);
        Object compiler = compilerType.getConstructor(argType).newInstance(parsed);
        Class<?> stringType = Class.forName(STRING_TYPE, true, loader);
        invoke(
                compilerType.getMethod("setStringType", stringType),
                compiler,
                enumValue(stringType, options.stringType()));
        Class<?> visibility = Class.forName(FIELD_VISIBILITY, true, loader);
        invoke(
                compilerType.getMethod("setFieldVisibility", visibility),
                compiler,
                enumValue(visibility, options.fieldVisibility()));
        invoke(compilerType.getMethod("setCreateSetters", boolean.class), compiler, options.setters());
        invoke(compilerType.getMethod("setCreateOptionalGetters", boolean.class), compiler, options.optionalGetters());
        invoke(
                compilerType.getMethod("setEnableDecimalLogicalType", boolean.class),
                compiler,
                options.decimalLogicalType());
        if (options.encoding() != null) {
            invoke(compilerType.getMethod("setOutputCharacterEncoding", String.class), compiler, options.encoding());
        }
        invoke(
                compilerType.getMethod("compileToDestination", File.class, File.class),
                compiler,
                source.toFile(),
                options.out().toFile());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        try {
            return Enum.valueOf((Class<? extends Enum>) type, name);
        } catch (IllegalArgumentException e) {
            List<String> names = new ArrayList<>();
            for (Object constant : type.getEnumConstants()) names.add(constant.toString());
            throw new IllegalArgumentException(
                    "\"" + name + "\" is not one of " + names + " (" + type.getSimpleName() + ")", e);
        }
    }

    /** A tool result that its API declares non-null. */
    private static Object require(@Nullable Object value) {
        if (value == null) throw new IllegalStateException("the Avro compiler returned null where a value is due");
        return value;
    }

    /** {@code method} on {@code target}, the tool's own exception unwrapped. */
    private static @Nullable Object invoke(Method method, @Nullable Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private static String value(String[] args, int at) {
        if (at >= args.length) throw new IllegalArgumentException(args[at - 1] + " needs a value");
        return args[at];
    }
}

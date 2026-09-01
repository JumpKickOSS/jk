// SPDX-License-Identifier: Apache-2.0
package demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Dispatches a verb to the {@link Command} that claims it. Both discovery paths name their
 * implementations as text, so neither is visible to R8's reachability analysis.
 */
public final class Cli {

    /** Registry resource whose {@code commands} property lists implementation class names. */
    static final String REGISTRY = "demo/commands.properties";

    public static void main(String[] args) {
        SortedMap<String, Command> commands = discover();
        if (args.length == 0) {
            System.out.println("verbs: " + String.join(", ", commands.keySet()));
            return;
        }
        Command command = commands.get(args[0]);
        if (command != null) {
            System.out.println(command.run(List.of(args).subList(1, args.length)));
            return;
        }
        System.err.println("unknown verb: " + args[0] + " (verbs: " + String.join(", ", commands.keySet()) + ")");
        System.exit(2);
    }

    /** Every command this jar names, keyed by verb, from both by-name conventions. */
    static SortedMap<String, Command> discover() {
        SortedMap<String, Command> byVerb = new TreeMap<>();
        for (Command command : ServiceLoader.load(Command.class)) {
            byVerb.put(command.verb(), command);
        }
        for (String className : registeredClassNames()) {
            Command command = instantiate(className);
            byVerb.put(command.verb(), command);
        }
        return byVerb;
    }

    /**
     * The class names {@value #REGISTRY} lists, in the shape {@code java.util.logging} uses for its
     * handlers: one property whose value is a comma-separated list of implementations.
     */
    static List<String> registeredClassNames() {
        Properties registry = new Properties();
        try (InputStream in = Cli.class.getResourceAsStream("/" + REGISTRY)) {
            if (in == null) return List.of();
            registry.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + REGISTRY, e);
        }
        return Arrays.stream(registry.getProperty("commands", "").split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    /**
     * The command {@code className} names. A registry entry naming a class this jar does not carry
     * is a packaging fault, so the failure names both the resource and the class rather than
     * leaving the verb quietly missing.
     */
    static Command instantiate(String className) {
        try {
            return Class.forName(className)
                    .asSubclass(Command.class)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(REGISTRY + " names " + className + ", which this jar does not carry", e);
        }
    }

    private Cli() {}
}

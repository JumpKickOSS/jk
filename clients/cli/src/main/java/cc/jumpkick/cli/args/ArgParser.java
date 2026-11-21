// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.args;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.command.Command;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Recursive-descent parser of argv against a {@link Command}'s {@link Opt}s/{@link Param}s (long
 * and short options, negatable flags, arity). Subcommand dispatch happens before parsing.
 */
public final class ArgParser {

    /**
     * A HotSpot {@code -X} flag. Short options bundle, so without this check {@code -Xmx2g} would
     * parse as {@code -X -m x2g} on a command that declares both.
     */
    private static final Pattern JVM_FLAG = Pattern.compile(
            "-X(X[:+-].*|m[sxn]\\d.*|ss\\d.*|log(:.*)?|share:.*|int|comp|mixed|rs|noclassgc|debug|diag|check:.*|future)");

    private ArgParser() {}

    public static Invocation parse(Command command, List<String> args) throws ParseException {
        return parse(command, args, false);
    }

    /**
     * @param passthroughUnknown when true, unrecognized options and every short option are positional
     *     arguments rather than jk's — passthrough commands like {@code jk mvn} / {@code jk gradle}
     *     forward them to a child process and take only their own options' long spellings.
     */
    public static Invocation parse(Command command, List<String> args, boolean passthroughUnknown)
            throws ParseException {
        Map<String, Opt> byName = new HashMap<>();
        for (Opt opt : command.options()) {
            for (String n : opt.allNames()) byName.put(n, opt);
        }

        Invocation.Builder out = Invocation.builder();
        boolean positionalsOnly = false;

        for (int i = 0; i < args.size(); i++) {
            String tok = args.get(i);

            if (positionalsOnly || tok.equals("-") || !tok.startsWith("-")) {
                out.addPositional(tok);
                continue;
            }
            if (tok.equals("--")) {
                positionalsOnly = true;
                out.markEndOfOptions();
                continue;
            }

            if (tok.startsWith("--")) {
                i = parseLong(command, byName, out, args, i, tok, passthroughUnknown);
            } else if (passthroughUnknown) {
                // A short option after a passthrough command's name is the child tool's: `jk mvn
                // install -X` is Maven's debug flag, whatever letter jk's own options carry.
                out.addPositional(tok);
            } else {
                i = parseShort(byName, out, args, i, tok);
            }
        }

        validate(command, out);
        return out.build();
    }

    /**
     * The options of {@code command} that appear in {@code args}, read before the command that owns
     * the rest is known: every token the table does not own is skipped, a value missing at the end
     * is dropped, and nothing throws. Long names are matched exactly from {@code abbreviateBefore}
     * on, where another command's options share the line and a prefix could name either; before it
     * they may be abbreviated as the parser allows. A short cluster is read until its first letter
     * the table does not own. A literal {@code --} ends the scan.
     */
    public static Invocation scan(Command command, List<String> args, int abbreviateBefore) {
        Map<String, Opt> byName = new HashMap<>();
        for (Opt opt : command.options()) {
            for (String n : opt.allNames()) byName.put(n, opt);
        }
        Invocation.Builder out = Invocation.builder();
        for (int i = 0; i < args.size(); i++) {
            String tok = args.get(i);
            if (tok.equals("--")) break;
            if (!looksLikeOption(tok)) continue;
            try {
                if (tok.startsWith("--")) {
                    i = scanLong(byName, out, args, i, tok, i < abbreviateBefore);
                } else {
                    i = scanShort(byName, out, args, i, tok);
                }
            } catch (ParseException notOurs) {
                // The real parse, once the command is known, reports it.
            }
        }
        return out.build();
    }

    private static int scanLong(
            Map<String, Opt> byName, Invocation.Builder out, List<String> args, int i, String tok, boolean abbreviate)
            throws ParseException {
        int eq = tok.indexOf('=');
        String name = eq >= 0 ? tok.substring(0, eq) : tok;
        String inline = eq >= 0 ? tok.substring(eq + 1) : null;
        Opt opt = abbreviate ? Abbreviations.resolve(name, byName).value() : byName.get(name);
        if (opt != null) return consume(out, opt, args, i, inline);
        if (name.startsWith("--no-")) {
            String base = "--" + name.substring("--no-".length());
            Opt neg = abbreviate ? Abbreviations.resolve(base, byName).value() : byName.get(base);
            if (neg != null && neg.negatable() && !neg.takesValue()) {
                out.flag(neg.canonicalName(), !inlineBoolean(name, inline));
            }
        }
        return i;
    }

    private static int scanShort(Map<String, Opt> byName, Invocation.Builder out, List<String> args, int i, String tok)
            throws ParseException {
        for (int c = 1; c < tok.length(); c++) {
            Opt opt = byName.get("-" + tok.charAt(c));
            if (opt == null) return i;
            if (opt.takesValue()) {
                String rest = tok.substring(c + 1);
                return consume(out, opt, args, i, rest.isEmpty() ? null : rest);
            }
            out.flag(opt.canonicalName(), true);
        }
        return i;
    }

    // --- long options: --name, --name=value, --no-name -----------------------

    private static int parseLong(
            Command command,
            Map<String, Opt> byName,
            Invocation.Builder out,
            List<String> args,
            int i,
            String tok,
            boolean passthroughUnknown)
            throws ParseException {
        String name = tok;
        String inline = null;
        int eq = tok.indexOf('=');
        if (eq >= 0) {
            name = tok.substring(0, eq);
            inline = tok.substring(eq + 1);
        }

        // Passthrough commands (jk mvn / gradle) match only their own options exactly and forward
        // everything else — abbreviation would risk eating a flag meant for the child process.
        Opt opt;
        if (passthroughUnknown) {
            opt = byName.get(name);
        } else {
            Abbreviations.Result<Opt> r = Abbreviations.resolve(name, byName);
            if (r.kind() == Abbreviations.Kind.AMBIGUOUS) throw ambiguous(tok, name, r.candidates());
            opt = r.value();
        }
        if (opt != null) {
            return consume(out, opt, args, i, inline);
        }
        // Negatable: --no-foo turns off a negatable flag --foo (exact, or a unique prefix of it).
        if (name.startsWith("--no-")) {
            String base = "--" + name.substring("--no-".length());
            Opt neg;
            if (passthroughUnknown) {
                neg = byName.get(base);
            } else {
                Abbreviations.Result<Opt> r = Abbreviations.resolve(base, byName);
                if (r.kind() == Abbreviations.Kind.AMBIGUOUS) throw ambiguous(tok, name, r.candidates());
                neg = r.value();
            }
            if (neg != null && neg.negatable() && !neg.takesValue()) {
                out.flag(neg.canonicalName(), !inlineBoolean(name, inline));
                return i;
            }
        }
        if (passthroughUnknown) {
            out.addPositional(tok); // treat unknown option as passthrough arg
            return i;
        }
        throw new ParseException(ParseException.Kind.UNKNOWN_OPTION, tok, "unrecognized option " + name);
    }

    // --- short options: -n value, -nvalue, -qv (bundled flags) ---------------

    private static int parseShort(Map<String, Opt> byName, Invocation.Builder out, List<String> args, int i, String tok)
            throws ParseException {
        if (JVM_FLAG.matcher(tok).matches()) {
            throw new ParseException(
                    ParseException.Kind.JVM_OPTION,
                    tok,
                    tok + " is a JVM option, not a jk option; pass it with --jvm-arg=" + tok);
        }
        // Walk the cluster char by char; a value-taking short consumes the rest
        // of the token (or the next arg) and ends the cluster.
        for (int c = 1; c < tok.length(); c++) {
            String shortName = "-" + tok.charAt(c);
            Opt opt = byName.get(shortName);
            if (opt == null) {
                throw new ParseException(
                        ParseException.Kind.UNKNOWN_OPTION, shortName, "unrecognized option " + shortName);
            }
            if (opt.takesValue()) {
                String rest = tok.substring(c + 1);
                String inline = rest.isEmpty() ? null : rest;
                return consume(out, opt, args, i, inline);
            }
            out.flag(opt.canonicalName(), true); // boolean short; continue the cluster
        }
        return i;
    }

    // --- shared value/flag consumption ---------------------------------------

    private static int consume(Invocation.Builder out, Opt opt, List<String> args, int i, @Nullable String inline)
            throws ParseException {
        if (!opt.takesValue()) {
            out.flag(opt.canonicalName(), inlineBoolean(opt.names().getLast(), inline));
            return i;
        }
        String value = inline;
        int consumed = i;
        if (value == null) {
            // Take the next token as the value, unless this is an optional-arg
            // option (then a missing/at-end value yields the fallback).
            if (i + 1 < args.size() && !looksLikeOption(args.get(i + 1))) {
                value = args.get(i + 1);
                consumed = i + 1;
            } else if (opt.fallbackValue() != null) {
                value = opt.fallbackValue();
            } else {
                String name = opt.names().getLast();
                String message = "option " + name + " requires a value";
                // A value that starts with '-' is never taken from the next token, so name the
                // spelling that carries it: --jvm-arg=-Xmx2g, or -m-core for a short option.
                if (i + 1 < args.size()) {
                    message += "; write " + name + (name.startsWith("--") ? "=" : "") + args.get(i + 1);
                }
                throw new ParseException(ParseException.Kind.MISSING_VALUE, opt.canonicalName(), message);
            }
        }
        if (opt.split() != null && !opt.split().isEmpty()) {
            for (String part : value.split(Pattern.quote(opt.split()), -1)) {
                out.addValue(opt.canonicalName(), part);
            }
        } else if (opt.repeatable()) {
            out.addValue(opt.canonicalName(), value);
        } else {
            out.putValue(opt.canonicalName(), value);
        }
        return consumed;
    }

    /**
     * The state a boolean flag written {@code --flag} or {@code --flag=<inline>} asks for: true when
     * no value is given, else the value read as jk reads every boolean ({@link EnvValues#parseBool}:
     * true/false, yes/no, on/off, 1/0). So {@code
     * --skip-tests=false} leaves tests on and {@code --no-foo=false} leaves {@code foo} on.
     */
    private static boolean inlineBoolean(String name, @Nullable String inline) throws ParseException {
        if (inline == null) return true;
        Optional<Boolean> value = EnvValues.parseBool(inline);
        if (value.isPresent()) return value.get();
        throw new ParseException(
                ParseException.Kind.UNEXPECTED_VALUE,
                name + "=" + inline,
                "option " + name + " takes no value; write " + name + " or " + name + "=true|false");
    }

    private static ParseException ambiguous(String tok, String name, List<String> candidates) {
        return new ParseException(
                ParseException.Kind.AMBIGUOUS_OPTION,
                tok,
                "option " + name + " is ambiguous: " + String.join(", ", candidates));
    }

    private static boolean looksLikeOption(String tok) {
        // "-" alone is a value (stdin convention); "-x"/"--x" are options.
        return tok.length() > 1 && tok.charAt(0) == '-';
    }

    // --- validation ----------------------------------------------------------

    private static void validate(Command command, Invocation.Builder builder) throws ParseException {
        Invocation snapshot = builder.build();
        for (Opt opt : command.options()) {
            if (opt.required() && !snapshot.has(opt.canonicalName())) {
                throw new ParseException(
                        ParseException.Kind.MISSING_REQUIRED,
                        opt.names().get(opt.names().size() - 1),
                        "missing required option " + opt.names().get(opt.names().size() - 1));
            }
        }

        int given = snapshot.positionals().size();
        int min = 0;
        long max = 0;
        for (Param p : command.parameters()) {
            min += p.arity().min();
            max += p.arity().max();
            if (max < 0) max = Integer.MAX_VALUE; // overflow guard
        }
        if (given < min) {
            Param firstUnsatisfied = command.parameters().stream()
                    .filter(p -> p.arity().required())
                    .findFirst()
                    .orElse(null);
            String name = firstUnsatisfied != null ? firstUnsatisfied.name() : "argument";
            throw new ParseException(
                    ParseException.Kind.MISSING_REQUIRED, name, "missing required argument <" + name + ">");
        }
        if (given > max) {
            throw new ParseException(
                    ParseException.Kind.TOO_MANY_ARGS,
                    snapshot.positionals().get((int) max),
                    "unexpected extra argument: " + snapshot.positionals().get((int) max));
        }
    }
}

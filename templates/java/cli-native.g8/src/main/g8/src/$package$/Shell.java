package $package$;

/** Pure command evaluator — the REPL loop stays trivial and this stays testable. */
public final class Shell {

    public record Reply(String text, boolean exit) {}

    public Reply eval(String line) {
        String cmd = line == null ? "" : line.trim();
        if (cmd.isEmpty()) return new Reply("", false);
        if (cmd.equals("exit") || cmd.equals("quit")) return new Reply("", true);
        if (cmd.equals("help")) return new Reply("commands: help, echo <text>, exit", false);
        if (cmd.startsWith("echo ")) return new Reply(cmd.substring(5), false);
        return new Reply("unknown command: " + cmd + " (try 'help')", false);
    }
}

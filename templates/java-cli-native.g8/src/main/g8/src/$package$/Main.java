package $package$;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class Main {

    public static void main(String[] args) throws Exception {
        // dumb(true): fall back to a plain terminal under pipes/CI and in a native image
        // without extra terminal-provider configuration.
        try (Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .appName("$name$")
                    .build();
            terminal.writer().println("$name$ — type 'help'; 'exit' to quit.");
            terminal.flush();
            Shell shell = new Shell();
            while (true) {
                String line;
                try {
                    line = reader.readLine("$name$> ");
                } catch (UserInterruptException e) {
                    continue; // Ctrl-C clears the line
                } catch (EndOfFileException e) {
                    break; // Ctrl-D exits
                }
                Shell.Reply reply = shell.eval(line);
                if (reply.exit()) break;
                if (!reply.text().isEmpty()) terminal.writer().println(reply.text());
                terminal.flush();
            }
        }
    }

    private Main() {}
}

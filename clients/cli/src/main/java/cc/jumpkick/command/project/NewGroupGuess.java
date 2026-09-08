// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Default {@code groupId} for {@code jk init} from git user email: GitHub → {@code io.github.*},
 * free-mail → {@code com.example}, else reverse domain.
 */
public final class NewGroupGuess {

    static final String FALLBACK = "com.example";

    private static final Set<String> FREE_MAIL = Set.of(
            "gmail.com",
            "googlemail.com",
            "outlook.com",
            "hotmail.com",
            "live.com",
            "msn.com",
            "proton.me",
            "protonmail.com",
            "yahoo.com",
            "ymail.com",
            "icloud.com",
            "me.com",
            "mac.com",
            "fastmail.com",
            "aol.com",
            "duck.com",
            "pm.me");

    private static final Pattern EMAIL_LINE =
            Pattern.compile("^\\s*email\\s*=\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);

    private NewGroupGuess() {}

    public static String guess() {
        return guess(
                Path.of(".").toAbsolutePath().normalize(),
                Optional.ofNullable(System.getProperty("user.home"))
                        .map(Path::of)
                        .orElse(null));
    }

    public static String guess(Path cwd, @Nullable Path home) {
        return readEmail(cwd, home).map(NewGroupGuess::groupForEmail).orElse(FALLBACK);
    }

    public static String groupForEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) return FALLBACK;
        String localPart = email.substring(0, at).toLowerCase(Locale.ROOT);
        String domain = email.substring(at + 1).toLowerCase(Locale.ROOT);

        if (domain.equals("github.com") || domain.equals("github.io") || domain.equals("users.noreply.github.com")) {
            String user = stripNoreplyPrefix(localPart);
            if (user.isEmpty()) return FALLBACK;
            return "io.github." + sanitizeIdentifier(user);
        }
        if (FREE_MAIL.contains(domain)) return FALLBACK;
        return reverseDomain(domain);
    }

    private static String stripNoreplyPrefix(String localPart) {
        int plus = localPart.indexOf('+');
        if (plus < 0) return localPart;
        return localPart.substring(plus + 1);
    }

    static String reverseDomain(String domain) {
        var labels = List.of(domain.split("\\."));
        if (labels.isEmpty()) return FALLBACK;
        var sb = new StringBuilder();
        for (int i = labels.size() - 1; i >= 0; i--) {
            var label = sanitizeIdentifier(labels.get(i));
            if (label.isEmpty()) continue;
            if (sb.length() > 0) sb.append('.');
            sb.append(label);
        }
        return sb.length() == 0 ? FALLBACK : sb.toString();
    }

    private static String sanitizeIdentifier(String label) {
        var sb = new StringBuilder();
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') sb.append(c);
        }
        if (sb.length() == 0) return "";
        if (Character.isDigit(sb.charAt(0))) sb.insert(0, '_');
        return sb.toString();
    }

    static Optional<String> readEmail(Path cwd, @Nullable Path home) {
        for (Path p = cwd; p != null; p = p.getParent()) {
            var repo = parseEmail(p.resolve(".git").resolve("config"));
            if (repo.isPresent()) return repo;
            var found = parseEmail(p.resolve(".gitconfig"));
            if (found.isPresent()) return found;
        }
        if (home != null) {
            var found = parseEmail(home.resolve(".gitconfig"));
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    private static Optional<String> parseEmail(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            boolean inUser = false;
            for (var raw : Files.readAllLines(file)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                if (line.startsWith("[")) {
                    inUser = line.equalsIgnoreCase("[user]");
                    continue;
                }
                if (!inUser) continue;
                Matcher m = EMAIL_LINE.matcher(line);
                if (m.matches()) {
                    String value = stripQuotes(m.group(1).trim());
                    if (!value.isEmpty()) return Optional.of(value);
                }
            }
        } catch (IOException ignored) {
        }
        return Optional.empty();
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}

package cc.jumpkick.config;

/** Stub of the redactor by its real name. */
public final class SecretRedactor {
    private SecretRedactor() {}

    public static SecretRedactor none() {
        return new SecretRedactor();
    }

    public static SecretRedactor from(Object lookup) {
        return new SecretRedactor();
    }

    public String redact(String text) {
        return text;
    }
}

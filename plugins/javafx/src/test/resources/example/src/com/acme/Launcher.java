package com.acme;

/**
 * The main class: not an {@code Application}, so the fat jar runs with {@code java -jar} too.
 * {@code --check} reports the JavaFX module the JVM loaded instead of opening a window.
 */
public final class Launcher {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--check")) {
            System.out.println("javafx.controls named="
                    + ModuleLayer.boot().findModule("javafx.controls").isPresent());
            return;
        }
        javafx.application.Application.launch(HelloApp.class, args);
    }
}

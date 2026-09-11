// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

/** The IDEs {@code jk ide} can generate project configuration for. */
public enum IdeTarget {
    /** IntelliJ IDEA — {@code .idea/} + {@code *.iml}. */
    IDEA("JetBrains IDEA"),
    /** VS Code — {@code .vscode/} + Eclipse project metadata for the redhat.java language server. */
    VSCODE("VS Code");

    private final String label;

    IdeTarget(String label) {
        this.label = label;
    }

    /** Human label for the shared {@code IDE} wedge phase ({@code JetBrains IDEA}, {@code VS Code}). */
    public String label() {
        return label;
    }
}

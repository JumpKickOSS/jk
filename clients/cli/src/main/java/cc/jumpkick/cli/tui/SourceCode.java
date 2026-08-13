// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.run.SyntaxHighlight;
import java.util.ArrayList;
import java.util.List;

/**
 * Syntax-highlighted source as a list of {@link RichText} lines. Use {@link JavaCode},
 * {@link KotlinCode}, or {@link GroovyCode}.
 */
public abstract class SourceCode implements Widget {

    private final String source;
    private final SyntaxHighlight.Language language;

    protected SourceCode(String source, SyntaxHighlight.Language language) {
        this.source = source == null ? "" : source;
        this.language = language;
    }

    public static JavaCode java(String source) {
        return new JavaCode(source);
    }

    public static KotlinCode kotlin(String source) {
        return new KotlinCode(source);
    }

    public static GroovyCode groovy(String source) {
        return new GroovyCode(source);
    }

    public String source() {
        return source;
    }

    public SyntaxHighlight.Language language() {
        return language;
    }

    /** One RichText per source line (no trailing newline). */
    public List<RichText> lines() {
        if (source.isEmpty()) return List.of(RichText.empty());
        String[] raw = source.split("\n", -1);
        var out = new ArrayList<RichText>(raw.length);
        for (String line : raw) {
            out.add(SyntaxHighlight.highlightRich(line, language));
        }
        return List.copyOf(out);
    }

    @Override
    public List<String> render(RenderContext ctx) {
        var painted = new ArrayList<String>();
        for (RichText line : lines()) {
            painted.add(line.render(ctx));
        }
        return painted;
    }

    public static final class JavaCode extends SourceCode {
        public JavaCode(String source) {
            super(source, SyntaxHighlight.Language.JAVA);
        }
    }

    public static final class KotlinCode extends SourceCode {
        public KotlinCode(String source) {
            super(source, SyntaxHighlight.Language.KOTLIN);
        }
    }

    public static final class GroovyCode extends SourceCode {
        public GroovyCode(String source) {
            super(source, SyntaxHighlight.Language.GROOVY);
        }
    }
}

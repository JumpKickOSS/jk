// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.host.CodeText;
import org.jspecify.annotations.Nullable;

/**
 * A text rule's {@code blank}: the view of a file its pattern reads. The four code views are {@link
 * CodeText}'s and read Markdown whole. {@link #PROSE} is the human-language text of any file: a
 * source file's comments, Markdown outside its code. It has a name of its own so that no code
 * view's spelling means one thing in a source file and another in a document.
 */
enum TextView {
    /** Comments blanked; code and literals kept. The default. */
    COMMENTS("comments", CodeText.Blank.COMMENTS),
    /** Comments and literal bodies blanked; code alone. */
    COMMENTS_AND_STRINGS("comments+strings", CodeText.Blank.COMMENTS_AND_STRINGS),
    /** Nothing blanked. */
    NONE("none", CodeText.Blank.NONE),
    /** Code and literals blanked; comments alone. */
    CODE("code", CodeText.Blank.CODE),
    /**
     * What a person wrote as language: comments in source, and in Markdown the text outside fences,
     * code spans, HTML, link targets and URLs ({@link MarkdownProse}). Config files read whole.
     */
    PROSE("prose", CodeText.Blank.CODE);

    private final String spelling;
    private final CodeText.Blank blank;

    TextView(String spelling, CodeText.Blank blank) {
        this.spelling = spelling;
        this.blank = blank;
    }

    /** As written in {@code blank}. */
    String spelling() {
        return spelling;
    }

    /** What the code lexer blanks for this view in a source file. */
    CodeText.Blank blank() {
        return blank;
    }

    /** The {@code blank} spelling → the view; absent or unknown is the default (the loader refuses unknown). */
    static TextView of(@Nullable String spelling) {
        if (spelling != null) {
            for (TextView v : values()) if (v.spelling.equals(spelling)) return v;
        }
        return COMMENTS;
    }

    /**
     * {@code text} through this view, unsquashed: Markdown's prose for {@link #PROSE} over a prose
     * file, the lexer's blanking over a lexable file, and the text as written otherwise.
     */
    String apply(String text, TextFiles.Language lang) {
        if (this == PROSE && lang == TextFiles.Language.PROSE) return MarkdownProse.blank(text);
        if (!lang.lexable || this == NONE) return text;
        return CodeText.blank(text, blank, lang == TextFiles.Language.JS);
    }
}

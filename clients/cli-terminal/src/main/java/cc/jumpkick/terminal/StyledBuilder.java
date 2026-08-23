// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import java.util.ArrayList;
import java.util.List;

/** Builder for {@link Styled}. */
public final class StyledBuilder {
    private final List<Styled.Span> spans = new ArrayList<>();

    public StyledBuilder append(String text, Style style) {
        spans.add(new Styled.Span(text, style));
        return this;
    }

    public StyledBuilder append(Styled other) {
        spans.addAll(other.spans());
        return this;
    }

    public Styled build() {
        return new Styled(spans);
    }
}

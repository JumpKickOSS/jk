// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import java.util.ArrayList;
import java.util.List;
import org.jline.utils.AttributedString;

/**
 * A tree of pills, labels, and hanging rich text. Title and root are optional so the same widget
 * covers {@code jk explain}'s Build Graph, {@code jk tree}, {@code jk jobs}, IDE summaries, and the
 * live plan rows.
 *
 * <p>{@link Gap} is the blank-spine policy. {@link BodyFit} is how hanging body lines attach. Nodes
 * describe <em>what</em>; {@link RenderContext} paints nerd / ansi / plain rails.
 */
public final class Tree implements Widget {

    public enum Gap {
        /** No blank spines (dependency tree, live plan, IDE lists). */
        NONE,
        /** One blank spine after a parent before its child nodes (Build Graph). */
        CHILDREN,
        /** Blank spine before every child, including the first ({@code jk jobs}). */
        EACH
    }

    public enum BodyFit {
        /** {@code ╰─} hanging leaf (phase chain, cached names). */
        HANG,
        /** {@code │} annotation under the node ({@code jk tree} scope line). */
        RAIL,
        /** Indent only (live-plan error brief). */
        INDENT
    }

    private static final String BRANCH = "├─";
    private static final String LAST = "╰─";
    private static final String SPINE = "│";
    private static final String BRANCH_PLAIN = "+-";
    private static final String LAST_PLAIN = "`-";
    private static final String SPINE_PLAIN = "|";

    /** Visible columns of {@code ├─}/{@code ╰─}, plus one so children line up past a flush pill. */
    private static final String CHILD_PAD = "   ";

    private final JkWedge title;
    private Node root;
    private final List<Node> children = new ArrayList<>();
    private Gap gap = Gap.CHILDREN;

    public Tree(String title) {
        this(title == null || title.isEmpty() ? null : JkWedge.menu(title));
    }

    public Tree(JkWedge title) {
        this.title = title;
    }

    /** No title line — live plan rows, or a fragment embedded under other chrome. */
    public static Tree untitled() {
        return new Tree((JkWedge) null);
    }

    public Tree gap(Gap gap) {
        this.gap = gap == null ? Gap.CHILDREN : gap;
        return this;
    }

    public Tree root(Node root) {
        this.root = root;
        return this;
    }

    public Tree child(Node node) {
        if (node != null) children.add(node);
        return this;
    }

    public Tree children(Node... nodes) {
        if (nodes != null) {
            for (Node node : nodes) child(node);
        }
        return this;
    }

    public JkWedge title() {
        return title;
    }

    public Node root() {
        return root;
    }

    public Gap gap() {
        return gap;
    }

    public static Node node(RichText label) {
        return new Node(null, null, label);
    }

    public static Node node(String label) {
        return node(RichText.plain(label == null ? "" : label));
    }

    public static Node node(Icon bullet, RichText label) {
        return new Node(bullet, null, label);
    }

    public static Node node(Icon bullet, String label) {
        return node(bullet, RichText.plain(label == null ? "" : label));
    }

    public static Node node(Pill pill) {
        return new Node(null, pill, RichText.empty());
    }

    public static Node node(Pill pill, RichText suffix) {
        return new Node(null, pill, spaced(suffix));
    }

    public static Node node(Pill pill, String suffix) {
        return node(pill, suffix == null || suffix.isEmpty() ? RichText.empty() : RichText.plain(suffix));
    }

    private static RichText spaced(RichText suffix) {
        if (suffix == null || suffix.isEmpty()) return RichText.empty();
        if (suffix.plainText().startsWith(" ")) return suffix;
        return RichText.plain(" ").plus(suffix);
    }

    /**
     * Recover a forest from already-painted tree lines (engine {@code jk tree} body, or a previous
     * render). Rails are re-painted; labels keep their styling.
     */
    public static List<Node> forest(List<String> paintedLines) {
        if (paintedLines == null || paintedLines.isEmpty()) return List.of();
        record Frame(int depth, Node node) {}
        var roots = new ArrayList<Node>();
        var stack = new ArrayList<Frame>();
        for (String line : paintedLines) {
            Parsed p = parsePainted(line);
            if (p == null) continue;
            Node node = node(RichText.ansi(p.label));
            if (p.flush) node.flush(true);
            while (!stack.isEmpty() && stack.getLast().depth >= p.depth) stack.removeLast();
            if (stack.isEmpty()) roots.add(node);
            else stack.getLast().node.child(node);
            stack.add(new Frame(p.depth, node));
        }
        return List.copyOf(roots);
    }

    @Override
    public List<String> render(RenderContext ctx) {
        var lines = new ArrayList<String>();
        if (title != null) lines.add(title.renderLine(ctx));
        if (root != null) {
            renderNode(root, " ", true, true, ctx, gap, lines);
        } else {
            renderForest(children, " ", ctx, gap, lines);
        }
        return List.copyOf(lines);
    }

    private static void renderForest(
            List<Node> nodes, String prefix, RenderContext ctx, Gap inherited, List<String> lines) {
        for (int i = 0; i < nodes.size(); i++) {
            if (wantsGap(inherited, i)) lines.add(prefix + rail(SPINE, ctx));
            renderNode(nodes.get(i), prefix, i == nodes.size() - 1, false, ctx, inherited, lines);
        }
    }

    private static void renderNode(
            Node node,
            String prefix,
            boolean last,
            boolean isRoot,
            RenderContext ctx,
            Gap inherited,
            List<String> lines) {
        Gap gap = node.gap != null ? node.gap : inherited;
        String childPrefix;
        if (isRoot) {
            lines.add(prefix + rootHead(node, ctx));
            childPrefix = prefix;
        } else {
            String label = nodeLabel(node, ctx);
            if (!flush(node) && !label.isEmpty()) label = " " + label;
            lines.add(prefix + rail(last ? LAST : BRANCH, ctx) + label);
            childPrefix = prefix + (last ? CHILD_PAD : rail(SPINE, ctx) + "  ");
        }

        BodyFit fit = node.bodyFit == null ? BodyFit.HANG : node.bodyFit;
        for (int i = 0; i < node.body.size(); i++) {
            String painted = node.body.get(i).render(ctx);
            lines.add(childPrefix + bodyPrefix(fit, i, ctx) + painted);
        }

        renderForest(node.children, childPrefix, ctx, gap, lines);
    }

    private static String bodyPrefix(BodyFit fit, int index, RenderContext ctx) {
        return switch (fit) {
            case HANG -> index == 0 ? rail(LAST + " ", ctx) : CHILD_PAD;
            case RAIL -> index == 0 ? rail(SPINE, ctx) + " " : "  ";
            case INDENT -> "";
        };
    }

    private static boolean wantsGap(Gap gap, int index) {
        return switch (gap) {
            case NONE -> false;
            case CHILDREN -> index == 0;
            case EACH -> true;
        };
    }

    private static boolean flush(Node node) {
        return node.flush || node.pill != null;
    }

    private static String rootHead(Node node, RenderContext ctx) {
        String label = nodeLabel(node, ctx);
        if (node.bullet == null) return label;
        String glyph = node.bullet.paint(ctx);
        if (ctx.ansi()) glyph = Theme.colorize(glyph, ctx.theme().darkGray());
        if (label.isEmpty()) return glyph;
        return glyph + " " + label;
    }

    private static String nodeLabel(Node node, RenderContext ctx) {
        var sb = new StringBuilder();
        if (node.pill != null) sb.append(node.pill.renderInline(ctx));
        if (!node.label.isEmpty()) sb.append(node.label.render(ctx));
        return sb.toString();
    }

    private static String rail(String unicode, RenderContext ctx) {
        if (!ctx.ansi()) {
            return switch (unicode) {
                case BRANCH -> BRANCH_PLAIN;
                case LAST -> LAST_PLAIN;
                case SPINE -> SPINE_PLAIN;
                case LAST + " " -> LAST_PLAIN + " ";
                case BRANCH + " " -> BRANCH_PLAIN + " ";
                default -> unicode;
            };
        }
        return Theme.colorize(unicode, ctx.theme().darkGray());
    }

    private record Parsed(int depth, String label, boolean flush) {}

    private static Parsed parsePainted(String line) {
        if (line == null || line.isEmpty()) return null;
        String vis = AttributedString.stripAnsi(line);
        int origin = 0;
        if (startsWithConnector(vis, 1) && vis.startsWith(" ")) origin = 1;
        int i = origin;
        int depth = 0;
        // Consume every rail pad (`│  ` / `|  ` / `   `), including stacked pads under
        // nested last-children, so transitive rows survive `forest()`.
        while (isPad(vis, i) && (startsWithConnector(vis, i + 3) || isPad(vis, i + 3))) {
            i += 3;
            depth++;
        }
        if (!startsWithConnector(vis, i)) return null;
        int conn = connectorLen(vis, i);
        String after = vis.substring(i + conn);
        boolean flush = !after.startsWith(" ");
        int labelVis = i + conn + (flush ? 0 : 1);
        String label = sliceFromVisible(line, labelVis);
        String lead = leadingSgr(line);
        if (lead != null && !label.startsWith("\u001B")) label = lead + label;
        return new Parsed(depth, label, flush);
    }

    /** First CSI on the line, if any — so a whole-row style (back-references) survives the slice. */
    private static String leadingSgr(String raw) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) != 0x1B) return null;
        int end = skipEscape(raw, 0);
        return end > 0 ? raw.substring(0, end) : null;
    }

    private static boolean isPad(String vis, int at) {
        if (at + 3 > vis.length()) return false;
        String tri = vis.substring(at, at + 3);
        return tri.equals("│  ") || tri.equals("|  ") || tri.equals("   ");
    }

    private static boolean startsWithConnector(String vis, int at) {
        if (at < 0 || at >= vis.length()) return false;
        String rest = vis.substring(at);
        return rest.startsWith(BRANCH)
                || rest.startsWith(LAST)
                || rest.startsWith(BRANCH_PLAIN)
                || rest.startsWith(LAST_PLAIN);
    }

    private static int connectorLen(String vis, int at) {
        String rest = vis.substring(at);
        if (rest.startsWith(BRANCH) || rest.startsWith(LAST)) return 2;
        if (rest.startsWith(BRANCH_PLAIN) || rest.startsWith(LAST_PLAIN)) return 2;
        return 0;
    }

    private static String sliceFromVisible(String raw, int visibleStart) {
        int vis = 0;
        int i = 0;
        while (i < raw.length() && vis < visibleStart) {
            if (raw.charAt(i) == 0x1B) {
                i = skipEscape(raw, i);
                continue;
            }
            vis++;
            i += Character.charCount(raw.codePointAt(i));
        }
        return raw.substring(i);
    }

    private static int skipEscape(String s, int i) {
        if (i + 1 >= s.length()) return s.length();
        char n = s.charAt(i + 1);
        if (n == '[') {
            int j = i + 2;
            while (j < s.length()) {
                char c = s.charAt(j++);
                if (c >= '@' && c <= '~') break;
            }
            return j;
        }
        return i + 1;
    }

    /** One tree node: optional bullet/pill/label, hanging body, child nodes. */
    public static final class Node {

        private Icon bullet;
        private Pill pill;
        private RichText label;
        private final List<RichText> body = new ArrayList<>();
        private final List<Node> children = new ArrayList<>();
        private Gap gap;
        private BodyFit bodyFit;
        private boolean flush;

        private Node(Icon bullet, Pill pill, RichText label) {
            this.bullet = bullet;
            this.pill = pill;
            this.label = label == null ? RichText.empty() : label;
        }

        public Node bullet(Icon icon) {
            this.bullet = icon;
            return this;
        }

        public Node pill(Pill pill) {
            this.pill = pill;
            return this;
        }

        public Node label(RichText label) {
            this.label = label == null ? RichText.empty() : label;
            return this;
        }

        public Node label(String label) {
            return label(RichText.plain(label == null ? "" : label));
        }

        public Node body(RichText... lines) {
            if (lines != null) {
                for (RichText line : lines) {
                    if (line != null && !line.isEmpty()) body.add(line);
                }
            }
            return this;
        }

        public Node body(List<RichText> lines) {
            if (lines != null) body(lines.toArray(RichText[]::new));
            return this;
        }

        public Node bodyFit(BodyFit fit) {
            this.bodyFit = fit;
            return this;
        }

        public Node child(Node node) {
            if (node != null) children.add(node);
            return this;
        }

        public Node children(Node... nodes) {
            if (nodes != null) {
                for (Node node : nodes) child(node);
            }
            return this;
        }

        /** Override the tree's {@link Gap} for this node's children. */
        public Node gap(Gap gap) {
            this.gap = gap;
            return this;
        }

        /**
         * When true, the connector abuts the label (scope pills). Default is flush for a {@link Pill}
         * and spaced for text.
         */
        public Node flush(boolean on) {
            this.flush = on;
            return this;
        }

        public Icon bullet() {
            return bullet;
        }

        public Pill pill() {
            return pill;
        }

        public RichText label() {
            return label;
        }

        public List<RichText> body() {
            return List.copyOf(body);
        }

        public List<Node> children() {
            return List.copyOf(children);
        }
    }
}

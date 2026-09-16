// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The HTML 4 character entities Maven's POM reader accepts without a declaration, as the numeric
 * character references XML itself defines.
 *
 * <p>Central holds POMs that lean on Maven's leniency here (the plexus parent names a developer
 * {@code Laugst&oslash;l}), and a strict parser refuses them as an undeclared entity. {@link
 * #numeric} rewrites every {@code &name;} in this table to {@code &#xHHHH;} before the document
 * reaches {@link DomXml}'s parser, so the parser's posture stays as it is: no DOCTYPE, no
 * declared entities, no external access. XML's own five ({@code amp lt gt quot apos}) are the
 * parser's already; a name outside the table is left for the parser to refuse; text inside a CDATA
 * section or a comment is not markup and is left alone.
 */
public final class XmlEntities {

    /** {@code name hex} pairs, six to a line; the set and values are Maven's. */
    private static final String TABLE = """
            nbsp 00a0  iexcl 00a1  cent 00a2  pound 00a3  curren 00a4  yen 00a5
            brvbar 00a6  sect 00a7  uml 00a8  copy 00a9  ordf 00aa  laquo 00ab
            not 00ac  shy 00ad  reg 00ae  macr 00af  deg 00b0  plusmn 00b1
            sup2 00b2  sup3 00b3  acute 00b4  micro 00b5  para 00b6  middot 00b7
            cedil 00b8  sup1 00b9  ordm 00ba  raquo 00bb  frac14 00bc  frac12 00bd
            frac34 00be  iquest 00bf  Agrave 00c0  Aacute 00c1  Acirc 00c2  Atilde 00c3
            Auml 00c4  Aring 00c5  AElig 00c6  Ccedil 00c7  Egrave 00c8  Eacute 00c9
            Ecirc 00ca  Euml 00cb  Igrave 00cc  Iacute 00cd  Icirc 00ce  Iuml 00cf
            ETH 00d0  Ntilde 00d1  Ograve 00d2  Oacute 00d3  Ocirc 00d4  Otilde 00d5
            Ouml 00d6  times 00d7  Oslash 00d8  Ugrave 00d9  Uacute 00da  Ucirc 00db
            Uuml 00dc  Yacute 00dd  THORN 00de  szlig 00df  agrave 00e0  aacute 00e1
            acirc 00e2  atilde 00e3  auml 00e4  aring 00e5  aelig 00e6  ccedil 00e7
            egrave 00e8  eacute 00e9  ecirc 00ea  euml 00eb  igrave 00ec  iacute 00ed
            icirc 00ee  iuml 00ef  eth 00f0  ntilde 00f1  ograve 00f2  oacute 00f3
            ocirc 00f4  otilde 00f5  ouml 00f6  divide 00f7  oslash 00f8  ugrave 00f9
            uacute 00fa  ucirc 00fb  uuml 00fc  yacute 00fd  thorn 00fe  yuml 00ff
            OElig 0152  oelig 0153  Scaron 0160  scaron 0161  Yuml 0178  circ 02c6
            tilde 02dc  ensp 2002  emsp 2003  thinsp 2009  zwnj 200c  zwj 200d
            lrm 200e  rlm 200f  ndash 2013  mdash 2014  lsquo 2018  rsquo 2019
            sbquo 201a  ldquo 201c  rdquo 201d  bdquo 201e  dagger 2020  Dagger 2021
            permil 2030  lsaquo 2039  rsaquo 203a  euro 20ac  fnof 0192  Alpha 0391
            Beta 0392  Gamma 0393  Delta 0394  Epsilon 0395  Zeta 0396  Eta 0397
            Theta 0398  Iota 0399  Kappa 039a  Lambda 039b  Mu 039c  Nu 039d
            Xi 039e  Omicron 039f  Pi 03a0  Rho 03a1  Sigma 03a3  Tau 03a4
            Upsilon 03a5  Phi 03a6  Chi 03a7  Psi 03a8  Omega 03a9  alpha 03b1
            beta 03b2  gamma 03b3  delta 03b4  epsilon 03b5  zeta 03b6  eta 03b7
            theta 03b8  iota 03b9  kappa 03ba  lambda 03bb  mu 03bc  nu 03bd
            xi 03be  omicron 03bf  pi 03c0  rho 03c1  sigmaf 03c2  sigma 03c3
            tau 03c4  upsilon 03c5  phi 03c6  chi 03c7  psi 03c8  omega 03c9
            thetasym 03d1  upsih 03d2  piv 03d6  bull 2022  hellip 2026  prime 2032
            Prime 2033  oline 203e  frasl 2044  weierp 2118  image 2111  real 211c
            trade 2122  alefsym 2135  larr 2190  uarr 2191  rarr 2192  darr 2193
            harr 2194  crarr 21b5  lArr 21d0  uArr 21d1  rArr 21d2  dArr 21d3
            hArr 21d4  forall 2200  part 2202  exist 2203  empty 2205  nabla 2207
            isin 2208  notin 2209  ni 220b  prod 220f  sum 2211  minus 2212
            lowast 2217  radic 221a  prop 221d  infin 221e  ang 2220  and 2227
            or 2228  cap 2229  cup 222a  int 222b  there4 2234  sim 223c
            cong 2245  asymp 2248  ne 2260  equiv 2261  le 2264  ge 2265
            sub 2282  sup 2283  nsub 2284  sube 2286  supe 2287  oplus 2295
            otimes 2297  perp 22a5  sdot 22c5  lceil 2308  rceil 2309  lfloor 230a
            rfloor 230b  lang 2329  rang 232a  loz 25ca  spades 2660  clubs 2663
            hearts 2665  diams 2666
            """;

    private static final Map<String, String> BY_NAME = parse(TABLE);

    /** The longest entity name in the table, bounding how far past an {@code &} a name is read. */
    private static final int LONGEST_NAME = 8;

    private XmlEntities() {}

    /** Whether {@code name} is one of the entities this table rewrites. */
    public static boolean knows(String name) {
        return BY_NAME.containsKey(name);
    }

    /**
     * {@code xml} with every tabled {@code &name;} replaced by its numeric reference; the same
     * array when there is nothing to rewrite. The scan is over ASCII in an ASCII-compatible
     * encoding, so the bytes of every other character pass through untouched, whatever the
     * document's declared encoding.
     */
    public static byte[] numeric(byte[] xml) {
        boolean ampersand = false;
        for (byte b : xml) {
            if (b == '&') {
                ampersand = true;
                break;
            }
        }
        if (!ampersand) return xml;
        String latin1 = new String(xml, StandardCharsets.ISO_8859_1);
        String rewritten = numeric(latin1);
        return rewritten.equals(latin1) ? xml : rewritten.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** {@link #numeric(byte[])} over decoded text. */
    public static String numeric(String xml) {
        int amp = xml.indexOf('&');
        if (amp < 0) return xml;
        StringBuilder out = new StringBuilder(xml.length() + 16);
        int copied = 0;
        int i = 0;
        int n = xml.length();
        while (i < n) {
            char c = xml.charAt(i);
            if (c == '<') {
                int skipped = skipUnparsed(xml, i);
                if (skipped > i) {
                    i = skipped;
                    continue;
                }
            } else if (c == '&') {
                int semicolon = xml.indexOf(';', i + 1);
                if (semicolon > i + 1 && semicolon - i - 1 <= LONGEST_NAME) {
                    String hex = BY_NAME.get(xml.substring(i + 1, semicolon));
                    if (hex != null) {
                        out.append(xml, copied, i).append("&#x").append(hex).append(';');
                        i = semicolon + 1;
                        copied = i;
                        continue;
                    }
                }
            }
            i++;
        }
        if (copied == 0) return xml;
        return out.append(xml, copied, n).toString();
    }

    /** The index just past a CDATA section or comment starting at {@code at}, or {@code at} itself. */
    private static int skipUnparsed(String xml, int at) {
        if (xml.startsWith("<![CDATA[", at)) {
            int end = xml.indexOf("]]>", at + 9);
            return end < 0 ? xml.length() : end + 3;
        }
        if (xml.startsWith("<!--", at)) {
            int end = xml.indexOf("-->", at + 4);
            return end < 0 ? xml.length() : end + 3;
        }
        return at;
    }

    private static Map<String, String> parse(String table) {
        Map<String, String> byName = new HashMap<>(512);
        String[] words = table.trim().split("\\s+");
        for (int i = 0; i + 1 < words.length; i += 2) {
            byName.put(words[i], words[i + 1]);
        }
        return Map.copyOf(byName);
    }
}

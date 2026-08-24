// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * The one place jk turns XML into a DOM, plus the child-navigation helpers for reading one.
 *
 * <p>Every XML document jk parses is third-party content — a POM or {@code maven-metadata.xml} off
 * a Maven repository, an AAR's {@code res/values/*.xml}, a git dependency's {@code pom.xml}, an
 * {@code AndroidManifest.xml}, Google's SDK feed. All of it is attacker-influenceable, so all of it
 * is parsed with the same XXE posture: {@linkplain XMLConstants#FEATURE_SECURE_PROCESSING secure
 * processing}, a DOCTYPE rejected outright, both external-entity features off, no external DTD, and
 * no entity-reference expansion.
 *
 * <p>That posture is enforceable only because it is owned. Round 3 found seven files each building
 * their own {@code DocumentBuilderFactory} at four different hardening levels, the weakest with one
 * flag of the six; a text scan cannot tell which factory instance a given {@code setFeature} call
 * configures, so "one place constructs the factory" is the only rule a guard can check. The build's
 * {@code checkSingleXmlParserOwner} (G3) bans {@code DocumentBuilderFactory} everywhere but this
 * file, and reads the six flags back out of {@link #hardened} — so weakening the posture here fails
 * the build rather than silently weakening seven callers.
 *
 * <p>Nothing here hands out a {@link DocumentBuilderFactory} or a {@link DocumentBuilder}: the only
 * way to get a {@link Document} is to get a hardened one.
 */
public final class DomXml {

    /**
     * jk's parser. One per process, because {@link DocumentBuilderFactory#newInstance} runs a JAXP
     * service lookup every call and an AAR merge parses a few hundred documents. Configured once
     * and never mutated; {@link #builder} still takes its monitor, since JAXP promises nothing
     * about concurrent {@code newDocumentBuilder} and the lock costs microseconds against a parse.
     */
    private static final DocumentBuilderFactory FACTORY = hardened();

    private DomXml() {}

    /** Parse untrusted XML bytes, honouring the document's own encoding declaration. */
    public static Document parse(byte[] xml) throws IOException {
        return parse(new InputSource(new ByteArrayInputStream(xml)));
    }

    /**
     * Parse untrusted XML text. The characters are already decoded, so an {@code encoding=} in the
     * declaration is ignored rather than applied a second time.
     */
    public static Document parse(String xml) throws IOException {
        return parse(new InputSource(new StringReader(xml)));
    }

    /** Parse an untrusted XML stream. The caller owns the stream and closes it. */
    public static Document parse(InputStream xml) throws IOException {
        return parse(new InputSource(xml));
    }

    /** Parse an untrusted XML file. Its path becomes the system id, so parse errors name it. */
    public static Document parse(Path xml) throws IOException {
        try {
            return builder().parse(xml.toFile());
        } catch (SAXException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** An empty document to build a DOM in — the write side of the same owner. */
    public static Document newDocument() {
        return builder().newDocument();
    }

    /** The trimmed text of the first direct child element named {@code tagName}, or {@code null}. */
    public static String childText(Element parent, String tagName) {
        Element child = childElement(parent, tagName);
        return child == null ? null : child.getTextContent().trim();
    }

    /** The first direct child element named {@code tagName}, or {@code null} ({@code null}-safe parent). */
    public static Element childElement(Element parent, String tagName) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals(tagName)) {
                return (Element) node;
            }
        }
        return null;
    }

    /** All direct child elements named {@code tagName} ({@code null}-safe parent → empty). */
    public static List<Element> childElements(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals(tagName)) {
                result.add((Element) node);
            }
        }
        return result;
    }

    /** All direct child elements, regardless of name ({@code null}-safe parent → empty). */
    public static List<Element> childElements(Element parent) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static Document parse(InputSource source) throws IOException {
        try {
            return builder().parse(source);
        } catch (SAXException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static DocumentBuilder builder() {
        synchronized (FACTORY) {
            try {
                return FACTORY.newDocumentBuilder();
            } catch (ParserConfigurationException e) {
                throw new IllegalStateException("this JVM cannot create an XML parser", e);
            }
        }
    }

    /**
     * jk's six-flag XXE posture. Fails closed: a parser that will not take a flag is a parser jk
     * will not use, because the alternative is parsing hostile XML with a hole nobody notices.
     */
    private static DocumentBuilderFactory hardened() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Namespace-unaware on purpose, and not one of the six: every jk reader matches literal tag
        // names, and both Android readers want the prefix kept (`android:name` as an attribute
        // name, `xmlns:*` as ordinary attributes to copy through).
        factory.setNamespaceAware(false);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // A DOCTYPE is rejected outright rather than merely declining to fetch what it declares:
            // no document jk reads has a legitimate one, and refusing the declaration is one check
            // instead of trusting four entity-resolution switches to all hold.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("this JVM's XML parser cannot be hardened against XXE", e);
        }
        factory.setExpandEntityReferences(false);
        return factory;
    }
}

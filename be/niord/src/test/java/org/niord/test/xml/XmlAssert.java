/*
 * Copyright 2016 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.niord.test.xml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A tiny, dependency-free helper for asserting the structure and content of XML / GML output in
 * tests. Built on the JDK's {@code javax.xml} DOM parser only - no new test dependency, no XMLUnit -
 * so it is framework-neutral and survives a Quarkus -&gt; Spring migration. It lives in the niord
 * test tree and reaches the niord-dk ITs through the niord-base test-jar, exactly like
 * {@code org.niord.test.http.HttpStub} and {@code org.niord.test.TestClock}.
 *
 * <p>It exists so that XML-producing code (notably the S-124 GML export) can be asserted on real
 * structure - root element, presence/count of elements, element text, attributes - rather than the
 * previous "render it and print it" smoke style that asserted nothing.</p>
 *
 * <p>All lookups are by <em>local name</em> (namespace-agnostic, via {@code getElementsByTagNameNS("*",
 * name)}), which keeps assertions readable for heavily namespaced GML (S124:, gml:, S100:, plus
 * un-prefixed elements) without having to wire up a {@code NamespaceContext}. The parser is
 * namespace-aware and hardened against XXE (secure processing on, DOCTYPE disallowed).</p>
 *
 * <p>Assertion methods throw {@link AssertionError} (so they integrate with any test framework) and
 * return {@code this} for fluent chaining.</p>
 */
public final class XmlAssert {

    private final Document doc;

    private XmlAssert(Document doc) {
        this.doc = doc;
    }

    /** Parses the given XML string (namespace-aware, XXE-hardened) or fails with {@link AssertionError}. */
    public static XmlAssert parse(String xml) {
        if (xml == null) {
            throw new AssertionError("XML is null");
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            Document d = b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return new XmlAssert(d);
        } catch (Exception e) {
            throw new AssertionError("XML is not well-formed: " + e.getMessage()
                    + "\n--- XML (truncated) ---\n" + truncate(xml), e);
        }
    }

    private static String truncate(String s) {
        return s.length() > 2000 ? s.substring(0, 2000) + "\n...[truncated]" : s;
    }

    /* ---------------------------------------------------------------- */
    /* Queries                                                          */
    /* ---------------------------------------------------------------- */

    public Document document() {
        return doc;
    }

    /** The local name of the document/root element. */
    public String rootLocalName() {
        return doc.getDocumentElement().getLocalName();
    }

    private List<Element> elements(String localName) {
        NodeList nl = doc.getElementsByTagNameNS("*", localName);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) {
            out.add((Element) nl.item(i));
        }
        return out;
    }

    /** Number of elements anywhere in the document with the given local name. */
    public int count(String localName) {
        return elements(localName).size();
    }

    public boolean has(String localName) {
        return count(localName) > 0;
    }

    /** Trimmed text content of the first element with the given local name. */
    public String firstText(String localName) {
        List<Element> e = elements(localName);
        if (e.isEmpty()) {
            throw new AssertionError("No <" + localName + "> element found");
        }
        return e.get(0).getTextContent().trim();
    }

    /** Trimmed text content of every element with the given local name (document order). */
    public List<String> texts(String localName) {
        List<String> out = new ArrayList<>();
        for (Element e : elements(localName)) {
            out.add(e.getTextContent().trim());
        }
        return out;
    }

    /** Value of an attribute (matched by local name, e.g. "id" for "gml:id") on the root element, or null. */
    public String rootAttr(String attrLocalName) {
        NamedNodeMap a = doc.getDocumentElement().getAttributes();
        for (int i = 0; i < a.getLength(); i++) {
            Node n = a.item(i);
            if (attrLocalName.equals(n.getLocalName()) || attrLocalName.equals(n.getNodeName())) {
                return n.getNodeValue();
            }
        }
        return null;
    }

    /* ---------------------------------------------------------------- */
    /* Fluent assertions                                                */
    /* ---------------------------------------------------------------- */

    public XmlAssert assertRoot(String localName) {
        if (!localName.equals(rootLocalName())) {
            throw new AssertionError("Expected root element <" + localName + "> but was <" + rootLocalName() + ">");
        }
        return this;
    }

    public XmlAssert assertHasElement(String localName) {
        if (!has(localName)) {
            throw new AssertionError("Expected at least one <" + localName + "> element, found none");
        }
        return this;
    }

    public XmlAssert assertNoElement(String localName) {
        int c = count(localName);
        if (c > 0) {
            throw new AssertionError("Expected no <" + localName + "> element but found " + c);
        }
        return this;
    }

    public XmlAssert assertElementCount(String localName, int expected) {
        int actual = count(localName);
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " <" + localName + "> element(s) but found " + actual);
        }
        return this;
    }

    public XmlAssert assertElementText(String localName, String expected) {
        String actual = firstText(localName);
        if (!expected.equals(actual)) {
            throw new AssertionError("Element <" + localName + "> text expected [" + expected
                    + "] but was [" + actual + "]");
        }
        return this;
    }

    /** Asserts that SOME element with the given local name has text containing the substring. */
    public XmlAssert assertElementTextContains(String localName, String substring) {
        List<String> all = texts(localName);
        for (String t : all) {
            if (t.contains(substring)) {
                return this;
            }
        }
        throw new AssertionError("No <" + localName + "> element text contains [" + substring + "]; texts=" + all);
    }

    public XmlAssert assertRootAttr(String attrLocalName, String expected) {
        String actual = rootAttr(attrLocalName);
        if (!expected.equals(actual)) {
            throw new AssertionError("Root attribute [" + attrLocalName + "] expected [" + expected
                    + "] but was [" + actual + "]");
        }
        return this;
    }
}

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
package org.niord.core.script.pdf;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xhtmlrenderer.pdf.ITextOutputDevice;
import org.xhtmlrenderer.pdf.ITextRenderer;
import org.xhtmlrenderer.pdf.PDFEncryption;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Used for creating a PDF file from HTML.
 * <p>
 * Important: If the HTML document contains inline SVG elements, it MUST defined the following CSS style:
 * <pre>
 * svg {
 *   display: inline-block;
 * }
 * </pre>
 */
public class HtmlToPdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(HtmlToPdfRenderer.class);

    /** The resolution {@code new ITextRenderer()} renders at */
    private static final float DOTS_PER_POINT = 20f * 4f / 3f;
    private static final int DOTS_PER_PIXEL = 20;

    /**
     * The page margin boxes the map credit may take, in order of preference: the two beside the
     * bottom-centre box, which holds the footer of every shipped template (pdf.css). The page number
     * is in the header, so both are free there.
     */
    private static final List<String> MAP_CREDIT_BOXES = List.of("bottom-right", "bottom-left");

    private Document doc = null;
    private String pdfEncryptionPassword = null;
    private String baseUri = "";
    private OutputStream pdf = null;
    private String mapImageAddress = null;
    private String mapCredit = null;

    /** No-access constructor **/
    private HtmlToPdfRenderer() {
    }

    /** Factory method for returning a HtmlToPdfRendererBuilder **/
    public static HtmlToPdfRendererBuilder newBuilder() {
        return new HtmlToPdfRendererBuilder();
    }

    /**
     * Renders the PDF from the HTML file
     */
    public void render() throws Exception {

        // Clean up HTML. Sometimes they paste html into fields that contain illegal tags, e.g. "<o:p></o:p>"
        cleanUpHtml();

        // Credit the map images in the page footer, if the document shows any
        addMapCredit();

        // Update all SVG elements
        updateSvgElements();

        // Convert the JSoup Document to an XML Document
        W3CDom w3cDom = new W3CDom();
        org.w3c.dom.Document xhtmlContent = w3cDom.fromJsoup(doc);

        // NB: We cannot use JTidy to clean the document, as it will remove all SVG tags :-(
        // TextUtils.cleanHtml(html);

        // Generate PDF from the HTML. The user agent loads images, style sheets and fonts from the
        // application's own origin only: the HTML carries markup the backend does not control.
        ITextOutputDevice outputDevice = new ITextOutputDevice(DOTS_PER_POINT);
        ITextRenderer renderer = new ITextRenderer(outputDevice,
                new SameOriginUserAgent(outputDevice, DOTS_PER_PIXEL, baseUri));

        // Add support for SVG in PDF generation
        ChainingReplacedElementFactory chainingReplacedElementFactory = new ChainingReplacedElementFactory();
        chainingReplacedElementFactory.addReplacedElementFactory(renderer.getSharedContext().getReplacedElementFactory());
        chainingReplacedElementFactory.addReplacedElementFactory(new SVGReplacedElementFactory());
        renderer.getSharedContext().setReplacedElementFactory(chainingReplacedElementFactory);

        // Check if we need to encrypt the PDF
        if (StringUtils.isNotBlank(pdfEncryptionPassword)) {
            renderer.setPDFEncryption(new PDFEncryption(null, pdfEncryptionPassword.getBytes()));
        }

        renderer.setDocument(xhtmlContent, baseUri);
        renderer.layout();
        renderer.createPDF(pdf);
    }

    /**
     * Clean up HTML.
     * Sometimes they paste html into fields that contain illegal tags, e.g. "<o:p></o:p>"
     */
    private void cleanUpHtml() {
        // Remove tags with a namespace component to get rid of e.g. "<o:p></o:p>" tags
        doc.select("*").stream()
                .filter(e -> e.tagName().contains(":"))
                .forEach(Node::remove);
    }

    /**
     * Adds the map credit to the page footer when the document shows a map image, i.e. an {@code <img>}
     * whose address contains the map image address. It is added as a page margin box of its own, so it
     * needs nothing from the template and appears on every page.
     * <p>
     * The box sits beside the footer, which keeps its place: on a narrow page (A5 or B5 portrait) a footer
     * line as wide as the page wraps to make room. The credit is set at the top of the bottom margin, level
     * with the top of the footer, where every printer prints; the bottom of the margin is closer to the
     * paper edge than many printers reach.
     * <p>
     * A template whose own style sheet already fills the bottom-right box gets the credit on the left. One
     * that fills both still gets it on the right, over its own content there: the credit is a condition of
     * the map tiles' licence.
     */
    private void addMapCredit() {
        if (StringUtils.isAnyBlank(mapImageAddress, mapCredit)
                || doc.select("img[src]").stream().noneMatch(img -> img.attr("src").contains(mapImageAddress))) {
            return;
        }

        String inlineCss = doc.select("style").stream().map(Element::data).collect(Collectors.joining("\n"));
        String box = MAP_CREDIT_BOXES.stream()
                .filter(name -> !Pattern.compile("@" + name + "(?![\\w-])").matcher(inlineCss).find())
                .findFirst()
                .orElse(null);
        if (box == null) {
            box = MAP_CREDIT_BOXES.getFirst();
            log.warn("The template fills both page margin boxes beside its footer; the map credit replaces @{}", box);
        }

        doc.head().appendElement("style").attr("type", "text/css").appendChild(new DataNode(
                "@page { @" + box + " { content: " + cssString(mapCredit) + "; font-size: 6pt; color: #666;"
                        + " vertical-align: top; padding-top: 5px; } }"));
    }

    /**
     * Returns the text as a quoted CSS string. Quotes, backslashes, angle brackets and control characters
     * are written as CSS escapes, so the text can neither end the string nor the style element.
     */
    private static String cssString(String text) {
        StringBuilder css = new StringBuilder("\"");
        text.codePoints().forEach(c -> {
            if (c == '"' || c == '\\' || c == '<' || c == '>' || c < 0x20 || c == 0x7f) {
                // The space after the hex digits ends the escape and is not part of the text
                css.append('\\').append(Integer.toHexString(c)).append(' ');
            } else {
                css.appendCodePoint(c);
            }
        });
        return css.append('"').toString();
    }

    /**
     * Updates all the svg elements of the HTML document,
     * by copying svg width and height into a style attributes.
     * Otherwise, the PDF rendering just doesn't know how to size the SVG element.
     */
    private void updateSvgElements() {
        doc.select("svg").forEach(svg -> {
            String style = svg.attr("style");
            if (!style.matches("[^-]width")) {
                String width = svg.attr("width");
                if (StringUtils.isNotBlank(width)) {
                    if (StringUtils.isNumeric(width)) {
                        width += "px";
                    }
                    style = "width: " + width + ";" + style;
                }
            }
            if (!style.matches("[^-]height")) {
                String height = svg.attr("height");
                if (StringUtils.isNotBlank(height)) {
                    if (StringUtils.isNumeric(height)) {
                        height += "px";
                    }
                    style = "height: " + height + ";" + style;
                }
            }
            if (StringUtils.isNotBlank(style)) {
                svg.attr("style", style);
            }
        });
    }

    /**
     * Used by the client for building a new HtmlToPdfRenderer.
     *
     * Initialize the builder by calling HtmlToPdfRenderer.newBuilder()
     */
    public static class HtmlToPdfRendererBuilder {

        HtmlToPdfRenderer renderer = new HtmlToPdfRenderer();

        /** No-access constructor **/
        private HtmlToPdfRendererBuilder() {
        }

        /** Sets the raw HTML content **/
        public HtmlToPdfRendererBuilder html(String html) {
            renderer.doc = Jsoup.parse(html);
            return this;
        }

        /** Sets the HTML from an input stream **/
        public HtmlToPdfRendererBuilder html(InputStream in) throws Exception {
            renderer.doc = Jsoup.parse(in, "UTF-8", "");
            return this;
        }

        /** Sets the PDF encryption password, causing the PDF to be encrypted **/
        public HtmlToPdfRendererBuilder encrypt(String password) {
            renderer.pdfEncryptionPassword = password;
            return this;
        }

        /**
         * Sets the base URI to use for the HTML. Images, style sheets and fonts are loaded from its
         * origin only (see {@link SameOriginUserAgent}); without a base URI nothing is loaded.
         **/
        public HtmlToPdfRendererBuilder baseUri(String baseUri) {
            renderer.baseUri = baseUri;
            return this;
        }

        /**
         * Credits the map images the document shows: when the document holds an {@code <img>} whose
         * address contains {@code imageAddress}, every page carries {@code credit} in its footer. A document
         * without such an image is rendered as it is, and so is every document when either value is blank.
         *
         * @param imageAddress the address the map images are served from, or the part of it their
         *                     {@code src} always contains
         * @param credit the credit line, plain text
         **/
        public HtmlToPdfRendererBuilder mapCredit(String imageAddress, String credit) {
            renderer.mapImageAddress = imageAddress;
            renderer.mapCredit = credit;
            return this;
        }

        /** Sets the output for the generated PDF **/
        public HtmlToPdfRendererBuilder pdf(OutputStream pdf) {
            renderer.pdf = pdf;
            return this;
        }

        /** Sets the output for the generated PDF **/
        public HtmlToPdfRendererBuilder pdf(File pdf) throws Exception {
            renderer.pdf = new FileOutputStream(pdf);
            return this;
        }

        /**
         * Returns an HtmlToPdfRenderer instantiated via this builder
         * @return an instantiated HtmlToPdfRenderer
         */
        public HtmlToPdfRenderer build() {

            // Check required parameters
            if (renderer.doc == null) {
                throw new IllegalArgumentException("No HTML defined");
            }
            if (renderer.pdf == null) {
                throw new IllegalArgumentException("No PDF output defined");
            }

            return renderer;
        }

    }

}

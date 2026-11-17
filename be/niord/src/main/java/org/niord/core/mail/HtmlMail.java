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
package org.niord.core.mail;

import com.steadystate.css.parser.CSSOMParser;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;
import org.niord.core.util.HtmlToPlainText;
import org.niord.core.util.SameOriginFetch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.css.sac.CSSException;
import org.w3c.css.sac.CSSParseException;
import org.w3c.css.sac.ErrorHandler;
import org.w3c.css.sac.InputSource;
import org.w3c.dom.css.CSSRule;
import org.w3c.dom.css.CSSRuleList;
import org.w3c.dom.css.CSSStyleDeclaration;
import org.w3c.dom.css.CSSStyleRule;
import org.w3c.dom.css.CSSStyleSheet;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Creates a mail from an HTML source.
 * <p>
 * The HTML linked sources, such as stylesheets and images, will be inlined.
 * Links will be absolutized (converted from relative links to absolute links).
 * <p>
 * There are two ways to inline CSS:
 * <ul>
 *     <li>Inline CSS: The linked stylesheets will be converted to linked inline mail parts</li>
 *     <li>Inline styles: The styles will be parsed and added to each element as a style attribute.
 *     This is quite experimental, but may improve the experience in web-based mail-clients such as gmail.</li>
 * </ul>
 * <p>
 * Only the resources on the origin of the base URI - the application's own, see {@link SameOriginFetch} -
 * are fetched and inlined. The HTML carries markup the backend does not control (message details,
 * comments), so anything else is never requested by the backend: a stylesheet elsewhere is dropped, an
 * http(s) or {@code data:} image elsewhere keeps its absolute address for the recipient's mail client to
 * load or not, and an image of any other kind ({@code file:}, {@code jar:} ...) is dropped. An own stylesheet or
 * image that cannot be fetched is left out with a WARN; the mail goes out without it.
 */
@SuppressWarnings("unused")
public class HtmlMail extends Mail {

    private final static Logger log = LoggerFactory.getLogger(HtmlMail.class);

    public enum StyleHandling { INLINE_CSS, INLINE_STYLES }

    private int cidIndex = 0;
    private Map<String, InlineMailPart> inlinePartsLookup = new HashMap<>();

    /** The base URI of the document: the only origin its resources are fetched from */
    private final String baseUri;

    /**
     * Constructor
     * @param doc the HTML document
     * @param baseUri the base URI of the document, the only origin its resources are fetched from
     * @param styleHandling whether to inline CSS styles or not
     * @param includePlainText whether to include a plain text version or not
     */
    private HtmlMail(Document doc, String baseUri, StyleHandling styleHandling, boolean includePlainText) throws IOException {
        this.baseUri = baseUri;
        processHtml(doc, styleHandling, includePlainText);
    }

    /**
     * Returns a new HTML mail from a URL
     *
     * @param url the URL of the HTML document
     * @param styleHandling whether to inline CSS styles or not
     * @param includePlainText whether to include a plain text version or not
     * @return the HTML mail
     */
    public static HtmlMail fromUrl(String url, StyleHandling styleHandling, boolean includePlainText) throws IOException {
        Document doc = Jsoup.connect(url).get();
        return new HtmlMail(doc, url, styleHandling, includePlainText);
    }

    /**
     * Returns a new HTML mail from the HTML content
     *
     * @param html the actual HTML
     * @param baseUri the base URI of the document: the application's own, the only origin the images and
     *                stylesheets of the document are fetched from
     * @param styleHandling whether to inline CSS styles or not
     * @param includePlainText whether to include a plain text version or not
     * @return the HTML mail
     */
    public static HtmlMail fromHtml(String html, String baseUri, StyleHandling styleHandling, boolean includePlainText) throws IOException {
        Document doc = Jsoup.parse(html, baseUri);
        return new HtmlMail(doc, baseUri, styleHandling, includePlainText);
    }

    /**
     * Transforms the document to be "mailable" and build up a list of
     * inline mail parts for images and stylesheets.
     *
     * @param doc the DOM of the HTML document
     */
    protected void processHtml(Document doc, StyleHandling styleHandling, boolean includePlainText) throws IOException {

        // Clean up a bit
        removeElements(doc, "script", "meta", "base", "iframe");

        if (styleHandling == StyleHandling.INLINE_STYLES) {
            // Inline stylesheets
            inlineStyles(doc);

        } else {
            // Inline stylesheets
            inlineStyleSheets(doc);
        }

        // Inline images
        inlineImages(doc);

        // Convert links to be absolute
        absolutizeLinks(doc, "a", "href");
        absolutizeLinks(doc, "form", "action");

        // Get the result
        setHtmlText(doc.toString());
        setInlineParts(new ArrayList<>(inlinePartsLookup.values()));
        if (includePlainText) {
            setPlainText(new HtmlToPlainText().getPlainText(doc.body()));
        }
    }

    /**
     * Removes the given html elements from the document
     *
     * @param doc the HTML document
     * @param elements the elements to remove
     */
    protected void removeElements(Document doc, String... elements) {
        doc.select(Arrays.stream(elements).collect(Collectors.joining(", ")))
                .forEach(Node::remove);
    }

    /** Creates a new CSS parser that does not log to std out. **/
    private CSSOMParser newCSSOMParser() {
        CSSOMParser parser = new CSSOMParser();
        parser.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(CSSParseException ex) throws CSSException {
                log(ex);
            }

            @Override
            public void error(CSSParseException ex) throws CSSException {
                log(ex);
            }

            @Override
            public void fatalError(CSSParseException ex) throws CSSException {
                log(ex);
            }

            private void log(final CSSParseException ex) throws CSSException {
                log.trace(ex.getURI() + " [" + ex.getLineNumber() + ":" + ex.getColumnNumber() + "] " + ex.getMessage());
            }
        });
        return parser;
    }

    /**
     * Inline the styles of the document.
     * The implementation is somewhat experimental and is inspired by:
     * http://stackoverflow.com/questions/4521557/automatically-convert-style-sheets-to-inline-style
     *
     * @param doc the HTML document
     */
    protected void inlineStyles(Document doc) throws IOException {
        Map<Element, Map<String, String>> allElementsStyles = new HashMap<>();

        // Handle linked style sheets
        for (Element e : doc.select("link")) {

            if ((e.attr("type").equalsIgnoreCase("text/css") || e.attr("rel").equalsIgnoreCase("stylesheet")) &&
                    !e.attr("media").equalsIgnoreCase("print")) {
                String url = e.absUrl("href");
                if (isOwnResource(url)) {
                    // Fetched here rather than by the CSS parser, which would open any URL it is given
                    String css;
                    try {
                        css = fetchText(url);
                    } catch (IOException ex) {
                        // As for an image (CachedUrlDataSource): a stylesheet that cannot be fetched costs the
                        // mail its styling, not the mail
                        log.warn("Mail sent without the stylesheet " + url + ", which could not be fetched: " + ex);
                        css = null;
                    }
                    if (css != null) {
                        InputSource source = new InputSource(new StringReader(css));
                        source.setURI(url);
                        CSSStyleSheet stylesheet = newCSSOMParser().parseStyleSheet(source, null, null);
                        addCssRules(doc, allElementsStyles, stylesheet.getCssRules());
                    }
                } else if (url.length() > 0) {
                    log.debug("Not inlining a stylesheet from another origin than " + baseUri);
                }
            }

            // Always remove the link
            e.remove();
        }

        // Handle <style> elements
        for (Element e : doc.select("style")) {
            if (!e.attr("media").equalsIgnoreCase("print")) {
                String style = e.html();
                InputSource source = new InputSource(new StringReader(style));
                CSSStyleSheet stylesheet = newCSSOMParser().parseStyleSheet(source, null, null);
                addCssRules(doc, allElementsStyles, stylesheet.getCssRules());
            }

            // Remove the style
            e.remove();
        }

        // Update the elements with the inlined styles
        for (Map.Entry<Element, Map<String, String>> elementEntry : allElementsStyles.entrySet()) {
            Element element = elementEntry.getKey();
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, String> styleEntry : elementEntry.getValue().entrySet()) {
                builder.append(styleEntry.getKey()).append(":").append(styleEntry.getValue()).append(";");
            }
            builder.append(element.attr("style"));
            element.attr("style", builder.toString());
            // Remove class attributes
            element.removeAttr("class");
        }
    }

    /**
     * Add the css rules from the rule list to the styles map
     * @param doc the HTML document
     * @param allElementsStyles the styles thus far
     * @param ruleList the rules to add
     */
    protected void addCssRules(Document doc, Map<Element, Map<String, String>> allElementsStyles, CSSRuleList ruleList) {
        for (int ruleIndex = 0; ruleIndex < ruleList.getLength(); ruleIndex++) {
            CSSRule item = ruleList.item(ruleIndex);
            if (item instanceof CSSStyleRule) {
                CSSStyleRule styleRule = (CSSStyleRule) item;
                String cssSelector = styleRule.getSelectorText();
                if (cssSelector.contains(":")) {
                    // Skip styles like "a:visited"
                    continue;
                }
                for (Element element : doc.select(cssSelector)) {
                    Map<String, String> elementStyles = allElementsStyles.get(element);
                    if (elementStyles == null) {
                        elementStyles = new LinkedHashMap<>();
                        allElementsStyles.put(element, elementStyles);
                    }
                    CSSStyleDeclaration style = styleRule.getStyle();
                    for (int propertyIndex = 0; propertyIndex < style.getLength(); propertyIndex++) {
                        String propertyName = style.item(propertyIndex);
                        String propertyValue = style.getPropertyValue(propertyName);
                        elementStyles.put(propertyName, propertyValue);
                    }
                }
            }
        }
    }

    /**
     * Inlines the linked stylesheets, and remove all other link elements
     *
     * @param doc the HTML document
     */
    protected void inlineStyleSheets(Document doc) {
        Elements elms = doc.select("link");
        for (Element e : elms) {

            // Handle style sheets
            if (e.attr("type").equalsIgnoreCase("text/css") || e.attr("rel").equals("stylesheet")) {
                String url = e.absUrl("href");
                if (!isOwnResource(url)) {
                    // Blank, or on another origin: never fetched
                    e.remove();
                    continue;
                }
                // Change the href to be the inline content id
                InlineMailPart mp = createInlineMailPart(url, "css");
                e.attr("href", "cid:" + mp.getContentId());

            } else  {
                // Remove all non-stylesheet links
                e.remove();
            }
        }
    }

    /**
     * Inlines the images
     *
     * @param doc the HTML document
     */
    protected void inlineImages(Document doc) {
        Elements elms = doc.select("img[src]");
        for (Element e : elms) {

            // A blank src resolves to the (non-empty) base URI via absUrl(), so guard the raw
            // attribute first - otherwise an <img src=""> becomes a spurious base-URI inline part.
            if (e.attr("src").trim().isEmpty()) {
                e.remove();
                continue;
            }
            String url = e.absUrl("src");
            if (url.length() == 0) {
                // Weird result...
                e.remove();
                continue;
            }
            if (!isOwnResource(url)) {
                // Never fetched by the backend. An http(s) or data: image stays for the recipient's mail
                // client to load or not; anything else (file:, jar: ...) has no place in a mail.
                if (isLeftForTheMailClient(url)) {
                    e.attr("src", url);
                } else {
                    e.remove();
                }
                continue;
            }
            // Change the src to be the inline content id
            InlineMailPart mp = createInlineMailPart(url, "img");
            e.attr("src", "cid:" + mp.getContentId());
        }
    }

    /**
     * Returns whether the URL is one of the application's own resources, the only ones this mail fetches
     * @param url the absolute URL of the resource
     * @return whether the URL is on the origin of the base URI
     */
    protected boolean isOwnResource(String url) {
        return SameOriginFetch.isSameOrigin(url, baseUri);
    }

    /** Returns whether an image URL the mail does not inline stays in the mail, as a remote or data: image */
    private static boolean isLeftForTheMailClient(String url) {
        String scheme = StringUtils.substringBefore(url, ":").toLowerCase(Locale.ROOT);
        return scheme.equals("http") || scheme.equals("https") || scheme.equals("data");
    }

    /**
     * Fetches the text of one of the application's own resources
     * @param url the URL of the resource
     * @return the text of the resource
     */
    private String fetchText(String url) throws IOException {
        HttpURLConnection connection = SameOriginFetch.open(url, baseUri);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Make sure that links are absolute.
     * Used for a.href and form.action links
     *
     * @param doc the HTML document
     */
    protected void absolutizeLinks(Document doc, String tag, String attr) {
        Elements elms = doc.select(tag + "[" + attr + "]");
        for (Element e : elms) {

            String url = e.absUrl(attr);
            if (url.length() == 0) {
                // Disable link
                e.attr(attr, "#");
                continue;
            }
            // Update the link to be the absolute link
            e.attr(attr, url);
        }
    }

    /**
     * Create or look up an existing inline mail part for the given url.
     * Hmm, we make the assumption that e.g. a stylesheet and an image
     * cannot have the same url...
     *
     * @param url the url of the resource
     * @param name the cid name prefix
     * @return the associated inline mail part
     */
    protected InlineMailPart createInlineMailPart(String url, String name) {
        // Check if this is a known url
        InlineMailPart mp = inlinePartsLookup.get(url);
        if (mp == null) {
            String cid = name + (cidIndex++);
            mp = new InlineMailPart(cid, url);
            inlinePartsLookup.put(url, mp);
        }
        return mp;
    }
}

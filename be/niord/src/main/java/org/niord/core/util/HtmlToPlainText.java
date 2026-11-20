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
package org.niord.core.util;

import org.jsoup.Jsoup;
import org.jsoup.internal.StringUtil;
import org.jsoup.helper.Validate;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.io.IOException;

/**
 * HTML to plain-text. This example program demonstrates the use of jsoup to convert HTML input to lightly-formatted
 * plain-text. That is divergent from the general goal of jsoup's .text() methods, which is to get clean data from a
 * scrape.
 * <p>
 * Note that this is a fairly simplistic formatter -- for real world use you'll want to embrace and extend.
 * </p>
 * <p>
 * To invoke from the command line, assuming you've downloaded the jsoup jar to your current directory:</p>
 * <p><code>java -cp jsoup.jar org.jsoup.examples.HtmlToPlainText url [selector]</code></p>
 * where <i>url</i> is the URL to fetch, and <i>selector</i> is an optional CSS selector.
 *
 * @author Jonathan Hedley, jonathan@hedley.net
 */
public class HtmlToPlainText {
    private static final String userAgent = "Mozilla/5.0 (jsoup)";
    private static final int timeout = 5 * 1000;

    public static void main(String... args) throws IOException {
        Validate.isTrue(args.length == 1 || args.length == 2, "usage: java -cp jsoup.jar org.jsoup.examples.HtmlToPlainText url [selector]");
        final String url = args[0];
        final String selector = args.length == 2 ? args[1] : null;

        // fetch the specified URL and parse to a HTML DOM
        Document doc = Jsoup.connect(url).userAgent(userAgent).timeout(timeout).get();

        HtmlToPlainText formatter = new HtmlToPlainText();

        if (selector != null) {
            Elements elements = doc.select(selector); // get each element that matches the CSS selector
            for (Element element : elements) {
                String plainText = formatter.getPlainText(element); // format that element to plain text
                System.out.println(plainText);
            }
        } else { // format the whole doc
            String plainText = formatter.getPlainText(doc);
            System.out.println(plainText);
        }
    }

    /**
     * Format an Element to plain-text
     * @param element the root element to format
     * @return formatted text
     */
    public String getPlainText(Element element) {
        FormattingVisitor formatter = new FormattingVisitor();
        NodeTraversor.traverse(formatter, element); // walk the DOM, and call .head() and .tail() for each node

        return formatter.toString();
    }

    // the formatting rules, implemented in a breadth-first DOM traverse
    private static class FormattingVisitor implements NodeVisitor {
        private static final int maxWidth = 80;
        // blocks that start and end a line of their own, where nothing else already does (TEXT2): those of the
        // editor (div, lists, table, blockquote, a code block's pre, a horizontal rule's hr) and those of older or
        // imported HTML and the source view (dl, the HTML5 sectioning elements, address, figure, center)
        private static final String[] lineBlocks = { "div", "ul", "ol", "dl", "table", "blockquote", "pre", "hr",
                "section", "article", "header", "footer", "aside", "nav", "main", "address", "figure", "figcaption",
                "center" };
        private int width = 0;
        private StringBuilder accum = new StringBuilder(); // holds the accumulated text
        // a line break ('\n') or a space (' ') owed before the next text, or 0: written only if that text does not
        // start with one itself, so a block inside a block adds no blank line, and nothing is written at the end
        private char owed = 0;

        // hit when the node is first seen
        public void head(Node node, int depth) {
            String name = node.nodeName();
            if (node instanceof TextNode)
                append(((TextNode) node).text()); // TextNodes carry all user-readable text in the DOM.
            else if (name.equals("li"))
                append("\n * ");
            else if (name.equals("dt"))
                append("  ");
            else if (StringUtil.in(name, "p", "h1", "h2", "h3", "h4", "h5", "h6", "tr"))
                append("\n");
            else if (StringUtil.in(name, lineBlocks))
                owe('\n');
        }

        // hit when all of the node's children (if any) have been visited
        public void tail(Node node, int depth) {
            String name = node.nodeName();
            if (StringUtil.in(name, "br", "dd", "dt", "p", "h1", "h2", "h3", "h4", "h5", "h6"))
                append("\n");
            else if (name.equals("a"))
                appendAddress(node.absUrl("href"));
            else if (StringUtil.in(name, lineBlocks))
                owe('\n');
            else if (StringUtil.in(name, "td", "th"))
                owe(' ');
        }

        // a link's address after its words; nothing where the address does not resolve, as a link inside Niord
        // ("#/message/...", a repository path) does not when the text is parsed without a base URI
        private void appendAddress(String url) {
            if (!url.isEmpty())
                append(String.format(" <%s>", url));
        }

        // a line break owed is not lowered to a space
        private void owe(char separator) {
            if (owed != '\n')
                owed = separator;
        }

        // writes the separator owed, unless the text, or what is written already, separates by itself
        private void payOwed(String text) {
            char separator = owed;
            owed = 0;
            boolean textSeparates = separator == '\n' ? text.startsWith("\n") : Character.isWhitespace(text.charAt(0));
            char last = accum.length() == 0 ? '\n' : accum.charAt(accum.length() - 1);
            if (textSeparates || last == '\n' || last == separator)
                return;
            accum.append(separator);
            width = separator == '\n' ? 0 : width + 1;
        }

        // appends text to the string builder with a simple word wrap method
        private void append(String text) {
            if (owed != 0 && !text.isEmpty())
                payOwed(text);
            if (text.startsWith("\n"))
                width = 0; // reset counter if starts with a newline. only from formats above, not in natural text
            if (text.equals(" ") &&
                    (accum.length() == 0 || StringUtil.in(accum.substring(accum.length() - 1), " ", "\n")))
                return; // don't accumulate long runs of empty spaces

            if (text.length() + width > maxWidth) { // won't fit, needs to wrap
                String[] words = text.split("\\s+");
                for (int i = 0; i < words.length; i++) {
                    String word = words[i];
                    boolean last = i == words.length - 1;
                    if (!last) // insert a space if not the last word
                        word = word + " ";
                    if (word.length() + width > maxWidth) { // wrap and reset counter
                        accum.append("\n").append(word);
                        width = word.length();
                    } else {
                        accum.append(word);
                        width += word.length();
                    }
                }
                // the split drops the white space at the text's end: keep one space, or the next text (a bold or a
                // linked word) is glued to the last word (TEXT2)
                if (!text.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1)) && accum.length() > 0
                        && !StringUtil.in(accum.substring(accum.length() - 1), " ", "\n")) {
                    accum.append(' ');
                    width++;
                }
            } else { // fits as is, without need to wrap text
                accum.append(text);
                width += text.length();
            }
        }

        @Override
        public String toString() {
            return accum.toString();
        }
    }
}

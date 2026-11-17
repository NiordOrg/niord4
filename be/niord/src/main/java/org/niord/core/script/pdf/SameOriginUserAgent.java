/*
 * Copyright 2026 Danish Maritime Authority.
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

import org.niord.core.util.SameOriginFetch;
import org.xhtmlrenderer.pdf.ITextOutputDevice;
import org.xhtmlrenderer.pdf.ITextUserAgent;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLConnection;

/**
 * The Flying Saucer user agent of every PDF the backend renders: it loads images, style sheets and
 * fonts from the application's own origin only (see {@link SameOriginFetch}).
 * <p>
 * The documents carry markup the backend does not control - message details, report parameters - and
 * the default user agent loads whatever that markup names: other hosts, internal addresses, {@code file:}
 * and {@code jar:} URLs, with redirects followed by the connection itself. Here any other URL is refused
 * with an {@link IOException}, which Flying Saucer logs before it leaves the resource out of the PDF; and a
 * redirect is followed only when its target is on the same origin, or on the same host with http and https
 * switched (see {@link SameOriginFetch}). Embedded {@code data:} images and fonts are decoded before a
 * stream is opened, so they keep working.
 */
public class SameOriginUserAgent extends ITextUserAgent {

    private final String baseUri;

    /**
     * Constructor
     *
     * @param outputDevice the output device of the renderer
     * @param dotsPerPixel the dots per pixel of the renderer
     * @param baseUri the application's base URI, whose origin is the only one resources are loaded from
     */
    public SameOriginUserAgent(ITextOutputDevice outputDevice, int dotsPerPixel, String baseUri) {
        super(outputDevice, dotsPerPixel);
        this.baseUri = baseUri;
    }

    /** {@inheritDoc} */
    @Override
    protected URLConnection openConnection(String uri) throws IOException {
        return onHttpConnection(SameOriginFetch.connect(uri, baseUri));
    }

    /** {@inheritDoc} */
    @Override
    protected URLConnection onHttpConnection(HttpURLConnection connection) throws IOException {
        return SameOriginFetch.followRedirects(connection, baseUri);
    }
}

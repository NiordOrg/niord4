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
package org.niord.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link WebAppFallbackFilter}, which forwards a browser's navigation to one of the web app's
 * own addresses to {@code /index.html}.
 *
 * <p>Both sides of the contract are pinned. A navigation — a GET or HEAD that accepts HTML, for a path
 * without a file extension outside the server's own prefixes, or a GET or HEAD for the root whatever it
 * accepts — is forwarded and never reaches the rest of the chain. Everything else passes on untouched: other
 * methods, requests that do not accept HTML, every path under a server prefix, and every path that names a
 * file.
 *
 * <p>Mock servlet plumbing only, no container.
 */
public class WebAppFallbackFilterTest {

    /** What a browser sends when it navigates */
    private static final String BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

    private final WebAppFallbackFilter filter = new WebAppFallbackFilter();

    private static MockHttpServletRequest request(String method, String uri, String accept) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (accept != null) {
            request.addHeader("Accept", accept);
        }
        return request;
    }

    /** Runs the filter and asserts that it forwarded the request to the app's index page */
    private void forwarded(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals("/index.html", response.getForwardedUrl(),
                request.getMethod() + " " + request.getRequestURI() + " must get the app's index page");
        assertNull(chain.getRequest(), "a forwarded request must not reach the rest of the chain");
    }

    /** Runs the filter and asserts that the request passed on to the chain untouched */
    private void passedOn(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertNotNull(chain.getRequest(),
                request.getMethod() + " " + request.getRequestURI() + " must pass on to the rest of the chain");
        assertNull(response.getForwardedUrl(), "the filter must not forward it");
    }

    // ------------------------------------------------------------------------------------------- //
    // A navigation to the app gets the app's index page
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void forwardsTheRoot() throws Exception {
        forwarded(request("GET", "/", BROWSER_ACCEPT));
    }

    @Test
    public void forwardsTheRootWhateverItAccepts() throws Exception {
        // curl sends */*, a monitoring probe often no Accept at all; both type the bare hostname and must
        // get the app, not a 404 (devops-shared, open issue 10).
        forwarded(request("GET", "/", "*/*"));
        forwarded(request("GET", "/", null));
        forwarded(request("HEAD", "/", null));
        forwarded(request("GET", "/", "application/json"));
    }

    @Test
    public void passesOnOtherMethodsOnTheRoot() throws Exception {
        passedOn(request("POST", "/", BROWSER_ACCEPT));
        passedOn(request("POST", "/", null));
    }

    @Test
    public void forwardsTheAppsOwnAddresses() throws Exception {
        forwarded(request("GET", "/messages", BROWSER_ACCEPT));
        forwarded(request("GET", "/editor/edit/1234", BROWSER_ACCEPT));
        forwarded(request("GET", "/sysadmin/settings", BROWSER_ACCEPT));
    }

    @Test
    public void forwardsAMessageLinkWithItsQueryString() throws Exception {
        MockHttpServletRequest request = request("GET", "/messages", BROWSER_ACCEPT);
        request.setQueryString("messageId=NW-001-26");

        forwarded(request);
    }

    @Test
    public void forwardsAHeadRequestToo() throws Exception {
        forwarded(request("HEAD", "/messages", BROWSER_ACCEPT));
    }

    @Test
    public void forwardsAPathThatOnlyStartsLikeAServerPrefix() throws Exception {
        // The prefixes match whole segments: /restaurant is not under /rest.
        forwarded(request("GET", "/restaurant", BROWSER_ACCEPT));
        forwarded(request("GET", "/images", BROWSER_ACCEPT));
    }

    // ------------------------------------------------------------------------------------------- //
    // Everything else passes on
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void passesOnOtherMethods() throws Exception {
        passedOn(request("POST", "/messages", BROWSER_ACCEPT));
        passedOn(request("PUT", "/messages", BROWSER_ACCEPT));
        passedOn(request("DELETE", "/messages", BROWSER_ACCEPT));
    }

    @Test
    public void passesOnARequestThatDoesNotAcceptHtml() throws Exception {
        passedOn(request("GET", "/messages", "application/json"));
        passedOn(request("GET", "/messages", "*/*"));
        passedOn(request("GET", "/messages", null));
    }

    @Test
    public void passesOnEveryServerPrefix() throws Exception {
        passedOn(request("GET", "/rest", BROWSER_ACCEPT));
        passedOn(request("GET", "/rest/public/v1/messages", BROWSER_ACCEPT));
        passedOn(request("GET", "/api/secom/v2/ping", BROWSER_ACCEPT));
        passedOn(request("GET", "/wms", BROWSER_ACCEPT));
        passedOn(request("GET", "/auth/realms/niord/account", BROWSER_ACCEPT));
        passedOn(request("GET", "/img/filetypes", BROWSER_ACCEPT));
        passedOn(request("GET", "/css/templates", BROWSER_ACCEPT));
        passedOn(request("GET", "/assets/i18n", BROWSER_ACCEPT));
        passedOn(request("GET", "/media/fonts", BROWSER_ACCEPT));
    }

    @Test
    public void passesOnAPathThatNamesAFile() throws Exception {
        passedOn(request("GET", "/index.html", BROWSER_ACCEPT));
        passedOn(request("GET", "/silent-check-sso.html", BROWSER_ACCEPT));
        passedOn(request("GET", "/main-ABCDEFGH.js", BROWSER_ACCEPT));
        passedOn(request("GET", "/messages/report.pdf", BROWSER_ACCEPT));
    }
}

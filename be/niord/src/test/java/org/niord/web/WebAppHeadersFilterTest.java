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
import org.niord.core.settings.SettingsService;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link WebAppHeadersFilter}, which sends the administration web app's Content-Security-Policy
 * on its HTML documents and {@code sandbox} with {@code nosniff} on the files a country supplies.
 *
 * <p>Pins which requests get which header: a navigation and a top-level {@code .html} file get the policy; a
 * legal document or a branding file, in any letter case, gets the sandbox; the API, the bundles and the other
 * assets get neither. The rest of the chain always runs.
 *
 * <p>Mock servlet plumbing only, no container. The servlet path is set as the container sets it for the
 * dispatcher servlet, mapped at {@code /}.
 */
public class WebAppHeadersFilterTest {

    /** What a browser sends when it navigates */
    private static final String BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

    private final WebAppContentSecurityPolicy policy =
            new WebAppContentSecurityPolicy(() -> mock(SettingsService.class), "http://localhost:8080/auth/realms/niord");

    private final WebAppHeadersFilter filter = new WebAppHeadersFilter(policy);

    private static MockHttpServletRequest request(String method, String path, String accept) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        if (accept != null) {
            request.addHeader("Accept", accept);
        }
        return request;
    }

    /** Runs the filter, asserts that the rest of the chain ran, and returns the response */
    private MockHttpServletResponse filter(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertNotNull(chain.getRequest(), request.getRequestURI() + " must pass on to the rest of the chain");
        return response;
    }

    private void getsThePolicy(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = filter(request);
        assertEquals(List.of(policy.current()), response.getHeaders("Content-Security-Policy"),
                request.getMethod() + " " + request.getRequestURI() + " must get the app's policy");
        assertNull(response.getHeader("X-Content-Type-Options"), "Spring Security sends nosniff here");
    }

    private void getsTheSandbox(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = filter(request);
        assertEquals(List.of("sandbox"), response.getHeaders("Content-Security-Policy"),
                request.getRequestURI() + " must be sandboxed");
        assertEquals(List.of("nosniff"), response.getHeaders("X-Content-Type-Options"));
    }

    private void getsNoPolicy(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = filter(request);
        assertNull(response.getHeader("Content-Security-Policy"),
                request.getMethod() + " " + request.getRequestURI() + " must get no policy");
    }

    @Test
    public void aNavigationGetsThePolicy() throws Exception {
        getsThePolicy(request("GET", "/messages", BROWSER_ACCEPT));
        getsThePolicy(request("GET", "/", BROWSER_ACCEPT));
        getsThePolicy(request("HEAD", "/editor/edit/1", BROWSER_ACCEPT));
    }

    @Test
    public void theRootGetsThePolicyWhateverItAccepts() throws Exception {
        // The fallback forwards the root to index.html for any client, so its policy must come along.
        getsThePolicy(request("GET", "/", "*/*"));
        getsThePolicy(request("GET", "/", null));
    }

    @Test
    public void theTopLevelHtmlFilesGetThePolicy() throws Exception {
        getsThePolicy(request("GET", "/index.html", null));
        getsThePolicy(request("GET", "/silent-check-sso.html", "*/*"));
    }

    @Test
    public void theApiTheBundlesAndTheOtherAssetsGetNoPolicy() throws Exception {
        for (String path : List.of("/rest/settings/web", "/main-ABC123.js", "/chunk-X.js", "/media/x.woff2",
                "/assets/i18n/en.json", "/img/x.png", "/wms/wms", "/api/v2/ping", "/ngsw-worker.js",
                "/assets/legal.html", "/assets/x/legal/y.html")) {
            getsNoPolicy(request("GET", path, BROWSER_ACCEPT));
        }
    }

    @Test
    public void aRequestThatIsNoNavigationGetsNoPolicy() throws Exception {
        getsNoPolicy(request("POST", "/messages", BROWSER_ACCEPT));
        getsNoPolicy(request("GET", "/messages", "application/json"));
    }

    @Test
    public void theLegalAndBrandingFilesAreSandboxed() throws Exception {
        getsTheSandbox(request("GET", "/assets/legal/disclaimer.en.html", BROWSER_ACCEPT));
        getsTheSandbox(request("GET", "/assets/branding/en.json", null));
        getsTheSandbox(request("GET", "/assets/branding/favicon.png", "image/*"));
    }

    @Test
    public void theLegalFilesAreSandboxedInAnyLetterCase() throws Exception {
        getsTheSandbox(request("GET", "/assets/LEGAL/x.html", BROWSER_ACCEPT));
        getsTheSandbox(request("GET", "/Assets/Branding/X.PNG", null));
    }

    @Test
    public void thePathIsTheServletPathAndPathInfo() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets/legal/x.html");
        request.setServletPath("/assets");
        request.setPathInfo("/legal/x.html");
        getsTheSandbox(request);
    }
}

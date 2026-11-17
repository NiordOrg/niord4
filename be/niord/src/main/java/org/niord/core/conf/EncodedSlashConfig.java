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
package org.niord.core.conf;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.firewall.FirewalledRequest;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Lets a message be read by a short id that holds a slash: the almanac's {@code A/1 2026}, the annex's
 * {@code B/1 2026}, the firing areas' {@code FA/14 2026}. Both frontends write the id into the address as one
 * encoded path segment, so its slash travels as {@code %2F}.
 *
 * <p>Two layers refuse {@code %2F} by default, and both are opened here:
 * <ul>
 *   <li>Tomcat's connector rejects it with 400 before any filter runs. {@link #encodedSlashPassThrough()} sets its
 *       {@code encodedSolidusHandling} to {@code passthrough}: Tomcat keeps {@code %2F} encoded in the path, so it
 *       never becomes a path separator (not {@code decode}, which would turn {@code ..%2F} into a traversal before
 *       Tomcat normalises the path), and Spring MVC decodes it inside the one segment it belongs to, so
 *       {@code {messageId}} receives {@code B/1 2026}. The setting is connector-wide: from here on only the firewall
 *       below refuses {@code %2F} on the other paths.</li>
 *   <li>Spring Security's {@link StrictHttpFirewall} rejects it as well, and runs in Spring Security's filter at
 *       the start of every request it sees, ahead of the servlets ({@code /wms/*}, {@code /api/secom/*}, the
 *       dispatcher) and of the filters ordered after it. {@link #messageIdFirewall()} installs
 *       {@link MessageIdFirewall}, which accepts {@code %2F} on {@link #MESSAGE_ID_PATHS} only and is the default
 *       strict firewall for every other request.</li>
 * </ul>
 *
 * <p>The three paths are GET reads whose last segment is a message id, a UID or a short id, and the id goes nowhere
 * but the message lookup ({@code MessageService.resolveMessage}): the public API's message details, the
 * administration's message read, and a message's PDF (whose file name, built from a resolved message's id, is
 * URI-encoded into the header). A traversal spelled with {@code %2F} there is an id no message has: it answers 404,
 * and 204 on the administration read. Any other method, a further segment, an encoded double slash and a
 * doubly encoded {@code %252F} are refused there as everywhere else.
 *
 * <p>The filters ordered ahead of Spring Security's ({@code RequestIdFilter}, {@code AdminAppRedirectFilter},
 * {@code TicketServletFilter} and Spring Boot's own) now see a refused request's {@code %2F} before the firewall
 * answers 400. None of them acts on the path: the redirect answers only an exact {@code /}, and the ticket filter
 * resolves a {@code ?ticket=} (which a refused request then uses up) without looking at the path.
 *
 * <p>REPO3 (a repository file name holding {@code %}, {@code ;} or {@code \}, refused by the same firewall) decides
 * its own allowances, in this firewall, when it is taken up; nothing here allows them.
 *
 * <p>A deployment's proxies must pass {@code %2F} on unchanged: nginx does when {@code proxy_pass} names no URI part,
 * and decodes it when it does.
 */
@Configuration
public class EncodedSlashConfig {

    /**
     * The paths, after the context path, on which a GET may carry an encoded slash in its last segment: the
     * message id of {@code ApiRestService.messageDetails}, {@code MessageRestService.getMessage} and
     * {@code MessageReportRestService.generatePdfForMessage}. Matched against the raw request URI, query excluded.
     */
    static final List<Pattern> MESSAGE_ID_PATHS = List.of(
            Pattern.compile("/rest/public/v1/message/[^/]+"),
            Pattern.compile("/rest/messages/message/[^/]+"),
            Pattern.compile("/rest/message-reports/message/[^/]+\\.pdf"));

    /** Tomcat keeps an encoded slash in the path rather than refusing the request; see the class comment */
    @Bean
    public WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> encodedSlashPassThrough() {
        return factory -> factory.addConnectorCustomizers(connector ->
                connector.setEncodedSolidusHandling(EncodedSolidusHandling.PASS_THROUGH.getValue()));
    }

    /** The firewall of every request Spring Security's filter sees; see {@link MessageIdFirewall} */
    @Bean
    public WebSecurityCustomizer messageIdFirewall() {
        HttpFirewall firewall = new MessageIdFirewall();
        return web -> web.httpFirewall(firewall);
    }

    /**
     * Spring Security's default {@link StrictHttpFirewall}, except for a GET of one of {@link #MESSAGE_ID_PATHS}: there
     * an encoded slash ({@code %2F} or {@code %2f}) is accepted. With Tomcat passing it through, the decoded servlet
     * path holds {@code %2F} literally, so that firewall also accepts a {@code %} in the decoded path; a raw
     * {@code %25} (an encoded percent, as in {@code %252F}) is never accepted, so the only {@code %} it can see there
     * is the encoded slash's. Everything else the strict firewall checks (an encoded double slash, period, backslash,
     * semicolon, the normalised path, the method, the headers) it still checks.
     */
    static final class MessageIdFirewall implements HttpFirewall {

        private final StrictHttpFirewall strict = new StrictHttpFirewall();
        private final StrictHttpFirewall encodedSlashAllowed = new StrictHttpFirewall();

        MessageIdFirewall() {
            encodedSlashAllowed.setAllowUrlEncodedSlash(true);
            encodedSlashAllowed.setAllowUrlEncodedPercent(true);
        }

        @Override
        public FirewalledRequest getFirewalledRequest(HttpServletRequest request) throws RequestRejectedException {
            return (isMessageIdRead(request) ? encodedSlashAllowed : strict).getFirewalledRequest(request);
        }

        @Override
        public HttpServletResponse getFirewalledResponse(HttpServletResponse response) {
            return strict.getFirewalledResponse(response);
        }

        /** Whether the request is a GET of one of {@link #MESSAGE_ID_PATHS} without an encoded percent */
        static boolean isMessageIdRead(HttpServletRequest request) {
            String uri = request.getRequestURI();
            if (!"GET".equals(request.getMethod()) || uri == null || uri.contains("%25")) {
                return false;
            }
            String contextPath = request.getContextPath();
            if (contextPath != null && !contextPath.isEmpty()) {
                if (!uri.startsWith(contextPath)) {
                    return false;
                }
                uri = uri.substring(contextPath.length());
            }
            String path = uri;
            return MESSAGE_ID_PATHS.stream().anyMatch(p -> p.matcher(path).matches());
        }
    }
}

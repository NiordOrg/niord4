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

import org.apache.commons.lang3.StringUtils;
import org.niord.core.settings.SettingsService;
import org.niord.web.map.MapTileSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.joining;

/**
 * The Content-Security-Policy of the administration web app's HTML documents, built from the current settings.
 *
 * <p>The directives, in the order they are sent:
 * <ul>
 *   <li>{@code default-src 'self'}: anything not named below comes from the app's own origin only.</li>
 *   <li>{@code script-src 'self'}: the build's bundles and the service worker; no page carries an inline
 *   script, and nothing evaluates code.</li>
 *   <li>{@code style-src 'self' 'unsafe-inline'}: the start-up splash in {@code index.html}, the styles
 *   Angular inserts for its components, and the inline styles of the map and the rich-text editor.</li>
 *   <li>{@code img-src 'self' data: blob:} and the tile hosts: the CSS and the rich-text editor's data URIs,
 *   the protected WMS tiles the app turns into blob URLs, and the base map and OpenSeaMap tiles of the
 *   {@code osmUrl} and {@code openSeaMapUrl} settings. The WMS needs no source: the browser reaches it
 *   through the same-origin {@code /wms/} proxy.</li>
 *   <li>{@code font-src 'self'}: the fonts of the build.</li>
 *   <li>{@code connect-src 'self'} and Keycloak: the REST API, and keycloak-js's token exchange.</li>
 *   <li>{@code frame-src 'self'} and Keycloak: the hidden iframe of keycloak-js's silent login check, which
 *   loads {@code silent-check-sso.html} back from this origin after Keycloak.</li>
 *   <li>{@code object-src 'none'}: no plug-in content.</li>
 *   <li>{@code base-uri 'self'}: no injected {@code <base>} can move the relative addresses elsewhere.</li>
 *   <li>{@code form-action 'self'}: no form posts elsewhere; keycloak-js logs in and out by navigation.</li>
 *   <li>{@code frame-ancestors 'self'}: only this origin may frame the app, as {@code silent-check-sso.html}
 *   requires; browsers apply it instead of {@code X-Frame-Options}.</li>
 * </ul>
 *
 * <p>Keycloak is the origin of the token issuer
 * ({@code spring.security.oauth2.resourceserver.jwt.issuer-uri}), the Keycloak the browser reaches; on a
 * deployment that serves it at {@code /auth} of the same origin it adds nothing to {@code 'self'}.
 *
 * <p>A tile host is taken from the setting's value as the browser reads it, never copied as text: an empty
 * value means the web maps' default ({@link MapTileSettings#DEFAULT_OSM_URL},
 * {@link MapTileSettings#DEFAULT_OPEN_SEA_MAP_URL}); a scheme-relative value ({@code //host/...}) gives the
 * host alone; a relative value gives nothing, {@code 'self'} covers it; a first host label with an OpenLayers
 * range such as {@code {a-c}} becomes {@code *.}, the only place a source admits a wildcard; any other value
 * that is not an {@code http} or {@code https} URL with a plain host and port, a range in a later label
 * included, gives nothing and is logged once as a warning with its cause, so a setting can never add a
 * directive.
 *
 * <p>The HTML responses read {@link #current()} on every request, and it reads both settings uncached and
 * without storing a row ({@link SettingsService#peek}), so a change on the Settings page applies on the next
 * page load on every pod. The policy is rebuilt only when a value it read differs from the last.
 *
 * <p>Not a bean: niord-base's {@link org.niord.core.conf.WebAppConfig} constructs it, and no country can replace
 * that class or its bean ({@code CountryContractGuard}). The guard does not check servlet filters: a country
 * filter that rewrites or drops the header {@code WebAppHeadersFilter} sets is refused by review
 * ({@code docs/fe-country-split-plan.md} §4.11 rule 3, {@code be/docs/country-api.md}).
 */
public final class WebAppContentSecurityPolicy {

    private static final Logger log = LoggerFactory.getLogger(WebAppContentSecurityPolicy.class);

    /** The scheme of a URL, as RFC 3986 spells it */
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

    /** A URL's scheme and authority; the authority ends where a browser ends it, also at a backslash */
    private static final Pattern SCHEME_AND_AUTHORITY =
            Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*)://([^/\\\\?#]*)");

    /** A host name or IPv4 address that cannot carry anything but itself into the header */
    private static final Pattern PLAIN_HOST = Pattern.compile("^[A-Za-z0-9.-]+$");

    /** A bracketed IPv6 literal */
    private static final Pattern IPV6_LITERAL = Pattern.compile("^\\[[0-9A-Fa-f:.]+]$");

    /** The settings the policy reads, and what it read last */
    private record Built(String osmValue, String openSeaMapValue, String policy) {}

    /** The settings service, resolved on the first read: see the constructor */
    private final Supplier<SettingsService> settings;

    /** The Keycloak origin, or null when the issuer gives none */
    private final String keycloakOrigin;

    private final AtomicReference<Built> memo = new AtomicReference<>();

    /** The values already warned about, so that each is logged once, not on every page load */
    private final Set<String> warnedValues = ConcurrentHashMap.newKeySet();

    /**
     * Creates the policy.
     * <p>
     * The settings service is passed as a supplier and asked for on the first read only. The filter that sends
     * the policy is created with the embedded web server, before the persistence layer exists, and the
     * service needs its entity manager: resolving it then stops the backend from starting.
     *
     * @param settings supplies the settings service the tile settings are read through
     * @param issuerUri the token issuer, whose origin is the Keycloak the browser reaches; null or blank for none
     */
    public WebAppContentSecurityPolicy(Supplier<SettingsService> settings, String issuerUri) {
        this.settings = Objects.requireNonNull(settings);
        this.keycloakOrigin = StringUtils.isBlank(issuerUri) ? null : originOf(issuerUri.trim(), false);
        if (keycloakOrigin == null) {
            log.info("The token issuer '{}' gives no Keycloak origin: the web app's Content-Security-Policy"
                    + " admits Keycloak on its own origin only", Objects.toString(issuerUri, ""));
        }
    }

    /**
     * Returns the policy for the current settings.
     * <p>
     * Never fails: when the settings cannot be read, the policy built last is returned, or the one of the
     * defaults before any was built.
     *
     * @return the policy, the same instance as long as the settings it reads are unchanged
     */
    public String current() {
        String osmValue;
        String openSeaMapValue;
        try {
            osmValue = read(MapTileSettings.OSM_URL.getKey());
            openSeaMapValue = read(MapTileSettings.OPEN_SEA_MAP_URL.getKey());
        } catch (RuntimeException e) {
            Built last = memo.get();
            log.warn("Could not read the tile settings of the web app's Content-Security-Policy; sending {}: {}",
                    last != null ? "the last one" : "the defaults' one", e.toString());
            return last != null ? last.policy() : build(null, null);
        }
        Built last = memo.get();
        if (last != null && Objects.equals(last.osmValue(), osmValue)
                && Objects.equals(last.openSeaMapValue(), openSeaMapValue)) {
            return last.policy();
        }
        Built built = new Built(osmValue, openSeaMapValue, build(osmValue, openSeaMapValue));
        if (memo.compareAndSet(last, built)) {
            log.info("The web app's Content-Security-Policy is now: {}", built.policy());
        }
        return built.policy();
    }

    /** Reads the setting, uncached and without storing a row */
    private String read(String key) {
        Object value = settings.get().peek(key);
        return value == null ? null : value.toString();
    }

    /** Builds the policy from the two tile settings' values */
    private String build(String osmValue, String openSeaMapValue) {
        Set<String> tileSources = new LinkedHashSet<>();
        addTileSource(tileSources, MapTileSettings.OSM_URL.getKey(), osmValue, MapTileSettings.DEFAULT_OSM_URL);
        addTileSource(tileSources, MapTileSettings.OPEN_SEA_MAP_URL.getKey(), openSeaMapValue,
                MapTileSettings.DEFAULT_OPEN_SEA_MAP_URL);
        String keycloak = keycloakOrigin == null ? "" : " " + keycloakOrigin;
        return "default-src 'self'"
                + "; script-src 'self'"
                + "; style-src 'self' 'unsafe-inline'"
                + "; img-src 'self' data: blob:" + tileSources.stream().map(source -> " " + source).collect(joining())
                + "; font-src 'self'"
                + "; connect-src 'self'" + keycloak
                + "; frame-src 'self'" + keycloak
                + "; object-src 'none'"
                + "; base-uri 'self'"
                + "; form-action 'self'"
                + "; frame-ancestors 'self'";
    }

    /** Adds the source of the tile setting's value, or of the default when it is empty */
    private void addTileSource(Set<String> sources, String key, String value, String defaultTemplate) {
        String template = StringUtils.trimToEmpty(value);
        if (template.isEmpty()) {
            template = defaultTemplate;
        }
        if (!template.startsWith("//") && !SCHEME.matcher(template).find()) {
            // A relative address loads from this origin
            return;
        }
        String source = originOf(template, true);
        if (source != null) {
            sources.add(source);
        } else if (warnedValues.add(key + '\n' + template)) {
            log.warn("The {} setting '{}' {}: the web app's Content-Security-Policy admits no tile host for it",
                    key, template, whyNoSource(template));
        }
    }

    /**
     * Returns why a tile template that is not relative gives no source, for the warning: a source may carry a
     * wildcard only in its first host label, so an OpenLayers range in a later label cannot be admitted,
     * although OpenLayers expands it there.
     *
     * @param template the trimmed tile URL template, absolute or scheme-relative
     * @return the cause, worded to follow the setting's name and value
     */
    static String whyNoSource(String template) {
        Matcher parts = SCHEME_AND_AUTHORITY.matcher(template.startsWith("//") ? "https:" + template : template);
        if (parts.find()) {
            String authority = parts.group(2);
            int firstDot = authority.indexOf('.');
            if (firstDot >= 0 && MapTileSettings.SUBDOMAIN_RANGE.matcher(authority.substring(firstDot)).find()) {
                return "has an OpenLayers range outside the first host label, the only label a range is admitted"
                        + " in: move the range there or name one host";
            }
        }
        return "is not an http or https URL with a plain host";
    }

    /**
     * Returns the source a browser loads the URL from: {@code scheme://host[:port]}, or {@code host[:port]} for
     * a scheme-relative URL; null when it is no {@code http} or {@code https} URL with a plain host and port.
     *
     * @param url the trimmed URL or tile URL template
     * @param tileTemplate whether a first host label with an OpenLayers range becomes {@code *.}
     * @return the source, or null
     */
    static String originOf(String url, boolean tileTemplate) {
        boolean schemeRelative = url.startsWith("//");
        Matcher parts = SCHEME_AND_AUTHORITY.matcher(schemeRelative ? "https:" + url : url);
        if (!parts.find()) {
            return null;
        }
        String scheme = parts.group(1).toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return null;
        }
        String authority = parts.group(2);
        boolean wildcard = false;
        int firstDot = authority.indexOf('.');
        if (tileTemplate && firstDot > 0
                && MapTileSettings.SUBDOMAIN_RANGE.matcher(authority.substring(0, firstDot)).find()) {
            wildcard = true;
            authority = "w" + authority.substring(firstDot);
        }
        URI uri;
        try {
            uri = new URI(scheme + "://" + authority + "/");
        } catch (URISyntaxException e) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null
                || !(PLAIN_HOST.matcher(host).matches() || IPV6_LITERAL.matcher(host).matches())) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (wildcard) {
            host = "*" + host.substring(1);
        }
        String hostAndPort = uri.getPort() < 0 ? host : host + ":" + uri.getPort();
        return schemeRelative ? hostAndPort : scheme + "://" + hostAndPort;
    }
}

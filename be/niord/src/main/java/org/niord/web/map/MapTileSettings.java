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
package org.niord.web.map;

import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.NiordBaseVersion;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The map tile settings: the tile addresses of the base map and of the OpenSeaMap sea-mark layer, the contact
 * the backend names when it fetches base map tiles itself, and the folder it caches them in.
 *
 * <p>Both web applications read {@code osmUrl} and {@code openSeaMapUrl} from {@code /rest/settings/web}. The
 * backend reads the base map address to draw the message map images, which show the base map only: no backend
 * code fetches sea-mark tiles. Every accessor reads its setting live through {@link SettingsService}, so an edit
 * on the Settings page takes effect without a restart; none of them is an {@code @Setting} field, which is
 * injected once, when the bean is created.
 *
 * <p>The tile addresses are URL templates with {@code {z}}, {@code {x}} and {@code {y}} placeholders, as the web
 * maps use them. The accessors return a template the backend can fetch from, or the default: see
 * {@link #normaliseTemplate}.
 */
@Service
public class MapTileSettings {

    /**
     * The base map of the OpenStreetMap Foundation. The web maps' default is the same (OpenLayers' OSM source);
     * the administration app's Content-Security-Policy relies on it.
     */
    public static final String DEFAULT_OSM_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";

    /**
     * The sea-mark overlay of OpenSeaMap. The web maps' default is the same ({@code fe/shared-map}); the
     * administration app's Content-Security-Policy relies on it.
     */
    public static final String DEFAULT_OPEN_SEA_MAP_URL = "https://t1.openseamap.org/seamark/{z}/{x}/{y}.png";

    /** The name of the tile cache folder under {@code niord.home} */
    static final String TILE_CACHE_FOLDER = "tile-cache";

    // The descriptions name the range rule: the administration app's Content-Security-Policy admits a range such as
    // {a-c} only in the first host label (WebAppContentSecurityPolicy), and the message map images take none
    // (normaliseTemplate). The Setting.description column holds 255 characters.
    public static final Setting OSM_URL = new Setting("osmUrl", DEFAULT_OSM_URL)
            .description("Base map tile URL with {z}, {x} and {y}: the web maps and, without a range such as {a-c},"
                    + " the message map images. Use https from a host that sends CORS headers; a range only in the"
                    + " first host label. Another host: update the public site's cookie statement.")
            .web(true)
            .editable(true);

    public static final Setting OPEN_SEA_MAP_URL = new Setting("openSeaMapUrl", DEFAULT_OPEN_SEA_MAP_URL)
            .description("Tile URL of the OpenSeaMap sea-mark layer of the web maps, with {z}, {x} and {y}."
                    + " Use https from a host that sends CORS headers; a range such as {a-c} only in the first"
                    + " host label.")
            .web(true)
            .editable(true);

    static final Setting MAP_TILE_CONTACT = new Setting("mapTileContact", "")
            .description("E-mail address or URL that the backend names in the User-Agent of its map tile requests,"
                    + " so that the tile server can reach this deployment. Empty: the baseUri.")
            .web(false)
            .editable(true);

    // Not editable on the Settings page: the folder is part of the deployment (a volume), and a folder under the
    // repository would publish the cached tiles. A deployment overrides it with a system property or an
    // environment variable of the same name.
    static final Setting MAP_TILE_CACHE_PATH = new Setting("mapTileCachePath", "${niord.home}/" + TILE_CACHE_FOLDER)
            .description("Folder of the backend's map tile cache, outside the repository: the tiles must never be"
                    + " served from it.")
            .web(false)
            .editable(false);

    /**
     * The default cache folder as it reads when nothing resolves {@code niord.home}: {@link SettingsService}
     * expands an unresolvable {@code ${token}} to the empty string, which would put the cache at the root of
     * the file system.
     */
    static final String UNRESOLVED_HOME_TILE_CACHE_PATH = "/" + TILE_CACHE_FOLDER;

    /** An OpenLayers subdomain range such as {@code {a-c}}, which the backend does not expand */
    public static final Pattern SUBDOMAIN_RANGE = Pattern.compile("\\{[a-z0-9]-[a-z0-9]}", Pattern.CASE_INSENSITIVE);

    /** The characters RFC 9110 allows in a token, besides letters and digits */
    private static final String TOKEN_PUNCTUATION = "!#$%&'*+-.^_`|~";

    private static final Logger log = LoggerFactory.getLogger(MapTileSettings.class);

    @Inject
    SettingsService settingsService;

    @Inject
    NiordApp app;

    /** Per setting key, the value read last, so that a value that cannot be used is logged once, not per call */
    private final Map<String, String> lastReadValues = new ConcurrentHashMap<>();

    /**
     * Returns the tile URL template of the base map, which the web maps and the message map images draw.
     * @return the configured template, normalised, or the OpenStreetMap default
     */
    public String osmUrlTemplate() {
        return tileUrlTemplate(OSM_URL, DEFAULT_OSM_URL);
    }

    /**
     * Returns the tile URL template of the OpenSeaMap sea-mark layer. Only the web maps draw that layer; they
     * read the setting from {@code /rest/settings/web}.
     * @return the configured template, normalised, or the OpenSeaMap default
     */
    public String openSeaMapUrlTemplate() {
        return tileUrlTemplate(OPEN_SEA_MAP_URL, DEFAULT_OPEN_SEA_MAP_URL);
    }

    /**
     * Returns the User-Agent of the backend's map tile requests, {@code Niord/<version> (+<contact>)}: the
     * niord-base version and the {@code mapTileContact} setting, or the {@code baseUri} when that is empty. Tile
     * servers such as the OpenStreetMap Foundation's ask for a User-Agent that identifies the application and a
     * way to reach whoever runs it. Built from the live settings on each call.
     *
     * @return the User-Agent, valid as an HTTP header value
     */
    public String userAgent() {
        String contact = StringUtils.trimToNull(settingsService.getString(MAP_TILE_CONTACT));
        return userAgent(NiordBaseVersion.get(), contact != null ? contact : app.getBaseUri());
    }

    /**
     * Returns the folder of the backend's map tile cache: the {@code mapTileCachePath} setting, by default
     * {@code niord.home}/{@value #TILE_CACHE_FOLDER}. When the setting is empty, is not a path, or is the default
     * with nothing to resolve {@code niord.home}, the folder is {@code niord-tile-cache} under the JVM's
     * temporary folder instead, and a warning is logged.
     *
     * @return the folder, which need not exist yet
     */
    public Path tileCacheDir() {
        String configured = StringUtils.trimToNull(settingsService.getString(MAP_TILE_CACHE_PATH));
        boolean changed = changedSinceLastRead(MAP_TILE_CACHE_PATH.getKey(), configured);
        String problem;
        if (configured == null) {
            problem = "is empty";
        } else if (configured.equals(UNRESOLVED_HOME_TILE_CACHE_PATH) && !niordHomeResolves()) {
            problem = "names the root of the file system, because nothing resolves niord.home";
        } else {
            try {
                return Path.of(configured);
            } catch (InvalidPathException e) {
                problem = "is not a path";
            }
        }
        Path fallback = temporaryTileCacheDir();
        if (changed) {
            warnUnusable(MAP_TILE_CACHE_PATH.getKey(), configured, problem, fallback.toString());
        }
        return fallback;
    }

    /**
     * Returns the tile cache folder of last resort: {@code niord-tile-cache} under the JVM's temporary folder.
     * @return the folder, which need not exist yet
     */
    static Path temporaryTileCacheDir() {
        return Path.of(System.getProperty("java.io.tmpdir"), "niord-" + TILE_CACHE_FOLDER);
    }

    /**
     * Warns once at start-up when the tile requests would reach a public tile server without a way to reach
     * this deployment: {@code mapTileContact} is empty and the {@code baseUri} names this machine. A base map
     * address on this machine (a local stand-in) needs no contact.
     *
     * <p>Not transactional: it only reads, each read in {@link SettingsService}'s own transaction, and an
     * enclosing one would turn a caught failure into a failed commit at the end of start-up.
     */
    @EventListener(ApplicationReadyEvent.class)
    void warnWhenTileRequestsNameNoContact() {
        try {
            if (StringUtils.isNotBlank(settingsService.getString(MAP_TILE_CONTACT))) {
                return;
            }
            String baseUri = app.getBaseUri();
            String baseHost = hostOf(baseUri);
            String tileHost = hostOf(osmUrlTemplate());
            if ((baseHost == null || isLoopbackHost(baseHost)) && !isLoopbackHost(tileHost)) {
                log.warn("mapTileContact is empty and baseUri '{}' names this machine: the requests to the tile"
                                + " server {} carry the User-Agent '{}', which gives no way to reach this deployment."
                                + " Set mapTileContact to an e-mail address or URL.",
                        baseUri, tileHost, userAgent());
            }
        } catch (RuntimeException e) {
            log.warn("Could not check the contact of the map tile requests", e);
        }
    }

    /** Returns the normalised template of the setting, warning once about a value that cannot be used */
    private String tileUrlTemplate(Setting setting, String defaultTemplate) {
        String configured = settingsService.getString(setting);
        boolean changed = changedSinceLastRead(setting.getKey(), configured);
        return normaliseTemplate(configured, defaultTemplate, problem -> {
            if (changed) {
                warnUnusable(setting.getKey(), configured, problem, defaultTemplate);
            }
        });
    }

    /** Records the value read for the key, and returns whether it differs from the value read before */
    private boolean changedSinceLastRead(String key, String value) {
        String read = Objects.toString(value, "");
        return !read.equals(lastReadValues.put(key, read));
    }

    private static void warnUnusable(String key, String value, String problem, String replacement) {
        log.warn("The {} setting '{}' {}; using {} instead", key, Objects.toString(value, ""), problem, replacement);
    }

    private boolean niordHomeResolves() {
        Object home = settingsService.peek(SettingsService.NIORD_HOME.getKey());
        return home != null && StringUtils.isNotBlank(home.toString());
    }

    /**
     * Returns the tile URL template the backend uses for a configured value:
     * <ul>
     *     <li>an empty value means the default;</li>
     *     <li>a scheme-relative value ({@code //host/...}) is given {@code https:};</li>
     *     <li>a value with a subdomain range such as {@code {a-c}}, without one of the {@code {z}}, {@code {x}}
     *     and {@code {y}} placeholders, that is not an {@code http} or {@code https} URL, or that names no host
     *     is rejected: the default is used, and the problem goes to {@code rejected}.</li>
     * </ul>
     *
     * @param configured the configured value, or null
     * @param defaultTemplate the template to use instead of an empty or rejected value
     * @param rejected receives what is wrong with a rejected value
     * @return the template
     */
    static String normaliseTemplate(String configured, String defaultTemplate, Consumer<String> rejected) {
        String template = StringUtils.trimToEmpty(configured);
        if (template.isEmpty()) {
            return defaultTemplate;
        }
        if (template.startsWith("//")) {
            template = "https:" + template;
        }
        String problem = problemOf(template);
        if (problem != null) {
            rejected.accept(problem);
            return defaultTemplate;
        }
        return template;
    }

    /** Returns what makes the template unusable for the backend, or null when it is usable */
    private static String problemOf(String template) {
        if (SUBDOMAIN_RANGE.matcher(template).find()) {
            return "names a range of subdomains such as {a-c}, which the backend does not expand";
        }
        if (!template.contains("{z}") || !template.contains("{x}") || !template.contains("{y}")) {
            return "lacks one of the {z}, {x} and {y} placeholders";
        }
        URI uri;
        try {
            uri = new URI(withPlaceholdersFilled(template));
        } catch (URISyntaxException e) {
            return "is not a valid URL";
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            return "is not an http or https URL";
        }
        if (StringUtils.isBlank(uri.getHost())) {
            return "names no host";
        }
        return null;
    }

    private static String withPlaceholdersFilled(String template) {
        return template.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0");
    }

    /** Returns the host of the URL or tile URL template, or null when it has none */
    static String hostOf(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        try {
            return StringUtils.trimToNull(new URI(withPlaceholdersFilled(url.trim())).getHost());
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Returns whether the host names this machine: localhost, a loopback address or the unspecified address */
    static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String name = StringUtils.strip(host.toLowerCase(Locale.ROOT), "[]");
        return name.equals("localhost") || name.endsWith(".localhost")
                || name.startsWith("127.") || name.equals("0.0.0.0")
                || name.equals("::1") || name.equals("0:0:0:0:0:0:0:1");
    }

    /**
     * Returns the User-Agent {@code Niord/<version> (+<contact>)}, or {@code Niord/<version>} without a contact,
     * with the version reduced to RFC 9110 token characters and the contact to comment text: printable US-ASCII
     * without parentheses and backslashes, with runs of white space as one space.
     *
     * @param version the version, {@value NiordBaseVersion#FALLBACK} when it has no token characters
     * @param contact the contact, or null
     * @return the User-Agent
     */
    static String userAgent(String version, String contact) {
        String product = "Niord/" + tokenOf(version);
        String comment = commentTextOf(contact);
        return comment.isEmpty() ? product : product + " (+" + comment + ")";
    }

    private static String tokenOf(String value) {
        StringBuilder token = new StringBuilder();
        for (char c : Objects.toString(value, "").toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || TOKEN_PUNCTUATION.indexOf(c) >= 0) {
                token.append(c);
            }
        }
        return token.isEmpty() ? NiordBaseVersion.FALLBACK : token.toString();
    }

    private static String commentTextOf(String value) {
        StringBuilder text = new StringBuilder();
        for (char c : Objects.toString(value, "").toCharArray()) {
            if (c > ' ' && c < 0x7f && c != '(' && c != ')' && c != '\\') {
                text.append(c);
            } else if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                text.append(' ');
            }
        }
        return text.toString().trim().replaceAll(" {2,}", " ");
    }
}

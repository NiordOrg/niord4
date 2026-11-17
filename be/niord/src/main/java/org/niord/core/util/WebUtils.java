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

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.web.NiordWebException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.function.Predicate.not;

/**
 * Web-related utility functions
 */
@SuppressWarnings("unused")
public class WebUtils {

    private WebUtils() {
    }

    /**
     * Returns the base URL of the request
     * @param request the request
     * @return the base URL
     */
    public static String getWebAppUrl(HttpServletRequest request, String... appends) {
        StringBuilder result = new StringBuilder(String.format(
                "%s://%s%s%s",
                request.getScheme(),
                request.getServerName(),
                request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort(),
                request.getContextPath()));
        for (String a : appends) {
            result.append(a);
        }
        return result.toString();
    }

    /**
     * Returns the base URL of the request
     * @param request the request
     * @return the base URL
     */
    public static String getServletUrl(HttpServletRequest request, String... appends) {
        String[] args = (String[])ArrayUtils.addAll(new String[] { request.getServletPath() }, appends);
        return getWebAppUrl(request, args);
    }

    /**
     * Returns the cookie with the given name or null if not found
     * @param request the request
     * @param name the name
     * @return the cookie with the given name or null if not found
     */
    public static Cookie getCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : request.getCookies()) {
                if (c.getName().equals(name)) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * Returns the value of the cookie with the given name or null if not found
     * @param request the request
     * @param name the name
     * @return the value of the cookie with the given name or null if not found
     */
    public static String getCookieValue(HttpServletRequest request, String name) {
        Cookie c = getCookie(request, name);
        return (c == null) ? null : c.getValue();
    }

    /**
     * Returns a single parameter value from the request parameter map
     * @param reqParams the request parameter map
     * @param name the name of the parameter
     * @return the value or null if not defined
     */
    public static String getParameterValues(Map<String, String[]> reqParams, String name) {
        if (reqParams != null && name != null) {
            String[] values = reqParams.get(name);
            return values == null || values.length == 0 ? null : values[0];
        }
        return null;
    }

    /**
     * Parses the URL (or part of the URL) to extract a request parameter map.
     * @param url the URL to parse
     * @return the parsed request parameter map
     */
    public static Map<String, String[]> parseParameterMap(String url) {
        Map<String, List<String>> params = new HashMap<>();

        if (StringUtils.isNotBlank(url)) {
            int index = url.indexOf("?");
            if (index > -1) {
                url = url.substring(index + 1);
            }
        }
        if (StringUtils.isNotBlank(url)) {
            for (String kv : url.split("&")) {
                String key, value = "";
                int x = kv.indexOf("=");
                if (x == -1) {
                    key = urlDecode(kv);
                } else {
                    key = urlDecode(kv.substring(0, x));
                    value = urlDecode(kv.substring(x + 1));
                }
                params.computeIfAbsent(key, v -> new ArrayList<>())
                        .add(value);
            }
        }
        return params.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().toArray(new String[e.getValue().size()])));
    }

    /**
     * Reads the body of a posted request
     * @param request the request
     * @return the body
     */
    public static String readRequestBody(HttpServletRequest request) throws IOException {
        StringBuilder result = new StringBuilder();

        String line;
        BufferedReader reader = request.getReader();
        while ((line = reader.readLine()) != null) {
            result.append(line).append("\n");
        }

        return result.toString();
    }

    /**
     * Add headers to the response to ensure no caching takes place
     * @param response the response
     * @return the response
     */
    public static HttpServletResponse nocache(HttpServletResponse response) {
        response.setHeader("Cache-Control","no-cache");
        response.addHeader("Cache-Control","no-store");
        response.setHeader("Pragma","no-cache");
        response.setDateHeader ("Expires", 0);
        return response;
    }

    /**
     * Add headers to the response to ensure caching in the given duration
     * @param response the response
     * @param seconds the number of seconds to cache the response
     * @return the response
     */
    public static HttpServletResponse cache(HttpServletResponse response, int seconds) {
        long now = System.currentTimeMillis();
        response.addHeader("Cache-Control", "max-age=" + seconds);
        response.setDateHeader("Last-Modified", now);
        response.setDateHeader("Expires", now + seconds * 1000L);
        return response;
    }

    /**
     * Encode identically to the javascript encodeURIComponent() method
     * @param s the string to encode
     * @return the encoded string
     */
    public static String encodeURIComponent(String s) {
        String result;

        try {
            result = URLEncoder.encode(s, "UTF-8")
                    .replaceAll("\\+", "%20")
                    .replaceAll("\\%21", "!")
                    .replaceAll("\\%27", "'")
                    .replaceAll("\\%28", "(")
                    .replaceAll("\\%29", ")")
                    .replaceAll("\\%7E", "~");
        } catch (Exception e) {
            result = s;
        }

        return result;
    }

    /**
     * Encodes a repository path for a file's link ({@code /rest/repo/file/<path>}): each character as
     * {@link #encodeURIComponent} does, but the slash, the colon and the semicolon are kept. Unlike the javascript
     * encodeURI() method, a question mark and an apostrophe in a name are encoded ({@code %3F} and {@code %27}):
     * a raw question mark would start the link's query string, and Niord's own link reader
     * ({@link org.niord.core.repo.RepoPaths#fileLinkPaths}) ends a link at either (REPO4).
     * @param s the string to encode
     * @return the encoded string
     */
    public static String encodeURI(String s) {
        return encodeURIComponent(s)
                    .replaceAll("\\%3A", ":")
                    .replaceAll("\\%2F", "/")
                    .replaceAll("\\%3B", ";")
                    .replace("'", "%27");
    }

    /**
     * Wraps a call to URLDecoder.decode(value, "UTF-8") to prevent the exception signature
     * @param value the value to decode
     * @return the decoded value or the original value in case of an exception
     */
    public static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Spring MVC multipart helpers, replacing the RESTEasy MultipartFormDataInput/MultipartInput ones from
    // the Jersey->Spring-MVC migration. They produce the same return shapes from a Spring
    // MultipartHttpServletRequest so the batch-upload resources (and AbstractBatchableRestService) keep
    // their downstream logic unchanged.
    // ------------------------------------------------------------------------------------------- //

    /**
     * Retrieves the non-file form parameters of a multipart request, returned by number
     * (0 -&gt; null, 1 -&gt; String, many -&gt; List&lt;String&gt;). File parts are excluded (use
     * {@link #getMultipartFiles}).
     *
     * <p>This reads the multipart <em>body</em> parts directly via {@link HttpServletRequest#getParts()}
     * rather than {@code getParameterMap()}: the latter (per the Servlet spec) also merges URL
     * query-string parameters, which the original RESTEasy reader never did — that would otherwise leak
     * query params into the form-parameter map (e.g. into persisted batch-job properties) and could turn a
     * key supplied both in the body and the query into a multi-valued list.
     *
     * @param request the Spring multipart request
     * @return the extracted parameters' map
     */
    public static Map<String, Object> getMultipartFormParams(MultipartHttpServletRequest request) throws IOException, ServletException {
        final Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Part part : request.getParts()) {
            // A null submitted file name identifies a plain form field (not an uploaded file).
            if (part.getSubmittedFileName() == null) {
                try (InputStream in = part.getInputStream()) {
                    grouped.computeIfAbsent(part.getName(), k -> new ArrayList<>())
                           .add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        final Map<String, Object> formParams = new HashMap<>();
        grouped.forEach((key, values) -> {
            if (values.isEmpty()) {
                formParams.put(key, null);
            } else if (values.size() == 1) {
                formParams.put(key, values.get(0));
            } else {
                formParams.put(key, new ArrayList<>(values));
            }
        });
        return formParams;
    }

    /**
     * Retrieves all uploaded files of a multipart request keyed by their file name, reduced to a bare name
     * (see {@link #bareFileName(String)}). Zero-byte uploads are included (the original RESTEasy reader
     * accepted them) — only the presence of a file part matters, not its size.
     * <p>
     * The submitted file name is chosen by the client, and callers resolve it against a folder to write the
     * file there, so no directory part of it, and no absolute form, reaches them. A part whose name leaves
     * no usable file name is refused.
     * <p>
     * {@link MultipartFile#getInputStream()} opens a new stream on each call, so a caller that reads a file
     * more than once (a check, then the import) asks for a stream each time and closes it.
     *
     * @param request the Spring multipart request
     * @return the uploaded files map
     * @throws NiordWebException 400 when a part's file name is blank or names only a directory
     */
    public static Map<String, MultipartFile> getMultipartFileMap(MultipartHttpServletRequest request) {
        final Map<String, MultipartFile> inputFiles = new HashMap<>();
        for (List<MultipartFile> files : request.getMultiFileMap().values()) {
            for (MultipartFile file : files) {
                if (file != null) {
                    String fileName = bareFileName(file.getOriginalFilename());
                    if (fileName == null) {
                        throw new NiordWebException("An uploaded file has no usable file name", 400);
                    }
                    inputFiles.put(fileName, file);
                }
            }
        }
        return inputFiles;
    }

    /**
     * Retrieves all uploaded files of a multipart request as streams, keyed by their file name as
     * {@link #getMultipartFileMap(MultipartHttpServletRequest)} keys them: reduced to a bare name, zero-byte
     * uploads included, and a part whose name leaves no usable file name refused.
     *
     * @param request the Spring multipart request
     * @return the uploaded files map
     * @throws NiordWebException 400 when a part's file name is blank or names only a directory
     */
    public static Map<String, InputStream> getMultipartFiles(MultipartHttpServletRequest request) throws IOException {
        final Map<String, InputStream> inputFiles = new HashMap<>();
        for (Map.Entry<String, MultipartFile> file : getMultipartFileMap(request).entrySet()) {
            inputFiles.put(file.getKey(), file.getValue().getInputStream());
        }
        return inputFiles;
    }

    /**
     * A file name reduced to a bare name: any directory part stripped ({@code '/'} and {@code '\'} both
     * separate), then trimmed. Null where nothing usable is left: a null or blank name, {@code "."} or
     * {@code ".."}.
     * <p>
     * A browser may send the client-side path with the name, and any other client may send what it likes,
     * so a name that is resolved against a folder is reduced with this first. What remains names a file
     * directly inside that folder.
     *
     * @param fileName the file name, possibly with a path
     * @return the bare file name, or null
     */
    public static String bareFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String bare = fileName.replace('\\', '/');
        int slash = bare.lastIndexOf('/');
        if (slash >= 0) {
            bare = bare.substring(slash + 1);
        }
        bare = bare.trim();
        if (bare.isEmpty() || ".".equals(bare) || "..".equals(bare)) {
            return null;
        }
        return bare;
    }

    /**
     * Whether the file name already is a bare name: {@link #bareFileName(String)} leaves it as it is, apart
     * from surrounding white space.
     *
     * @param fileName the file name
     * @return whether the file name is a bare name
     */
    public static boolean isBareFileName(String fileName) {
        return fileName != null && fileName.trim().equals(bareFileName(fileName));
    }

    /**
     * Resolves a file name against a folder, and returns the file only when it lies directly inside the folder.
     * <p>
     * The name comes from a client or from stored data, and the folders next to the one it is meant for may be
     * someone else's. So the name, resolved against the folder and normalised, must name a file whose parent is
     * the folder itself: a name that climbs out of the folder, one in a sub-folder, one in absolute form, one that
     * names the folder itself and one the file system cannot represent are all refused. Where a name that carries
     * a path is to be reduced rather than refused, reduce it with {@link #bareFileName(String)} first.
     *
     * @param folder the folder
     * @param fileName the file name
     * @return the normalised file directly inside the folder, or null when the name does not name one
     */
    public static Path fileDirectlyIn(Path folder, String fileName) {
        if (fileName == null) {
            return null;
        }
        try {
            Path file = folder.resolve(fileName).normalize();
            Path parent = file.getParent();
            return parent != null && parent.toAbsolutePath().normalize().equals(folder.toAbsolutePath().normalize())
                    ? file
                    : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * Normalises a Spring MVC {@code {*var}} capture-the-rest path variable to the form a JAX-RS
     * {@code @PathParam("var")} on a {@code {var:.+}} template produced: Spring's {@code {*var}} includes
     * the leading {@code '/'} separator (e.g. {@code "/temp/uuid"}), whereas the JAX-RS variable did not
     * ({@code "temp/uuid"}). Strips a single leading slash so the downstream repo-relative path logic is
     * unchanged. Used by the migrated resources whose multi-segment paths became {@code {*var}}.
     */
    public static String trimLeadingSlash(String path) {
        return (path != null && path.startsWith("/")) ? path.substring(1) : path;
    }

    /**
     * Builds an absolute URL for a root-relative path (e.g. {@code "/rest/repo/file/x"} or
     * {@code "/img/filetypes/y"}) against the current request's scheme/host/port/context-path.
     *
     * <p>Reproduces the absolutization that JAX-RS {@code Response.temporaryRedirect()} performed against
     * the application base URI. Spring MVC otherwise emits a relative {@code Location} verbatim, which a
     * client resolves against the request URI — landing on the wrong, often non-existent, URL. Call sites
     * must pass a <em>root-relative</em> path (drop any JAX-RS-era {@code "../"} prefix, which existed only
     * so Jersey would re-resolve it against {@code /rest}).
     */
    public static URI absoluteUrl(String rootRelativePath) {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path(rootRelativePath).build().toUri();
    }

}

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
package org.niord.core.script;

import org.niord.core.script.vo.ScriptResourceImportPreviewVo;
import org.niord.core.script.vo.ScriptResourceVo;
import org.niord.core.util.JsonUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The decisions of the Script resources import (docs/code-data-config-plan.md §6.4), shared by its preview and its
 * batch job ({@code script-resource-import}).
 *
 * <p>A file is refused when {@link ScriptResourceCheck} finds a reason, or when its path appears earlier in the same
 * import. Otherwise a path the database lacks is added, unless the sysadmin unticked it; a path whose row has the same
 * content is unchanged; and a path whose row has other content is overwritten only when the sysadmin ticked it. The
 * sysadmin's ticks reach the job as the job properties {@value #ADD}, a JSON array of paths, and {@value #OVERWRITE},
 * a JSON array whose elements are a path or {@code {"path", "basedOn"}}: without {@value #ADD} every missing path is
 * added, and without {@value #OVERWRITE} no row is overwritten, so the bootstrap, which passes neither, adds what a
 * fresh database lacks and changes nothing it has. {@code basedOn} is the fingerprint of the row's content the preview
 * showed ({@link ScriptResourceImportPreviewVo.Overwrite#getBasedOn()}): a ticked row whose content has changed since
 * is kept, so the job never overwrites what the sysadmin did not see.
 *
 * <p>Paths are compared as the template loader and {@link ScriptResourceService#findByPath} compare them:
 * case-insensitively. A file's path that differs from a row's only in case is that row's overwrite or unchanged, never
 * an add, which the database would refuse.
 */
@Service
public class ScriptResourceImportService {

    /** The job property naming the missing paths to add; absent, every missing path is added */
    public static final String ADD = "add";

    /** The job property naming the existing paths to overwrite; absent, none is */
    public static final String OVERWRITE = "overwrite";

    private final ScriptResourceService resourceService;

    private final MissingPathReport missingPathReport;

    ScriptResourceImportService(ScriptResourceService resourceService, MissingPathReport missingPathReport) {
        this.resourceService = resourceService;
        this.missingPathReport = missingPathReport;
    }

    /**
     * Returns what importing the resources would do. It only reads.
     *
     * @param resources the import file's resources, in its order
     * @return the paths it adds, would overwrite and leaves unchanged, and the files it refuses, each sorted by path
     */
    @Transactional(readOnly = true)
    public ScriptResourceImportPreviewVo preview(List<ScriptResourceVo> resources) {
        Map<String, ScriptResource> rows = new HashMap<>();
        resourceService.findAll().forEach(row -> rows.putIfAbsent(key(row.getPath()), row));
        Set<String> listed = missingPathReport.report().stream()
                .filter(entry -> entry.kind() == MissingPathReport.Kind.TEMPLATE)
                .map(MissingPathReport.Entry::path)
                .collect(Collectors.toSet());

        ScriptResourceImportPreviewVo preview = new ScriptResourceImportPreviewVo();
        Set<String> seen = new HashSet<>();
        for (ScriptResourceVo resource : resources) {
            List<String> reasons = refusal(resource, seen);
            if (!reasons.isEmpty()) {
                preview.getRefused().add(new ScriptResourceImportPreviewVo.Refused(
                        resource == null ? null : resource.getPath(), String.join("; ", reasons)));
                continue;
            }
            String path = resource.getPath();
            String content = contentOf(resource.getContent());
            ScriptResource row = rows.get(key(path));
            if (row == null) {
                preview.getAdd().add(new ScriptResourceImportPreviewVo.Add(
                        path, ScriptResource.path2type(path), content, listed.contains(path)));
            } else if (sameContent(row.getContent(), content)) {
                preview.getUnchanged().add(path);
            } else {
                preview.getOverwrite().add(new ScriptResourceImportPreviewVo.Overwrite(
                        path, ScriptResource.path2type(path), contentOf(row.getContent()), content,
                        fingerprint(row.getContent())));
            }
        }

        Comparator<String> byPath = Comparator.nullsFirst(Comparator.naturalOrder());
        preview.getAdd().sort(Comparator.comparing(ScriptResourceImportPreviewVo.Add::getPath, byPath));
        preview.getOverwrite().sort(Comparator.comparing(ScriptResourceImportPreviewVo.Overwrite::getPath, byPath));
        preview.getUnchanged().sort(byPath);
        preview.getRefused().sort(Comparator.comparing(ScriptResourceImportPreviewVo.Refused::getPath, byPath));
        return preview;
    }

    /**
     * Returns why the import refuses the resource: the reasons of {@link ScriptResourceCheck}, and a path that
     * appears earlier in the same import, compared case-insensitively. Records the path as seen.
     *
     * @param resource the resource
     * @param seen     the paths of the import seen so far; updated
     * @return the reasons, empty when the resource is accepted
     */
    public static List<String> refusal(ScriptResourceVo resource, Set<String> seen) {
        if (resource == null) {
            return List.of("the entry is not a script resource");
        }
        List<String> reasons = new ArrayList<>(
                ScriptResourceCheck.check(resource.getPath(), resource.getContent()));
        if (resource.getPath() != null && !seen.add(key(resource.getPath()))) {
            reasons.add("the path appears earlier in the file");
        }
        return reasons;
    }

    /** The content as compared and written: null counts as empty */
    public static String contentOf(String content) {
        return content == null ? "" : content;
    }

    /** A path as the import compares it with the rows': case-insensitively, as findByPath does */
    static String key(String path) {
        return path == null ? null : path.toLowerCase(Locale.ROOT);
    }

    /**
     * The fingerprint of a row's content, as the preview gives it ({@code basedOn}) and the job compares it
     *
     * @param content the content; null counts as empty
     * @return {@code sha256:<64 hex>} of its UTF-8 bytes
     */
    public static String fingerprint(String content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(contentOf(content).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads the {@value #OVERWRITE} job property or form field: a JSON array whose elements are a path, or
     * {@code {"path": ..., "basedOn": ...}} with the fingerprint of the row the preview showed
     *
     * @param name  the property's name, used in the message
     * @param value the property: a JSON array as a string, a collection, or null
     * @return each ticked path with its {@code basedOn}, null for a bare path; null when the property is absent
     * @throws IllegalArgumentException when it is not such an array
     */
    public static Map<String, String> ticks(String name, Object value) {
        if (value == null) {
            return null;
        }
        Map<String, String> ticks = new LinkedHashMap<>();
        for (Object item : items(name, value)) {
            if (item instanceof String path) {
                ticks.put(path, null);
            } else if (item instanceof Map<?, ?> tick && tick.get("path") instanceof String path
                    && (tick.get("basedOn") == null || tick.get("basedOn") instanceof String)
                    && Set.of("path", "basedOn").containsAll(tick.keySet())) {
                ticks.put(path, (String) tick.get("basedOn"));
            } else {
                throw new IllegalArgumentException("The field '" + name + "' holds " + item
                        + ", which is neither a path nor {\"path\", \"basedOn\"}");
            }
        }
        return ticks;
    }

    /**
     * Reads the {@value #ADD} or {@value #OVERWRITE} job property or form field
     *
     * @param name  the property's name, used in the message
     * @param value the property: a JSON array of paths as a string, a collection of paths, or null
     * @return the paths, or null when the property is absent
     * @throws IllegalArgumentException when it is not an array of paths
     */
    public static Set<String> paths(String name, Object value) {
        if (value == null) {
            return null;
        }
        Set<String> paths = new LinkedHashSet<>();
        for (Object item : items(name, value)) {
            if (!(item instanceof String path)) {
                throw new IllegalArgumentException("The field '" + name + "' holds " + item + ", which is not a path");
            }
            paths.add(path);
        }
        return paths;
    }

    /** The elements of a JSON array given as a string or a collection */
    private static Collection<?> items(String name, Object value) {
        Collection<?> items;
        if (value instanceof String json) {
            try {
                items = JsonUtils.fromJson(json, new TypeReference<List<Object>>() {});
            } catch (Exception e) {
                throw new IllegalArgumentException("The field '" + name + "' is not a JSON array of paths: "
                        + e.getMessage());
            }
            if (items == null) {
                throw new IllegalArgumentException("The field '" + name + "' is not a JSON array of paths");
            }
        } else if (value instanceof Collection<?> collection) {
            items = collection;
        } else {
            throw new IllegalArgumentException("The field '" + name + "' is not a JSON array of paths");
        }
        return items;
    }

    /** Whether the two contents are the same, as the import compares them */
    public static boolean sameContent(String a, String b) {
        return Objects.equals(contentOf(a), contentOf(b));
    }
}

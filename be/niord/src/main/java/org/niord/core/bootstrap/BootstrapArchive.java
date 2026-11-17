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
package org.niord.core.bootstrap;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.batch.vo.BatchSetVo;
import org.niord.core.script.ScriptResourceCheck;
import org.niord.core.script.ScriptResourceImportService;
import org.niord.core.util.JsonUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * One bootstrap archive, read into memory and checked before anything is written (docs/code-data-config-plan.md
 * §6.5, §7.2): a zip in the batch-set format, whose {@code batch-set.json} names the import jobs and their data
 * files.
 *
 * <p>The rules: at most {@value #MAX_ENTRIES} entries, {@value #MAX_ENTRY_BYTES} bytes an entry and
 * {@value #MAX_TOTAL_BYTES} bytes in all, counted while reading and never taken from the entry headers; each name
 * once; only the layout {@code batch-set.json}, {@code README.md}, root-level {@code *.json},
 * {@code templates/**}{@code /*.ftl} or {@code *.js}, and {@code releases/<folder>/*.json}, {@code *.md} or
 * {@code *.sql}, with plain name segments (no {@code ..}, no absolute path, no backslash). Directory entries inside
 * {@code templates/} and {@code releases/}, which the JDK's {@code jar} tool and the Maven assembly write for a
 * zipped folder, are skipped. Nothing is extracted to disk, so an entry stored as a symbolic link is only ever read
 * as the bytes of a data file. Every file of {@code templates/} must pass the script check
 * ({@link ScriptResourceCheck}, the check the Script resources import runs), every job of {@code batch-set.json}
 * must be one the runner accepts ({@link BootstrapRunner#ACCEPTED_JOBS}), and every data file it names must be in the
 * archive. An item's {@code properties} may carry what a job reads from a batch set, but not {@value #ADD} or
 * {@value #OVERWRITE}: those are a sysadmin's ticks after a preview (the Script resources and Dictionaries imports),
 * and the bootstrap adds what is missing and overwrites nothing outside the first bootstrap's rule, where a later
 * archive's values replace what an earlier archive of the run created ({@link BootstrapRun}). Nor may it carry
 * {@value #RUN}, the runner's own token for that rule.
 *
 * <p>The byte limits are this class's; the server's multipart limit comes first. niord-base's defaults set
 * {@code spring.servlet.multipart.max-file-size} and {@code max-request-size} to 50 MB (the chart's proxy allows the
 * same), and a larger upload is answered 413 before the bootstrap command reads it. So the {@value #MAX_ENTRY_BYTES}
 * bytes here apply as they are, and the {@value #MAX_TOTAL_BYTES} bytes only where a deployment has raised those.
 *
 * <p>The release folders ({@code releases/}, plan §6.4) and a root-level file {@code batch-set.json} does not name,
 * such as the base product texts' {@code texts.json} (§4.6), travel with the published archive for a sysadmin's
 * import: they are counted against the limits and hashed with the archive, and never applied ({@link #items()}
 * lists only {@code batch-set.json}'s jobs, {@link #templates()} only {@code templates/}).
 */
public final class BootstrapArchive {

    public static final int MAX_ENTRIES = 2_000;
    public static final int MAX_ENTRY_BYTES = 16 * 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 64 * 1024 * 1024;

    public static final String BATCH_SET = "batch-set.json";
    public static final String README = "README.md";
    /** The folder of templates and scripts; the script-resource job's {@code fileName} names it */
    public static final String TEMPLATES = "templates";
    /** The folder of release import files, one folder a release; read and hashed, never applied */
    public static final String RELEASES = "releases";

    /** The properties a batch-set item of an archive may not carry: the import previews' ticks */
    static final String ADD = ScriptResourceImportService.ADD;
    static final String OVERWRITE = ScriptResourceImportService.OVERWRITE;

    /** The property the runner gives every job of a run ({@link BootstrapRun}); an item may not carry it */
    static final String RUN = BootstrapRun.PROPERTY;

    private static final String SEGMENT = "[A-Za-z0-9_][A-Za-z0-9._-]*";
    private static final Pattern FILE = Pattern.compile(
            "batch-set\\.json|README\\.md|" + SEGMENT + "\\.json|templates/(" + SEGMENT + "/)*" + SEGMENT
                    + "\\.(ftl|js)|" + RELEASES + "/" + SEGMENT + "/" + SEGMENT + "\\.(json|md|sql)");
    private static final Pattern FOLDER = Pattern.compile(
            "templates/(" + SEGMENT + "/)*|" + RELEASES + "/(" + SEGMENT + "/)?");

    /** The job whose data files {@link #domainIds()} reads */
    static final String DOMAIN_JOB = "domain-import";

    /**
     * The domain import's own defaults ({@code JsonUtils.readJson}), so {@link #domainIds()} reads a file as the import
     * reads it, content after the root included
     */
    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    private final String name;
    private final String hash;
    private final Map<String, byte[]> files;
    private final List<BatchSetVo> items;

    private BootstrapArchive(String name, String hash, Map<String, byte[]> files, List<BatchSetVo> items) {
        this.name = name;
        this.hash = hash;
        this.files = files;
        this.items = items;
    }

    /**
     * Reads and checks an archive file
     *
     * @param zip the archive
     * @return the archive
     * @throws BootstrapRefusedException when it breaks a rule
     */
    public static BootstrapArchive read(Path zip) throws IOException {
        try (InputStream in = Files.newInputStream(zip)) {
            return read(zip.getFileName().toString(), in);
        }
    }

    /**
     * Reads and checks an archive
     *
     * @param name the archive's name, used in messages
     * @param in   the archive's bytes; read to the end, not closed
     * @return the archive
     * @throws BootstrapRefusedException when it breaks a rule
     */
    public static BootstrapArchive read(String name, InputStream in) throws IOException {
        byte[] raw = in.readNBytes(MAX_TOTAL_BYTES + 1);
        if (raw.length > MAX_TOTAL_BYTES) {
            throw refused(name, "is larger than " + MAX_TOTAL_BYTES + " bytes");
        }

        Map<String, byte[]> files = new LinkedHashMap<>();
        int entries = 0;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(raw), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw refused(name, "has more than " + MAX_ENTRIES + " entries");
                }
                String entryName = entry.getName();
                if (entry.isDirectory()) {
                    if (!FOLDER.matcher(entryName).matches()) {
                        throw refused(name, "has the folder '" + entryName + "' outside templates/ and releases/");
                    }
                    continue;
                }
                if (!FILE.matcher(entryName).matches()) {
                    throw refused(name, "has the entry '" + entryName + "' outside the layout batch-set.json, "
                            + "README.md, *.json, templates/**/*.ftl or *.js and releases/<folder>/*.json, *.md or "
                            + "*.sql");
                }
                if (files.containsKey(entryName)) {
                    throw refused(name, "has the entry '" + entryName + "' twice");
                }
                byte[] content = readEntry(zip, name, entryName, MAX_TOTAL_BYTES - total);
                total += content.length;
                files.put(entryName, content);
            }
        } catch (ZipException e) {
            throw refused(name, "is not a readable zip archive: " + e.getMessage());
        }
        if (entries == 0) {
            throw refused(name, "is empty or not a zip archive");
        }

        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (file.getKey().startsWith(TEMPLATES + "/")) {
                List<String> reasons = ScriptResourceCheck.check(file.getKey(),
                        new String(file.getValue(), StandardCharsets.UTF_8));
                if (!reasons.isEmpty()) {
                    throw refused(name, "has the template '" + file.getKey()
                            + "', which the script check refuses: " + String.join("; ", reasons));
                }
            }
        }

        byte[] batchSet = files.get(BATCH_SET);
        if (batchSet == null) {
            throw refused(name, "has no " + BATCH_SET);
        }
        List<BatchSetVo> items;
        try {
            items = JsonUtils.fromJson(new String(batchSet, StandardCharsets.UTF_8),
                    new TypeReference<List<BatchSetVo>>() {});
        } catch (Exception e) {
            throw refused(name, "has a " + BATCH_SET + " that is not a list of jobs: " + e.getMessage());
        }
        if (items == null || items.isEmpty()) {
            throw refused(name, "names no job in " + BATCH_SET);
        }
        for (BatchSetVo item : items) {
            checkItem(name, item, files);
        }
        return new BootstrapArchive(name, sha256(raw), Collections.unmodifiableMap(files), List.copyOf(items));
    }

    /** Reads one entry, counting its bytes against the entry limit and what is left of the total */
    private static byte[] readEntry(ZipInputStream zip, String name, String entryName, long totalLeft)
            throws IOException {
        long limit = Math.min(MAX_ENTRY_BYTES, totalLeft);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long read = 0;
        int n;
        while ((n = zip.read(buffer)) > 0) {
            read += n;
            if (read > limit) {
                throw refused(name, read > MAX_ENTRY_BYTES
                        ? "has the entry '" + entryName + "' larger than " + MAX_ENTRY_BYTES + " bytes"
                        : "unpacks to more than " + MAX_TOTAL_BYTES + " bytes");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Checks that the item's job is accepted, carries no import tick and not the runner's token, and its data file is
     * in the archive
     */
    private static void checkItem(String name, BatchSetVo item, Map<String, byte[]> files) {
        String job = item == null ? null : item.getJobName();
        if (StringUtils.isBlank(job)) {
            throw refused(name, "has a job without a name in " + BATCH_SET);
        }
        if (!BootstrapRunner.ACCEPTED_JOBS.contains(job)) {
            throw refused(name, "names the job '" + job + "', which the bootstrap does not run (it runs "
                    + String.join(", ", BootstrapRunner.ACCEPTED_JOBS) + ")");
        }
        if (item.getProperties() != null) {
            for (String tick : List.of(ADD, OVERWRITE)) {
                if (item.getProperties().containsKey(tick)) {
                    throw refused(name, "gives the job '" + job + "' (" + item.getFileName() + ") the property '"
                            + tick + "' in " + BATCH_SET + ": a sysadmin's tick after a preview, never the "
                            + "bootstrap's, which adds what is missing and overwrites nothing");
                }
            }
            if (item.getProperties().containsKey(RUN)) {
                throw refused(name, "gives the job '" + job + "' (" + item.getFileName() + ") the property '" + RUN
                        + "' in " + BATCH_SET + ": the bootstrap runner's own token for the run, never an archive's");
            }
        }
        String fileName = item.getFileName();
        if (BootstrapRunner.SCRIPT_RESOURCE_JOB.equals(job)) {
            if (!TEMPLATES.equals(fileName)) {
                throw refused(name, "gives the job '" + job + "' the file '" + fileName + "'; it reads the folder '"
                        + TEMPLATES + "'");
            }
            if (files.keySet().stream().noneMatch(f -> f.startsWith(TEMPLATES + "/"))) {
                throw refused(name, "names the folder '" + TEMPLATES + "' for the job '" + job
                        + "', and has no template in it");
            }
            return;
        }
        if (StringUtils.isBlank(fileName) || fileName.contains("/") || !fileName.endsWith(".json")) {
            throw refused(name, "gives the job '" + job + "' the file '" + fileName
                    + "'; a job reads a root-level .json file");
        }
        if (!files.containsKey(fileName)) {
            throw refused(name, "names the file '" + fileName + "' for the job '" + job + "', and does not have it");
        }
    }

    private static BootstrapRefusedException refused(String name, String rule) {
        return new BootstrapRefusedException("The archive '" + name + "' " + rule, BootstrapRefusedException.ARCHIVE);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns the SHA-256 over the hashes of the archives, in order: the hash a run records in the marker
     *
     * @param archives the run's archives
     * @return the run's hash
     */
    public static String runHash(List<BootstrapArchive> archives) {
        StringBuilder hashes = new StringBuilder();
        archives.forEach(a -> hashes.append(a.hash()).append('\n'));
        return sha256(hashes.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the archive's name */
    public String name() {
        return name;
    }

    /** Returns the SHA-256 of the archive's bytes */
    public String hash() {
        return hash;
    }

    /** Returns the jobs of {@code batch-set.json}, in its order */
    public List<BatchSetVo> items() {
        return items;
    }

    /**
     * Returns a root-level data file
     *
     * @param fileName the file name
     * @return its bytes, or null
     */
    public byte[] file(String fileName) {
        return files.get(fileName);
    }

    /**
     * Returns the domainId of every domain the archive's domain import gives: each object's {@code domainId} in the
     * data file of each {@value #DOMAIN_JOB} item, in file order. Read leniently: a file that is not a JSON list of
     * objects gives none, and the domain import fails on it when the run gets there.
     *
     * @return the domainIds
     */
    public Set<String> domainIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (BatchSetVo item : items) {
            byte[] data = DOMAIN_JOB.equals(item.getJobName()) ? files.get(item.getFileName()) : null;
            if (data == null) {
                continue;
            }
            JsonNode root;
            try {
                root = MAPPER.readTree(data);
            } catch (JacksonException e) {
                continue;
            }
            if (root == null || !root.isArray()) {
                continue;
            }
            for (JsonNode domain : root) {
                JsonNode id = domain.path("domainId");
                if (id.isString() && StringUtils.isNotBlank(id.asString())) {
                    ids.add(id.asString());
                }
            }
        }
        return ids;
    }

    /**
     * Returns the domainId of every domain the archive restates with its active flag only: each domainId whose every
     * object in the data files of the {@value #DOMAIN_JOB} items has exactly the two fields {@code domainId} and
     * {@code active}, {@code active} a JSON boolean (not null, not a string). These are the restatements of an earlier
     * archive's domain the run accepts ({@link BootstrapRunner#refuseARestatedDomain}), so that a country may leave the
     * base product's master domain inactive (the owner's ruling of 2026-11-03). Read as leniently as
     * {@link #domainIds()}: a file that is not a JSON list of objects gives none.
     *
     * @return the domainIds restated with their active flag only
     */
    public Set<String> activeOnlyDomainIds() {
        Set<String> activeOnly = new LinkedHashSet<>();
        Set<String> other = new LinkedHashSet<>();
        for (BatchSetVo item : items) {
            byte[] data = DOMAIN_JOB.equals(item.getJobName()) ? files.get(item.getFileName()) : null;
            if (data == null) {
                continue;
            }
            JsonNode root;
            try {
                root = MAPPER.readTree(data);
            } catch (JacksonException e) {
                continue;
            }
            if (root == null || !root.isArray()) {
                continue;
            }
            for (JsonNode domain : root) {
                JsonNode id = domain.path("domainId");
                if (!id.isString() || StringUtils.isBlank(id.asString())) {
                    continue;
                }
                boolean onlyActive = domain.isObject() && domain.size() == 2 && domain.path("active").isBoolean();
                (onlyActive ? activeOnly : other).add(id.asString());
            }
        }
        activeOnly.removeAll(other);
        return activeOnly;
    }

    /** Returns the files under {@code templates/}, by their path in the archive ({@code templates/...}), sorted */
    public Map<String, byte[]> templates() {
        Map<String, byte[]> templates = new TreeMap<>();
        files.forEach((path, content) -> {
            if (path.startsWith(TEMPLATES + "/")) {
                templates.put(path, content);
            }
        });
        return templates;
    }
}

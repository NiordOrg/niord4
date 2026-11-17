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
package org.niord.web;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.niord.core.aton.vo.AtonNodeVo;
import org.niord.core.batch.AbstractBatchableRestService;
import org.niord.core.dictionary.DictionaryEntry;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.dictionary.vo.DictionaryEntryVo;
import org.niord.core.dictionary.vo.DictionaryImportPreviewVo;
import org.niord.core.dictionary.vo.DictionaryKeyUsersVo;
import org.niord.core.dictionary.vo.DictionaryVo;
import org.niord.core.dictionary.vo.ExportedDictionaryVo;
import org.niord.core.user.Roles;
import org.niord.core.util.JsonUtils;
import org.niord.core.util.WebUtils;
import org.niord.model.DataFilter;
import org.niord.model.IJsonSerializable;
import org.slf4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import tools.jackson.core.type.TypeReference;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * REST interface for accessing charts.
 */
@RestController
@RequestMapping("/rest/dictionaries")
@Transactional
@PermitAll
public class DictionaryRestService extends AbstractBatchableRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DictionaryRestService.class);

    private final DictionaryService dictionaryService;

    DictionaryRestService(DictionaryService dictionaryService) {
        this.dictionaryService = dictionaryService;
    }

    /** Returns the dictionaries with the given name */
    @GetMapping(value = "/names", produces = "application/json;charset=UTF-8")
    public List<String> getDictionaryNames() {
        return dictionaryService.getDictionaryNames();
    }

    /** Returns the dictionaries with the given name */
    // The {name} is constrained to exclude '.' so this generic mapping does not shadow the more-literal
    // export mappings (/dictionary/{name}_{lang}.properties, /dictionary/{names}.json): Spring's
    // PathPattern ranks a pattern with fewer captured variables as MORE specific, so without this
    // constraint "/dictionary/web_en.properties" would match this 1-variable mapping (name="web_en.properties")
    // instead of the 2-variable .properties export — JAX-RS preferred the more-literal pattern.
    @GetMapping(value = "/dictionary/{name:[^.]+}", produces = "application/json;charset=UTF-8")
    public DictionaryVo getDictionary(@PathVariable("name") String name) {
        return dictionaryService.getCachedDictionary(name);
    }

    /**
     * Returns the dictionary entries with the given name. A translation whose value still equals what start-up
     * added or adopted carries {@code asAdded: true}, so the administration app lets its own text win
     * (docs/code-data-config-plan.md §4.5).
     */
    @GetMapping(value = "/dictionary/{name}/entries", produces = "application/json;charset=UTF-8")
    public List<DictionaryEntryVo> getDictionaryEntries(
            @PathVariable("name") String name,
            @RequestParam(name = "lang", required = false) String lang) {
        DictionaryVo dict = dictionaryService.getCachedDictionary(name);
        if (dict == null) {
            throw new NiordWebException("No such dictionary: " + name, 404);
        }
        List<DictionaryEntryVo> entries = dict
                .getEntries()
                .values()
                .stream()
                .sorted(Comparator.comparing(e -> e.getKey().toLowerCase()))
                .collect(Collectors.toList());

        if (StringUtils.isNotBlank(lang)) {
            entries.forEach(e -> e.sortDescs(lang));
        }
        return entries;
    }

    /** Exports the dictionary as a text file */
    @GetMapping(value = "/dictionary/{name}_{lang}.properties", produces = "text/plain;charset=UTF-8")
    public String getDictionaryEntriesAsPropertyFile(@PathVariable("name") String name, @PathVariable("lang") String lang) {
        DictionaryVo dict = dictionaryService.getCachedDictionary(name);
        if (dict == null) {
            throw new NiordWebException(404);
        }

        StringBuilder result = new StringBuilder();
        dict.getEntries()
                .values()
                .stream()
                .filter(e -> e.getDesc(lang) != null && e.getDesc(lang).descDefined())
                .sorted(Comparator.comparing(e2 -> e2.getKey().toLowerCase()))
                .forEach(entry ->
                        result.append(entry.getKey())
                        .append(" = ")
                        .append(encodeValue(entry.getDesc(lang).getValue()))
                        .append("\n"));

        return result.toString();
    }

    /** Exports the dictionaries with the comma-separated names as a JSON file */
    @GetMapping(value = "/dictionary/{names}.json", produces = "application/json;charset=UTF-8")
    public List<ExportedDictionaryVo> exportDictionary(@PathVariable("names") String names) {

        return Arrays.stream(names.split(","))
                .map(name -> dictionaryService.getCachedDictionary(name))
                .filter(Objects::nonNull)
                .map(ExportedDictionaryVo::new)
                .collect(Collectors.toList());
    }

    /** Encodes the value as a property file value **/
    private String encodeValue(String value) {
        return StringEscapeUtils.escapeEcmaScript(value);
    }

    /** Creates a new dictionary entry */
    @PostMapping(value = "/dictionary/{name}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public DictionaryEntryVo createDictionaryEntry(@PathVariable("name") String name, @RequestBody DictionaryEntryVo entryVo) throws Exception {
        DictionaryEntry entry = new DictionaryEntry(entryVo);
        log.info("Creating dictionary entry " + entryVo);
        return dictionaryService.createEntry(name, entry).toVo(DataFilter.get());
    }

    /** Updates a dictionary entry */
    @PutMapping(value = "/dictionary/{name}/{key}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public DictionaryEntryVo updateDictionaryEntry(@PathVariable("name") String name, @PathVariable("key") String key, @RequestBody DictionaryEntryVo entryVo) throws Exception {
        if (!Objects.equals(key, entryVo.getKey())) {
            throw new NiordWebException(400);
        }
        DictionaryEntry entry = new DictionaryEntry(entryVo);
        log.info("Updating dictionary entry " + entry);
        return dictionaryService.updateEntry(name, entry).toVo(DataFilter.get());
    }

    /** Deletes the given dictionary entry; 409 naming the list parameter types whose values hold it */
    @DeleteMapping("/dictionary/{name}/{key}")
    @RolesAllowed(Roles.SYSADMIN)
    public boolean deleteDictionaryEntry(@PathVariable("name") String name, @PathVariable("key") String key) throws Exception {
        log.info("Deleting dictionary entry " + key);
        return dictionaryService.deleteEntry(name, key);
    }

    /**
     * Returns what uses the key, which the Dictionaries page's delete confirmation shows: the script resources that
     * name the key or its runtime prefix, and the Java code that reads it from this dictionary. The scan is by key,
     * so it answers for any dictionary name. The key is a query parameter, so any character in it survives.
     */
    @GetMapping(value = "/dictionary/{name}/key-users", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public DictionaryKeyUsersVo getKeyUsers(@PathVariable("name") String name, @RequestParam("key") String key) {
        return dictionaryService.keyUsers(name, key);
    }

    /**
     * Returns what importing the uploaded dictionary json file would do (docs/code-data-config-plan.md §4.6): the
     * keys it adds, marking those an operator deleted, the keys it would overwrite with the row's values and the
     * file's, how many keys it leaves unchanged, and the entries it refuses with their reasons. It only reads.
     * <p>
     * Sysadmin only, like the import itself.
     *
     * @param request the multi-part form data request, with the file as the upload sends it
     * @return what the import would do
     */
    @PostMapping(value = "/import-preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    @Transactional(readOnly = true)
    public DictionaryImportPreviewVo previewDictionaryImport(MultipartHttpServletRequest request) throws Exception {
        Map<String, InputStream> files = WebUtils.getMultipartFiles(request);
        if (files.size() != 1) {
            throw new NiordWebException("Upload one dictionary file; the request has " + files.size(), 400);
        }
        Map.Entry<String, InputStream> file = files.entrySet().iterator().next();
        List<ExportedDictionaryVo> dictionaries;
        try (InputStream in = file.getValue()) {
            dictionaries = JsonUtils.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<List<ExportedDictionaryVo>>() {});
        } catch (Exception e) {
            throw new NiordWebException("The file " + file.getKey() + " is not a list of dictionaries: "
                    + e.getMessage(), 400);
        }
        if (dictionaries == null) {
            throw new NiordWebException("The file " + file.getKey() + " is not a list of dictionaries", 400);
        }
        return dictionaryService.previewImport(dictionaries);
    }

    /**
     * Imports an uploaded dictionary json file (docs/code-data-config-plan.md §4.6).
     * <p>
     * Sysadmin only, like the entry create/update/delete endpoints above: dictionaries are shared by every domain.
     * The sysadmin sees the preview first ({@code /import-preview}); the import then adds the missing keys, but not
     * one the defaults ledger records as deleted unless the sysadmin ticked it, and overwrites an existing key only
     * when the sysadmin ticked it. Two optional text fields carry the ticks, each a JSON array of
     * {@code {"dictionary": ..., "key": ...}}: {@code add}, the missing keys to add (absent: every missing key not
     * recorded as deleted), and {@code overwrite}, the existing keys to overwrite (absent: none), each with the
     * preview's {@code basedOn}, which keeps a key changed since the preview. An entry the import refuses is skipped,
     * and the job log names its reasons.
     *
     * @param request the multi-part form data request
     * @return a status
     */
    @PostMapping(value = "/upload-dictionaries", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "text/plain")
    @RolesAllowed(Roles.SYSADMIN)
    public String importDictionaries(MultipartHttpServletRequest request) throws Exception {
        // The ticks are checked before any job starts; the job reads them from its properties
        Map<String, Object> fields = WebUtils.getMultipartFormParams(request);
        for (String field : List.of(DictionaryService.IMPORT_ADD, DictionaryService.IMPORT_OVERWRITE)) {
            Object value = fields.get(field);
            if (value != null && !(value instanceof String)) {
                throw new NiordWebException("The field '" + field + "' is given more than once", 400);
            }
            try {
                DictionaryService.importKeys(field, value);
            } catch (IllegalArgumentException e) {
                throw new NiordWebException(e.getMessage(), 400);
            }
        }
        return executeBatchJobFromUploadedFile(request, "dictionary-import");
    }

    /**
     * *******************************************
     * AtoN matching
     * *******************************************
     */

    /**
     * Matches an AtoN against a list of dictionary entries. The entries' AtoN filters come from the request
     * body, so they are checked against the AtoN filter grammar before they are run (see AtonFilter), and
     * the endpoint is for editors, the role of the message editor that selects list values from an AtoN.
     */
    @PutMapping(value = "/matches-aton",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public DictionaryEntryVo matchesAton(@RequestBody AtonDictEntryListVo params) throws Exception {

        if (params.getAton() == null || params.getValues() == null) {
            throw new NiordWebException("No proper message specified", 400);
        }

        if (params.getValues().stream().noneMatch(d -> StringUtils.isNotBlank(d.getAtonFilter()))) {
            return null;
        }

        return dictionaryService.matchesAton(params.getAton(), params.getValues());
    }

    /**
     * ******************
     * Helper classes
     * *******************
     */

    /** Encapsulates the parameters used for matching an AtoN against a list of dictionary entries */
    @SuppressWarnings("unused")
    public static class AtonDictEntryListVo implements IJsonSerializable {
        AtonNodeVo aton;
        List<DictionaryEntryVo> values;

        public AtonNodeVo getAton() {
            return aton;
        }

        public void setAton(AtonNodeVo aton) {
            this.aton = aton;
        }

        public List<DictionaryEntryVo> getValues() {
            return values;
        }

        public void setValues(List<DictionaryEntryVo> values) {
            this.values = values;
        }
    }
}

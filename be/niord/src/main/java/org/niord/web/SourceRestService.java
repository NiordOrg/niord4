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

import org.niord.core.NiordApp;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.niord.core.batch.AbstractBatchableRestService;
import org.niord.core.source.Source;
import org.niord.core.source.SourceService;
import org.niord.core.source.vo.SourceVo;
import org.niord.core.user.Roles;
import org.niord.core.util.TextUtils;
import org.niord.model.DataFilter;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import org.apache.commons.lang3.StringUtils;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST interface for accessing sources.
 */
@RestController
@RequestMapping("/rest/sources")
@Transactional
@PermitAll
public class SourceRestService extends AbstractBatchableRestService {

    private final NiordApp app;

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SourceRestService.class);

    private final SourceService sourceService;

    SourceRestService(NiordApp app, SourceService sourceService) {
        this.app = app;
        this.sourceService = sourceService;
    }

    /** Searches sources based on the given search parameters */
    @GetMapping(value = "/search", produces = "application/json;charset=UTF-8")
    public List<SourceVo> searchSources(
            @RequestParam(name = "lang", required = false) String lang,
            @RequestParam(name = "name", defaultValue = "") String name,
            @RequestParam(name = "inactive", defaultValue = "false") boolean inactive,
            @RequestParam(name = "limit", defaultValue = "1000") int limit) {

        DataFilter dataFilter = DataFilter.get().defaultLanguage(app.getDefaultLanguage()).lang(lang);

        return sourceService.searchSources(lang, name, inactive, limit).stream()
                .map(p -> p.toVo(dataFilter))
                .sorted(sourceNameComparator(lang))
                .collect(Collectors.toList());
    }

    /** Searches sources based on comma-separated ID's */
    @GetMapping(value = "/search/{ids}", produces = "application/json;charset=UTF-8")
    public List<SourceVo> searchSourcesById(
            @PathVariable("ids") String ids) {

        Set<Integer> idSet = Arrays.stream(ids.split(","))
                .map(Integer::valueOf)
                .collect(Collectors.toSet());

        return sourceService.findByIds(idSet).stream()
                .map(p -> p.toVo(DataFilter.get().defaultLanguage(app.getDefaultLanguage())))
                .collect(Collectors.toList());
    }

    /** Returns all sources up to the given limit */
    @GetMapping(value = "/all", produces = "application/json;charset=UTF-8")
    public List<SourceVo> getAllSources(
            @RequestParam(name = "lang", required = false) String lang,
            @RequestParam(name = "limit", defaultValue = "1000") int limit) {
        DataFilter dataFilter = DataFilter.get().defaultLanguage(app.getDefaultLanguage()).lang(lang);
        return sourceService.getSources().stream()
                .limit(limit)
                .map(p -> p.toVo(dataFilter))
                .sorted(sourceNameComparator(lang))
                .collect(Collectors.toList());
    }

    /** Returns the source with the given ID */
    @GetMapping(value = "/source/{id}", produces = "application/json;charset=UTF-8")
    public SourceVo getSource(@PathVariable("id") Integer id) throws Exception {
        Source source = sourceService.findById(id);
        if (source == null) {
            throw new NiordWebException("No such source: " + id, 404);
        }
        return source.toVo(DataFilter.get().defaultLanguage(app.getDefaultLanguage()));
    }

    /** Creates a new source */
    @PostMapping(value = {"/source", "/source/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public SourceVo createSource(@RequestBody SourceVo source) throws Exception {
        // Here, not in SourceService.createSource, which the source import shares and whose behaviour stays as it is
        if (source.getDescs() == null
                || source.getDescs().stream().noneMatch(d -> d != null && StringUtils.isNotBlank(d.getName()))) {
            throw new NiordWebException("A source needs a name in at least one language", 400);
        }
        log.info("Creating source " + source);
        return sourceService.createSource(new Source(source))
                .toVo(DataFilter.get().defaultLanguage(app.getDefaultLanguage()));
    }

    /** Updates an existing source */
    @PutMapping(value = "/source/{id}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public SourceVo updateSource(
            @PathVariable("id") Integer id,
            @RequestBody SourceVo source) throws Exception {

        if (!Objects.equals(id, source.getId())) {
            throw new NiordWebException(400);
        }

        log.info("Updating source " + source);
        return sourceService.updateSource(new Source(source))
                .toVo(DataFilter.get().defaultLanguage(app.getDefaultLanguage()));
    }

    /** Deletes an existing source */
    @DeleteMapping("/source/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RolesAllowed(Roles.ADMIN)
    public void deleteSource(@PathVariable("id") Integer id) throws Exception {
        log.info("Deleting source " + id);
        sourceService.deleteSource(id);
    }

    /**
     * Imports an uploaded Sources json file
     *
     * @param request the multi-part form data request
     * @return a status
     */
    @PostMapping(value = "/upload-sources", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "text/plain")
    @RolesAllowed(Roles.ADMIN)
    public String importSources(MultipartHttpServletRequest request) throws Exception {
        return executeBatchJobFromUploadedFile(request, "source-import");
    }

    /** Returns a source name comparator **/
    private Comparator<SourceVo> sourceNameComparator(String lang) {
        return (s1, s2) -> {
            String n1 = s1.getDesc(lang) != null ? s1.getDesc(lang).getName() : null;
            String n2 = s2.getDesc(lang) != null ? s2.getDesc(lang).getName() : null;
            return TextUtils.compareIgnoreCase(n1, n2);
        };
    }

}

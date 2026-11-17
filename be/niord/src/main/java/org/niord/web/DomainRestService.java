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

import org.niord.core.batch.AbstractBatchableRestService;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.domain.vo.DomainVo;
import org.niord.core.user.Roles;
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

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * REST interface for accessing domains.
 */
@RestController
@RequestMapping("/rest/domains")
@Transactional
@PermitAll
public class DomainRestService extends AbstractBatchableRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DomainRestService.class);

    private final DomainService domainService;

    DomainRestService(DomainService domainService) {
        this.domainService = domainService;
    }

    /** Returns all domains */
    @GetMapping(value = "/all", produces = "application/json;charset=UTF-8")
    public List<DomainVo> getAllDomains(
            @RequestParam(name = "lang", required = false) String lang,
            @RequestParam(name = "inactive", defaultValue = "false") boolean includeInactive,
            @RequestParam(name = "keycloakState", defaultValue = "true") boolean keycloakState) {

        // Load the domains including their keycloak state, if requested
        List<DomainVo> domains = domainService.getDomains(includeInactive, keycloakState ).stream()
                .map(Domain::toVo)
                .collect(Collectors.toList());

        // Sort areas and categories by language
        domains.forEach(d -> {
            if (d.getAreas() != null) {
                d.getAreas().forEach(a -> a.sortDescs(lang));
            }
            if (d.getCategories() != null) {
                d.getCategories().forEach(c -> c.sortDescs(lang));
            }
        });

        return domains;
    }

    /** Creates a new domain */
    @PostMapping(value = {"/domain", "/domain/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public DomainVo createDomain(@RequestBody DomainVo domain) throws Exception {
        log.info("Creating domain " + domain);
        return domainService.createDomain(new Domain(domain), true).toVo();
    }

    /** Updates an existing domain */
    @PutMapping(value = "/domain/{domainId}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public DomainVo updateDomain(@PathVariable("domainId") String domainId, @RequestBody DomainVo domain) throws Exception {
        if (!Objects.equals(domainId, domain.getDomainId())) {
            throw new NiordWebException(400);
        }

        log.info("Updating domain " + domain);
        return domainService.updateDomain(new Domain(domain)).toVo();
    }

    /** Deletes an existing domain */
    @DeleteMapping("/domain/{domainId}")
    @RolesAllowed(Roles.SYSADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDomain(@PathVariable("domainId") String domainId) throws Exception {
        log.info("Deleting domain " + domainId);
        domainService.deleteDomain(domainId);
    }

    /** Creates the domain in Keycloak */
    @PostMapping(value = "/keycloak", consumes = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void createDomainInKeycloak(@RequestBody DomainVo domain) throws Exception {
        log.info("Creating Keycloak domain " + domain);
        domainService.createDomainInKeycloak(new Domain(domain));
    }

    /**
     * Imports an uploaded domains json file
     *
     * @param request the multi-part form data request
     * @return a status
     */
    @PostMapping(value = "/upload-domains",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "text/plain")
    @RolesAllowed(Roles.SYSADMIN)
    public String importDomains(MultipartHttpServletRequest request) throws Exception {
        return executeBatchJobFromUploadedFile(request, "domain-import");
    }

}

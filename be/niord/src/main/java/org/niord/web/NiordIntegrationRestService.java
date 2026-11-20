/*
 * Copyright 2017 Danish Maritime Authority.
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

import org.niord.core.integration.NiordIntegration;
import org.niord.core.integration.NiordIntegrationService;
import org.niord.core.integration.vo.NiordIntegrationVo;
import org.niord.core.user.Roles;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.RolesAllowed;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * REST interface for accessing and managing Niord integration points.
 * <p>
 * Sysadmin only, like the admin application's page for it. An integration imports another Niord
 * server's messages into the message series it maps to, and it names those series by id alone, in
 * any domain; the ADMIN role is granted per domain, so it cannot be what guards a cross-domain import.
 */
@RestController
@RequestMapping("/rest/niord-integrations")
@Transactional
@RolesAllowed(Roles.SYSADMIN)
@SuppressWarnings("unused")
public class NiordIntegrationRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NiordIntegrationRestService.class);

    private final NiordIntegrationService niordIntegrationService;

    NiordIntegrationRestService(NiordIntegrationService niordIntegrationService) {
        this.niordIntegrationService = niordIntegrationService;
    }

    /** Returns all Niord integration points */
    @GetMapping(value = "/all", produces = "application/json;charset=UTF-8")
    public List<NiordIntegrationVo> getAllNiordIntegrations() {
        return niordIntegrationService.getAllNiordIntegrations().stream()
                .map(NiordIntegration::toVo)
                .collect(Collectors.toList());
    }

    /** Returns the Niord integration point with the given ID */
    @GetMapping(value = "/niord-integration/{id}", produces = "application/json;charset=UTF-8")
    public NiordIntegrationVo getNiordIntegration(@PathVariable("id") Integer id) {
        NiordIntegration integration = niordIntegrationService.findById(id);
        if (integration == null) {
            throw new NiordWebException(404);
        }
        return integration.toVo();
    }

    /** Creates a new Niord integration point */
    @PostMapping(value = {"/niord-integration", "/niord-integration/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public NiordIntegrationVo createNiordIntegration(@RequestBody NiordIntegrationVo integration) throws Exception {
        // Not the address: it is checked by the service, which logs it once it has passed (a credential is refused)
        log.info("Creating Niord integration point");
        return niordIntegrationService
                .createNiordIntegration(new NiordIntegration(integration))
                .toVo();
    }

    /** Updates an existing Niord integration point */
    @PutMapping(value = "/niord-integration/{id}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public NiordIntegrationVo updateNiordIntegration(@PathVariable("id") Integer id, @RequestBody NiordIntegrationVo integration) throws Exception {
        if (!Objects.equals(id, integration.getId())) {
            throw new NiordWebException(400);
        }

        log.info("Updating Niord integration point " + integration.getId());
        return niordIntegrationService
                .updateNiordIntegration(new NiordIntegration(integration))
                .toVo();
    }

    /** Deletes an existing Niord integration point */
    @DeleteMapping("/niord-integration/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNiordIntegration(@PathVariable("id") Integer id) throws Exception {
        log.info("Deleting Niord integration point " + id);
        niordIntegrationService.deleteNiordIntegration(id);
    }

    /** Updates an existing Niord integration point */
    @PutMapping("/execute/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void executeNiordIntegration(@PathVariable("id") Integer id) throws Exception {
        log.info("Executing Niord integration point " + id);
        niordIntegrationService.processNiordIntegration(id);
    }

}

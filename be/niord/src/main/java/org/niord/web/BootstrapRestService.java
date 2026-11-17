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

import jakarta.annotation.security.RolesAllowed;
import org.niord.core.bootstrap.BootstrapArchive;
import org.niord.core.bootstrap.BootstrapMarker;
import org.niord.core.bootstrap.BootstrapRefusedException;
import org.niord.core.bootstrap.BootstrapRunner;
import org.niord.core.bootstrap.StartUpState;
import org.niord.core.user.Roles;
import org.niord.core.util.MdcContext;
import org.niord.core.util.WebUtils;
import org.niord.core.web.NiordWebException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The bootstrap command (docs/code-data-config-plan.md §7.2, D4): applies the bootstrap archives once, to a database
 * whose marker reads {@code pending} and that holds no data at the claim. A failed bootstrap, or one that broke off,
 * is never run again: the database is recreated empty, and the bootstrap run again with the same archives.
 * {@code be/scripts/bootstrap.sh} wraps it.
 */
@RestController
@RequestMapping("/rest/bootstrap")
public class BootstrapRestService {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRestService.class);

    /** The name of the multipart parts that carry the archives */
    static final String ARCHIVE_PART = "archive";

    /** The marker as the command answers it */
    public record BootstrapStateVo(String state, String hash, String error, String baseVersion, String updated) {

        static BootstrapStateVo of(BootstrapMarker.State s) {
            return new BootstrapStateVo(s.state(), s.hash(), s.error(), s.baseVersion(),
                    s.updated() != null ? s.updated().toString() : null);
        }
    }

    private final BootstrapMarker marker;
    private final BootstrapRunner runner;
    private final StartUpState startUp;

    BootstrapRestService(BootstrapMarker marker, BootstrapRunner runner, StartUpState startUp) {
        this.marker = marker;
        this.runner = runner;
        this.startUp = startUp;
    }

    /**
     * Returns the bootstrap marker
     *
     * @return the marker
     * @throws NiordWebException 503 until every start-up listener has run ({@link StartUpState}), the marker's,
     *                           the default texts' and the settings' included: the web server answers before they
     *                           have, so a caller asks again
     */
    @GetMapping(produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public BootstrapStateVo getState() {
        checkStarted();
        return marker.read()
                .map(BootstrapStateVo::of)
                .orElseThrow(() -> new NiordWebException(
                        "The bootstrap marker is not written yet: the backend is still starting", 503));
    }

    /**
     * Applies the uploaded archives, in the order sent: the base product's first, then a country's (locally the
     * base product's archive alone, the sample then loaded by its own loader). The command does not check that the
     * first part is the base product's archive: a country's archive sent first would have its values replaced by
     * the base product's. Every archive is read and checked, and the marker claimed, before the answer, so a refusal
     * is immediate; the jobs then run in the background on the caller's security context, and {@code GET} reads the
     * outcome.
     *
     * @param request the multipart request, one part named {@value #ARCHIVE_PART} per archive
     * @return the marker, {@code in-progress}
     * @throws NiordWebException 503 until every start-up listener has run, before any part is read; 400 naming the
     *                           archive rule an archive broke, also a later archive that restates a domainId an
     *                           earlier one gives, other than with its domainId and active flag only; 409 naming
     *                           the marker's state when it is not {@code pending}
     *                           (a failed bootstrap, or one that broke off, is never run again: the remedy, a
     *                           database recreated empty, named), or the first table that holds a row when the
     *                           database holds data
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BootstrapStateVo bootstrap(MultipartHttpServletRequest request) throws IOException {
        checkStarted();
        List<MultipartFile> parts = request.getMultiFileMap().get(ARCHIVE_PART);
        if (parts == null || parts.isEmpty()) {
            throw new NiordWebException("No archive: send each archive as a part named '" + ARCHIVE_PART + "'", 400);
        }
        List<BootstrapArchive> archives = new ArrayList<>();
        try {
            for (MultipartFile part : parts) {
                String name = WebUtils.bareFileName(part.getOriginalFilename());
                try (InputStream in = part.getInputStream()) {
                    archives.add(BootstrapArchive.read(name != null ? name : ARCHIVE_PART, in));
                }
            }
            runner.claim(archives);
        } catch (BootstrapRefusedException e) {
            throw new NiordWebException(e.getMessage(), e.getStatus());
        }

        Runnable apply = MdcContext.wrapRunnable(new DelegatingSecurityContextRunnable(() -> {
            try {
                runner.apply(archives);
            } catch (RuntimeException e) {
                log.error("The bootstrap run ended with an error", e);
            }
        }));
        Thread.ofPlatform().name("niord-bootstrap").start(apply);

        return marker.read().map(BootstrapStateVo::of).orElseThrow();
    }

    /**
     * Refuses until every start-up listener has run: the bootstrap's imports never run beside the start-up's own
     * writes, so the base product's bootstrap gives the same ids on every fresh database
     */
    private void checkStarted() {
        if (!startUp.isDone()) {
            throw new NiordWebException("The backend is still starting: ask again in a moment", 503);
        }
    }
}

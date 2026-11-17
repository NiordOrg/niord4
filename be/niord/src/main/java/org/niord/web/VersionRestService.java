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

import org.niord.core.NiordApp;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.annotation.security.PermitAll;


/**
 * An extremely simple REST endpoint that returns the build version of the Niord back-end.
 */
@RestController
@RequestMapping("/rest/version")
@PermitAll
public class VersionRestService {

    private final NiordApp niordApp;

    VersionRestService(NiordApp niordApp) {
        this.niordApp = niordApp;
    }

    /** Returns the build-version of the Niord back-end **/
    @GetMapping(value = "/build-version", produces = "text/plain")
    public String buildVersion() {
        return niordApp.getBuildVersion();
    }

}

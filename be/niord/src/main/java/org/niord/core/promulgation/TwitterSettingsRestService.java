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

package org.niord.core.promulgation;

import org.niord.core.promulgation.vo.TwitterSettingsVo;
import org.niord.core.user.Roles;
import org.niord.core.web.NiordWebException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.RolesAllowed;

import java.util.Objects;

/**
 * REST interface for managing Twitter settings.
 */
@RestController
@RequestMapping("/rest/promulgation/twitter-settings")
@RolesAllowed(Roles.SYSADMIN)
@SuppressWarnings("unused")
public class TwitterSettingsRestService {

    private final TwitterPromulgationService twitterPromulgationService;

    TwitterSettingsRestService(TwitterPromulgationService twitterPromulgationService) {
        this.twitterPromulgationService = twitterPromulgationService;
    }

    /** Returns the settings for the given type */
    @GetMapping(value = "/{typeId}", produces = "application/json;charset=UTF-8")
    public TwitterSettingsVo getSettings(
            @PathVariable("typeId") String typeId) {
        TwitterSettings settings = twitterPromulgationService.getSettings(typeId);
        return settings == null ? null : settings.toVo();
    }

    /** Creates new settings */
    @PostMapping(value = {"", "/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public TwitterSettingsVo createSettings(
            @RequestBody TwitterSettingsVo settings) throws Exception {

        return twitterPromulgationService.createSettings(new TwitterSettings(settings)).toVo();
    }

    /** Updates the credential for the given promulgation type */
    @PutMapping(value = "/{typeId}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public TwitterSettingsVo updateSettings(
            @PathVariable("typeId") String typeId,
            @RequestBody TwitterSettingsVo settings) throws Exception {

        if (!Objects.equals(typeId, settings.getPromulgationType().getTypeId())) {
            throw new NiordWebException(400);
        }

        return twitterPromulgationService.updateSettings(new TwitterSettings(settings)).toVo();
    }

}

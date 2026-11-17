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

import static org.niord.core.promulgation.NavtexPromulgationService.NAVTEX_LINE_LENGTH;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.promulgation.vo.NavtexMessagePromulgationVo;
import org.niord.core.promulgation.vo.NavtexTransmitterVo;
import org.niord.core.user.Roles;
import org.niord.core.util.TextUtils;
import org.niord.model.DataFilter;
import org.springframework.http.HttpStatus;
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

import jakarta.annotation.security.RolesAllowed;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;

/**
 * REST interface to managing NAVTEX transmitters.
 */
@RestController
@RequestMapping("/rest/promulgation/navtex")
@RolesAllowed(Roles.SYSADMIN)
public class NavtexPromulgationRestService {

    /**
     * The longest text the reformat endpoints accept. NAVTEX and SafetyNET texts run to a few hundred
     * characters; the bound only keeps the work of one request small.
     */
    static final int MAX_REFORMAT_TEXT_LENGTH = 20_000;

    private final NavtexPromulgationService navtexPromulgationService;

    NavtexPromulgationRestService(NavtexPromulgationService navtexPromulgationService) {
        this.navtexPromulgationService = navtexPromulgationService;
    }

    /**
     * Reformats the NAVTEX text.
     *
     * Called by the message editor only, so it takes an editor, like generating the promulgation does.
     * A text longer than {@link #MAX_REFORMAT_TEXT_LENGTH} characters is refused with 400.
     */
    @PutMapping(value = "/reformat-navtex",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public NavtexMessagePromulgationVo reformatNavtex(@RequestBody NavtexMessagePromulgationVo navtex) throws Exception {
        checkReformatTextLength(navtex.getText());
        if (StringUtils.isNotBlank(navtex.getText())) {
            String text = navtex.getText();

            // Split into 40-character lines and enforce uppercase
            text = TextUtils.maxLineLength(text, NAVTEX_LINE_LENGTH)
                    .replaceAll("(?is)\\n+", "\n")
                    .toUpperCase()
                    .trim();

            navtex.setText(text);
        }
        return navtex;
    }

    /**
     * Refuses a text to reformat that is longer than {@link #MAX_REFORMAT_TEXT_LENGTH} characters.
     * @param text the text to reformat, may be null
     * @throws NiordWebException 400 when the text is too long
     */
    static void checkReformatTextLength(String text) {
        if (text != null && text.length() > MAX_REFORMAT_TEXT_LENGTH) {
            throw new NiordWebException(
                    "The text is longer than " + MAX_REFORMAT_TEXT_LENGTH + " characters", 400);
        }
    }

    /***************************************/
    /** Transmitter Handling              **/
    /***************************************/

    /** Returns all transmitters */
    @GetMapping(value = "/transmitters/{typeId}/all", produces = "application/json;charset=UTF-8")
    @Transactional
    public List<NavtexTransmitterVo> getTransmitters(
            @PathVariable("typeId") String typeId,
            @RequestParam(name = "lang", defaultValue = "en") String lang) {

        DataFilter filter = DataFilter.get().lang(lang);
        return navtexPromulgationService.getTransmitters(typeId).stream()
                .map(t -> t.toVo(filter))
                .collect(Collectors.toList());
    }

    /** Creates a new transmitter */
    @PostMapping(value = {"/transmitters/{typeId}/transmitter", "/transmitters/{typeId}/transmitter/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @Transactional
    public NavtexTransmitterVo createTransmitter(
            @PathVariable("typeId") String typeId,
            @RequestBody NavtexTransmitterVo transmitter) throws Exception {

        refuseWithoutType(transmitter);
        if (!Objects.equals(typeId, transmitter.getPromulgationType().getTypeId())) {
            throw new NiordWebException(400);
        }
        if (StringUtils.isBlank(transmitter.getName())) {
            throw new IllegalArgumentException("A transmitter needs a name");
        }

        NavtexTransmitter t = new NavtexTransmitter(transmitter);
        return navtexPromulgationService.createTransmitter(t)
                .toVo(DataFilter.get());
    }

    /** Updates an existing transmitter */
    @PutMapping(value = "/transmitters/{typeId}/transmitter/{name}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @Transactional
    public NavtexTransmitterVo updateTransmitter(
            @PathVariable("typeId") String typeId,
            @PathVariable("name") String name,
            @RequestBody NavtexTransmitterVo transmitter) throws Exception {

        refuseWithoutType(transmitter);
        if (!Objects.equals(typeId, transmitter.getPromulgationType().getTypeId())) {
            throw new NiordWebException(400);
        }
        if (!Objects.equals(name, transmitter.getName())) {
            throw new NiordWebException(400);
        }

        NavtexTransmitter t = new NavtexTransmitter(transmitter);
        return navtexPromulgationService.updateTransmitter(t)
                .toVo(DataFilter.get());
    }

    /**
     * Refuses a transmitter body that does not name its promulgation type. The path names the type too, and the
     * two must agree; without this check the comparison failed with a {@code NullPointerException} and a 500.
     */
    private static void refuseWithoutType(NavtexTransmitterVo transmitter) {
        if (transmitter.getPromulgationType() == null) {
            throw new IllegalArgumentException("A transmitter must name its promulgation type");
        }
    }

    /** Deletes an existing transmitter */
    @DeleteMapping("/transmitters/{typeId}/transmitter/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransmitter(
            @PathVariable("typeId") String typeId,
            @PathVariable("name") String name) throws Exception {

        navtexPromulgationService.deleteTransmitter(typeId, name);
    }

}

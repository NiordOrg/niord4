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

import org.apache.commons.lang3.StringUtils;
import org.niord.core.promulgation.vo.SafetyNetAreaVo;
import org.niord.core.promulgation.vo.SafetyNetMessagePromulgationVo;
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
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.niord.core.promulgation.NavtexPromulgationService.NAVTEX_LINE_LENGTH;

/**
 * REST interface to managing SafetyNET areas.
 * <p>
 * The handlers that answer with areas run in a transaction: an area's value object carries its
 * promulgation type with the type's lazy domains, which are read while the answer is built.
 */
@RestController
@RequestMapping("/rest/promulgation/safetynet")
@RolesAllowed(Roles.SYSADMIN)
@SuppressWarnings("unused")
public class SafetyNetPromulgationRestService {

    private final SafetyNetPromulgationService safetyNetPromulgationService;

    SafetyNetPromulgationRestService(SafetyNetPromulgationService safetyNetPromulgationService) {
        this.safetyNetPromulgationService = safetyNetPromulgationService;
    }

    /**
     * Reformats the SafetyNET text.
     *
     * Called by the message editor only, so it takes an editor, like generating the promulgation does.
     * A text longer than {@link NavtexPromulgationRestService#MAX_REFORMAT_TEXT_LENGTH} characters is
     * refused with 400.
     */
    @PutMapping(value = "/reformat-safetynet",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public SafetyNetMessagePromulgationVo reformatSafetyNet(@RequestBody SafetyNetMessagePromulgationVo safetynet) throws Exception {
        NavtexPromulgationRestService.checkReformatTextLength(safetynet.getText());
        if (StringUtils.isNotBlank(safetynet.getText())) {
            String text = safetynet.getText();

            // Clean up blank space and enforce uppercase
            text = TextUtils.maxLineLength(text, NAVTEX_LINE_LENGTH)
                    .replaceAll("(?is)\\n+", "\n")
                    .toUpperCase()
                    .trim();

            safetynet.setText(text);
        }
        return safetynet;
    }

    /***************************************/
    /** SafetyNET Area Handling           **/
    /***************************************/

    /** Returns all areas */
    @GetMapping(value = "/areas/{typeId}/all", produces = "application/json;charset=UTF-8")
    @Transactional(readOnly = true)
    public List<SafetyNetAreaVo> getAreas(
            @PathVariable("typeId") String typeId,
            @RequestParam(name = "lang", defaultValue = "en") String lang) {

        DataFilter filter = DataFilter.get().lang(lang);
        return safetyNetPromulgationService.getAreas(typeId).stream()
                .map(a -> a.toVo(DataFilter.get().fields(DataFilter.DETAILS)))
                .collect(Collectors.toList());
    }

    /** Creates a new area */
    @PostMapping(value = {"/areas/{typeId}/area", "/areas/{typeId}/area/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @Transactional
    public SafetyNetAreaVo createArea(
            @PathVariable("typeId") String typeId,
            @RequestBody SafetyNetAreaVo area) throws Exception {

        if (!Objects.equals(typeId, area.getPromulgationType().getTypeId())) {
            throw new NiordWebException(400);
        }

        return safetyNetPromulgationService.createArea(area.toEntity())
                .toVo(DataFilter.get().fields(DataFilter.DETAILS));
    }

    /** Updates an existing area */
    @PutMapping(value = "/areas/{typeId}/area/{name}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @Transactional
    public SafetyNetAreaVo updateArea(
            @PathVariable("typeId") String typeId,
            @PathVariable("name") String name,
            @RequestBody SafetyNetAreaVo area) throws Exception {

        if (!Objects.equals(typeId, area.getPromulgationType().getTypeId())) {
            throw new NiordWebException(400);
        }
        if (!Objects.equals(name, area.getName())) {
            throw new NiordWebException(400);
        }

        return safetyNetPromulgationService.updateArea(area.toEntity())
                .toVo(DataFilter.get().fields(DataFilter.DETAILS));
    }

    /** Deletes an existing area */
    @DeleteMapping("/areas/{typeId}/area/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteArea(
            @PathVariable("typeId") String typeId,
            @PathVariable("name") String name) throws Exception {

        safetyNetPromulgationService.deleteArea(typeId, name);
    }

}

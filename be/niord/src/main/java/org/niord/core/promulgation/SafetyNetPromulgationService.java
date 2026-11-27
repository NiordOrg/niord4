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

import org.springframework.stereotype.Service;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.message.Message;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.PromulgationType.Requirement;
import org.niord.core.promulgation.vo.BaseMessagePromulgationVo;
import org.niord.core.promulgation.vo.SafetyNetAreaVo;
import org.niord.core.promulgation.vo.SafetyNetMessagePromulgationVo;
import org.niord.core.util.PositionAssembler;
import org.niord.core.util.PositionUtils;
import org.niord.core.util.TextUtils;
import org.niord.model.DataFilter;
import org.niord.model.message.Type;

import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Manages SafetyNET-via-mailing-lists promulgations
 */
@Service
@SuppressWarnings("unused")
public class SafetyNetPromulgationService
        extends BasePromulgationService {

    public static final String SUPERFLUOUS_WORDS = "the|in pos\\.|is";

    private final PromulgationTypeService promulgationTypeService;

    SafetyNetPromulgationService(PromulgationTypeService promulgationTypeService) {
        this.promulgationTypeService = promulgationTypeService;
    }

    /***************************************/
    /** Promulgation Service Handling     **/
    /***************************************/

    /** {@inheritDoc} */
    @Override
    public String getServiceId() {
        return SafetyNetMessagePromulgation.SERVICE_ID;
    }

    /** {@inheritDoc} */
    @Override
    public String getServiceName() {
        return "SafetyNET mailing list";
    }

    /***************************************/
    /** Message Life-cycle Management     **/
    /***************************************/

    /** {@inheritDoc} */
    @Override
    public void onLoadSystemMessage(SystemMessageVo message, PromulgationType type) throws PromulgationException {
        SafetyNetMessagePromulgationVo safetynet = message.promulgation(SafetyNetMessagePromulgationVo.class, type.getTypeId());
        if (safetynet == null) {
            safetynet = new SafetyNetMessagePromulgationVo(type.toVo(DataFilter.get()));
            message.checkCreatePromulgations().add(safetynet);
        }

        checkSafetyNetArea(safetynet, message.getType());
    }

    /** {@inheritDoc} */
    @Override
    public void onCreateMessage(Message message, PromulgationType type) throws PromulgationException {
        // A created message may come from an import: an area this deployment lacks is dropped with a WARN
        checkSafetyNetPromulgation(message, type, false);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Only an editor's save of an existing message comes here: a selected area that no longer exists refuses the
     * save instead of being dropped.
     */
    @Override
    public void onUpdateMessage(Message message, PromulgationType type) throws PromulgationException {
        checkSafetyNetPromulgation(message, type, true);
    }

    /**
     * Checks that the SafetyNET promulgation is valid and ready to be persisted
     * @param message the message to check
     * @param refuseUnknown true when an area that does not exist refuses the save; false when it is dropped with
     *                      a WARN
     * @throws IllegalArgumentException if refuseUnknown is true and the selected area names no area of the type
     */
    private void checkSafetyNetPromulgation(Message message, PromulgationType type, boolean refuseUnknown) {
        SafetyNetMessagePromulgation safetynet = message.promulgation(SafetyNetMessagePromulgation.class, type.getTypeId());
        if (safetynet != null && safetynet.getArea() != null) {
            // Replace the area with the persisted entities
            String name = safetynet.getArea().getName();
            SafetyNetArea area = findAreaByName(type.getTypeId(), name);
            if (area == null) {
                if (refuseUnknown) {
                    throw new IllegalArgumentException("The SafetyNET area '" + name
                            + "' does not exist; choose another area and save again");
                }
                log.warn("The SafetyNET area '{}' of type {} does not exist and is left out of the message",
                        name, type.getTypeId());
            }
            safetynet.setArea(area);
        }
    }

    /**
     * Checks that SafetyNET area matches the current message type.
     * @param safetynet the promulgation type.
     * @param messageType the message type to match
     */
    public void checkSafetyNetArea(SafetyNetMessagePromulgationVo safetynet, Type messageType) {

        // Get all active areas for the given message type
        List<SafetyNetAreaVo> areas = findAreasByMessageType(safetynet.getType().getTypeId(), messageType, true).stream()
                .map(a -> a.toVo(DataFilter.get()))
                .collect(Collectors.toList());
        safetynet.setAreas(areas);

        // Get the selected area - found by matching the selected area name against the list of areas
        SafetyNetAreaVo selArea = safetynet.selectedArea();

        // If no selected area was found (it may e.g. be inactive), look it up by name and add it to the area list.
        if (selArea == null && StringUtils.isNotBlank(safetynet.getAreaName())) {
            SafetyNetArea named = findAreaByName(safetynet.getType().getTypeId(), safetynet.getAreaName());
            selArea = named == null ? null : named.toVo(DataFilter.get());
            // For persisted SafetyNET promulgations, always include the selected area, even if it is not valid anymore
            if (safetynet.getId() != null && selArea != null) {
                safetynet.getAreas().add(selArea);
            } else {
                safetynet.setAreaName(null);
            }
        }

        // If the SafetyNET promulgation is not yet persisted and no area is specified
        // and only one area is available, select it.
        if (StringUtils.isBlank(safetynet.getAreaName()) && areas.size() == 1) {
            safetynet.setAreaName(areas.getFirst().getName());
        }
    }

    /***************************************/
    /** Generating promulgations          **/
    /***************************************/

    /** {@inheritDoc} */
    @Override
    public BaseMessagePromulgationVo generateMessagePromulgation(SystemMessageVo message, PromulgationType type) throws PromulgationException {

        SafetyNetMessagePromulgationVo safetynet = new SafetyNetMessagePromulgationVo(type.toVo(DataFilter.get()));

        // Compute the SafetyNET text. A message without parts, and a part without texts, hold no list at all: they
        // give no text
        String language = getLanguage(type);
        StringBuilder text = new StringBuilder();
        if (message.getParts() != null) {
            message.getParts().stream()
                .filter(p -> p.getDescs() != null)
                .flatMap(p -> p.getDescs().stream())
                .filter(d -> d.getLang().equals(language))
                .filter(d -> StringUtils.isNotBlank(d.getDetails()))
                .map(d -> html2safetynet(d.getDetails(), type.getLanguage()))
                .forEach(d -> text.append(d).append(System.lineSeparator()));
        }

        if (text.length() > 0) {
            safetynet.setPromulgate(true);
            safetynet.setText(text.toString().toUpperCase().trim());
        } else {
            safetynet.setPromulgate(type.getRequirement() == Requirement.MANDATORY);
        }

        return safetynet;
    }

    /** Transforms a HTML description to a SafetyNET description **/
    private String html2safetynet(String text, String language) {

        // Convert from html to plain text
        text = TextUtils.html2txt(text, true);

        // Remove separator between positions
        text = PositionUtils.replaceSeparator(text, " ");

        // Replace positions with NAVTEX versions
        PositionAssembler navtexPosAssembler = PositionAssembler.newNavtexPositionAssembler();
        text = PositionUtils.updatePositionFormat(text, navtexPosAssembler);

        // Remove verbose words, such as "the", from the text
        text = TextUtils.removeWords(text, SUPERFLUOUS_WORDS);

        // NB: unlike NAVTEX, we do not split into 40-character lines

        return text.toUpperCase();
    }

    /** {@inheritDoc} */
    @Override
    public void messagePromulgationGenerated(SystemMessageVo message, PromulgationType type) throws PromulgationException {
        SafetyNetMessagePromulgationVo safetynet = message.promulgation(SafetyNetMessagePromulgationVo.class, type.getTypeId());
        if (safetynet != null) {

            if (StringUtils.isNotBlank(safetynet.getText())) {
                String text = safetynet.getText();

                // Remove blank lines and enforce uppercase
                text = text
                        .replaceAll("(?is)\\n+", "\n")
                        .toUpperCase()
                        .trim();

                safetynet.setText(text);
            }

            // Compute the SafetyNET distribution area
            checkSafetyNetArea(safetynet, message.getType());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void resetMessagePromulgation(SystemMessageVo message, PromulgationType type) throws PromulgationException {
        SafetyNetMessagePromulgationVo safetynet = message.promulgation(SafetyNetMessagePromulgationVo.class, type.getTypeId());
        if (safetynet != null) {
            safetynet.reset();
            checkSafetyNetArea(safetynet, message.getType());
        }
    }

    /***************************************/
    /** SafetyNET Area Handling           **/
    /***************************************/

    /**
     * Returns the area of the type with the given name, compared ignoring case, or null if there is none.
     * <p>
     * Two areas of a type may have names that differ only in case (the unique name column compares case). Then the
     * one whose name matches exactly is returned; when none matches exactly, the name is ambiguous and an
     * {@code IllegalStateException} is thrown. A database failure is not caught.
     *
     * @throws IllegalStateException if several areas of the type are named like the name and none exactly
     */
    public SafetyNetArea findAreaByName(String typeId, String name) {
        List<SafetyNetArea> matches = em.createNamedQuery("SafetyNetArea.findByName", SafetyNetArea.class)
                .setParameter("typeId", typeId)
                .setParameter("name", name)
                .getResultList();
        if (matches.size() <= 1) {
            return matches.isEmpty() ? null : matches.getFirst();
        }
        return matches.stream()
                .filter(a -> Objects.equals(a.getName(), name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Several SafetyNET areas of type " + typeId + " are named like '" + name + "'"));
    }

    /**
     * Find all SafetyNET areas matching the given message type.
     * @param typeId the promulgation type
     * @param messageType the message type to match
     * @param onlyActive whether or not to only return active areas
     * @return all SafetyNET areas matching the given message type.
     */
    public List<SafetyNetArea> findAreasByMessageType(String typeId, Type messageType, boolean onlyActive) {

        return em.createNamedQuery("SafetyNetArea.findByType", SafetyNetArea.class)
                .setParameter("typeId", typeId)
                .getResultList()
                .stream()
                .filter(a -> !onlyActive || a.isActive())
                .filter(a -> a.supportsMessageType(messageType))
                .collect(Collectors.toList());
    }

    /** Returns all SafetyNET areas associated with the given SafetyNET promulgation type */
    public List<SafetyNetArea> getAreas(String typeId) {
        return em.createNamedQuery("SafetyNetArea.findByType", SafetyNetArea.class)
                .setParameter("typeId", typeId)
                .getResultList();
    }

    /** Creates a new SafetyNET area */
    @Transactional
    public SafetyNetArea createArea(SafetyNetArea area) {

        String typeId = area.getPromulgationType().getTypeId();
        log.info("Creating SafetyNET area " + area.getName() + " for promulgation type " + typeId);

        // A name that differs from an existing one of the type only in case would make the lookup by name ambiguous
        boolean twin = !em.createNamedQuery("SafetyNetArea.findByName", SafetyNetArea.class)
                .setParameter("typeId", typeId)
                .setParameter("name", area.getName())
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
        if (twin) {
            throw new IllegalStateException("A SafetyNET area of type " + typeId + " is already named like '"
                    + area.getName() + "'");
        }

        area.setPromulgationType(promulgationTypeService.getPromulgationType(typeId));
        return saveEntity(area);
    }

    /** Updates an existing SafetyNET area */
    @Transactional
    public SafetyNetArea updateArea(SafetyNetArea area) {

        String typeId = area.getPromulgationType().getTypeId();
        log.info("Updating SafetyNET area " + area.getName() + " for promulgation type " + typeId);

        SafetyNetArea original = findAreaByName(typeId, area.getName());
        original.update(area);

        return saveEntity(original);
    }

    /** Deletes an existing SafetyNET area */
    @SuppressWarnings("all")
    @Transactional
    public boolean deleteArea(String typeId, String name) {

        log.info("Deleting SafetyNET area " + name + " for promulgation type " + typeId);

        SafetyNetArea original = findAreaByName(typeId, name);
        if (original != null) {
            remove(original);
            return true;
        }
        return false;
    }

}

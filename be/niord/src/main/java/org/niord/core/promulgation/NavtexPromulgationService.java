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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.area.Area;
import org.niord.core.db.CriteriaHelper;
import org.niord.core.message.Message;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.MessageTokenExpander;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.PromulgationType.Requirement;
import org.niord.core.promulgation.vo.BaseMessagePromulgationVo;
import org.niord.core.promulgation.vo.NavtexMessagePromulgationVo;
import org.niord.core.util.PositionAssembler;
import org.niord.core.util.PositionUtils;
import org.niord.core.util.TextUtils;
import org.niord.core.util.TimeUtils;
import org.niord.model.DataFilter;
import org.niord.model.message.Status;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages NAVTEX-via-mailing-lists promulgations
 */
@Service
@SuppressWarnings("unused")
public class NavtexPromulgationService extends BasePromulgationService {

    public static int NAVTEX_LINE_LENGTH = 40;
    public static final String SUPERFLUOUS_WORDS = "the|in pos\\.|is";

    private final PromulgationTypeService promulgationTypeService;

    private final MessageSeriesService messageSeriesService;

    NavtexPromulgationService(PromulgationTypeService promulgationTypeService, MessageSeriesService messageSeriesService) {
        this.promulgationTypeService = promulgationTypeService;
        this.messageSeriesService = messageSeriesService;
    }

    /***************************************/
    /** Promulgation Service Handling     **/
    /***************************************/

    /** {@inheritDoc} */
    @Override
    public String getServiceId() {
        return NavtexMessagePromulgation.SERVICE_ID;
    }

    /** {@inheritDoc} */
    @Override
    public String getServiceName() {
        return "NAVTEX mailing list";
    }

    /***************************************/
    /** Message Life-cycle Management     **/
    /***************************************/

    /** {@inheritDoc} */
    @Override
    public void onLoadSystemMessage(SystemMessageVo message, PromulgationType type) throws PromulgationException {
        NavtexMessagePromulgationVo navtex = message.promulgation(NavtexMessagePromulgationVo.class, type.getTypeId());
        if (navtex == null) {
            navtex = new NavtexMessagePromulgationVo(type.toVo(DataFilter.get()));
            message.checkCreatePromulgations().add(navtex);
        }

        // Update the preamble from the message series - if defined
        checkNavtexPreamble(message, navtex);

        // Add all active transmitters not already added
        for (NavtexTransmitter transmitter : findTransmittersByAreas(type.getTypeId(), null, true)) {
            if (!navtex.getTransmitters().containsKey(transmitter.getName())) {
                navtex.getTransmitters().put(transmitter.getName(), Boolean.FALSE);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onCopyMessage(SystemMessageVo message, PromulgationType type) throws PromulgationException {
        NavtexMessagePromulgationVo navtex = message.promulgation(NavtexMessagePromulgationVo.class, type.getTypeId());

        // When copying a message with a NAVTEX promulgation, reset the preamble
        if (navtex != null) {
            navtex.setPreamble(null);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onCreateMessage(Message message, PromulgationType type) throws PromulgationException {
        // A created message comes from an editor, an import, the firing run or the cancellation warning: a
        // transmitter this deployment lacks is dropped with a WARN, never a reason to refuse it
        checkNavtexPromulgation(message, type, false);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Only an editor's save of an existing message comes here: a ticked transmitter that no longer exists refuses
     * the save, so the warning does not go to fewer stations than the editor chose without a word.
     */
    @Override
    public void onUpdateMessage(Message message, PromulgationType type) throws PromulgationException {
        checkNavtexPromulgation(message, type, true);
    }

    /** {@inheritDoc} */
    @Override
    public void onUpdateMessageStatus(Message message, PromulgationType type) throws PromulgationException {
        NavtexMessagePromulgation navtex = checkNavtexPromulgation(message, type, false);

        // When the message is published and if the preamble is defined,
        if (message.getStatus() == Status.PUBLISHED && navtex != null) {

            // Update the NAVTEX preamble
            if (StringUtils.isNotBlank(navtex.getPreamble())) {
                // Replace tokens
                navtex.setPreamble(expandNavtexPreambleTokens(
                        navtex.getPreamble(),
                        message.getStatus(),
                        message.getPublishDateFrom(),
                        message.newMessageTokenExpander(null, null),
                        message.getUid()));
            }

            // Update the NAVTEX text
            if (StringUtils.isNotBlank(navtex.getText())) {
                // Format according to NAVTEX guidelines: the NAVTEX alphabet, then lines of 40 characters
                String text = TextUtils.maxLineLength(toNavtexAlphabet(navtex.getText(), message.getUid()),
                                NAVTEX_LINE_LENGTH)
                        .toUpperCase()
                        .trim();
                navtex.setText(text);
            }
        }
    }

    /** Check if the NAVTEX preamble should be updated from the message series **/
    private void checkNavtexPreamble(SystemMessageVo message, NavtexMessagePromulgationVo navtex) {
        if (StringUtils.isBlank(navtex.getPreamble()) && message.getMessageSeries() != null) {
            MessageSeries messageSeries = messageSeriesService.findBySeriesId(message.getMessageSeries().getSeriesId());
            if (messageSeries != null && StringUtils.isNotBlank(messageSeries.getNavtexFormat())) {
                navtex.setPreamble(expandNavtexPreambleTokens(
                        messageSeries.getNavtexFormat(),
                        message.getStatus(),
                        message.getPublishDateFrom(),
                        message.newMessageTokenExpander(null, null),
                        message.getId()));
            }
        }
    }

    /**
     * If the message status is PUBLISHED, CANCELLED or DELETED, expand preamble tokens such as
     * ${publish-date} and ${short-id}
     */
    private String expandNavtexPreambleTokens(String preamble, Status status, Date publishDateFrom,
                                              MessageTokenExpander tokenExpander, String messageUid) {
        // Update the NAVTEX preamble
        if (StringUtils.isNotBlank(preamble) && status.isPublic()) {

            // Replace tokens
            preamble = tokenExpander
                    .token("${publish-date}", TimeUtils.formatNavtexDate(publishDateFrom))
                    .expandTokens(preamble);

            // Format according to NAVTEX guidelines: the NAVTEX alphabet (only now, since the "${" and "}" of a
            // token are outside it), then lines of 40 characters
            preamble = TextUtils.maxLineLength(toNavtexAlphabet(preamble, messageUid), NAVTEX_LINE_LENGTH)
                    .toUpperCase()
                    .trim();
        }
        return preamble;
    }

    /**
     * Checks that the NAVTEX promulgation is valid and ready to be persisted
     * @param message the message to check
     * @param refuseUnknown whether a transmitter that does not exist refuses the save (see persistedTransmitters)
     */
    private NavtexMessagePromulgation checkNavtexPromulgation(Message message, PromulgationType type,
                                                              boolean refuseUnknown) {
        NavtexMessagePromulgation navtex = message.promulgation(NavtexMessagePromulgation.class, type.getTypeId());
        if (navtex != null) {
            // Replace the list of transmitters with the persisted entities
            navtex.setTransmitters(persistedTransmitters(type.getTypeId(), navtex.getTransmitters(), refuseUnknown));

            // Update the preamble from the message series
            if (StringUtils.isBlank(navtex.getPreamble()) && message.getMessageSeries() != null
                    && StringUtils.isNotBlank(message.getMessageSeries().getNavtexFormat())) {
                navtex.setPreamble(message.getMessageSeries().getNavtexFormat());
            }

            // Hold a typed or imported text to the NAVTEX alphabet on every save
            if (StringUtils.isNotBlank(navtex.getText())) {
                navtex.setText(toNavtexAlphabet(navtex.getText(), message.getUid()));
            }
        }
        return navtex;
    }

    /**
     * Maps the text into the NAVTEX alphabet ({@link NavtexText#normalise}), and logs at WARN the characters that had
     * no mapping and became spaces. It does not split lines: a mapped letter may lengthen a line, so a caller
     * splits the text into lines of 40 characters afterwards.
     */
    private String toNavtexAlphabet(String text, String messageUid) {
        if (StringUtils.isBlank(text)) {
            return text;
        }
        List<String> leftovers = NavtexText.outsideAlphabet(text);
        if (!leftovers.isEmpty()) {
            log.warn("NAVTEX text of message {}: characters outside the NAVTEX alphabet replaced by spaces: {}",
                    messageUid, leftovers.stream()
                            .map(c -> String.format("'%s' U+%04X", c, c.codePointAt(0)))
                            .collect(Collectors.joining(", ")));
        }
        return NavtexText.normalise(text);
    }

    /***************************************/
    /** Generating promulgations          **/
    /***************************************/

    /** {@inheritDoc} */
    @Override
    public BaseMessagePromulgationVo generateMessagePromulgation(SystemMessageVo message, PromulgationType type) throws PromulgationException {

        NavtexMessagePromulgationVo navtex = new NavtexMessagePromulgationVo(type.toVo(DataFilter.get()));

        // Add all active transmitters - by default, not selected
        findTransmittersByAreas(type.getTypeId(), null, true)
                .forEach(t -> navtex.getTransmitters().put(t.getName(), Boolean.FALSE));

        // Select transmitters associated with the current message areas
        if (message.getAreas() != null && !message.getAreas().isEmpty()) {
            List<Area> areas = message.getAreas().stream()
                    .map(Area::new)
                    .collect(Collectors.toList());
            findTransmittersByAreas(type.getTypeId(), areas, true)
                    .forEach(t -> navtex.getTransmitters().put(t.getName(), Boolean.TRUE));
        }

        // Keep what the editor chose on the message's own NAVTEX entry, which the caller replaces by this one: every
        // active transmitter ticked by hand stays ticked (an inactive or unknown name is left out), and the priority.
        // A tick the stored message's areas give was derived from them, not set by hand: when the editor has changed
        // the areas it goes with them, as a Generate that kept no tick did. The entry cannot tell a hand tick from a tick
        // an earlier Generate gave, so a message never saved with its former areas keeps those ticks.
        NavtexMessagePromulgationVo incoming = message.promulgation(NavtexMessagePromulgationVo.class, type.getTypeId());
        if (incoming != null) {
            if (incoming.getTransmitters() != null) {
                Set<String> derivedFromTheStoredAreas = transmittersOfTheStoredAreas(message.getId(), type.getTypeId());
                incoming.getTransmitters().forEach((name, ticked) -> {
                    if (Boolean.TRUE.equals(ticked) && navtex.getTransmitters().containsKey(name)
                            && !derivedFromTheStoredAreas.contains(name)) {
                        navtex.getTransmitters().put(name, Boolean.TRUE);
                    }
                });
            }
            if (incoming.getPriority() != null) {
                navtex.setPriority(incoming.getPriority());
            }
        }

        // A message without parts, and a part without texts, hold no list at all: they give no text
        String language = getLanguage(type);
        StringBuilder text = new StringBuilder();
        if (message.getParts() != null) {
            message.getParts().stream()
                .filter(p -> p.getDescs() != null)
                .flatMap(p -> p.getDescs().stream())
                .filter(d -> d.getLang().equals(language))
                .filter(d -> StringUtils.isNotBlank(d.getDetails()))
                .map(d -> html2navtex(d.getDetails(), message.getId()))
                .forEach(d -> text.append(d).append('\n'));
        }

        if (text.length() > 0) {
            navtex.setPromulgate(true);
            navtex.setText(text.toString().toUpperCase().trim());
        } else {
            navtex.setPromulgate(type.getRequirement() == Requirement.MANDATORY);
        }

        return navtex;
    }

    /**
     * The names of the active transmitters the stored message's areas give: the ticks an earlier Generate or template
     * run derived from them. Empty for a message not saved yet, or one stored without areas.
     */
    private Set<String> transmittersOfTheStoredAreas(String messageUid, String typeId) {
        if (StringUtils.isBlank(messageUid)) {
            return Set.of();
        }
        List<Area> storedAreas = em.createNamedQuery("Message.findByUid", Message.class)
                .setParameter("uid", messageUid)
                .getResultStream()
                .findFirst()
                .map(Message::getAreas)
                .map(ArrayList::new)
                .orElse(new ArrayList<>());
        if (storedAreas.isEmpty()) {
            return Set.of();
        }
        return findTransmittersByAreas(typeId, storedAreas, true).stream()
                .map(NavtexTransmitter::getName)
                .collect(Collectors.toSet());
    }

    /** Transforms a HTML description to a NAVTEX description **/
    private String html2navtex(String text, String messageUid) {

        // Convert from html to plain text
        text = TextUtils.html2txt(text, true);

        // Remove separator between positions
        text = PositionUtils.replaceSeparator(text, " ");

        // Replace positions with NAVTEX versions
        PositionAssembler navtexPosAssembler = PositionAssembler.newNavtexPositionAssembler();
        text = PositionUtils.updatePositionFormat(text, navtexPosAssembler);

        // Remove verbose words, such as "the", from the text
        text = TextUtils.removeWords(text, SUPERFLUOUS_WORDS);

        // Hold the text to the NAVTEX alphabet, after the positions are rewritten (their degree signs mark them)
        // and before the lines are split (a mapped letter may lengthen a line)
        text = toNavtexAlphabet(text, messageUid);

        // Split into 40-character lines
        text = TextUtils.maxLineLength(text, NAVTEX_LINE_LENGTH);

        return text.toUpperCase();
    }

    /** {@inheritDoc} */
    @Override
    public void messagePromulgationGenerated(SystemMessageVo message, PromulgationType type) throws PromulgationException {
        NavtexMessagePromulgationVo navtex = message.promulgation(NavtexMessagePromulgationVo.class, type.getTypeId());
        if (navtex != null) {

            if (StringUtils.isNotBlank(navtex.getText())) {
                // Hold the text (a template's, a promulgation type script's or one typed) to the NAVTEX alphabet
                String text = toNavtexAlphabet(navtex.getText(), message.getId());

                // Split into 40-character lines, remove blank lines and enforce uppercase
                text = TextUtils.maxLineLength(text, NAVTEX_LINE_LENGTH)
                        .replaceAll("(?is)\\n+", "\n")
                        .toUpperCase()
                        .trim();

                navtex.setText(text);
            }

            // Compute the active transmitters from the message areas
            if (message.getAreas() != null && !message.getAreas().isEmpty()) {
                List<Area> areas = message.getAreas().stream()
                        .map(Area::new)
                        .collect(Collectors.toList());
                String typeId = type.getTypeId();
                Set<String> enabledTransmitters = findTransmittersByAreas(typeId, areas, true).stream()
                        .map(NavtexTransmitter::getName)
                        .collect(Collectors.toSet());

                // Add transmitters manually selected to the enabled-list
                enabledTransmitters.addAll(navtex.getTransmitters().entrySet().stream()
                    .filter(Map.Entry::getValue)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList()));

                navtex.getTransmitters().keySet().forEach(name ->
                        navtex.getTransmitters().put(name, enabledTransmitters.contains(name)));
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void resetMessagePromulgation(SystemMessageVo message, PromulgationType type) throws PromulgationException {
        NavtexMessagePromulgationVo navtex = message.promulgation(NavtexMessagePromulgationVo.class, type.getTypeId());
        if (navtex != null) {
            navtex.reset();
            checkNavtexPreamble(message, navtex);
            findTransmittersByAreas(type.getTypeId(), null, true)
                    .forEach(t -> navtex.getTransmitters().put(t.getName(), Boolean.FALSE));
        }
    }

    /***************************************/
    /** Transmitter Handling              **/
    /***************************************/

    /**
     * Returns the transmitter of the type with the given name, compared ignoring case, or null if there is none.
     * <p>
     * Two transmitters of a type may have names that differ only in case (the unique name column compares case).
     * Then the one whose name matches exactly is returned; when none matches exactly, the name is ambiguous and
     * an {@code IllegalStateException} is thrown. A database failure is not caught.
     *
     * @throws IllegalStateException if several transmitters of the type are named like the name and none exactly
     */
    public NavtexTransmitter findTransmitterByName(String typeId, String name) {
        List<NavtexTransmitter> matches = em.createNamedQuery("NavtexTransmitter.findByName", NavtexTransmitter.class)
                .setParameter("typeId", typeId)
                .setParameter("name", name)
                .getResultList();
        if (matches.size() <= 1) {
            return matches.isEmpty() ? null : matches.getFirst();
        }
        return matches.stream()
                .filter(t -> Objects.equals(t.getName(), name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Several NAVTEX transmitters of type " + typeId + " are named like '" + name + "'"));
    }

    /**
     * Update the list of transmitters with the persisted entities.
     *
     * @param refuseUnknown true when a name that names no transmitter refuses the save (an editor's update of an
     *                      existing message); false when it is dropped with a WARN (a message created by an editor,
     *                      an import or the system, and every status change, which must not fail on it)
     * @throws IllegalArgumentException if refuseUnknown is true and a name names no transmitter of the type
     */
    protected List<NavtexTransmitter> persistedTransmitters(String typeId, List<NavtexTransmitter> transmitters,
                                                            boolean refuseUnknown) {
        List<NavtexTransmitter> result = new ArrayList<>();
        for (NavtexTransmitter t : transmitters) {
            NavtexTransmitter persisted = findTransmitterByName(typeId, t.getName());
            if (persisted != null) {
                result.add(persisted);
            } else if (refuseUnknown) {
                throw new IllegalArgumentException("The NAVTEX transmitter '" + t.getName()
                        + "' does not exist; untick it and save again");
            } else {
                log.warn("The NAVTEX transmitter '{}' of type {} does not exist and is left out of the message",
                        t.getName(), typeId);
            }
        }
        return result;
    }

    /**
     * Find all NAVTEX transmitters matching the given areas.
     * @param areas the areas to match
     * @return all NAVTEX transmitters matching the given areas.
     */
    @SuppressWarnings("all")
    public List<NavtexTransmitter> findTransmittersByAreas(String typeId, List<Area> areas, boolean onlyActive) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<NavtexTransmitter> query = cb.createQuery(NavtexTransmitter.class);
        Root<NavtexTransmitter> transmitterRoot = query.from(NavtexTransmitter.class);

        CriteriaHelper<NavtexTransmitter> criteriaHelper = new CriteriaHelper<>(cb, query);

        if (onlyActive) {
            criteriaHelper.equals(transmitterRoot.get("active"), Boolean.TRUE);
        }

        if (StringUtils.isNotBlank(typeId)) {
            Join<NavtexTransmitter, PromulgationType> typeJoin = transmitterRoot.join("promulgationType", JoinType.LEFT);
            criteriaHelper.equals(typeJoin.get("typeId"), typeId);
        }

        if (areas != null && !areas.isEmpty()) {

            // Make sure we have managed entities, not template antities
            areas = persistedList(Area.class, areas);

            Join<NavtexTransmitter, Area> areaJoin = transmitterRoot.join("areas", JoinType.LEFT);
            Predicate[] areaMatch = areas.stream()
                    .map(a -> cb.equal(cb.locate(cb.literal(a.getLineage()), areaJoin.get("lineage")), 1))
                    .toArray(Predicate[]::new);
            criteriaHelper.add(cb.or(areaMatch));
        }

        query.select(transmitterRoot)
                .where(criteriaHelper.where())
                .orderBy(cb.asc(cb.lower(transmitterRoot.get("name"))));

        return em.createQuery(query).getResultList();
    }

    /** Returns all transmitters associated with the given NAVTEX promulgation type */
    public List<NavtexTransmitter> getTransmitters(String typeId) {
        return em.createNamedQuery("NavtexTransmitter.findByType", NavtexTransmitter.class)
                .setParameter("typeId", typeId)
                .getResultList();
    }

    /** Creates a new transmitter */
    @Transactional
    public NavtexTransmitter createTransmitter(NavtexTransmitter transmitter) {

        String typeId = transmitter.getPromulgationType().getTypeId();
        log.info("Creating transmitter " + transmitter.getName() + " for promulgation type " + typeId);

        // A name that differs from an existing one of the type only in case would make the lookup by name ambiguous
        boolean twin = !em.createNamedQuery("NavtexTransmitter.findByName", NavtexTransmitter.class)
                .setParameter("typeId", typeId)
                .setParameter("name", transmitter.getName())
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
        if (twin) {
            throw new IllegalStateException("A NAVTEX transmitter of type " + typeId + " is already named like '"
                    + transmitter.getName() + "'");
        }

        transmitter.setPromulgationType(promulgationTypeService.getPromulgationType(typeId));
        transmitter.setAreas(persistedList(Area.class, transmitter.getAreas()));
        return saveEntity(transmitter);
    }

    /** Updates an existing transmitter */
    @Transactional
    public NavtexTransmitter updateTransmitter(NavtexTransmitter transmitter) {

        String typeId = transmitter.getPromulgationType().getTypeId();
        log.info("Updating transmitter " + transmitter.getName() + " for promulgation type " + typeId);

        NavtexTransmitter original = findTransmitterByName(typeId, transmitter.getName());
        original.setActive(transmitter.isActive());
        original.setAreas(persistedList(Area.class, transmitter.getAreas()));
        return saveEntity(original);
    }

    /** Deletes an existing transmitter */
    @SuppressWarnings("all")
    @Transactional
    public boolean deleteTransmitter(String typeId, String name) {

        log.info("Deleting transmitter " + name + " for promulgation type " + typeId);

        NavtexTransmitter original = findTransmitterByName(typeId, name);
        if (original != null) {
            // Manually remove the transmitter from any references in NavtexMessagePromulgation
            List<NavtexMessagePromulgation> messages = findMessagesByTransmitter(original);
            for (NavtexMessagePromulgation message : messages) {
                message.getTransmitters().remove(original);
            }

            // Now, remove the transmitter
            remove(original);
            return true;
        }
        return false;
    }

    private List<NavtexMessagePromulgation> findMessagesByTransmitter(NavtexTransmitter transmitter) {
        return em.createQuery("SELECT m FROM NavtexMessagePromulgation m JOIN m.transmitters t WHERE t = :transmitter", NavtexMessagePromulgation.class)
                 .setParameter("transmitter", transmitter)
                 .getResultList();
    }
}

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
package org.niord.core.message;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static java.util.Arrays.asList;
import static org.niord.core.geojson.Feature.WGS84_SRID;
import static org.niord.core.message.MessageIdMatch.MatchType.SHORT_ID;
import static org.niord.core.message.MessageIdMatch.MatchType.TEXT;
import static org.niord.core.message.MessageIdMatch.MatchType.UID;
import static org.niord.core.message.MessageSearchParams.CommentsType.ANY;
import static org.niord.core.message.MessageSearchParams.CommentsType.ANY_UNACK;
import static org.niord.core.message.MessageSearchParams.CommentsType.OWN;
import static org.niord.core.message.MessageSearchParams.CommentsType.OWN_UNACK;
import static org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType.MANUAL;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.locationtech.jts.geom.Geometry;
import org.niord.core.NiordApp;
import org.niord.core.area.Area;
import org.niord.core.area.AreaService;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryService;
import org.niord.core.chart.Chart;
import org.niord.core.chart.ChartService;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.db.CriteriaHelper;
import org.niord.core.db.SpatialIntersectsPredicate;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.geojson.Feature;
import org.niord.core.geojson.FeatureCollection;
import org.niord.core.geojson.FeatureService;
import org.niord.core.geojson.JtsConverter;
import org.niord.core.mailinglist.MailingListStatusChangeOutbox;
import org.niord.core.message.MessageSearchParams.DateType;
import org.niord.core.message.MessageSearchParams.UserType;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.BaseMessagePromulgation;
import org.niord.core.promulgation.PromulgationManager;
import org.niord.core.promulgation.PromulgationType;
import org.niord.core.publication.PublicationService;
import org.niord.core.repo.RepositoryService;
import org.niord.core.service.BaseService;
import org.niord.core.settings.SettingsService;
import org.niord.core.user.Roles;
import org.niord.core.user.User;
import org.niord.core.user.UserService;
import org.niord.core.util.TextUtils;
import org.niord.model.DataFilter;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.message.AreaVo;
import org.niord.model.message.CategoryVo;
import org.niord.model.message.ChartVo;
import org.niord.model.message.MainType;
import org.niord.model.message.MessageVo;
import org.niord.model.message.ReferenceType;
import org.niord.model.message.Status;
import org.niord.model.search.PagedSearchParamsVo.SortOrder;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import jakarta.persistence.NonUniqueResultException;
import jakarta.persistence.NoResultException;
import jakarta.inject.Inject;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.Session;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Business interface for managing messages
 */
@Service
@SuppressWarnings("unused")
public class MessageService extends BaseService {

    /** Specifies which fields to update when adjusting a message **/
    public enum AdjustmentType {
        /** If auto-title is turned on, compute title from subjects and areas **/
        TITLE,

        /** If manual message series is chosen and a number is defined, compute the short ID **/
        SHORT_ID,

        /** If no areas is specifed but a geometry is defined, compute the areas **/
        AREAS
    }

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageService.class);

    @Inject
    ConnectionFactory connectionFactory;

    @Value("${niord.jms.topic.messagestatustopic:messageStatus}")
    String messageStatusTopic;

    @Inject
    UserService userService;

    @Inject
    MessageSeriesService messageSeriesService;

    @Inject
    MessageTagService messageTagService;

    @Inject
    MessageQueryService messageQueryService;

    @Inject
    PublicationService publicationService;

    @Inject
    SettingsService settingsService;

    @Inject
    AreaService areaService;

    @Inject
    CategoryService categoryService;

    @Inject
    ChartService chartService;

    @Inject
    RepositoryService repositoryService;

    @Inject
    DomainService domainService;

    @Inject
    FeatureService featureService;

    @Inject
    PromulgationManager promulgationManager;

    @Inject
    DictionaryService dictionaryService;

    @Inject
    NiordApp app;

    @Inject
    MailingListStatusChangeOutbox mailingListStatusChangeOutbox;

    /***************************************/
    /** Message Look-up                   **/
    /***************************************/

    /**
     * Returns the message with the given legacy id
     * <p>
     * The legacy id is not unique in the schema: when several messages have it (restored data), none is returned, as
     * when there is none, and a WARN names the legacy id.
     *
     * @param legacyId the id of the message
     * @return the message with the given id or null if not found
     * @throws jakarta.persistence.PersistenceException if the look-up fails: a failure is not reported as "not found"
     */
    public Message findByLegacyId(String legacyId) {
        try {
            return em.createNamedQuery("Message.findByLegacyId", Message.class)
                    .setParameter("legacyId", legacyId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (NonUniqueResultException e) {
            log.warn("Several messages have the legacy id '{}'; the look-up by legacy id answers none of them",
                    legacyId);
            return null;
        }
    }

    /**
     * Returns the messages with the given short ID.
     *
     * @param shortId the short ID of the messages
     * @return the messages with the given short ID
     */
    public List<Message> findByShortId(String shortId) {
        return em.createNamedQuery("Message.findByShortId", Message.class)
                .setParameter("shortId", shortId)
                .getResultList();
    }

    /**
     * Resolves the "best" message with the given message id, which may be either a UID,
     * or a short ID of a message.
     * If the are multiple matching messages, priority is given to a messages of the current domain.
     *
     * @param messageId the message id to resolve
     * @return the matching message or null if none is found
     */
    public Message resolveMessage(String messageId) {
        // Sanity check
        if (StringUtils.isBlank(messageId)) {
            return null;

        }

        Message message = messageQueryService.findByUid(messageId);
        if (message != null) {
            return message;
        }

        // Check if the message ID is a short ID
        List<Message> messages = findByShortId(messageId);
        if (!messages.isEmpty()) {
            return resolveMessage(messages);
        }

        // No joy
        return null;
    }

    /** Returns a single message from the list - preferably from the current domain */
    private Message resolveMessage(List<Message> messages) {
        // Sort messages by domain and status
        messages.sort(new MessageIdMatchComparator(domainService.currentDomain()));

        // No current domain - just return the first message
        return messages.get(0);
    }

    /**
     * Returns the list of referenced messages, optionally with the given reference type and status
     *
     * @param message the message to return referenced messages for
     * @param referenceTypes optionally, the reference types
     * @param status optionally, the status of the referenced messages
     * @return the list of referenced messages
     */
    public List<Message> getReferencedMessages(Message message, Set<ReferenceType> referenceTypes, Status status) {
        return message.getReferences().stream()
                .filter(ref -> referenceTypes == null || referenceTypes.isEmpty() || referenceTypes.contains(ref.getType()))
                .map(ref -> resolveMessage(ref.getMessageId()))
                .filter(Objects::nonNull)
                .filter(msg -> status == null || status == msg.getStatus())
                .collect(Collectors.toList());
    }

    /**
     * Returns a list of message IDs (UID or shortId) that - possibly partially - matches
     * real text.
     *
     * @param lang the language to return the title in
     * @param txt the text to match
     * @param maxGroupCount the max number of matching message IDs to return.
     * @param includeText whether to include the search text as a match
     * @param includeDeleted whether to include deleted messages in the result
     * @param publicOnly whether to match only publicly visible messages ({@link Status#isPublic()}):
     *                   set for a caller that may not see drafts, and then overrides includeDeleted
     * @return the search result
     */
    public List<MessageIdMatch> searchMessageIds(String lang, String txt, int maxGroupCount, boolean includeText,
                                                 boolean includeDeleted, boolean publicOnly) {
        List<MessageIdMatch> result = new ArrayList<>();
        if (StringUtils.isBlank(txt)) {
            return result;
        }

        // First priority is the text itself - if requested
        if (includeText) {
            result.add(new MessageIdMatch(txt, TEXT, null));
        }

        // Check for a matching UID. A match carries the title and status, so a caller limited to
        // public messages only learns of a uid that belongs to one.
        Message message = messageQueryService.findByUid(txt);
        if (message != null && (!publicOnly || (message.getStatus() != null && message.getStatus().isPublic()))) {
            result.add(new MessageIdMatch(txt, UID, message, lang));
        }

        // Search shortIds. The text is data, not a pattern: its LIKE wildcards are escaped, so a
        // lone "%" or "_" matches only a short id that contains that character.
        String searchShortIdSql = "select distinct m from Message m where lower(m.shortId) like lower(:term) escape '!' ";
        if (publicOnly) {
            searchShortIdSql += "and m.status in (:publicStatuses) ";
        } else if (!includeDeleted) {
            searchShortIdSql += "and m.status != 'DELETED' ";
        }
        TypedQuery<Message> shortIdQuery = em.createQuery(searchShortIdSql, Message.class)
                .setParameter("term", "%" + TextUtils.escapeLike(txt) + "%");
        if (publicOnly) {
            shortIdQuery.setParameter("publicStatuses",
                    Arrays.stream(Status.values()).filter(Status::isPublic).toList());
        }
        shortIdQuery
                .setMaxResults(maxGroupCount)
                .getResultList()
                .stream()
                .sorted(Comparator.comparingInt((Message m) -> StringUtils.indexOfIgnoreCase(m.getShortId(), txt))
                        .thenComparing(Message::getUpdated))
                .forEach(m -> result.add(new MessageIdMatch(m.getShortId(), SHORT_ID, m, lang)));

        return  result;
    }

    /***************************************/
    /** Message Persistence               **/
    /***************************************/

    /**
     * Saves the message
     *
     * @param message the message to save
     * @return the saved message
     */
    public Message saveMessage(Message message) {
        boolean wasPersisted = message.isPersisted();

        // Please see comment on Message.onPersist()
        message.onPersist();

        // Save the message
        message = saveEntity(message);

        // Save a MessageHistory entity for the message
        saveHistory(message);

        return message;
    }

    /**
     * Creates a new message as a draft message
     *
     * @param message the template for the message to create
     * @return the new message
     */
    @Transactional
    public Message createMessage(Message message) throws Exception {

        // Validate the message
        if (message.isPersisted()) {
            throw new Exception("Message already persisted");
        }

        // Register who originally created the message
        message.setCreatedBy(userService.currentUser());

        // Validate various required fields
        if (message.getMessageSeries() == null) {
            throw new Exception("Message series not specified");
        }

        // Substitute the message series with the persisted on
        message.setMessageSeries(messageSeriesService.findBySeriesId(message.getMessageSeries().getSeriesId()));

        if (message.getType() != null) {
            if (!message.getMessageSeries().validType(message.getType())) {
                throw new Exception("Invalid type for message " + message.getType());
            }
            message.setMainType(message.getType().getMainType());
        }
        if (message.getMainType() != null && message.getMainType() != message.getMessageSeries().getMainType()) {
            throw new Exception("Invalid main-type for message " + message.getMainType());
        }

        // Default and validate the message kind - system-managed kinds only exist for NW messages
        if (message.getKind() == null) {
            message.setKind(MessageKind.WARNING);
        }
        if (message.getKind() != MessageKind.WARNING && message.getMainType() != MainType.NW) {
            throw new Exception("Message kind " + message.getKind() + " is only valid for NW messages");
        }

        // Set default status
        if (message.getStatus() == null) {
            message.setStatus(Status.DRAFT);
        }

        // Remove empty geometries
        message.getParts().stream()
                .filter(p -> p.getGeometry() != null && p.getGeometry().getFeatures().isEmpty())
                .forEach(p -> p.setGeometry(null));

        // Substitute the Area with a persisted one
        message.setAreas(persistedList(Area.class, message.getAreas()));
        message.setAreaSortOrder(areaService.computeMessageAreaSortingOrder(message));

        // Substitute the Categories with the persisted ones
        message.setCategories(persistedList(Category.class, message.getCategories()));

        // Substitute the Charts with the persisted ones
        message.setCharts(chartService.persistedCharts(message.getCharts()));

        // The year of the publish date in the zone of the series' domain; the save callback keeps it
        if (message.getPublishDateFrom() != null) {
            message.checkUpdateYear(messageSeriesService.seriesZone(message.getMessageSeries()));
        }

        // Let promulgation services update the message promulgation data
        promulgationManager.onCreateMessage(message);

        // Persist the message
        message = saveMessage(message);
        log.info("Saved message " + message.getUid());

        em.flush();
        return message;
    }

    /**
     * Updates the given message.
     * <p>
     * Important: this function can not be used to change the status of the message.
     * For that, call {@code MessageService.updateStatus()}
     *
     * @param message the template for the message to update
     * @return the updated message
     */
    @Transactional
    public Message updateMessage(Message message) throws Exception {

        Message original = messageQueryService.findByUid(message.getUid());

        // Validate the message
        if (original == null) {
            throw new Exception("Message not an existing message");
        }

        // System-managed messages (e.g. auto-created cancellation warnings) are read-only.
        // Status changes still flow through updateStatus(); only content edits are rejected.
        if (original.getKind() != MessageKind.WARNING) {
            throw new Exception("Message " + original.getUid() + " of kind " + original.getKind() + " cannot be edited");
        }

        // Register who last updated he message
        original.setLastUpdatedBy(userService.currentUser());

        original.setRevision(message.getRevision());

        // Validate various required fields
        if (message.getMessageSeries() == null) {
            throw new Exception("Message series not specified");
        }

        // Check that there is no attempt to update the status
        if (message.getStatus() != original.getStatus()) {
            throw new Exception("updateMessage() cannot change status");
        }

        original.setMessageSeries(messageSeriesService.findBySeriesId(message.getMessageSeries().getSeriesId()));

        if (message.getType() != null) {
            if (!original.getMessageSeries().validType(message.getType())) {
                throw new Exception("Invalid type for message " + message.getType());
            }
            message.setMainType(message.getType().getMainType());
        }

        original.setNumber(message.getNumber());
        original.setShortId(message.getShortId());
        original.setType(message.getType());
        original.setMainType(message.getMainType());
        original.setThumbnailPath(message.getThumbnailPath());

        if (original.getMainType() != null && original.getMainType() != original.getMessageSeries().getMainType()) {
            throw new Exception("Invalid main-type for message " + original.getMainType());
        }

        // If a verified message is updated, it will become draft again
        if (original.getStatus() == Status.VERIFIED) {
            original.setStatus(Status.DRAFT);
        }

        original.setHorizontalDatum(message.getHorizontalDatum());

        // Substitute the Area with a persisted one
        original.setAreas(persistedList(Area.class, message.getAreas()));

        // Substitute the Categories with the persisted ones
        original.setCategories(persistedList(Category.class, message.getCategories()));

        // Substitute the Charts with the persisted ones
        original.setCharts(chartService.persistedCharts(message.getCharts()));

        Date formerPublishDateFrom = original.getPublishDateFrom();
        original.setPublishDateFrom(message.getPublishDateFrom());
        original.setPublishDateTo(message.getPublishDateTo());
        original.setFollowUpDate(message.getFollowUpDate());

        // A changed publish date gives the year again, read in the zone of the series' domain; the save callback
        // keeps it (it would read the JVM's zone)
        if (original.getPublishDateFrom() != null && (original.year == null || formerPublishDateFrom == null
                || formerPublishDateFrom.getTime() != original.getPublishDateFrom().getTime())) {
            original.checkUpdateYear(messageSeriesService.seriesZone(original.getMessageSeries()));
        }

        original.getReferences().clear();
        message.getReferences().stream()
                .map(this::updateReference)
                .filter(Objects::nonNull)
                .forEach(original::addReference);

        original.setOriginalInformation(message.getOriginalInformation());

        original.getParts().clear();
        message.getParts().stream()
                .map(this::updateMessagePart)
                .filter(Objects::nonNull)
                .forEach(original::addPart);
        original.getParts().removeIf(part -> !part.partDefined());

        // Copy the localized description data
        original.copyDescsAndRemoveBlanks(message.getDescs());
        original.setAutoTitle(message.isAutoTitle());

        original.getAttachments().clear();
        message.getAttachments().stream()
                .map(this::updateAttachment)
                .filter(Objects::nonNull)
                .forEach(original::addAttachment);

        original.setSeparatePage(message.getSeparatePage());

        // For non-published messages, compute the area sort order based on associated area and message part geometry
        if (original.getStatus().isDraft()) {
            original.setAreaSortOrder(areaService.computeMessageAreaSortingOrder(original));
        }

        // Let promulgation services update the message promulgation data
        promulgationManager.onUpdateMessage(message);
        original.getPromulgations().clear();
        message.getPromulgations().stream()
                .map(this::updatePromulgation)
                .filter(Objects::nonNull)
                .forEach(original::addPromulgation);

        // Persist the message
        saveMessage(original);
        log.info("Updated message " + original);

        em.flush();
        return original;
    }

    /** Called upon saving a message. Updates the message part **/
    private MessagePart updateMessagePart(MessagePart part) {

        part.setGeometry(featureService.updateFeatureCollection(part.getGeometry()));

        if (part.isNew()) {
            return part;
        }
        MessagePart original = getByPrimaryKey(MessagePart.class, part.getId());
        if (original != null) {
            original.updateMessagePart(part);
        }
        return original;
    }

    /** Upon saving a message, the attachment captions may have been updated **/
    private Attachment updateAttachment(Attachment attachment) {
        if (attachment.isNew()) {
            return attachment;
        }
        Attachment original = getByPrimaryKey(Attachment.class, attachment.getId());
        if (original != null) {
            original.updateAttachment(attachment);
        }
        return original;
    }

    /** Called upon saving a message. Updates the reference **/
    private Reference updateReference(Reference reference) {
        if (reference.isNew()) {
            return reference;
        }
        Reference original = getByPrimaryKey(Reference.class, reference.getId());
        if (original != null) {
            original.updateReference(reference);
        }
        return original;
    }

    /** Called upon saving a message. Updates the date interval **/
    private DateInterval updateDateInterval(DateInterval dateInterval) {
        if (dateInterval.isNew()) {
            return dateInterval;
        }
        DateInterval original = getByPrimaryKey(DateInterval.class, dateInterval.getId());
        if (original != null) {
            original.updateDateInterval(dateInterval);
        }
        return original;
    }

    /** Called upon saving a message. Updates the promulgation **/
    private BaseMessagePromulgation updatePromulgation(BaseMessagePromulgation promulgation) {
        if (promulgation.isNew()) {
            return promulgation;
        }
        BaseMessagePromulgation original = getByPrimaryKey(promulgation.getClass(), promulgation.getId());
        if (original != null) {
            original.update(promulgation);
        }
        return original;
    }

    /**
     * Updates the status of the given message
     * <p>
     * The change rolls back for any exception, checked ones too (a country's promulgation service may throw its
     * declared {@code PromulgationException}): joined to a caller's transaction it marks that transaction
     * rollback-only, so nothing of a failed change is committed.
     *
     * @param uid the UID of the message
     * @param status    the status
     * @throws IllegalArgumentException when no status is given (400 over REST)
     * @throws IllegalStateException when the message's status cannot change to the given one (409 over REST)
     */
    @Transactional(rollbackFor = Exception.class)
    public Message updateStatus(String uid, Status status) throws Exception {
        Date now = new Date();
        Message message = messageQueryService.findByUid(uid);
        Status prevStatus = message.getStatus();

        if (status == null) {
            throw new IllegalArgumentException("No status to change message " + statusLabel(message) + " to");
        }

        // Check that a valid status transition is requested
        if (!getValidStatusTransitions(prevStatus).contains(status)) {
            throw new IllegalStateException("Invalid status transition " + prevStatus + " -> " + status
                    + " for message " + statusLabel(message));
        }

        // Register who last updated he message
        message.setLastUpdatedBy(userService.currentUser());

        // Update the status
        message.setStatus(status);

        // When published, update dates and the message series
        if (prevStatus.isDraft() && status == Status.PUBLISHED) {

            // Update the publish date needs updating
            if (message.getPublishDateFrom() == null || message.getPublishDateFrom().after(now)) {
                message.setPublishDateFrom(now);
                message.checkUpdateYear();
            }

            // If no event dates are defined, add event dates based on publish start date
            message.checkEventDateIntervalsUponPublishStart();

            // Assign a new message number and short ID
            messageSeriesService.updateMessageIdsFromMessageSeries(message, true);

        } else if (status == Status.CANCELLED || status == Status.EXPIRED) {

            // Update the publish date needs updating
            if (message.getPublishDateTo() == null || message.getPublishDateTo().before(now)) {
                message.setPublishDateTo(now);
            }

            // Update or remove open-ended event date intervals based on the publish end date.
            message.checkEventDateIntervalsUponPublishEnd();
        }

        // Add or remove the message from any message-recording publication message tags
        publicationService.updateRecordingPublications(message, prevStatus);

        // Let promulgation services update the message promulgation data
        promulgationManager.onUpdateMessageStatus(message);

        message = saveMessage(message);

        em.flush();
        
        // Broadcast the status change to any listener
        sendStatusUpdate(message, prevStatus);
        
        return message;
    }

    /** How a refused status change names its message: the short id when it has one, otherwise the UID */
    private static String statusLabel(Message message) {
        return StringUtils.isNotBlank(message.getShortId()) ? message.getShortId() : message.getUid();
    }

    /**
     * Defines the set of valid status transitions from the given status
     * @param status the status to transition from
     * @return the set of valid status transitions
     */
    private Set<Status> getValidStatusTransitions(Status status) {
        return switch (status) {
            case DRAFT -> new HashSet<>(asList(Status.VERIFIED, Status.DELETED));
            case VERIFIED -> new HashSet<>(asList(Status.PUBLISHED, Status.DRAFT, Status.DELETED));
            case PUBLISHED -> new HashSet<>(asList(Status.EXPIRED, Status.CANCELLED));
            default -> Collections.emptySet();
        };
    }

    /**
     * Broadcasts a JMS message to indicate that the message status has changed.
     * <p>
     * The broadcast is deferred until <em>after</em> the surrounding transaction commits, so
     * listeners that react by re-reading the message from the database (mailing-list, Twitter,
     * SECOM) always observe the committed status change. Sending it inline - before the commit -
     * let the event overtake the commit, so listeners could read a stale or absent row; the
     * previous workaround for that was a fixed 10s sleep in the mailing-list listener. As a
     * side benefit, the event is no longer sent at all if the transaction rolls back.
     * <p>
     * The topic is not stored, so a restart, a broker error or a listener subscribing again can
     * lose the event. The mailing lists' duty is therefore recorded first, in this transaction: a
     * row of {@link MailingListStatusChangeOutbox}, which commits or rolls back with the status
     * change, and whose failure fails it. For the mailing lists the broadcast is only the prompt to
     * take that row at once; their listener also takes it once a minute and at start.
     * @param message the message
     * @param prevStatus the previous status
     */
    private void sendStatusUpdate(Message message, Status prevStatus) {
        mailingListStatusChangeOutbox.enqueue(message.getUid(), message.getStatus());

        // Snapshot the body while the entity is still attached...
        Map<String, Object> body = new HashMap<>();
        body.put("ID", message.getId());
        body.put("UID", message.getUid());
        body.put("STATUS", message.getStatus().name());
        body.put("PREV_STATUS", prevStatus.name());

        // ...but only broadcast once the transaction has committed. If there is no active
        // transaction, send immediately (preserving the previous behaviour for that case).
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    broadcastStatusUpdate(body);
                }
            });
        } else {
            broadcastStatusUpdate(body);
        }
    }

    /** Sends the pre-built status-change message body to the JMS status topic */
    private void broadcastStatusUpdate(Map<String, Object> body) {
        try (JMSContext jmsContext = connectionFactory.createContext(Session.AUTO_ACKNOWLEDGE)){
            jmsContext.createProducer().send(jmsContext.createTopic(messageStatusTopic), body);
        } catch (Exception e) {
            log.error("Failed sending JMS (the mailing lists' mails are still made from their outbox): " + e, e);
        }
    }

    /**
     * Cancels the given published message. For an NW warning of an auto-numbered series, this
     * always also auto-creates and publishes a cancellation warning (the S-124 "cancellation NW",
     * cf. S-124 Ed. 2.0.0 section 9.3 method 2): a new, born-published message in the same series,
     * taking the next warning number, whose entire content is a single CANCELLATION reference to
     * the cancelled message. Not overridable - neither per message nor per series.
     * <p>
     * The cancellation warning is created in the same transaction as the status change: if its
     * creation fails, with a checked exception too, the cancellation of the source message rolls
     * back. The JMS status events of both are sent only after the commit, as for every
     * status change (see {@link #sendStatusUpdate}), so a rolled-back cancellation broadcasts
     * nothing.
     * <p>
     * When a cancellation warning will be issued, the series' number for it is locked first, before
     * the cancelled message and any recording publication's tag are changed: a publication takes its
     * number first too, so a cancellation and a publication in one series take turns on the number
     * instead of each waiting for a row the other holds.
     *
     * @param uid the UID of the message to cancel
     * @return the cancelled message
     */
    @Transactional(rollbackFor = Exception.class)
    public Message cancelMessage(String uid) throws Exception {
        Message stored = messageQueryService.findByUid(uid);
        if (stored != null && stored.getStatus() == Status.PUBLISHED && willIssueCancellationWarning(stored)) {
            messageSeriesService.lockMessageNumber(stored.getMessageSeries(), new Date());
        }

        Message cancelled = updateStatus(uid, Status.CANCELLED);

        if (issueCancellationWarning(cancelled)) {
            createCancellationWarning(cancelled);
        }

        return cancelled;
    }

    /**
     * Returns if cancelling the given message would issue an auto-created cancellation warning:
     * the message must be an ordinary editorial message - never a cancellation-of-a-cancellation
     * (the terminal rule) - in an auto-numbered NW series, since the cancellation warning is an NW
     * message taking the next number in the series. There is no opt-out: no per-message request
     * and no per-series flag, and {@code niord.s124.enabled} does not change it either (decision 2
     * of {@code be/docs/s124-cancellation-plan.md}, the owner 2026-11-01).
     * <p>
     * Pure rule with no diagnostics - besides gating the minting in {@link #cancelMessage}, it
     * drives the editor's "a cancellation warning will be issued" note (via the editor-only
     * {@code autoCancellationWarning} field of the system message VO).
     *
     * @param message the message to check
     * @return if cancelling the message would issue a cancellation warning
     */
    public boolean willIssueCancellationWarning(Message message) {
        MessageSeries series = message.getMessageSeries();
        return series != null
                && message.getKind() == MessageKind.WARNING
                && message.getMainType() == MainType.NW
                && series.isAutoNumbered();
    }

    /**
     * Cancel-time variant of {@link #willIssueCancellationWarning}: same rule, but logs why an NW
     * message - the only kind of message cancellation warnings apply to - nonetheless skips
     * minting (the terminal rule, or a series that cannot number the cancellation warning).
     *
     * @param message the message being cancelled
     * @return if a cancellation warning should be issued for the message
     */
    boolean issueCancellationWarning(Message message) {
        if (willIssueCancellationWarning(message)) {
            return true;
        }
        MessageSeries series = message.getMessageSeries();
        if (series != null && message.getMainType() == MainType.NW) {
            if (message.getKind() != MessageKind.WARNING) {
                log.info("Not issuing a cancellation warning for message " + message.getUid()
                        + " of kind " + message.getKind());
            } else {
                log.info("Not issuing a cancellation warning for message " + message.getUid()
                        + ": series " + series.getSeriesId() + " is not auto-numbered");
            }
        }
        return false;
    }

    /**
     * Creates and publishes the cancellation warning for the given cancelled source message.
     * The message is persisted like any editor-created message and then taken directly to the
     * PUBLISHED state, mirroring the publish steps of {@link #updateStatus}: it never passes
     * through the editorial workflow.
     *
     * @param source the cancelled message to issue a cancellation warning for
     * @return the published cancellation warning
     */
    private Message createCancellationWarning(Message source) throws Exception {
        Message cancellation = createMessage(composeCancellationWarning(source));

        // Mirror the publish branch of updateStatus() - the message is born published
        cancellation.checkUpdateYear();
        messageSeriesService.updateMessageIdsFromMessageSeries(cancellation, true);

        // Keep the cancellation warning sorted alongside its geometry-carrying source
        cancellation.setAreaSortOrder(source.getAreaSortOrder());

        publicationService.updateRecordingPublications(cancellation, Status.VERIFIED);
        promulgationManager.onUpdateMessageStatus(cancellation);

        cancellation = saveMessage(cancellation);

        em.flush();

        log.info("Created cancellation warning " + cancellation.getUid() + " for message " + source.getUid());

        // Broadcast the publication (SECOM datasets, mailing lists, ...) as a VERIFIED -> PUBLISHED transition
        sendStatusUpdate(cancellation, Status.VERIFIED);

        return cancellation;
    }

    /**
     * Builds the un-persisted cancellation-warning template for the given cancelled source message:
     * same scope (type), series, areas, categories and charts as the source, born PUBLISHED with no
     * message parts, and a single CANCELLATION reference to the source carrying the cancellation
     * semantics (rendered with referenceCategory "Warning Cancellation" in the S-124 GML).
     *
     * @param source the cancelled message to compose a cancellation warning for
     * @return the un-persisted cancellation warning template
     */
    Message composeCancellationWarning(Message source) {
        Message cancellation = new Message();
        cancellation.setKind(MessageKind.CANCELLATION);
        cancellation.setMessageSeries(source.getMessageSeries());
        cancellation.setMainType(source.getMainType());
        cancellation.setType(source.getType());
        cancellation.setStatus(Status.PUBLISHED);
        cancellation.setPublishDateFrom(new Date());
        // publishDateTo stays undefined: the cancellation warning is in force until manually retired
        cancellation.setAreas(new ArrayList<>(source.getAreas()));
        cancellation.setCategories(new ArrayList<>(source.getCategories()));
        cancellation.setCharts(new ArrayList<>(source.getCharts()));
        cancellation.setOriginalInformation(true);
        cancellation.setAutoTitle(false);

        Reference reference = new Reference();
        reference.setMessageId(source.getUid());
        reference.setType(ReferenceType.CANCELLATION);
        cancellation.addReference(reference);

        // The message has no parts for the auto-title to compose a title from - set explicit titles
        String sourceId = StringUtils.defaultIfBlank(source.getShortId(), source.getUid());
        for (String lang : app.getLanguages()) {
            cancellation.checkCreateDesc(lang).setTitle(cancellationTitle(lang, sourceId));
        }

        return cancellation;
    }

    /**
     * Resolves the localized "Cancellation of {0}" title of a cancellation warning from the
     * "message" dictionary, falling back to English and finally to a fixed text.
     *
     * @param lang the language to resolve the title in
     * @param sourceId the short ID (or UID) of the cancelled message
     * @return the title of the cancellation warning
     */
    private String cancellationTitle(String lang, String sourceId) {
        String title = dictionaryService.value("message", lang, "msg.cancellation.title");
        if (StringUtils.isBlank(title)) {
            title = dictionaryService.value("message", "en", "msg.cancellation.title");
        }
        if (StringUtils.isBlank(title)) {
            title = "Cancellation of {0}";
        }
        return MessageFormat.format(title, sourceId);
    }

    /**
     * Creates a new un-persisted draft message template
     * @return a new message template
     */
    public Message newTemplateMessage(MainType mainType) {

        Message message = new Message();
        message.assignNewUid();
        message.setMainType(mainType);
        message.setStatus(Status.DRAFT);
        message.setAutoTitle(true);
        if (mainType == MainType.NM) {
            message.setOriginalInformation(true);
        }

        return  message;
    }

    /**
     * Computes the auto-generated message fields for the given message template
     *
     * @param message the message template to compute auto-generated message fields for
     * @return the updated message template
     * @noinspection all
     */
    public Message updateAutoMessageFields(Message message) {
        if (message.isAutoTitle()) {
            message.setAreas(persistedList(Area.class, message.getAreas()));
        }
        message.updateAutoMessageFields();
        return message;
    }

    /**
     * If the associated message series is of type MANUEL with a defined shortFormat and the message
     * has an associated number, then the shortId of the message is updated.
     * @param message the message to update shortId for
     * @return the update message
     * @noinspection all
     */
    public Message checkUpdateShortId(Message message) {
        if (message.getMessageSeries() != null) {
            message.setMessageSeries(messageSeriesService.findBySeriesId(message.getMessageSeries().getSeriesId()));
            if (message.getNumber() != null &&
                    message.getMessageSeries().getNumberSequenceType() == MANUAL &&
                    StringUtils.isNotBlank(message.getMessageSeries().getShortFormat())) {
                if (message.getPublishDateFrom() == null) {
                    message.setPublishDateFrom(new Date());
                }
                messageSeriesService.updateMessageIdsFromMessageSeries(message, false);
            }
        }
        return message;
    }

    /**
     * Utility method used to update the auto-generated fields of a message
     * @param message the message to update
     * @return the updated message
     * @noinspection all
     */
    public SystemMessageVo adjustMessage(SystemMessageVo message, AdjustmentType... types) {

        Set<AdjustmentType> typeSet = new HashSet<>();
        if (types != null) {
            typeSet.addAll(Arrays.asList(types));
        }

        Message msg = new Message(message);

        if (typeSet.isEmpty() || typeSet.contains(AdjustmentType.AREAS)) {
            if (msg.getAreas().isEmpty()) {
                FeatureCollectionVo fc = msg.toFeatureCollection();
                if (fc.getFeatures() != null && fc.getFeatures().length > 0) {
                    Geometry geometry = JtsConverter.toJts(fc.toGeometry());
                    List<Area> areas = areaService.getIntersectingAreas(geometry, 2, true);
                    message.setAreas(areas.stream()
                        .map(a -> a.toVo(AreaVo.class, DataFilter.get().defaultLanguage(app.getDefaultLanguage())))
                        .collect(Collectors.toList()));
                }
            }
        }

        if (typeSet.isEmpty() || typeSet.contains(AdjustmentType.SHORT_ID)) {
            checkUpdateShortId(msg);
            message.setShortId(msg.getShortId());
        }

        if (typeSet.isEmpty() || typeSet.contains(AdjustmentType.TITLE)) {
            updateAutoMessageFields(msg);
            message.setDescs(msg.getDescs().stream()
                    .map(d -> d.toVo(DataFilter.get().defaultLanguage(app.getDefaultLanguage())))
                    .collect(Collectors.toList()));
        }

        updateAutoMessageFields(msg);
        return message;
    }

    /**
     * Replace the related based data with fresh version containing all descriptor entities
     * @param message the message whose base data to update
     * @return the updated message
     */
    public SystemMessageVo updateBaseDate(SystemMessageVo message) {
        Message msg = new Message(message);
        msg.setAreas(persistedList(Area.class, msg.getAreas()));
        msg.setCategories(persistedList(Category.class, msg.getCategories()));
        msg.setCharts(chartService.persistedCharts(msg.getCharts()));

        // Copy back using an all-language data filter
        DataFilter filter = Message.MESSAGE_DETAILS_FILTER;
        message.setCategories(msg.getCategories().stream()
            .map(c -> c.toVo(CategoryVo.class, filter))
            .collect(Collectors.toList()));
        message.setAreas(msg.getAreas().stream()
                .map(a -> a.toVo(AreaVo.class, filter))
                .collect(Collectors.toList()));
        message.setCharts(msg.getCharts().stream()
                .map(c -> c.toVo(ChartVo.class, filter))
                .collect(Collectors.toList()));

        return message;
    }

    /***************************************/
    /** Message Sorting                  **/
    /***************************************/

    /**
     * Generate a sorting order for the message within its associated area.
     * The area-sort value is based on the message center latitude and longitude, and the sorting type for
     * its first associated area.
     */
    public void computeMessageAreaSortingOrder(Message message) {
        double areaSortOrder = areaService.computeMessageAreaSortingOrder(message);
        message.setAreaSortOrder(areaSortOrder);
        saveMessage(message);
    }

    /**
     * Changes the area sort order of a message to position it between two other messages
     * @param uid the UID of the message to reorder
     * @param afterUid if defined, the message must come after this message
     * @param beforeUid if defined, the message must come before this message
     */
    public void changeAreaSortOrder(String uid, String afterUid, String beforeUid) {
        Message m = messageQueryService.findByUid(uid);
        Message am = afterUid == null ? null : messageQueryService.findByUid(afterUid);
        Message bm = beforeUid == null ? null : messageQueryService.findByUid(beforeUid);

        if (m == null || (am == null && bm == null)) {
            throw new IllegalArgumentException("Invalid arguments uid=" + uid
                    + ", afterUid=" + afterUid + ", beforeUid=" + beforeUid);
        }

        if (am == null) {
            m.setAreaSortOrder(bm.getAreaSortOrder() - 1);
            log.info("Changed area sort order of " + uid + ": moved before " + beforeUid);
        } else if (bm == null) {
            m.setAreaSortOrder(am.getAreaSortOrder() + 1);
            log.info("Changed area sort order of " + uid + ": moved after " + afterUid);
        } else {
            m.setAreaSortOrder((am.getAreaSortOrder() + bm.getAreaSortOrder()) / 2.0);
            log.info("Changed area sort order of " + uid + ": moved between " + afterUid + " and " + beforeUid);
        }
    }

    /**
     * From the given list of Message UIDs, return the UIDs of the message with the separatePage flag set.
     * Used when printing messages (generating PDFs)
     * @param uids the UIDs of the messages to search
     * @return the UIDs of the message with the separatePage flag set
     */
    public Set<String> getSeparatePageUids(Set<String> uids) {
        if (uids == null || uids.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(
                em.createNamedQuery("Message.separatePageUids", String.class)
                .setParameter("uids", uids)
                .getResultList());
    }

    /***************************************/
    /** Message History                   **/
    /***************************************/

    /**
     * Saves a history entity containing a snapshot of the message
     *
     * @param message the message to save a snapshot for
     */
    @Transactional
    public void saveHistory(Message message) {

        try {
            MessageHistory hist = new MessageHistory();
            hist.setMessage(message);
            hist.setUser(userService.currentUser());
            hist.setStatus(message.getStatus());
            hist.setCreated(new Date());
            hist.setVersion(message.getVersion() + 1);

            // Create a snapshot of the message
            ObjectMapper jsonMapper = JsonMapper.builderWithJackson2Defaults()
                    .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false) // Use ISO-8601 format
                    .build();
            DataFilter dataFilter = DataFilter.get().defaultLanguage(app.getDefaultLanguage())
                    .fields("Message.details", "Message.geometry", "Message.promulgations");
            MessageVo snapshot = message.toVo(SystemMessageVo.class, dataFilter);
            hist.compressSnapshot(jsonMapper.writeValueAsString(snapshot));

            saveEntity(hist);

        } catch (Exception e) {
            log.error("Error saving a history entry for message " + message.getId(), e);
            // NB: Don't propagate errors
        }
    }

    /**
     * Returns the message history for the given message ID
     *
     * @param messageId the message ID
     * @return the message history
     */
    public List<MessageHistory> getMessageHistory(Integer messageId) {
        return em.createNamedQuery("MessageHistory.findByMessageId", MessageHistory.class)
                .setParameter("messageId", messageId)
                .getResultList();
    }

    /**
     * Returns the messages most recently edited by the current user
     * within the last month
     *
     * @param maxMessageNo max number of message to return
     * @return the messages most recently edited by the current user
     */
    public List<Message> getMostRecentlyEditedMessages(int maxMessageNo, Status... statuses) {
        User user = userService.currentUser();
        Domain domain = domainService.currentDomain();
        if (user == null || domain == null || domain.getMessageSeries().isEmpty()) {
            return Collections.emptyList();
        }
        // Only check last month
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MONTH, -1);

        Set<Status> statusSet = new HashSet<>();
        if (statuses != null && statuses.length > 0) {
            statusSet.addAll(Arrays.asList(statuses));
        } else {
            statusSet.addAll(Arrays.asList(Status.values()));
        }

        return em.createNamedQuery("MessageHistory.findRecentChangesByUser", MessageHistory.class)
                .setParameter("user", user)
                .setParameter("date", cal.getTime())
                .setParameter("messageSeries", domain.getMessageSeries())
                .setParameter("statuses", statusSet)
                .getResultList()
                .stream()
                .map(MessageHistory::getMessage)
                .distinct()
                .limit(maxMessageNo)
                .collect(Collectors.toList());
    }

    /**
     * Returns the draft messages most recently edited by the current user
     * within the last month
     *
     * @param maxMessageNo max number of draft message to return
     * @return the draft messages most recently edited by the current user
     */
    public List<Message> getMostRecentlyEditedDrafts(int maxMessageNo) {
        return getMostRecentlyEditedMessages(maxMessageNo, Status.DRAFT, Status.VERIFIED);
    }

    /***************************************/
    /** Repo methods                      **/
    /***************************************/

    /**
     * Creates a temporary repository folder for the given message
     * @param copyToTemp whether to copy all message resources to the associated temporary directory or not
     * @param message the message
     */
    public void createTempMessageRepoFolder(SystemMessageVo message, boolean copyToTemp) throws IOException {
        repositoryService.createTempEditRepoFolder(message, copyToTemp);
    }

    /**
     * Update the message repository folder from a temporary repository folder used whilst editing the message
     * @param message the message
     */
    public void updateMessageFromTempRepoFolder(SystemMessageVo message) throws IOException {
        repositoryService.updateRepoFolderFromTempEditFolder(message);
    }
}

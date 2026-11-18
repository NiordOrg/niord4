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

import freemarker.template.TemplateNotFoundException;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.geojson.FeatureService;
import org.niord.core.geojson.GeometryFormatService;
import org.niord.core.geojson.PlainTextConverter;
import org.niord.core.message.*;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.vo.MessageHistoryVo;
import org.niord.core.message.vo.MessagePublicationVo;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.PromulgationManager;
import org.niord.core.publication.Publication;
import org.niord.core.publication.PublicationResolver;
import org.niord.core.publication.PublicationService;
import org.niord.core.publication.PublicationUtils;
import org.niord.core.publication.vo.SystemPublicationVo;
import org.niord.core.repo.FileTypes;
import org.niord.core.repo.RepositoryService;
import org.niord.core.user.CurrentUserContext;
import org.niord.core.user.Roles;
import org.niord.core.user.UserService;
import org.niord.core.util.WebUtils;
import org.niord.model.DataFilter;
import org.niord.model.IJsonSerializable;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.GeoJsonVo;
import org.niord.model.message.*;
import org.niord.model.search.PagedSearchResultVo;
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
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import org.niord.core.repo.RepoPaths;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.niord.core.message.vo.MessageTagVo.MessageTagType.PUBLIC;
import static org.niord.model.message.Status.*;

/**
 * REST interface for managing messages.
 */
@RestController
@RequestMapping("/rest/messages")
@Transactional
@PermitAll
@SuppressWarnings("unused")
public class MessageRestService  {

    enum EditOp { VIEW, UPDATE, CHANGE_STATUS }

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageRestService.class);

    /**
     * A geometry template name: letters, digits, '-' and '_' only, so it names a template directly under
     * /templates/geometry/. Admits the shipped names and any site-specific one a sysadmin adds there.
     */
    static final Pattern GEOMETRY_TEMPLATE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final FileTypes fileTypes;

    private final NiordApp app;

    private final RepositoryService repositoryService;

    private final MessageService messageService;

    private final MessageSearchService messageSearchService;

    private final MessageQueryService messageQueryService;

    private final DomainService domainService;

    private final UserService userService;

    @Inject
    CurrentUserContext currentUserContext;

    private final FeatureService featureService;

    private final GeometryFormatService geometryFormatService;

    private final EditorFieldsService editorFieldsService;

    private final PublicationService publicationService;

    private final PublicationResolver publicationResolver;

    private final PromulgationManager promulgationManager;

    private final MessagePublishGate publishGate;

    MessageRestService(FileTypes fileTypes, NiordApp app, RepositoryService repositoryService, MessageService messageService, MessageSearchService messageSearchService, MessageQueryService messageQueryService, DomainService domainService, UserService userService, FeatureService featureService, GeometryFormatService geometryFormatService, EditorFieldsService editorFieldsService, PublicationService publicationService, PublicationResolver publicationResolver, PromulgationManager promulgationManager, MessagePublishGate publishGate) {
        this.fileTypes = fileTypes;
        this.app = app;
        this.repositoryService = repositoryService;
        this.messageService = messageService;
        this.messageSearchService = messageSearchService;
        this.messageQueryService = messageQueryService;
        this.domainService = domainService;
        this.userService = userService;
        this.featureService = featureService;
        this.geometryFormatService = geometryFormatService;
        this.editorFieldsService = editorFieldsService;
        this.publicationService = publicationService;
        this.publicationResolver = publicationResolver;
        this.promulgationManager = promulgationManager;
        this.publishGate = publishGate;
    }

    /***************************
     * Message access functions
     ***************************/

    /**
     * Checks that the user has editing access to the given message by matching the current domain
     * with the message series of the message.<br>
     * Throws a 403 response code error if no access.
     * @param editOp the type of edit operation
     * @param message the message
     */
    private void checkMessageEditingAccess(Message message, EditOp editOp) {
        Domain domain = domainService.currentDomain();

        if (domain == null || message == null) {
            throw new NiordWebException(403);
        }

        // An incomplete body is the caller's error, not a server error
        if (message.getMessageSeries() == null) {
            throw new NiordWebException("A message must name its message series", 400);
        }
        if (message.getStatus() == null) {
            throw new NiordWebException("A message must have a status", 400);
        }

        if (!domain.containsMessageSeries(message.getMessageSeries())) {
            throw new NiordWebException("Domain: " + domain.getName() + " does not contain message series " + message.getMessageSeries().getSeriesId(), 403);
        }

        if (editOp == EditOp.VIEW && !userService.isCallerInRole(Roles.USER)) {
            throw new NiordWebException("Only users, editors and admins can view an editable message", 403);
        }

        if (editOp == EditOp.CHANGE_STATUS && !userService.isCallerInRole(Roles.EDITOR)) {
            throw new NiordWebException("Only editors and admins can change status of a message", 403);
        }

        if (editOp == EditOp.UPDATE && !userService.isCallerInRole(Roles.EDITOR)) {
            throw new NiordWebException("Only editors and admins can update a message", 403);
        }

        // Extra check - only admins can edit a non-draft message
        boolean draft = message.getStatus().isDraft();
        if (editOp == EditOp.UPDATE && !draft && !userService.isCallerInRole(Roles.ADMIN)) {
            throw new NiordWebException("Only admins can update a non-draft message", 403);
        }
    }

    /**
     * Whether the message's series, as the current domain holds it, numbers its warnings itself (YEARLY or
     * CONTINUOUS). Publication takes a number from the series only for a message that has none, so the number of such
     * a message is never the request body's to give: the editor sends none, and a number a script sent would go out
     * beside the warning that already has it. A MANUAL or NONE series keeps the body's number.
     */
    private boolean autoNumbered(Message message) {
        Domain domain = domainService.currentDomain();
        if (domain == null || message == null || message.getMessageSeries() == null) {
            return false;
        }
        String seriesId = message.getMessageSeries().getSeriesId();
        return domain.getMessageSeries().stream()
                .anyMatch(s -> Objects.equals(s.getSeriesId(), seriesId) && s.isAutoNumbered());
    }

    /** Short-cut function that returns if the user has message editing access **/
    private boolean messageEditingAccess(Message message, EditOp editOp) {
        try {
            checkMessageEditingAccess(message, editOp);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Checks that the user has viewing access to the given message. The message must adhere to one of the following
     * criteria:
     * <ul>
     *     <li>The message is published, cancelled or expired.</li>
     *     <li>The current domain of the user matches the message series of the message, and the caller
     *         holds the user role.</li>
     *     <li>The message is associated with a public tag, and the caller holds the user role.</li>
     * </ul>
     *
     * Throws a 403 response code error if no access.
     * @param message the message
     */
    private void checkMessageViewingAccess(Message message) {
        // 1) Always grant access if the message is published, cancelled or expired
        if (message.getStatus().isPublic()) {
            return;
        }

        // 2) Grant access if the current domain of the user matches the message series of the message
        Domain domain = domainService.currentDomain();
        if (domain != null && domain.containsMessageSeries(message.getMessageSeries())
                && userService.isCallerInRole(Roles.USER)) {
            return;
        }

        // 3) Grant access if the message is associated with a public tag - to a USER only. A PUBLIC tag
        //    shares a message with the staff of every domain; it does not publish it, and any USER may
        //    create one. A caller without the user role (anonymous, or a role-less ticket) therefore only
        //    ever sees the public statuses of rule 1 - the floor the message search applies (SECAUD-6).
        if (userService.isCallerInRole(Roles.USER)
                && message.getTags().stream().anyMatch(t -> t.getType() == PUBLIC)) {
            return;
        }

        // No access
        throw new NiordWebException(403);
    }

    /** Short-cut function that returns if the user has message viewing access **/
    private boolean messageViewingAccess(Message message) {
        try {
            checkMessageViewingAccess(message);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Computes the editor fields to use for the given message
     * @param message the message to compute the editor fields for
     */
    private void computeEditorFields(SystemMessageVo message) {
        editorFieldsService.computeEditorFields(message, currentUserContext.getUserAttributes());
    }

    /**
     * Returns the message with the given message id, which may be either a UID,
     * or a short ID of a message.
     *
     * If no message exists with the given ID, null is returned.
     *
     * @param messageId the message ID
     * @param language the language of the returned data
     * @return the message or null
     */
    @GetMapping(value = "/message/{messageId}", produces = "application/json;charset=UTF-8")
    public MessageVo getMessage(
            @PathVariable("messageId") String messageId,
            @RequestParam(name = "lang", required = false) String language
            ) throws Exception {

        Message message = messageService.resolveMessage(messageId);
        if (message == null) {
            return null;
        }

        // Validate access to the message
        checkMessageViewingAccess(message);

        DataFilter filter = Message.MESSAGE_DETAILS_FILTER
                .lang(language)
                .user(userService.userResolver());
        return message.toVo(MessageVo.class, filter);
    }

    /**
     * Returns a system-model version of the message, which has been associated with a temporary
     * repository folder for new changes.
     *
     * @param message the message get an system-model version of
     * @param copyToTemp whether to copy all message resources to the associated temporary directory or not
     * @return the system-model version of the message
     */
    private SystemMessageVo toSystemMessage(Message message, boolean copyToTemp) throws Exception {

        DataFilter filter = Message.MESSAGE_DETAILS_AND_PROMULGATIONS_FILTER
                .user(userService.userResolver());

        SystemMessageVo messageVo = message.toVo(SystemMessageVo.class, filter);

        // Let promulgation services adjust the promulgations of the message
        promulgationManager.onLoadSystemMessage(messageVo, false);

        // Compute the default set of editor fields to display for the message
        computeEditorFields(messageVo);

        // Lets the cancel dialog announce that cancelling will auto-create a cancellation warning
        messageVo.setAutoCancellationWarning(messageService.willIssueCancellationWarning(message));

        // Create a temporary repository folder for the message
        messageService.createTempMessageRepoFolder(messageVo, copyToTemp);

        return messageVo;
    }

    /**
     * Returns the system-model message with the given message id, which may be either a UID,
     * or a short ID of a message.
     *
     * If no message exists with the given ID, null is returned.
     *
     * @param messageId the message ID
     * @return the message or null
     */
    @GetMapping(value = "/editable-message/{messageId}", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public SystemMessageVo getSystemMessage(
            @PathVariable("messageId") String messageId,
            @RequestParam(name = "lang", required = false) String language) throws Exception {

        Message message = messageService.resolveMessage(messageId);
        if (message == null) {
            return null;
        }

        // Validate access to the message
        checkMessageEditingAccess(message, EditOp.VIEW);

        // Returns a system model version of the message
        SystemMessageVo msg = toSystemMessage(message, false);
        msg.sort(language);

        return msg;
    }

    /**
     * Creates a new message template with a temporary repository path
     *
     * @return the new message template
     */
    @GetMapping(value = "/new-message-template", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public SystemMessageVo newTemplateMessage(
            @RequestParam(name = "mainType", required = false) MainType mainType,
            @RequestParam(name = "populate", defaultValue = "false") boolean populate) throws Exception {

        log.debug("Creating new message template");

        // Validate the mainType is valid in the current domain
        Domain domain = domainService.currentDomain();
        if (domain == null) {
            // No NiordDomain header: refused as checkMessageEditingAccess refuses it
            throw new NiordWebException(403);
        }
        List<MessageSeries> messageSeries = domain.getMessageSeries().stream()
                .filter(ms -> ms.getMainType().equals(mainType))
                .collect(Collectors.toList());

        if (messageSeries.isEmpty()) {
            throw new NiordWebException(403);
        }

        // Create a new template message
        Message message = messageService.newTemplateMessage(mainType);

        // If only one matching message series is defined, set it for the message.
        if (messageSeries.size() == 1) {
            message.setMessageSeries(messageSeries.get(0));
        }

        // Check if the type can be deduced from the message series
        if (message.getType() == null && message.getMessageSeries() != null
                && message.getMessageSeries().validTypes().size() == 1) {
            message.setType(message.getMessageSeries().validTypes().iterator().next());
        }

        SystemMessageVo messageVo = toSystemMessage(message, false);

        // Set whether or not to promulgate based on promulgation type requirements
        promulgationManager.checkDefaultPromulgationRequirements(messageVo);

        // Check if we need to populate with empty description records
        if (populate) {
            messageVo.checkCreateDescs(app.getLanguages());
            MessagePartVo part = messageVo.checkCreatePart(MessagePartType.DETAILS);
            Arrays.stream(app.getLanguages()).forEach(part::checkCreateDesc);
        }
        return messageVo;
    }

    /**
     * Creates a new message copy template with a temporary repository path
     *
     * @return the new message copy template
     */
    @GetMapping(value = "/copy-message-template/{messageId}", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public SystemMessageVo copyMessageTemplate(
            @PathVariable("messageId") String messageId,
            @RequestParam(name = "referenceType", required = false) ReferenceType referenceType) throws Exception {

        log.debug("Creating copy of message " + messageId);
        Domain domain = domainService.currentDomain();

        Message message = messageService.resolveMessage(messageId);
        if (message == null) {
            return null;
        }

        // Validate viewing access to the message
        checkMessageViewingAccess(message);

        // Create a system model version of the message - copy all repository resource to new temp folder
        SystemMessageVo editMessage = toSystemMessage(message, true);

        // Optionally, add a reference to the original message (before resetting IDs, etc)
        if (referenceType != null) {
            if (editMessage.getReferences() == null) {
                editMessage.setReferences(new ArrayList<>());
            }
            ReferenceVo ref = new ReferenceVo();
            ref.setMessageId(StringUtils.isNotBlank(editMessage.getShortId()) ? editMessage.getShortId() : editMessage.getId());
            ref.setType(referenceType);
            editMessage.getReferences().add(ref);
        }

        // Assign a new ID and repoPath
        editMessage.assignNewId();

        // Reset mainType, if not part of the current domain
        if (editMessage.getMainType() != null && !domain.supportsMainType(editMessage.getMainType())) {
            editMessage.setMainType(null);
            editMessage.setType(null);
            editMessage.setMainType(domain.getMessageSeries()
                    .stream()
                    .findFirst()
                    .map(MessageSeries::getMainType)
                    .orElse(null));
        }

        // Reset message series, if not part of the current domain
        if (editMessage.getMessageSeries() != null
                && !domain.containsMessageSeries(editMessage.getMessageSeries().getSeriesId())) {
            editMessage.setMessageSeries(null);
        }

        // Compute the default set of editor fields to display for the message
        computeEditorFields(editMessage);

        // Reset various fields. A copy is always an ordinary editorial message, also the copy of a
        // cancellation warning, which could otherwise never be saved
        editMessage.setKind(MessageKind.WARNING);
        editMessage.setShortId(null);
        editMessage.setStatus(DRAFT);
        editMessage.setCreated(null);
        editMessage.setUpdated(null);
        editMessage.setNumber(null);
        editMessage.setPublishDateFrom(null);
        editMessage.setPublishDateTo(null);
        editMessage.setUnackComments(0);
        if (editMessage.getParts() != null) {
            editMessage.getParts()
                    .forEach(p -> {
                        p.setEventDates(null);
                        p.setGeometry(featureService.copyFeatureCollection(p.getGeometry()));
                    });
        }

        // Reset copied promulgations
        promulgationManager.onCopyMessage(editMessage);

        return editMessage;
    }

    /**
     * Returns the list of referenced messages, optionally with the given reference type and status
     *
     * @return the list of referenced messages
     */
    @GetMapping(value = "/referenced-messages/{messageId}", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public List<MessageVo> getReferencedMessages(
            @PathVariable("messageId") String messageId,
            @RequestParam(name = "status", required = false) Status status,
            @RequestParam(name = "referenceType", required = false) Set<ReferenceType> referenceTypes,
            @RequestParam(name = "lang", required = false) String language
    ) throws Exception {

        log.debug("Returning referenced messages for " + messageId + ", types="
                    + referenceTypes + ", status=" + status);

        Message message = messageService.resolveMessage(messageId);
        if (message == null) {
            return Collections.emptyList();
        }

        // Validate viewing access to the message
        checkMessageViewingAccess(message);

        // Resolve the referenced messages
        List<Message> referencedMessages = messageService.getReferencedMessages(message, referenceTypes, status);

        DataFilter filter = Message.MESSAGE_DETAILS_FILTER
                .lang(language)
                .user(userService.userResolver());

        // Return the result
        // Filter the messages by access (exclude - do not throw exception)
        return referencedMessages.stream()
                .filter(msg -> messageViewingAccess(msg))
                .map(msg -> msg.toVo(MessageVo.class, filter))
                .collect(Collectors.toList());
    }

    /**
     * Creates a new draft message
     *
     * @param message the message to create
     * @return the persisted message
     */
    @PostMapping(value = "/message",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public MessageVo createMessage(@RequestBody SystemMessageVo message) throws Exception {
        log.debug("Creating message " + message);

        // Point embedded links and images to the message repository folder
        message.toMessageRepo();

        Message msg = new Message(message);

        // System-managed kinds (cancellation warnings, bulletins) are created by Niord itself, never via the API
        if (msg.getKind() != MessageKind.WARNING) {
            throw new NiordWebException("Message kind " + msg.getKind() + " cannot be created via the API", 400);
        }

        // A message is created as a draft. Its status changes go through the status endpoints, which ask the publish
        // gate, number it, add it to the recording publications and send the status event; a message created VERIFIED
        // would be published by the auto-publish sweep without the gate's checks, and one created PUBLISHED, CANCELLED
        // or EXPIRED would have none of that. A body without a status gets the access check's own 400 below. The
        // unattended runs and the imports create through MessageService, not through this endpoint
        if (msg.getStatus() != null && msg.getStatus() != DRAFT) {
            throw new NiordWebException("A message is created as a draft (status DRAFT, not " + msg.getStatus()
                    + "); change its status afterwards", 400);
        }

        // Validate access to the message
        checkMessageEditingAccess(msg, EditOp.UPDATE);

        // A series that numbers its warnings gives the number at publication, to a message that has none
        if (autoNumbered(msg)) {
            msg.setNumber(null);
        }

        // The attachments and thumbnail must name files in the message's own repository folder
        checkRepoFileReferences(msg);

        // The short id and the referenced message ids hold no markup character
        checkMessageIds(msg, null);

        msg = messageService.createMessage(msg);

        // Copy resources from the temporary editing message folder to the message repository folder.
        // The folder is the persisted message's own, derived from its UID, never the body's repoPath.
        message.setId(msg.getUid());
        message.setRepoPath(msg.getRepoPath());
        messageService.updateMessageFromTempRepoFolder(message);

        return getMessage(msg.getUid(), null);
    }

    /**
     * Updates a message
     *
     * @param message the message to update
     * @return the updated message
     */
    @PutMapping(value = "/message/{messageId}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public MessageVo updateMessage(@PathVariable("messageId") String messageId, @RequestBody SystemMessageVo message) throws Exception {
        if (!Objects.equals(messageId, message.getId())) {
            throw new NiordWebException(400);
        }
        log.debug("Updating message " + message);

        // Point embedded links and images to the message repository folder
        message.toMessageRepo();

        Message msg = new Message(message);

        // Validate access to the message
        checkMessageEditingAccess(msg, EditOp.UPDATE);
        Message original = messageQueryService.findByUid(messageId);
        checkMessageEditingAccess(original, EditOp.UPDATE);

        // System-managed messages (e.g. auto-created cancellation warnings) are read-only
        if (original != null && original.getKind() != MessageKind.WARNING) {
            throw new NiordWebException("Message " + messageId + " of kind " + original.getKind() + " is read-only", 400);
        }

        // A series that numbers its warnings gives the number at publication: a draft keeps none, and a warning keeps
        // the number it was given, whatever the body says
        if (autoNumbered(msg)) {
            msg.setNumber(original.getStatus().isDraft() ? null : original.getNumber());
        }

        // The attachments and thumbnail must name files in the message's own repository folder
        checkRepoFileReferences(msg);

        // A new short id or referenced message id holds no markup character
        checkMessageIds(msg, original);

        msg = messageService.updateMessage(msg);

        // Copy resources from the temporary editing message folder to the message repository folder.
        // The folder is the persisted message's own, derived from its UID, never the body's repoPath.
        message.setRepoPath(msg.getRepoPath());
        messageService.updateMessageFromTempRepoFolder(message);

        return getMessage(msg.getUid(), null);
    }

    /**
     * Refuses a message whose attachments or thumbnail do not name a file in the message's own repository
     * folder.
     * <p>
     * They are stored as the request body sends them and later resolved against the repository, by the
     * anonymous message archive export among others. So an attachment's file name must be a bare name, and
     * an attachment's path and the thumbnail path must lie below the message's folder once normalised. The
     * folder is the one derived from the message UID; after {@code toMessageRepo()} the body's paths point
     * into it. Blank values are left alone.
     *
     * @param message the message about to be saved
     * @throws NiordWebException 400 when a reference names anything else
     */
    static void checkRepoFileReferences(Message message) {
        String folder = message.getRepoPath();
        for (Attachment att : message.getAttachments()) {
            if (StringUtils.isNotBlank(att.getFileName()) && !WebUtils.isBareFileName(att.getFileName())) {
                throw new NiordWebException("Invalid attachment file name", 400);
            }
            if (StringUtils.isNotBlank(att.getPath()) && !RepoPaths.isBelowFolder(att.getPath(), folder)) {
                throw new NiordWebException("Invalid attachment path", 400);
            }
        }
        if (StringUtils.isNotBlank(message.getThumbnailPath())
                && !RepoPaths.isBelowFolder(message.getThumbnailPath(), folder)) {
            throw new NiordWebException("Invalid thumbnail path", 400);
        }
    }

    /**
     * Refuses a message whose short id, or the message id of one of whose references, holds a markup character
     * ({@code <}, {@code >}, {@code "} or {@code &}) that the stored message does not already have.
     * <p>
     * The mail and PDF templates print these ids into HTML unescaped. Every short-id format in use passes, and
     * a message saved before the rule stays editable; see {@link MessageIdMarkup}.
     *
     * @param message the message about to be saved
     * @param stored the stored message it updates, or null when it is created
     * @throws NiordWebException 400 when an id holds a markup character
     */
    static void checkMessageIds(Message message, Message stored) {
        String refusal = MessageIdMarkup.refusal(message, stored);
        if (refusal != null) {
            throw new NiordWebException(refusal, 400);
        }
    }

    /**
     * Asks the publish gate about a change of the stored message to the given status (MSG10).
     * <p>
     * Only a change the message may take is asked about: DRAFT to VERIFIED and VERIFIED to PUBLISHED, the two steps
     * of {@code MessageService.updateStatus}'s transitions that end in a status the gate checks. Any other change to
     * VERIFIED or PUBLISHED is refused by {@code updateStatus} as an invalid transition, with its own 409 naming the
     * two statuses, whatever the message holds.
     *
     * @param stored the stored message
     * @param from the status the message has when the change is made: the stored one, or in a bulk list the one the
     *             list's earlier items give it
     * @param target the status asked for
     * @return the gate's refusal, or null when the change may go ahead or is not one the gate checks
     */
    private MessagePublishGate.Refusal publishGateRefusal(Message stored, Status from, Status target) {
        boolean step = (target == VERIFIED && from == DRAFT)
                || (target == PUBLISHED && from == VERIFIED);
        return step ? publishGate.check(stored, target) : null;
    }

    /**
     * The 409 of the publish gate: its detail names each refused message and why, and its ProblemDetail property
     * {@code refusals} lists them as {@code [{ messageId, shortId?, reasons: [{ code, types?, date? }] }]}, the
     * message named by its UID as the request names it.
     *
     * @param refusals the gate's refusals, not empty
     * @return the exception to throw
     */
    private static NiordWebException publishGateConflict(List<MessagePublishGate.Refusal> refusals) {
        String detail = "No status was changed. " + refusals.stream()
                .map(MessagePublishGate.Refusal::describe)
                .collect(Collectors.joining(" "));
        NiordWebException conflict = new NiordWebException(detail, 409);
        conflict.getBody().setProperty("refusals", refusals);
        return conflict;
    }

    /**
     * Updates the status of a message
     * <p>
     * The request is one transaction, rolled back for any exception: a refused transition answers 409 and
     * changes nothing. VERIFIED and PUBLISHED are held to the editor's own Verify and Publish (MSG10): a message
     * without a title, with a MANDATORY promulgation not ticked, or (for PUBLISHED) with a publish-from date still
     * ahead is refused with 409 before anything changes, whose ProblemDetail property {@code refusals} gives the
     * reasons ({@link MessagePublishGate}). When a message is published with {@code cancelReferencedMessages}, the
     * publication and the cancellation of every published message it cancels, repeats or updates are one act: a
     * referenced message the current domain cannot change is refused with 409 before anything changes, and a failure
     * to cancel one rolls the publication back too. The cascade cancels with {@code updateStatus}, issuing no cancellation
     * warning: the message being published is itself the cancellation.
     *
     * @param messageId the ID of the message
     * @param status the status update
     * @return the updated message
     */
    @PutMapping(value = "/message/{messageId}/status",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    @Transactional(rollbackFor = Exception.class)
    public MessageVo updateMessageStatus(
            @PathVariable("messageId") String messageId,
            @RequestParam(name = "cancelReferencedMessages", required = false) Boolean cancelReferencedMessages,
            @RequestBody String status) throws Exception {
        log.debug("Updating status of message " + messageId + " to " + status);

        // Validate access to the message
        Message stored = messageQueryService.findByUid(messageId);
        checkMessageEditingAccess(stored, EditOp.CHANGE_STATUS);
        Status newStatus = Status.valueOf(status);

        // When publishing a message, the published messages it cancels are cancelled with it. Every one of them
        // must be one the current domain may change, checked before anything changes. A message referenced twice
        // (two reference types, or once by UID and once by short id) is cancelled once: a second cancellation
        // would be refused as CANCELLED -> CANCELLED and roll the publication back.
        List<Message> referencedMessages = List.of();
        if (newStatus == PUBLISHED && Boolean.TRUE.equals(cancelReferencedMessages)) {
            Set<String> seenUids = new HashSet<>();
            referencedMessages = messageService.getReferencedMessages(
                            stored, ReferenceType.cancelsReferencedMessage(), PUBLISHED).stream()
                    .filter(m -> seenUids.add(m.getUid()))
                    .collect(Collectors.toList());
            List<String> otherDomain = referencedMessages.stream()
                    .filter(m -> !messageEditingAccess(m, EditOp.CHANGE_STATUS))
                    .map(m -> StringUtils.defaultIfBlank(m.getShortId(), m.getUid()))
                    .collect(Collectors.toList());
            if (!otherDomain.isEmpty()) {
                throw new NiordWebException("Not published: the referenced messages "
                        + String.join(", ", otherDomain) + " belong to another domain and cannot be cancelled "
                        + "from here. Publish without cancelling them, or ask that domain to cancel them.", 409);
            }
        }

        // The publish gate, before anything changes
        MessagePublishGate.Refusal refusal = publishGateRefusal(stored, stored.getStatus(), newStatus);
        if (refusal != null) {
            throw publishGateConflict(List.of(refusal));
        }

        // Cancellations flow through cancelMessage(), which auto-creates a cancellation warning
        // for an NW warning of an auto-numbered series
        Message msg = newStatus == CANCELLED
                ? messageService.cancelMessage(messageId)
                : messageService.updateStatus(messageId, newStatus);

        // A failure to cancel one fails the whole request, the publication included
        for (Message refMsg : referencedMessages) {
            log.debug("Cancelling referenced messages to " + messageId + ": " + refMsg.getUid());
            messageService.updateStatus(refMsg.getUid(), CANCELLED);
        }

        return getMessage(msg.getUid(), null);
    }

    /**
     * Updates the statuses of a list of messages
     * <p>
     * The list is one transaction, rolled back for any exception: an item without a status is refused with
     * 400 before any change, and a refused transition answers 409 naming the message and leaves every message of the
     * list as it was. The publish gate (MSG10) asks every item before anything changes: when it refuses one or more,
     * the answer is one 409 whose ProblemDetail property {@code refusals} lists every refused item with its reasons,
     * and no message of the list is changed. An item is judged from the status the list's earlier items give its
     * message, so a list that names a message twice cannot step past the gate.
     *
     * @param updates the status updates
     * @return the updated messages
     */
    @PutMapping(value = "/update-statuses",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    @Transactional(rollbackFor = Exception.class)
    public List<MessageVo> updateMessageStatuses(@RequestBody List<UpdateStatusParam> updates) throws Exception {
        log.debug("Updating statuses " + updates);

        // Validate access to the messages, and that each names a status
        List<Message> stored = new ArrayList<>();
        for (UpdateStatusParam update : updates) {
            Message message = messageQueryService.findByUid(update.getMessageId());
            checkMessageEditingAccess(message, EditOp.CHANGE_STATUS);
            if (update.getStatus() == null) {
                throw new NiordWebException("No status to change message " + update.getMessageId() + " to", 400);
            }
            stored.add(message);
        }

        // The publish gate asks every item before anything changes, and one answer names every refused item.
        // The items are applied one after another, so an item is judged from the status the earlier items of the
        // list give its message, not from the stored one: [A to VERIFIED, A to PUBLISHED] is a publish of A.
        List<MessagePublishGate.Refusal> refusals = new ArrayList<>();
        Map<String, Status> statusAfterEarlierItems = new HashMap<>();
        for (int i = 0; i < updates.size(); i++) {
            Message message = stored.get(i);
            Status target = updates.get(i).getStatus();
            Status from = statusAfterEarlierItems.getOrDefault(message.getUid(), message.getStatus());
            MessagePublishGate.Refusal refusal = publishGateRefusal(message, from, target);
            if (refusal != null) {
                refusals.add(refusal);
            }
            statusAfterEarlierItems.put(message.getUid(), target);
        }
        if (!refusals.isEmpty()) {
            throw publishGateConflict(refusals);
        }

        // Perform the updates. Cancellations flow through cancelMessage(), which auto-creates a
        // cancellation warning for an NW warning of an auto-numbered series
        List<MessageVo> result = new ArrayList<>();
        for (UpdateStatusParam update : updates) {
            Message message = update.getStatus() == CANCELLED
                    ? messageService.cancelMessage(update.getMessageId())
                    : messageService.updateStatus(update.getMessageId(), update.getStatus());
            result.add(getMessage(message.getUid(), null));
        }
        return result;
    }

    /***************************
     * Attachment handling
     ***************************/

    /**
     * Called to upload message attachments to a temporary message folder
     *
     * @param path the folder to upload the files to
     * @param request the multi-part input request
     * @return a the updated list of attachments
     */
    @PostMapping(value = "/attachments/{*folder}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public List<AttachmentVo> uploadMessageAttachments(@PathVariable("folder") String folderPath, MultipartHttpServletRequest request) throws Exception {

        // Multi-segment {*folder} capture-the-rest variable arrives with a leading '/'.
        final String path = WebUtils.trimLeadingSlash(folderPath);

        // Check that the specified folder is indeed under the "temp" root (throws 403 otherwise), then write the
        // files into it as rows, each under a name unique in the folder
        String folder = repositoryService.validateTempRepoPath(path);

        return repositoryService.uploadRows(folder, request).stream()
                .map(file -> {
                    AttachmentVo att = new AttachmentVo();
                    att.setPath(folder + "/" + WebUtils.encodeURIComponent(file.name()));
                    att.setFileName(file.name());
                    att.setFileSize(file.size());
                    att.setType(fileTypes.getContentType(Paths.get(file.name())));
                    return att;
                })
                .collect(Collectors.toList());
    }

    /***************************************/
    /** Message History methods           **/
    /***************************************/

    /**
     * Returns the message history for the given message ID
     * @param messageId the message ID or message series ID
     * @return the message history
     */
    @GetMapping(value = "/message/{messageId}/history", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public List<MessageHistoryVo> getMessageHistory(@PathVariable("messageId") String messageId) {

        // Get the message id
        Message message = messageService.resolveMessage(messageId);
        if (message == null) {
            return Collections.emptyList();
        }

        // The history holds every earlier version of the message, drafts included, and the names of its
        // editors, so it takes the domain gate of getSystemMessage (a USER of a domain that owns the
        // message series), not the viewing gate of getMessage, which admits any public-status message.
        checkMessageEditingAccess(message, EditOp.VIEW);

        return messageService.getMessageHistory(message.getId()).stream()
                .map(MessageHistory::toVo)
                .collect(Collectors.toList());
    }

    /**
     * Returns the recently edited messages for the current user
     * @return the recently edited messages for the current user
     */
    @GetMapping(value = "/recently-edited-drafts", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public List<MessageVo> getRecentlyEditedMessages(
            @RequestParam(name = "lang", defaultValue = "en") String lang,
            @RequestParam(name = "maxMessageNo", defaultValue = "20") int maxMessageNo
    ) {
        DataFilter filter = DataFilter
                .get().lang(lang)
                .fields("MessageDesc.title");
        return messageService.getMostRecentlyEditedDrafts(maxMessageNo).stream()
                .map(m -> m.toVo(MessageVo.class, filter))
                .collect(Collectors.toList());
    }

    /***************************
     * Sorting functionality
     ***************************/

    /** Re-computes the area sort order of published messages in the current domain */
    @PutMapping("/recompute-area-sort-order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RolesAllowed(Roles.ADMIN)
    public void reindexPublishedMessageAreaSorting() throws Exception {

        // Search for published message in the curretn domain
        Domain domain = domainService.currentDomain();
        if (domain == null) {
            // No NiordDomain header: refused as checkMessageEditingAccess refuses it
            throw new NiordWebException(403);
        }
        Set<String> messageSeries = domain.getMessageSeries().stream()
                .map(MessageSeries::getSeriesId)
                .collect(Collectors.toSet());

        MessageSearchParams params = new MessageSearchParams();
        params.statuses(Collections.singleton(Status.PUBLISHED))
                .seriesIds(messageSeries);

        // Perform the search and update the area sort order of the messages. A failed search is an error, not a
        // "done" over no messages
        long t0 = System.currentTimeMillis();
        PagedSearchResultVo<Message> searchResult = messageSearchService.searchOrFail(params);
        searchResult.getData().forEach(m -> messageService.computeMessageAreaSortingOrder(m));

        log.debug(String.format("Updates area sort order for  %d messages in %d ms",
                searchResult.getData().size(), System.currentTimeMillis() - t0));
    }

    /** Swaps the area sort order of the two messages */
    @PutMapping(value = "/change-area-sort-order", consumes = "application/json;charset=UTF-8")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RolesAllowed(Roles.EDITOR)
    public void changeAreaSortOrder(@RequestBody AreaSortOrderUpdateParam params) {
        if (params.getId() == null || (params.getBeforeId() == null && params.getAfterId() == null)) {
            throw new NiordWebException(400);
        }

        // Validate that the current domain has editing access to the message being reordered.
        // CHANGE_STATUS (not UPDATE) so non-admin editors can still reorder published messages in their domain.
        checkMessageEditingAccess(messageQueryService.findByUid(params.getId()), EditOp.CHANGE_STATUS);

        messageService.changeAreaSortOrder(params.getId(), params.getAfterId(), params.getBeforeId());
    }

    /***************************
     * Utility functions
     ***************************/

    /**
     * Adjust two aspects of the edited message:
     * <ul>
     *     <li>Computes the editor fields to display for the message</li>
     *     <li>If "autoTitle" is set, computes the title lines for the given message template</li>
     * </ul>
     *
     * @param message the message template to adjust
     * @return the updated message template
     */
    @PostMapping(value = "/adjust-editable-message",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public SystemMessageVo adjustEditableMessage(@RequestBody SystemMessageVo message) throws Exception {

        // Compute the editor fields to use for the message
        computeEditorFields(message);

        Message msg = null;
        boolean autoTitle = message.isAutoTitle() != null && message.isAutoTitle();

        // If auto-title, auto-publication or auto-source is on, compute the resulting fields
        if (autoTitle) {
            msg = new Message(message);

            // Ensure that description records are generated for all model languages
            Arrays.stream(app.getLanguages()).forEach(msg::checkCreateDesc);

            messageService.updateAutoMessageFields(msg);

            // Replace the value object with title line descriptors.
            message.setDescs(null);
            DataFilter filter = DataFilter.get().defaultLanguage(app.getDefaultLanguage()).fields(DataFilter.DETAILS);
            msg.getDescs().forEach(desc -> message.checkCreateDescs().add(desc.toVo(filter)));

        } else {
            message.setDescs(null);
        }

        // Check if we need to update the short ID from the message number
        if (message.getNumber() != null && message.getMessageSeries() != null) {
            if (msg == null) {
                msg = new Message(message);
            }
            messageService.checkUpdateShortId(msg);
            message.setShortId(msg.getShortId());
        }

        // Let promulgation services adjust the promulgations of the message
        promulgationManager.onLoadSystemMessage(message, true);

        // Prune irrelevant fields
        message.setNumber(null);
        message.setPublishDateFrom(null);
        message.setMainType(null);
        message.setAutoTitle(null);
        message.setAreas(null);
        message.setCategories(null);
        message.setMessageSeries(null);

        return message;
    }

    /** Extracts the message publication from the message */
    @PostMapping(value = "/extract-message-publication",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public MessagePublicationVo extractMessagePublication(
            @RequestParam(name = "lang", defaultValue = "en") String lang,
            @RequestParam(name = "publicationId", required = false) String publicationId,
            @RequestBody MessageVo message) throws Exception {
        // The shared resolver: a new-model issue answers here in exactly the
        // shape the legacy citation machinery reads, so nothing below had to
        // learn about issues.
        //
        // The null branch is preserved deliberately. The frontend reads null as
        // "this publication is not embedded in this message" and shows an empty
        // form; turning it into a 404 would make the editor show an error where
        // it used to show a blank field.
        SystemPublicationVo publication = publicationResolver.systemVo(publicationId, lang);
        if (publication != null) {
            return PublicationUtils.extractMessagePublication(message, publication, lang);
        }
        return null;
    }

    /** Updates the message publications with the given parameters and link */
    @PostMapping(value = "/update-message-publications",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public MessageVo updateMessagePublications(
            @RequestParam(name = "publicationId", required = false) String publicationId,
            @RequestParam(name = "parameters", required = false) String parameters,
            @RequestParam(name = "link", required = false) String link,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestBody MessageVo message) throws Exception {
        // As above: one resolver, both halves of the transition, and the silent
        // branch kept. Returning the message unchanged for an unknown id is what
        // the frontend expects.
        SystemPublicationVo publication = publicationResolver.systemVo(publicationId, lang);
        if (publication != null) {
            if (message.getDescs() == null) {
                throw new NiordWebException("A message must carry its descs", 400);
            }
            return PublicationUtils.updateMessagePublications(
                    message, publication, parameters, link, lang);
        }
        return message;
    }

    /**
     * Formats the feature collection according to the given template
     *
     * @param geometry the feature collection to format
     * @param language the language of the returned data
     * @param template the template to use for formatting the geometry
     * @param format the position format, either "dec", "sec" or "navtex"
     * @return the formatted geometry
     */
    @PostMapping(value = "/format-message-geometry",
            consumes = "application/json;charset=UTF-8",
            produces = "text/html;charset=UTF-8")
    public String formatGeometry(
            @RequestParam(name = "lang", defaultValue = "en") String language,
            @RequestParam(name = "template", defaultValue = "numbered-list") String template,
            @RequestParam(name = "format", defaultValue = "dec") String format,
            @RequestBody FeatureCollectionVo geometry) throws Exception {

        // The name is formatted into a template path under /templates/geometry/, so it must be a plain name.
        // Checked before the try, whose catch-all would turn the refusal into a 500.
        if (!GEOMETRY_TEMPLATE_NAME.matcher(template).matches()) {
            throw new NiordWebException("Invalid geometry template", 400);
        }

        try {
            return geometryFormatService.formatGeometryAsHtml(language, template, format, geometry);
        } catch (TemplateNotFoundException e) {
            // A plain name that names no template is the caller's error
            throw new NiordWebException("No geometry template " + template, 404);
        } catch (Exception e) {
            log.error("Error formatting geometry: " + e.getMessage(), e);
            throw new NiordWebException("Error formatting geometry", e);
        }
    }

    /**
     * Converts a plain-text geometry representation into a GeoJSON representation.
     * Returns null if the parsing fails.
     */
    @PostMapping(value = "/parse-geometry",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public FeatureCollectionVo parsePlainTextGeometry(@RequestBody PlainTextGeometryParam param) throws Exception {
        try {
            PlainTextConverter converter = PlainTextConverter.newInstance(app.getLanguages());
            return converter.fromPlainText(param.getGeometryText());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Converts a GeoJSON geometry to a plain-text representation
     * Returns null if the formatting fails.
     */
    @PostMapping(value = "/format-geometry",
            consumes = "application/json;charset=UTF-8",
            produces = "plain/text;charset=UTF-8")
    public String formatGeometryAsPlainText(
            @RequestParam(name = "lang", defaultValue = "en") String language,
            @RequestBody GeoJsonVo geoJson) throws Exception {
        try {
            // Sort languages so that the language parameter comes first (decides decimal separator being used)
            List<String> languages = new ArrayList<>();
            languages.add(language);
            Arrays.stream(app.getLanguages())
                    .filter(lang -> !lang.equalsIgnoreCase(language))
                    .forEach(languages::add);

            PlainTextConverter converter = PlainTextConverter.newInstance(languages.toArray(new String[languages.size()]));
            return converter.toPlainText(geoJson);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Converts an UTM text representation into a GeoJSON representation.
     * Returns null if the parsing fails.
     */
    @PostMapping(value = "/parse-utm",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public FeatureCollectionVo parseUtmGeometry(@RequestBody PlainTextGeometryParam param) throws Exception {
        try {
            PlainTextConverter converter = PlainTextConverter.newInstance(app.getLanguages());
            return converter.fromUtmText(param.getGeometryText());
        } catch (Exception e) {
            return null;
        }
    }

    /***************************
     * Helper classes
     ***************************/

    /** Encapsulates a status change for a message */
    public static class UpdateStatusParam implements IJsonSerializable {
        String messageId;
        Status status;

        @Override
        public String toString() {
            return "{messageId='" + messageId + "', status=" + status + "}";
        }

        public String getMessageId() {
            return messageId;
        }

        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }

        public Status getStatus() {
            return status;
        }

        public void setStatus(Status status) {
            this.status = status;
        }
    }

    /** Parameter that encapsulates a message being dragged to a new area sort order position */
    public static class AreaSortOrderUpdateParam implements IJsonSerializable {
        String id;
        String beforeId;
        String afterId;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getBeforeId() {
            return beforeId;
        }

        public void setBeforeId(String beforeId) {
            this.beforeId = beforeId;
        }

        public String getAfterId() {
            return afterId;
        }

        public void setAfterId(String afterId) {
            this.afterId = afterId;
        }
    }

    /** Parameter that encapsulates a plain-text geometry specification */
    public static class PlainTextGeometryParam implements IJsonSerializable {
        String geometryText;

        public String getGeometryText() {
            return geometryText;
        }

        public void setGeometryText(String geometryText) {
            this.geometryText = geometryText;
        }
    }
}

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
package org.niord.core.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.promulgation.BaseMessagePromulgation;
import org.niord.core.promulgation.PromulgationType;
import org.niord.core.promulgation.PromulgationType.Requirement;
import org.niord.core.promulgation.PromulgationTypeService;
import org.niord.model.message.Status;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The publish gate: what the editor's own Verify and Publish allow, as one check for the status endpoints.
 * <p>
 * The editor's status panel lets a message become VERIFIED or PUBLISHED only when it has a title in some language
 * and every MANDATORY promulgation type is ticked, and it offers no Publish for a message with a publish-from date
 * still ahead. This class makes the same check on the stored message, so that a status change asked for over REST
 * (the editor's bulk Update Statuses dialog, or any API caller) is held to the same rule:
 * <ul>
 *     <li>VERIFIED or PUBLISHED is refused when no language has a title that is more than white space
 *         ({@link Code#NO_TITLE});</li>
 *     <li>VERIFIED or PUBLISHED is refused when a promulgation type that is active for the current domain and
 *         the message's type, and MANDATORY, is not ticked on the message ({@link Code#MANDATORY_PROMULGATION_OFF},
 *         with the types' names);</li>
 *     <li>PUBLISHED is refused when the message's publish-from date is after now ({@link Code#SCHEDULED}, with
 *         the date): the scheduler publishes it then. VERIFIED is not, which is how such a message is
 *         scheduled.</li>
 * </ul>
 * Any other target status passes. The check starts from the active promulgation types, as the editor's load
 * does ({@code PromulgationManager.onLoadSystemMessage}), not from the stored promulgations: the message keeps
 * only the promulgations that hold data, so a type that was never ticked has no row at all.
 * <p>
 * The gate is called by the status endpoints only, before anything changes, never by
 * {@link MessageService#updateStatus}: the scheduler, the firing exercises, the integration and the imports change
 * statuses without it.
 */
@Service
public class MessagePublishGate {

    /** Why the gate refuses a status change */
    public enum Code {
        /** No language has a title that is more than white space */
        NO_TITLE,
        /** A MANDATORY promulgation type is not ticked */
        MANDATORY_PROMULGATION_OFF,
        /** PUBLISHED was asked for before the message's publish-from date */
        SCHEDULED
    }

    /**
     * One reason for a refusal.
     *
     * @param code  the reason
     * @param types for {@link Code#MANDATORY_PROMULGATION_OFF}, the names of the types that are off, in priority
     *              order; otherwise null
     * @param date  for {@link Code#SCHEDULED}, the publish-from date; otherwise null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reason(Code code, List<String> types, Date date) {

        /** The reason as a clause of an English sentence about the message */
        public String describe() {
            return switch (code) {
                case NO_TITLE -> "it has no title";
                case MANDATORY_PROMULGATION_OFF -> (types.size() == 1
                        ? "its mandatory promulgation " + types.getFirst() + " is not ticked"
                        : "its mandatory promulgations " + String.join(", ", types) + " are not ticked");
                case SCHEDULED -> "it is scheduled for publication at " + date.toInstant();
            };
        }
    }

    /**
     * The gate's refusal of one message.
     *
     * @param messageId the message's UID, as the status endpoints name it
     * @param shortId   the message's short id, or null when it has none
     * @param reasons   every reason, never empty
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Refusal(String messageId, String shortId, List<Reason> reasons) {

        /** The refusal as an English sentence, naming the message by its short id, or its UID when it has none */
        public String describe() {
            return "Message " + StringUtils.defaultIfBlank(shortId, messageId) + " cannot be "
                    + "given this status: " + reasons.stream().map(Reason::describe).collect(Collectors.joining("; "))
                    + ".";
        }
    }

    private final PromulgationTypeService promulgationTypeService;

    public MessagePublishGate(PromulgationTypeService promulgationTypeService) {
        this.promulgationTypeService = promulgationTypeService;
    }

    /**
     * Whether the gate checks a change to the given status
     *
     * @param target the status asked for
     * @return whether the status is VERIFIED or PUBLISHED
     */
    public static boolean gates(Status target) {
        return target == Status.VERIFIED || target == Status.PUBLISHED;
    }

    /**
     * Checks a change of the stored message to the given status, as of now.
     * <p>
     * The active promulgation types are those of the current domain, as in the editor; call it inside the request.
     *
     * @param message the stored message
     * @param target  the status asked for
     * @return the refusal, or null when the change may go ahead
     */
    public Refusal check(Message message, Status target) {
        return check(message, target, new Date());
    }

    /**
     * Checks a change of the stored message to the given status, as of the given instant
     *
     * @param message the stored message
     * @param target  the status asked for
     * @param now     the instant a publish-from date is compared with
     * @return the refusal, or null when the change may go ahead
     */
    Refusal check(Message message, Status target, Date now) {
        if (!gates(target)) {
            return null;
        }

        List<Reason> reasons = new ArrayList<>();

        if (!hasTitle(message)) {
            reasons.add(new Reason(Code.NO_TITLE, null, null));
        }

        List<String> off = promulgationTypeService.getActivePromulgationTypes(message.getType()).stream()
                .filter(type -> type.getRequirement() == Requirement.MANDATORY)
                .filter(type -> !ticked(message, type))
                .map(type -> StringUtils.defaultIfBlank(type.getName(), type.getTypeId()))
                .collect(Collectors.toList());
        if (!off.isEmpty()) {
            reasons.add(new Reason(Code.MANDATORY_PROMULGATION_OFF, List.copyOf(off), null));
        }

        Date publishDateFrom = message.getPublishDateFrom();
        if (target == Status.PUBLISHED && publishDateFrom != null
                && publishDateFrom.getTime() > now.getTime()) {
            reasons.add(new Reason(Code.SCHEDULED, null, new Date(publishDateFrom.getTime())));
        }

        return reasons.isEmpty()
                ? null
                : new Refusal(message.getUid(), StringUtils.defaultIfBlank(message.getShortId(), null),
                        List.copyOf(reasons));
    }

    /** Whether some language has a title that is more than white space */
    private static boolean hasTitle(Message message) {
        return message.getDescs() != null && message.getDescs().stream()
                .anyMatch(desc -> desc != null && !blank(desc.getTitle()));
    }

    /**
     * Whether the text is null or white space only. Besides Java's white space, the no-break spaces and the byte
     * order mark count as white space, as they do for the editor's {@code trim()}.
     */
    static boolean blank(String text) {
        return text == null || text.codePoints().allMatch(cp ->
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0xFEFF);
    }

    /** Whether the message holds a ticked promulgation of the given type */
    private static boolean ticked(Message message, PromulgationType type) {
        if (message.getPromulgations() == null) {
            return false;
        }
        for (BaseMessagePromulgation<?> promulgation : message.getPromulgations()) {
            if (promulgation != null && promulgation.getType() != null
                    && type.getTypeId().equals(promulgation.getType().getTypeId())) {
                return promulgation.isPromulgate();
            }
        }
        return false;
    }
}

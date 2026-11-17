/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.web.publication;

import org.niord.core.publication.PublicationResolver;
import org.niord.core.publication.series.IssueDeleteService;
import org.niord.core.publication.series.IssuePublishService;
import org.niord.core.publication.series.PublicationException;
import org.niord.core.publication.series.SeriesValidator;
import org.niord.core.publication.series.StaleVersionGuard;
import org.niord.core.publication.series.resolve.IssueNaming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the publication failures into the wire shape, once.
 *
 * Without this every endpoint catches the same handful of exceptions and picks
 * its own status and body, and they drift -- so the same failure looks different
 * depending which endpoint you hit, and a client cannot handle it generically.
 *
 * Every response carries a CODE, not just a message. The message is for a human
 * reading a log; the code is what a client branches on, and a client that has to
 * match on message text breaks the first time somebody improves the wording.
 *
 * THE BODY IS DELIBERATELY NOT A {@code ProblemDetail}. Every other error in this
 * deployment renders as RFC 9457 through {@code spring.mvc.problemdetails.enabled},
 * but this one is a published contract: the Angular client reads {@code code},
 * {@code message} and the per-failure keys below straight off the object, and the
 * keys are in insertion order because that is the order the JAX-RS mapper emitted
 * them in. Keep the shape byte-for-byte; see
 * {@code fe/docs/publications/API-CONTRACT.md} section 7.1.
 *
 * THE HANDLER'S PARAMETER TYPE IS LOAD-BEARING. It is {@link PublicationException}
 * and it must stay that. An {@code @ExceptionHandler(RuntimeException.class)} is a
 * handler for every runtime failure in the whole deployment: Spring picks the
 * handler method whose declared type is the nearest supertype of what was thrown,
 * so with nothing more specific registered that one answers literally everything.
 * Recognising nothing and rethrowing does not undo it either -- an exception thrown
 * from inside a handler method is no longer resolvable and escapes as a plain 500 --
 * so a bare {@code NiordWebException(403)} raised anywhere else, and every request
 * that matched no route, would come back carrying a catalogue status and a
 * publication body. Narrowing the parameter is what leaves those alone.
 *
 * No {@code @Order} is needed. {@link PublicationException} extends
 * {@code RuntimeException} directly -- not {@code IllegalArgumentException} or
 * {@code IllegalStateException} -- so {@code RestExceptionStatusAdvice} cannot
 * match it however the two advices happen to sort, and {@code NullBodyNoContentAdvice}
 * skips {@code ResponseEntity} return values outright.
 */
@RestControllerAdvice
public class PublicationExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(PublicationExceptionAdvice.class);

    @ExceptionHandler(PublicationException.class)
    public ResponseEntity<Map<String, Object>> toResponse(PublicationException e) {
        String code = e.code();
        int status = PublicationErrorCatalogue.statusOf(code);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", e.getMessage());

        // The stamp the winner recorded, so a losing client can show it rather
        // than guess.
        if (e instanceof IssuePublishService.AlreadyPublishedException already
                && already.stampedAt() != null) {
            body.put("stampedAt", already.stampedAt().getTime());
        }
        if (e instanceof IssueNaming.UnknownTokenException unknown) {
            body.put("token", unknown.token());
        }
        // When the publication stopped being public. The refusal exists to name
        // the state the caller could not see, so the date has to be readable as a
        // date rather than parsed back out of the sentence.
        if (e instanceof OneOffRestService.PublicWindowClosedException closed
                && closed.publicTo() != null) {
            body.put("publicTo", closed.publicTo().getTime());
        }
        // Both revisions, so the client can say "you are three saves behind"
        // rather than "somebody changed something" -- and so it knows which
        // revision to re-read against without guessing.
        if (e instanceof StaleVersionGuard.StaleVersionException stale) {
            body.put("storedVersion", stale.stored());
            body.put("submittedVersion", stale.submitted());
        }
        // The codes the admin has to acknowledge, so the dialog can list them
        // rather than send the admin back to the checklist to find out.
        if (e instanceof IssuePublishService.WarningsNotAcknowledgedException warnings) {
            body.put("unacknowledgedWarnings", warnings.codes());
        }
        // Which messages cite the issue somebody tried to delete, and how many
        // there are. The sentence carries the same names, but a dialog has to be
        // able to render each one as a row somebody can open -- and a client
        // parsing them back out of the message would break the first time the
        // wording improved. The count is the true total; the list is bounded.
        if (e instanceof IssueDeleteService.IssueCitedException cited) {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (PublicationResolver.CitingMessage m : cited.citingMessages()) {
                Map<String, Object> one = new LinkedHashMap<>();
                // NULL where the message has not been numbered yet, with the title
                // carrying the name instead. A uid put in this key would be
                // indistinguishable from a real short id, and a dialog rendering
                // it as a link offers a number nobody can look up.
                one.put("messageId", m.messageId());
                one.put("uid", m.uid());
                one.put("title", m.title());
                messages.add(one);
            }
            body.put("citingMessages", messages);
            body.put("citingCount", cited.citingCount());
        }
        // Which fields failed, so a form can put each message beside the control
        // that caused it. The message stays a readable sentence for a log; this
        // is the same information in the shape a client can act on.
        if (!e.fieldErrors().isEmpty()) {
            List<Map<String, Object>> fields = new ArrayList<>();
            for (SeriesValidator.FieldError fe : e.fieldErrors()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("rule", fe.rule());
                one.put("field", fe.field());
                one.put("message", fe.message());
                fields.add(one);
            }
            body.put("fieldErrors", fields);
        }

        if (status >= 500) {
            log.error("publication error {} -> {}", code, status, e);
        } else {
            log.debug("publication error {} -> {}: {}", code, status, e.getMessage());
        }

        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /**
     * The catalogue code of a failure, or null when it is not one of ours.
     *
     * Kept as a static helper because the guard test asserts the code of every
     * mapped type against the catalogue without standing a container up.
     */
    static String codeOf(Throwable e) {
        return e instanceof PublicationException pe ? pe.code() : null;
    }
}

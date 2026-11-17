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

import org.junit.jupiter.api.Test;
import org.niord.core.publication.PublicationResolver;
import org.niord.core.publication.series.IssueDeleteService;
import org.niord.core.publication.series.IssueLifecycleService;
import org.niord.core.publication.series.IssuePublicWindowService;
import org.niord.core.publication.series.IssuePublishService;
import org.niord.core.publication.series.IssueRenderService;
import org.niord.core.publication.series.MemberResolutionService;
import org.niord.core.publication.series.PublicationException;
import org.niord.core.publication.series.SeriesValidator;
import org.niord.core.publication.series.criteria.CriteriaParseException;
import org.niord.core.publication.series.criteria.CriteriaResolver;
import org.niord.core.publication.series.criteria.CriterionKind;
import org.niord.core.publication.series.resolve.IssueNaming;
import org.niord.core.web.NiordWebException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the advice is registered FOR, which decides what it does to everything
 * else in the deployment.
 *
 * The defect these assertions exist for: the handler was declared against
 * RuntimeException, which in a deployment with no competing handler makes it the
 * handler for every runtime failure anywhere. It recognised what it knew and
 * rethrew the rest -- and a throw from inside a handler does not return the
 * exception to the container, it escapes the response pipeline entirely. So a
 * plain 403 raised by any unrelated resource, and every request that matched no
 * route, came back as a 500.
 *
 * That is not reproducible here: this is not a container test. What IS checkable,
 * and is what actually decides the behaviour, is the type the handler declares --
 * so that is what is asserted.
 *
 * PORT: the JAX-RS {@code @Provider ExceptionMapper<PublicationException>} is now
 * a {@code @RestControllerAdvice} with one {@code @ExceptionHandler}, so the
 * declared type is read off the handler method's parameter rather than off the
 * interface type argument, and the registration fact is the advice annotation.
 */
public class PublicationExceptionMapperContractTest {

    /**
     * The advice handles publication failures and nothing else.
     *
     * A NiordWebException is not a PublicationException, so Spring never offers
     * one to this handler and its own status survives -- which is the whole fix,
     * expressed as the one fact that produces it.
     */
    @Test
    public void theMapperIsScopedToPublicationFailuresOnly() {
        Type mapped = mappedType();
        assertEquals(PublicationException.class, mapped,
                "the handler must be declared for PublicationException. Declared for "
                        + "RuntimeException it becomes the handler for every runtime failure in the "
                        + "deployment, and rethrowing what it does not recognise escapes the "
                        + "container's own handling rather than deferring to it.");

        assertFalse(PublicationException.class.isAssignableFrom(NiordWebException.class),
                "a NiordWebException must not be a PublicationException, or the advice would "
                        + "take over the status the caller chose");
    }

    /**
     * A bare 403 from any resource keeps its status.
     *
     * Stated as the concrete case because it is the one that was broken: a
     * legacy endpoint throwing a 403 answered 500, so a client could not tell a
     * permission refusal from a server fault.
     */
    @Test
    public void abareForbiddenIsNotTurnedIntoAServerError() {
        NiordWebException forbidden = new NiordWebException("not yours", 403);
        assertEquals(403, forbidden.getStatusCode().value());
        assertFalse(PublicationException.class.isInstance(forbidden),
                "a 403 reaching the publication advice would be re-answered from the "
                        + "error catalogue, which does not know it -- and an unknown code is 500");
        assertNull(PublicationExceptionAdvice.codeOf(forbidden),
                "the advice must not claim a code for an exception that is not ours");

        // And the same for a route that matched nothing, which is the other half
        // of what the RuntimeException registration swallowed.
        assertNull(PublicationExceptionAdvice.codeOf(new NiordWebException(404)));
        assertNull(PublicationExceptionAdvice.codeOf(new IllegalStateException("unrelated")));
    }

    /**
     * Every failure the API raises is a PublicationException carrying a
     * catalogued code.
     *
     * The list is spelled out rather than discovered, because the property being
     * asserted is that a failure type added later joins the hierarchy: a new
     * RuntimeException outside it would no longer be mapped at all, and would
     * reach the caller as a bare 500 with a stack trace.
     */
    @Test
    public void everyMappedFailureIsAPublicationExceptionWithACataloguedCode() {
        List<PublicationException> all = List.of(
                new IssueLifecycleService.TransitionRefusedException("ISSUE_NOT_OPEN", "…"),
                new IssuePublishService.AlreadyPublishedException(new Date()),
                new IssuePublishService.WarningsNotAcknowledgedException(List.of("STALE_OVERRIDE")),
                new IssuePublishService.ArchiveFailedException("…", new RuntimeException()),
                new IssueRenderService.RenderFailedException("…", new RuntimeException()),
                new MemberResolutionService.UnresolvableOperandException("…"),
                new IssueDeleteService.IssueCitedException("…",
                        List.of(new PublicationResolver.CitingMessage("NM-1-26", "uid-1", "A notice")),
                        1),
                new IssueNaming.UnknownTokenException("yeer"),
                new CriteriaResolver.EmptyOperandException(CriterionKind.AREA),
                new CriteriaParseException("…", new RuntimeException()),
                new IssueLifecycleService.TransitionRefusedException(
                        IssuePublicWindowService.INVALID, "…"));

        for (PublicationException e : all) {
            assertNotNull(e.code(), e.getClass().getSimpleName() + " carries no code");
            assertTrue(PublicationErrorCatalogue.knows(e.code()),
                    e.getClass().getSimpleName() + " raises the uncatalogued code '" + e.code()
                            + "', which resolves to 500 -- the exact failure the catalogue exists "
                            + "to prevent");
            assertEquals(e.code(), PublicationExceptionAdvice.codeOf(e));
        }
    }

    /**
     * A refused deletion puts the citing messages ON THE WIRE, not only in the
     * sentence.
     *
     * This is the whole deliverable of that refusal. Told "some messages cite
     * this", an admin has to search the estate by hand for something that is not
     * in any list -- the citation lives inside message HTML. Told WHICH, the
     * dialog renders a row per message that opens straight into it.
     *
     * The keys are asserted rather than assumed because they are a contract with
     * a client written in another repository: renaming one here breaks the
     * dialog, and nothing in either build would say so.
     *
     * The count is the true total while the list is bounded, so an issue cited by
     * three hundred messages produces a response somebody can render and a
     * sentence somebody can read. Asserted with a total LARGER than the sample,
     * because a fixture where they happen to be equal would pass just as well
     * against a body that reported the list length.
     */
    @Test
    public void aCitationRefusalNamesTheMessagesAndCountsThemAll() {
        ResponseEntity<Map<String, Object>> response = new PublicationExceptionAdvice().toResponse(
                new IssueDeleteService.IssueCitedException("cited by 37 messages",
                        List.of(new PublicationResolver.CitingMessage("NM-1-26", "uid-1",
                                        "Buoy off Hesselo withdrawn"),
                                new PublicationResolver.CitingMessage(null, "uid-2",
                                        "Firing exercise in the Kattegat")),
                        37));

        assertEquals(409, response.getStatusCode().value(),
                "a deletion refused because the estate points at the issue is a state conflict, "
                        + "not a malformed request");

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("ISSUE_CITED", body.get("code"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("citingMessages");
        assertNotNull(messages, "the refusal carries no citingMessages, so the dialog has nothing "
                + "to list and the admin is sent to search by hand");
        assertEquals(2, messages.size());
        assertEquals("NM-1-26", messages.get(0).get("messageId"));
        assertEquals("uid-1", messages.get(0).get("uid"));
        assertEquals("Buoy off Hesselo withdrawn", messages.get(0).get("title"),
                "the row carries no title, so a client that cannot show a bare number has nothing "
                        + "else to render");

        // A message with no number yet carries a NULL messageId and is named by its
        // title. The uid used to be put in that field, which a dialog cannot tell
        // apart from a real message number -- so it renders a link to a number
        // nobody can look up, and the one row that most needs explaining is the one
        // that reads as ordinary.
        assertNull(messages.get(1).get("messageId"),
                "an unnumbered message was given a messageId; whatever string is there will be "
                        + "rendered as the number an editor cites");
        assertEquals("uid-2", messages.get(1).get("uid"));
        assertEquals("Firing exercise in the Kattegat", messages.get(1).get("title"),
                "the unnumbered row has neither a number nor a title, so nothing on it names the "
                        + "message blocking the deletion");

        assertEquals(37L, body.get("citingCount"),
                "the count must be the true total rather than the length of the bounded list, or "
                        + "the dialog tells the admin there are two when there are thirty-seven");
    }

    /**
     * The two public-period refusals are catalogued, and they are NOT the same status.
     *
     * PUBLIC_WINDOW_CLOSED is a state that can change: re-open the period and the
     * identical request goes through, which is what the refusal tells the caller
     * to do. PUBLIC_WINDOW_INVALID never can -- both instants are IN the request,
     * so re-sending it fails identically and only different instants succeed.
     * Answering one with the other's status is how "retry on 409" becomes a loop
     * against a body that will never be accepted.
     */
    @Test
    public void thetwoPublicPeriodRefusalsAreCataloguedAndDistinct() {
        assertEquals(400, PublicationErrorCatalogue.statusOf(IssuePublicWindowService.INVALID));
        assertEquals(409, PublicationErrorCatalogue.statusOf("PUBLIC_WINDOW_CLOSED"));

        ResponseEntity<Map<String, Object>> response = new PublicationExceptionAdvice().toResponse(
                new IssueLifecycleService.TransitionRefusedException(IssuePublicWindowService.INVALID,
                        "the public period would end before it starts"));
        assertEquals(400, response.getStatusCode().value());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("PUBLIC_WINDOW_INVALID", body.get("code"),
                "the code is the contract; a client branches on it and nothing else");
    }

    /** The field errors survive the move onto the base type. */
    @Test
    public void aValidationRefusalStillCarriesItsFieldErrors() {
        var refusal = new IssueLifecycleService.TransitionRefusedException("SERIES_INVALID", "2 fail",
                List.of(new SeriesValidator.FieldError("S-22", "releaseMode", "not supported"),
                        new SeriesValidator.FieldError("S-23", "reportParams.week", "injected")));

        assertEquals(2, refusal.fieldErrors().size(),
                "a form cannot render \"two rules fail\" against a control; the rules already say "
                        + "which field each belongs to");
        assertEquals("releaseMode", refusal.fieldErrors().get(0).field());

        // And an ordinary refusal carries an empty list rather than null, so the
        // advice does not have to guard every read.
        assertTrue(new IssueLifecycleService.TransitionRefusedException("ISSUE_NOT_OPEN", "…")
                .fieldErrors().isEmpty());
    }

    /** The class is still a registered advice. */
    @Test
    public void theMapperIsStillRegistered() {
        assertTrue(PublicationExceptionAdvice.class.isAnnotationPresent(RestControllerAdvice.class),
                "without @RestControllerAdvice the handler is not registered at all and every coded "
                        + "refusal becomes a 500");
    }

    /** The declared parameter type of the one @ExceptionHandler method. */
    private static Type mappedType() {
        Type mapped = null;
        for (Method m : PublicationExceptionAdvice.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(ExceptionHandler.class)) {
                assertNull(mapped, "PublicationExceptionAdvice declares more than one "
                        + "@ExceptionHandler; each one widens what the advice answers for");
                assertEquals(1, m.getParameterCount(), "the handler takes the exception and nothing else");
                mapped = m.getParameterTypes()[0];
            }
        }
        if (mapped == null) {
            throw new AssertionError("PublicationExceptionAdvice no longer declares an @ExceptionHandler");
        }
        return mapped;
    }
}

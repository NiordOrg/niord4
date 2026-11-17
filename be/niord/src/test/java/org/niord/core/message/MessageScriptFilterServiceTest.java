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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MessageScriptFilterService}, the application-scoped service that compiles a
 * JavaScript message filter into a cached {@link MessageScriptFilterEvaluator} (one evaluator per filter
 * string) and uses it to decide whether a given {@link Message} is included.
 *
 * <p>{@code includeMessage} is pure in-memory logic backed by Nashorn (which is on the test classpath as a
 * compile dependency of the {@code niord} module) plus an in-process {@link java.util.concurrent.ConcurrentHashMap}
 * cache &mdash; no database, transaction or CDI context is needed. The service is therefore instantiated
 * directly with {@code new}; only its {@code @Inject Logger log} (referenced on every {@code includeMessage}
 * call) is supplied, via reflection, with a real SLF4J logger. This keeps the test framework-neutral and lets
 * it read the package-private {@code filters} cache directly &mdash; something a CDI client proxy would hide,
 * because field reads on an {@code @ApplicationScoped} proxy do not delegate to the contextual instance.
 *
 * <p>Behaviour covered: a valid filter includes/excludes by its boolean expression; repeated evaluation of the
 * same filter reuses the cached evaluator (no new cache entry); an invalid filter falls back to and caches
 * {@link MessageScriptFilterEvaluator#EXCLUDE_ALL}; blank and {@code null} filters both normalise to the
 * empty-string cache key; and the {@code data} argument is forwarded to the filter function.
 */
public class MessageScriptFilterServiceTest {

    private MessageScriptFilterService service;

    @BeforeEach
    public void setUp() throws Exception {
        service = new MessageScriptFilterService();
        // The service references its @Inject Logger on every call; wire a real one (the field is private).
        Field logField = MessageScriptFilterService.class.getDeclaredField("log");
        logField.setAccessible(true);
        logField.set(service, LoggerFactory.getLogger(MessageScriptFilterServiceTest.class));
    }

    private Message message(Status status) {
        Message msg = new Message();
        msg.setMainType(MainType.NW);
        msg.setType(Type.LOCAL_WARNING);
        msg.setStatus(status);
        return msg;
    }

    /** A valid filter includes a matching message and excludes a non-matching one. */
    @Test
    public void evaluatesValidFilter() {
        assertTrue(service.includeMessage("msg.status == Status.PUBLISHED", message(Status.PUBLISHED), null),
                "PUBLISHED message must match the status filter");
        assertFalse(service.includeMessage("msg.status == Status.PUBLISHED", message(Status.DRAFT), null),
                "DRAFT message must not match the PUBLISHED status filter");
    }

    /** Re-evaluating the same filter reuses the cached evaluator without adding a new cache entry. */
    @Test
    public void cachesEvaluatorForFilter() {
        String filter = "msg.mainType == MainType.NW";

        assertTrue(service.includeMessage(filter, message(Status.PUBLISHED), null));
        assertTrue(service.filters.containsKey(filter), "the filter must be cached after first evaluation");

        MessageScriptFilterEvaluator cached = service.filters.get(filter);
        int sizeAfterFirst = service.filters.size();

        assertTrue(service.includeMessage(filter, message(Status.PUBLISHED), null));
        assertEquals(sizeAfterFirst, service.filters.size(),
                "re-evaluating the same filter must not add a new cache entry");
        assertSame(cached, service.filters.get(filter), "the cached evaluator instance must be reused");
    }

    /** An invalid filter falls back to EXCLUDE_ALL, caches it, and returns false on every evaluation. */
    @Test
    public void invalidFilterCachesExcludeAllAndReturnsFalse() {
        String bad = "!!! invalid javascript !!!";

        assertFalse(service.includeMessage(bad, message(Status.PUBLISHED), null),
                "an invalid filter must not include any message");
        assertSame(MessageScriptFilterEvaluator.EXCLUDE_ALL, service.filters.get(bad),
                "an invalid filter must cache the shared EXCLUDE_ALL evaluator");

        assertFalse(service.includeMessage(bad, message(Status.PUBLISHED), null),
                "the cached EXCLUDE_ALL evaluator must keep excluding the message");
    }

    /** Blank and null filters both normalise to the empty-string cache key and exclude messages. */
    @Test
    public void blankAndNullFilterMapToEmptyKey() {
        assertFalse(service.includeMessage("", message(Status.PUBLISHED), null),
                "a blank filter must not include any message");
        assertFalse(service.includeMessage(null, message(Status.PUBLISHED), null),
                "a null filter must not include any message");

        assertTrue(service.filters.containsKey(""),
                "blank and null filters must normalise to the empty-string cache key");
        assertNotNull(service.filters.get(""));
    }

    /** The data argument is passed through to the compiled filter function. */
    @Test
    public void dataParameterForwardedToFilter() {
        String filter = "data == 42";

        assertTrue(service.includeMessage(filter, message(Status.PUBLISHED), 42),
                "filter referencing data must include when data matches");
        assertFalse(service.includeMessage(filter, message(Status.PUBLISHED), 0),
                "filter referencing data must exclude when data does not match");
    }
}

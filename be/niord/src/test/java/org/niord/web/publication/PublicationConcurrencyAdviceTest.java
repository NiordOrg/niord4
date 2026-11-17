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

import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.niord.core.conf.RestExceptionStatusAdvice;
import org.niord.web.AreaRestService;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerTypePredicate;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A write that lost a race at the {@code @Version} update answers 409, not 500 (LOCK1): the publication
 * controllers in their coded body, every other controller as a problem detail.
 */
class PublicationConcurrencyAdviceTest {

    private static final List<RuntimeException> LOCK_FAILURES = List.of(
            new ObjectOptimisticLockingFailureException("PublicationSeries", 42L),
            new OptimisticLockException("Unexpected row count (expected row count 1 but was 0)"));

    @Test
    void aPublicationWriteThatLostTheRaceAnswersStaleVersion() {
        for (RuntimeException e : LOCK_FAILURES) {
            ResponseEntity<Map<String, Object>> response = new PublicationConcurrencyAdvice().toResponse(e);
            assertEquals(409, response.getStatusCode().value(), e.getClass().getSimpleName());
            Map<String, Object> body = response.getBody();
            assertNotNull(body);
            assertEquals(List.of("code", "message"), List.copyOf(body.keySet()),
                    "the publication shape, with no revisions: none is known where the failure is caught");
            assertEquals("STALE_VERSION", body.get("code"));
            assertFalse(((String) body.get("message")).contains("row count"),
                    "the database's sentence stays in the log");
        }
    }

    @Test
    void thePublicationAdviceAnswersThePublicationControllersOnlyAndIsAskedFirst() {
        RestControllerAdvice advice = PublicationConcurrencyAdvice.class.getAnnotation(RestControllerAdvice.class);
        assertNotNull(advice, "not registered");
        HandlerTypePredicate scope = HandlerTypePredicate.forBasePackageClass(advice.basePackageClasses());
        assertTrue(scope.test(PublicationSeriesRestService.class));
        assertTrue(scope.test(PublicationIssueRestService.class));
        assertFalse(scope.test(AreaRestService.class),
                "another controller would get a publication body instead of a problem detail");

        Order order = PublicationConcurrencyAdvice.class.getAnnotation(Order.class);
        assertNotNull(order, "without an order the global advice may be asked first");
        assertEquals(Ordered.HIGHEST_PRECEDENCE, order.value());

        assertEquals(Set.of(ObjectOptimisticLockingFailureException.class, OptimisticLockException.class),
                handledTypes(PublicationConcurrencyAdvice.class));
    }

    @Test
    void anyOtherWriteThatLostTheRaceAnswers409AsAProblemDetail() {
        for (RuntimeException e : LOCK_FAILURES) {
            ProblemDetail problem = new RestExceptionStatusAdvice().handleOptimisticLock(e);
            assertEquals(409, problem.getStatus(), e.getClass().getSimpleName());
            assertNotNull(problem.getDetail());
            assertFalse(problem.getDetail().contains("row count"), "the database's sentence stays in the log");
        }
        assertTrue(handledTypes(RestExceptionStatusAdvice.class).containsAll(
                Set.of(ObjectOptimisticLockingFailureException.class, OptimisticLockException.class)));
    }

    private static Set<Class<?>> handledTypes(Class<?> adviceType) {
        Set<Class<?>> types = new HashSet<>();
        for (Method m : adviceType.getDeclaredMethods()) {
            ExceptionHandler handler = m.getAnnotation(ExceptionHandler.class);
            if (handler != null) {
                types.addAll(List.of(handler.value()));
            }
        }
        return types;
    }
}

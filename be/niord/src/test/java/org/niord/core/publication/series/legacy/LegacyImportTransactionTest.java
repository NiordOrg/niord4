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

package org.niord.core.publication.series.legacy;

import org.junit.jupiter.api.Test;
import org.niord.core.service.BaseService;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The estate-scale entry points must not run inside anybody's transaction but
 * their own -- including the one they would otherwise inherit.
 *
 * THE FAILURE THIS PINS IS INVISIBLE IN THE SOURCE OF THE METHOD ITSELF.
 * {@link BaseService} is annotated {@code @Transactional} at class level, and
 * although Spring's {@code @Transactional} is not {@code @Inherited}, Spring's own
 * attribute lookup walks the superclass chain -- so every public method of every
 * service extending it is wrapped by the REQUIRED interceptor on whatever the
 * ambient transaction budget turns out to be, with nothing at the method to say so.
 *
 * For a 784-second import that was fatal in the one way that looks like success on
 * the previous deployment: the work committed through the transaction the method
 * opened for itself, the ambient one was reaped at the container's default budget,
 * and the interceptor's commit on the way out threw. The caller read 500 over a
 * database holding the complete archive, and an operator seeing that re-runs an
 * import that had worked. Nothing sets a default transaction timeout here, so that
 * exact failure cannot fire today -- which is why it is asserted rather than
 * observed: introducing one later would revive it silently.
 *
 * Reflection rather than a run, because the annotation IS the contract: there is
 * no observable difference between the two shapes until the operation runs longer
 * than the ambient budget, which is minutes of real estate and not something a
 * test can afford to reproduce.
 */
public class LegacyImportTransactionTest {

    /**
     * The premise. If the base class ever stops being transactional the
     * annotations below become belt-and-braces rather than load-bearing -- and
     * whoever notices should be told that here, not by a 500 in a go-live window.
     */
    @Test
    public void theBaseClassIsWhereTheAmbientTransactionComesFrom() {
        assertTrue(BaseService.class.isAssignableFrom(LegacyImportService.class),
                "the import service no longer extends the transactional base class; "
                        + "re-check whether the NOT_SUPPORTED annotations below are still needed");
        assertNotNull(BaseService.class.getAnnotation(Transactional.class),
                "BaseService is no longer @Transactional, so the inherited binding this "
                        + "test exists to neutralise may have moved somewhere else");
        // Spring's @Transactional is deliberately NOT @Inherited -- it resolves a
        // class-level attribute through its own type-hierarchy search instead, which is
        // what makes the binding reach a subclass. Asserted through that search rather
        // than through java.lang.annotation.Inherited, or this reads as "the premise is
        // gone" on a framework where it very much is not.
        assertNotNull(
                AnnotatedElementUtils.findMergedAnnotation(LegacyImportService.class, Transactional.class),
                "the class-level @Transactional on the base class no longer reaches the import "
                        + "service through Spring's attribute lookup; the inheritance premise has "
                        + "changed");
    }

    @Test
    public void theEstateScaleEntryPointsJoinNoCallersTransaction() throws NoSuchMethodException {
        for (String name : new String[] { "run", "dryRun", "undo" }) {
            Method method = LegacyImportService.class.getDeclaredMethod(name);
            Transactional tx = method.getAnnotation(Transactional.class);
            assertNotNull(tx, "LegacyImportService." + name + "() carries no method-level "
                    + "@Transactional, so it inherits REQUIRED from BaseService and runs the whole "
                    + "estate inside an ambient transaction on the ambient budget");
            assertEquals(Propagation.NOT_SUPPORTED, tx.propagation(),
                    "LegacyImportService." + name + "() must run in no transaction but the one it "
                            + "opens itself, with the budget it chose");
        }
    }
}

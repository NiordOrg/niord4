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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The estate-scale endpoints must not impose a request transaction on work that
 * outlives one.
 *
 * WHAT WENT WRONG WITHOUT THIS, MEASURED. The series resource is @Transactional
 * at class level, which is right for the endpoints that write one row. An
 * endpoint that drives the whole archive is a different animal: it commits
 * through transactions of its own, sized for the estate or bounded per batch, and
 * the ambient one the class annotation opens sits there for the duration on
 * whatever budget the platform imposes. Where that budget was 240 seconds the
 * reaper took it long before the work finished, and the failure as the
 * transaction interceptor unwound surfaced as a 500 over a database that had
 * every imported row in it. The import ran 784 seconds against that 240-second
 * default and answered exactly that; the danger is that an operator believes it
 * and re-runs an import that had worked.
 *
 * PORT: this deployment configures no transaction timeout at all -- no
 * @Transactional(timeout = ...) anywhere in main, no spring.transaction
 * default-timeout, so JpaTransactionManager runs at TIMEOUT_DEFAULT and there is
 * no reaper here today. The contract stays for the reason it was written: these
 * endpoints are not to be wrapped, so a global default added later cannot
 * silently cut an import short.
 *
 * A DECLARED SET, not a rule. Which endpoints are estate-scale is a fact about
 * what they call, not something readable off a signature -- an endpoint that
 * walks every series and one that reads a single row look identical from here.
 * So the list is written down and its absentees are pinned too: an endpoint that
 * leaves the set silently is the regression this test exists to catch.
 */
public class PublicationTransactionContractTest {

    /**
     * The endpoints that must carry NOT_SUPPORTED, by method name.
     *
     * import-legacy/validate, import-legacy (POST and DELETE): each opens one
     * transaction with a budget sized for the whole estate.
     * import-check: it reads every series and every issue in the installation,
     * and the service behind it manages its own transaction.
     */
    private static final Set<String> ESTATE_SCALE = new LinkedHashSet<>(Set.of(
            "importDryRun",
            "importLegacy",
            "undoImport",
            "importCheck"));

    @Test
    public void everyEstateScaleEndpointRunsOutsideTheRequestTransaction() {
        Set<String> found = new TreeSet<>();
        for (Method method : PublicationSeriesRestService.class.getDeclaredMethods()) {
            if (!ESTATE_SCALE.contains(method.getName())) {
                continue;
            }
            found.add(method.getName());
            Transactional tx = method.getAnnotation(Transactional.class);
            assertNotNull(tx, method.getName() + " carries no method-level @Transactional, so the "
                    + "class annotation wraps it in a request transaction on the default budget");
            assertEquals(Propagation.NOT_SUPPORTED, tx.propagation(),
                    method.getName() + " must run outside the request transaction");
        }
        assertEquals(new TreeSet<>(ESTATE_SCALE), found,
                "an endpoint named in the estate-scale set no longer exists under that name; "
                        + "rename it here rather than dropping it, or the contract stops being checked");
    }

    /**
     * Nothing else on the resource may opt out of the class transaction, and
     * nothing may opt into a different one.
     *
     * REQUIRES_NEW is the one that would be tempting and wrong here: it commits
     * the endpoint's writes independently of the request, so a later refusal in
     * the same call leaves half a change behind.
     */
    @Test
    public void noOtherEndpointRedeclaresItsTransaction() {
        for (Method method : PublicationSeriesRestService.class.getDeclaredMethods()) {
            Transactional tx = method.getAnnotation(Transactional.class);
            if (tx == null) {
                continue;
            }
            assertTrue(ESTATE_SCALE.contains(method.getName()),
                    method.getName() + " declares @Transactional(" + tx.propagation() + ") but is not in "
                            + "the estate-scale set. Either it belongs there -- say so above, with "
                            + "what it drives -- or it should inherit the class annotation.");
        }
    }
}

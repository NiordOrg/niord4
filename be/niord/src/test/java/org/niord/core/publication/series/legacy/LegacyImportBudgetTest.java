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
package org.niord.core.publication.series.legacy;

import org.junit.jupiter.api.Test;
import org.niord.core.publication.series.PublicationTxRunner;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The budget the legacy import's log line reports is the timeout its transaction is opened with: one number,
 * {@link PublicationTxRunner#IMPORT_TIMEOUT_SECONDS}, where there were two literals that a later edit could part.
 */
class LegacyImportBudgetTest {

    @Test
    void theReportedBudgetIsTheTransactionsTimeout() throws Exception {
        Transactional tx = PublicationTxRunner.class.getMethod("callWithImportBudget", Supplier.class)
                .getAnnotation(Transactional.class);

        assertEquals(PublicationTxRunner.IMPORT_TIMEOUT_SECONDS, tx.timeout());
        assertEquals(PublicationTxRunner.IMPORT_TIMEOUT_SECONDS, LegacyImportService.IMPORT_TIMEOUT_SECONDS);
    }
}

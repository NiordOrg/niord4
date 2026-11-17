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
package org.niord.core.mail;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.TransactionDefinition;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * How many database connections the mail sender holds (the review's finding R4, with the four scheduler threads of
 * P2-7): each of the five sender threads holds one, in its mail's own transaction, while the mail goes to the SMTP
 * server. The scheduler thread that hands the mails out holds none while they are sent: it reads the pending ids
 * without a transaction, so a slow SMTP server costs five of Hikari's connections, not six.
 *
 * <p>Read from the transaction metadata Spring's proxy goes by, for the class-based proxies the application uses
 * ({@code publicMethodsOnly = false}, so the package-private job method counts), the same way the proxy reads it. The
 * class inherits {@code BaseService}'s class-level {@code @Transactional}, so a job method without an annotation of its
 * own would still run in a transaction: it says so itself.
 */
class ScheduledMailSenderTransactionTest {

    private final AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource(false);

    @Test
    void theSchedulerThreadHoldsNoTransactionWhileTheMailsAreSent() throws Exception {
        Method job = ScheduledMailService.class.getDeclaredMethod("sendPendingMails");
        TransactionAttribute attribute = source.getTransactionAttribute(job, ScheduledMailService.class);
        assertNotNull(attribute, "the job's transaction behaviour is declared");
        assertEquals(TransactionDefinition.PROPAGATION_NOT_SUPPORTED, attribute.getPropagationBehavior(),
                "the job hands the mails out without a transaction, so it holds no connection while they are sent");
    }

    @Test
    void eachMailIsSentInATransactionOfItsOwn() throws Exception {
        Method send = MailService.class.getDeclaredMethod("sendScheduledMail", Integer.class);
        TransactionAttribute attribute = source.getTransactionAttribute(send, MailService.class);
        assertNotNull(attribute);
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, attribute.getPropagationBehavior(),
                "each mail is claimed and marked sent in its own transaction");
    }
}

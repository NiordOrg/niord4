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

package org.niord.core.user;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.mail.ScheduledMail;
import org.niord.core.mail.ScheduledMailRecipient;
import org.niord.core.mailinglist.MailingList;
import org.niord.core.service.BaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Niord's part of deleting a user, in its own short transaction: Niord's record of the user stops mailing the user.
 *
 * <p>{@code UserService.deleteKeycloakUser} reads the user name of the Keycloak account, calls {@link #stopMailing},
 * and deletes the account only once this part has committed. So every failure is repaired by deleting again: a
 * failure here leaves the account and Niord as they were, and a failed Keycloak delete leaves the account, which the
 * next delete reads again, finding nothing left to do here. In the other order a failure here could not be repaired,
 * as the user name of a deleted account can no longer be read.
 *
 * <p>Deliberate consequence: when the Keycloak delete fails and is not repeated, the user keeps the account but not the
 * mailing lists. The address comes back at the user's next sign-in.
 */
@Service
public class UserDeletionService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(UserDeletionService.class);

    /**
     * Takes the user off every mailing list, removes the user from the mails queued for the user and not yet sent
     * (pending, or held by the mail filter, LOG or a list that did not name every recipient, which a system
     * administrator may still send again), and
     * clears the address of the user's record; and, on return, commits. The record itself stays, with the user's name,
     * for the messages, comments and history the user made. Does nothing for a user without a record.
     *
     * @param username the user name of the user to stop mailing
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stopMailing(String username) {
        User user = StringUtils.isBlank(username) ? null : em.createNamedQuery("User.findByUsername", User.class)
                .setParameter("username", username)
                .getResultStream()
                .findFirst()
                .orElse(null);
        if (user == null) {
            return;
        }

        List<MailingList> mailingLists = em.createNamedQuery("MailingList.findByUser", MailingList.class)
                .setParameter("user", user)
                .getResultList();
        mailingLists.forEach(mailingList -> mailingList.getUsers().removeIf(u -> u.getId().equals(user.getId())));

        // A mailing list queues one mail per recipient, addressed with the address the user's record computes. A mail
        // left with no recipient is removed: it was never sent (pending or held), and no one is left to send it to.
        int queuedMails = 0;
        if (StringUtils.isNotBlank(user.getEmail())) {
            List<ScheduledMailRecipient> queued = em
                    .createNamedQuery("ScheduledMailRecipient.findUnsentByAddress", ScheduledMailRecipient.class)
                    .setParameter("address", user.computeInternetAddress())
                    .getResultList();
            for (ScheduledMailRecipient recipient : queued) {
                ScheduledMail mail = recipient.getMail();
                mail.getRecipients().remove(recipient);
                if (mail.getRecipients().isEmpty()) {
                    em.remove(mail);
                }
            }
            queuedMails = queued.size();
        }

        user.setEmail(null);
        log.info("Stopped mailing the deleted user " + user.getUsername() + ": off " + mailingLists.size()
                + " mailing lists, off " + queuedMails + " queued mails, its e-mail address cleared");
    }
}

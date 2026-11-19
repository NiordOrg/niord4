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

package org.niord.core.mail.vo;

import org.niord.core.mail.ScheduledMail.Status;
import org.niord.model.IJsonSerializable;

import java.util.Date;
import java.util.List;

/**
 * The request of the Mail Queue's Send again ({@code POST /rest/scheduled-mails/send-again}): the mails to send again,
 * each with the status and the time of the last Send again the page showed, so a mail that changed since the page
 * read it is refused rather than sent.
 */
@SuppressWarnings("unused")
public class SendAgainRequestVo implements IJsonSerializable {

    List<Item> mails;

    public List<Item> getMails() {
        return mails;
    }

    public void setMails(List<Item> mails) {
        this.mails = mails;
    }

    /** One mail to send again, as the page showed it */
    public static class Item implements IJsonSerializable {

        Integer id;
        Status status;
        /**
         * The mail's sentAgainAt as the page showed it, or null: epoch milliseconds, or the ISO-8601 text the answers
         * of the Mail Queue carry, as Jackson reads a date
         */
        Date sentAgainAt;

        public Item() {
        }

        public Item(Integer id, Status status, Date sentAgainAt) {
            this.id = id;
            this.status = status;
            this.sentAgainAt = sentAgainAt;
        }

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public Status getStatus() {
            return status;
        }

        public void setStatus(Status status) {
            this.status = status;
        }

        public Date getSentAgainAt() {
            return sentAgainAt;
        }

        public void setSentAgainAt(Date sentAgainAt) {
            this.sentAgainAt = sentAgainAt;
        }
    }
}

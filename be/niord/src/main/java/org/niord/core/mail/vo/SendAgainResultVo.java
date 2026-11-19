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

import org.niord.model.IJsonSerializable;

import java.util.ArrayList;
import java.util.List;

/**
 * The answer of the Mail Queue's Send again: the ids of the mails queued to be sent again, and the mails left as they
 * were, each with its reason.
 */
@SuppressWarnings("unused")
public class SendAgainResultVo implements IJsonSerializable {

    /**
     * Why a mail was not sent again. GONE: no such mail (deleted since). RECORD: a record of a failure, never sent.
     * CHANGED: its status or its last Send again differs from what the page showed.
     */
    public enum Reason { GONE, CHANGED, RECORD }

    List<Integer> queued = new ArrayList<>();
    List<Refusal> refused = new ArrayList<>();

    public List<Integer> getQueued() {
        return queued;
    }

    public void setQueued(List<Integer> queued) {
        this.queued = queued;
    }

    public List<Refusal> getRefused() {
        return refused;
    }

    public void setRefused(List<Refusal> refused) {
        this.refused = refused;
    }

    /** A mail that was not sent again, and why */
    public static class Refusal implements IJsonSerializable {

        Integer id;
        Reason reason;

        public Refusal() {
        }

        public Refusal(Integer id, Reason reason) {
            this.id = id;
            this.reason = reason;
        }

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public Reason getReason() {
            return reason;
        }

        public void setReason(Reason reason) {
            this.reason = reason;
        }
    }
}

/*
 * Copyright 2017 Danish Maritime Authority.
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
package org.niord.core.mailinglist;

import org.niord.core.mailinglist.vo.MailingListDescVo;
import org.niord.core.mailinglist.vo.MailingListTriggerDescVo;
import org.niord.core.mailinglist.vo.MailingListTriggerVo;
import org.niord.core.mailinglist.vo.MailingListVo;
import org.niord.core.script.ScriptResource;
import org.niord.core.user.ContactMapper;
import org.niord.core.user.UserMapper;
import org.niord.model.DataFilter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.stream.Collectors;

import static org.niord.core.mailinglist.TriggerType.SCHEDULED;
import static org.niord.core.mailinglist.TriggerType.STATUS_CHANGE;

/**
 * Maps between the {@link MailingList} entity and {@link MailingListVo}.
 *
 * <p>Replaces the {@code toVo(DataFilter)} method and the {@code MailingList(MailingListVo)} conversion
 * constructor previously embedded in the {@link MailingList} entity. {@code MailingListVo} is single-tier
 * (no System* subclass) but honours a {@link DataFilter}. The users/contacts lists are mapped via the
 * {@link UserMapper}/{@link ContactMapper} (the entity used {@code User::toVo}/{@code User::new} and
 * {@code Contact::toVo}/{@code Contact::new}). The owned {@link MailingListDesc} descriptions and the
 * {@link MailingListTrigger}s (with their {@link MailingListTriggerDesc} descriptions) are mapped inline,
 * preserving the trigger constructor's per-type reset logic and the {@code ScriptResource.path2type}
 * filtering exactly.
 */
@Component
public class MailingListMapper {

    private final UserMapper userMapper;

    private final ContactMapper contactMapper;

    MailingListMapper(UserMapper userMapper, ContactMapper contactMapper) {
        this.userMapper = userMapper;
        this.contactMapper = contactMapper;
    }

    /** Converts the entity to a value object, honouring the data filter. */
    public MailingListVo toVo(MailingList mailingList, DataFilter filter) {
        if (mailingList == null) {
            return null;
        }

        DataFilter compFilter = filter.forComponent(MailingList.class);

        MailingListVo vo = new MailingListVo();
        vo.setMailingListId(mailingList.getMailingListId());
        vo.setActive(mailingList.isActive());

        if (compFilter.includeDetails()) {
            vo.setDescs(mailingList.getDescs(filter).stream()
                    .map(this::toDescVo)
                    .collect(Collectors.toList()));
            if (!mailingList.getTriggers().isEmpty()) {
                vo.setTriggers(mailingList.getTriggers().stream()
                    .map(t -> toTriggerVo(t, filter))
                    .collect(Collectors.toList()));
            }
            vo.setRecipientNo(mailingList.getUsers().size() + mailingList.getContacts().size());
        }
        if (!mailingList.getUsers().isEmpty() && compFilter.includeField("users")) {
            vo.setUsers(mailingList.getUsers().stream()
                    .map(userMapper::toVo)
                    .collect(Collectors.toList()));
        }
        if (!mailingList.getContacts().isEmpty() && compFilter.includeField("contacts")) {
            vo.setContacts(mailingList.getContacts().stream()
                    .map(contactMapper::toVo)
                    .collect(Collectors.toList()));
        }

        return vo;
    }

    /** Maps a single {@link MailingListDesc} to its value object. */
    private MailingListDescVo toDescVo(MailingListDesc desc) {
        MailingListDescVo vo = new MailingListDescVo();
        vo.setLang(desc.getLang());
        vo.setName(desc.getName());
        vo.setDescription(desc.getDescription());
        return vo;
    }

    /** Maps a single {@link MailingListTrigger} to its value object, honouring the data filter. */
    private MailingListTriggerVo toTriggerVo(MailingListTrigger trigger, DataFilter filter) {
        MailingListTriggerVo vo = new MailingListTriggerVo();
        vo.setType(trigger.getType());
        vo.setScheduleType(trigger.getScheduleType());
        vo.setScheduledExecutionTime(trigger.getScheduledExecutionTime());
        vo.setScheduledExecutionTimeZone(trigger.getScheduledExecutionTimeZone());
        if (!trigger.getStatusChanges().isEmpty()) {
            vo.setStatusChanges(new HashSet<>(trigger.getStatusChanges()));
        }
        vo.setMessageQuery(trigger.getMessageQuery());
        vo.setMessageFilter(trigger.getMessageFilter());
        if (!trigger.getScriptResourcePaths().isEmpty()) {
            vo.setScriptResourcePaths(new ArrayList<>(trigger.getScriptResourcePaths()));
        }
        vo.setPublicReport(trigger.getPublicReport());
        vo.setDescs(trigger.getDescs(filter).stream()
                .map(this::toTriggerDescVo)
                .collect(Collectors.toList()));

        return vo;
    }

    /** Maps a single {@link MailingListTriggerDesc} to its value object. */
    private MailingListTriggerDescVo toTriggerDescVo(MailingListTriggerDesc desc) {
        MailingListTriggerDescVo vo = new MailingListTriggerDescVo();
        vo.setLang(desc.getLang());
        vo.setSubject(desc.getSubject());
        return vo;
    }

    /** Creates a new {@link MailingList} entity from the given value object. */
    public MailingList toEntity(MailingListVo vo) {
        if (vo == null) {
            return null;
        }
        MailingList mailingList = new MailingList();
        mailingList.setMailingListId(vo.getMailingListId());
        mailingList.setActive(vo.getActive() != null && vo.getActive());
        if (vo.getUsers() != null) {
            mailingList.getUsers().addAll(vo.getUsers().stream()
                .map(userMapper::toEntity)
                .collect(Collectors.toList()));
        }
        if (vo.getContacts() != null) {
            mailingList.getContacts().addAll(vo.getContacts().stream()
                .map(contactMapper::toEntity)
                .collect(Collectors.toList()));
        }
        if (vo.getDescs() != null) {
            vo.getDescs().stream()
                    .filter(MailingListDescVo::descDefined)
                    .forEach(desc -> mailingList.createDesc(desc.getLang())
                            .copyDesc(new MailingListDesc(desc)));
        }
        if (vo.getTriggers() != null) {
            vo.getTriggers().forEach(t -> mailingList.addTrigger(toTriggerEntity(t)));
        }
        return mailingList;
    }

    /**
     * Creates a new {@link MailingListTrigger} entity from the given value object.
     * <p>
     * Preserves the per-type reset logic: {@code STATUS_CHANGE} triggers null out the schedule-only
     * fields, while {@code SCHEDULED} triggers clear the status changes and message filter. Also keeps
     * the {@code ScriptResource.path2type} filtering of the script resource paths.
     */
    private MailingListTrigger toTriggerEntity(MailingListTriggerVo vo) {
        MailingListTrigger trigger = new MailingListTrigger();

        trigger.setType(vo.getType());
        trigger.setScheduleType(vo.getScheduleType());
        trigger.setScheduledExecutionTime(vo.getScheduledExecutionTime());
        trigger.setScheduledExecutionTimeZone(vo.getScheduledExecutionTimeZone());
        if (vo.getStatusChanges() != null) {
            trigger.setStatusChanges(new HashSet<>(vo.getStatusChanges()));
        }
        trigger.setMessageQuery(vo.getMessageQuery());
        trigger.setMessageFilter(vo.getMessageFilter());
        if (vo.getScriptResourcePaths() != null) {
            vo.getScriptResourcePaths().stream()
                    .filter(p -> ScriptResource.path2type(p) != null)
                    .forEach(p -> trigger.getScriptResourcePaths().add(p));
        }
        trigger.setPublicReport(vo.getPublicReport());
        if (vo.getDescs() != null) {
            vo.getDescs().stream()
                    .filter(MailingListTriggerDescVo::descDefined)
                    .forEach(desc -> trigger.createDesc(desc.getLang())
                            .copyDesc(new MailingListTriggerDesc(desc)));
        }

        // Reset fields that are not valid for the current trigger type
        if (trigger.getType() == STATUS_CHANGE) {
            trigger.setScheduleType(null);
            trigger.setScheduledExecutionTime(null);
            trigger.setScheduledExecutionTimeZone(null);
            trigger.setMessageQuery(null);
        } else if (trigger.getType() == SCHEDULED) {
            trigger.getStatusChanges().clear();
            trigger.setMessageFilter(null);
        }

        return trigger;
    }
}

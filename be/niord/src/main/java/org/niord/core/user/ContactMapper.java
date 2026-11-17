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
package org.niord.core.user;

import org.niord.core.user.vo.ContactVo;
import org.springframework.stereotype.Component;

/**
 * Maps between the {@link Contact} entity and {@link ContactVo}.
 *
 * <p>Replaces the {@code toVo()} method and the {@code Contact(ContactVo)} conversion constructor
 * previously embedded in the entity. {@code ContactVo} is single-tier.
 */
@Component
public class ContactMapper {

    /** Converts the entity to a value object. */
    public ContactVo toVo(Contact contact) {
        if (contact == null) {
            return null;
        }
        ContactVo vo = new ContactVo();
        vo.setId(contact.getId());
        vo.setEmail(contact.getEmail());
        vo.setName(contact.getName());
        vo.setLanguage(contact.getLanguage());
        return vo;
    }

    /** Creates a new {@link Contact} entity from the given value object. */
    public Contact toEntity(ContactVo vo) {
        if (vo == null) {
            return null;
        }
        Contact contact = new Contact();
        contact.setId(vo.getId());
        contact.setEmail(vo.getEmail());
        contact.setName(vo.getName());
        contact.setLanguage(vo.getLanguage());
        return contact;
    }
}

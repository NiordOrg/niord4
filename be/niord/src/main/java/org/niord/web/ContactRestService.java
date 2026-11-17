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

package org.niord.web;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.user.*;
import org.niord.core.user.vo.ContactVo;
import org.niord.model.IJsonSerializable;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.niord.core.util.TextUtils.encodeCVSLine;

/**
 * REST interface for accessing contacts.
 */
@RestController
@RequestMapping("/rest/contacts")
@Transactional
@RolesAllowed(Roles.ADMIN)
public class ContactRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContactRestService.class);

    private final ContactService contactService;

    private final UserService userService;

    ContactRestService(ContactService contactService, UserService userService) {
        this.contactService = contactService;
        this.userService = userService;
    }

    /** Returns all contacts that matches the search criteria */
    @GetMapping(value = "/search", produces = "application/json;charset=UTF-8")
    public PagedSearchResultVo<ContactVo> search(
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "maxSize", defaultValue = "100") int maxSize,
            @RequestParam(name = "page", defaultValue = "0") int page) {

        ContactSearchParams params = new ContactSearchParams()
                .name(name);
        params.maxSize(maxSize).page(page);

        return contactService.searchContacts(params)
                .map(Contact::toVo);
    }

    /**
     * Creates a new contact
     * @param contact the template contact to add
     */
    @PostMapping(value = {"/contact", "/contact/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public ContactVo addContact(@RequestBody ContactVo contact) throws Exception {
        log.info("Creating contact " + contact.getEmail());
        return contactService.createContact(new Contact(contact)).toVo();
    }

    /**
     * Updates the contact  with the given id
     * @param contact the template contact to update
     */
    @PutMapping(value = "/contact/{id}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public ContactVo updateContact(@PathVariable("id") Integer id, @RequestBody ContactVo contact) throws Exception {
        if (!Objects.equals(id, contact.getId())) {
            throw new NiordWebException(400);
        }
        log.info("Updating contact " + contact.getId());
        return contactService.updateContact(new Contact(contact)).toVo();
    }

    /**
     * Deletes the contact with the given id
     * @param id the contact to delete
     */
    @DeleteMapping("/contact/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteContact(@PathVariable("id") Integer id) throws Exception {
        log.info("Deleting contact " + id);
        contactService.deleteContact(id);
    }

    /** Exports all contacts in CSV format */
    @GetMapping(value = "/export", produces = "text/plain;charset=UTF-8")
    @PermitAll // Checked programmatically
    public String exportCSV(@RequestParam(name = "separator", defaultValue = ";") String separator) {

        // If a ticket is defined, check if programmatically
        if (!userService.isCallerInRole(Roles.ADMIN)) {
            throw new NiordWebException(403);
        }

        return contactService.getAllContacts().stream()
                .map(c -> encodeCVSLine(separator, c.getEmail(), c.getName(), c.getLanguage()))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    /**
     * Imports a list of emails addresses as contacts.
     * The emails are only imported if they do not already exist as contacts or users
     * @param param the emails to import
     */
    @PostMapping(value = "/import-emails",
            consumes = "application/json;charset=UTF-8",
            produces = "text/plain;charset=UTF-8")
    public String importEmails(@RequestBody EmailContactImportVo param) throws Exception {

        long t0 = System.currentTimeMillis();

        String[] emailList = StringUtils.defaultString(param.getEmails()).split("[;,\n]");

        // An address given more than once, in any case, is imported once, in the spelling given first
        Map<String, String> uniqueEmails = new LinkedHashMap<>();
        Arrays.stream(emailList)
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .forEach(e -> uniqueEmails.putIfAbsent(e.toLowerCase(Locale.ROOT), e));

        // emails are only imported if they are valid and do not already exist as contacts or users
        List<String> validEmails = uniqueEmails.values().stream()
                .filter(e -> UserService.EMAIL_PATTERN.matcher(e).matches())
                .filter(e -> contactService.findByEmail(e) == null)
                .filter(e -> userService.findByEmail(e) == null)
                .collect(Collectors.toList());

        log.info("Importing " + validEmails.size() + " of " + emailList.length + " e-mails");
        int imported = 0;
        for (String email : validEmails) {
            Contact contact = new Contact();
            contact.setEmail(email);
            try {
                contactService.importContact(contact);
                imported++;
            } catch (Exception e) {
                log.warn("Failed importing contact email " + email + ": " + e.getMessage());
            }
        }

        // The answer counts the contacts created, not the addresses tried
        String result = String.format("Imported %d new contacts in %d ms",
                imported,
                System.currentTimeMillis() - t0);
        log.info(result);
        return result;
    }

    /************************/
    /** Helper classes     **/
    /***/

    @SuppressWarnings("unused")
    public static class EmailContactImportVo implements IJsonSerializable {
        String emails;

        public String getEmails() {
            return emails;
        }

        public void setEmails(String emails) {
            this.emails = emails;
        }
    }
}

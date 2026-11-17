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

package org.niord.core.user;

import org.springframework.stereotype.Service;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.db.CriteriaHelper;
import org.niord.core.service.BaseService;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;

import jakarta.inject.Inject;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Defines the API for accessing contacts
 */
@Service
@SuppressWarnings("unused")
public class ContactService extends BaseService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContactService.class);

    /**
     * Searches for contacts matching the search criteria
     * @param params the search params to match
     * @return the matching contacts
     */
    public PagedSearchResultVo<Contact> searchContacts(ContactSearchParams params) {

        long t0 = System.currentTimeMillis();

        PagedSearchResultVo<Contact> result = new PagedSearchResultVo<>();

        CriteriaBuilder cb = em.getCriteriaBuilder();

        // First compute the total number of matching contacts
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Contact> countContactRoot = countQuery.from(Contact.class);

        // No ORDER BY on the count query: ordering a single-row aggregate is meaningless, and PostgreSQL
        // rejects an ORDER BY on a non-grouped, non-aggregate column in an aggregate query (the paged query
        // below keeps the email ordering). MySQL tolerated it.
        countQuery.select(cb.count(countContactRoot))
                .where(buildQueryPredicates(cb, countQuery, countContactRoot, params));

        result.setTotal(em.createQuery(countQuery).getSingleResult());

        // Then, extract the current page of matches
        CriteriaQuery<Contact> query = cb.createQuery(Contact.class);
        Root<Contact> contactRoot = query.from(Contact.class);
        query.select(contactRoot)
                .where(buildQueryPredicates(cb, query, contactRoot, params))
                .orderBy(cb.asc(cb.lower(contactRoot.get("email"))));

        List<Contact> contacts = em.createQuery(query)
                .setMaxResults(params.getMaxSize())
                .setFirstResult(params.getPage() * params.getMaxSize())
                .getResultList();
        result.setData(contacts);
        result.updateSize();

        log.info("Search [" + params + "] returned " + result.getSize() + " of " + result.getTotal() + " in "
                + (System.currentTimeMillis() - t0) + " ms");

        return result;
    }

    /** Helper function that translates the search parameters into predicates */
    private <T> Predicate[] buildQueryPredicates(CriteriaBuilder cb, CriteriaQuery<T> query, Root<Contact> contactRoot, ContactSearchParams params) {

        // Build the predicate
        CriteriaHelper<T> criteriaHelper = new CriteriaHelper<>(cb, query);

        // Match name
        if (StringUtils.isNotBlank(params.getName())) {
            String nameMatch = "%" + params.getName().toLowerCase() + "%";
            // lower() the columns too: the term is lower-cased, so the columns must be as well for a
            // case-insensitive match. PostgreSQL's LIKE is case-sensitive (MySQL's default collation was not).
            Predicate namePredicate = cb.or(
                    cb.like(cb.lower(contactRoot.get("name")), nameMatch),
                    cb.like(cb.lower(contactRoot.get("email")), nameMatch)
            );
            criteriaHelper.add(namePredicate);
        }

        // More filter options to come...

        return criteriaHelper.where();
    }

    /** Returns all contacts **/
    public List<Contact> getAllContacts() {
        return getAll(Contact.class);
    }

    /**
     * Returns the contact with the given ID, or null if not found.
     * @param id the ID of the contact
     * @return the contact with the given ID
     */
    public Contact findById(Integer id) {
        return getByPrimaryKey(Contact.class, id);
    }

    /**
     * Returns the contact with the given email address, or null if not found.
     * @param email the email address of the contact
     * @return the contact with the given email address
     */
    public Contact findByEmail(String email) {
        try {
            return em.createNamedQuery("Contact.findByEmail", Contact.class)
                    .setParameter("email", email)
                    .getSingleResult();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The rule the administration's contact form checks an address with: the {@code email()} validator of Angular's
     * signal forms, which is the WHATWG rule for an e-mail input. The backend refuses what the form refuses and
     * nothing more. {@link UserService#EMAIL_PATTERN} is narrower (no hyphen in a later domain label, no apostrophe)
     * and would refuse addresses the form sends.
     */
    static final Pattern FORM_EMAIL_PATTERN = Pattern.compile(
            "^(?=.{1,254}$)(?=.{1,64}@)[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                    + "@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$");

    /**
     * Refuses a blank contact address; the address is the contact's required key
     * @param email the address
     */
    private static void checkEmailPresent(String email) {
        if (StringUtils.isBlank(email)) {
            throw new IllegalArgumentException("A contact needs an e-mail address");
        }
    }

    /**
     * Refuses a contact address that is blank or that the contact form would refuse ({@link #FORM_EMAIL_PATTERN})
     * @param email the address
     */
    private static void checkEmail(String email) {
        checkEmailPresent(email);
        if (!FORM_EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("'" + email + "' is not an e-mail address");
        }
    }

    /**
     * Creates a new contact from the given template
     * @param contact the Contact template
     * @return the newly created contact
     */
    @Transactional
    public Contact createContact(Contact contact) {
        if (contact.isPersisted()) {
            throw new IllegalArgumentException("Cannot create contact with existing ID "
                    + contact.getId());
        }
        checkEmail(contact.getEmail());

        Contact original = findByEmail(contact.getEmail());
        if (original != null) {
            throw new IllegalArgumentException("Contact with email " + contact.getEmail() + " already exists");
        }

        return saveEntity(contact);
    }

    /**
     * Imports a new contact from the given template in a new transaction
     * @param contact the Contact template
     * @return the newly created contact
     * @noinspection all
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Contact importContact(Contact contact) {
        return createContact(contact);
    }

    /**
     * Updates an existing contact from the given template
     * @param contact the Contact template
     * @return the newly created contact
     */
    @Transactional
    public Contact updateContact(Contact contact) {
        Contact original = findById(contact.getId());
        if (original == null) {
            throw new IllegalArgumentException("Contact with ID " + contact.getId() + " does not exists");
        }
        // The format is checked only when the address changes, so a contact stored with an address the form would
        // refuse (older data) can still have its name or language edited
        if (Objects.equals(original.getEmail(), contact.getEmail())) {
            checkEmailPresent(contact.getEmail());
        } else {
            checkEmail(contact.getEmail());
        }

        original.setEmail(contact.getEmail());
        original.setName(contact.getName());
        original.setLanguage(contact.getLanguage());

        return saveEntity(original);
    }

    /**
     * Deletes the contact with the given ID
     * @param id the ID of the contact to delete
     * @return if the contact was deleted
     * @noinspection all
     */
    @Transactional
    public boolean deleteContact(Integer id) {
        Contact original = findById(id);
        if (original != null) {
            remove(original);
            return true;
        }
        return false;
    }

}

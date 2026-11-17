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

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.model.search.PagedSearchResultVo;
import org.niord.test.ITTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ContactService} persistence and CRUD behaviour, run against the
 * seeded MySQL container of the {@link ITTest} infrastructure. No {@code Contact} rows are seeded,
 * so every test creates its own data.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the
 * shared seed database pristine and the tests independent. Test contacts use an {@code IT-} email
 * prefix that does not occur in the seed data, so searches stay isolated.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class ContactServiceITTest extends ITTest {

    @Autowired
    ContactService contactService;

    @PersistenceContext
    EntityManager em;

    /** Builds a detached Contact template. */
    private Contact newContact(String email, String name, String language) {
        Contact contact = new Contact();
        contact.setEmail(email);
        contact.setName(name);
        contact.setLanguage(language);
        return contact;
    }

    private ContactSearchParams params() {
        ContactSearchParams p = new ContactSearchParams();
        p.maxSize(100);
        return p;
    }

    // ------------------------------------------------------------------------------------------- //
    // createContact
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createContactPersistsRowWithGeneratedId() {
        Contact created = contactService.createContact(
                newContact("it-create@example.com", "IT Create", "en"));
        Integer id = created.getId();
        assertNotNull(id, "createContact must assign a generated id");

        em.flush();
        em.clear();

        Contact reloaded = em.find(Contact.class, id);
        assertNotNull(reloaded, "contact row must exist in the database");
        assertEquals("it-create@example.com", reloaded.getEmail());
        assertEquals("IT Create", reloaded.getName());
        assertEquals("en", reloaded.getLanguage());
    }

    @Test
    @Transactional
    void createContactSetsCreatedAndUpdatedTimestamps() {
        Contact created = contactService.createContact(
                newContact("it-timestamps@example.com", "IT Timestamps", "da"));
        Integer id = created.getId();
        em.flush();
        em.clear();

        Contact reloaded = em.find(Contact.class, id);
        assertNotNull(reloaded.getCreated(), "@PrePersist must populate the created timestamp");
        assertNotNull(reloaded.getUpdated(), "@PrePersist must populate the updated timestamp");
    }

    @Test
    @Transactional
    void createContactAllowsNullNameAndLanguage() {
        Contact created = contactService.createContact(
                newContact("it-nullfields@example.com", null, null));
        Integer id = created.getId();
        em.flush();
        em.clear();

        Contact reloaded = em.find(Contact.class, id);
        assertNotNull(reloaded, "a contact with only an email must persist");
        assertEquals("it-nullfields@example.com", reloaded.getEmail());
        assertNull(reloaded.getName());
        assertNull(reloaded.getLanguage());
    }

    @Test
    @Transactional
    void createContactRejectsDuplicateEmail() {
        contactService.createContact(newContact("it-dup@example.com", "First", "en"));
        em.flush();

        // The service performs an explicit findByEmail() uniqueness check before saving.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> contactService.createContact(newContact("it-dup@example.com", "Second", "da")));
        assertTrue(ex.getMessage().contains("it-dup@example.com"),
                "the duplicate-email error must mention the offending address");
    }

    @Test
    @Transactional
    void createContactRejectsDuplicateEmailEvenAfterFlush() {
        contactService.createContact(newContact("it-dup-flush@example.com", "First", "en"));
        em.flush();
        em.clear();

        assertThrows(IllegalArgumentException.class,
                () -> contactService.createContact(
                        newContact("it-dup-flush@example.com", "Second", "en")));

        // The original contact must remain the only row for that email.
        Long count = em.createQuery(
                        "select count(c) from Contact c where c.email = :e", Long.class)
                .setParameter("e", "it-dup-flush@example.com")
                .getSingleResult();
        assertEquals(1L, count, "the duplicate must not have been persisted");
    }

    @Test
    @Transactional
    void createContactWithoutEmailIsRefusedBeforeTheDatabase() {
        // The address is the contact's required key. Without it, the save used to fail on Hibernate's not-null
        // check (a PropertyValueException, a 500 over REST); createContact now refuses it first with an
        // IllegalArgumentException (a 400 over REST) that names the field (ERR4).
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            contactService.createContact(newContact(null, "IT No Email", "en"));
            em.flush();
        });
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("e-mail address"),
                "the refusal must name the e-mail address, but was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // findById
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByIdReturnsPersistedContact() {
        Contact created = contactService.createContact(
                newContact("it-findbyid@example.com", "IT FindById", "en"));
        Integer id = created.getId();
        em.flush();
        em.clear();

        Contact found = contactService.findById(id);
        assertNotNull(found);
        assertEquals(id, found.getId());
        assertEquals("it-findbyid@example.com", found.getEmail());
    }

    @Test
    @Transactional
    void findByIdReturnsNullForUnknownId() {
        assertNull(contactService.findById(-999_999));
    }

    // ------------------------------------------------------------------------------------------- //
    // findByEmail
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByEmailMatchesLowercaseStoredEmail() {
        Contact created = contactService.createContact(
                newContact("it-findbyemail@example.com", "IT FindByEmail", "en"));
        em.flush();
        em.clear();

        Contact found = contactService.findByEmail("it-findbyemail@example.com");
        assertNotNull(found);
        assertEquals(created.getId(), found.getId());
    }

    @Test
    @Transactional
    void findByEmailReturnsNullForUnknownEmail() {
        assertNull(contactService.findByEmail("it-nobody@example.com"));
    }

    @Test
    @Transactional
    void findByEmailMatchesRegardlessOfStoredEmailCasing() {
        // The named query lowercases the stored column (lower(c.email) = :email), so a mixed-case
        // stored email is still found when the caller passes the lowercased address.
        contactService.createContact(
                newContact("IT-MixedCase@Example.com", "IT MixedCase", "en"));
        em.flush();
        em.clear();

        assertNotNull(contactService.findByEmail("it-mixedcase@example.com"),
                "findByEmail must match the stored email after lower-casing the column");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateContact
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateContactPersistsAllMutableFields() {
        Contact created = contactService.createContact(
                newContact("it-update@example.com", "Original Name", "en"));
        Integer id = created.getId();
        em.flush();
        em.clear();

        Contact template = newContact("it-update-renamed@example.com", "Renamed", "da");
        template.setId(id);
        contactService.updateContact(template);
        em.flush();
        em.clear();

        Contact reloaded = em.find(Contact.class, id);
        assertEquals("it-update-renamed@example.com", reloaded.getEmail());
        assertEquals("Renamed", reloaded.getName());
        assertEquals("da", reloaded.getLanguage());
    }

    @Test
    @Transactional
    void updateContactCanClearNameAndLanguage() {
        Contact created = contactService.createContact(
                newContact("it-update-clear@example.com", "Had Name", "en"));
        Integer id = created.getId();
        em.flush();
        em.clear();

        Contact template = newContact("it-update-clear@example.com", null, null);
        template.setId(id);
        contactService.updateContact(template);
        em.flush();
        em.clear();

        Contact reloaded = em.find(Contact.class, id);
        assertNull(reloaded.getName(), "update must clear the name when the template name is null");
        assertNull(reloaded.getLanguage(), "update must clear the language when the template language is null");
    }

    @Test
    @Transactional
    void updateContactDoesNotCreateNewRow() {
        Contact created = contactService.createContact(
                newContact("it-update-norow@example.com", "Name", "en"));
        Integer id = created.getId();
        em.flush();

        Contact template = newContact("it-update-norow2@example.com", "Name2", "da");
        template.setId(id);
        contactService.updateContact(template);
        em.flush();
        em.clear();

        Long count = em.createQuery(
                        "select count(c) from Contact c where c.email like 'it-update-norow%'", Long.class)
                .getSingleResult();
        assertEquals(1L, count, "updateContact must update in place, not insert a new contact");
    }

    @Test
    @Transactional
    void updateContactRejectsUnknownId() {
        Contact template = newContact("it-update-unknown@example.com", "Ghost", "en");
        template.setId(-999_999);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> contactService.updateContact(template));
        assertTrue(ex.getMessage().contains("-999999"),
                "the not-found error must mention the missing id");
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteContact
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteContactRemovesRowAndReturnsTrue() {
        Contact created = contactService.createContact(
                newContact("it-delete@example.com", "IT Delete", "en"));
        Integer id = created.getId();
        em.flush();

        assertTrue(contactService.deleteContact(id));
        em.flush();
        em.clear();

        assertNull(em.find(Contact.class, id), "the deleted contact must be gone from the database");
    }

    @Test
    @Transactional
    void deleteContactReturnsFalseForUnknownId() {
        assertFalse(contactService.deleteContact(-999_999));
    }

    @Test
    @Transactional
    void emailBecomesReusableAfterDelete() {
        Contact first = contactService.createContact(
                newContact("it-reuse@example.com", "First", "en"));
        em.flush();

        assertTrue(contactService.deleteContact(first.getId()));
        em.flush();
        em.clear();

        // After deletion the unique email may be used again.
        Contact second = contactService.createContact(
                newContact("it-reuse@example.com", "Second", "da"));
        em.flush();
        em.clear();

        Contact reloaded = em.find(Contact.class, second.getId());
        assertNotNull(reloaded);
        assertEquals("Second", reloaded.getName());
    }

    // ------------------------------------------------------------------------------------------- //
    // getAllContacts
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getAllContactsIncludesCreatedContacts() {
        contactService.createContact(newContact("it-all-1@example.com", "All One", "en"));
        contactService.createContact(newContact("it-all-2@example.com", "All Two", "da"));
        em.flush();

        List<Contact> all = contactService.getAllContacts();
        long matching = all.stream()
                .filter(c -> c.getEmail() != null && c.getEmail().startsWith("it-all-"))
                .count();
        assertEquals(2L, matching, "both newly created contacts must be returned by getAllContacts");
    }

    // ------------------------------------------------------------------------------------------- //
    // searchContacts
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByNameSubstringMatchesContact() {
        // searchContacts() lower-cases BOTH the search term and the name/email columns (cb.lower —
        // restored during the Postgres migration, see potential-errors.md PG3), so the stored casing
        // is immaterial; a lower-case name is used simply as a uniform fixture choice. This test pins
        // the substring (name-column) match; case-insensitivity is asserted separately in
        // searchByNameIsCaseInsensitive.
        contactService.createContact(newContact("it-search-name@example.com", "it-searchsubject", "en"));
        em.flush();

        PagedSearchResultVo<Contact> result =
                contactService.searchContacts(params().name("searchsubject"));
        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getData().size());
        assertEquals("it-searchsubject", result.getData().get(0).getName());
    }

    @Test
    @Transactional
    void searchByEmailSubstringMatchesContact() {
        contactService.createContact(newContact("it-search-email-unique@example.com", "No Match Name", "en"));
        em.flush();

        // The search term matches the email column even though it does not match the name.
        PagedSearchResultVo<Contact> result =
                contactService.searchContacts(params().name("search-email-unique"));
        assertEquals(1L, result.getTotal());
        assertEquals("it-search-email-unique@example.com", result.getData().get(0).getEmail());
    }

    @Test
    @Transactional
    void searchByNameIsCaseInsensitive() {
        // searchContacts() lower-cases BOTH the search term and the name/email columns (cb.lower), so the
        // name search is case-insensitive — preserved across the MySQL -> PostgreSQL migration. PostgreSQL's
        // LIKE is case-sensitive by default, so ContactService.buildQueryPredicates lowers the columns
        // explicitly; under MySQL the default collation made the match case-insensitive without it.
        contactService.createContact(newContact("it-search-case@example.com", "CaseName", "en"));
        em.flush();

        // A differently-cased term still matches the mixed-case stored name.
        PagedSearchResultVo<Contact> matched =
                contactService.searchContacts(params().name("CASENAME"));
        assertEquals(1L, matched.getTotal(),
                "name search is case-insensitive: an upper-cased term must match a mixed-case stored name");
        assertEquals("CaseName", matched.getData().get(0).getName());

        // A substring of any case also matches.
        PagedSearchResultVo<Contact> substring =
                contactService.searchContacts(params().name("aSeN"));
        assertEquals(1L, substring.getTotal(),
                "a substring of the stored name must match regardless of case");
    }

    @Test
    @Transactional
    void searchWithUnknownTermReturnsEmpty() {
        contactService.createContact(newContact("it-search-none@example.com", "IT-SomeName", "en"));
        em.flush();

        PagedSearchResultVo<Contact> result =
                contactService.searchContacts(params().name("IT-NoSuchTermXyz"));
        assertEquals(0L, result.getTotal());
        assertTrue(result.getData().isEmpty());
    }

    @Test
    @Transactional
    void searchSubstringMatchesMultipleContacts() {
        contactService.createContact(newContact("it-multi-1@example.com", "IT-MultiOne", "en"));
        contactService.createContact(newContact("it-multi-2@example.com", "IT-MultiTwo", "da"));
        em.flush();

        PagedSearchResultVo<Contact> result =
                contactService.searchContacts(params().name("IT-Multi"));
        assertEquals(2L, result.getTotal());
        assertEquals(2, result.getData().size());
    }

    @Test
    @Transactional
    void searchResultsAreOrderedByEmailAscending() {
        contactService.createContact(newContact("it-order-b@example.com", "IT-Order", "en"));
        contactService.createContact(newContact("it-order-a@example.com", "IT-Order", "da"));
        em.flush();

        PagedSearchResultVo<Contact> result =
                contactService.searchContacts(params().name("IT-Order"));
        assertEquals(2, result.getData().size());
        assertEquals("it-order-a@example.com", result.getData().get(0).getEmail(),
                "results must be ordered by email ascending");
        assertEquals("it-order-b@example.com", result.getData().get(1).getEmail());
    }

    @Test
    @Transactional
    void searchRespectsPageSizeButReportsFullTotal() {
        contactService.createContact(newContact("it-page-a@example.com", "IT-Page", "en"));
        contactService.createContact(newContact("it-page-b@example.com", "IT-Page", "en"));
        contactService.createContact(newContact("it-page-c@example.com", "IT-Page", "en"));
        em.flush();

        ContactSearchParams p = new ContactSearchParams();
        p.maxSize(2);
        p.name("IT-Page");

        PagedSearchResultVo<Contact> page0 = contactService.searchContacts(p);
        assertEquals(3L, page0.getTotal(), "total must reflect all matches, not just the page size");
        assertEquals(2, page0.getData().size(), "the first page must hold at most maxSize matches");
        // First page (ordered by email asc) holds the two lowest emails.
        assertEquals("it-page-a@example.com", page0.getData().get(0).getEmail());
        assertEquals("it-page-b@example.com", page0.getData().get(1).getEmail());

        ContactSearchParams p2 = new ContactSearchParams();
        p2.maxSize(2);
        p2.page(1);
        p2.name("IT-Page");
        PagedSearchResultVo<Contact> page1 = contactService.searchContacts(p2);
        assertEquals(3L, page1.getTotal());
        assertEquals(1, page1.getData().size(), "the second page must hold the remaining match");
        assertEquals("it-page-c@example.com", page1.getData().get(0).getEmail());
    }

    @Test
    @Transactional
    void searchWithBlankNameReturnsAllContacts() {
        // A blank name adds no predicate, so this search matches EVERY contact in the table — not
        // just the rows this test owns. The seed ships an empty Contact table, but that is not the
        // same as the table being empty when this class runs: MailingListRestRecipientsITTest
        // creates contacts over HTTP, which commit in the server's own transaction (the test
        // transaction cannot roll those back) and are only cleaned up best-effort. Whether its
        // leftovers are present here comes down to class execution order, which differs between a
        // local run and the CI runner — so pinning the absolute total was green locally and red on
        // CI. Assert the delta and this test's own rows instead. See docs/potential-errors.md TESTQ2.
        long before = contactService.searchContacts(params().name("  ")).getTotal();

        contactService.createContact(newContact("it-blank-1@example.com", "IT-Blank", "en"));
        contactService.createContact(newContact("it-blank-2@example.com", "IT-Blank", "en"));
        em.flush();

        PagedSearchResultVo<Contact> result = contactService.searchContacts(params().name("  "));
        assertEquals(before + 2, result.getTotal(),
                "a blank name adds no predicate, so both new contacts must be matched");

        // Matched, not merely counted: a blank name treated as a filter (LIKE '%  %') would return
        // neither of them. The class creates 19 contacts at most, comfortably inside the maxSize of
        // 100 that params() sets, so a single page holds every match.
        List<String> emails = result.getData().stream().map(Contact::getEmail).toList();
        assertTrue(emails.contains("it-blank-1@example.com"),
                "a blank name must not filter out the contacts this test created");
        assertTrue(emails.contains("it-blank-2@example.com"),
                "a blank name must not filter out the contacts this test created");
    }

    /**
     * A create body must not carry an id: {@code Contact(ContactVo)} copies it and {@code saveEntity()}
     * merges a persisted entity, so a "create" with a fresh e-mail would silently rewrite the contact with
     * that id - redirecting an existing mailing-list recipient's address.
     */
    @Test
    @Transactional
    void createContactRejectsContactWithExistingId() {
        Contact victim = contactService.createContact(
                newContact("it-victim@example.com", "IT Victim", "en"));
        em.flush();
        assertNotNull(victim.getId(), "precondition: the victim contact must have a generated id");

        Contact forged = newContact("it-forged@example.com", "IT Forged", "da");
        forged.setId(victim.getId());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> contactService.createContact(forged));
        assertTrue(ex.getMessage().contains("existing ID"),
                "the rejection must mention the existing id");
    }

}

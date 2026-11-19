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
package org.niord.core.mail;

import jakarta.mail.Address;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for the package-private {@link ValidMailRecipients} filter.
 *
 * <p>The class is constructed with either the special tokens "ALL"/"LOG" (case-insensitive) or a
 * comma-separated explicit allow-list. In explicit-list mode, recipients not on the list are
 * substituted with the first listed address (carrying the original address as a personal name).
 * These tests pin that observable contract by reading the actual source behavior.
 */
public class ValidMailRecipientsTest {

    // ----- ALL token --------------------------------------------------------

    @Test
    public void allToken_doesNotSimulate() {
        assertFalse(new ValidMailRecipients("ALL").simulate());
    }

    @Test
    public void allToken_returnsSameAddressInstance() throws Exception {
        ValidMailRecipients filter = new ValidMailRecipients("ALL");
        InternetAddress addr = new InternetAddress("a@b.com");
        // allValid mode returns the exact same instance, unchanged.
        assertSame(addr, filter.filter(addr));
    }

    @Test
    public void allToken_isCaseInsensitive() throws Exception {
        ValidMailRecipients filter = new ValidMailRecipients("all");
        assertFalse(filter.simulate());
        InternetAddress addr = new InternetAddress("a@b.com");
        assertSame(addr, filter.filter(addr));
    }

    // ----- LOG token --------------------------------------------------------

    @Test
    public void logToken_simulates() {
        assertTrue(new ValidMailRecipients("LOG").simulate());
    }

    @Test
    public void logToken_returnsAddressUnchanged() throws Exception {
        ValidMailRecipients filter = new ValidMailRecipients("LOG");
        InternetAddress addr = new InternetAddress("a@b.com");
        // LOG is allValid (just simulated), so the address passes through untouched.
        assertSame(addr, filter.filter(addr));
    }

    @Test
    public void logToken_isCaseInsensitive() {
        assertTrue(new ValidMailRecipients("log").simulate());
    }

    // ----- Explicit allow-list ---------------------------------------------

    @Test
    public void explicitList_doesNotSimulate() {
        assertFalse(new ValidMailRecipients("keep@me.com, other@ok.com").simulate());
    }

    @Test
    public void explicitList_listedRecipientPassesThrough() throws Exception {
        ValidMailRecipients filter = new ValidMailRecipients("keep@me.com, other@ok.com");
        Address result = filter.filter(new InternetAddress("keep@me.com"));
        assertEquals("keep@me.com", ((InternetAddress) result).getAddress());
    }

    @Test
    public void explicitList_secondListedRecipientPassesThrough() throws Exception {
        ValidMailRecipients filter = new ValidMailRecipients("keep@me.com, other@ok.com");
        Address result = filter.filter(new InternetAddress("other@ok.com"));
        assertEquals("other@ok.com", ((InternetAddress) result).getAddress());
    }

    @Test
    public void explicitList_unlistedRecipientIsSubstitutedWithFirstValidAddress() throws Exception {
        ValidMailRecipients filter = new ValidMailRecipients("keep@me.com, other@ok.com");
        Address result = filter.filter(new InternetAddress("stranger@x.com"));
        InternetAddress ia = (InternetAddress) result;
        // The substitute uses the first list entry as the actual address...
        assertEquals("keep@me.com", ia.getAddress());
        // ...and carries the original recipient as the personal name "(" + addr + ")".
        assertEquals("(stranger@x.com)", ia.getPersonal());
    }

    @Test
    public void explicitList_substituteMatchesSourceConstruction() throws Exception {
        // Verify against the exact construction in the source:
        //   new InternetAddress(validAddresses.iterator().next(), "(" + addr + ")")
        ValidMailRecipients filter = new ValidMailRecipients("keep@me.com, other@ok.com");
        InternetAddress original = new InternetAddress("stranger@x.com");
        InternetAddress expected = new InternetAddress("keep@me.com", "(" + original + ")");

        InternetAddress result = (InternetAddress) filter.filter(original);
        assertEquals(expected.getAddress(), result.getAddress());
        assertEquals(expected.getPersonal(), result.getPersonal());
    }

    @Test
    public void explicitList_matchingIsCaseInsensitive() throws Exception {
        // Allow-list entries are stored lower-cased; an upper-cased recipient still matches and
        // passes through as-is (same instance), rather than being substituted.
        ValidMailRecipients filter = new ValidMailRecipients("keep@me.com, other@ok.com");
        InternetAddress addr = new InternetAddress("KEEP@ME.com");
        Address result = filter.filter(addr);
        assertSame(addr, result);
        assertEquals("KEEP@ME.com", ((InternetAddress) result).getAddress());
    }

    // ----- redirects(): what makes a mail held under a list --------------------

    @Test
    public void redirects_onlyAValidAddressAListDoesNotName() throws Exception {
        ValidMailRecipients list = new ValidMailRecipients("keep@example.org, other@example.org");
        assertFalse(list.redirects(new InternetAddress("keep@example.org")), "a listed address");
        assertFalse(list.redirects(new InternetAddress("OTHER@example.org")), "a listed address, in another case");
        assertTrue(list.redirects(new InternetAddress("stranger@example.com")), "an address the list does not name");
        assertFalse(list.redirects(new InternetAddress("plainaddress", false)), "an invalid address is refused, "
                + "not redirected");
        assertFalse(list.redirects(null));

        InternetAddress stranger = new InternetAddress("stranger@example.com");
        for (String token : new String[] {"ALL", "LOG"}) {
            assertFalse(new ValidMailRecipients(token).redirects(stranger), token + " redirects nothing");
        }
    }

    // ----- filter(String) overload -----------------------------------------

    @Test
    public void filterString_singleAddressParsesAndReturns() throws Exception {
        ValidMailRecipients filter = new ValidMailRecipients("ALL");
        Address result = filter.filter("a@b.com");
        assertEquals("a@b.com", ((InternetAddress) result).getAddress());
    }

    @Test
    public void filterString_multipleAddressesRejected() {
        ValidMailRecipients filter = new ValidMailRecipients("ALL");
        try {
            filter.filter("a@b.com, c@d.com");
            fail("Expected AddressException for multi-address string");
        } catch (AddressException e) {
            assertTrue(e.getMessage().contains("Invalid recipient: a@b.com, c@d.com"));
        }
    }

    @Test
    public void filterString_invalidSingleTokenFailsValidation() {
        // A single token with no '@' parses to one address, but addr.validate() rejects it.
        ValidMailRecipients filter = new ValidMailRecipients("ALL");
        try {
            filter.filter("plainaddress");
            fail("Expected AddressException for unvalidatable token");
        } catch (AddressException e) {
            // validate() throws; message is from jakarta.mail, so we only assert the type/non-empty.
            assertTrue(e.getMessage() != null && !e.getMessage().isEmpty());
        }
    }

    // ----- null recipient ---------------------------------------------------

    @Test
    public void filterNullInternetAddress_rejected() {
        ValidMailRecipients filter = new ValidMailRecipients("ALL");
        try {
            filter.filter((InternetAddress) null);
            fail("Expected AddressException for null recipient");
        } catch (AddressException e) {
            assertTrue(e.getMessage().contains("Undefined recipient"));
        }
    }
}

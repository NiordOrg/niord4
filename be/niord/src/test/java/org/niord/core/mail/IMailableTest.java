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

package org.niord.core.mail;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for the default and static methods of {@link IMailable}.
 *
 * <p>{@link IMailable#computeInternetAddress()} builds an RFC-822 style address from the name and
 * email, and {@link IMailable#sortByEmail(List)} sorts a list in place (case-insensitively) by email
 * and returns the same list reference. These tests pin that observable contract.
 */
public class IMailableTest {

    /** Minimal settable implementation used to exercise the interface methods. */
    private static class TestMailable implements IMailable {
        private String name;
        private String email;

        TestMailable(String name, String email) {
            this.name = name;
            this.email = email;
        }

        @Override
        public String getEmail() {
            return email;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    // ---------------------------------------------------------------------
    // computeInternetAddress()
    // ---------------------------------------------------------------------

    @Test
    public void computeInternetAddress_nameAndEmail_combinesBoth() {
        assertEquals("John Doe <j@x.com>",
                new TestMailable("John Doe", "j@x.com").computeInternetAddress());
    }

    @Test
    public void computeInternetAddress_blankName_returnsEmailOnly() {
        assertEquals("j@x.com",
                new TestMailable("   ", "j@x.com").computeInternetAddress());
    }

    @Test
    public void computeInternetAddress_nullName_returnsEmailOnly() {
        assertEquals("j@x.com",
                new TestMailable(null, "j@x.com").computeInternetAddress());
    }

    @Test
    public void computeInternetAddress_blankEmail_returnsNameOnly() {
        assertEquals("John Doe",
                new TestMailable("John Doe", "   ").computeInternetAddress());
    }

    @Test
    public void computeInternetAddress_nullEmail_returnsNameOnly() {
        assertEquals("John Doe",
                new TestMailable("John Doe", null).computeInternetAddress());
    }

    @Test
    public void computeInternetAddress_blankNameAndBlankEmail_returnsNull() {
        assertNull(new TestMailable("  ", "  ").computeInternetAddress());
    }

    @Test
    public void computeInternetAddress_nullNameAndNullEmail_returnsNull() {
        assertNull(new TestMailable(null, null).computeInternetAddress());
    }

    // ---------------------------------------------------------------------
    // sortByEmail(List)
    // ---------------------------------------------------------------------

    @Test
    public void sortByEmail_sortsAscendingCaseInsensitively() {
        TestMailable b = new TestMailable("B", "B@x.com");
        TestMailable a = new TestMailable("A", "a@x.com");
        TestMailable c = new TestMailable("C", "C@x.com");

        List<TestMailable> list = new ArrayList<>(Arrays.asList(b, a, c));
        List<TestMailable> sorted = IMailable.sortByEmail(list);

        // Lower-cased ordering: "a@x.com" < "b@x.com" < "c@x.com" -> a, B, C
        assertEquals(Arrays.asList(a, b, c), sorted);
    }

    @Test
    public void sortByEmail_returnsSameListReference() {
        List<TestMailable> list = new ArrayList<>(Arrays.asList(
                new TestMailable("B", "B@x.com"),
                new TestMailable("A", "a@x.com")));

        assertSame(list, IMailable.sortByEmail(list));
    }

    @Test
    public void sortByEmail_null_returnsNullWithoutException() {
        assertNull(IMailable.sortByEmail(null));
    }

    /**
     * Fixed (docs/potential-errors.md MAIL3): {@code sortByEmail} now applies
     * {@code StringUtils.defaultString(...)} BEFORE {@code toLowerCase()}, so a {@code null} email
     * is treated as an empty string (sorting first) instead of throwing a {@code NullPointerException}.
     */
    @Test
    public void sortByEmail_nullEmailSortsAsEmptyStringFirst() {
        TestMailable noEmail = new TestMailable("NoEmail", null);
        TestMailable a = new TestMailable("A", "a@x.com");

        List<TestMailable> list = new ArrayList<>(Arrays.asList(a, noEmail));
        List<TestMailable> sorted = IMailable.sortByEmail(list);

        // A null email is normalised to "" and therefore sorts ahead of "a@x.com" — no NPE.
        assertEquals(Arrays.asList(noEmail, a), sorted);
    }
}

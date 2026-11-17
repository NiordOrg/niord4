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
package org.niord.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contacts' e-mail import ({@code POST /rest/contacts/import-emails}, no batch job) and the CSV export
 * ({@code GET /rest/contacts/export}), as the administrator. The admin manual: "Each valid address that is not
 * already a contact or a user becomes a new contact; the others are skipped. Niord then says how many contacts it
 * imported."
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class ContactEmailImportRoundTripITTest extends ImportRoundTripSupport {

    private String prefix;

    @AfterEach
    void removeTheContacts() {
        if (prefix != null) {
            for (Integer id : ints("SELECT id FROM public.\"Contact\" WHERE lower(email) LIKE ?1", prefix + "%")) {
                delete("admin", "/rest/contacts/contact/" + id);
            }
        }
    }

    /**
     * Three new addresses, one an existing contact has, one invalid, and one given three times (once more in the
     * same spelling and once in capitals): three contacts are created and the rest skipped, the answer says
     * "Imported 3 new contacts", and the export lists the new ones. The list is deduplicated case-insensitively
     * before the checks, and the answer counts the contacts created (CONTACT2).
     */
    @Test
    void anEmailListImportsTheNewValidAddressesOnceAndCountsThem() {
        prefix = "it-imp-" + unique();
        String existing = prefix + "-old@example.org";
        post("admin", "/rest/contacts/contact", map("email", existing, "name", "Old, \"Ærø\" ; x", "language", "da"));
        String a = prefix + "-a@example.org";
        String b = prefix + "-b@example.org";
        String c = prefix + "-c@example.org";
        String emails = a + ", " + b + ";\n" + c + "\nnot-an-address\n" + existing.toUpperCase() + ";" + a + ";"
                + a.toUpperCase();

        String answer = as("admin").body(map("emails", emails)).when().post("/rest/contacts/import-emails")
                .then().statusCode(200).extract().asString();
        List<String> stored = strings("SELECT lower(email) FROM public.\"Contact\" WHERE lower(email) LIKE ?1 ORDER BY 1", prefix + "%");
        assertEquals(List.of(a, b, c, existing), stored, "the three new addresses, once each, beside the existing one");
        assertTrue(answer.startsWith("Imported 3 new contacts in "), answer);

        // The same list again creates nothing: every address is now a contact, the invalid one is still skipped
        String again = as("admin").body(map("emails", emails)).when().post("/rest/contacts/import-emails")
                .then().statusCode(200).extract().asString();
        assertTrue(again.startsWith("Imported 0 new contacts in "), again);
        assertEquals(stored, strings("SELECT lower(email) FROM public.\"Contact\" WHERE lower(email) LIKE ?1 ORDER BY 1", prefix + "%"));

        // The CSV export: one line per contact, the name with its separator and quote kept in quotes
        String csv = as("admin").when().get("/rest/contacts/export").then().statusCode(200).extract().asString();
        List<String> mine = Arrays.stream(csv.split("\\R")).filter(l -> l.toLowerCase().contains(prefix)).sorted().toList();
        assertEquals(List.of("\"" + a + "\";\"\";\"\"", "\"" + b + "\";\"\";\"\"", "\"" + c + "\";\"\";\"\"",
                "\"" + existing + "\";\"Old, \"\"Ærø\"\" ; x\";\"da\""), mine);
    }
}

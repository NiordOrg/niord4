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
package org.niord.core.message;

import org.junit.jupiter.api.Test;
import org.niord.core.model.DescEntity;
import org.niord.model.DataFilter;
import org.niord.model.message.MessageDescVo;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure (non-persistent) logic of the {@link MessageDesc} localized description entity:
 *
 * <ul>
 *     <li>{@link MessageDesc#descDefined()} — true iff any translatable field is non-blank.</li>
 *     <li>{@link MessageDesc#copyDesc} — copies lang + all five translatable fields.</li>
 *     <li>The VO constructors and their field-gating / truncation behaviour driven by {@link DataFilter}:
 *         the full-copy branch truncates each field to its column length, whereas the {@code title}-only
 *         branch copies the title verbatim (no truncation) and leaves the other fields {@code null}.</li>
 *     <li>{@link MessageDesc#toVo(DataFilter)} — field-gating including the {@code internalPublication}
 *         {@code userInRole("user")} security gate.</li>
 *     <li>The static {@link DescEntity#trunc(String, int)} helper.</li>
 * </ul>
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks. Domain objects are
 * built directly with {@code new} + setters and the {@link DataFilter.UserResolver} used to exercise the
 * role gate is a real (hand-written) interface implementation, not a mock. The tests therefore survive a
 * future Quarkus-to-Spring port. The test lives in {@code org.niord.core.message} so it can read the
 * package-private translatable fields directly when convenient.
 */
public class MessageDescEntityTest {

    /** Repeats the given character {@code count} times. */
    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /** A real UserResolver that reports membership of exactly the named role. */
    private static DataFilter.UserResolver resolverForRole(final String grantedRole) {
        return new DataFilter.UserResolver() {
            @Override
            public Principal getPrincipal() {
                return null;
            }
            @Override
            public boolean isUserInRole(String role) {
                return grantedRole != null && grantedRole.equals(role);
            }
        };
    }

    /*****************************/
    /** descDefined()           **/
    /*****************************/

    @Test
    public void descDefinedIsFalseForBlankDesc() {
        MessageDesc desc = new MessageDesc();
        assertFalse(desc.descDefined(), "A MessageDesc with no fields set should not be 'defined'");
    }

    @Test
    public void descDefinedIsTrueWhenOnlySourceSet() {
        MessageDesc desc = new MessageDesc();
        desc.setSource("Notice to Mariners");
        assertTrue(desc.descDefined(), "Setting only the source must make the desc 'defined'");
    }

    @Test
    public void descDefinedIsTrueWhenOnlyTitleSet() {
        MessageDesc desc = new MessageDesc();
        desc.setTitle("Buoy off station");
        assertTrue(desc.descDefined());
    }

    @Test
    public void descDefinedIsTrueWhenOnlyInternalPublicationSet() {
        MessageDesc desc = new MessageDesc();
        desc.setInternalPublication("internal-only");
        assertTrue(desc.descDefined());
    }

    @Test
    public void descDefinedTreatsEmptyStringAsUndefined() {
        MessageDesc desc = new MessageDesc();
        desc.setTitle("");
        desc.setVicinity("");
        desc.setPublication("");
        desc.setInternalPublication("");
        desc.setSource("");
        assertFalse(desc.descDefined(), "Empty strings are blank and should not count as 'defined'");
    }

    /*****************************/
    /** copyDesc()              **/
    /*****************************/

    @Test
    public void copyDescCopiesLangAndAllFiveFields() {
        MessageDesc source = new MessageDesc();
        source.setLang("en");
        source.setTitle("title");
        source.setVicinity("vicinity");
        source.setPublication("publication");
        source.setInternalPublication("internalPublication");
        source.setSource("source");

        MessageDesc target = new MessageDesc();
        target.copyDesc(source);

        assertEquals("en", target.getLang());
        assertEquals("title", target.getTitle());
        assertEquals("vicinity", target.getVicinity());
        assertEquals("publication", target.getPublication());
        assertEquals("internalPublication", target.getInternalPublication());
        assertEquals("source", target.getSource());
    }

    @Test
    public void copyDescCopiesNullFields() {
        MessageDesc source = new MessageDesc();
        source.setLang("da");
        // all translatable fields left null

        MessageDesc target = new MessageDesc();
        target.setTitle("pre-existing");
        target.setVicinity("pre-existing");
        target.copyDesc(source);

        assertEquals("da", target.getLang());
        assertNull(target.getTitle(), "copyDesc must overwrite with the (null) source title");
        assertNull(target.getVicinity());
        assertNull(target.getPublication());
        assertNull(target.getInternalPublication());
        assertNull(target.getSource());
    }

    /*****************************/
    /** Constructor (no filter) — full copy + truncation **/
    /*****************************/

    @Test
    public void constructorNoFilterCopiesAllFieldsForShortValues() {
        MessageDescVo vo = new MessageDescVo();
        vo.setLang("en");
        vo.setTitle("title");
        vo.setVicinity("vicinity");
        vo.setPublication("publication");
        vo.setInternalPublication("internalPublication");
        vo.setSource("source");

        MessageDesc desc = new MessageDesc(vo);

        assertEquals("en", desc.getLang());
        assertEquals("title", desc.getTitle());
        assertEquals("vicinity", desc.getVicinity());
        assertEquals("publication", desc.getPublication());
        assertEquals("internalPublication", desc.getInternalPublication());
        assertEquals("source", desc.getSource());
    }

    @Test
    public void constructorNoFilterTruncatesVicinityTo256AndTitleTo1024() {
        MessageDescVo vo = new MessageDescVo();
        vo.setLang("en");
        // Use 'a' (non-whitespace) so trim() after substring does not change the length.
        vo.setTitle(repeat('a', 1100));
        vo.setVicinity(repeat('a', 300));

        MessageDesc desc = new MessageDesc(vo);

        assertEquals(1024, desc.getTitle().length(), "title should be truncated to its 1024 column length");
        assertEquals(256, desc.getVicinity().length(), "vicinity should be truncated to 256 chars");
    }

    @Test
    public void constructorNoFilterTruncatesPublicationAndInternalPublicationTo2048() {
        MessageDescVo vo = new MessageDescVo();
        vo.setLang("en");
        vo.setPublication(repeat('p', 3000));
        vo.setInternalPublication(repeat('i', 3000));

        MessageDesc desc = new MessageDesc(vo);

        assertEquals(2048, desc.getPublication().length());
        assertEquals(2048, desc.getInternalPublication().length());
    }

    @Test
    public void constructorNoFilterTruncatesSourceTo1024() {
        MessageDescVo vo = new MessageDescVo();
        vo.setLang("en");
        vo.setSource(repeat('s', 2000));

        MessageDesc desc = new MessageDesc(vo);

        assertEquals(1024, desc.getSource().length());
    }

    /*****************************/
    /** Constructor (title-only filter) — raw title, other fields skipped **/
    /*****************************/

    @Test
    public void constructorTitleOnlyFilterCopiesTitleVerbatimWithoutTruncation() {
        MessageDescVo vo = new MessageDescVo();
        vo.setLang("en");
        vo.setTitle(repeat('a', 1100));
        vo.setVicinity("vicinity");
        vo.setPublication("publication");
        vo.setInternalPublication("internalPublication");
        vo.setSource("source");

        DataFilter filter = DataFilter.get().fields("MessageDesc.title");
        MessageDesc desc = new MessageDesc(vo, filter);

        assertEquals("en", desc.getLang(), "lang is always copied by the super constructor");
        assertEquals(1100, desc.getTitle().length(),
                "title-only branch must copy the title raw, with no truncation");
        assertNull(desc.getVicinity(), "title-only branch must leave vicinity unset");
        assertNull(desc.getPublication(), "title-only branch must leave publication unset");
        assertNull(desc.getInternalPublication(), "title-only branch must leave internalPublication unset");
        assertNull(desc.getSource(), "title-only branch must leave source unset");
    }

    /*****************************/
    /** toVo(DataFilter)        **/
    /*****************************/

    private MessageDesc fullyPopulatedDesc() {
        MessageDesc desc = new MessageDesc();
        desc.setLang("en");
        desc.setTitle("title");
        desc.setVicinity("vicinity");
        desc.setPublication("publication");
        desc.setInternalPublication("internalPublication");
        desc.setSource("source");
        return desc;
    }

    @Test
    public void toVoFullFilterWithoutUserOmitsInternalPublication() {
        MessageDesc desc = fullyPopulatedDesc();

        MessageDescVo vo = desc.toVo(DataFilter.get());

        assertEquals("en", vo.getLang());
        assertEquals("title", vo.getTitle());
        assertEquals("vicinity", vo.getVicinity());
        assertEquals("publication", vo.getPublication());
        assertEquals("source", vo.getSource());
        assertNull(vo.getInternalPublication(),
                "internalPublication must be gated off without a user in role 'user'");
    }

    @Test
    public void toVoFullFilterWithUserInRoleIncludesInternalPublication() {
        MessageDesc desc = fullyPopulatedDesc();

        DataFilter filter = DataFilter.get().user(resolverForRole("user"));
        MessageDescVo vo = desc.toVo(filter);

        assertEquals("title", vo.getTitle());
        assertEquals("vicinity", vo.getVicinity());
        assertEquals("publication", vo.getPublication());
        assertEquals("source", vo.getSource());
        assertEquals("internalPublication", vo.getInternalPublication(),
                "internalPublication must be included when the user is in role 'user'");
    }

    @Test
    public void toVoFullFilterWithUserInWrongRoleOmitsInternalPublication() {
        MessageDesc desc = fullyPopulatedDesc();

        DataFilter filter = DataFilter.get().user(resolverForRole("admin"));
        MessageDescVo vo = desc.toVo(filter);

        assertEquals("vicinity", vo.getVicinity());
        assertNull(vo.getInternalPublication(),
                "internalPublication must stay gated off for a user lacking role 'user'");
    }

    @Test
    public void toVoTitleOnlyFilterIncludesOnlyLangAndTitle() {
        MessageDesc desc = fullyPopulatedDesc();

        DataFilter filter = DataFilter.get().fields("MessageDesc.title");
        MessageDescVo vo = desc.toVo(filter);

        assertEquals("en", vo.getLang());
        assertEquals("title", vo.getTitle());
        assertNull(vo.getVicinity(), "title-only filter must omit vicinity");
        assertNull(vo.getPublication(), "title-only filter must omit publication");
        assertNull(vo.getSource(), "title-only filter must omit source");
        assertNull(vo.getInternalPublication(), "title-only filter must omit internalPublication");
    }

    @Test
    public void toVoTitleOnlyFilterIgnoresUserRoleForInternalPublication() {
        MessageDesc desc = fullyPopulatedDesc();

        // Even with a user in role 'user', the title-only branch never reaches the internalPublication line.
        DataFilter filter = DataFilter.get().fields("MessageDesc.title").user(resolverForRole("user"));
        MessageDescVo vo = desc.toVo(filter);

        assertEquals("title", vo.getTitle());
        assertNull(vo.getInternalPublication(),
                "title-only branch must never set internalPublication, even for a 'user'");
        assertNull(vo.getVicinity());
    }

    /*****************************/
    /** DescEntity.trunc()      **/
    /*****************************/

    @Test
    public void truncReturnsNullForNullInput() {
        assertNull(DescEntity.trunc(null, 10));
    }

    @Test
    public void truncLeavesShortValueUnchanged() {
        assertEquals("short", DescEntity.trunc("short", 10));
    }

    @Test
    public void truncLeavesValueOfExactLengthUnchanged() {
        String value = repeat('x', 10);
        assertEquals(value, DescEntity.trunc(value, 10));
    }

    @Test
    public void truncShortensLongValueToLength() {
        String value = repeat('x', 25);
        String result = DescEntity.trunc(value, 10);
        assertEquals(10, result.length());
        assertEquals(repeat('x', 10), result);
    }

    @Test
    public void truncTrimsTrailingWhitespaceAfterCut() {
        // First 5 chars are "ab   "; substring(0,5) -> "ab   " -> trim() -> "ab".
        String result = DescEntity.trunc("ab   cd", 5);
        assertEquals("ab", result);
    }

    @Test
    public void truncReturnsEmptyStringUnchanged() {
        assertEquals("", DescEntity.trunc("", 10));
    }

    // --------------------------------------------------------------------------------------------- //
    // Inconsistency: the "*" (ALL) wildcard yields LESS than the DETAILS projection — see
    // docs/potential-errors.md (MSG1). These pin the current (surprising) behaviour so a future fix
    // fails loudly. The "title" field name acts as a "restrict to title only" sentinel, and because
    // DataFilter.includeField("title") returns true whenever the ALL wildcard is present, an ALL
    // projection takes the title-only branch and drops vicinity/publication/source.
    // --------------------------------------------------------------------------------------------- //

    /** Builds a fully-populated MessageDesc directly (no VO filtering). */
    private static MessageDesc fullDesc() {
        MessageDesc d = new MessageDesc();
        d.setLang("en");
        d.setTitle("T");
        d.setVicinity("V");
        d.setPublication("P");
        d.setSource("S");
        d.setInternalPublication("I");
        return d;
    }

    @Test
    public void toVoUnderDetailsFilterCopiesNonTitleFields() {
        MessageDescVo vo = fullDesc().toVo(DataFilter.get().fields(DataFilter.DETAILS));
        assertEquals("T", vo.getTitle());
        assertEquals("V", vo.getVicinity());
        assertEquals("P", vo.getPublication());
        assertEquals("S", vo.getSource());
    }

    @Test
    public void toVoUnderAllWildcardDropsNonTitleFields_documentsBehavior() {
        // ALL ("*") is supposed to be a superset of DETAILS, but here it yields LESS: only the title
        // survives. See docs/potential-errors.md (MSG1).
        MessageDescVo vo = fullDesc().toVo(DataFilter.get().fields(DataFilter.ALL));
        assertEquals("T", vo.getTitle());
        assertNull(vo.getVicinity(), "ALL wildcard paradoxically drops vicinity (MSG1)");
        assertNull(vo.getPublication());
        assertNull(vo.getSource());
    }

    @Test
    public void constructorUnderAllWildcardCopiesOnlyTitle_documentsBehavior() {
        MessageDescVo src = new MessageDescVo();
        src.setLang("en");
        src.setTitle("T");
        src.setVicinity("V");
        src.setPublication("P");
        src.setSource("S");

        // Same sentinel collision on the inbound (VO -> entity) path.
        MessageDesc d = new MessageDesc(src, DataFilter.get().fields(DataFilter.ALL));
        assertEquals("T", d.getTitle());
        assertNull(d.getVicinity(), "ALL wildcard takes the title-only branch (MSG1)");
        assertNull(d.getPublication());
        assertNull(d.getSource());
    }
}

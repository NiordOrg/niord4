/*
 * Copyright 2024 Danish Maritime Authority.
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
package org.niord.model;

import org.junit.jupiter.api.Test;
import org.niord.model.message.AreaDescVo;
import org.niord.model.message.AreaVo;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DataFilter}, the immutable field/language filter engine
 * used when copying entities to value objects.
 *
 * <p>These tests exercise pure object logic only: no Quarkus, persistence, or
 * framework wiring is involved, so they survive a future Spring migration.
 */
public class DataFilterTest {

    /*************************/
    /** Immutability        **/
    /*************************/

    @Test
    public void fields_returnsNewInstance_andDoesNotMutateOriginal() {
        DataFilter base = DataFilter.get();
        DataFilter d = base.fields(DataFilter.DETAILS);

        // A new, distinct instance is returned
        assertNotSame(base, d);
        // The original is unchanged
        assertFalse(base.includeDetails());
        // The derived filter has the field
        assertTrue(d.includeDetails());
    }

    @Test
    public void lang_returnsNewInstance_andDoesNotMutateOriginal() {
        DataFilter base = DataFilter.get();
        DataFilter d = base.lang("da");

        assertNotSame(base, d);
        // The original lang stays null (immutable)
        assertNull(base.getLang());
        assertEquals("da", d.getLang());
    }

    @Test
    public void fields_accumulatesAcrossChainedCalls() {
        DataFilter d = DataFilter.get()
                .fields(DataFilter.DETAILS)
                .fields(DataFilter.GEOMETRY);

        assertTrue(d.includeDetails());
        assertTrue(d.includeGeometry());
        assertFalse(d.includeParent());
    }

    /*************************/
    /** includeField        **/
    /*************************/

    @Test
    public void includeField_onlyReturnsTrueForExplicitlyAddedField() {
        DataFilter d = DataFilter.get().fields(DataFilter.DETAILS);
        assertTrue(d.includeDetails());
        assertFalse(d.includeGeometry());
    }

    @Test
    public void includeField_emptyFilterIncludesNothing() {
        DataFilter d = DataFilter.get();
        assertFalse(d.includeField("x"));
        assertFalse(d.includeDetails());
        assertFalse(d.includeParent());
        assertFalse(d.includeParentId());
        assertFalse(d.includeChildren());
        assertFalse(d.includeGeometry());
    }

    @Test
    public void includeField_nullFieldReturnsFalse() {
        // null is rejected even when ALL is present
        assertFalse(DataFilter.get().includeField(null));
        assertFalse(DataFilter.get().fields(DataFilter.ALL).includeField(null));
    }

    @Test
    public void includeField_allShortCircuitsToTrueForAnything() {
        DataFilter d = DataFilter.get().fields(DataFilter.ALL);
        assertTrue(d.includeField("anything"));
        assertTrue(d.includeField("never-added"));
        assertTrue(d.includeDetails());
        assertTrue(d.includeGeometry());
        assertTrue(d.includeParent());
    }

    @Test
    public void includeShortcuts_mapToTheirConstants() {
        assertTrue(DataFilter.get().fields(DataFilter.PARENT).includeParent());
        assertTrue(DataFilter.get().fields(DataFilter.PARENT_ID).includeParentId());
        assertTrue(DataFilter.get().fields(DataFilter.CHILDREN).includeChildren());
        assertTrue(DataFilter.get().fields(DataFilter.GEOMETRY).includeGeometry());
        assertTrue(DataFilter.get().fields(DataFilter.DETAILS).includeDetails());

        // parent and parentId are distinct fields
        DataFilter onlyParent = DataFilter.get().fields(DataFilter.PARENT);
        assertTrue(onlyParent.includeParent());
        assertFalse(onlyParent.includeParentId());
    }

    /*************************/
    /** anyOfFields         **/
    /*************************/

    @Test
    public void anyOfFields_trueWhenAtLeastOneMatches() {
        DataFilter d = DataFilter.get().fields("a");
        assertTrue(d.anyOfFields("a", "b"));
    }

    @Test
    public void anyOfFields_falseWhenNoneMatch() {
        DataFilter d = DataFilter.get().fields("a");
        assertFalse(d.anyOfFields("b", "c"));
    }

    @Test
    public void anyOfFields_allShortCircuitsToTrue() {
        DataFilter d = DataFilter.get().fields(DataFilter.ALL);
        assertTrue(d.anyOfFields("z"));
    }

    @Test
    public void anyOfFields_emptyVarargsReturnsFalseWithoutAll() {
        // No field can match an empty list of candidates
        assertFalse(DataFilter.get().fields("a").anyOfFields());
    }

    /*************************/
    /** allOfFields         **/
    /*************************/

    @Test
    public void allOfFields_trueWhenEveryFieldPresent() {
        DataFilter d = DataFilter.get().fields("a", "b");
        assertTrue(d.allOfFields("a", "b"));
    }

    @Test
    public void allOfFields_falseWhenAnyFieldMissing() {
        DataFilter d = DataFilter.get().fields("a", "b");
        assertFalse(d.allOfFields("a", "c"));
    }

    @Test
    public void allOfFields_allShortCircuitsToTrue() {
        DataFilter d = DataFilter.get().fields(DataFilter.ALL);
        assertTrue(d.allOfFields("z"));
    }

    @Test
    public void allOfFields_emptyVarargsReturnsTrue_documentsBehavior() {
        // Vacuously true: with no candidate fields, the "all present" loop never
        // finds a missing field. Pins the current vacuous-truth behavior.
        assertTrue(DataFilter.get().allOfFields());
        assertTrue(DataFilter.get().fields("a").allOfFields());
    }

    /*************************/
    /** restrictToFields    **/
    /*************************/

    @Test
    public void restrictToFields_trueOnlyWhenSoleField() {
        assertTrue(DataFilter.get().fields("a").restrictToFields("a"));
    }

    @Test
    public void restrictToFields_falseWhenOtherFieldsPresent() {
        assertFalse(DataFilter.get().fields("a", "b").restrictToFields("a"));
    }

    @Test
    public void restrictToFields_falseWhenFieldAbsent() {
        assertFalse(DataFilter.get().fields("a").restrictToFields("b"));
        // Empty filter has size 0, so it cannot be restricted to a single field
        assertFalse(DataFilter.get().restrictToFields("a"));
    }

    /*************************/
    /** includeLang(String) **/
    /*************************/

    @Test
    public void includeLang_noLangSetIncludesEverything() {
        DataFilter d = DataFilter.get();
        assertTrue(d.includeLang("en"));
        assertTrue(d.includeLang("da"));
        assertTrue(d.includeLang((String) null));
    }

    @Test
    public void includeLang_withLangSetMatchesOnlyEqualOrNull() {
        DataFilter d = DataFilter.get().lang("da");
        assertTrue(d.includeLang("da"));
        assertFalse(d.includeLang("en"));
        // null arg is always accepted
        assertTrue(d.includeLang((String) null));
    }

    /*****************************/
    /** includeLang(LocalizedDesc) **/
    /*****************************/

    @Test
    public void includeLangDesc_matchesWhenDescLangEqualsFilterLang() {
        DataFilter d = DataFilter.get().lang("da");

        AreaDescVo da = new AreaDescVo();
        da.setLang("da");
        AreaDescVo en = new AreaDescVo();
        en.setLang("en");

        assertTrue(d.includeLang(da));
        assertFalse(d.includeLang(en));
    }

    @Test
    public void includeLangDesc_nullDescReturnsFalse() {
        // Even with no lang restriction, a null desc is rejected
        assertFalse(DataFilter.get().includeLang((ILocalizedDesc) null));
        assertFalse(DataFilter.get().lang("da").includeLang((ILocalizedDesc) null));
    }

    @Test
    public void includeLangDesc_descWithNullLangIsIncluded() {
        // includeLang(null lang) is true, so a desc whose lang is null is included
        AreaDescVo noLang = new AreaDescVo();
        assertNull(noLang.getLang());
        assertTrue(DataFilter.get().lang("da").includeLang(noLang));
    }

    /*************************/
    /** forComponent        **/
    /*************************/

    @Test
    public void forComponent_stripsMatchingPrefixKeepsOthers() {
        DataFilter d = DataFilter.get()
                .fields("Area.parent", "details", "Other.foo");

        DataFilter area = d.forComponent("Area");

        // "Area.parent" prefix stripped -> plain "parent"
        assertTrue(area.includeParent());
        // non-prefixed field kept
        assertTrue(area.includeDetails());
        // other-component field kept verbatim
        assertTrue(area.includeField("Other.foo"));
        // the prefixed form is no longer present (it was rewritten)
        assertFalse(area.includeField("Area.parent"));
    }

    @Test
    public void forComponent_strippedFieldDoesNotLeakToOtherComponents() {
        // "Area.parent" should become "parent" ONLY under the Area component view.
        DataFilter d = DataFilter.get().fields("Area.parent");

        // Under "Other", the Area-prefixed field is kept verbatim, not stripped
        DataFilter other = d.forComponent("Other");
        assertFalse(other.includeParent());
        assertTrue(other.includeField("Area.parent"));
    }

    @Test
    public void forComponent_returnsNewInstanceForNonNullComponent() {
        DataFilter d = DataFilter.get().fields("Area.parent");
        DataFilter area = d.forComponent("Area");
        assertNotSame(d, area);
        // original retains its prefixed field
        assertTrue(d.includeField("Area.parent"));
        assertFalse(d.includeParent());
    }

    @Test
    public void forComponent_nullReturnsSameInstance() {
        DataFilter d = DataFilter.get().fields("Area.parent");
        assertSame(d, d.forComponent((String) null));
    }

    @Test
    public void forComponent_preservesLang() {
        DataFilter d = DataFilter.get().lang("da").fields("Area.parent");
        assertEquals("da", d.forComponent("Area").getLang());
    }

    @Test
    public void forComponent_byClassUsesSimpleName() {
        // AreaVo.class.getSimpleName() == "AreaVo"
        DataFilter d = DataFilter.get().fields("AreaVo.parent", "geometry");

        DataFilter viaClass = d.forComponent(AreaVo.class);
        DataFilter viaName = d.forComponent("AreaVo");

        // Class-based and name-based behave identically: prefix stripped to "parent"
        assertTrue(viaClass.includeParent());
        assertTrue(viaName.includeParent());
        // non-prefixed field preserved in both
        assertTrue(viaClass.includeGeometry());
        assertTrue(viaName.includeGeometry());
    }

    @Test
    public void forComponent_byClassEqualsNameForUnmatchedComponent() {
        // When the component prefix does not match, fields are kept verbatim
        DataFilter d = DataFilter.get().fields("Area.parent");
        DataFilter viaClass = d.forComponent(AreaVo.class);
        // "Area." prefix does not match "AreaVo." -> kept verbatim, not stripped
        assertFalse(viaClass.includeParent());
        assertTrue(viaClass.includeField("Area.parent"));
    }

    /*************************/
    /** user / principal    **/
    /*************************/

    @Test
    public void principalAndRole_defaultToNullAndFalseWithoutResolver() {
        DataFilter d = DataFilter.get();
        assertNull(d.principal());
        assertFalse(d.userInRole("admin"));
    }

    @Test
    public void user_resolverIsConsultedForRoleChecks() {
        DataFilter.UserResolver resolver = new DataFilter.UserResolver() {
            @Override
            public java.security.Principal getPrincipal() {
                return () -> "alice";
            }
            @Override
            public boolean isUserInRole(String role) {
                return "admin".equals(role);
            }
        };

        DataFilter d = DataFilter.get().user(resolver);
        assertNotNull(d.principal());
        assertEquals("alice", d.principal().getName());
        assertTrue(d.userInRole("admin"));
        assertFalse(d.userInRole("editor"));
    }

    @Test
    public void user_returnsNewInstanceAndDoesNotMutateOriginal() {
        DataFilter.UserResolver resolver = new DataFilter.UserResolver() {
            @Override
            public java.security.Principal getPrincipal() {
                return () -> "bob";
            }
            @Override
            public boolean isUserInRole(String role) {
                return true;
            }
        };

        DataFilter base = DataFilter.get();
        DataFilter withUser = base.user(resolver);

        assertNotSame(base, withUser);
        // original has no resolver, so still default behavior
        assertNull(base.principal());
        assertFalse(base.userInRole("admin"));
        // derived filter consults the resolver
        assertTrue(withUser.userInRole("admin"));
    }

    @Test
    public void forComponent_preservesUserResolver() {
        DataFilter.UserResolver resolver = new DataFilter.UserResolver() {
            @Override
            public java.security.Principal getPrincipal() {
                return () -> "carol";
            }
            @Override
            public boolean isUserInRole(String role) {
                return "viewer".equals(role);
            }
        };

        DataFilter d = DataFilter.get().user(resolver).fields("Area.parent");
        DataFilter area = d.forComponent("Area");

        // the resolver survives the component transform
        assertTrue(area.userInRole("viewer"));
        assertFalse(area.userInRole("admin"));
        assertEquals("carol", area.principal().getName());
    }
}

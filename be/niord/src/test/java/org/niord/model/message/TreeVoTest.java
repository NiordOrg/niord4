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
package org.niord.model.message;

import org.junit.jupiter.api.Test;
import org.niord.model.DataFilter;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the hierarchical {@link AreaVo} and {@link CategoryVo} value objects
 * (and the {@link AreaDescVo} / {@link CategoryDescVo} description VOs).
 *
 * <p>These tests focus on the framework-neutral business behaviour of the REST/JSON
 * value-object contract: the {@code active} default, the filtered {@code copy()}
 * semantics (id/mrn/active/descs copied, parent gated by the PARENT filter field),
 * the language-driven description filtering, and the description accessors.</p>
 *
 * <p>AreaVo and CategoryVo are near-identical, so the suite covers both to guard
 * against future divergence.</p>
 */
public class TreeVoTest {

    /*****************************************************************/
    /** Helpers                                                     **/
    /*****************************************************************/

    private static AreaDescVo areaDesc(String lang, String name) {
        AreaDescVo desc = new AreaDescVo();
        desc.setLang(lang);
        desc.setName(name);
        return desc;
    }

    private static CategoryDescVo catDesc(String lang, String name) {
        CategoryDescVo desc = new CategoryDescVo();
        desc.setLang(lang);
        desc.setName(name);
        return desc;
    }

    private static AreaVo area(Integer id, String mrn, AreaDescVo... descs) {
        AreaVo a = new AreaVo();
        a.setId(id);
        a.setMrn(mrn);
        a.setDescs(new java.util.ArrayList<>(Arrays.asList(descs)));
        return a;
    }

    private static CategoryVo category(Integer id, String mrn, CategoryDescVo... descs) {
        CategoryVo c = new CategoryVo();
        c.setId(id);
        c.setMrn(mrn);
        c.setDescs(new java.util.ArrayList<>(Arrays.asList(descs)));
        return c;
    }

    /*****************************************************************/
    /** Defaults & accessors                                        **/
    /*****************************************************************/

    @Test
    public void freshAreaIsActiveByDefault() {
        assertTrue(new AreaVo().isActive(), "A fresh AreaVo must default to active=true");
    }

    @Test
    public void freshCategoryIsActiveByDefault() {
        assertTrue(new CategoryVo().isActive(), "A fresh CategoryVo must default to active=true");
    }

    @Test
    public void areaAccessorsRoundTrip() {
        AreaVo a = new AreaVo();
        AreaVo parent = new AreaVo();
        parent.setId(7);

        a.setId(42);
        a.setMrn("urn:mrn:iho:area:dk");
        a.setActive(false);
        a.setParent(parent);

        assertEquals(Integer.valueOf(42), a.getId());
        assertEquals("urn:mrn:iho:area:dk", a.getMrn());
        assertFalse(a.isActive());
        assertSame(parent, a.getParent());
        assertEquals(Integer.valueOf(7), a.getParent().getId());
    }

    @Test
    public void categoryAccessorsRoundTrip() {
        CategoryVo c = new CategoryVo();
        CategoryVo parent = new CategoryVo();
        parent.setId(3);

        c.setId(99);
        c.setMrn("urn:mrn:iho:cat:nav");
        c.setActive(false);
        c.setParent(parent);

        assertEquals(Integer.valueOf(99), c.getId());
        assertEquals("urn:mrn:iho:cat:nav", c.getMrn());
        assertFalse(c.isActive());
        assertSame(parent, c.getParent());
        assertEquals(Integer.valueOf(3), c.getParent().getId());
    }

    @Test
    public void areaDescAccessorsRoundTrip() {
        AreaDescVo desc = areaDesc("en", "Denmark");
        assertEquals("en", desc.getLang());
        assertEquals("Denmark", desc.getName());
    }

    @Test
    public void categoryDescAccessorsRoundTrip() {
        CategoryDescVo desc = catDesc("da", "Navigation");
        assertEquals("da", desc.getLang());
        assertEquals("Navigation", desc.getName());
    }

    /*****************************************************************/
    /** createDesc                                                  **/
    /*****************************************************************/

    @Test
    public void areaCreateDescAppendsDescWithGivenLang() {
        AreaVo a = new AreaVo();
        assertNull(a.getDescs(), "descs list starts out null");

        AreaDescVo desc = a.createDesc("en");

        assertEquals("en", desc.getLang());
        assertNull(desc.getName(), "a freshly created desc has no name yet");
        assertNotNull(a.getDescs(), "createDesc must lazily create the descs list");
        assertEquals(1, a.getDescs().size());
        assertSame(desc, a.getDescs().get(0),
                "the created desc must be the one stored in the list");

        desc.setName("Denmark");
        assertEquals("Denmark", a.getDescs().get(0).getName());
    }

    @Test
    public void categoryCreateDescAppendsDescWithGivenLang() {
        CategoryVo c = new CategoryVo();

        CategoryDescVo desc = c.createDesc("da");

        assertEquals("da", desc.getLang());
        assertEquals(1, c.getDescs().size());
        assertSame(desc, c.getDescs().get(0));
    }

    @Test
    public void areaCreateDescAppendsRatherThanReplaces() {
        AreaVo a = new AreaVo();
        AreaDescVo en = a.createDesc("en");
        AreaDescVo da = a.createDesc("da");

        assertEquals(2, a.getDescs().size());
        assertSame(en, a.getDescs().get(0));
        assertSame(da, a.getDescs().get(1));
        assertNotSame(en, da);
    }

    /*****************************************************************/
    /** getDesc (lang lookup, inherited from ILocalizable)          **/
    /*****************************************************************/

    @Test
    public void getDescReturnsMatchingLangCaseInsensitively() {
        AreaVo a = area(1, "mrn", areaDesc("en", "Denmark"), areaDesc("da", "Danmark"));

        assertEquals("Danmark", a.getDesc("DA").getName());
        assertEquals("Denmark", a.getDesc("EN").getName());
    }

    @Test
    public void getDescReturnsNullWhenNoLangMatchesAndNoNullLangDesc() {
        AreaVo a = area(1, "mrn", areaDesc("en", "Denmark"), areaDesc("da", "Danmark"));
        assertNull(a.getDesc("de"), "No 'de' desc and no null-lang desc -> null");
    }

    @Test
    public void getDescMatchesNullLangDescForAnyLanguage() {
        // A desc with a null lang acts as a wildcard match in getDesc().
        AreaVo a = area(1, "mrn", areaDesc(null, "Fallback"));
        assertEquals("Fallback", a.getDesc("anything").getName());
    }

    /*****************************************************************/
    /** AreaVo.copy                                                 **/
    /*****************************************************************/

    @Test
    public void areaCopyMinimalCopiesScalarsAndDescsButNotParent() {
        AreaVo parent = area(10, "urn:parent");
        AreaVo a = area(5, "urn:child", areaDesc("en", "Child"));
        a.setActive(false);
        a.setParent(parent);

        AreaVo copy = a.copy(DataFilter.get());

        assertNotSame(a, copy, "copy() must return a new instance");
        assertEquals(Integer.valueOf(5), copy.getId());
        assertEquals("urn:child", copy.getMrn());
        assertFalse(copy.isActive(), "active must be copied verbatim");

        // Parent is excluded because the PARENT field is not in the filter.
        assertNull(copy.getParent(), "parent must NOT be copied without the PARENT filter field");

        // Descs are copied (content), into a fresh list of fresh desc instances.
        assertNotNull(copy.getDescs());
        assertEquals(1, copy.getDescs().size());
        AreaDescVo copiedDesc = copy.getDescs().get(0);
        assertEquals("en", copiedDesc.getLang());
        assertEquals("Child", copiedDesc.getName());
        assertNotSame(a.getDescs().get(0), copiedDesc,
                "copied desc must be a distinct instance");
    }

    @Test
    public void areaCopyDescsAreIndependentOfOriginal() {
        AreaVo a = area(5, "urn:child", areaDesc("en", "Original"));

        AreaVo copy = a.copy(DataFilter.get());

        // Mutating the copy's desc must not affect the source desc.
        copy.getDescs().get(0).setName("Changed");
        assertEquals("Original", a.getDescs().get(0).getName(), "source desc must be untouched");
        assertEquals("Changed", copy.getDescs().get(0).getName());
    }

    @Test
    public void areaCopyWithParentFieldCopiesParentAsDistinctInstance() {
        AreaVo parent = area(10, "urn:parent", areaDesc("en", "Parent"));
        AreaVo a = area(5, "urn:child", areaDesc("en", "Child"));
        a.setParent(parent);

        AreaVo copy = a.copy(DataFilter.get().fields(DataFilter.PARENT));

        assertNotNull(copy.getParent(), "parent must be copied when PARENT field present");
        assertNotSame(parent, copy.getParent(),
                "the copied parent must be a distinct instance");
        assertEquals(Integer.valueOf(10), copy.getParent().getId());
        assertEquals("urn:parent", copy.getParent().getMrn());
    }

    @Test
    public void areaCopyWithParentFieldCopiesTwoLevelChain() {
        AreaVo grandParent = area(100, "urn:gp");
        AreaVo parent = area(10, "urn:parent");
        parent.setParent(grandParent);
        AreaVo a = area(5, "urn:child");
        a.setParent(parent);

        AreaVo copy = a.copy(DataFilter.get().fields(DataFilter.PARENT));

        assertNotNull(copy.getParent());
        assertEquals(Integer.valueOf(10), copy.getParent().getId());
        assertNotNull(copy.getParent().getParent(), "the grandparent level must also be copied");
        assertEquals(Integer.valueOf(100), copy.getParent().getParent().getId());
        assertNotSame(grandParent, copy.getParent().getParent());
    }

    @Test
    public void areaCopyWithoutParentFieldLeavesGrandparentChainUncopied() {
        AreaVo parent = area(10, "urn:parent");
        AreaVo a = area(5, "urn:child");
        a.setParent(parent);

        AreaVo copy = a.copy(DataFilter.get());

        assertNull(copy.getParent(), "no PARENT field -> no parent chain at all");
    }

    @Test
    public void areaCopyByLangCopiesOnlyMatchingLangDesc() {
        AreaVo a = area(5, "urn:child",
                areaDesc("en", "Denmark"),
                areaDesc("da", "Danmark"));

        AreaVo copy = a.copy(DataFilter.get().lang("da"));

        assertEquals(1, copy.getDescs().size(),
                "only the matching-language desc should survive the copy");
        AreaDescVo copied = copy.getDescs().get(0);
        assertEquals("da", copied.getLang());
        assertEquals("Danmark", copied.getName());
    }

    @Test
    public void areaCopyByLangFallsBackToFirstDescWhenNoLangMatches() {
        // getDescs(filter) falls back to a singleton of the FIRST desc when none match the lang.
        AreaVo a = area(5, "urn:child",
                areaDesc("en", "Denmark"),
                areaDesc("da", "Danmark"));

        AreaVo copy = a.copy(DataFilter.get().lang("de"));

        assertEquals(1, copy.getDescs().size(), "fallback yields exactly one desc");
        AreaDescVo copied = copy.getDescs().get(0);
        assertEquals("en", copied.getLang(), "the fallback is the FIRST desc (en)");
        assertEquals("Denmark", copied.getName());
    }

    @Test
    public void areaCopyWithNullLangIncludesAllDescs() {
        // A filter with no lang includes every language.
        AreaVo a = area(5, "urn:child",
                areaDesc("en", "Denmark"),
                areaDesc("da", "Danmark"));

        AreaVo copy = a.copy(DataFilter.get());

        assertEquals(2, copy.getDescs().size(), "no lang restriction -> both descs copied");
        assertNotNull(copy.getDesc("en"));
        assertNotNull(copy.getDesc("da"));
        assertEquals("Denmark", copy.getDesc("en").getName());
        assertEquals("Danmark", copy.getDesc("da").getName());
    }

    /*****************************************************************/
    /** CategoryVo.copy                                             **/
    /*****************************************************************/

    @Test
    public void categoryCopyMinimalCopiesScalarsAndDescsButNotParent() {
        CategoryVo parent = category(10, "urn:parent");
        CategoryVo c = category(5, "urn:child", catDesc("en", "Child"));
        c.setActive(false);
        c.setParent(parent);

        CategoryVo copy = c.copy(DataFilter.get());

        assertNotSame(c, copy);
        assertEquals(Integer.valueOf(5), copy.getId());
        assertEquals("urn:child", copy.getMrn());
        assertFalse(copy.isActive());
        assertNull(copy.getParent(), "parent must NOT be copied without the PARENT filter field");
        assertEquals(1, copy.getDescs().size());
        assertEquals("Child", copy.getDescs().get(0).getName());
        assertNotSame(c.getDescs().get(0), copy.getDescs().get(0));
    }

    @Test
    public void categoryCopyWithParentFieldCopiesParentAsDistinctInstance() {
        CategoryVo parent = category(10, "urn:parent", catDesc("en", "Parent"));
        CategoryVo c = category(5, "urn:child", catDesc("en", "Child"));
        c.setParent(parent);

        CategoryVo copy = c.copy(DataFilter.get().fields(DataFilter.PARENT));

        assertNotNull(copy.getParent());
        assertNotSame(parent, copy.getParent());
        assertEquals(Integer.valueOf(10), copy.getParent().getId());
        assertEquals("urn:parent", copy.getParent().getMrn());
    }

    @Test
    public void categoryCopyWithParentFieldCopiesTwoLevelChain() {
        CategoryVo grandParent = category(100, "urn:gp");
        CategoryVo parent = category(10, "urn:parent");
        parent.setParent(grandParent);
        CategoryVo c = category(5, "urn:child");
        c.setParent(parent);

        CategoryVo copy = c.copy(DataFilter.get().fields(DataFilter.PARENT));

        assertNotNull(copy.getParent());
        assertEquals(Integer.valueOf(10), copy.getParent().getId());
        assertNotNull(copy.getParent().getParent());
        assertEquals(Integer.valueOf(100), copy.getParent().getParent().getId());
    }

    @Test
    public void categoryCopyByLangCopiesOnlyMatchingLangDesc() {
        CategoryVo c = category(5, "urn:child",
                catDesc("en", "Navigation"),
                catDesc("da", "Navigation-da"));

        CategoryVo copy = c.copy(DataFilter.get().lang("da"));

        assertEquals(1, copy.getDescs().size());
        assertEquals("da", copy.getDescs().get(0).getLang());
        assertEquals("Navigation-da", copy.getDescs().get(0).getName());
    }

    /*****************************************************************/
    /** Component-prefixed filter fields (forComponent semantics)   **/
    /*****************************************************************/

    @Test
    public void areaCopyHonoursComponentPrefixedParentField() {
        // "Area.parent" is stripped to "parent" by forComponent("Area"), so parent IS copied.
        AreaVo parent = area(10, "urn:parent");
        AreaVo a = area(5, "urn:child");
        a.setParent(parent);

        AreaVo copy = a.copy(DataFilter.get().fields("Area.parent"));

        assertNotNull(copy.getParent(), "Area-prefixed parent field must enable parent copy");
        assertEquals(Integer.valueOf(10), copy.getParent().getId());
    }

    @Test
    public void areaCopyIgnoresOtherComponentPrefixedParentField() {
        // "Category.parent" is NOT stripped for the Area component, so parent stays null.
        AreaVo parent = area(10, "urn:parent");
        AreaVo a = area(5, "urn:child");
        a.setParent(parent);

        AreaVo copy = a.copy(DataFilter.get().fields("Category.parent"));

        assertNull(copy.getParent(), "a foreign-component parent field must not enable parent copy");
    }

    @Test
    public void categoryCopyHonoursComponentPrefixedParentField() {
        CategoryVo parent = category(10, "urn:parent");
        CategoryVo c = category(5, "urn:child");
        c.setParent(parent);

        CategoryVo copy = c.copy(DataFilter.get().fields("Category.parent"));

        assertNotNull(copy.getParent());
        assertEquals(Integer.valueOf(10), copy.getParent().getId());
    }

    /*****************************************************************/
    /** ALL ("*") wildcard field                                    **/
    /*****************************************************************/

    @Test
    public void areaCopyWithAllFieldCopiesParent() {
        AreaVo parent = area(10, "urn:parent");
        AreaVo a = area(5, "urn:child");
        a.setParent(parent);

        AreaVo copy = a.copy(DataFilter.get().fields(DataFilter.ALL));

        assertNotNull(copy.getParent(), "the '*' wildcard must enable parent copy");
        assertEquals(Integer.valueOf(10), copy.getParent().getId());
    }

    /*****************************************************************/
    /** copy() with no descs                                        **/
    /*****************************************************************/

    @Test
    public void areaCopyWithNullDescsLeavesCopyDescsNull() {
        // copyDescs(null) is a no-op, and the new AreaVo never had its descs list created.
        AreaVo a = new AreaVo();
        a.setId(5);
        a.setMrn("urn:child");
        // descs intentionally left null

        AreaVo copy = a.copy(DataFilter.get());

        assertEquals(Integer.valueOf(5), copy.getId());
        assertNull(copy.getDescs(), "no source descs -> copy.descs stays null");
    }

    @Test
    public void areaCopyWithEmptyDescsLeavesCopyDescsNull() {
        // getDescs(filter) returns the (empty) list; copyDescs short-circuits on size()==0,
        // so the copy never creates its own descs list.
        AreaVo a = new AreaVo();
        a.setId(5);
        a.setDescs(new java.util.ArrayList<>());

        AreaVo copy = a.copy(DataFilter.get());

        assertNull(copy.getDescs(), "empty source descs -> copy.descs stays null");
    }

    /*****************************************************************/
    /** Description copy / blank-removal behaviour                  **/
    /*****************************************************************/

    @Test
    public void copyDescsCopiesNameButNotLangContractuallyMatchesByLang() {
        // copyDesc copies only content (name), not lang; checkCreateDesc handles lang matching.
        AreaVo target = new AreaVo();
        List<AreaDescVo> source = Arrays.asList(areaDesc("en", "Denmark"), areaDesc("da", "Danmark"));

        target.copyDescs(source);

        assertEquals(2, target.getDescs().size());
        assertEquals("Denmark", target.getDesc("en").getName());
        assertEquals("Danmark", target.getDesc("da").getName());
        // The copied descs are distinct instances from the source list.
        assertNotSame(source.get(0), target.getDesc("en"));
    }

    @Test
    public void copyDescsAndRemoveBlanksDropsBlankDescs() {
        AreaVo target = new AreaVo();
        List<AreaDescVo> source = Arrays.asList(
                areaDesc("en", "Denmark"),
                areaDesc("da", null));   // blank -> should be removed

        target.copyDescsAndRemoveBlanks(source);

        assertEquals(1, target.getDescs().size(), "the blank 'da' desc must be removed");
        assertEquals("en", target.getDescs().get(0).getLang());
        assertEquals("Denmark", target.getDescs().get(0).getName());
    }

    @Test
    public void descDefinedReflectsNameField() {
        assertTrue(areaDesc("en", "Denmark").descDefined());
        assertFalse(areaDesc("en", null).descDefined());
        assertFalse(areaDesc("en", "").descDefined(), "empty string is treated as blank");
        assertTrue(catDesc("da", "x").descDefined());
        assertFalse(catDesc("da", "").descDefined());
    }

    /*****************************************************************/
    /** sortDescs                                                   **/
    /*****************************************************************/

    @Test
    public void sortDescsMovesRequestedLangToFront() {
        AreaVo a = area(1, "mrn",
                areaDesc("en", "Denmark"),
                areaDesc("da", "Danmark"),
                areaDesc("de", "Daenemark"));

        a.sortDescs("da");

        assertEquals("da", a.getDescs().get(0).getLang(), "requested lang must move to the front");
    }

    @Test
    public void sortDescsLeavesNullLangDescAtEnd() {
        AreaVo a = area(1, "mrn",
                areaDesc(null, "Fallback"),
                areaDesc("da", "Danmark"));

        a.sortDescs("da");

        assertEquals("da", a.getDescs().get(0).getLang());
        assertNull(a.getDescs().get(1).getLang(), "null-lang descs sort to the end");
    }
}

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
package org.niord.core.message.vo;

import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.promulgation.BaseMessagePromulgation;
import org.niord.core.promulgation.vo.BaseMessagePromulgationVo;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.util.UidUtils;
import org.niord.model.message.MessageDescVo;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessagePartVo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SystemMessageVo}-specific behavior that is layered on top of the base
 * {@code MessageVo} model: ID/repo-path assignment ({@link SystemMessageVo#assignNewId()}), the
 * NAVTEX-style number/year id ({@link SystemMessageVo#computeNumberYearId()}), the message-part
 * lookup/creation helpers ({@code partsOfType}, {@code part}, {@code checkCreatePart}), the
 * promulgation lookup/creation helpers ({@code promulgation}, {@code checkCreatePromulgations}),
 * and the relative attachment-link repair performed by {@link SystemMessageVo#toMessageRepo()} via
 * the private {@code fixDescriptionRepoPaths} step.
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no Spring, no mocking
 * library. The value object is built directly with {@code new} + setters and assertions check
 * concrete returned values, mutated collection state, ordering and identity, so the tests survive a
 * future Quarkus-&gt;Spring port. A minimal hand-written {@link FixturePromulgationVo} concrete
 * subclass stands in for the abstract {@link BaseMessagePromulgationVo} (no mocking framework needed).
 *
 * <p>IDs that flow into repository paths are produced with {@link UidUtils#newUid()} (valid UUIDs),
 * never arbitrary strings, since {@code UidUtils.uidToHashedFolderPath} throws for non-UUID ids.
 */
public class SystemMessageVoTest {

    /**
     * Minimal concrete {@link BaseMessagePromulgationVo} fixture used only by these tests.
     * It declares the abstract members trivially: {@code toEntity()} returns null and
     * {@code promulgationDataDefined()} returns true. It carries no real promulgation data.
     */
    @SuppressWarnings("rawtypes")
    static class FixturePromulgationVo extends BaseMessagePromulgationVo {

        FixturePromulgationVo(PromulgationTypeVo type) {
            super(type);
        }

        @Override
        public BaseMessagePromulgation toEntity() {
            return null;
        }

        @Override
        public boolean promulgationDataDefined() {
            return true;
        }
    }

    /** A second distinct subclass to exercise class-filtered promulgation lookup negatives. */
    @SuppressWarnings("rawtypes")
    static class OtherPromulgationVo extends BaseMessagePromulgationVo {

        OtherPromulgationVo(PromulgationTypeVo type) {
            super(type);
        }

        @Override
        public BaseMessagePromulgation toEntity() {
            return null;
        }

        @Override
        public boolean promulgationDataDefined() {
            return true;
        }
    }

    /** Builds a PromulgationTypeVo with the given typeId. */
    private static PromulgationTypeVo promulgationType(String typeId) {
        PromulgationTypeVo type = new PromulgationTypeVo();
        type.setTypeId(typeId);
        return type;
    }

    /** Builds a MessagePartVo of the given type. */
    private static MessagePartVo part(MessagePartType type) {
        MessagePartVo part = new MessagePartVo();
        part.setType(type);
        return part;
    }

    /***************************/
    /** assignNewId           **/
    /***************************/

    @Test
    public void assignNewId_setsValidUuidAndHashedRepoPath() {
        SystemMessageVo vo = new SystemMessageVo();

        vo.assignNewId();

        String id = vo.getId();
        assertNotNull(id, "assignNewId must populate the id");
        assertTrue(UidUtils.validUidFormat(id), "id must be a valid UUID");

        String expectedRepoPath =
                UidUtils.uidToHashedFolderPath(Message.MESSAGE_REPO_FOLDER, id);
        assertEquals(expectedRepoPath, vo.getRepoPath(),
                "repoPath must be the hashed folder path derived from the new id");
        // The repo path is rooted at the public "messages" folder.
        assertEquals("messages", Message.MESSAGE_REPO_FOLDER);
        assertTrue(vo.getRepoPath().startsWith("messages/"));
        assertTrue(vo.getRepoPath().endsWith("/" + id));
    }

    @Test
    public void assignNewId_calledTwice_yieldsTwoDifferentIds() {
        SystemMessageVo vo = new SystemMessageVo();

        vo.assignNewId();
        String firstId = vo.getId();
        String firstRepoPath = vo.getRepoPath();

        vo.assignNewId();
        String secondId = vo.getId();

        assertNotEquals(firstId, secondId, "each assignNewId call must produce a fresh id");
        assertNotEquals(firstRepoPath, vo.getRepoPath(), "repoPath must follow the new id");
        assertTrue(UidUtils.validUidFormat(secondId));
    }

    /***************************/
    /** computeNumberYearId   **/
    /***************************/

    @Test
    public void computeNumberYearId_formatsNumberAndTwoDigitYear() {
        SystemMessageVo vo = new SystemMessageVo();
        vo.setNumber(7);
        vo.setYear(2023);

        assertEquals("007/23", vo.computeNumberYearId());
    }

    @Test
    public void computeNumberYearId_padsAndUsesLargerNumbers() {
        SystemMessageVo vo = new SystemMessageVo();
        vo.setNumber(123);
        vo.setYear(2000);

        assertEquals("123/00", vo.computeNumberYearId());
    }

    @Test
    public void computeNumberYearId_nullNumber_returnsEmptyString() {
        SystemMessageVo vo = new SystemMessageVo();
        vo.setNumber(null);
        vo.setYear(2023);

        assertEquals("", vo.computeNumberYearId());
    }

    @Test
    public void computeNumberYearId_nullYear_returnsEmptyString() {
        SystemMessageVo vo = new SystemMessageVo();
        vo.setNumber(7);
        vo.setYear(null);

        assertEquals("", vo.computeNumberYearId());
    }

    /***************************/
    /** partsOfType / part    **/
    /***************************/

    @Test
    public void partsOfType_nullParts_returnsEmptyListAndPartReturnsNull() {
        SystemMessageVo vo = new SystemMessageVo();
        // getParts() is null by default

        List<MessagePartVo> details = vo.partsOfType(MessagePartType.DETAILS);
        assertNotNull(details, "partsOfType must never return null");
        assertTrue(details.isEmpty(), "partsOfType on null parts must be empty");

        assertNull(vo.part(MessagePartType.DETAILS), "part(type) on null parts must be null");
    }

    @Test
    public void partsOfType_filtersByTypeAndReturnsAllMatches() {
        SystemMessageVo vo = new SystemMessageVo();
        MessagePartVo details1 = part(MessagePartType.DETAILS);
        MessagePartVo details2 = part(MessagePartType.DETAILS);
        MessagePartVo time = part(MessagePartType.TIME);
        vo.setParts(new ArrayList<>(Arrays.asList(details1, details2, time)));

        List<MessagePartVo> detailParts = vo.partsOfType(MessagePartType.DETAILS);
        assertEquals(2, detailParts.size());
        assertSame(details1, detailParts.get(0));
        assertSame(details2, detailParts.get(1));

        assertEquals(1, vo.partsOfType(MessagePartType.TIME).size());
        assertTrue(vo.partsOfType(MessagePartType.POSITIONS).isEmpty());
    }

    @Test
    public void part_returnsFirstMatchByEnumAndByName_andNullForAbsentType() {
        SystemMessageVo vo = new SystemMessageVo();
        MessagePartVo details1 = part(MessagePartType.DETAILS);
        MessagePartVo details2 = part(MessagePartType.DETAILS);
        MessagePartVo time = part(MessagePartType.TIME);
        vo.setParts(new ArrayList<>(Arrays.asList(details1, details2, time)));

        assertSame(details1,
                vo.part(MessagePartType.DETAILS), "part(type) returns the first matching part");
        assertSame(time,
                vo.part("TIME"), "part(String) resolves the enum and returns the match");
        assertNull(vo.part(MessagePartType.POSITIONS), "absent type returns null");
    }

    @Test
    public void part_byInvalidName_throwsIllegalArgumentException() {
        SystemMessageVo vo = new SystemMessageVo();
        vo.setParts(new ArrayList<>(Arrays.asList(part(MessagePartType.DETAILS))));

        try {
            vo.part("NOT_A_PART_TYPE");
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException for an unknown part type name");
        } catch (IllegalArgumentException expected) {
            // MessagePartType.valueOf rejects unknown names
        }
    }

    /***************************/
    /** checkCreatePart       **/
    /***************************/

    @Test
    public void checkCreatePart_createsWhenAbsent_thenReusesWithoutDuplicating() {
        SystemMessageVo vo = new SystemMessageVo();

        MessagePartVo created = vo.checkCreatePart(MessagePartType.DETAILS);
        assertNotNull(created);
        assertEquals(MessagePartType.DETAILS, created.getType());
        assertEquals(1, vo.getParts().size(), "first call must create exactly one part");
        assertSame(created, vo.getParts().get(0));

        MessagePartVo reused = vo.checkCreatePart(MessagePartType.DETAILS);
        assertSame(created, reused, "second call must return the existing part");
        assertEquals(1, vo.getParts().size(), "no duplicate part may be added");
    }

    @Test
    public void checkCreatePart_withIndex_insertsNewPartAtRequestedPosition() {
        SystemMessageVo vo = new SystemMessageVo();
        MessagePartVo existingTime = part(MessagePartType.TIME);
        vo.setParts(new ArrayList<>(Arrays.asList(existingTime)));

        MessagePartVo inserted = vo.checkCreatePart(MessagePartType.DETAILS, 0);

        assertEquals(MessagePartType.DETAILS, inserted.getType());
        assertEquals(2, vo.getParts().size());
        assertSame(inserted, vo.getParts().get(0),
                "the new part must be inserted at the requested index");
        assertSame(existingTime, vo.getParts().get(1));
    }

    @Test
    public void checkCreatePart_withIndex_existingTypeIgnoresIndexAndDoesNotInsert() {
        SystemMessageVo vo = new SystemMessageVo();
        MessagePartVo existingDetails = part(MessagePartType.DETAILS);
        MessagePartVo existingTime = part(MessagePartType.TIME);
        vo.setParts(new ArrayList<>(Arrays.asList(existingDetails, existingTime)));

        MessagePartVo result = vo.checkCreatePart(MessagePartType.DETAILS, 1);

        assertSame(existingDetails, result, "existing part of that type is returned");
        assertEquals(2, vo.getParts().size(), "no insertion happens when the type already exists");
    }

    /***************************/
    /** promulgation lookups  **/
    /***************************/

    @Test
    public void checkCreatePromulgations_createsListWhenNullAndIsIdempotent() {
        SystemMessageVo vo = new SystemMessageVo();
        assertNull(vo.getPromulgations(), "promulgations is null by default");

        List<BaseMessagePromulgationVo> list = vo.checkCreatePromulgations();
        assertNotNull(list);
        assertTrue(list.isEmpty());
        assertSame(list, vo.getPromulgations(), "the created list must be exposed via the getter");

        assertSame(list, vo.checkCreatePromulgations(),
                "a second call must return the same list instance");
    }

    @Test
    public void promulgation_byTypeId_returnsMatchAndNullForMissing() {
        SystemMessageVo vo = new SystemMessageVo();
        FixturePromulgationVo navtex = new FixturePromulgationVo(promulgationType("navtex"));
        vo.checkCreatePromulgations().add(navtex);

        assertSame(navtex, vo.promulgation("navtex"));
        assertNull(vo.promulgation("missing"));
    }

    @Test
    public void promulgation_byClassAndTypeId_returnsMatchAndRespectsClassFilter() {
        SystemMessageVo vo = new SystemMessageVo();
        FixturePromulgationVo navtex = new FixturePromulgationVo(promulgationType("navtex"));
        vo.checkCreatePromulgations().add(navtex);

        assertSame(navtex, vo.promulgation(FixturePromulgationVo.class, "navtex"),
                "matching class + typeId returns the entry");
        assertNull(vo.promulgation(OtherPromulgationVo.class, "navtex"),
                "matching typeId but wrong class returns null");
        assertNull(vo.promulgation(FixturePromulgationVo.class, "missing"),
                "matching class but wrong typeId returns null");
    }

    @Test
    public void promulgation_nullPromulgationsList_returnsNull() {
        SystemMessageVo vo = new SystemMessageVo();
        // promulgations list is null and not created

        assertNull(vo.promulgation("navtex"));
        assertNull(vo.promulgation(FixturePromulgationVo.class, "navtex"));
        assertNull(vo.getPromulgations(),
                "the lookups must not lazily create the list");
    }

    /***************************/
    /** toMessageRepo / link fix */
    /***************************/

    @Test
    public void toMessageRepo_addsLeadingSlashToRelativeAttachmentLinks() {
        SystemMessageVo vo = new SystemMessageVo();

        // repoPath == editRepoPath so rewriteRepoPath is effectively a no-op and we isolate
        // the fixDescriptionRepoPaths leading-slash repair invoked by toMessageRepo().
        String repoPath = "messages/a/bc/some-folder";
        vo.setRepoPath(repoPath);
        vo.setEditRepoPath(repoPath);

        MessagePartDescVo desc = new MessagePartDescVo();
        desc.setLang("en");
        // Note: no leading slash on "rest/repo/file" — this is the relative form a rich-text editor may store.
        desc.setDetails("<img src=\"rest/repo/file/x.png\"/>");
        MessagePartVo p = part(MessagePartType.DETAILS);
        p.setDescs(new ArrayList<>(Arrays.asList(desc)));
        vo.setParts(new ArrayList<>(Arrays.asList(p)));

        vo.toMessageRepo();

        assertEquals("<img src=\"/rest/repo/file/x.png\"/>",
                vo.getParts().get(0).getDescs().get(0).getDetails(),
                "the leading slash must be restored on the attachment link");
    }

    @Test
    public void toMessageRepo_leavesAlreadyAbsoluteAttachmentLinksUnchanged() {
        SystemMessageVo vo = new SystemMessageVo();
        String repoPath = "messages/a/bc/some-folder";
        vo.setRepoPath(repoPath);
        vo.setEditRepoPath(repoPath);

        MessagePartDescVo desc = new MessagePartDescVo();
        desc.setLang("en");
        String alreadyAbsolute = "<img src=\"/rest/repo/file/x.png\"/>";
        desc.setDetails(alreadyAbsolute);
        MessagePartVo p = part(MessagePartType.DETAILS);
        p.setDescs(new ArrayList<>(Arrays.asList(desc)));
        vo.setParts(new ArrayList<>(Arrays.asList(p)));

        vo.toMessageRepo();

        assertEquals(alreadyAbsolute,
                vo.getParts().get(0).getDescs().get(0).getDetails(),
                "an already-absolute link must not be double-prefixed");
    }

    @Test
    public void toMessageRepo_withNoParts_isANoOp() {
        SystemMessageVo vo = new SystemMessageVo();
        vo.setRepoPath("messages/a/bc/some-folder");
        vo.setEditRepoPath("messages/a/bc/some-folder");
        // getParts() is null

        vo.toMessageRepo();

        assertNull(vo.getParts(), "parts remain untouched (null) when there are none to fix");
    }

    /***************************/
    /** checkCreateDescs      **/
    /***************************/

    /** Collects the languages of the message-level descs, preserving order. */
    private static List<String> descLangs(SystemMessageVo vo) {
        if (vo.getDescs() == null) {
            return new ArrayList<>();
        }
        return vo.getDescs().stream().map(MessageDescVo::getLang).collect(Collectors.toList());
    }

    @Test
    public void checkCreateDescs_createsOneDescPerLanguageOnTheMessageItself() {
        SystemMessageVo vo = new SystemMessageVo();
        assertNull(vo.getDescs(), "no descs exist up front");

        vo.checkCreateDescs(new String[]{"en", "da"});

        assertNotNull(vo.getDescs());
        assertEquals(2, vo.getDescs().size(), "one desc per requested language must be created");
        assertEquals(Arrays.asList("en", "da"), descLangs(vo));
    }

    @Test
    public void checkCreateDescs_isIdempotentAndDoesNotDuplicateExistingLanguages() {
        SystemMessageVo vo = new SystemMessageVo();
        vo.checkCreateDescs(new String[]{"en", "da"});

        // A second call with the same languages must reuse the existing descs.
        vo.checkCreateDescs(new String[]{"en", "da"});

        assertEquals(2, vo.getDescs().size(), "languages must not be duplicated across calls");
        assertEquals(Arrays.asList("en", "da"), descLangs(vo));
    }

    @Test
    public void checkCreateDescs_addsOnlyMissingLanguagesToExistingDescs() {
        SystemMessageVo vo = new SystemMessageVo();
        MessageDescVo en = new MessageDescVo();
        en.setLang("en");
        en.setTitle("Existing English title");
        vo.setDescs(new ArrayList<>(Arrays.asList(en)));

        vo.checkCreateDescs(new String[]{"en", "da", "de"});

        assertEquals(3, vo.getDescs().size(), "only the two missing languages are added");
        assertEquals(Arrays.asList("en", "da", "de"), descLangs(vo));
        assertSame(en, vo.getDescs().get(0), "the pre-existing 'en' desc is reused, not replaced");
        assertEquals("Existing English title",
                vo.getDescs().get(0).getTitle(), "its data is left intact");
    }

    @Test
    public void checkCreateDescs_emptyLanguageArray_createsNoDescs() {
        SystemMessageVo vo = new SystemMessageVo();

        vo.checkCreateDescs(new String[]{});

        assertNull(vo.getDescs(), "an empty language array must not create any descs");
    }

    @Test
    public void checkCreateDescs_alsoCreatesDescsOnContainedMessageParts() {
        SystemMessageVo vo = new SystemMessageVo();
        MessagePartVo detailsPart = part(MessagePartType.DETAILS);
        vo.setParts(new ArrayList<>(Arrays.asList(detailsPart)));

        vo.checkCreateDescs(new String[]{"en", "da"});

        // The message itself gets descs ...
        assertEquals(2, vo.getDescs().size());
        // ... and so does the contained message part (it is part of localizableStream()).
        assertNotNull(detailsPart.getDescs(), "the message part must receive descs too");
        assertEquals(2, detailsPart.getDescs().size());
        List<String> partLangs = detailsPart.getDescs().stream()
                .map(MessagePartDescVo::getLang)
                .collect(Collectors.toList());
        assertEquals(Arrays.asList("en", "da"), partLangs);
    }
}

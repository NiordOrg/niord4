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
package org.niord.core.publication;

import org.junit.jupiter.api.Test;
import org.niord.core.publication.vo.MessagePublication;
import org.niord.core.publication.vo.PeriodicalType;
import org.niord.core.publication.vo.PublicationMainType;
import org.niord.core.publication.vo.PublicationStatus;
import org.niord.core.publication.vo.SystemPublicationVo;
import org.niord.core.util.UidUtils;
import org.niord.model.DataFilter;
import org.niord.model.publication.PublicationType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link Publication} entity's pure logic: the publication-id / repository-path
 * (filesystem layout) assignment, the link-rewriting performed when a new id is assigned, the
 * template-copy rules in {@code updateFromTemplate}, the value-object round-trip / revision bump in
 * {@code toVo}, and the {@link PublicationStatus#isDraft()} helper.
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks. They assert
 * concrete field values and the exact hashed repo-path layout so they survive a future Spring port.
 * The "filesystem" behaviour under test is the deterministic {@code publications/x/yy/uuid} folder
 * mapping computed by {@link UidUtils#uidToHashedFolderPath}; actual disk I/O lives in
 * {@code RepositoryService} and is covered (or documented) separately.
 */
public class PublicationEntityTest {

    /** A fixed, valid UUID so the derived repo path is deterministic. */
    private static final String UID = "abcd1234-5678-90ab-cdef-1234567890ab";
    private static final String UID_REPO = "publications/a/bc/abcd1234-5678-90ab-cdef-1234567890ab";

    // --------------------------------------------------------------------------------------------- //
    // checkPublicationId — repo-path (filesystem) assignment
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void checkPublicationIdDerivesRepoPathFromExistingId() {
        Publication p = new Publication();
        p.setPublicationId(UID);
        assertNull(p.getRepoPath(), "precondition: no repo path yet");

        p.checkPublicationId();

        // The id is untouched and the hashed folder path is derived from it.
        assertEquals(UID, p.getPublicationId());
        assertEquals(UID_REPO, p.getRepoPath());
    }

    @Test
    public void checkPublicationIdAssignsNewIdAndRepoPathWhenBlank() {
        Publication p = new Publication();

        p.checkPublicationId();

        assertTrue(UidUtils.validUidFormat(p.getPublicationId()), "a fresh UUID must be assigned");
        // The repo path must be the hashed-folder mapping of the freshly assigned id.
        assertEquals(UidUtils.uidToHashedFolderPath(Publication.PUBLICATION_REPO_FOLDER, p.getPublicationId()),
                p.getRepoPath());
    }

    @Test
    public void checkPublicationIdLeavesExistingIdAndRepoPathUntouched() {
        Publication p = new Publication();
        p.setPublicationId(UID);
        p.setRepoPath("publications/custom/path");

        p.checkPublicationId();

        // Neither value is recomputed once both are present.
        assertEquals(UID, p.getPublicationId());
        assertEquals("publications/custom/path", p.getRepoPath());
    }

    // --------------------------------------------------------------------------------------------- //
    // assignNewPublicationId
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void assignNewPublicationIdReplacesIdAndRepoPath() {
        Publication p = new Publication();
        p.setPublicationId(UID);
        p.checkPublicationId();
        String oldRepo = p.getRepoPath();

        String returned = p.assignNewPublicationId();

        assertEquals(returned, p.getPublicationId(), "must return the newly assigned id");
        assertNotEquals(UID, p.getPublicationId());
        assertTrue(UidUtils.validUidFormat(p.getPublicationId()));
        assertNotEquals(oldRepo, p.getRepoPath());
        assertEquals(UidUtils.uidToHashedFolderPath(Publication.PUBLICATION_REPO_FOLDER, p.getPublicationId()),
                p.getRepoPath());
    }

    @Test
    public void assignNewPublicationIdRewritesDescLinksToNewRepoPath() {
        Publication p = new Publication();
        p.setPublicationId(UID);
        p.checkPublicationId();
        String oldRepo = p.getRepoPath();

        // A desc link that references a file under the OLD repo path.
        PublicationDesc desc = p.createDesc("en");
        desc.setLink("<a href=\"/rest/repo/file/" + oldRepo + "/doc.pdf\">file</a>");

        p.assignNewPublicationId();
        String newRepo = p.getRepoPath();
        String updated = p.getDesc("en").getLink();

        // The repo-path segment of the link is rewritten to point at the new folder.
        assertTrue(updated.contains("/rest/repo/file/" + newRepo + "/doc.pdf"),
                "link must reference the new repo path: " + updated);
        assertFalse(updated.contains(oldRepo),
                "link must no longer reference the old repo path: " + updated);
    }

    @Test
    public void assignNewPublicationIdLeavesUnrelatedLinksUnchanged() {
        Publication p = new Publication();
        p.setPublicationId(UID);
        p.checkPublicationId();

        PublicationDesc desc = p.createDesc("en");
        desc.setLink("http://external.example.dk/doc.pdf");

        p.assignNewPublicationId();

        // A link that does not reference the old repo path is left exactly as-is.
        assertEquals("http://external.example.dk/doc.pdf", p.getDesc("en").getLink());
    }

    // --------------------------------------------------------------------------------------------- //
    // updateFromTemplate
    // --------------------------------------------------------------------------------------------- //

    /** Builds a template publication whose fields differ from a fresh publication's defaults. */
    private Publication template() {
        Publication template = new Publication();
        template.setType(PublicationType.REPOSITORY);
        template.setPeriodicalType(PeriodicalType.WEEKLY);
        template.setLanguageSpecific(true);
        template.setMessagePublication(MessagePublication.EXTERNAL);
        template.getPrintSettings().put("pageSize", "A4");
        PublicationDesc d = template.createDesc("en");
        d.setTitleFormat("My Title");
        d.setLink("http://template.dk");
        d.setFileName("report.pdf");
        d.setMessagePublicationFormat("Fmt");
        return template;
    }

    @Test
    public void updateFromTemplateCopiesTemplateFieldsForDraft() {
        Publication template = template();

        Publication p = new Publication();
        p.setStatus(PublicationStatus.DRAFT);
        p.setTemplate(template);

        // messageTagFormat stays blank, so no MessageTagService is needed; passing null is safe here.
        p.updateFromTemplate(new PublicationTemplateUpdateCtx(p, null));

        assertEquals(PublicationType.REPOSITORY, p.getType());
        assertEquals(PeriodicalType.WEEKLY, p.getPeriodicalType());
        assertEquals(MessagePublication.EXTERNAL, p.getMessagePublication());
        assertTrue(p.isLanguageSpecific());
        assertEquals("A4", p.getPrintSettings().get("pageSize"));

        PublicationDesc desc = p.getDesc("en");
        assertEquals("My Title", desc.getTitle());
        assertEquals("http://template.dk", desc.getLink());
        assertEquals("report.pdf", desc.getFileName());
        assertEquals("Fmt", desc.getMessagePublicationFormat());
    }

    @Test
    public void updateFromTemplateIsNoOpForNonDraftStatus() {
        Publication template = template();

        Publication p = new Publication();
        p.setStatus(PublicationStatus.ACTIVE); // not a draft -> template is ignored
        p.setTemplate(template);

        p.updateFromTemplate(new PublicationTemplateUpdateCtx(p, null));

        // type keeps the entity default (LINK); nothing from the template was applied.
        assertEquals(PublicationType.LINK, p.getType());
        assertNull(p.getPeriodicalType());
        assertTrue(p.getDescs().isEmpty());
    }

    @Test
    public void updateFromTemplateIsNoOpWhenNoTemplate() {
        Publication p = new Publication();
        p.setStatus(PublicationStatus.DRAFT);
        p.setType(PublicationType.LINK);
        // no template set

        p.updateFromTemplate(new PublicationTemplateUpdateCtx(p, null));

        assertEquals(PublicationType.LINK, p.getType());
        assertNull(p.getPeriodicalType());
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo / constructor round-trip
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoIncrementsRevisionAndCopiesSystemFields() {
        Publication p = new Publication();
        p.setPublicationId(UID);
        p.setRepoPath(UID_REPO);
        p.setRevision(3);
        p.setStatus(PublicationStatus.ACTIVE);
        p.setMainType(PublicationMainType.PUBLICATION);
        p.setType(PublicationType.LINK);

        SystemPublicationVo vo = p.toVo(SystemPublicationVo.class, DataFilter.get());

        // toVo deliberately bumps the revision (used as the repo sub-folder when editing).
        assertEquals(4, vo.getRevision());
        assertEquals(UID, vo.getPublicationId());
        assertEquals(UID_REPO, vo.getRepoPath());
        assertEquals(PublicationStatus.ACTIVE, vo.getStatus());
        assertEquals(PublicationType.LINK, vo.getType());
    }

    @Test
    public void constructorFromSystemVoCopiesFields() {
        SystemPublicationVo vo = new SystemPublicationVo();
        vo.setPublicationId(UID);
        vo.setRevision(7);
        vo.setStatus(PublicationStatus.INACTIVE);
        vo.setMainType(PublicationMainType.TEMPLATE);
        vo.setType(PublicationType.REPOSITORY);
        vo.setMessagePublication(MessagePublication.INTERNAL);
        vo.setLanguageSpecific(true);

        Publication p = new Publication(vo);

        assertEquals(UID, p.getPublicationId());
        assertEquals(7, p.getRevision());
        assertEquals(PublicationStatus.INACTIVE, p.getStatus());
        assertEquals(PublicationMainType.TEMPLATE, p.getMainType());
        assertEquals(PublicationType.REPOSITORY, p.getType());
        assertEquals(MessagePublication.INTERNAL, p.getMessagePublication());
        assertTrue(p.isLanguageSpecific());
    }

    // --------------------------------------------------------------------------------------------- //
    // PublicationStatus.isDraft
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void isDraftIsTrueOnlyForDraftAndRecording() {
        assertTrue(PublicationStatus.DRAFT.isDraft());
        assertTrue(PublicationStatus.RECORDING.isDraft());
        assertFalse(PublicationStatus.ACTIVE.isDraft());
        assertFalse(PublicationStatus.INACTIVE.isDraft());
    }
}

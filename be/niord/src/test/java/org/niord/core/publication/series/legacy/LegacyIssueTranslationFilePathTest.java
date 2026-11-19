/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.core.publication.series.legacy;

import org.junit.jupiter.api.Test;
import org.niord.core.publication.Publication;
import org.niord.core.publication.PublicationDesc;
import org.niord.core.publication.series.PublicationIssue;
import org.niord.core.publication.vo.PublicationStatus;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where an imported issue's file is: the file its legacy link names, not the one
 * the current revision would name.
 *
 * The old product raised a publication's revision each time the publication was
 * opened for editing, whether or not a file was uploaded, so a released file can
 * sit in an older revision folder than the current one while its link, which the
 * public site serves, still names it there. A filePath built from the current
 * revision names no file: the repository import reports it missing, the archive
 * before a replacement finds nothing, and a corrected upload lands where no link
 * points. The import check counts what is still apart after the import.
 */
class LegacyIssueTranslationFilePathTest {

    private static final String REPO = "publications/a/bc/abcdef";

    private static Publication legacy(int revision, String fileName, String link) {
        Publication p = new Publication();
        p.setPublicationId("abcdef");
        p.setStatus(PublicationStatus.ACTIVE);
        p.setRepoPath(REPO);
        p.setRevision(revision);
        PublicationDesc d = new PublicationDesc();
        d.setLang("en");
        d.setTitle("Probe");
        d.setFileName(fileName);
        d.setLink(link);
        d.setEntity(p);
        p.setDescs(new ArrayList<>(List.of(d)));
        return p;
    }

    private static String importedFilePath(Publication legacy) {
        PublicationIssue issue = LegacyIssueTranslation.translate(legacy, null, new Date(), (Date) null);
        return issue.getDescs().getFirst().getFilePath();
    }

    @Test
    void aLinkIntoAnOlderRevisionFolderGivesThatFolder() {
        Publication p = legacy(3, "notice.pdf", "/rest/repo/file/" + REPO + "/1/notice.pdf");

        assertEquals(REPO + "/1/notice.pdf", importedFilePath(p),
                "the file is where the link says, in the folder of the revision it was uploaded in; "
                        + "the current revision's folder holds nothing");
    }

    @Test
    void anEncodedLinkGivesTheNameAsStored() {
        Publication p = legacy(2, "Notice ${year}.pdf",
                "/rest/repo/file/" + REPO + "/1/Notice%20%24%7Byear%7D.pdf");

        assertEquals(REPO + "/1/Notice ${year}.pdf", importedFilePath(p),
                "the store holds the name as written; the link's encoding is only the URL's");
    }

    @Test
    void aPlusInTheNameStaysAPlus() {
        // The repository endpoint decodes a request path as a path, where '+' stays '+'; a form decoder would read
        // it as a space and name a file the store does not hold
        assertEquals(REPO + "/2/NtM 1+2.pdf", importedFilePath(legacy(2, "NtM 1+2.pdf",
                        "/rest/repo/file/" + REPO + "/2/NtM%201+2.pdf")),
                "the current revision's folder");
        assertEquals(REPO + "/1/NtM 1+2.pdf", importedFilePath(legacy(3, "NtM 1+2.pdf",
                        "/rest/repo/file/" + REPO + "/1/NtM%201+2.pdf")),
                "an older revision's folder");
        assertEquals(REPO + "/1/NtM 1 2.pdf", importedFilePath(legacy(3, "NtM 1 2.pdf",
                        "/rest/repo/file/" + REPO + "/1/NtM+1+2.pdf")),
                "a link written with '+' for a space still gives the folder; the name is the descriptor's");
    }

    @Test
    void aLinkNamingAnotherFileKeepsTheCurrentRevision() {
        assertEquals(REPO + "/3/notice.pdf", importedFilePath(legacy(3, "notice.pdf",
                        "/rest/repo/file/" + REPO + "/1/other.pdf")),
                "the name is the descriptor's; a link to another name is counted by the import check, not guessed");
    }

    @Test
    void aLinkThatAgreesWithTheCurrentRevisionChangesNothing() {
        Publication p = legacy(2, "notice.pdf", "/rest/repo/file/" + REPO + "/2/notice.pdf");

        assertEquals(REPO + "/2/notice.pdf", importedFilePath(p));
    }

    @Test
    void withoutARepositoryLinkUnderTheFolderTheCurrentRevisionIsKept() {
        String current = REPO + "/4/notice.pdf";

        assertEquals(current, importedFilePath(legacy(4, "notice.pdf", null)), "no link");
        assertEquals(current, importedFilePath(legacy(4, "notice.pdf", "https://example.org/notice.pdf")),
                "a link outside the repository names no stored file");
        assertEquals(current, importedFilePath(legacy(4, "notice.pdf",
                        "/rest/repo/file/publications/x/yz/other/1/notice.pdf")),
                "a file under another publication's folder is not this issue's file");
        assertEquals(current, importedFilePath(legacy(4, "notice.pdf",
                        "/rest/repo/file/" + REPO + "/../other/1/notice.pdf")),
                "a link that climbs out of the folder is not under it");
    }

    @Test
    void theImportCheckCountsALinkThatServesAnotherFile() {
        assertTrue(ImportCheckService.linkNamesAnotherFile(
                        "/rest/repo/file/" + REPO + "/1/notice.pdf", REPO + "/3/notice.pdf"),
                "the link serves the file in folder 1, the row names folder 3");
        assertFalse(ImportCheckService.linkNamesAnotherFile(
                "/rest/repo/file/" + REPO + "/1/notice.pdf", REPO + "/1/notice.pdf"));
        assertFalse(ImportCheckService.linkNamesAnotherFile(
                        "/rest/repo/file/" + REPO + "/1/Notice%20%24%7Byear%7D.pdf", REPO + "/1/Notice ${year}.pdf"),
                "an encoded link names the file its decoded path is");
        assertFalse(ImportCheckService.linkNamesAnotherFile(
                        "/rest/repo/file/" + REPO + "/1/NtM%201+2.pdf", REPO + "/1/NtM 1+2.pdf"),
                "a '+' in a link's path is a '+' in the file it serves");
        assertTrue(ImportCheckService.linkNamesAnotherFile(
                        "/rest/repo/file/" + REPO + "/1/other.pdf", REPO + "/3/notice.pdf"),
                "a link to another name");
        assertFalse(ImportCheckService.linkNamesAnotherFile("https://example.org/notice.pdf", REPO + "/1/notice.pdf"),
                "a link outside the repository names no stored file");
        assertFalse(ImportCheckService.linkNamesAnotherFile(null, REPO + "/1/notice.pdf"));
        assertTrue(ImportCheckService.linkNamesAnotherFile("/rest/repo/file/" + REPO + "/1/notice.pdf", null),
                "a repository link beside no filePath at all");
    }
}

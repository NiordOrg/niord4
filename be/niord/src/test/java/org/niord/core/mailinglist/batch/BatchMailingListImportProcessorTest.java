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
package org.niord.core.mailinglist.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.mailinglist.MailingList;
import org.niord.core.mailinglist.MailingListDesc;
import org.niord.core.mailinglist.vo.MailingListDescVo;
import org.niord.core.mailinglist.vo.MailingListVo;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Framework-neutral unit tests for {@link BatchMailingListImportProcessor#processItem(Object)}.
 *
 * <p>The processor is the conversion step of the mailing-list import batch job: it casts each batch item to a
 * {@link MailingListVo} and constructs the corresponding {@link MailingList} entity template via
 * {@code new MailingList(vo)}. The CDI-injected infrastructure inherited from {@code AbstractItemHandler}
 * (job context, logger bridge, etc.) is never touched by {@code processItem}, so the bean is exercised here as
 * a plain object via {@code new BatchMailingListImportProcessor()} — mirroring the
 * {@code BatchPromulgationTypeImportProcessorTest} approach. No broker, no database, no Quarkus container is
 * involved.
 *
 * <p>These tests pin three things: (1) the value-object-to-entity field mapping the processor delegates to the
 * {@code MailingList(MailingListVo)} constructor (mailingListId, active, and the localized name/description),
 * (2) the null-safe coercion of the {@code Boolean active} flag, and (3) the documented unchecked cast — a
 * non-{@code MailingListVo} item surfaces as a {@link ClassCastException} rather than being silently skipped.
 *
 * <p>Users, contacts and triggers are deliberately left unset: copying them requires {@code UserVo}/
 * {@code ContactVo}/{@code MailingListTriggerVo} sub-objects, and the entity constructor only walks those lists
 * when they are non-null. The fields under test below copy purely, with no CDI or persistence context.
 */
public class BatchMailingListImportProcessorTest {

    private BatchMailingListImportProcessor processor;

    @BeforeEach
    public void setUp() {
        processor = new BatchMailingListImportProcessor();
    }

    /**
     * A {@link MailingListVo} item is converted into a {@link MailingList} entity that carries the value
     * object's {@code mailingListId} and {@code active} flag verbatim, plus a single localized description
     * whose {@code lang}, {@code name} and {@code description} are copied exactly.
     */
    @Test
    public void processItemWithMailingListVo_copiesIdActiveAndLocalizedDesc() throws Exception {
        MailingListVo vo = new MailingListVo();
        vo.setMailingListId("nw-subscribers");
        vo.setActive(true);

        MailingListDescVo desc = vo.createDesc("en");
        desc.setName("NW subscribers");
        desc.setDescription("Recipients of navigational warnings");

        Object result = processor.processItem(vo);

        assertTrue(result instanceof MailingList, "processItem must return a MailingList entity");

        MailingList entity = (MailingList) result;
        assertEquals("nw-subscribers", entity.getMailingListId(), "mailingListId must be copied from the VO");
        assertTrue(entity.isActive(), "active flag true must be carried over");

        assertEquals(1, entity.getDescs().size(), "exactly the one defined localized desc must be created");
        MailingListDesc copied = entity.getDescs().get(0);
        assertEquals("en", copied.getLang(), "desc lang must be copied");
        assertEquals("NW subscribers", copied.getName(), "desc name must be copied");
        assertEquals("Recipients of navigational warnings", copied.getDescription(),
                "desc description must be copied");
        assertEquals(entity, copied.getEntity(), "the created desc must back-reference the owning entity");
    }

    /**
     * The {@code MailingList(MailingListVo)} constructor coerces the nullable {@code Boolean active} flag with
     * {@code getActive() != null && getActive()}. A {@code null} active flag must therefore yield an inactive
     * entity rather than throwing a {@link NullPointerException} on unboxing.
     */
    @Test
    public void processItemWithNullActive_yieldsInactiveEntity() throws Exception {
        MailingListVo vo = new MailingListVo();
        vo.setMailingListId("no-active-flag");
        vo.setActive(null);

        MailingList entity = (MailingList) processor.processItem(vo);

        assertEquals("no-active-flag", entity.getMailingListId());
        assertFalse(entity.isActive(), "null active flag must coerce to inactive");
    }

    /**
     * Descriptions are filtered through {@link MailingListDescVo#descDefined()}: a desc with both name and
     * description blank is dropped, while a desc with only a name set is kept. The entity must end up with just
     * the meaningful description.
     */
    @Test
    public void processItemSkipsEmptyDescsAndKeepsPartiallyDefinedOnes() throws Exception {
        MailingListVo vo = new MailingListVo();
        vo.setMailingListId("filtered-descs");
        vo.setActive(false);

        // Empty desc: neither name nor description defined -> must be skipped.
        vo.createDesc("da");

        // Partially defined desc: only name set -> must be kept.
        MailingListDescVo en = vo.createDesc("en");
        en.setName("Name only");

        MailingList entity = (MailingList) processor.processItem(vo);

        assertEquals(1, entity.getDescs().size(),
                "only the defined desc must survive descDefined() filtering");
        MailingListDesc kept = entity.getDescs().get(0);
        assertEquals("en", kept.getLang());
        assertEquals("Name only", kept.getName());
        assertEquals(null, kept.getDescription(), "description left unset must remain null");
    }

    /**
     * Multiple defined descriptions are all carried into the entity with their fields intact.
     */
    @Test
    public void processItemWithMultipleDescs_copiesAll() throws Exception {
        MailingListVo vo = new MailingListVo();
        vo.setMailingListId("multi-lang");
        vo.setActive(true);

        MailingListDescVo en = new MailingListDescVo();
        en.setLang("en");
        en.setName("English name");
        en.setDescription("English description");

        MailingListDescVo da = new MailingListDescVo();
        da.setLang("da");
        da.setName("Dansk navn");
        da.setDescription("Dansk beskrivelse");

        vo.setDescs(Arrays.asList(en, da));

        MailingList entity = (MailingList) processor.processItem(vo);

        assertEquals(2, entity.getDescs().size(), "both defined descs must be created");

        MailingListDesc enDesc = entity.getDescs().stream()
                .filter(d -> "en".equals(d.getLang())).findFirst().orElse(null);
        MailingListDesc daDesc = entity.getDescs().stream()
                .filter(d -> "da".equals(d.getLang())).findFirst().orElse(null);

        assertEquals("English name", enDesc.getName());
        assertEquals("English description", enDesc.getDescription());
        assertEquals("Dansk navn", daDesc.getName());
        assertEquals("Dansk beskrivelse", daDesc.getDescription());
    }

    /**
     * The processor performs an unchecked cast to {@link MailingListVo}. Feeding it an item of an unrelated
     * type fails fast with a {@link ClassCastException} rather than producing a malformed entity.
     */
    @Test
    public void processItemWithWrongType_throwsClassCastException() throws Exception {
        try {
            processor.processItem("not a vo");
            fail("Expected ClassCastException when item is not a MailingListVo");
        } catch (ClassCastException expected) {
            // Expected: documents the unchecked (MailingListVo) cast in processItem.
        }
    }
}

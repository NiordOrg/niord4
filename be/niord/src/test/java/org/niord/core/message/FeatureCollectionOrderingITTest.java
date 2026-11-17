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
package org.niord.core.message;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.geojson.Feature;
import org.niord.core.geojson.FeatureCollection;
import org.niord.core.geojson.JtsConverter;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the {@code @OrderColumn(name = "indexNo")} ordered-collection behavior of
 * {@link FeatureCollection#getFeatures()} — directly in the firing line of the MySQL&nbsp;-&gt;&nbsp;
 * PostgreSQL migration.
 *
 * <p><b>Why this matters.</b> {@code Feature.indexNo} is a NOT-NULL column owned by the
 * {@code @OrderColumn}; Hibernate INSERTs the {@code Feature} row <em>without</em> it (the order is
 * written by a follow-up UPDATE). On MySQL this only works because the integration JDBC session runs
 * with {@code sql_mode=''} (see {@code ITTest}), letting the omitted NOT-NULL column default to 0.
 * PostgreSQL has no {@code sql_mode}; the migration must give the column a {@code DEFAULT 0} (via the
 * Liquibase changelog) or the INSERT fails. A wrong fix silently collapses multi-feature collections
 * (every feature persisted with {@code indexNo=0}, all but one lost on reload — the exact ORM-7
 * regression documented in {@code Feature.java}). This test makes that failure mode loud.
 *
 * <p>Migration-agnostic: it asserts only that a multi-feature collection round-trips with its count and
 * order intact, which must hold on MySQL today and PostgreSQL after. Runs in a rolled-back
 * {@link Transactional} transaction; the collection is reloaded after {@code flush()/clear()}.
 *
 * <p>This is a framework-coupled test (boots the application context for JPA persistence) — see
 * docs/framework-coupled-tests.md.
 */
class FeatureCollectionOrderingITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @PersistenceContext
    EntityManager em;

    private Message newDraftWithFeatures(String title, int featureCount) {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries("dma-nw"));
        msg.setType(Type.COASTAL_WARNING);
        msg.setAutoTitle(false);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        part.createDesc("en").setSubject("subject");
        msg.addPart(part);
        msg.createDesc("en").setTitle(title);

        FeatureCollection fc = new FeatureCollection();
        for (int i = 0; i < featureCount; i++) {
            Feature f = new Feature();
            // Distinct, monotonically increasing longitude marks each feature's insertion position so a
            // reordered or collapsed collection is detectable.
            f.setGeometry(JtsConverter.toJtsPoint(55.0, 10.0 + i * 0.1));
            fc.addFeature(f);
        }
        part.setGeometry(fc);
        return msg;
    }

    private Message create(Message draft) {
        try {
            return messageService.createMessage(draft);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Transactional
    void multiFeatureCollectionPreservesCountAndOrderOnReload() {
        Message created = create(newDraftWithFeatures("order-5", 5));
        Integer id = created.getId();

        em.flush();
        em.clear();

        Message reloaded = em.find(Message.class, id);
        List<Feature> features = reloaded.getParts().get(0).getGeometry().getFeatures();

        assertEquals(5, features.size(),
                "all 5 features must survive — a collapsed @OrderColumn collection would lose all but one");
        for (int i = 0; i < 5; i++) {
            double lon = features.get(i).getGeometry().getCoordinate().getX();
            assertEquals(10.0 + i * 0.1, lon, 1e-9,
                    "feature at index " + i + " must keep its insertion-order position (@OrderColumn indexNo)");
        }
    }

    @Test
    @Transactional
    void singleFeatureCollectionRoundTrips() {
        Message created = create(newDraftWithFeatures("order-1", 1));
        Integer id = created.getId();

        em.flush();
        em.clear();

        Message reloaded = em.find(Message.class, id);
        List<Feature> features = reloaded.getParts().get(0).getGeometry().getFeatures();
        assertEquals(1, features.size(), "a single-feature collection must round-trip");
        assertTrue(reloaded.getHasGeometry(), "hasGeometry must be true for a non-empty collection");
    }
}

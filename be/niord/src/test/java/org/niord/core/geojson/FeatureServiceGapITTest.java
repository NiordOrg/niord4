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
package org.niord.core.geojson;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link FeatureService} persistence behaviour, run against the seeded MySQL
 * container of the {@link ITTest} infrastructure.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the shared
 * seed database pristine and the tests independent. Test data uses an {@code IT-} property/value prefix
 * that does not occur in the seed data, so the assertions stay isolated from seeded geometry.
 *
 * <p>The pure-logic helpers {@code assignNewFeatureUids} and {@code copyFeatureCollection} are already
 * unit-tested in the {@code niord} module, so this class focuses on the persistence-bound operations
 * ({@code createFeatureCollection}, {@code updateFeatureCollection}, {@code findFeatureByUid},
 * {@code findFeatureCollectionByUid}) that need a live {@link EntityManager}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class FeatureServiceGapITTest extends ITTest {

    @Autowired
    FeatureService featureService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Test data factories
    // ------------------------------------------------------------------------------------------- //

    /** Builds a detached Feature with a point geometry and a single property. */
    private Feature newFeature(double lat, double lon, String propValue) {
        Feature f = new Feature();
        f.setGeometry(JtsConverter.toJtsPoint(lat, lon));
        f.getProperties().put("name", propValue);
        return f;
    }

    /** Builds a detached FeatureCollection wrapping the given features, wiring up the back-references. */
    private FeatureCollection newCollection(Feature... features) {
        FeatureCollection fc = new FeatureCollection();
        for (Feature f : features) {
            fc.addFeature(f);
        }
        return fc;
    }

    // ------------------------------------------------------------------------------------------- //
    // createFeatureCollection
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createFeatureCollectionPersistsCollectionAndFeaturesWithGeneratedUids() throws Exception {
        FeatureCollection created = featureService.createFeatureCollection(
                newCollection(newFeature(56.0, 10.0, "IT-A"), newFeature(57.0, 11.0, "IT-B")));

        Integer id = created.getId();
        String uid = created.getUid();
        assertNotNull(id, "createFeatureCollection must assign a generated row id");
        assertNotNull(uid, "createFeatureCollection must assign a fresh collection UID");
        assertEquals(2, created.getFeatures().size());

        em.flush();
        em.clear();

        FeatureCollection reloaded = em.find(FeatureCollection.class, id);
        assertNotNull(reloaded, "feature collection row must exist in the database");
        assertEquals(uid, reloaded.getUid());
        assertEquals(2, reloaded.getFeatures().size(), "both features must be cascade-persisted");
        // Persisted features must each have a non-null UID assigned by assignNewFeatureUids.
        reloaded.getFeatures().forEach(f -> assertNotNull(f.getUid(), "each feature must have a UID"));
        assertNotEquals(reloaded.getFeatures().get(0).getUid(), reloaded.getFeatures().get(1).getUid(),
                "features must get distinct UIDs");
    }

    @Test
    @Transactional
    void createFeatureCollectionNullsAnyCallerSuppliedCollectionUid() throws Exception {
        FeatureCollection template = newCollection(newFeature(55.0, 12.0, "IT-Uid"));
        template.setUid("IT-caller-supplied-uid");

        FeatureCollection created = featureService.createFeatureCollection(template);

        // createFeatureCollection sets uid to null then the @PrePersist callback assigns a fresh one,
        // so the caller-supplied UID must never survive.
        assertNotNull(created.getUid());
        assertNotEquals("IT-caller-supplied-uid", created.getUid(),
                "caller-supplied collection UID must be discarded and replaced");
    }

    @Test
    @Transactional
    void createFeatureCollectionRejectsNullCollection() {
        assertThrows(NullPointerException.class, () -> featureService.createFeatureCollection(null));
    }

    @Test
    @Transactional
    void createFeatureCollectionFeaturesAreRetrievableByUid() throws Exception {
        FeatureCollection created = featureService.createFeatureCollection(
                newCollection(newFeature(56.5, 10.5, "IT-Find")));
        em.flush();

        String collUid = created.getUid();
        String featUid = created.getFeatures().get(0).getUid();
        em.clear();

        FeatureCollection foundColl = featureService.findFeatureCollectionByUid(collUid);
        assertNotNull(foundColl);
        assertEquals(1, foundColl.getFeatures().size());

        Feature foundFeat = featureService.findFeatureByUid(featUid);
        assertNotNull(foundFeat);
        assertEquals("IT-Find", foundFeat.getProperties().get("name"));
    }

    // ------------------------------------------------------------------------------------------- //
    // updateFeatureCollection - null / empty guards
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateFeatureCollectionReturnsNullForNullCollection() {
        assertNull(featureService.updateFeatureCollection(null));
    }

    @Test
    @Transactional
    void updateFeatureCollectionReturnsNullAndWritesNothingForFeaturelessCollection() {
        long before = countFeatureCollections();

        FeatureCollection empty = new FeatureCollection();
        assertTrue(empty.getFeatures().isEmpty());

        assertNull(featureService.updateFeatureCollection(empty),
                "a feature collection without features must yield null");

        em.flush();
        assertEquals(before, countFeatureCollections(),
                "no feature collection must be written when there are no features");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateFeatureCollection - new (unpersisted) collection
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateFeatureCollectionAssignsUidAndReturnsSameInstanceForBrandNewCollection() {
        FeatureCollection fresh = newCollection(newFeature(54.0, 13.0, "IT-New"));
        assertNull(fresh.getUid());

        FeatureCollection result = featureService.updateFeatureCollection(fresh);

        // The collection's UID is blank, so no existing collection matches: the same (new) instance
        // is returned, now carrying a freshly assigned UID.
        assertNotNull(result);
        assertEquals(fresh.getUid(), result.getUid());
        assertNotNull(result.getUid(), "a blank-UID collection must be assigned a fresh UID");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateFeatureCollection - existing collection merge
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateFeatureCollectionMergesMatchingFeatureAndAttachesNewFeature() throws Exception {
        // Persist a collection with a single feature.
        FeatureCollection created = featureService.createFeatureCollection(
                newCollection(newFeature(56.0, 10.0, "IT-Orig")));
        em.flush();

        Integer collId = created.getId();
        String collUid = created.getUid();
        String existingFeatUid = created.getFeatures().get(0).getUid();
        em.clear();

        // Build an update template referencing the same collection UID. It contains:
        //  - one feature with the existing UID but a NEW geometry + properties (a merge)
        //  - one brand-new feature with no UID (an attach)
        FeatureCollection template = new FeatureCollection();
        template.setUid(collUid);

        Feature mergeFeature = new Feature();
        mergeFeature.setUid(existingFeatUid);
        mergeFeature.setGeometry(JtsConverter.toJtsPoint(58.0, 14.0)); // changed geometry
        mergeFeature.getProperties().put("name", "IT-Merged");        // changed property
        template.getFeatures().add(mergeFeature);

        Feature attachFeature = new Feature();
        attachFeature.setGeometry(JtsConverter.toJtsPoint(59.0, 15.0));
        attachFeature.getProperties().put("name", "IT-Attached");
        template.getFeatures().add(attachFeature);

        FeatureCollection updated = featureService.updateFeatureCollection(template);
        assertNotNull(updated, "update of an existing collection must return the managed entity");
        assertEquals(collId, updated.getId(), "the original persisted collection must be reused");
        assertEquals(2, updated.getFeatures().size());

        em.flush();
        em.clear();

        FeatureCollection reloaded = em.find(FeatureCollection.class, collId);
        assertEquals(2, reloaded.getFeatures().size(),
                "the merged feature plus the new feature must both be persisted");

        // The merged feature keeps its UID but adopts the new geometry + properties.
        Feature mergedReloaded = featureService.findFeatureByUid(existingFeatUid);
        assertNotNull(mergedReloaded, "the merged feature keeps its original UID");
        assertEquals("IT-Merged", mergedReloaded.getProperties().get("name"),
                "the matching feature's properties must be replaced with the template's");
        assertEquals(58.0, mergedReloaded.getGeometry().getCoordinate().y, 1e-9);
        assertEquals(14.0, mergedReloaded.getGeometry().getCoordinate().x, 1e-9);

        // The attached feature is present with its own (PrePersist-assigned) UID and value.
        List<String> names = reloaded.getFeatures().stream()
                .map(f -> (String) f.getProperties().get("name"))
                .collect(java.util.stream.Collectors.toList());
        assertTrue(names.contains("IT-Merged"), "merged feature must be present");
        assertTrue(names.contains("IT-Attached"), "attached feature must be present");
    }

    @Test
    @Transactional
    void updateFeatureCollectionDropsOmittedFeatures() throws Exception {
        // Persist a collection with two features.
        FeatureCollection created = featureService.createFeatureCollection(
                newCollection(newFeature(56.0, 10.0, "IT-Keep"), newFeature(57.0, 11.0, "IT-Drop")));
        em.flush();

        Integer collId = created.getId();
        String collUid = created.getUid();
        String keepUid = created.getFeatures().get(0).getUid();
        String dropUid = created.getFeatures().get(1).getUid();
        em.clear();

        // Update referencing only the "keep" feature: the other is omitted and must be orphan-removed.
        FeatureCollection template = new FeatureCollection();
        template.setUid(collUid);
        Feature keep = new Feature();
        keep.setUid(keepUid);
        keep.setGeometry(JtsConverter.toJtsPoint(56.0, 10.0));
        keep.getProperties().put("name", "IT-Keep");
        template.getFeatures().add(keep);

        featureService.updateFeatureCollection(template);
        em.flush();
        em.clear();

        FeatureCollection reloaded = em.find(FeatureCollection.class, collId);
        assertEquals(1, reloaded.getFeatures().size(), "omitted feature must be removed from the collection");
        assertNotNull(featureService.findFeatureByUid(keepUid), "retained feature must still exist");
        assertNull(featureService.findFeatureByUid(dropUid),
                "the omitted feature must be orphan-removed from the database");
    }

    // ------------------------------------------------------------------------------------------- //
    // findFeatureByUid / findFeatureCollectionByUid
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findFeatureByUidReturnsEntityForKnownUidAndNullForUnknown() throws Exception {
        FeatureCollection created = featureService.createFeatureCollection(
                newCollection(newFeature(56.0, 10.0, "IT-Lookup")));
        em.flush();
        String featUid = created.getFeatures().get(0).getUid();
        em.clear();

        Feature found = featureService.findFeatureByUid(featUid);
        assertNotNull(found);
        assertEquals(featUid, found.getUid());
        assertEquals("IT-Lookup", found.getProperties().get("name"));

        assertNull(featureService.findFeatureByUid("IT-unknown-feature-uid"),
                "an unknown feature UID must resolve to null");
    }

    @Test
    @Transactional
    void findFeatureCollectionByUidReturnsEntityForKnownUidAndNullForUnknown() throws Exception {
        FeatureCollection created = featureService.createFeatureCollection(
                newCollection(newFeature(56.0, 10.0, "IT-CollLookup")));
        em.flush();
        String collUid = created.getUid();
        em.clear();

        FeatureCollection found = featureService.findFeatureCollectionByUid(collUid);
        assertNotNull(found);
        assertEquals(collUid, found.getUid());
        assertEquals(1, found.getFeatures().size());

        assertNull(featureService.findFeatureCollectionByUid("IT-unknown-collection-uid"),
                "an unknown collection UID must resolve to null");
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    private long countFeatureCollections() {
        return em.createQuery("select count(fc) from FeatureCollection fc", Long.class)
                .getSingleResult();
    }
}

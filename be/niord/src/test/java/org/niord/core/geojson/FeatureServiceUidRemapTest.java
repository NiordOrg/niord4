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

import org.junit.jupiter.api.Test;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.FeatureVo;
import org.niord.model.geojson.PointVo;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic unit tests for {@link FeatureService#assignNewFeatureUids(FeatureCollection)} and
 * {@link FeatureService#copyFeatureCollection(FeatureCollectionVo)}.
 * <p>
 * These two methods do not touch the {@code EntityManager}, so they can be exercised with a bare
 * {@code new FeatureService()}. They are the heart of "copy a feature collection" and must (a) give
 * every feature a fresh UID and (b) keep the {@code parentFeatureIds} cross-references pointing at the
 * <em>new</em> UIDs of the copied parents while leaving unknown tokens untouched. The persist/find
 * code paths require a database and are deferred to an integration test.
 */
public class FeatureServiceUidRemapTest {

    /** Builds a Feature with the given UID and an arbitrary (valid) point geometry. */
    private Feature feature(String uid) {
        Feature f = new Feature();
        f.setUid(uid);
        f.setGeometry(JtsConverter.toJts(new PointVo(new double[]{10, 56})));
        return f;
    }

    /** Builds a FeatureCollection entity from the supplied features (via addFeature). */
    private FeatureCollection collection(Feature... features) {
        FeatureCollection fc = new FeatureCollection();
        for (Feature f : features) {
            fc.addFeature(f);
        }
        return fc;
    }

    @Test
    public void assignNewFeatureUidsReplacesEveryUidWithDistinctNonNullValue() {
        FeatureService service = new FeatureService();

        Feature a = feature("old-a");
        Feature b = feature("old-b");
        FeatureCollection fc = collection(a, b);

        service.assignNewFeatureUids(fc);

        // Every feature has a fresh, non-null UID, different from the original.
        assertNotNull(a.getUid());
        assertNotNull(b.getUid());
        assertNotEquals("old-a", a.getUid());
        assertNotEquals("old-b", b.getUid());

        // The two new UIDs are distinct from each other.
        assertNotEquals(a.getUid(), b.getUid());
    }

    @Test
    public void assignNewFeatureUidsRemapsChildParentLinkToNewParentUid() {
        FeatureService service = new FeatureService();

        Feature parent = feature("parent-old");
        Feature child = feature("child-old");
        child.getProperties().put("parentFeatureIds", "parent-old");
        FeatureCollection fc = collection(parent, child);

        service.assignNewFeatureUids(fc);

        String newParentUid = parent.getUid();
        assertNotEquals("parent-old", newParentUid);

        // The child's parentFeatureIds was rewritten from the parent's OLD uid to its NEW uid.
        assertEquals(newParentUid, child.getProperties().get("parentFeatureIds"));
    }

    @Test
    public void assignNewFeatureUidsPreservesUnknownParentToken() {
        FeatureService service = new FeatureService();

        Feature parent = feature("parent-old");
        Feature child = feature("child-old");
        // "ghost-id" refers to no feature in this collection and must survive verbatim,
        // while "parent-old" must be remapped to the parent's new UID.
        child.getProperties().put("parentFeatureIds", "parent-old,ghost-id");
        FeatureCollection fc = collection(parent, child);

        service.assignNewFeatureUids(fc);

        String newParentUid = parent.getUid();
        String result = (String) child.getProperties().get("parentFeatureIds");

        // Order is preserved: remapped parent first, unknown token unchanged.
        assertEquals(newParentUid + ",ghost-id", result);

        List<String> tokens = Arrays.stream(result.split(","))
                .collect(Collectors.toList());
        assertEquals(2, tokens.size());
        assertEquals(newParentUid, tokens.get(0));
        assertEquals("ghost-id", tokens.get(1));
    }

    @Test
    public void copyFeatureCollectionReturnsNullForNullInput() {
        FeatureService service = new FeatureService();
        assertNull(service.copyFeatureCollection(null));
    }

    @Test
    public void copyFeatureCollectionDeepCopiesUidsAndRemapsParentLink() {
        FeatureService service = new FeatureService();

        // Build a source VO: a parent feature and a child feature whose parentFeatureIds
        // references the parent's UID.
        FeatureVo parentVo = new FeatureVo();
        parentVo.setId("src-parent");
        parentVo.setGeometry(new PointVo(new double[]{10, 56}));

        FeatureVo childVo = new FeatureVo();
        childVo.setId("src-child");
        childVo.setGeometry(new PointVo(new double[]{11, 57}));
        childVo.getProperties().put("parentFeatureIds", "src-parent");

        FeatureCollectionVo source = new FeatureCollectionVo();
        source.setId("src-collection");
        source.setFeatures(new FeatureVo[]{parentVo, childVo});

        FeatureCollectionVo copy = service.copyFeatureCollection(source);

        assertNotNull(copy);

        // The copied collection has a new UID (id), distinct from the source.
        assertNotNull(copy.getId());
        assertNotEquals("src-collection", copy.getId());

        FeatureVo[] copied = copy.getFeatures();
        assertEquals(2, copied.length);

        FeatureVo copiedParent = copied[0];
        FeatureVo copiedChild = copied[1];

        // Every feature UID differs from the source UIDs.
        assertNotNull(copiedParent.getId());
        assertNotNull(copiedChild.getId());
        assertNotEquals("src-parent", copiedParent.getId());
        assertNotEquals("src-child", copiedChild.getId());
        assertNotEquals(copiedParent.getId(), copiedChild.getId());

        // The deep copy did not mutate the source VOs.
        assertEquals("src-parent", parentVo.getId());
        assertEquals("src-child", childVo.getId());
        assertEquals("src-parent", childVo.getProperties().get("parentFeatureIds"));

        // The child's parent link in the copy points at the COPIED parent's new UID.
        assertEquals(copiedParent.getId().toString(),
                copiedChild.getProperties().get("parentFeatureIds"));
        // ...and not at the original source parent UID.
        assertNotEquals("src-parent", copiedChild.getProperties().get("parentFeatureIds"));
    }

    @Test
    public void copyFeatureCollectionAssignsNewUidEvenWhenSourceFeatureHasNoId() {
        FeatureService service = new FeatureService();

        // A source feature without an id: assignNewFeatureUids must still give it a UID,
        // and that UID must appear on the copied VO.
        FeatureVo featureVo = new FeatureVo();
        featureVo.setGeometry(new PointVo(new double[]{12, 55}));

        FeatureCollectionVo source = new FeatureCollectionVo();
        source.setFeatures(new FeatureVo[]{featureVo});

        FeatureCollectionVo copy = service.copyFeatureCollection(source);

        assertNotNull(copy);
        assertEquals(1, copy.getFeatures().length);

        Object copiedId = copy.getFeatures()[0].getId();
        assertNotNull(copiedId);
        assertFalse(copiedId.toString().isEmpty());

        // The source feature was untouched (still no id).
        assertNull(featureVo.getId());
        // The collection itself got a fresh UID too.
        assertNotNull(copy.getId());
    }
}

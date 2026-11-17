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
package org.niord.core.aton;

import org.junit.jupiter.api.Test;
import org.niord.core.aton.vo.AtonNodeVo;
import org.niord.core.aton.vo.AtonTagVo;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for the {@link AtonNode} entity logic.
 *
 * These tests exercise only the in-memory entity behaviour (VO conversion, tag
 * lookup/update/removal, change detection and merge). They deliberately do NOT
 * trigger persistence: {@code onCreate()} (the {@code @PrePersist}/{@code @PreUpdate}
 * callback) is never invoked, so {@code geometry} is never derived and no
 * EntityManager is required. This keeps the suite framework-neutral and fast.
 */
public class AtonNodeEntityTest {

    /*************************/
    /** Helper methods       */
    /*************************/

    /** Builds an AtonNodeVo with the given coordinates and tag key/value pairs. */
    private static AtonNodeVo vo(Integer id, double lat, double lon, String... kv) {
        AtonNodeVo node = new AtonNodeVo();
        node.setId(id);
        node.setLat(lat);
        node.setLon(lon);
        node.setUser("malcolmh");
        node.setUid(128186);
        node.setVisible(true);
        node.setVersion(11);
        node.setChangeset(9107813);
        node.setTimestamp(new Date(1314134556000L));
        node.setTags(tags(kv));
        return node;
    }

    /** Builds an AtonTagVo[] from alternating key/value strings. */
    private static AtonTagVo[] tags(String... kv) {
        assertEquals(0, kv.length % 2, "kv pairs must be even");
        AtonTagVo[] result = new AtonTagVo[kv.length / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = new AtonTagVo(kv[i * 2], kv[i * 2 + 1]);
        }
        return result;
    }

    /** Builds a bare AtonNode (no VO) with coordinates, visibility and tags. */
    private static AtonNode node(double lat, double lon, boolean visible, String... kv) {
        AtonNode n = new AtonNode();
        n.setLat(lat);
        n.setLon(lon);
        n.setVisible(visible);
        for (int i = 0; i < kv.length / 2; i++) {
            n.updateTag(kv[i * 2], kv[i * 2 + 1]);
        }
        return n;
    }

    /*************************/
    /** Constructor from VO  */
    /*************************/

    @Test
    public void constructorCopiesScalarFields() {
        AtonNodeVo source = vo(672436827, 50.8070813, -1.2841124);
        AtonNode node = new AtonNode(source);

        assertEquals(Integer.valueOf(672436827), node.getId());
        assertEquals(50.8070813, node.getLat(), 0.0);
        assertEquals(-1.2841124, node.getLon(), 0.0);
        assertEquals("malcolmh", node.getUser());
        assertEquals(128186, node.getUid());
        assertTrue(node.isVisible());
        assertEquals(11, node.getVersion());
        assertEquals(9107813, node.getChangeset());
        assertEquals(new Date(1314134556000L), node.getTimestamp());
    }

    @Test
    public void constructorMapsTagsAndBackReference() {
        AtonNodeVo source = vo(1, 10.0, 20.0,
                "seamark:type", "buoy_cardinal",
                "seamark:name", "Calshot");
        AtonNode node = new AtonNode(source);

        assertEquals(2, node.getTags().size());

        AtonTag typeTag = node.getTag("seamark:type");
        assertNotNull(typeTag);
        assertEquals("seamark:type", typeTag.getK());
        assertEquals("buoy_cardinal", typeTag.getV());

        // Each created AtonTag must point back at the owning node
        for (AtonTag t : node.getTags()) {
            assertSame(node, t.getAtonNode());
        }
    }

    @Test
    public void constructorWithNullTagsArrayLeavesTagsEmpty() {
        AtonNodeVo source = vo(5, 1.0, 2.0);
        source.setTags(null);
        AtonNode node = new AtonNode(source);

        // The default list is retained (not replaced) and stays empty
        assertNotNull(node.getTags());
        assertTrue(node.getTags().isEmpty());
    }

    @Test
    public void constructorWithNullVoThrows() {
        assertThrows(NullPointerException.class, () -> new AtonNode((AtonNodeVo) null));
    }

    /*************************/
    /** toVo round-trip      */
    /*************************/

    @Test
    public void toVoRoundTripsScalarFields() {
        AtonNode node = new AtonNode(vo(672436827, 50.8070813, -1.2841124,
                "seamark:type", "buoy_cardinal"));

        AtonNodeVo out = node.toVo();

        assertEquals(Integer.valueOf(672436827), out.getId());
        assertEquals(50.8070813, out.getLat(), 0.0);
        assertEquals(-1.2841124, out.getLon(), 0.0);
        assertEquals("malcolmh", out.getUser());
        assertEquals(128186, out.getUid());
        assertTrue(out.isVisible());
        assertEquals(11, out.getVersion());
        assertEquals(9107813, out.getChangeset());
        assertEquals(new Date(1314134556000L), out.getTimestamp());
    }

    @Test
    public void toVoRoundTripsTags() {
        AtonNode node = new AtonNode(vo(1, 0.0, 0.0,
                "seamark:type", "buoy_cardinal",
                "seamark:name", "Calshot",
                "seamark:light:colour", "white"));

        AtonNodeVo out = node.toVo();

        assertEquals(3, out.getTags().length);

        // locate the name tag in the produced VO array
        AtonTagVo nameTag = null;
        for (AtonTagVo t : out.getTags()) {
            if ("seamark:name".equals(t.getK())) {
                nameTag = t;
            }
        }
        assertNotNull(nameTag);
        assertEquals("Calshot", nameTag.getV());
    }

    /*************************/
    /** getTag / getTagValue */
    /*************************/

    @Test
    public void getTagReturnsMatchingTag() {
        AtonNode node = node(0, 0, true, "seamark:type", "light_minor");
        AtonTag tag = node.getTag("seamark:type");

        assertNotNull(tag);
        assertEquals("light_minor", tag.getV());
    }

    @Test
    public void getTagReturnsNullForAbsentKey() {
        AtonNode node = node(0, 0, true, "seamark:type", "light_minor");
        assertNull(node.getTag("seamark:name"));
    }

    @Test
    public void getTagReturnsNullForBlankKey() {
        AtonNode node = node(0, 0, true, "seamark:type", "light_minor");
        assertNull(node.getTag(""));
        assertNull(node.getTag("   "));
    }

    @Test
    public void getTagReturnsNullForNullKey() {
        AtonNode node = node(0, 0, true, "seamark:type", "light_minor");
        assertNull(node.getTag(null));
    }

    @Test
    public void getTagValueReturnsValueOrNull() {
        AtonNode node = node(0, 0, true, "seamark:type", "light_minor");
        assertEquals("light_minor", node.getTagValue("seamark:type"));
        assertNull(node.getTagValue("seamark:name"));
    }

    /*************************/
    /** getAtonUid           */
    /*************************/

    @Test
    public void getAtonUidReturnsSeamarkRefValue() {
        // AtonTag.TAG_ATON_UID == "seamark:ref"
        AtonNode node = node(0, 0, true, AtonTag.TAG_ATON_UID, "aton-42");
        assertEquals("seamark:ref", AtonTag.TAG_ATON_UID);
        assertEquals("aton-42", node.getAtonUid());
    }

    @Test
    public void getAtonUidReturnsNullWhenAbsent() {
        AtonNode node = node(0, 0, true, "seamark:type", "light_minor");
        assertNull(node.getAtonUid());
    }

    /*************************/
    /** matchingTags         */
    /*************************/

    @Test
    public void matchingTagsReturnsKeysMatchingFullRegex() {
        AtonNode node = node(0, 0, true,
                "seamark:light:colour", "white",
                "seamark:light:character", "Fl",
                "seamark:type", "light_minor");

        List<AtonTag> matched = node.matchingTags("seamark:light.*");
        assertEquals(2, matched.size());
        for (AtonTag t : matched) {
            assertTrue(t.getK().startsWith("seamark:light"));
        }
    }

    @Test
    public void matchingTagsRequiresFullMatchNotSubstring() {
        AtonNode node = node(0, 0, true,
                "seamark:light:colour", "white",
                "seamark:type", "light_minor");

        // String.matches anchors the whole string: "light" alone matches nothing
        assertTrue(node.matchingTags("light").isEmpty());
        // but a full pattern does
        assertEquals(1, node.matchingTags("seamark:light:colour").size());
    }

    @Test
    public void matchingTagsReturnsEmptyWhenNoMatch() {
        AtonNode node = node(0, 0, true, "seamark:type", "light_minor");
        assertTrue(node.matchingTags("seamark:buoy.*").isEmpty());
    }

    /*************************/
    /** updateTag            */
    /*************************/

    @Test
    public void updateTagAddsNewTagWhenAbsent() {
        AtonNode node = node(0, 0, true);
        assertEquals(0, node.getTags().size());

        AtonTag added = node.updateTag("seamark:type", "buoy_cardinal");

        assertNotNull(added);
        assertEquals("seamark:type", added.getK());
        assertEquals("buoy_cardinal", added.getV());
        assertSame(node, added.getAtonNode());
        assertEquals(1, node.getTags().size());
        assertSame(added, node.getTag("seamark:type"));
    }

    @Test
    public void updateTagUpdatesValueWhenKeyExistsWithoutAddingTag() {
        AtonNode node = node(0, 0, true, "seamark:type", "buoy_cardinal");
        AtonTag existing = node.getTag("seamark:type");

        AtonTag returned = node.updateTag("seamark:type", "buoy_lateral");

        // same instance updated in place; no new tag added
        assertSame(existing, returned);
        assertEquals("buoy_lateral", returned.getV());
        assertEquals(1, node.getTags().size());
    }

    @Test
    public void updateTagWithBlankValueReturnsNullAndAddsNothing() {
        AtonNode node = node(0, 0, true);

        assertNull(node.updateTag("seamark:type", ""));
        assertNull(node.updateTag("seamark:type", "   "));
        assertEquals(0, node.getTags().size());
    }

    @Test
    public void updateTagWithNullValueReturnsNullAndAddsNothing() {
        AtonNode node = node(0, 0, true);

        assertNull(node.updateTag("seamark:type", null));
        assertEquals(0, node.getTags().size());
    }

    @Test
    public void updateTagWithBlankValueDoesNotMutateExistingTag() {
        AtonNode node = node(0, 0, true, "seamark:type", "buoy_cardinal");

        // blank value is rejected before the existing tag is touched
        assertNull(node.updateTag("seamark:type", null));
        assertEquals("buoy_cardinal", node.getTagValue("seamark:type"));
        assertEquals(1, node.getTags().size());
    }

    /*************************/
    /** removeTags           */
    /*************************/

    @Test
    public void removeTagsRemovesMatchingAndReturnsTrue() {
        AtonNode node = node(0, 0, true,
                "seamark:light:colour", "white",
                "seamark:light:character", "Fl",
                "seamark:type", "light_minor");

        boolean removed = node.removeTags("seamark:light.*");

        assertTrue(removed);
        assertEquals(1, node.getTags().size());
        assertEquals("seamark:type", node.getTags().get(0).getK());
    }

    @Test
    public void removeTagsReturnsFalseWhenNothingMatches() {
        AtonNode node = node(0, 0, true, "seamark:type", "light_minor");

        boolean removed = node.removeTags("seamark:buoy.*");

        assertFalse(removed);
        assertEquals(1, node.getTags().size());
    }

    /*************************/
    /** hasChanged           */
    /*************************/

    @Test
    public void hasChangedReturnsTrueForNullTemplate() {
        AtonNode node = node(10.0, 20.0, true);
        assertTrue(node.hasChanged(null));
    }

    @Test
    public void hasChangedReturnsTrueWhenLatDiffersBeyondThreshold() {
        AtonNode node = node(10.0, 20.0, true);
        AtonNode template = node(10.0001, 20.0, true); // diff 0.0001 > 0.00001
        assertTrue(node.hasChanged(template));
    }

    @Test
    public void hasChangedReturnsFalseWhenLatDiffersWithinThreshold() {
        AtonNode node = node(10.0, 20.0, true);
        AtonNode template = node(10.000001, 20.0, true); // diff 0.000001 < 0.00001
        assertFalse(node.hasChanged(template));
    }

    @Test
    public void hasChangedReturnsTrueWhenLonDiffersBeyondThreshold() {
        AtonNode node = node(10.0, 20.0, true);
        AtonNode template = node(10.0, 20.0001, true);
        assertTrue(node.hasChanged(template));
    }

    @Test
    public void hasChangedReturnsTrueWhenVisibilityDiffers() {
        AtonNode node = node(10.0, 20.0, true);
        AtonNode template = node(10.0, 20.0, false);
        assertTrue(node.hasChanged(template));
    }

    @Test
    public void hasChangedReturnsTrueWhenTemplateTagValueDiffers() {
        AtonNode node = node(10.0, 20.0, true, "seamark:type", "buoy_cardinal");
        AtonNode template = node(10.0, 20.0, true, "seamark:type", "buoy_lateral");
        assertTrue(node.hasChanged(template));
    }

    @Test
    public void hasChangedReturnsTrueWhenTemplateTagMissingOnNode() {
        AtonNode node = node(10.0, 20.0, true); // no tags
        AtonNode template = node(10.0, 20.0, true, "seamark:type", "buoy_cardinal");
        // node has no value for the template's key -> getTagValue == null != "buoy_cardinal"
        assertTrue(node.hasChanged(template));
    }

    @Test
    public void hasChangedReturnsFalseWhenPositionVisibilityAndTemplateTagsMatch() {
        AtonNode node = node(10.0, 20.0, true, "seamark:type", "buoy_cardinal");
        AtonNode template = node(10.0, 20.0, true, "seamark:type", "buoy_cardinal");
        assertFalse(node.hasChanged(template));
    }

    @Test
    public void hasChangedIgnoresExtraTagsPresentOnlyOnNode() {
        // hasChanged only inspects the TEMPLATE's tags; an extra tag on the node
        // that is not on the template must not count as a change
        AtonNode node = node(10.0, 20.0, true,
                "seamark:type", "buoy_cardinal",
                "seamark:name", "Calshot");
        AtonNode template = node(10.0, 20.0, true, "seamark:type", "buoy_cardinal");
        assertFalse(node.hasChanged(template));
    }

    /*************************/
    /** updateNode           */
    /*************************/

    @Test
    public void updateNodeCopiesScalarFields() {
        AtonNode node = node(10.0, 20.0, true);
        node.setUser("old");
        node.setUid(1);
        node.setVersion(1);
        node.setChangeset(1);

        AtonNode template = node(30.0, 40.0, false);
        template.setUser("new");
        template.setUid(99);
        template.setVersion(7);
        template.setChangeset(123);
        Date ts = new Date(1314134556000L);
        template.setTimestamp(ts);

        node.updateNode(template);

        assertEquals(30.0, node.getLat(), 0.0);
        assertEquals(40.0, node.getLon(), 0.0);
        assertEquals("new", node.getUser());
        assertEquals(99, node.getUid());
        assertFalse(node.isVisible());
        assertEquals(7, node.getVersion());
        assertEquals(123, node.getChangeset());
        assertEquals(ts, node.getTimestamp());
    }

    @Test
    public void updateNodeMergesTemplateTagsAndLeavesExtraNodeTagsUnchanged() {
        AtonNode node = node(0, 0, true,
                "seamark:type", "buoy_cardinal",   // will be overwritten by template
                "seamark:name", "Calshot");        // not in template -> left unchanged
        AtonNode template = node(0, 0, true,
                "seamark:type", "buoy_lateral",    // overwrites
                "seamark:light:colour", "white");  // new tag created

        node.updateNode(template);

        // overwritten
        assertEquals("buoy_lateral", node.getTagValue("seamark:type"));
        // created from template
        assertEquals("white", node.getTagValue("seamark:light:colour"));
        // a node-only tag is preserved (not removed) since updateNode only merges
        assertEquals("Calshot", node.getTagValue("seamark:name"));
        assertEquals(3, node.getTags().size());
    }
}

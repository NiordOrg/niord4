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

package org.niord.core.promulgation;

import org.junit.jupiter.api.Test;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.promulgation.vo.TwitterMessagePromulgationVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral Tier-1 unit tests for {@link TwitterMessagePromulgation} (entity)
 * and {@link TwitterMessagePromulgationVo} (value object).
 *
 * Pure POJO construction only: no Quarkus, no CDI, no database. Verifies the VO->entity
 * constructor, entity->VO conversion, the polymorphic update() contract, the
 * promulgationDataDefined() blank-tweet rule, reset() and toEntity().
 */
public class TwitterMessagePromulgationTest {

    /** Builds a PromulgationTypeVo carrying just a typeId. */
    private static PromulgationTypeVo typeVo(String typeId) {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setTypeId(typeId);
        return vo;
    }

    /*************************/
    /**  Entity tests       **/
    /*************************/

    @Test
    public void constructorFromVoCopiesTweetTypeIdAndPromulgateFlag() {
        TwitterMessagePromulgationVo vo = new TwitterMessagePromulgationVo();
        vo.setTweet("hi there");
        vo.setType(typeVo("tw"));
        vo.setPromulgate(true);

        TwitterMessagePromulgation entity = new TwitterMessagePromulgation(vo);

        assertEquals("hi there", entity.getTweet(), "tweet should be copied from the VO");
        assertEquals("tw", entity.getType().getTypeId(), "type id should be copied through the base constructor");
        assertTrue(entity.isPromulgate(), "promulgate flag should be copied from the VO");
    }

    @Test
    public void toVoCopiesTweetTypeIdAndPromulgateFlag() {
        TwitterMessagePromulgation entity = new TwitterMessagePromulgation();
        entity.setTweet("bye");
        entity.setType(new PromulgationType(typeVo("tw")));
        entity.setPromulgate(false);

        TwitterMessagePromulgationVo vo = entity.toVo();

        assertEquals("bye", vo.getTweet(), "tweet should be copied into the VO");
        assertEquals("tw", vo.getType().getTypeId(), "type id should be copied into the VO");
        assertFalse(vo.isPromulgate(), "promulgate flag should be copied into the VO");
    }

    @Test
    public void updateFromNonTwitterPromulgationIsNoOp() {
        TwitterMessagePromulgation entity = new TwitterMessagePromulgation();
        entity.setTweet("original tweet");
        entity.setType(new PromulgationType(typeVo("tw")));
        entity.setPromulgate(true);

        // A non-Twitter promulgation must not affect the Twitter entity at all.
        entity.update(new NavtexMessagePromulgation());

        assertEquals("original tweet", entity.getTweet(),
                "tweet must be unchanged after update from a non-Twitter promulgation");
        assertEquals("tw", entity.getType().getTypeId(),
                "type must be unchanged after update from a non-Twitter promulgation");
        assertTrue(entity.isPromulgate(),
                "promulgate flag must be unchanged after update from a non-Twitter promulgation");
    }

    @Test
    public void updateFromAnotherTwitterPromulgationCopiesTweetTypeAndPromulgateFlag() {
        TwitterMessagePromulgation target = new TwitterMessagePromulgation();
        target.setTweet("stale tweet");
        target.setType(new PromulgationType(typeVo("old")));
        target.setPromulgate(false);

        TwitterMessagePromulgation source = new TwitterMessagePromulgation();
        source.setTweet("fresh tweet");
        PromulgationType sourceType = new PromulgationType(typeVo("tw"));
        source.setType(sourceType);
        source.setPromulgate(true);

        target.update(source);

        assertEquals("fresh tweet", target.getTweet(), "tweet should be copied from the source promulgation");
        assertSame(sourceType, target.getType(), "base update should copy the source type reference");
        assertEquals("tw", target.getType().getTypeId(), "type id should now reflect the source type");
        assertTrue(target.isPromulgate(), "promulgate flag should be copied from the source promulgation");
    }

    /*************************/
    /**  VO tests           **/
    /*************************/

    @Test
    public void promulgationDataDefinedTrueWhenTweetHasContent() {
        TwitterMessagePromulgationVo vo = new TwitterMessagePromulgationVo();
        vo.setTweet("x");

        assertTrue(vo.promulgationDataDefined(), "a non-blank tweet means promulgation data is defined");
    }

    @Test
    public void promulgationDataDefinedFalseWhenTweetIsNull() {
        TwitterMessagePromulgationVo vo = new TwitterMessagePromulgationVo();
        vo.setTweet(null);

        assertFalse(vo.promulgationDataDefined(), "a null tweet means promulgation data is not defined");
    }

    @Test
    public void promulgationDataDefinedFalseWhenTweetIsBlank() {
        TwitterMessagePromulgationVo vo = new TwitterMessagePromulgationVo();
        vo.setTweet("  ");

        assertFalse(vo.promulgationDataDefined(),
                "a whitespace-only tweet means promulgation data is not defined");
    }

    @Test
    public void resetClearsTweetAndReturnsSameInstance() {
        TwitterMessagePromulgationVo vo = new TwitterMessagePromulgationVo();
        vo.setTweet("x");

        TwitterMessagePromulgationVo returned = vo.reset();

        assertNull(vo.getTweet(), "reset() should clear the tweet");
        assertSame(vo, returned, "reset() should return the same instance for fluent chaining");
    }

    @Test
    public void toEntityReturnsTwitterMessagePromulgationInstance() {
        TwitterMessagePromulgationVo vo = new TwitterMessagePromulgationVo();
        vo.setTweet("hello");
        vo.setType(typeVo("tw"));

        TwitterMessagePromulgation entity = vo.toEntity();

        assertEquals(TwitterMessagePromulgation.class, entity.getClass(),
                "toEntity() should produce a TwitterMessagePromulgation");
        assertEquals("hello", entity.getTweet(), "the produced entity should carry the VO's tweet");
    }
}

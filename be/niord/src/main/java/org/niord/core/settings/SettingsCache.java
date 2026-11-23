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
package org.niord.core.settings;

import org.springframework.stereotype.Component;

import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.util.concurrent.IsolationLevel;
import org.niord.core.cache.BaseCache;
import org.niord.core.cache.CacheElement;

/**
 * Implements the settings cache, local to the node, with a lifespan of 3 minutes. A save empties its key on the node
 * that saves ({@link SettingsService#set}), and every other node empties the whole cache at its next poll of the change
 * count ({@link SettingsRefresher#pollForChanges}), about every 30 seconds.
 *
 * NB: We cannot cache null, so use a CacheElement value wrapper.
 */
@Component
public class SettingsCache extends BaseCache<String, CacheElement<Object>> {

    final static long LIFESPAN      = 3 * 60 * 1000;    // 3 minutes
    final static String CACHE_ID    = "settingsCache";

    /** {@inheritDoc} */
    @Override
    public String getCacheId() {
        return CACHE_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Configuration createCacheConfiguration() {
        return new ConfigurationBuilder()
                .clustering().cacheMode(CacheMode.LOCAL)
                .locking().isolationLevel(IsolationLevel.REPEATABLE_READ)
                .expiration().lifespan(LIFESPAN)
                .build();
    }

}

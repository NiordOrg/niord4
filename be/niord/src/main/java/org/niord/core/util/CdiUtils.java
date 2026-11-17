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
package org.niord.core.util;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import javax.naming.NamingException;

/**
 * Can be used to force injection in classes that do not support CDI
 */
// PORT: §14 replaced CDI.current() programmatic lookup with a static Spring
// PORT: ApplicationContext captured via ApplicationContextAware. The lookup is
// PORT: only used by deferred runtime paths; re-verify bean resolution at the IT stage.
@Component
public class CdiUtils implements ApplicationContextAware {

    private static ApplicationContext applicationContext;

    /**
     * Don't instantiate this class
     */
    private CdiUtils() {
    }

    /** {@inheritDoc} */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        CdiUtils.applicationContext = applicationContext;
    }

    /**
     * Looks up a CDI managed bean with the given class
     *
     * @param clazz The class of the object to look up
     * @return the object with the given class
     */
    @SuppressWarnings({ "unchecked", "unused" })
    public static <T> T getBean(Class<T> clazz) throws NamingException {
        return applicationContext.getBean(clazz);
    }
}

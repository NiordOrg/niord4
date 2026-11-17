/*
 * Copyright 2026 Danish Maritime Authority.
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
package org.niord.core.script;

import freemarker.core.Environment;
import freemarker.core.TemplateClassResolver;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.utility.ClassUtil;

/**
 * Decides which classes a template may instantiate with {@code ?new}: only the directives of
 * {@code org.niord.core.script.directive}, that package and none below it.
 *
 * <p>A template is data an operator edits in admin, so a stored template must not reach any other Java class
 * (FreeMarker's own {@code Execute} and {@code ObjectConstructor} among them). A name outside the package is
 * refused before any class is loaded.
 */
final class DirectiveClassResolver implements TemplateClassResolver {

    static final String DIRECTIVE_PACKAGE = "org.niord.core.script.directive.";

    static final DirectiveClassResolver INSTANCE = new DirectiveClassResolver();

    private DirectiveClassResolver() {
    }

    /** {@inheritDoc} **/
    @Override
    public Class<?> resolve(String className, Environment env, Template template) throws TemplateException {
        if (!isDirective(className)) {
            throw new TemplateException("Instantiating " + className + " is not allowed in a template", env);
        }
        try {
            return ClassUtil.forName(className);
        } catch (ClassNotFoundException e) {
            throw new TemplateException("No directive class " + className, e, env);
        }
    }

    /** Whether the name is a class directly in the directive package */
    static boolean isDirective(String className) {
        if (className == null || !className.startsWith(DIRECTIVE_PACKAGE)) {
            return false;
        }
        String simpleName = className.substring(DIRECTIVE_PACKAGE.length());
        return !simpleName.isEmpty() && simpleName.indexOf('.') < 0;
    }
}

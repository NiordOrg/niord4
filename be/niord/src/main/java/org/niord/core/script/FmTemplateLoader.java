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

package org.niord.core.script;

import freemarker.cache.TemplateLoader;

import java.io.Reader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A version of the Freemarker StringTemplateLoader that loads templates lazily from the ScriptResource database
 * table, and from nothing else: a path with no row resolves to nothing. No template is read from the class path
 * (code/data/configuration plan §6.2); a deployment gets its templates by the bootstrap or a sysadmin's import.
 */
public class FmTemplateLoader implements TemplateLoader {

    // Localized resources use ISO 639-1 suffixes; words such as "_pdf" are not locales.
    private static final Pattern LOCALIZED_TEMPLATE = Pattern.compile("^(.*)_([a-z]{2})\\.ftl$");

    // All templates that have actually been loaded during execution
    private final Map<String, ScriptResource> templates = new HashMap<>();

    // All existing paths in the ScriptResource table
    private final Set<String> existingTemplatePaths;

    private final ScriptResourceService resourceService;
    private final String defaultLanguage;

    /** Constructor **/
    public FmTemplateLoader(ScriptResourceService resourceService) {
        this(resourceService, null);
    }

    public FmTemplateLoader(ScriptResourceService resourceService, String defaultLanguage) {
        this.resourceService = resourceService;
        this.existingTemplatePaths = resourceService.findAllScriptResourcePaths();
        this.defaultLanguage = defaultLanguage;
    }

    /** {@inheritDoc} **/
    @Override
    public void closeTemplateSource(Object template) {
    }

    /** {@inheritDoc} **/
    @Override
    public ScriptResource findTemplateSource(String path) {
        ScriptResource template = findExactTemplateSource(path);
        var localized = LOCALIZED_TEMPLATE.matcher(path);
        if (template == null && defaultLanguage != null && localized.matches()) {
            // Let FreeMarker reach the base template (often English) before applying our fallback.
            if (findExactTemplateSource(localized.group(1) + ".ftl") == null) {
                template = findExactTemplateSource(localized.group(1) + "_" + defaultLanguage + ".ftl");
            }
        }
        return template;
    }

    private ScriptResource findExactTemplateSource(String path) {
        if (existingTemplatePaths.contains(path) && !templates.containsKey(path)) {
            ScriptResource fromDb = resourceService.findByPath(path);
            if (fromDb != null) {
                templates.put(path, fromDb);
            }
        }
        return templates.get(path);
    }

    /** {@inheritDoc} **/
    @Override
    public long getLastModified(Object template) {
        return ((ScriptResource)template).getUpdated().getTime();
    }

    /** {@inheritDoc} **/
    @Override
    public Reader getReader(Object template, String encoding) {
        return new StringReader(((ScriptResource)template).getContent());
    }
}

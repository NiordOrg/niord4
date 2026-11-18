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

package org.niord.core.category;

import freemarker.template.TemplateException;
import org.niord.core.category.FieldTemplateProcessor.FieldTemplate;
import org.niord.core.script.FmTemplateService;
import org.niord.core.script.JsResourceService;
import org.niord.core.script.NashornEngines;
import org.niord.core.script.ScriptResource;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import jakarta.inject.Inject;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executes script resources — JavaScript (via Nashorn) or Freemarker templates — against a context-data map.
 * <p>
 * Extracted from {@link TemplateExecutionService} so that both {@code TemplateExecutionService} (template
 * editing) and {@code PromulgationManager} (promulgation generation) can run scripts without forming a
 * {@code PromulgationManager <-> TemplateExecutionService} bean cycle.
 */
@Service
public class ScriptExecutionService {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(ScriptExecutionService.class);

    @Inject
    FmTemplateService templateService;

    @Inject
    JsResourceService javaScriptService;

    /**
     * Executes the list of script resources on the context data
     * @param scriptResourcePaths the list of script resources
     * @param contextData the context data, i.e. current message, part, etc.
     */
    public void executeScriptResources(List<String> scriptResourcePaths, Map<String, Object> contextData) throws Exception {
        for (String scriptResourcePath : scriptResourcePaths) {

            ScriptResource.Type type = ScriptResource.path2type(scriptResourcePath);
            if (type == ScriptResource.Type.JS) {
                // JavaScript update
                evalJavaScriptResource(contextData, scriptResourcePath);
            } else if (type == ScriptResource.Type.FM) {
                // Freemarker Template update
                applyFreemarkerTemplate(contextData, scriptResourcePath);
            }
        }
    }

    /**
     * Executes the Freemarker template at the given path and updates the message accordingly
     *
     * @param contextData        the context data to use in the Freemarker template
     * @param scriptResourcePath the path to the Freemarker template
     */
    private void applyFreemarkerTemplate(
            Map<String, Object> contextData,
            String scriptResourcePath) throws IOException, TemplateException, ScriptException {

        // Run the associated Freemarker template to get a result in the "FieldTemplates" format
        String fieldTemplateTxt = templateService.newFmTemplateBuilder()
                .templatePath(scriptResourcePath)
                .data(contextData)
                .dictionaryNames("message", "template")
                .process();

        List<FieldTemplate> fieldTemplates = FieldTemplateProcessor.parse(fieldTemplateTxt);

        try {
            applyFieldTemplates(fieldTemplates, contextData);
        } catch (ScriptException e) {
            // Name the template that holds the bad field
            throw new ScriptException("Template " + scriptResourcePath + ": " + e.getMessage());
        }
    }

    /**
     * Updates the fields of the message using the FieldTemplate templates.
     * <p>
     * A FieldTemplate field may have the format "message.promulgation('twitter').tweet"
     * and will be assigned the body content of the field template.
     * <p>
     * The field templates are the output of a Freemarker template, which prints values the caller of the
     * template execution supplied. So a field is only assigned when it has the shape of a property path from
     * the context data ({@link FieldTemplatePath}), and the assignment runs on a sandboxed engine. A field of
     * another shape is never evaluated.
     * <p>
     * A field that is not a property path, or that cannot be assigned (a description in a language the part does
     * not have, a promulgation the message does not carry), fails the run after the other fields have been tried,
     * and each such field is logged: a template that leaves a field unset is a failed script, and Generate and the
     * template dialog fail when a script fails.
     *
     * @param fieldTemplates the field templates to apply
     * @throws ScriptException naming every field that could not be applied
     */
    void applyFieldTemplates(List<FieldTemplate> fieldTemplates, Map<String, Object> contextData) throws ScriptException {

        ScriptEngine jsEngine = NashornEngines.sandboxed();

        // Update the JavaScript engine bindings from the context data
        contextData.forEach(jsEngine::put);

        List<String> failures = new ArrayList<>();
        for (FieldTemplate fieldTemplate : fieldTemplates) {

            if (!FieldTemplatePath.isValid(fieldTemplate.getField(), contextData.keySet())) {
                log.error("Skipping field template with an unsupported field expression: " + fieldTemplate.getField());
                failures.add(fieldTemplate.getField() + " (not a property path)");
                continue;
            }

            jsEngine.put("content", fieldTemplate.getContent());
            String script = getUpdateScript(fieldTemplate);
            try {
                jsEngine.eval(script);
            } catch (ScriptException e) {
                // Apply the result of the field templates
                log.error("Error applying field template " + fieldTemplate + ": " + e.getMessage());
                failures.add(fieldTemplate.getField() + " (" + e.getMessage() + ")");
            }
        }

        if (!failures.isEmpty()) {
            throw new ScriptException(failures.size() + " field template(s) could not be applied: "
                    + String.join("; ", failures));
        }
    }

    /** Returns a JavaScript that either assigns or appends the content to the field **/
    private String getUpdateScript(FieldTemplate fieldTemplate) {
        String field = fieldTemplate.getField();

        // Either append or assign
        if ("append".equalsIgnoreCase(fieldTemplate.getUpdate())) {
            // Append the content variable to the field
            return String.format("%s = (%s == null) ? content : %s + '\\n' + content;", field, field, field);

        } else {
            // Assign the content variable to the field
            return field + " = content;";
        }
    }

    /**
     * Evaluates the JavaScript resource at the given path
     *
     * @param contextData        the context data to use in the Freemarker template
     * @param scriptResourcePath the path to the Freemarker template
     */
    private void evalJavaScriptResource(Map<String, Object> contextData, String scriptResourcePath) throws Exception {

        javaScriptService.newJsResourceBuilder()
                .resourcePath(scriptResourcePath)
                .data(contextData)
                .evaluate();
    }
}

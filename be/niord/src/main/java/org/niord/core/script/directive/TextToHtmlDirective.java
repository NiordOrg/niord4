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

package org.niord.core.script.directive;

import freemarker.core.Environment;
import freemarker.template.SimpleScalar;
import freemarker.template.TemplateDirectiveBody;
import freemarker.template.TemplateDirectiveModel;
import freemarker.template.TemplateException;
import freemarker.template.TemplateModel;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * Freemarker directive. Converts plain text to HTML.
 *
 * <p>A missing {@code text} parameter and a text whose value is null reach the directive alike, and print nothing:
 * a template passes optional texts to it, such as a NAVTEX promulgation's preamble, which a series without a NAVTEX
 * format never fills in.
 */
@SuppressWarnings("unused")
public class TextToHtmlDirective implements TemplateDirectiveModel {

    private static final String PARAM_TEXT = "text";

    private static final Logger log = LoggerFactory.getLogger(TextToHtmlDirective.class);

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(Environment env,
                        Map params,
                        TemplateModel[] loopVars,
                        TemplateDirectiveBody body)
            throws TemplateException, IOException {

        SimpleScalar txtModel = (SimpleScalar)params.get(PARAM_TEXT);
        if (txtModel == null) {
            return;
        }

        try {
            String txt = txtModel.getAsString();

            if (txt != null) {
                env.getOut().write(TextUtils.txt2html(txt));
            }
        } catch (Exception e) {
            // Prefer robustness over correctness, but say so (the length only, never the text)
            log.warn("Text of {} characters not printed as HTML in template {}: {}",
                    StringUtils.length(txtModel.getAsString()),
                    LightCharacterDirective.templateName(env), LightCharacterDirective.reason(e));
        }
    }

}

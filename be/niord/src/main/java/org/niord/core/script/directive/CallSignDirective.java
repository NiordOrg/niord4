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

package org.niord.core.script.directive;

import freemarker.core.Environment;
import freemarker.template.SimpleScalar;
import freemarker.template.TemplateDirectiveBody;
import freemarker.template.TemplateDirectiveModel;
import freemarker.template.TemplateException;
import freemarker.template.TemplateModel;
import freemarker.template.TemplateModelException;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.aton.LightCharacterService;
import org.niord.core.util.CdiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * Freemarker directive.
 * If the "format" parameter is "verbose", the call sign is formatted in telephony codes,
 * otherwise, the call sign is emitted directly.
 * <p>
 * Example of "verbose" translation:
 * <pre>
 *     "OUXR2" -> "Oscar Uniform X-ray Romeo Two"
 * </pre>
 */
@SuppressWarnings("unused")
public class CallSignDirective implements TemplateDirectiveModel {

    private static final String PARAM_CALL_SIGN = "callSign";
    private static final String PARAM_FORMAT    = "format";

    private static final Logger log = LoggerFactory.getLogger(CallSignDirective.class);

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(Environment env,
                        Map params,
                        TemplateModel[] loopVars,
                        TemplateDirectiveBody body)
            throws TemplateException, IOException {

        // Get the "callSign" parameter
        SimpleScalar callSignModel = (SimpleScalar)params.get(PARAM_CALL_SIGN);
        if (callSignModel == null) {
            throw new TemplateModelException("The 'callSign' parameter must be specified");
        }
        String callSign = callSignModel.getAsString();

        // Get the "format" parameter
        SimpleScalar formatModel = (SimpleScalar)params.get(PARAM_FORMAT);
        boolean verbose = formatModel != null && "verbose".equalsIgnoreCase(formatModel.getAsString());

        try {
            if (verbose && StringUtils.isNotBlank(callSign)) {

                String lang = env.getLocale().getLanguage();
                LightCharacterService lightCharacterService = CdiUtils.getBean(LightCharacterService.class);
                String result = lightCharacterService.formatTelephonyCode(lang, callSign);
                env.getOut().write(result);

            } else {
                env.getOut().write(callSign.toUpperCase());
            }
        } catch (Exception e) {
            // Prefer robustness over correctness, but say so: a missing telephony-codes template (a row an operator
            // can rename or delete) is otherwise seen nowhere at the deployed level (TMPL19)
            log.warn("Call sign \"{}\" written as it is, not in telephony words: {}", callSign,
                    LightCharacterDirective.reason(e));
            env.getOut().write(callSign);
        }
    }

}

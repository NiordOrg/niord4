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
import freemarker.template.SimpleDate;
import freemarker.template.SimpleScalar;
import freemarker.template.TemplateDirectiveBody;
import freemarker.template.TemplateDirectiveModel;
import freemarker.template.TemplateException;
import freemarker.template.TemplateModel;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TimeZone;

import static org.niord.core.script.FmTemplateService.BUNDLE_PROPERTY;
import static org.niord.core.script.FmTemplateService.TIME_ZONE_PROPERTY;

/**
 * This Freemarker directive will format a DateIntervalVo or a from-to date interval
 */
@SuppressWarnings("unused")
public class FromToDateDirective implements TemplateDirectiveModel {

    private static final String PARAM_FROM_DATE = "fromDate";
    private static final String PARAM_TO_DATE = "toDate";
    private static final String PARAM_TZ = "tz";

    private static final Logger log = LoggerFactory.getLogger(FromToDateDirective.class);

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(Environment env,
                        Map params,
                        TemplateModel[] loopVars,
                        TemplateDirectiveBody body)
            throws TemplateException, IOException {

        // Fetch the resource bundle
        MultiResourceBundleModel text = (MultiResourceBundleModel)env.getDataModel().get(BUNDLE_PROPERTY);

        Date fromDate = null;
        if (params.get(PARAM_FROM_DATE) != null) {
            fromDate = ((SimpleDate)params.get(PARAM_FROM_DATE)).getAsDate();
        }

        Date toDate = null;
        if (params.get(PARAM_TO_DATE) != null) {
            toDate = ((SimpleDate)params.get(PARAM_TO_DATE)).getAsDate();
        }

        // For the time zone, first check the "tz" parameter, next the "timeZone" data, lastly the default
        // (mirrors DateIntervalDirective). SCRIPT3.
        SimpleScalar tzParam = (SimpleScalar)params.get(PARAM_TZ);
        SimpleScalar timeZoneId = (tzParam != null && StringUtils.isNotBlank(tzParam.getAsString()))
                ? tzParam
                : (SimpleScalar)env.getDataModel().get(TIME_ZONE_PROPERTY);
        TimeZone timeZone = (timeZoneId != null)
                ? TimeZone.getTimeZone(timeZoneId.toString())
                : TimeZone.getDefault();

        try {
            String result = formatFromToDates(text.getResourceBundle(), env.getLocale(), timeZone, fromDate, toDate);
            env.getOut().write(result);
        } catch (Exception e) {
            // Prefer robustness over correctness, but say so: a date format text missing or edited to a pattern
            // that does not parse otherwise leaves the dates blank without a trace
            log.warn("Dates {} - {} not printed in template {}: {}",
                    fromDate == null ? "(none)" : fromDate.toInstant(),
                    toDate == null ? "(none)" : toDate.toInstant(),
                    LightCharacterDirective.templateName(env), LightCharacterDirective.reason(e));
        }
    }

    /**
     * Formats the date interval as text
     *
     * @return the formatted date interval
     */
    private String formatFromToDates(ResourceBundle text, Locale locale, TimeZone timeZone, Date from, Date to) throws Exception {
        if (from == null && to == null) {
            return text.getString("msg.time.until_further_notice");
        }

        DateFormat dateFormat = new SimpleDateFormat(text.getString("msg.time.date_time_format"), locale);
        dateFormat.setTimeZone(timeZone);

        StringBuilder result = new StringBuilder();

        if (from != null) {
            result.append(dateFormat.format(from));
        }
        if (from != null && to != null) {
            result.append(" - ");
        }
        if (to != null) {
            result.append(dateFormat.format(to));
        }

        return result.toString();
    }
}

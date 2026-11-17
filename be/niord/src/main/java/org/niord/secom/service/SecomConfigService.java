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
package org.niord.secom.service;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.service.BaseService;
import org.niord.secom.model.S124SeriesConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owns the persisted per-series SECOM configuration: the {@link S124SeriesConfig} rows that
 * scope which message series are exposed over SECOM (replaces the {@code secomS124Series}
 * setting - phase 3 of docs/s124-cancellation-plan.md) and which model languages their
 * datasets may carry; edited from the sysadmin SECOM API. The scalar node-wide SECOM knobs
 * are ordinary {@code @Setting}s on their consuming beans (phase 4), managed from the generic
 * sysadmin Settings page.
 */
@Service
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@SuppressWarnings("unused")
public class SecomConfigService extends BaseService {

    private final MessageSeriesService messageSeriesService;
    private final NiordApp app;

    public SecomConfigService(MessageSeriesService messageSeriesService, NiordApp app) {
        this.messageSeriesService = messageSeriesService;
        this.app = app;
    }

    /***************************************/
    /** Per-series configuration          **/
    /***************************************/

    /** Returns all per-series SECOM configuration rows */
    public List<S124SeriesConfig> getSeriesConfigs() {
        return em.createNamedQuery("S124SeriesConfig.findAll", S124SeriesConfig.class)
                .getResultList();
    }

    /** Returns the configuration row for the given series, or null if none exists */
    public S124SeriesConfig findSeriesConfig(String seriesId) {
        return getSingleOrNull(em.createNamedQuery("S124SeriesConfig.findBySeriesId", S124SeriesConfig.class)
                .setParameter("seriesId", seriesId)
                .getResultList());
    }

    /** Returns the IDs of the message series exposed over SECOM (no config row = not exposed) */
    public Set<String> exposedSeriesIds() {
        return Set.copyOf(em.createNamedQuery("S124SeriesConfig.exposedSeriesIds", String.class)
                .getResultList());
    }

    /**
     * Creates or updates the configuration row of the given message series - the exposure
     * flag and the language restriction of its S-124 datasets - replacing it as given. The
     * languages are normalised to model-language order (see {@link #normalizeLanguages});
     * null or empty means no restriction.
     *
     * @throws IllegalArgumentException if no message series with the given ID exists, or a
     *                                  language is not one of the model languages
     */
    @Transactional
    public S124SeriesConfig upsertSeriesConfig(String seriesId, boolean exposeOverSecom, List<String> languages) {
        List<String> normalized = normalizeLanguages(languages, app.getLanguages());
        S124SeriesConfig config = findSeriesConfig(seriesId);
        if (config == null) {
            MessageSeries series = messageSeriesService.findBySeriesId(seriesId);
            if (series == null) {
                throw new IllegalArgumentException("Unknown message series " + seriesId);
            }
            config = new S124SeriesConfig();
            config.setMessageSeries(series);
        }
        config.setExposeOverSecom(exposeOverSecom);
        config.setLanguages(normalized);
        return saveEntity(config);
    }

    /**
     * Normalises a requested language restriction against the model languages: codes are
     * trimmed and lower-cased, blanks and duplicates dropped, and the result is ordered as
     * the model languages are, so the stored value is canonical however the request spelled
     * it. Null or nothing left means no restriction (empty list). A code that is not a model
     * language is rejected rather than dropped: silently ignoring it would leave the series
     * looking restricted to something it never publishes.
     *
     * @throws IllegalArgumentException if a requested language is not a model language
     */
    static List<String> normalizeLanguages(List<String> requested, String[] modelLanguages) {
        if (requested == null) {
            return List.of();
        }
        List<String> model = Arrays.stream(modelLanguages)
                .map(l -> l.toLowerCase(Locale.ROOT))
                .toList();
        Set<String> codes = requested.stream()
                .filter(StringUtils::isNotBlank)
                .map(l -> l.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        codes.stream().filter(code -> !model.contains(code)).findFirst().ifPresent(code -> {
            throw new IllegalArgumentException("Unknown language '" + code
                    + "': the S-124 languages of a series must be model languages " + model);
        });
        return model.stream().filter(codes::contains).toList();
    }

    /**
     * Deletes the configuration row of the given message series (the series is no longer exposed)
     *
     * @throws IllegalArgumentException if the series has no configuration row
     */
    @Transactional
    public S124SeriesConfig deleteSeriesConfig(String seriesId) {
        S124SeriesConfig config = findSeriesConfig(seriesId);
        if (config == null) {
            throw new IllegalArgumentException("No SECOM series config for " + seriesId);
        }
        em.remove(config);
        return config;
    }
}

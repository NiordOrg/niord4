/*
 * Copyright 2024 Danish Maritime Authority.
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

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.niord.core.conf.TextResource;
import org.niord.core.conf.TextResourceProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Spring replacement for the CDI {@code @Produces @Setting} / {@code @TextResource}
 * qualifier-driven producers that lived on {@code SettingsService} / {@code TextResourceProducer}
 * under Quarkus.
 *
 * <p>It scans every bean for fields annotated with
 * {@link org.niord.core.settings.annotation.Setting} or {@link TextResource} and populates
 * them by reflection — {@code @Setting} via {@link SettingsService}, and {@code @TextResource} via
 * {@link TextResourceProducer#loadResourceText}. This reproduces the original injection semantics without CDI
 * {@code InjectionPoint} producers.
 *
 * <p>A bean created while the database cannot be read yet gets the setting's default
 * ({@link SettingsService#resolveDefault}: the settings file's value, read without the database), or the annotation
 * default when {@link SettingsService} itself is not available yet. Such a field is recorded and injected again
 * through the database once start-up has added the missing settings ({@link #reinjectDeferred}), so every bean ends
 * start-up with the database's value (docs/code-data-config-plan.md §5.2). A field injected without the database after
 * start-up, such as the sea-chart servlet's at its first request, is read again by every {@link #refresh}, the poll's
 * every 30 seconds included, until a read succeeds.
 *
 * <p>A saved setting takes effect at once. The processor records every {@code @Setting} field it injects, also of a
 * bean created after start-up (the bean by weak reference), and {@link #refresh} sets the fields of a key again with
 * the same resolve and coerce code as the injection, when {@link SettingsRefresher} asks after a save or after
 * another node's save. A refreshed field is {@code volatile}. Two kinds of field are not refreshed: one whose key is on
 * {@link #RESTART_KEYS}, which by its meaning acts only when the server starts, and one whose key the catalogue does
 * not make editable (deployment configuration, which the Settings page neither lists nor saves).
 */
@Component
public class SettingInjectionBeanPostProcessor implements InstantiationAwareBeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SettingInjectionBeanPostProcessor.class);
    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    /**
     * The settings that take effect only at the next start, so their fields are never refreshed; each has its reason
     * in {@code SettingsCatalogCurrentTest}, and its description ends "Applies at the next start."
     */
    static final Set<String> RESTART_KEYS = Set.of("messageIndexDeleteOnStartup");

    // ObjectProvider keeps the dependency lazy so this processor (and SettingsService) can be
    // created without an eager circular dependency.
    private final ObjectProvider<SettingsService> settingsService;

    /**
     * A field the processor injected: the bean by weak reference, so a bean that is no singleton is not kept alive,
     * and the value last resolved for it, so a refresh changes the field only when its setting changed
     */
    private static final class Injected {
        final WeakReference<Object> bean;
        final Field field;
        final Setting template;
        Object value;

        /**
         * Whether the field may not hold the database's value although its setting did not change: it was injected
         * without the database after start-up, or while a refresh ran. Every refresh reads it again until a read
         * succeeds, without counting it as a change.
         */
        volatile boolean stale;

        /** Whether the last refresh could not read its setting: every refresh reads it again until a read succeeds */
        volatile boolean failed;

        Injected(Object bean, Field field, Setting template, Object value) {
            this.bean = new WeakReference<>(bean);
            this.field = field;
            this.template = template;
            this.value = value;
        }
    }

    /**
     * What a {@link #refresh} did
     *
     * @param changed the keys of which a field took a new value
     * @param failed  the keys of which a field keeps its value because its setting could not be read; the next
     *                refresh reads it again
     */
    record Refreshed(Set<String> changed, Set<String> failed) {
    }

    /** Every field injected so far; guarded by itself */
    private final List<Injected> injected = new ArrayList<>();

    /** How many refreshes have started: an injection that overlaps one marks its field stale */
    private final AtomicLong refreshes = new AtomicLong();

    /** The size of {@link #injected} at which its dead entries are removed next */
    private int purgeAt = 64;

    /** One refresh at a time: a save's and the poll's */
    private final Object refreshLock = new Object();

    /** A field injected without the database, to be injected again once start-up has added the missing settings */
    private record Deferred(Object bean, Injected injected) {
    }

    private final List<Deferred> deferred = new ArrayList<>();

    /** Set by the first {@link #reinjectDeferred}: a field injected without the database later is not deferred */
    private volatile boolean started;

    public SettingInjectionBeanPostProcessor(ObjectProvider<SettingsService> settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Populate {@code @Setting} / {@code @TextResource} fields during the property-population
     * phase — i.e. <em>before</em> {@code @PostConstruct} init methods run (which is exactly
     * where {@code @Inject}/{@code @Autowired} injection happens too).
     */
    @Override
    public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
        Class<?> type = bean.getClass();
        ReflectionUtils.doWithFields(type, field -> {
            org.niord.core.settings.annotation.Setting setting =
                    field.getAnnotation(org.niord.core.settings.annotation.Setting.class);
            if (setting != null) {
                injectSetting(bean, field, setting);
            }
            TextResource textResource = field.getAnnotation(TextResource.class);
            if (textResource != null) {
                injectTextResource(bean, field, textResource, type);
            }
        });
        return pvs;
    }

    // ------------------------------------------------------------------------------------------------

    private void injectSetting(Object bean, Field field,
                               org.niord.core.settings.annotation.Setting ann) {
        Setting template = annotationToSetting(ann, field.getName());
        if (template.getType() == Setting.Type.Password) {
            // Before the value is resolved: no log line may show it, whatever a niord.json says
            SettingsService.registerDeclaredPasswordKey(template.getKey());
        }
        SettingsService svc = available();
        Object value;
        boolean withoutDatabase = false;
        long refreshesBefore = refreshes.get();
        try {
            if (svc == null) {
                throw new IllegalStateException("SettingsService is not available yet");
            }
            value = resolveSettingValue(svc, template, field.getType());
        } catch (Exception e) {
            // e.g. EntityManager not ready for a very early bean: the default now, the database's value after start-up
            log.debug("@Setting '{}' of {} resolved without the database during startup: {}", template.getKey(),
                    bean.getClass().getSimpleName(), e.toString());
            value = defaultWithoutDatabase(svc, template);
            withoutDatabase = true;
        }
        if (value == null) {
            // fall back to the parsed annotation default (mirrors the CDI producer when DB has no override)
            value = template.getValue();
        }
        // The typed getters can hand back a raw String (e.g. an un-coerced default); coerce
        // to the field's declared type so e.g. a Path field is never assigned a String.
        value = coerce(value, field.getType());
        setField(bean, field, value);

        Injected record = new Injected(bean, field, template, value);
        if (withoutDatabase) {
            synchronized (deferred) {
                if (!started) {
                    deferred.add(new Deferred(bean, record));
                } else {
                    // A bean created after start-up, such as the sea-chart servlet at its first request: the deferred
                    // list would keep the bean reachable, so the next refresh, the poll's at the latest, reads it again
                    record.stale = true;
                    log.debug("@Setting '{}' of {}.{} keeps the default resolved without the database until the "
                            + "next refresh", template.getKey(), bean.getClass().getSimpleName(), field.getName());
                }
            }
        }
        record(record);
        if (refreshes.get() != refreshesBefore) {
            // A refresh that began after the value was read may have passed this field by before it was recorded
            record.stale = true;
        }
    }

    /** Records an injected field, and every so often forgets the fields of beans that are gone */
    private void record(Injected record) {
        synchronized (injected) {
            injected.add(record);
            if (injected.size() >= purgeAt) {
                injected.removeIf(i -> i.bean.get() == null);
                purgeAt = Math.max(64, injected.size() * 2);
            }
        }
    }

    /**
     * Sets again each recorded field of a key the given filter accepts, with the value the setting has now, resolved
     * as the injection resolves it (an environment variable or system property named like the key still wins); and
     * returns the keys of which at least one field took a new value. A field is set only when that value differs from
     * the one last resolved for it, so a field is changed only when its setting changed. A field whose key is on
     * {@link #RESTART_KEYS}, or whose key the catalogue does not make editable, is left as it is. A value that cannot
     * be resolved leaves the field as it is, with a warning the first time, and every later refresh reads it again
     * until a read succeeds, whatever keys it was asked for; so does a field that may not hold the database's value
     * although its setting did not change ({@code stale}: injected without the database after start-up, or while a
     * refresh ran), which is set again whatever its key and is not counted as a change. Each field set is logged,
     * without its value.
     *
     * @param svc  the settings service, its transactional proxy
     * @param keys which keys to refresh
     * @return the keys of which a field took a new value, and those of which a field could not be read
     */
    Refreshed refresh(SettingsService svc, Predicate<String> keys) {
        refreshes.incrementAndGet();
        List<Injected> due;
        synchronized (injected) {
            due = injected.stream()
                    .filter(i -> i.bean.get() != null && (i.stale || i.failed || keys.test(i.template.getKey())))
                    .toList();
        }
        Set<String> changed = new LinkedHashSet<>();
        Set<String> failed = new LinkedHashSet<>();
        synchronized (refreshLock) {
            for (Injected i : due) {
                String key = i.template.getKey();
                Object bean = i.bean.get();
                boolean stale = i.stale;
                if (bean == null || (!stale && !refreshable(svc, key))) {
                    continue;
                }
                try {
                    Object value = resolveSettingValue(svc, i.template, i.field.getType());
                    if (value == null) {
                        value = i.template.getValue();
                    }
                    value = coerce(value, i.field.getType());
                    i.stale = false;
                    i.failed = false;
                    if (Objects.equals(value, i.value)) {
                        continue;
                    }
                    if (value == null && i.field.getType().isPrimitive()) {
                        continue;
                    }
                    ReflectionUtils.makeAccessible(i.field);
                    ReflectionUtils.setField(i.field, bean, value);
                    i.value = value;
                    if (stale) {
                        log.info("@Setting '{}' of {}.{} injected again: the database's value differs from the one "
                                + "injected", key, bean.getClass().getSimpleName(), i.field.getName());
                    } else {
                        changed.add(key);
                        log.info("@Setting '{}' of {}.{} refreshed: the setting changed", key,
                                bean.getClass().getSimpleName(), i.field.getName());
                    }
                } catch (Exception e) {
                    if (stale || i.failed) {
                        log.debug("@Setting '{}' of {}.{} still keeps its value, the setting could not be read: {}",
                                key, bean.getClass().getSimpleName(), i.field.getName(), e.toString());
                    } else {
                        log.warn("@Setting '{}' of {}.{} keeps its value, the setting could not be read; it is read "
                                        + "again at the next refresh: {}", key, bean.getClass().getSimpleName(),
                                i.field.getName(), e.toString());
                    }
                    if (!stale) {
                        i.failed = true;
                        failed.add(key);
                    }
                }
            }
        }
        return new Refreshed(changed, failed);
    }

    /** Whether the fields of the key are refreshed: not a restart setting, and editable by the catalogue */
    static boolean refreshable(SettingsService svc, String key) {
        if (RESTART_KEYS.contains(key)) {
            return false;
        }
        SettingsCatalog.Entry entry = svc.catalogEntry(key);
        return entry != null && entry.editable();
    }

    /**
     * Injects again, through the database, each field that was injected without it while the context started, and
     * forgets them. Called by {@link SettingsService} once start-up has added the missing settings; a field injected
     * without the database after the first call, or one this cannot read either, is read again by the next
     * {@link #refresh}.
     *
     * @param svc the settings service
     */
    void reinjectDeferred(SettingsService svc) {
        List<Deferred> pending;
        synchronized (deferred) {
            started = true;
            pending = new ArrayList<>(deferred);
            deferred.clear();
        }
        for (Deferred d : pending) {
            Injected i = d.injected();
            try {
                Object value = resolveSettingValue(svc, i.template, i.field.getType());
                if (value == null) {
                    value = i.template.getValue();
                }
                value = coerce(value, i.field.getType());
                ReflectionUtils.makeAccessible(i.field);
                if (value != null && !Objects.equals(ReflectionUtils.getField(i.field, d.bean()), value)) {
                    setField(d.bean(), i.field, value);
                    log.info("@Setting '{}' of {}.{} injected again: the database's value differs from the one "
                                    + "injected while the database could not be read",
                            i.template.getKey(), d.bean().getClass().getSimpleName(), i.field.getName());
                }
                synchronized (refreshLock) {
                    i.value = value;
                }
            } catch (Exception e) {
                // The next refresh, the poll's at the latest, reads it again
                i.stale = true;
                log.warn("@Setting '{}' of {}.{} keeps the value injected without the database: {}",
                        i.template.getKey(), d.bean().getClass().getSimpleName(), i.field.getName(), e.toString());
            }
        }
    }

    /** The settings service, or null while it cannot be created yet */
    private SettingsService available() {
        try {
            return settingsService.getIfAvailable();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The setting's default without the database: {@link SettingsService#resolveDefault}, called on the service
     * itself rather than its transactional proxy, which would need a transaction; null when there is no service yet
     */
    private static Object defaultWithoutDatabase(SettingsService svc, Setting template) {
        if (svc == null) {
            return null;
        }
        try {
            Object target = AopProxyUtils.getSingletonTarget(svc);
            return (target instanceof SettingsService s ? s : svc).resolveDefault(template);
        } catch (Exception e) {
            log.debug("@Setting '{}' has no default without the database: {}", template.getKey(), e.toString());
            return null;
        }
    }

    /** Resolves the setting through the settings service with the field type's getter */
    private static Object resolveSettingValue(SettingsService svc, Setting template, Class<?> ft) {
        if (ft == String.class)                       return svc.getString(template);
        if (ft == Integer.class || ft == int.class)   return svc.getInteger(template);
        if (ft == Boolean.class || ft == boolean.class) return svc.getBoolean(template);
        if (ft == Long.class || ft == long.class)     return svc.getLong(template);
        if (ft == Float.class || ft == float.class)   return svc.getFloat(template);
        if (ft == Double.class || ft == double.class) return svc.getDouble(template);
        if (Path.class.isAssignableFrom(ft))          return svc.getPath(template);
        if (Date.class.isAssignableFrom(ft))          return svc.getDate(template);
        return svc.get(template);
    }

    /**
     * Port of {@code SettingsService.ip2setting(InjectionPoint)} reading from the annotation. The settings catalog
     * generator reads each {@code @Setting} field through it, so the catalog's code default is the injected one.
     */
    static Setting annotationToSetting(org.niord.core.settings.annotation.Setting ann, String fieldName) {
        String key = (ann.value() == null || ann.value().isBlank()) ? fieldName : ann.value();
        String defVal = ann.defaultValue();
        Object value = null;
        switch (ann.type()) {
            case String:
            case Password: value = defVal; break;
            case Integer:  value = defVal.isEmpty() ? null : Integer.valueOf(defVal); break;
            case Long:     value = defVal.isEmpty() ? null : Long.valueOf(defVal); break;
            case Float:    value = defVal.isEmpty() ? null : Float.valueOf(defVal); break;
            case Double:   value = defVal.isEmpty() ? null : Double.valueOf(defVal); break;
            case Boolean:  value = defVal.isEmpty() ? null : Boolean.valueOf(defVal); break;
            case Date:     value = defVal.isEmpty() ? null : Long.valueOf(defVal); break;
            case Path:     value = defVal.isEmpty() ? null : Paths.get(defVal); break;
            case json:
                try { value = defVal.isEmpty() ? null : MAPPER.readValue(defVal, Object.class); }
                catch (Exception ignored) { }
                break;
        }
        return new Setting(key, value, ann.type(), ann.description(), ann.cached(), ann.web(), ann.editable())
                .scope(ann.scope())
                .required(ann.required());
    }

    /** Coerce a resolved setting value to the declared field type (String -> Path/Integer/Boolean/...). */
    private static Object coerce(Object value, Class<?> ft) {
        if (value == null || ft.isInstance(value)) {
            return value;
        }
        String s = value.toString();
        try {
            if (ft == String.class)                       return s;
            if (ft == Integer.class || ft == int.class)   return Integer.valueOf(s);
            if (ft == Long.class || ft == long.class)     return Long.valueOf(s);
            if (ft == Boolean.class || ft == boolean.class) return Boolean.valueOf(s);
            if (ft == Float.class || ft == float.class)   return Float.valueOf(s);
            if (ft == Double.class || ft == double.class) return Double.valueOf(s);
            if (Path.class.isAssignableFrom(ft))          return Paths.get(s);
        } catch (RuntimeException e) {
            log.debug("Could not coerce setting value '{}' to {}: {}", s, ft, e.toString());
        }
        return value;
    }

    private void injectTextResource(Object bean, Field field, TextResource ann, Class<?> declaring) {
        String text = TextResourceProducer.loadResourceText(declaring, ann.value());
        setField(bean, field, text);
    }

    private void setField(Object bean, Field field, Object value) {
        if (value == null) {
            return;
        }
        ReflectionUtils.makeAccessible(field);
        try {
            ReflectionUtils.setField(field, bean, value);
        } catch (Exception e) {
            log.warn("Failed injecting value into {}.{}: {}", bean.getClass().getSimpleName(), field.getName(), e.toString());
        }
    }
}

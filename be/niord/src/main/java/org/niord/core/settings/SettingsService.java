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

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.Session;
import org.niord.core.cache.CacheElement;
import org.niord.core.db.JpaJsonAttributeConverter;
import org.niord.core.defaults.DefaultSettings;
import org.niord.core.defaults.DefaultTexts;
import org.niord.core.defaults.DefaultsGuard;
import org.niord.core.defaults.DefaultsLedger;
import org.niord.core.service.BaseService;
import org.niord.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.ClassUtils;

import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;

/**
 * Interface for accessing settings.
 * <p/>
 * This bean can either be injected directly,
 * or the {@code @Setting} annotation can be used.
 * <p/>
 * The default settings (docs/code-data-config-plan.md §5) are read from the class path in the constructor, with no
 * database: the catalogs and the settings file ({@link DefaultSettings}), and the server's own
 * {@code ${niord.home}/niord.json} over them. So an {@code @Setting} field resolves through them from the first bean
 * on. At start-up {@link #init()} adds the operator settings the database lacks ({@link #addMissing()}); a read never
 * writes, and a {@code state} setting is written only by {@link #writeState}.
 */
@Service
@SuppressWarnings("unused")
public class SettingsService extends BaseService {

    /** The root folder of the server's own files; no code default: without a row {@link #peek} gives the settings file's value, expanded */
    public static final Setting NIORD_HOME = new Setting("niord.home", null)
            .type(Setting.Type.String)
            .description("The root directory for the Niord application")
            .editable(true);

    /**
     * The deployments' environment variable for the home folder, and the token the countries' settings files give
     * {@code niord.home}: {@link #overrideOf} answers it for {@code niord.home} when no {@code niord.home} system
     * property or environment variable is set
     */
    static final String HOME_VARIABLE = "NIORD_HOME";

    /** The server's own settings file, in the {@code niord.home} folder */
    static final String SERVER_SETTINGS_FILE = "niord.json";

    /** What the logs show in place of the value of a Password-typed setting */
    static final String MASKED_VALUE = "********";

    /** The keys an {@code @Setting} field declares Password-typed, see {@link #registerDeclaredPasswordKey} */
    private static final Set<String> DECLARED_PASSWORD_KEYS = ConcurrentHashMap.newKeySet();

    private static final String INSERT_SETTING = "INSERT INTO public.\"Setting\" "
            + "(setting_key, cached, description, editable, type, setting_value, web) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (setting_key) DO NOTHING";

    private static final String UPSERT_STATE = "INSERT INTO public.\"Setting\" "
            + "(setting_key, cached, description, editable, type, setting_value, web) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value";

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final SettingsCache settingsCache;

    private final ListableBeanFactory beanFactory;

    /** The defaults of the class path, without the server's overlay */
    private final DefaultSettings fileDefaults;

    /** The defaults a missing row resolves to: the class path's, with the server's overlay over them */
    private volatile DefaultSettings defaults;

    /** The folder the server's overlay was read from, or null */
    private volatile String overlayFolder;

    @Inject
    DefaultsLedger ledger;

    SettingsService(SettingsCache settingsCache, ListableBeanFactory beanFactory) {
        this.settingsCache = settingsCache;
        this.beanFactory = beanFactory;
        ClassLoader cl = SettingsService.class.getClassLoader();
        DefaultsGuard.checkSettings(cl);
        fileDefaults = DefaultSettings.read(cl);

        // niord.home without the database: a system property or environment variable named niord.home, else
        // NIORD_HOME (override), else the settings file's value. init() reads the overlay again when the database's
        // row names another folder and neither is set.
        String home = override(NIORD_HOME.getKey());
        if (home == null && fileDefaults.defaultValue(NIORD_HOME.getKey()) instanceof String fileHome) {
            home = expandWithoutDatabase(fileHome, fileDefaults);
        }
        overlayFolder = StringUtils.trimToNull(home);
        defaults = withServerOverlay(fileDefaults, overlayFolder);

        Set<String> passwordKeys = passwordKeys(defaults.passwordKeys());
        defaults.fileValues().forEach((key, value) -> log.info("Default setting {} = {} ({})", key,
                loggableValue(new Setting(key, value), passwordKeys), defaults.source(key)));
    }

    /**
     * Called when the system starts up, before every other {@code ApplicationReadyEvent} listener: adds the operator
     * settings the database lacks ({@link #addMissing()}).
     *
     * <p>First it refuses the start when {@code niord.home} names no folder ({@link #refuseAnUnresolvableHome()}),
     * before any listener creates a folder under the home and before anything is added.
     *
     * <p>Then, when no {@code niord.home} property or variable and no {@code NIORD_HOME} is set ({@link #override})
     * and the database's {@code niord.home} row names another folder than the one the constructor read the server's
     * {@code niord.json} from, the overlay is read again from that folder: the row wins over the file, as it always
     * has.
     */
    // PG5: the settings READERS (e.g. DictionaryService.init -> NiordApp.getLanguages, MessageLuceneIndex.init) are
    // @EventListener(ApplicationReadyEvent) too. HIGHEST_PRECEDENCE makes the add-missing step run before every
    // other ApplicationReadyEvent listener, so they read the added rows; a read before it resolves to the settings
    // file's value and writes nothing.
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    void init() {
        log.info("Starting Initialization Settings");
        refuseAnUnresolvableHome();
        readOverlayFromTheDatabasesHome();
        addMissing();
        log.info("Finished Initializing Settings");
    }

    /**
     * Stops the start, with the cause and the cure logged at ERROR and as the exception's text, when {@code niord.home}
     * names no folder: its own value, as a read takes it ({@link #homeValue}), still holds a {@code ${...}} token that
     * nothing resolves, or comes out blank ({@link #homeRefusal}). An unresolved token would otherwise read as empty
     * text, and the folders under the home ({@code ${niord.home}/message-index} and the others) would land at the root
     * of the disk. Only {@code niord.home} is checked; the listeners that create folders under it
     * ({@code MessageLuceneIndex}, {@code PublicationPathService}) run after this one.
     *
     * @throws IllegalStateException when {@code niord.home} cannot be resolved
     */
    void refuseAnUnresolvableHome() {
        String key = NIORD_HOME.getKey();
        String override = override(key);
        Setting row = override == null ? em.find(Setting.class, key) : null;
        boolean homeSettingSet = System.getProperty(key) != null || System.getenv(key) != null;
        HomeValue home = homeValue(override, homeSettingSet, row, unexpandedDefault(NIORD_HOME));
        String refusal = homeRefusal(home, this::tokenValue);
        if (refusal != null) {
            log.error(refusal);
            throw new IllegalStateException(refusal);
        }
    }

    /** The cure of a home read from the database's row or the settings file, whose tokens are expanded */
    static final String HOME_CURE = "set the environment variable " + HOME_VARIABLE
            + " or start with -Dniord.home=<folder>";

    /**
     * The cure of a home set by a {@code niord.home} system property or environment variable, which hides
     * {@code NIORD_HOME} even when it is blank ({@link #overrideOf})
     */
    static final String HOME_SETTING_CURE = "give the niord.home system property or environment variable a folder ("
            + HOME_VARIABLE + " is not read while niord.home is set)";

    /** The cure of a home set by {@code NIORD_HOME} itself */
    static final String HOME_VARIABLE_CURE = "give " + HOME_VARIABLE + " a folder";

    /**
     * The value of {@code niord.home} as a read takes it, its tokens not expanded, where it comes from and how to cure
     * it when it names no folder
     *
     * @param value    the value, null for none
     * @param origin   where it comes from, for the refusal's text
     * @param override whether it is an override ({@link #override}), which is used as it is, its tokens not expanded,
     *                 as {@link #get(Setting)} returns it
     * @param cure     what makes it name a folder, for the refusal's text
     */
    record HomeValue(String value, String origin, boolean override, String cure) {
    }

    /**
     * Returns the value of {@code niord.home} as a read takes it: the override ({@link #override}), else the
     * database's row, which wins over the settings file, else the settings file's value
     *
     * @param override       the override, or null
     * @param homeSettingSet whether a {@code niord.home} system property or environment variable is set, blank or
     *                       not: the override is then that, else it is {@code NIORD_HOME}
     * @param row            the database's row, or null
     * @param fileValue      the settings file's value, its tokens not expanded
     * @return the value, where it comes from and its cure
     */
    static HomeValue homeValue(String override, boolean homeSettingSet, Setting row, Object fileValue) {
        if (override != null) {
            return homeSettingSet
                    ? new HomeValue(override, "from the niord.home system property or environment variable", true,
                            HOME_SETTING_CURE)
                    : new HomeValue(override, "from " + HOME_VARIABLE, true, HOME_VARIABLE_CURE);
        }
        if (row != null) {
            return new HomeValue(row.getValue() instanceof String s ? s : null,
                    "from the database's niord.home setting", false, HOME_CURE);
        }
        return new HomeValue(fileValue instanceof String s ? s : null, "from the settings file", false, HOME_CURE);
    }

    /**
     * Returns why {@code niord.home} names no folder, as the text the start stops with, or null when it names one:
     * each {@code ${token}} of its value is expanded with {@code tokens}, and a token that resolves to nothing or to
     * blank text, or that is left in the value, is unresolved. The value is refused when a token is unresolved or
     * when it comes out blank. An override's tokens are not expanded: any token in it is unresolved, and the text says
     * so rather than that nothing sets it.
     *
     * @param home   the value, where it comes from and its cure
     * @param tokens the value of a token, or null when nothing sets it
     * @return the refusal's text, or null
     */
    static String homeRefusal(HomeValue home, Function<String, String> tokens) {
        String value = home.value() != null ? home.value() : "";
        Set<String> unresolved = new LinkedHashSet<>();
        SettingValueExpander expander = new SettingValueExpander(value);
        String token;
        int liveLockControlCount = 500;
        while ((token = expander.nextToken()) != null && liveLockControlCount-- > 0) {
            String resolved = home.override() ? null : tokens.apply(token);
            if (StringUtils.isBlank(resolved)) {
                unresolved.add(token);
                expander.replaceToken(token, "");
            } else {
                expander.replaceToken(token, resolved);
            }
        }
        String expanded = Objects.toString(expander.getValue(), "");
        Matcher left = SettingValueExpander.TOKEN_FORMAT.matcher(expanded);
        while (left.find()) {
            unresolved.add(left.group("token"));
        }
        if (unresolved.isEmpty() && StringUtils.isNotBlank(expanded)) {
            return null;
        }
        String cause = unresolved.isEmpty() ? ", which names no folder"
                : home.override() ? ", which is used as it is: a value set this way has its ${...} tokens not expanded"
                : unresolved.size() == 1 ? " and no " + unresolved.iterator().next() + " is set"
                : " and none of " + String.join(", ", unresolved) + " is set";
        return "niord.home cannot be resolved: its value is \"" + value + "\" (" + home.origin() + ")" + cause
                + "; " + home.cure();
    }

    /** The value of a token in niord.home, as {@link #expandSettingValue} finds it; null when it is unset or blank */
    private String tokenValue(String token) {
        Object setting = peek(token);
        if (setting != null && StringUtils.isNotBlank(setting.toString())) {
            return setting.toString();
        }
        String property = System.getProperty(token);
        if (StringUtils.isNotBlank(property)) {
            return property;
        }
        String variable = System.getenv(token);
        return StringUtils.isNotBlank(variable) ? variable : null;
    }

    /**
     * Calls the {@code @Setting} injection back for the fields it could not resolve through the database while the
     * context started: each now gets the database's value, not the file's or the annotation's. Runs right after {@link #init()} has
     * committed, before every other {@code ApplicationReadyEvent} listener. Not in a transaction of its own, so a
     * field that still fails costs no other field its value.
     */
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @EventListener(ApplicationReadyEvent.class)
    void reinjectDeferredSettings() {
        SettingsService self = beanFactory.getBean(SettingsService.class);
        beanFactory.getBeanProvider(SettingInjectionBeanPostProcessor.class)
                .ifAvailable(processor -> processor.reinjectDeferred(self));
    }

    /**
     * Reads the server's overlay again when the database's niord.home row names another folder; not when a
     * {@code niord.home} property or variable, or {@code NIORD_HOME}, is set, which wins over the row
     */
    private void readOverlayFromTheDatabasesHome() {
        if (override(NIORD_HOME.getKey()) != null) {
            return;
        }
        Setting row = em.find(Setting.class, NIORD_HOME.getKey());
        if (row == null || !(row.getValue() instanceof String value)) {
            return;
        }
        String folder = StringUtils.trimToNull(expandSettingValue(value));
        if (folder == null || sameFolder(folder, overlayFolder)) {
            return;
        }
        log.info("niord.home is {} in the database, not {}: reading the server's settings from there",
                folder, overlayFolder);
        overlayFolder = folder;
        defaults = withServerOverlay(fileDefaults, folder);
    }

    /**
     * Adds the operator settings of the catalogs that the database lacks, and records them in the
     * {@link DefaultsLedger}; never changes or deletes a row (docs/code-data-config-plan.md §5.3).
     *
     * <p>Takes the add-missing advisory lock first and reads the database and the ledger only then, so a second
     * start-up on the same database waits for the first and then finds nothing missing. Per operator key of the
     * catalogs:
     * <ul>
     *     <li>present in the database and not in the ledger: recorded as {@code adopted}, with the value's hash only
     *         when the row equals the default;</li>
     *     <li>missing while the ledger has it (a row removed by hand stays removed), or declared only by code that
     *         does not run on this node ({@link #declaringCodeRuns}): skipped;</li>
     *     <li>otherwise missing: added with its default value ({@link DefaultSettings#defaultValue}, null included)
     *         and the catalog's type, description and flags, and recorded with where the value came from and its
     *         hash.</li>
     * </ul>
     * A {@code state} key is neither added nor recorded. The rows go in one JDBC batch, the ledger rows in another.
     * A key is added whether or not a system property or environment variable overrides it: the override still wins
     * on every read.
     *
     * @return the number of settings added
     */
    @Transactional
    int addMissing() {
        long t0 = System.currentTimeMillis();
        DefaultSettings current = defaults;
        ledger.lockAddMissing();

        // The database's rows and the ledger's setting keys, read after the lock
        Map<String, Object> existing = new HashMap<>();
        em.createQuery("select s from Setting s", Setting.class)
                .getResultList()
                .forEach(s -> existing.put(s.getKey(), s.getValue()));
        Set<String> ledgered = ledger.loadSettingKeys();

        ClassLoader cl = SettingsService.class.getClassLoader();
        List<Setting> added = new ArrayList<>();
        List<DefaultsLedger.Row> rows = new ArrayList<>();
        int adopted = 0;
        for (SettingsCatalog.Entry entry : current.catalog().values()) {
            if (entry.scope() != Setting.Scope.operator) {
                continue;
            }
            String key = entry.key();
            Object value = current.defaultValue(key);
            if (existing.containsKey(key)) {
                if (!ledgered.contains(key)) {
                    boolean equal = Objects.equals(SettingsCatalog.jsonValue(existing.get(key)), value);
                    rows.add(DefaultsLedger.Row.setting(key, equal ? hash(value) : null,
                            DefaultsLedger.SOURCE_ADOPTED));
                    adopted++;
                }
                continue;
            }
            if (ledgered.contains(key) || !declaringCodeRuns(entry, cl, beanFactory)) {
                continue;
            }
            added.add(new Setting(key, value, entry.type(), entry.description(), entry.cached(), entry.web(),
                    entry.editable()));
            rows.add(DefaultsLedger.Row.setting(key, hash(value), current.source(key)));
        }

        insertAll(added);
        ledger.insertAll(rows);

        Set<String> passwordKeys = passwordKeys(current.passwordKeys());
        added.forEach(s -> log.info("Added setting {} = {} ({})", s.getKey(), loggableValue(s, passwordKeys),
                current.source(s.getKey())));
        if (!added.isEmpty() || adopted > 0) {
            log.info(String.format("Added %d and adopted %d default settings in %d ms",
                    added.size(), adopted, System.currentTimeMillis() - t0));
        }

        // A read before the add cached the default; the row holds the same value, but read it from now on
        List<String> addedKeys = added.stream().map(Setting::getKey).toList();
        if (!addedKeys.isEmpty()) {
            DefaultsLedger.afterCommit(() -> addedKeys.forEach(this::evictFromCache));
        }
        return added.size();
    }

    /** Writes the settings in one JDBC batch on the transaction's connection; a key that exists is skipped */
    private void insertAll(List<Setting> settings) {
        if (settings.isEmpty()) {
            return;
        }
        JpaJsonAttributeConverter converter = new JpaJsonAttributeConverter();
        em.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT_SETTING)) {
                for (Setting s : settings) {
                    bindRow(ps, s, converter);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    private static void bindRow(PreparedStatement ps, Setting s, JpaJsonAttributeConverter converter)
            throws java.sql.SQLException {
        ps.setString(1, s.getKey());
        ps.setBoolean(2, s.isCached());
        ps.setString(3, s.getDescription());
        ps.setBoolean(4, s.isEditable());
        ps.setString(5, s.getType() == null ? null : s.getType().name());
        ps.setString(6, converter.convertToDatabaseColumn(s.getValue()));
        ps.setBoolean(7, s.isWeb());
    }

    /**
     * Returns whether the code that declares the key runs on this node, so start-up may add the key: when any of the
     * declaring classes loads and either is no Spring component (a holder of constants such as
     * {@link FrontendSettings}, or {@code WmsProxyServlet}, which is created on its first request) or has a bean
     * definition in the context (prototype and lazy beans count). A catalog without {@code declaredIn} counts as
     * running. So a node whose SECOM configuration is switched off adds no SECOM setting, as before.
     *
     * @param entry the key's catalog entry
     * @param cl    the class loader of the declaring classes
     * @param bf    the context's bean factory
     * @return whether the declaring code runs
     */
    static boolean declaringCodeRuns(SettingsCatalog.Entry entry, ClassLoader cl, ListableBeanFactory bf) {
        if (entry.declaredIn().isEmpty()) {
            return true;
        }
        for (String name : entry.declaredIn()) {
            Class<?> type;
            try {
                type = ClassUtils.forName(name, cl);
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            if (!isSpringComponent(type)) {
                return true;
            }
            String[] beans = bf.getBeanNamesForType(type, true, false);
            if (beans != null && beans.length > 0) {
                return true;
            }
        }
        return false;
    }

    /** Whether the class is a Spring component: Spring creates it only as a bean, when its conditions hold */
    private static boolean isSpringComponent(Class<?> type) {
        MergedAnnotations annotations = MergedAnnotations.from(type, SearchStrategy.TYPE_HIERARCHY);
        return annotations.isPresent(Component.class) || annotations.isPresent(jakarta.inject.Named.class);
    }

    /** The sha-256 of a value's JSON form */
    private static String hash(Object value) {
        return DefaultTexts.sha256(String.valueOf(new JpaJsonAttributeConverter().convertToDatabaseColumn(value)));
    }

    /** Returns the defaults with the server's {@code niord.json} in the folder over them, when it exists */
    private static DefaultSettings withServerOverlay(DefaultSettings defaults, String folder) {
        if (folder == null) {
            return defaults;
        }
        Path file;
        try {
            file = Paths.get(folder, SERVER_SETTINGS_FILE);
        } catch (InvalidPathException e) {
            log.warn("niord.home {} is not a folder: no server settings read", folder);
            return defaults;
        }
        log.info("Expecting the server's settings file at {}", file.toAbsolutePath());
        if (!Files.isRegularFile(file)) {
            return defaults;
        }
        try (InputStream in = Files.newInputStream(file)) {
            log.info("Reading the server's settings file {}", file.toAbsolutePath());
            return defaults.withOverlay(file, DefaultSettings.readLegacy(file.toString(), in));
        } catch (IOException | RuntimeException e) {
            // Stop the application starting up
            throw new IllegalStateException("Error loading settings from " + file, e);
        }
    }

    private static boolean sameFolder(String a, String b) {
        if (b == null) {
            return false;
        }
        try {
            return Paths.get(a).toAbsolutePath().normalize().equals(Paths.get(b).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return a.equals(b);
        }
    }

    /**
     * Records that a {@code @Setting} field declares the key Password-typed, so {@link #loggableValue} masks its
     * value whatever a settings file says. Called by {@link SettingInjectionBeanPostProcessor} as it injects the
     * field.
     *
     * @param key the setting key
     */
    static void registerDeclaredPasswordKey(String key) {
        if (key != null) {
            DECLARED_PASSWORD_KEYS.add(key);
        }
    }

    /**
     * Returns the keys whose values must not be logged: the given ones, which the catalogs declare Password-typed,
     * and those an {@code @Setting} field declares Password-typed.
     *
     * @param catalogPasswordKeys the keys the catalogs declare Password-typed
     * @return the keys whose values must not be logged
     */
    static Set<String> passwordKeys(Collection<String> catalogPasswordKeys) {
        Set<String> keys = new HashSet<>(DECLARED_PASSWORD_KEYS);
        keys.addAll(catalogPasswordKeys);
        return keys;
    }

    /**
     * Returns the value of the setting as it may be logged: masked when the setting is Password-typed or
     * its key is one of the given password keys, as it is otherwise.
     *
     * @param setting the setting
     * @param passwordKeys the keys whose values must not be logged
     * @return the loggable value
     */
    static String loggableValue(Setting setting, Set<String> passwordKeys) {
        if (setting.getType() == Setting.Type.Password || passwordKeys.contains(setting.getKey())) {
            return MASKED_VALUE;
        }
        return String.valueOf(setting.getValue());
    }

    /**
     * Returns the catalog entry of the key: its type, scope and flags
     *
     * @param key the setting key
     * @return the entry, or null when no catalog declares the key
     */
    public SettingsCatalog.Entry catalogEntry(String key) {
        return defaults.entry(key);
    }

    /**
     * Returns all settings that are emitted to the web applications ({@code /rest/settings/web}): the rows marked web
     * whose key a settings catalog declares, as {@link #getAllEditable()} does. A legacy row no code declares, such as
     * {@code faMrnPrefix}, {@code frontPageMessageSeries}, {@code lightCharacterUrl} and {@code numeralLanguages} in
     * DK's and GL's databases, is left out (owner, 2026-11-16); it stays in the database untouched.
     *
     * @return all settings that are emitted to the web applications
     */
    @Transactional
    public List<Setting> getAllForWeb() {
        return em.createNamedQuery("Setting.findAllForWeb", Setting.class)
                .getResultList()
                .stream()
                .filter(setting -> catalogEntry(setting.getKey()) != null)
                .toList();
    }

    /**
     * Returns all settings that are editable on the Settings admin page: the rows whose key a settings catalog
     * declares editable (owner, 2026-10-21; docs/code-data-config-plan.md §5.6). Whether a key is editable is the
     * catalog's answer, the code's current one, not the row's {@code editable} column, which keeps what it was first
     * written with: so a key that became deployment configuration leaves the page on a restored database too, and the
     * row is not changed. A row no code declares, such as the eight legacy rows in DK's and GL's databases
     * ({@code authServerRealmKey}, {@code authServerSslRequired}, {@code faMrnPrefix}, {@code frontPageMessageSeries},
     * {@code lightCharacterUrl}, {@code numeralLanguages}, {@code wmsLogin}, {@code wmsServiceName}), is left out; it
     * stays in the database untouched.
     *
     * @return all settings that are editable on the Settings admin page
     */
    @Transactional
    public List<Setting> getAllEditable() {
        return em.createQuery("select s from Setting s", Setting.class)
                .getResultList()
                .stream()
                .filter(setting -> {
                    SettingsCatalog.Entry entry = catalogEntry(setting.getKey());
                    return entry != null && entry.editable();
                })
                .toList();
    }

    /**
     * Looks up the persisted setting with the given key, or returns null if it does not exist.
     * Unlike {@link #get(Setting)}, this never consults system properties or environment variables
     * or the default settings.
     *
     * @param key the setting key
     * @return the persisted setting, or null if undefined
     */
    @Transactional
    public Setting getSetting(String key) {
        Objects.requireNonNull(key, "Must specify valid setting key");
        return em.find(Setting.class, key);
    }

    /**
     * Returns the value associated with the setting.
     * A setting the database lacks resolves to its default ({@link #resolveDefault}) and is never created.
     *
     * @param key the setting key
     * @return the associated value
     */
    public Object get(String key) {
        return get(new Setting(key));
    }

    /**
     * Returns the value associated with the setting: a system property or environment variable named like it (for
     * {@code niord.home}, else {@code NIORD_HOME}: {@link #override}), else the database row, else its default
     * ({@link #resolveDefault}). A number row left empty reads as the default too ({@link #rowValue}). A read never
     * writes: a setting the database lacks is not created, and its default is cached like a row.
     *
     * @param setting the source
     * @return the associated value
     */
    @Transactional
    public Object get(Setting setting) {
        Objects.requireNonNull(setting, "Must specify valid setting");

        // If a corresponding system or environment property is set, it takes precedence
        String override = override(setting.getKey());
        if (override != null) {
            return override;
        }

        // Look for a cached value
        CacheElement<Object> value = settingsCache.getCache().get(setting.getKey());

        // No cached value
        if (value == null) {
            Setting result = em.find(Setting.class, setting.getKey());
            value = new CacheElement<>(result != null ? rowValue(result, setting) : unexpandedDefault(setting));

            // Cache it.
            if (setting.isCached()) {
                settingsCache.getCache().put(setting.getKey(), value);
            }
        }

        // Check if we need to substitute with system properties. Only applies to String-based settings.
        Object result = value.getElement();
        if (result instanceof String s) {
            result = expandSettingValue(s);
        }

        return result;
    }

    /**
     * Returns the value a setting the database lacks resolves to, expanded without the database: for a key a catalog
     * declares, the settings file's value, else the base product's, else the code default
     * ({@link DefaultSettings#defaultValue}); for any other key, the template's value. Reads no database, so the
     * {@code @Setting} injection uses it while the database cannot be read yet.
     *
     * @param setting the setting, with its own default as the value
     * @return the default value
     */
    public Object resolveDefault(Setting setting) {
        Object value = unexpandedDefault(setting);
        return value instanceof String s ? expandWithoutDatabase(s, defaults) : value;
    }

    /** The default of a setting the database lacks, its tokens not expanded; a copy, as a row read would be */
    private Object unexpandedDefault(Setting setting) {
        return defaults.entry(setting.getKey()) != null
                ? defaults.defaultValue(setting.getKey())
                : setting.getValue();
    }

    /**
     * The value a database row gives a read: its own, except that a number setting left empty (an Integer, Long,
     * Float or Double row, by the row's type, else the catalog's, whose value is null) reads as its default, as a
     * setting the database lacks does and as the {@code @Setting} injection already did at the next start (the
     * owner's ruling). Any other type keeps an empty value as meant.
     *
     * @param row     the database row
     * @param setting the setting read, with its own default as the value
     * @return the value of the read, its tokens not expanded
     */
    private Object rowValue(Setting row, Setting setting) {
        Object value = row.getValue();
        if (value != null) {
            return value;
        }
        Setting.Type type = row.getType();
        if (type == null) {
            SettingsCatalog.Entry entry = defaults.entry(row.getKey());
            type = entry != null ? entry.type() : null;
        }
        return type == Setting.Type.Integer || type == Setting.Type.Long
                || type == Setting.Type.Float || type == Setting.Type.Double
                ? unexpandedDefault(setting)
                : null;
    }

    /**
     * Returns the system property, or failing that the environment variable, named like the setting:
     * the value {@link #get(Setting)} answers with instead of the stored one. For {@code niord.home} only, when
     * neither is set, the {@code NIORD_HOME} system property, else environment variable, when not blank: the home
     * follows {@code NIORD_HOME} ahead of the database row, as the countries' token {@code ${NIORD_HOME}} already
     * made it. Null when none is set.
     *
     * @param key the setting key
     * @return the overriding value, or null
     */
    public String override(String key) {
        return overrideOf(key);
    }

    /**
     * Returns the system property, else the environment variable, named like the key, a blank value included; for
     * {@code niord.home}, when neither is set, the {@code NIORD_HOME} system property, else environment variable,
     * each only when not blank. No other key gains an upper-case alias.
     *
     * @param key the setting key
     * @return the overriding value, or null
     */
    static String overrideOf(String key) {
        String value = System.getProperty(key);
        if (value == null) {
            value = System.getenv(key);
        }
        if (value == null && NIORD_HOME.getKey().equals(key)) {
            value = StringUtils.trimToNull(System.getProperty(HOME_VARIABLE));
            if (value == null) {
                value = StringUtils.trimToNull(System.getenv(HOME_VARIABLE));
            }
        }
        return value;
    }

    /**
     * Returns the value associated with the setting, bypassing the cache: a system property or environment variable
     * named like it (for {@code niord.home}, else {@code NIORD_HOME}: {@link #override}), else the database row,
     * else, for a key a catalog declares, its default; a number row left empty reads as that default too
     * ({@link #rowValue}). Never creates the setting; null for a key that has no row and that no catalog declares.
     *
     * @param key the setting key
     * @return the associated value
     */
    @Transactional
    public Object peek(String key) {
        Objects.requireNonNull(key, "Must specify valid setting key");

        // If a corresponding system property or environment variable is set, it takes precedence. Values are not
        // logged: a deployment passes its secrets this way.
        String override = overrideOf(key);
        if (override != null) {
            log.debug("Peek: {} is a system property or environment variable", key);
            return override;
        }

        Setting setting = em.find(Setting.class, key);
        Object result = setting != null ? rowValue(setting, new Setting(key)) : unexpandedDefault(new Setting(key));

        // Check if we need to substitute with system properties. Only applies to String-based settings.
        if (result instanceof String s) {
            result = expandSettingValue(s);
        }

        log.debug("Peek: {} is a {}", key, setting != null ? "stored setting" : "default setting");
        return result;
    }

    /**
     * Replace any nested token with the format "${token}" with either the setting with the given name
     * or with a System property with the given name.
     *
     * @param value the value to expand
     * @return the expanded value
     */
    private String expandSettingValue(String value) {
        SettingValueExpander valueExpander = new SettingValueExpander(value);
        String token;
        int liveLockControlCount = 500;
        while ((token = valueExpander.nextToken()) != null) {
            if (liveLockControlCount--<0) {
                throw new RuntimeException("Livelock encountered for key " + value);
            }
            Object setting = peek(token);
            if (setting != null) {
                valueExpander.replaceToken(token, setting.toString());
                continue;
            }
            String sysProp = System.getProperty(token);
            if (StringUtils.isNotBlank(sysProp)) {
                valueExpander.replaceToken(token, sysProp);
                continue;
            }

            sysProp = System.getenv(token);
            if (StringUtils.isNotBlank(sysProp)) {
                valueExpander.replaceToken(token, sysProp);
                continue;
            }

            valueExpander.replaceToken(token, "");
        }
        return valueExpander.getValue();
    }

    /**
     * Expands each "${token}" like {@link #expandSettingValue}, but without the database: a system property or an
     * environment variable named like the token (for {@code niord.home}, else {@code NIORD_HOME}: {@link #overrideOf}),
     * else the token's default setting, else the empty string.
     *
     * @param value    the value to expand
     * @param defaults the default settings
     * @return the expanded value
     */
    private static String expandWithoutDatabase(String value, DefaultSettings defaults) {
        SettingValueExpander valueExpander = new SettingValueExpander(value);
        String token;
        int liveLockControlCount = 500;
        while ((token = valueExpander.nextToken()) != null) {
            if (liveLockControlCount-- < 0) {
                throw new RuntimeException("Livelock encountered for key " + value);
            }
            String replacement = overrideOf(token);
            if (replacement == null && defaults.defaultValue(token) != null) {
                replacement = defaults.defaultValue(token).toString();
            }
            valueExpander.replaceToken(token, replacement != null ? replacement : "");
        }
        return valueExpander.getValue();
    }

    /**
     * Updates the database value of an operator setting: what the Settings page and the settings import write
     * (docs/code-data-config-plan.md §5.3). Refused with an {@link IllegalArgumentException}, which the REST layer
     * answers with 400, for a key no catalog declares (also one that has a row), a {@code state} key, a key the
     * catalog does not make editable (deployment configuration, whatever its row's {@code editable} column says), a
     * key the database has no row for, a value that does not fit the row's type (else the catalog's), a null or
     * blank value for a {@code required} key (a row wins over the settings file, so such a row would read null), and a
     * value the code that reads the setting cannot use ({@link #valueRefusal}).
     *
     * <p>A saved setting takes effect at once. The save adds one to {@link SettingsRefresher#CHANGE_COUNT}, which the
     * other nodes poll, and once it has committed the {@code @Setting} fields on this node take the new
     * value ({@link SettingsRefresher#refreshSaved}).
     *
     * @param template the setting to update
     * @return the updated setting
     */
    @Transactional
    public Setting set(Setting template) {
        String key = template.getKey();
        SettingsCatalog.Entry entry = defaults.entry(key);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown setting " + key + ": no catalog declares it");
        }
        if (entry.scope() == Setting.Scope.state) {
            throw new IllegalArgumentException(key + " is a state setting: only the system writes it");
        }
        if (!entry.editable()) {
            throw new IllegalArgumentException(key + " is deployment configuration, not saved here: the deployment "
                    + "sets it by an environment variable or system property of this name, or the settings file");
        }
        Setting setting = em.find(Setting.class, key);
        if (setting == null) {
            throw new IllegalArgumentException("Non-existing setting " + key);
        }
        Setting.Type type = setting.getType() != null ? setting.getType() : entry.type();
        if (type != null && !SettingsCatalog.hasType(type, template.getValue())) {
            throw new IllegalArgumentException(key + " is " + type + "-typed and takes "
                    + SettingsCatalog.shape(type));
        }
        if (entry.required() && isBlank(template.getValue())) {
            throw new IllegalArgumentException(key + " is required: it takes a value, not an empty one");
        }
        String refusal = valueRefusal(key, template.getValue());
        if (refusal != null) {
            throw new IllegalArgumentException(refusal);
        }

        // Noted before the commit, so a poll that meets the committed value before refreshSaved still publishes the
        // change as this node's
        beanFactory.getBeanProvider(SettingsRefresher.class).ifAvailable(refresher -> refresher.savingHere(key));

        // Update the DB
        setting.setValue(template.getValue());
        setting = em.merge(setting);

        // Count the save, for the other nodes' poll: the row is locked until the save commits, so two saves at once
        // count two
        Setting count = em.find(Setting.class, SettingsRefresher.CHANGE_COUNT.getKey(), LockModeType.PESSIMISTIC_WRITE);
        Long countBefore = count != null ? SettingsRefresher.toLong(count.getValue()) : null;
        long countAfter = (countBefore != null ? countBefore : 0L) + 1;
        writeState(SettingsRefresher.CHANGE_COUNT, countAfter);

        // Invalidate the cache now and again once the write commits: a read on another thread before the commit fills
        // the cache again from the old row (SETTINGS-9). Then the fields that hold the setting take the new value
        evictFromCache(key);
        DefaultsLedger.afterCommit(() -> {
            evictFromCache(key);
            try {
                beanFactory.getBeanProvider(SettingsRefresher.class)
                        .ifAvailable(refresher -> refresher.refreshSaved(key, countBefore, countAfter));
            } catch (RuntimeException e) {
                // The save has committed; the next poll reads what this refresh did not
                log.warn("The fields of setting {} were not refreshed after its save: {}", key, e.toString());
            }
        });

        return setting;
    }

    /**
     * Returns why a save may not give a setting the value, by the checks of the code that reads it
     * ({@link SettingValueCheck}): what {@link #set} refuses and the settings import skips. The value has the setting's
     * type.
     *
     * @param key   the setting's key
     * @param value the value
     * @return why the value is refused, or null when it may be saved
     */
    public String valueRefusal(String key, Object value) {
        return beanFactory.getBeanProvider(SettingValueCheck.class).orderedStream()
                .map(check -> check.refusal(key, value))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns whether a value is null or a blank string, which a {@code required} key may not hold
     *
     * @param value the value
     * @return whether it is null or blank
     */
    public static boolean isBlank(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    /**
     * Writes the value of a {@code state} setting, a value only the system writes (docs/code-data-config-plan.md
     * §5.3): the row is created with the catalog's type, description and flags the first time, and only its value
     * changes afterwards. An upsert, so two nodes writing at once do not collide.
     *
     * @param stateSetting the state setting
     * @param value        the value
     * @throws IllegalArgumentException when no catalog declares the key a {@code state} setting
     */
    @Transactional
    public void writeState(Setting stateSetting, Object value) {
        String key = stateSetting.getKey();
        SettingsCatalog.Entry entry = defaults.entry(key);
        if (entry == null || entry.scope() != Setting.Scope.state) {
            throw new IllegalArgumentException(key + " is not a state setting");
        }
        Setting setting = new Setting(key, value, entry.type(), entry.description(), entry.cached(), entry.web(),
                entry.editable());
        em.flush();
        JpaJsonAttributeConverter converter = new JpaJsonAttributeConverter();
        em.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(UPSERT_STATE)) {
                bindRow(ps, setting, converter);
                ps.executeUpdate();
            }
        });
        Setting managed = em.find(Setting.class, key);
        if (managed != null) {
            em.refresh(managed);
        }
        evictFromCache(key);
        DefaultsLedger.afterCommit(() -> evictFromCache(key));
    }

    /** Evicts any setting with the given key from the cache **/
    public void evictFromCache(String key) {
        settingsCache.getCache().remove(key);
    }

    /**
     * Updates the database value of the given setting, see {@link #set(Setting)}
     * @param key the key of the setting to update
     * @param value the value of the setting to update
     * @return the updated setting
     */
    public Setting set(String key, Object value) {
        return set(new Setting(key, value));
    }

    /**
     * Returns the setting as a String
     *
     * @param setting the source
     * @return the associated value
     */
    public String getString(Setting setting) {
        Object value = get(setting);
        return value == null
                ? null
                : (value instanceof String s) ? s : value.toString();
    }

    public String getString(String key) {
        return getString(new Setting(key));
    }

    /**
     * Returns the setting as a boolean, read by {@link #toBoolean}: a system property or an environment variable
     * arrives as a string ({@link #get(Setting)}), so {@code false} switches off a setting whose default is true
     *
     * @param setting the source
     * @return the associated value, or null when it is neither true nor false
     */
    public Boolean getBoolean(Setting setting) {
        return toBoolean(get(setting));
    }

    public Boolean getBoolean(String key) {
        return getBoolean(new Setting(key));
    }

    /**
     * Reads a setting's value as a boolean: a {@link Boolean} as it is; any other value trimmed and without regard to
     * case, {@code true}, {@code yes}, {@code t} and {@code y} as true and {@code false}, {@code no}, {@code f} and
     * {@code n} as false. Anything else, blank included, is null, so a typo keeps the caller's default.
     *
     * @param value the value
     * @return the boolean, or null when the value is neither true nor false
     */
    public static Boolean toBoolean(Object value) {
        if (value != null) {
            if (value instanceof Boolean b) {
                return b;
            }
            switch(value.toString().trim().toLowerCase()) {
                case "true":
                case "yes":
                case "t" :
                case "y":
                    return true;
                case "false":
                case "no":
                case "f":
                case "n":
                    return false;
            }
        }
        return null;
    }

    /**
     * Returns the setting as a long
     *
     * @param setting the source
     * @return the associated value
     */
    public Long getLong(Setting setting) {
        Object value = get(setting);
        if (value != null) {
            if (value instanceof Number n) {
                return n.longValue();
            }
            return Long.valueOf(value.toString());
        }
        return null;
    }

    public Long getLong(String key) {
        return getLong(new Setting(key));
    }

    /**
     * Returns the setting as an integer
     *
     * @param setting the source
     * @return the associated value
     */
    public Integer getInteger(Setting setting) {
        Object value = get(setting);
        if (value != null) {
            if (value instanceof Number n) {
                return n.intValue();
            }
            return Integer.valueOf(value.toString());
        }
        return null;
    }

    public Integer getInteger(String key) {
        return getInteger(new Setting(key));
    }

    /**
     * Returns the setting as a Double
     *
     * @param setting the source
     * @return the associated value
     */
    public Double getDouble(Setting setting) {
        Object value = get(setting);
        if (value != null) {
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            return Double.valueOf(value.toString());
        }
        return null;
    }

    public Double getDouble(String key) {
        return getDouble(new Setting(key));
    }

    /**
     * Returns the setting as a Float
     *
     * @param setting the source
     * @return the associated value
     */
    public Float getFloat(Setting setting) {
        Object value = get(setting);
        if (value != null) {
            if (value instanceof Number n) {
                return n.floatValue();
            }
            return Float.valueOf(value.toString());
        }
        return null;
    }

    public Float getFloat(String key) {
        return getFloat(new Setting(key));
    }

    /**
     * Returns the setting as a Path
     *
     * @param setting the source
     * @return the associated value
     */
    public Path getPath(Setting setting) {
        String value = getString(setting);
        return value == null ? null : Paths.get(value);
    }

    public Path getPath(String key) {
        return getPath(new Setting(key));
    }

    public void setPath(String key, Path path) {
        set(key, path == null ? null : path.toAbsolutePath().toString());
    }

    /**
     * Returns the setting as a Date
     *
     * @param setting the source
     * @return the associated value
     */
    public Date getDate(Setting setting) {
        Long value = getLong(setting);
        return value == null ? null : new Date(value);
    }

    public Date getDate(String key) {
        return getDate(new Setting(key));
    }

    public void setDate(String key, Date date) {
        set(key, date == null ? null : date.getTime());
    }

    /**
     * Returns the setting from a JSON representation
     *
     * @param setting the source
     * @return the associated value
     */
    public <T> T getFromJson(Setting setting, Class<T> dataClass) {
        try {
            return JsonUtils.fromJson(JsonUtils.toJson(get(setting)), dataClass);
        } catch (IOException e) {
            return null;
        }
    }

    public <T> T getFromJson(String key, Class<T> dataClass) {
        return getFromJson(new Setting(key), dataClass);
    }

    public void setAsJson(String key, Object data) {
        try {
            set(key, JsonUtils.toJson(data));
        } catch (IOException ignored) {
        }
    }
}

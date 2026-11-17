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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import freemarker.template.Configuration;

import java.io.Reader;
import java.io.StringWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Framework-neutral Tier-1 unit tests for {@link FmTemplateLoader}, the Freemarker
 * {@link freemarker.cache.TemplateLoader} backed by the {@code ScriptResource} database table via a
 * {@link ScriptResourceService}, and by nothing else: since C4.2a no template is read from the class path.
 * <p>
 * The tests exercise the loader's observable {@code TemplateLoader} contract directly against an
 * in-memory stub of {@link ScriptResourceService} ({@link StubResourceService}), which overrides only
 * the two methods the loader invokes ({@code findAllScriptResourcePaths} and {@code findByPath}). The
 * stub's constructor arguments stay null because the overridden methods never touch them, so no Spring,
 * database or Docker is required.
 * <p>
 * Verified behaviours: resolving an existing template path to its {@code ScriptResource}; reporting the
 * source's last-modified time from {@code getUpdated()}; streaming the source's content through
 * {@code getReader}; returning {@code null} for a path with no row, without a per-path query; the
 * lazy-and-cached lookup of {@code findByPath} (queried at most once per path); the no-op
 * {@code closeTemplateSource}; and the localized lookup with its default-language fallback.
 */
public class FmTemplateLoaderTest {

    @Test
    void englishLocalizedLookupReachesTheBaseTemplateBeforeDanishFallback() throws Exception {
        ScriptResource base = sampleResource();
        base.setPath("templates/example.ftl");
        base.setContent("English base");
        ScriptResource danish = sampleResource();
        danish.setPath("templates/example_da.ftl");
        danish.setContent("Danish fallback");
        var resources = new StubResourceService(Map.of(base.getPath(), base, danish.getPath(), danish));
        var loader = new FmTemplateLoader(resources, "da");
        Assertions.assertNull(loader.findTemplateSource("templates/example_en.ftl"));
        var configuration = new Configuration(Configuration.VERSION_2_3_31);
        configuration.setTemplateLoader(loader);
        configuration.setLocale(Locale.ENGLISH);
        var output = new StringWriter();
        configuration.getTemplate(base.getPath()).process(Map.of(), output);
        Assertions.assertEquals("English base", output.toString());
    }

    @Test
    void filenameWordsAreNotTreatedAsLanguageSuffixes() {
        ScriptResource resource = sampleResource();
        resource.setPath("templates/example_da.ftl");
        var loader = new FmTemplateLoader(stubWithSample(resource), "da");
        Assertions.assertNull(loader.findTemplateSource("templates/example_pdf.ftl"));
    }

    @Test
    void missingLocalizedTemplateUsesConfiguredDefault() throws Exception {
        ScriptResource resource = sampleResource();
        resource.setPath("templates/example_da.ftl");
        var loader = new FmTemplateLoader(stubWithSample(resource), "da");
        Assertions.assertSame(resource, loader.findTemplateSource("templates/example_kl.ftl"));
        Assertions.assertNull(loader.findTemplateSource("templates/absent_kl.ftl"));
    }

    /**
     * Stub of {@link ScriptResourceService} overriding only the methods {@link FmTemplateLoader}
     * calls. {@code findByPathCalls} records how many times the loader queried the backing table.
     */
    static class StubResourceService extends ScriptResourceService {
        final Map<String, ScriptResource> byPath;
        int findByPathCalls = 0;

        StubResourceService(Map<String, ScriptResource> byPath) {
            // The refactor moved the former @Inject fields (userService, app) into a package-private
            // constructor. The test never touched them, so they stay null exactly as before.
            super(null, null);
            this.byPath = byPath;
        }

        @Override
        public Set<String> findAllScriptResourcePaths() {
            return new HashSet<>(byPath.keySet());
        }

        @Override
        public ScriptResource findByPath(String path) {
            findByPathCalls++;
            return byPath.get(path);
        }
    }

    /** Builds a ScriptResource with path "path/a.ftl", content "Hello" and updated == new Date(123456L). */
    private static ScriptResource sampleResource() {
        ScriptResource res = new ScriptResource();
        res.setPath("path/a.ftl");
        res.setContent("Hello");
        res.setUpdated(new Date(123456L)); // setUpdated is inherited from VersionedEntity
        return res;
    }

    /** Builds a stub seeded with the single sample resource keyed by its path. */
    private static StubResourceService stubWithSample(ScriptResource res) {
        Map<String, ScriptResource> byPath = new HashMap<>();
        byPath.put(res.getPath(), res);
        return new StubResourceService(byPath);
    }

    /** Reads a Reader fully into a String. */
    private static String readFully(Reader reader) throws Exception {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[256];
        int n;
        while ((n = reader.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    @Test
    public void findTemplateSource_existingPath_returnsBackingResource() {
        // An existing path resolves to the exact ScriptResource held by the backing service.
        ScriptResource res = sampleResource();
        FmTemplateLoader loader = new FmTemplateLoader(stubWithSample(res));

        Object source = loader.findTemplateSource("path/a.ftl");

        Assertions.assertSame(res, source);
    }

    @Test
    public void getLastModified_returnsUpdatedTimestampOfSource() {
        // getLastModified reports the source's getUpdated().getTime() value exactly.
        ScriptResource res = sampleResource();
        FmTemplateLoader loader = new FmTemplateLoader(stubWithSample(res));

        Object source = loader.findTemplateSource("path/a.ftl");

        Assertions.assertEquals(123456L, loader.getLastModified(source));
    }

    @Test
    public void getReader_yieldsReaderOverSourceContent() throws Exception {
        // getReader streams the full content of the resolved source ("Hello").
        ScriptResource res = sampleResource();
        FmTemplateLoader loader = new FmTemplateLoader(stubWithSample(res));

        Object source = loader.findTemplateSource("path/a.ftl");
        Reader reader = loader.getReader(source, "UTF-8");

        Assertions.assertEquals("Hello", readFully(reader));
    }

    @Test
    public void findTemplateSource_unknownPath_returnsNull() {
        // An unknown path returns null when it is absent from the table.
        ScriptResource res = sampleResource();
        FmTemplateLoader loader = new FmTemplateLoader(stubWithSample(res));

        Assertions.assertNull(loader.findTemplateSource("unknown/x.ftl"));
    }

    @Test
    public void findTemplateSource_existingPath_isLazyAndCached_queriesByPathOnce() {
        // The loader loads an existing template lazily and caches it: findByPath is invoked only once.
        ScriptResource res = sampleResource();
        StubResourceService stub = stubWithSample(res);
        FmTemplateLoader loader = new FmTemplateLoader(stub);

        Object first = loader.findTemplateSource("path/a.ftl");
        Object second = loader.findTemplateSource("path/a.ftl");

        Assertions.assertSame(res, first);
        Assertions.assertSame(res, second);
        Assertions.assertEquals(1, stub.findByPathCalls);
    }

    @Test
    public void constructor_doesNotEagerlyQueryByPath() {
        // Construction only enumerates paths; no per-path findByPath query happens until a lookup.
        ScriptResource res = sampleResource();
        StubResourceService stub = stubWithSample(res);

        new FmTemplateLoader(stub);

        Assertions.assertEquals(0, stub.findByPathCalls);
    }

    @Test
    public void closeTemplateSource_isNoOp_doesNotThrow() {
        // closeTemplateSource is a no-op and must not throw for a real source.
        ScriptResource res = sampleResource();
        FmTemplateLoader loader = new FmTemplateLoader(stubWithSample(res));

        Object source = loader.findTemplateSource("path/a.ftl");
        loader.closeTemplateSource(source);
        // No exception means the no-op behaviour holds; assert the source was resolvable as a guard.
        Assertions.assertSame(res, source);
    }

    @Test
    public void findTemplateSource_pathWithNoRow_resolvesToNullAndIsNeverLookedUpByPath() {
        // A path with no row resolves to null, again and again, and is never queried by path: the
        // table's paths, read once when the loader is made, already say it is not there.
        StubResourceService stub = new StubResourceService(new HashMap<>());
        FmTemplateLoader loader = new FmTemplateLoader(stub);

        Assertions.assertNull(loader.findTemplateSource("missing/y.ftl"));
        Assertions.assertNull(loader.findTemplateSource("missing/y.ftl"));
        Assertions.assertEquals(0, stub.findByPathCalls);
    }
}

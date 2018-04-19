/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.api.vm;

import com.oracle.truffle.api.CompilerAsserts;
import static com.oracle.truffle.api.vm.VMAccessor.INSTRUMENT;
import static com.oracle.truffle.api.vm.VMAccessor.LANGUAGE;
import static com.oracle.truffle.api.vm.VMAccessor.NODES;
import static com.oracle.truffle.api.vm.VMAccessor.SPI;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.graalvm.options.OptionDescriptors;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Instrument;
import org.graalvm.polyglot.Language;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.InstrumentInfo;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.impl.DispatchOutputStream;
import com.oracle.truffle.api.instrumentation.ContextsListener;
import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventListener;
import com.oracle.truffle.api.instrumentation.Instrumenter;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.ThreadsListener;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.nodes.Node;
import org.graalvm.polyglot.io.FileSystem;

@SuppressWarnings("deprecation")
class PolyglotEngineImpl extends org.graalvm.polyglot.impl.AbstractPolyglotImpl.AbstractEngineImpl implements com.oracle.truffle.api.vm.PolyglotImpl.VMObject {

    /**
     * Context index for the host language.
     */
    static final int HOST_LANGUAGE_INDEX = 0;
    static final String HOST_LANGUAGE_ID = "host";
    private static final Set<String> RESERVED_IDS = new HashSet<>(
                    Arrays.asList(HOST_LANGUAGE_ID, "graal", "truffle", "engine", "language", "instrument", "graalvm", "context", "polyglot", "compiler", "vm"));

    private static final Map<PolyglotEngineImpl, Void> ENGINES = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean shutdownHookInitialized = false;
    private static final boolean DEBUG_MISSING_CLOSE = Boolean.getBoolean("polyglotimpl.DebugMissingClose");

    Engine api; // effectively final
    final Object instrumentationHandler;
    final PolyglotImpl impl;
    DispatchOutputStream out;       // effectively final
    DispatchOutputStream err;       // effectively final
    InputStream in;                 // effectively final
    long timeout;                   // effectively final
    TimeUnit timeoutUnit;           // effectively final
    boolean sandbox;                // effectively final

    final Map<String, PolyglotLanguage> idToLanguage;
    final Map<String, Language> idToPublicLanguage;
    final Map<String, LanguageInfo> idToInternalLanguageInfo;

    final Map<String, PolyglotInstrument> idToInstrument;
    final Map<String, Instrument> idToPublicInstrument;
    final Map<String, InstrumentInfo> idToInternalInstrumentInfo;

    final OptionDescriptors engineOptions;
    final OptionDescriptors compilerOptions;
    final OptionDescriptors allEngineOptions;

    final OptionValuesImpl engineOptionValues;
    final OptionValuesImpl compilerOptionValues;
    ClassLoader contextClassLoader;     // effectively final
    boolean boundEngine;    // effectively final
    final Exception createdLocation = DEBUG_MISSING_CLOSE ? new Exception() : null;
    private final Set<PolyglotContextImpl> contexts = new LinkedHashSet<>();
    private PolyglotContextImpl preInitializedContext;

    PolyglotLanguage hostLanguage;

    volatile OptionDescriptors allOptions;
    volatile boolean closed;

    private volatile CancelHandler cancelHandler;
    // Data used by the runtime to enable "global" state per Engine
    volatile Object runtimeData;
    final Map<Object, Object> javaInteropCodeCache = new ConcurrentHashMap<>();

    PolyglotEngineImpl(PolyglotImpl impl, DispatchOutputStream out, DispatchOutputStream err, InputStream in, Map<String, String> options, long timeout, TimeUnit timeoutUnit,
                    boolean sandbox, boolean useSystemProperties, ClassLoader contextClassLoader, boolean boundEngine) {
        this(impl, out, err, in, options, timeout, timeoutUnit, sandbox, useSystemProperties, contextClassLoader, boundEngine, false);
    }

    private PolyglotEngineImpl(PolyglotImpl impl, DispatchOutputStream out, DispatchOutputStream err, InputStream in, Map<String, String> options, long timeout, TimeUnit timeoutUnit,
                    boolean sandbox, boolean useSystemProperties, ClassLoader contextClassLoader, boolean boundEngine, boolean preInitialization) {
        super(impl);
        this.instrumentationHandler = INSTRUMENT.createInstrumentationHandler(this, out, err, in);
        this.impl = impl;
        this.out = out;
        this.err = err;
        this.in = in;
        this.timeout = timeout;
        this.timeoutUnit = timeoutUnit;
        this.contextClassLoader = contextClassLoader;
        this.sandbox = sandbox;
        this.boundEngine = boundEngine;

        Map<String, LanguageInfo> languageInfos = new LinkedHashMap<>();
        this.idToLanguage = Collections.unmodifiableMap(initializeLanguages(languageInfos));
        this.idToInternalLanguageInfo = Collections.unmodifiableMap(languageInfos);

        Map<String, InstrumentInfo> instrumentInfos = new LinkedHashMap<>();
        this.idToInstrument = Collections.unmodifiableMap(initializeInstruments(instrumentInfos));
        this.idToInternalInstrumentInfo = Collections.unmodifiableMap(instrumentInfos);

        for (String id : idToLanguage.keySet()) {
            if (idToInstrument.containsKey(id)) {
                throw failDuplicateId(id,
                                idToLanguage.get(id).cache.getClassName(),
                                idToInstrument.get(id).cache.getClassName());
            }
        }

        this.engineOptions = new PolyglotEngineOptionsOptionDescriptors();
        this.compilerOptions = VMAccessor.SPI.getCompilerOptions();
        this.allEngineOptions = OptionDescriptors.createUnion(engineOptions, compilerOptions);
        this.engineOptionValues = new OptionValuesImpl(this, this.engineOptions);
        this.compilerOptionValues = new OptionValuesImpl(this, this.compilerOptions);

        Map<String, Language> publicLanguages = new LinkedHashMap<>();
        for (String key : this.idToLanguage.keySet()) {
            PolyglotLanguage languageImpl = idToLanguage.get(key);
            if (!languageImpl.cache.isInternal()) {
                publicLanguages.put(key, languageImpl.api);
            }
        }
        idToPublicLanguage = Collections.unmodifiableMap(publicLanguages);

        Map<String, Instrument> publicInstruments = new LinkedHashMap<>();
        for (String key : this.idToInstrument.keySet()) {
            PolyglotInstrument instrumentImpl = idToInstrument.get(key);
            if (!instrumentImpl.cache.isInternal()) {
                publicInstruments.put(key, instrumentImpl.api);
            }
        }
        idToPublicInstrument = Collections.unmodifiableMap(publicInstruments);

        Map<String, String> originalEngineOptions = new HashMap<>();
        Map<String, String> originalCompilerOptions = new HashMap<>();
        Map<PolyglotLanguage, Map<String, String>> languagesOptions = new HashMap<>();
        Map<PolyglotInstrument, Map<String, String>> instrumentsOptions = new HashMap<>();

        parseOptions(options, useSystemProperties, originalEngineOptions, originalCompilerOptions, languagesOptions, instrumentsOptions, preInitialization);

        this.engineOptionValues.putAll(originalEngineOptions);
        this.compilerOptionValues.putAll(originalCompilerOptions);

        for (PolyglotLanguage language : languagesOptions.keySet()) {
            language.getOptionValues().putAll(languagesOptions.get(language));
        }

        ENGINES.put(this, null);
        if (!preInitialization) {
            createInstruments(instrumentsOptions);
            registerShutDownHook();
        }
    }

    boolean patch(DispatchOutputStream newOut, DispatchOutputStream newErr, InputStream newIn, Map<String, String> newOptions, long newTimeout, TimeUnit newTimeoutUnit,
                    boolean newSandbox, boolean newUseSystemProperties, ClassLoader newContextClassLoader, boolean newBoundEngine) {
        CompilerAsserts.neverPartOfCompilation();
        if (this.boundEngine != newBoundEngine) {
            return false;
        }
        this.out = newOut;
        this.err = newErr;
        this.in = newIn;
        this.timeout = newTimeout;
        this.timeoutUnit = newTimeoutUnit;
        this.contextClassLoader = newContextClassLoader;
        this.sandbox = newSandbox;
        this.boundEngine = newBoundEngine;
        INSTRUMENT.patchInstrumentationHandler(instrumentationHandler, newOut, newErr, newIn);

        Map<String, String> originalEngineOptions = new HashMap<>();
        Map<String, String> originalCompilerOptions = new HashMap<>();
        Map<PolyglotLanguage, Map<String, String>> languagesOptions = new HashMap<>();
        Map<PolyglotInstrument, Map<String, String>> instrumentsOptions = new HashMap<>();

        parseOptions(newOptions, newUseSystemProperties, originalEngineOptions, originalCompilerOptions, languagesOptions, instrumentsOptions, false);

        this.engineOptionValues.putAll(originalEngineOptions);
        this.compilerOptionValues.putAll(originalCompilerOptions);

        for (PolyglotLanguage language : languagesOptions.keySet()) {
            language.getOptionValues().putAll(languagesOptions.get(language));
        }

        createInstruments(instrumentsOptions);
        registerShutDownHook();
        return true;
    }

    private static void createInstruments(final Map<PolyglotInstrument, Map<String, String>> instrumentsOptions) {
        for (PolyglotInstrument instrument : instrumentsOptions.keySet()) {
            instrument.getOptionValues().putAll(instrumentsOptions.get(instrument));
        }

        for (PolyglotInstrument instrument : instrumentsOptions.keySet()) {
            // we got options for this instrument -> create it.
            instrument.ensureCreated();
        }
    }

    private static void registerShutDownHook() {
        if (!shutdownHookInitialized) {
            synchronized (ENGINES) {
                if (!shutdownHookInitialized) {
                    shutdownHookInitialized = true;
                    Runtime.getRuntime().addShutdownHook(new Thread(new PolyglotShutDownHook()));
                }
            }
        }
    }

    private void parseOptions(Map<String, String> options, boolean useSystemProperties,
                    Map<String, String> originalEngineOptions, Map<String, String> originalCompilerOptions,
                    Map<PolyglotLanguage, Map<String, String>> languagesOptions, Map<PolyglotInstrument, Map<String, String>> instrumentsOptions,
                    boolean preInitialization) {
        // When changing this logic, make sure it is in synch with #isEngineGroup()
        if (useSystemProperties) {
            Properties properties = System.getProperties();
            synchronized (properties) {
                for (Object systemKey : properties.keySet()) {
                    String key = (String) systemKey;
                    if (key.startsWith(OptionValuesImpl.SYSTEM_PROPERTY_PREFIX)) {
                        String engineKey = key.substring(OptionValuesImpl.SYSTEM_PROPERTY_PREFIX.length(), key.length());
                        if (!options.containsKey(engineKey) && (!preInitialization || idToPublicLanguage.containsKey(parseOptionGroup(engineKey)) ||
                                        engineKey.equals(PolyglotImpl.OPTION_GROUP_ENGINE + '.' + PolyglotEngineOptions.PREINITIALIZE_CONTEXT_NAME))) {
                            options.put(engineKey, System.getProperty(key));
                        }
                    }
                }
            }
        }
        for (String key : options.keySet()) {
            String group = parseOptionGroup(key);
            String value = options.get(key);
            PolyglotLanguage language = idToLanguage.get(group);
            if (language != null && !language.cache.isInternal()) {
                Map<String, String> languageOptions = languagesOptions.get(language);
                if (languageOptions == null) {
                    languageOptions = new HashMap<>();
                    languagesOptions.put(language, languageOptions);
                }
                languageOptions.put(key, value);
                continue;
            }
            PolyglotInstrument instrument = idToInstrument.get(group);
            if (instrument != null && !instrument.cache.isInternal()) {
                Map<String, String> instrumentOptions = instrumentsOptions.get(instrument);
                if (instrumentOptions == null) {
                    instrumentOptions = new HashMap<>();
                    instrumentsOptions.put(instrument, instrumentOptions);
                }
                instrumentOptions.put(key, value);
                continue;
            }

            if (group.equals(PolyglotImpl.OPTION_GROUP_ENGINE)) {
                originalEngineOptions.put(key, value);
                continue;
            }

            if (group.equals(PolyglotImpl.OPTION_GROUP_COMPILER)) {
                originalCompilerOptions.put(key, value);
                continue;
            }
            throw OptionValuesImpl.failNotFound(getAllOptions(), key);
        }
    }

    /**
     * Find if there is an "engine option" (covers engine, compiler and instruments options) present
     * among the given options.
     */
    // The implementation must be in synch with #parseOptions()
    boolean isEngineGroup(String group) {
        return idToPublicInstrument.containsKey(group) ||
                        group.equals(PolyglotImpl.OPTION_GROUP_ENGINE) ||
                        group.equals(PolyglotImpl.OPTION_GROUP_COMPILER);
    }

    static String parseOptionGroup(String key) {
        int groupIndex = key.indexOf('.');
        String group;
        if (groupIndex != -1) {
            group = key.substring(0, groupIndex);
        } else {
            group = key;
        }
        return group;
    }

    @Override
    public PolyglotEngineImpl getEngine() {
        return this;
    }

    private Map<String, PolyglotInstrument> initializeInstruments(Map<String, InstrumentInfo> infos) {
        Map<String, PolyglotInstrument> instruments = new LinkedHashMap<>();
        List<InstrumentCache> cachedInstruments = InstrumentCache.load(SPI.allLoaders());
        for (InstrumentCache instrumentCache : cachedInstruments) {
            PolyglotInstrument instrumentImpl = new PolyglotInstrument(this, instrumentCache);
            instrumentImpl.info = LANGUAGE.createInstrument(instrumentImpl, instrumentCache.getId(), instrumentCache.getName(), instrumentCache.getVersion());
            Instrument instrument = impl.getAPIAccess().newInstrument(instrumentImpl);
            instrumentImpl.api = instrument;

            String id = instrumentImpl.cache.getId();
            verifyId(id, instrumentCache.getClassName());
            if (instruments.containsKey(id)) {
                throw failDuplicateId(id, instrumentImpl.cache.getClassName(), instruments.get(id).cache.getClassName());
            }
            instruments.put(id, instrumentImpl);
            infos.put(id, instrumentImpl.info);
        }
        return instruments;
    }

    private Map<String, PolyglotLanguage> initializeLanguages(Map<String, LanguageInfo> infos) {
        Map<String, PolyglotLanguage> polyglotLanguages = new LinkedHashMap<>();
        Map<String, LanguageCache> cachedLanguages = new HashMap<>();
        List<LanguageCache> sortedLanguages = new ArrayList<>();
        for (LanguageCache lang : LanguageCache.languages().values()) {
            String id = lang.getId();
            if (!cachedLanguages.containsKey(id)) {
                sortedLanguages.add(lang);
                cachedLanguages.put(id, lang);
            }
        }
        Collections.sort(sortedLanguages);

        LinkedHashSet<LanguageCache> serializedLanguages = new LinkedHashSet<>();
        Set<String> languageReferences = new HashSet<>();
        Map<String, RuntimeException> initErrors = new HashMap<>();

        for (LanguageCache language : sortedLanguages) {
            languageReferences.addAll(language.getDependentLanguages());
        }

        // visit / initialize internal languages first to model the implicit
        // dependency of every public language to every internal language
        for (LanguageCache language : sortedLanguages) {
            if (language.isInternal() && !languageReferences.contains(language.getId())) {
                visitLanguage(initErrors, cachedLanguages, serializedLanguages, language);
            }
        }

        for (LanguageCache language : sortedLanguages) {
            if (!language.isInternal() && !languageReferences.contains(language.getId())) {
                visitLanguage(initErrors, cachedLanguages, serializedLanguages, language);
            }
        }

        this.hostLanguage = createLanguage(createHostLanguageCache(), HOST_LANGUAGE_INDEX, null);

        int index = 1;
        for (LanguageCache cache : serializedLanguages) {
            PolyglotLanguage languageImpl = createLanguage(cache, index, initErrors.get(cache.getId()));

            String id = languageImpl.cache.getId();
            verifyId(id, cache.getClassName());
            if (polyglotLanguages.containsKey(id)) {
                throw failDuplicateId(id, languageImpl.cache.getClassName(), polyglotLanguages.get(id).cache.getClassName());
            }
            polyglotLanguages.put(id, languageImpl);
            infos.put(id, languageImpl.info);
            index++;
        }

        this.hostLanguage.ensureInitialized();

        return polyglotLanguages;
    }

    private void visitLanguage(Map<String, RuntimeException> initErrors, Map<String, LanguageCache> cachedLanguages, LinkedHashSet<LanguageCache> serializedLanguages,
                    LanguageCache language) {
        visitLanguageImpl(new HashSet<>(), initErrors, cachedLanguages, serializedLanguages, language);
    }

    private void visitLanguageImpl(Set<String> visitedIds, Map<String, RuntimeException> initErrors, Map<String, LanguageCache> cachedLanguages, LinkedHashSet<LanguageCache> serializedLanguages,
                    LanguageCache language) {
        Set<String> dependencies = language.getDependentLanguages();
        for (String dependency : dependencies) {
            LanguageCache dependentLanguage = cachedLanguages.get(dependency);
            if (dependentLanguage == null) {
                // dependent languages are optional
                continue;
            }
            if (visitedIds.contains(dependency)) {
                initErrors.put(language.getId(), new PolyglotIllegalStateException("Illegal cyclic language dependency found:" + language.getId() + " -> " + dependency));
                continue;
            }
            visitedIds.add(dependency);
            visitLanguageImpl(visitedIds, initErrors, cachedLanguages, serializedLanguages, dependentLanguage);
            visitedIds.remove(dependency);
        }
        serializedLanguages.add(language);
    }

    private PolyglotLanguage createLanguage(LanguageCache cache, int index, RuntimeException initError) {
        PolyglotLanguage languageImpl = new PolyglotLanguage(this, cache, index, index == HOST_LANGUAGE_INDEX, initError);
        languageImpl.info = NODES.createLanguage(languageImpl, cache.getId(), cache.getName(), cache.getVersion(), cache.getMimeTypes(), cache.isInternal());
        Language language = impl.getAPIAccess().newLanguage(languageImpl);
        languageImpl.api = language;
        return languageImpl;
    }

    private static LanguageCache createHostLanguageCache() {
        return new LanguageCache(HOST_LANGUAGE_ID, Collections.emptySet(),
                        "Host", "Host", System.getProperty("java.version"), false, false, new HostLanguage());
    }

    private static void verifyId(String id, String className) {
        if (RESERVED_IDS.contains(id)) {
            throw new IllegalStateException(String.format("The language or instrument with class '%s' uses a reserved id '%s'. " +
                            "Resolve this by using a not reserved id for the language or instrument. " +
                            "The following ids are reserved %s for internal use.",
                            className, id, RESERVED_IDS));
        } else if (id.contains(".")) {
            throw new IllegalStateException(String.format("The language '%s' must not contain a period in its id '%s'. " +
                            "Remove all periods from the id to resolve this issue. ",
                            className, id));
        }
    }

    private static RuntimeException failDuplicateId(String duplicateId, String className1, String className2) {
        return new IllegalStateException(
                        String.format("Duplicate id '%s' specified by language or instrument with class '%s' and '%s'. " +
                                        "Resolve this by specifying a unique id for each language or instrument.",
                                        duplicateId, className1, className2));
    }

    final void checkState() {
        if (closed) {
            throw new IllegalStateException("Engine is already closed.");
        }
    }

    OptionDescriptors getEngineOptions() {
        throw new UnsupportedOperationException();
    }

    void addContext(PolyglotContextImpl context) {
        assert Thread.holdsLock(this);
        contexts.add(context);
    }

    synchronized void removeContext(PolyglotContextImpl context) {
        contexts.remove(context);
    }

    void reportAllLanguageContexts(ContextsListener listener) {
        PolyglotContextImpl[] allContexts;
        synchronized (this) {
            if (contexts.isEmpty()) {
                return;
            }
            allContexts = contexts.toArray(new PolyglotContextImpl[contexts.size()]);
        }
        for (PolyglotContextImpl context : allContexts) {
            listener.onContextCreated(context.truffleContext);
            for (PolyglotLanguageContext lc : context.contexts) {
                LanguageInfo language = lc.language.info;
                if (lc.eventsEnabled && lc.env != null) {
                    listener.onLanguageContextCreated(context.truffleContext, language);
                    if (lc.isInitialized()) {
                        listener.onLanguageContextInitialized(context.truffleContext, language);
                        if (lc.finalized) {
                            listener.onLanguageContextFinalized(context.truffleContext, language);
                        }
                    }
                }
            }
        }
    }

    void reportAllContextThreads(ThreadsListener listener) {
        PolyglotContextImpl[] allContexts;
        synchronized (this) {
            if (contexts.isEmpty()) {
                return;
            }
            allContexts = contexts.toArray(new PolyglotContextImpl[contexts.size()]);
        }
        for (PolyglotContextImpl context : allContexts) {
            Thread[] threads;
            synchronized (context) {
                threads = context.getSeenThreads().keySet().toArray(new Thread[0]);
            }
            for (Thread thread : threads) {
                listener.onThreadInitialized(context.truffleContext, thread);
            }
        }
    }

    @Override
    public Language requirePublicLanguage(String id) {
        checkState();
        Language language = idToPublicLanguage.get(id);
        if (language == null) {
            String misspelledGuess = matchSpellingError(idToPublicLanguage.keySet(), id);
            String didYouMean = "";
            if (misspelledGuess != null) {
                didYouMean = String.format("Did you mean '%s'? ", misspelledGuess);
            }
            throw new PolyglotIllegalStateException(String.format("A language with id '%s' is not installed. %sInstalled languages are: %s.", id, didYouMean, getLanguages().keySet()));
        }
        return language;
    }

    private static String matchSpellingError(Set<String> allIds, String enteredId) {
        String lowerCaseEnteredId = enteredId.toLowerCase();
        for (String id : allIds) {
            if (id.toLowerCase().equals(lowerCaseEnteredId)) {
                return id;
            }
        }
        return null;
    }

    @Override
    public Instrument requirePublicInstrument(String id) {
        checkState();
        Instrument instrument = idToPublicInstrument.get(id);
        if (instrument == null) {
            String misspelledGuess = matchSpellingError(idToPublicInstrument.keySet(), id);
            String didYouMean = "";
            if (misspelledGuess != null) {
                didYouMean = String.format("Did you mean '%s'? ", misspelledGuess);
            }
            throw new PolyglotIllegalStateException(String.format("An instrument with id '%s' is not installed. %sInstalled instruments are: %s.", id, didYouMean, getInstruments().keySet()));
        }
        return instrument;
    }

    @Override
    public synchronized void ensureClosed(boolean cancelIfExecuting, boolean ignoreCloseFailure) {
        if (!closed) {
            PolyglotContextImpl[] localContexts = contexts.toArray(new PolyglotContextImpl[0]);
            /*
             * Check ahead of time for open contexts to fail early and avoid closing only some
             * contexts.
             */
            if (!cancelIfExecuting && !ignoreCloseFailure) {
                for (PolyglotContextImpl context : localContexts) {
                    synchronized (context) {
                        if (context.hasActiveOtherThread(false)) {
                            throw new IllegalStateException(String.format("One of the context instances is currently executing. " +
                                            "Set cancelIfExecuting to true to stop the execution on this thread."));
                        }
                    }
                }
            }
            for (PolyglotContextImpl context : localContexts) {
                try {
                    boolean closeCompleted = context.closeImpl(cancelIfExecuting, cancelIfExecuting);
                    if (!closeCompleted && !cancelIfExecuting && !ignoreCloseFailure) {
                        throw new IllegalStateException(String.format("One of the context instances is currently executing. " +
                                        "Set cancelIfExecuting to true to stop the execution on this thread."));
                    }
                } catch (Throwable e) {
                    if (!ignoreCloseFailure) {
                        throw e;
                    }
                }
            }
            if (cancelIfExecuting) {
                getCancelHandler().waitForClosing(localContexts);
            }

            if (!boundEngine) {
                for (PolyglotContextImpl context : localContexts) {
                    PolyglotContextImpl.disposeStaticContext(context);
                }
            }

            contexts.clear();
            for (Instrument instrument : idToPublicInstrument.values()) {
                PolyglotInstrument instrumentImpl = (PolyglotInstrument) getAPIAccess().getImpl(instrument);
                try {
                    instrumentImpl.notifyClosing();
                } catch (Throwable e) {
                    if (!ignoreCloseFailure) {
                        throw e;
                    }
                }
            }
            for (Instrument instrument : idToPublicInstrument.values()) {
                PolyglotInstrument instrumentImpl = (PolyglotInstrument) getAPIAccess().getImpl(instrument);
                try {
                    instrumentImpl.ensureClosed();
                } catch (Throwable e) {
                    if (!ignoreCloseFailure) {
                        throw e;
                    }
                }
            }
            for (PolyglotLanguage language : idToLanguage.values()) {
                if (language.isInitialized()) {
                    language.requireProfile().notifyEngineDisposed();
                }
            }

            ENGINES.remove(this);
            closed = true;
        }
    }

    @Override
    public Map<String, Instrument> getInstruments() {
        checkState();
        return idToPublicInstrument;
    }

    @Override
    public Map<String, Language> getLanguages() {
        checkState();
        return idToPublicLanguage;
    }

    @Override
    public OptionDescriptors getOptions() {
        checkState();
        return allEngineOptions;
    }

    @Override
    public String getVersion() {
        String version = System.getProperty("org.graalvm.version");
        if (version == null) {
            version = System.getProperty("graalvm.version");
        }
        if (version == null) {
            return "Development Build";
        } else {
            return version;
        }
    }

    OptionDescriptors getAllOptions() {
        checkState();
        if (allOptions == null) {
            synchronized (this) {
                if (allOptions == null) {
                    List<OptionDescriptors> allDescriptors = new ArrayList<>();
                    allDescriptors.add(engineOptions);
                    allDescriptors.add(compilerOptions);
                    for (PolyglotLanguage language : idToLanguage.values()) {
                        allDescriptors.add(language.getOptions());
                    }
                    for (PolyglotInstrument instrument : idToInstrument.values()) {
                        allDescriptors.add(instrument.getOptions());
                    }
                    allOptions = OptionDescriptors.createUnion(allDescriptors.toArray(new OptionDescriptors[0]));
                }
            }
        }
        return allOptions;
    }

    Collection<Thread> getAllThreads(PolyglotContextImpl context) {
        synchronized (context) {
            return new ArrayList<>(context.getSeenThreads().keySet());
        }
    }

    static PolyglotEngineImpl preInitialize(PolyglotImpl impl, DispatchOutputStream out, DispatchOutputStream err, InputStream in, ClassLoader contextClassLoader) {
        final PolyglotEngineImpl engine = new PolyglotEngineImpl(impl, out, err, in, new HashMap<>(), 0, null, false, true, contextClassLoader, true, true);
        synchronized (engine) {
            try {
                engine.preInitializedContext = PolyglotContextImpl.preInitialize(engine);
                engine.addContext(engine.preInitializedContext);
            } finally {
                // Reset language homes from native-image compilatio time, will be recomputed in
                // image execution time
                LanguageCache.resetNativeImageCacheLanguageHomes();
            }
        }
        return engine;
    }

    /**
     * Clears the pre-initialized engines. The TruffleFeature needs to clean emitted engines during
     * Feature.cleanup.
     */
    static void resetPreInitializedEngine() {
        ENGINES.clear();
    }

    private static final class PolyglotShutDownHook implements Runnable {

        public void run() {
            PolyglotEngineImpl[] engines = ENGINES.keySet().toArray(new PolyglotEngineImpl[0]);
            for (PolyglotEngineImpl engine : engines) {
                if (DEBUG_MISSING_CLOSE) {
                    PrintStream out = System.out;
                    out.println("Missing close on vm shutdown: ");
                    out.print(" InitializedLanguages:");
                    for (PolyglotContextImpl context : engine.contexts) {
                        for (PolyglotLanguageContext langContext : context.contexts) {
                            if (langContext.env != null) {
                                out.print(langContext.language.getId());
                                out.print(", ");
                            }
                        }
                    }
                    out.println();
                    engine.createdLocation.printStackTrace();
                }
                if (engine != null) {
                    engine.ensureClosed(false, true);
                }
            }
            ENGINES.keySet().removeAll(Arrays.asList(engines));
        }
    }

    CancelHandler getCancelHandler() {
        if (cancelHandler == null) {
            synchronized (this) {
                if (cancelHandler == null) {
                    cancelHandler = new CancelHandler();
                }
            }
        }
        return cancelHandler;

    }

    final class CancelHandler {
        private final Instrumenter instrumenter;
        private volatile EventBinding<?> cancellationBinding;
        private int cancellationUsers;

        CancelHandler() {
            this.instrumenter = (Instrumenter) INSTRUMENT.getEngineInstrumenter(instrumentationHandler);
        }

        void waitForClosing(PolyglotContextImpl... localContexts) {
            boolean cancelling = false;
            for (PolyglotContextImpl context : localContexts) {
                if (context.cancelling) {
                    cancelling = true;
                    break;
                }
            }

            if (cancelling) {
                enableCancel();

                try {
                    for (PolyglotContextImpl context : localContexts) {
                        context.sendInterrupt();
                    }
                    for (PolyglotContextImpl context : localContexts) {
                        context.waitForClose();
                    }
                } finally {
                    disableCancel();
                }
            }
        }

        private synchronized void enableCancel() {
            if (cancellationBinding == null) {
                cancellationBinding = instrumenter.attachExecutionEventListener(SourceSectionFilter.ANY, new ExecutionEventListener() {
                    public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
                        cancelExecution(context);
                    }

                    public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
                        cancelExecution(context);
                    }

                    public void onEnter(EventContext context, VirtualFrame frame) {
                        cancelExecution(context);
                    }

                    @TruffleBoundary
                    private void cancelExecution(EventContext eventContext) {
                        PolyglotContextImpl context = PolyglotContextImpl.requireContext();
                        if (context.cancelling) {
                            throw new CancelExecution(eventContext);
                        }
                    }
                });
            }
            cancellationUsers++;
        }

        private synchronized void disableCancel() {
            int usersLeft = --cancellationUsers;
            if (usersLeft <= 0) {
                EventBinding<?> b = cancellationBinding;
                if (b != null) {
                    b.dispose();
                }
                cancellationBinding = null;
            }
        }
    }

    @SuppressWarnings("serial")
    private static final class CancelExecution extends ThreadDeath implements TruffleException {

        private final Node node;

        CancelExecution(EventContext context) {
            this.node = context.getInstrumentedNode();
        }

        public Node getLocation() {
            return node;
        }

        @Override
        public String getMessage() {
            return "Execution got cancelled.";
        }

        public boolean isCancelled() {
            return true;
        }

    }

    @Override
    public String getImplementationName() {
        String name = Truffle.getRuntime().getName();
        if (name.contains("Graal")) {
            return "Graal Engine";
        } else {
            return "Default Engine";
        }
    }

    @Override
    @SuppressWarnings({"all"})
    public synchronized Context createContext(OutputStream out, OutputStream err, InputStream in, boolean allowHostAccess,
                    boolean allowNativeAccess, boolean allowCreateThread, boolean allowHostIO, boolean allowHostClassLoading,
                    Predicate<String> classFilter, Map<String, String> options, Map<String, String[]> arguments, String[] onlyLanguages, FileSystem fileSystem) {
        checkState();
        if (boundEngine && preInitializedContext == null && !contexts.isEmpty()) {
            throw new IllegalArgumentException("Automatically created engines cannot be used to create more than one context. " +
                            "Use Engine.newBuilder().build() to construct a new engine and pass it using Context.newBuilder().engine(engine).build().");
        }

        Set<String> allowedLanguages;
        if (onlyLanguages.length == 0) {
            allowedLanguages = getLanguages().keySet();
        } else {
            allowedLanguages = new HashSet<>(Arrays.asList(onlyLanguages));
        }
        final FileSystem fs;
        if (allowHostIO) {
            fs = fileSystem != null ? fileSystem : FileSystems.getDefaultFileSystem();
        } else {
            fs = FileSystems.newNoIOFileSystem();
        }
        PolyglotContextImpl contextImpl = preInitializedContext;
        preInitializedContext = null;
        if (contextImpl != null) {
            boolean patchResult;
            try {
                patchResult = contextImpl.patch(out, err, in, allowHostAccess, allowNativeAccess, allowCreateThread, allowHostClassLoading, classFilter, options, arguments, allowedLanguages, fs);
            } catch (RuntimeException re) {
                contextImpl.closeImpl(false, false);
                PolyglotContextImpl.disposeStaticContext(null);
                throw re;
            }
            if (!patchResult) {
                contextImpl.closeImpl(false, false);
                contextImpl = null;
                PolyglotContextImpl.disposeStaticContext(contextImpl);
            }
        }
        if (contextImpl == null) {
            contextImpl = new PolyglotContextImpl(this, out, err, in, allowHostAccess, allowNativeAccess, allowCreateThread, allowHostClassLoading, classFilter, options, arguments, allowedLanguages,
                            fs);
            addContext(contextImpl);
        }
        Context api = impl.getAPIAccess().newContext(contextImpl);
        contextImpl.api = api;
        return api;
    }

}

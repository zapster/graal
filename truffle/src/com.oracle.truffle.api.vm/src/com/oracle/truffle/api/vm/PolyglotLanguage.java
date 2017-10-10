/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.truffle.api.vm.VMAccessor.LANGUAGE;

import org.graalvm.options.OptionDescriptors;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Language;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl.AbstractLanguageImpl;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.vm.LanguageCache.LoadedLanguage;
import com.oracle.truffle.api.vm.PolyglotImpl.VMObject;

class PolyglotLanguage extends AbstractLanguageImpl implements VMObject {

    final PolyglotEngineImpl engine;
    final LanguageCache cache;
    Language api;
    LanguageInfo info;
    final int index;
    private final boolean host;

    private OptionDescriptors options;
    private volatile OptionValuesImpl optionValues;

    volatile boolean initialized;

    PolyglotLanguage(PolyglotEngineImpl engine, LanguageCache cache, int index, boolean host) {
        super(engine.impl);
        this.engine = engine;
        this.cache = cache;
        this.index = index;
        this.host = host;
    }

    Object getCurrentContext() {
        Env env = PolyglotContextImpl.requireContext().contexts[index].env;
        if (env == null) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException(
                            "The language context is not yet initialized or already disposed. ");
        }
        return LANGUAGE.getContext(env);
    }

    @Override
    public boolean isHost() {
        return host;
    }

    @Override
    public OptionDescriptors getOptions() {
        engine.checkState();
        ensureInitialized();
        return options;
    }

    @Override
    public PolyglotEngineImpl getEngine() {
        return engine;
    }

    @Override
    public Engine getEngineAPI() {
        return getEngine().api;
    }

    void ensureInitialized() {
        if (!initialized) {
            synchronized (engine) {
                if (!initialized) {
                    try {
                        LoadedLanguage loadedLanguage = cache.loadLanguage();
                        LANGUAGE.initializeLanguage(info, loadedLanguage.getLanguage(), loadedLanguage.isSingleton());
                        this.options = LANGUAGE.describeOptions(loadedLanguage.getLanguage(), cache.getId());
                    } catch (Exception e) {
                        throw new IllegalStateException(String.format("Error initializing language '%s' using class '%s'.", cache.getId(), cache.getClassName()), e);
                    }
                    initialized = true;
                }
            }
        }
    }

    OptionValuesImpl getOptionValues() {
        if (optionValues == null) {
            synchronized (engine) {
                if (optionValues == null) {
                    optionValues = new OptionValuesImpl(engine, getOptions());
                }
            }
        }
        return optionValues;
    }

    <S> S lookup(Class<S> serviceClass) {
        ensureInitialized();
        return LANGUAGE.lookup(info, serviceClass);
    }

    @Override
    public String getName() {
        return cache.getName();
    }

    @Override
    public String getImplementationName() {
        return cache.getImplementationName();
    }

    @Override
    public boolean isInteractive() {
        return cache.isInteractive();
    }

    @Override
    public String getVersion() {
        return cache.getVersion();
    }

    @Override
    public String getId() {
        return cache.getId();
    }

}

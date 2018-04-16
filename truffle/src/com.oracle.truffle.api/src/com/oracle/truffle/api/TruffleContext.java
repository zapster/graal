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
package com.oracle.truffle.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage.AccessAPI;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.TruffleLanguage.Env;

/**
 * A handle on a context of a set of Truffle languages. This context handle is designed to be used
 * by Truffle guest language implementations. The Truffle context can be used to create inner
 * contexts for isolated execution of guest language code.
 * <p>
 * A {@link TruffleContext context} consists of a {@link TruffleLanguage#createContext(Env) language
 * context} instance for each {@link Env#getLanguages() installed language}. The current language
 * context is {@link TruffleLanguage#createContext(Env) created} eagerly and can be accessed using a
 * {@link ContextReference context reference} or statically with
 * {@link TruffleLanguage#getCurrentContext(Class)} after the context was
 * {@link TruffleContext#enter() entered}.
 * <p>
 * The configuration for each language context is inherited from its parent/creator context. In
 * addition to that {@link Builder#config(String, Object) config} parameters can be passed to new
 * language context instance of the current language. The configuration of other installed languages
 * cannot be modified. To run guest language code in a context, the context needs to be first
 * {@link #enter() entered} and then {@link #leave(Object) left}. The context should be
 * {@link #close() closed} when it is no longer needed. If the context is not closed explicitly,
 * then it is automatically closed together with the parent context.
 * <p>
 * Example usage: {@link TruffleContextSnippets#executeInContext}
 *
 * @since 0.27
 */
public final class TruffleContext implements AutoCloseable {

    static final TruffleContext EMPTY = new TruffleContext();

    private static ThreadLocal<List<Object>> assertStack;
    final Object impl;
    final boolean closeable;

    TruffleContext(Object impl) {
        this.impl = impl;
        this.closeable = false;
    }

    private TruffleContext(TruffleLanguage.Env env, Map<String, Object> config) {
        this.impl = AccessAPI.engineAccess().createInternalContext(env.getVMObject(), config, this);
        this.closeable = false;
        // Initialized after this TruffleContext instance is fully set up
        AccessAPI.engineAccess().initializeInternalContext(env.getVMObject(), impl);
    }

    /**
     * Creates closeable context representation for use by a language.
     */
    private TruffleContext(Object impl, boolean closeable) {
        this.impl = impl;
        this.closeable = closeable;
    }

    private TruffleContext() {
        this.impl = null;
        this.closeable = false;
    }

    static {
        assert initializeAssertStack();
    }

    private static boolean initializeAssertStack() {
        assertStack = new ThreadLocal<List<Object>>() {
            @Override
            protected List<Object> initialValue() {
                return new ArrayList<>();
            }
        };
        return true;
    }

    /**
     * Get a parent context of this context, if any. This provides the hierarchy of inner contexts.
     *
     * @return a parent context, or <code>null</code> if there is no parent
     * @since 0.30
     */
    @TruffleBoundary
    public TruffleContext getParent() {
        return AccessAPI.engineAccess().getParentContext(impl);
    }

    /**
     * Enters this context and returns an object representing the previous context. Calls to enter
     * must be followed by a call to {@link #leave(Object)} in a finally block and the previous
     * context must be passed as an argument. It is allowed to enter a context multiple times from
     * the same thread. If the context is currently not entered by any thread then it is allowed be
     * entered by an arbitrary thread. Entering the context from two or more different threads at
     * the same time is possible, unless one of the loaded languages denies access to the thread, in
     * which case an {@link IllegalStateException} is thrown. The result of the enter function is
     * unspecified and must only be passed to {@link #leave(Object)}. The result value must not be
     * stored permanently.
     * <p>
     * Entering a language context is designed for compilation and is most efficient if the
     * {@link TruffleContext context} instance is compilation final.
     *
     * <p>
     * Example usage: {@link TruffleContextSnippets#executeInContext}
     *
     * @see #leave(Object)
     * @since 0.27
     */
    public Object enter() {
        Object prev = AccessAPI.engineAccess().enterInternalContext(impl);
        assert verifyEnter(prev);
        return prev;
    }

    /**
     * Leaves this context and sets the previous context as the new current context.
     * <p>
     * Leaving a language context is designed for compilation and is most efficient if the
     * {@link TruffleContext context} instance is compilation final.
     *
     * @param prev the previous context returned by {@link #enter()}
     * @see #enter()
     * @since 0.27
     */
    public void leave(Object prev) {
        assert verifyLeave(prev);
        AccessAPI.engineAccess().leaveInternalContext(impl, prev);
    }

    /**
     * Closes this context and disposes its resources. A context cannot be closed if it is currently
     * {@link #enter() entered} by any thread. If a closed context is attempted to be accessed or
     * entered, then an {@link IllegalStateException} is thrown. If the context is not closed
     * explicitly, then it is automatically closed together with the parent context. If an attempt
     * to close a context was successful then consecutive calls to close have no effect.
     * <p>
     * Only contexts created by {@link Builder#build()} can be explicitly closed. Other instances
     * throw {@link UnsupportedOperationException} on close attempts.
     *
     * @throws UnsupportedOperationException when not created by {@link Builder#build()}.
     * @since 0.27
     */
    @Override
    @TruffleBoundary
    public void close() {
        if (!closeable) {
            throw new UnsupportedOperationException("It's not possible to close a foreign context.");
        }
        AccessAPI.engineAccess().closeInternalContext(impl);
    }

    @TruffleBoundary
    private static boolean verifyEnter(Object prev) {
        assertStack.get().add(prev);
        return true;
    }

    @TruffleBoundary
    private static boolean verifyLeave(Object prev) {
        List<Object> list = assertStack.get();
        assert list.size() > 0 : "Assert stack is empty.";
        Object expectedPrev = list.get(list.size() - 1);
        assert prev == expectedPrev : "Invalid prev argument provided in TruffleContext.leave(Object).";
        list.remove(list.size() - 1);
        return true;
    }

    /**
     * Builder class to create new {@link TruffleContext} instances.
     *
     * @since 0.27
     */
    public final class Builder {

        private final Env sourceEnvironment;
        private Map<String, Object> config;

        Builder(Env env) {
            this.sourceEnvironment = env;
        }

        /**
         * Sets a config parameter that the child context of this language can access using
         * {@link Env#getConfig()}.
         *
         * @since 0.27
         */
        @TruffleBoundary
        public Builder config(String key, Object value) {
            if (config == null) {
                config = new HashMap<>();
            }
            config.put(key, value);
            return this;
        }

        /**
         * Builds the new context instance.
         *
         * @since 0.27
         */
        @TruffleBoundary
        public TruffleContext build() {
            TruffleContext context = new TruffleContext(sourceEnvironment, config);
            return new TruffleContext(context.impl, true);
        }
    }
}

class TruffleContextSnippets {
    // @formatter:off
    abstract class MyContext {
    }
    abstract class MyLanguage extends TruffleLanguage<MyContext> {
    }
    // BEGIN: TruffleContextSnippets#executeInContext
    void executeInContext(Env env) {
        MyContext outerLangContext = getContext();

        TruffleContext innerContext = env.newContextBuilder().build();
        Object p = innerContext.enter();
        try {
            MyContext innerLangContext = getContext();

            assert outerLangContext != innerLangContext;
        } finally {
            innerContext.leave(p);
        }
        assert outerLangContext == getContext();
        innerContext.close();
    }
    private static MyContext getContext() {
        return TruffleLanguage.getCurrentContext(MyLanguage.class);
    }
    // END: TruffleContextSnippets#executeInContext
    // @formatter:on

}

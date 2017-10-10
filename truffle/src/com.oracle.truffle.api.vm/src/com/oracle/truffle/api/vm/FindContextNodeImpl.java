/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
import static com.oracle.truffle.api.vm.VMAccessor.NODES;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.vm.PolyglotRuntime.LanguageShared;

@SuppressWarnings("deprecation")
final class FindContextNodeImpl<C> extends com.oracle.truffle.api.impl.FindContextNode<C> {
    private final LanguageShared languageShared;
    private final Env env;

    FindContextNodeImpl(Env env) {
        this.env = env;
        this.languageShared = (LanguageShared) NODES.getEngineObject(LANGUAGE.getLanguageInfo(env));
    }

    @SuppressWarnings("unchecked")
    @Override
    public C executeFindContext() {
        return (C) languageShared.getCurrentContext();
    }

    @SuppressWarnings("unchecked")
    @Override
    public TruffleLanguage<C> getTruffleLanguage() {
        return (TruffleLanguage<C>) NODES.getLanguageSpi(LANGUAGE.getLanguageInfo(env));
    }
}

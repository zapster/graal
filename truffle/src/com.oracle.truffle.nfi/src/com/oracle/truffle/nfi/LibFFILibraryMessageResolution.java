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
package com.oracle.truffle.nfi;

import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.CanResolve;
import com.oracle.truffle.api.interop.KeyInfo;
import com.oracle.truffle.api.interop.MessageResolution;
import com.oracle.truffle.api.interop.Resolve;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.nfi.LibFFIFunctionMessageResolution.CachedExecuteNode;
import com.oracle.truffle.nfi.LibFFIFunctionMessageResolutionFactory.CachedExecuteNodeGen;
import com.oracle.truffle.nfi.LibFFILibraryMessageResolutionFactory.CachedLookupSymbolNodeGen;

@MessageResolution(receiverType = LibFFILibrary.class)
class LibFFILibraryMessageResolution {

    abstract static class CachedLookupSymbolNode extends Node {

        private final ContextReference<NFIContext> ctxRef = NFILanguage.getCurrentContextReference();

        protected abstract TruffleObject executeLookup(LibFFILibrary receiver, String symbol);

        @Specialization(guards = {"receiver == cachedReceiver", "symbol.equals(cachedSymbol)"})
        @SuppressWarnings("unused")
        protected TruffleObject lookupCached(LibFFILibrary receiver, String symbol,
                        @Cached("receiver") LibFFILibrary cachedReceiver,
                        @Cached("symbol") String cachedSymbol,
                        @Cached("lookup(cachedReceiver, cachedSymbol)") TruffleObject cachedRet) {
            return cachedRet;
        }

        @Specialization(replaces = "lookupCached")
        protected TruffleObject lookup(LibFFILibrary receiver, String symbol) {
            TruffleObject preBound = receiver.findSymbol(symbol);
            if (preBound != null) {
                return preBound;
            }
            try {
                return ctxRef.get().lookupSymbol(receiver, symbol);
            } catch (UnsatisfiedLinkError ex) {
                throw UnknownIdentifierException.raise(symbol);
            }
        }
    }

    @Resolve(message = "READ")
    abstract static class LookupSymbolNode extends Node {

        @Child private CachedLookupSymbolNode cached = CachedLookupSymbolNodeGen.create();

        public TruffleObject access(LibFFILibrary receiver, String symbol) {
            return cached.executeLookup(receiver, symbol);
        }
    }

    @Resolve(message = "INVOKE")
    abstract static class InvokeSymbolNode extends Node {

        @Child private CachedLookupSymbolNode cached = CachedLookupSymbolNodeGen.create();
        @Child private CachedExecuteNode exec = CachedExecuteNodeGen.create();

        public Object access(LibFFILibrary receiver, String symbol, Object... args) {
            LibFFIFunction obj = (LibFFIFunction) cached.executeLookup(receiver, symbol);
            return exec.execute(obj, args);
        }
    }

    @Resolve(message = "KEY_INFO")
    abstract static class KeyInfoNode extends Node {

        private final ContextReference<NFIContext> ctxRef = NFILanguage.getCurrentContextReference();

        private static final int READABLE = KeyInfo.newBuilder().setReadable(true).build();
        private static final int NOT_EXISTING = 0;

        public int access(LibFFILibrary receiver, String symbol) {
            if (receiver.findSymbol(symbol) == null) {
                try {
                    ctxRef.get().lookupSymbol(receiver, symbol);
                } catch (UnsatisfiedLinkError ex) {
                    return NOT_EXISTING;
                }
            }

            return READABLE;
        }
    }

    @CanResolve
    abstract static class CanResolveLibFFILibraryNode extends Node {

        public boolean test(TruffleObject receiver) {
            return receiver instanceof LibFFILibrary;
        }
    }
}

/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.nfi.impl;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.CachedLanguage;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.profiles.BranchProfile;

@GenerateUncached
@ImportStatic(NFILanguageImpl.class)
abstract class FunctionExecuteNode extends Node {

    static final int ARG_DISPATCH_LIMIT = 5;

    public abstract Object execute(LibFFIFunction receiver, Object[] args) throws ArityException, UnsupportedTypeException;

    @Specialization(guards = "receiver.getSignature() == signature")
    protected Object cachedSignature(LibFFIFunction receiver, Object[] args,
                    @Cached("receiver.getSignature()") @SuppressWarnings("unused") LibFFISignature signature,
                    @Cached("createCachedSignatureCall(signature)") DirectCallNode execute) throws ArityException, UnsupportedTypeException {
        return execute.call(receiver.asPointer(), args);
    }

    static class SignatureExecuteNode extends RootNode {

        final LibFFISignature signature;
        @Children NativeArgumentLibrary[] argLibs;

        final ContextReference<NFIContext> ctxRef;

        SignatureExecuteNode(ContextReference<NFIContext> ctxRef, LibFFISignature signature) {
            super(ctxRef.get().language);
            this.signature = signature;
            this.ctxRef = ctxRef;

            LibFFIType[] argTypes = signature.getArgTypes();
            this.argLibs = new NativeArgumentLibrary[argTypes.length];
            for (int i = 0; i < argTypes.length; i++) {
                argLibs[i] = NativeArgumentLibrary.getFactory().create(argTypes[i]);
            }
        }

        @Override
        @ExplodeLoop
        public Object execute(VirtualFrame frame) {
            long address = (long) frame.getArguments()[0];
            Object[] args = (Object[]) frame.getArguments()[1];

            if (args.length != signature.getRealArgCount()) {
                throw silenceException(RuntimeException.class, ArityException.create(argLibs.length, args.length));
            }

            NativeArgumentBuffer.Array buffer = signature.prepareBuffer();
            try {
                LibFFIType[] types = signature.getArgTypes();
                assert argLibs.length == types.length;

                int argIdx = 0;
                for (int i = 0; i < argLibs.length; i++) {
                    Object arg = argIdx < args.length ? args[argIdx] : null;
                    argLibs[i].serialize(types[i], buffer, arg);
                    if (!types[i].injectedArgument) {
                        argIdx++;
                    }
                }
                assert argIdx == args.length : "SerializeArgumentNodes didn't consume all arguments";
            } catch (UnsupportedTypeException ex) {
                throw silenceException(RuntimeException.class, ex);
            }

            CompilerDirectives.ensureVirtualized(buffer);
            return signature.execute(ctxRef.get(), address, buffer);
        }

        @SuppressWarnings({"unchecked", "unused"})
        static <E extends Exception> RuntimeException silenceException(Class<E> type, Exception ex) throws E {
            throw (E) ex;
        }
    }

    protected DirectCallNode createCachedSignatureCall(LibFFISignature signature) {
        CallTarget target = Truffle.getRuntime().createCallTarget(new SignatureExecuteNode(lookupContextReference(NFILanguageImpl.class), signature));
        DirectCallNode callNode = DirectCallNode.create(target);
        callNode.forceInlining();
        return callNode;
    }

    @ExplodeLoop
    @Specialization(replaces = "cachedSignature", guards = "receiver.getSignature().getArgTypes().length == libs.length")
    protected Object cachedArgCount(LibFFIFunction receiver, Object[] args,
                    @Cached("getGenericNativeArgumentLibraries(receiver.getSignature().getArgTypes().length)") NativeArgumentLibrary[] libs,
                    @CachedLanguage NFILanguageImpl language,
                    @Cached IndirectCallNode callNode,
                    @Cached BranchProfile exception) throws ArityException, UnsupportedTypeException {
        LibFFISignature signature = receiver.getSignature();
        LibFFIType[] argTypes = signature.getArgTypes();

        NativeArgumentBuffer.Array buffer = signature.prepareBuffer();
        int argIdx = 0;
        for (int i = 0; i < libs.length; i++) {
            if (argIdx >= args.length) {
                raiseArityException(argTypes, args.length);
            }

            Object arg;
            if (argTypes[i].injectedArgument) {
                arg = null;
            } else {
                arg = args[argIdx++];
            }
            libs[i].serialize(argTypes[i], buffer, arg);
        }

        if (argIdx != args.length) {
            exception.enter();
            throw ArityException.create(argIdx, args.length);
        }

        return slowPathExecute(language, callNode, receiver, buffer);
    }

    private static void raiseArityException(LibFFIType[] argTypes, int actualArgCount) throws ArityException {
        CompilerDirectives.transferToInterpreter();
        int expectedArgCount = 0;
        for (LibFFIType argType : argTypes) {
            if (!argType.injectedArgument) {
                expectedArgCount++;
            }
        }
        throw ArityException.create(expectedArgCount, actualArgCount);
    }

    protected static NativeArgumentLibrary[] getGenericNativeArgumentLibraries(int argCount) {
        NativeArgumentLibrary[] ret = new NativeArgumentLibrary[argCount];
        for (int i = 0; i < argCount; i++) {
            ret[i] = NativeArgumentLibrary.getFactory().createDispatched(ARG_DISPATCH_LIMIT);
        }
        return ret;
    }

    @Specialization(replaces = "cachedArgCount")
    static Object genericExecute(LibFFIFunction receiver, Object[] args,
                    @CachedLibrary(limit = "ARG_DISPATCH_LIMIT") NativeArgumentLibrary nativeArguments,
                    @CachedLanguage NFILanguageImpl language,
                    @Cached IndirectCallNode callNode,
                    @CachedContext(NFILanguageImpl.class) NFIContext ctx) throws ArityException, UnsupportedTypeException {
        LibFFISignature signature = receiver.getSignature();
        LibFFIType[] argTypes = signature.getArgTypes();

        NativeArgumentBuffer.Array buffer = signature.prepareBuffer();
        int argIdx = 0;
        for (int i = 0; i < argTypes.length; i++) {
            Object arg;
            if (argTypes[i].injectedArgument) {
                arg = null;
            } else {
                if (argIdx >= args.length) {
                    raiseArityException(argTypes, args.length);
                }
                arg = args[argIdx++];
            }
            nativeArguments.serialize(argTypes[i], buffer, arg);
        }

        if (argIdx != args.length) {
            throw ArityException.create(argIdx, args.length);
        }

        return slowPathExecute(language, callNode, receiver, buffer);
    }

    private static Object slowPathExecute(NFILanguageImpl language, IndirectCallNode callNode, LibFFIFunction receiver, NativeArgumentBuffer.Array buffer) {
        return callNode.call(language.getSlowPathCall(), receiver, buffer);
    }

    static class SlowPathExecuteNode extends RootNode {

        final ContextReference<NFIContext> ctxRef;

        SlowPathExecuteNode(NFILanguageImpl language) {
            super(language);
            ctxRef = language.getContextReference();
        }

        @Override
        public Object execute(VirtualFrame frame) {
            LibFFIFunction receiver = (LibFFIFunction) frame.getArguments()[0];
            NativeArgumentBuffer.Array buffer = (NativeArgumentBuffer.Array) frame.getArguments()[1];

            return slowPathExecute(ctxRef.get(), receiver.getSignature(), receiver.getAddress(), buffer);
        }

        @TruffleBoundary
        static Object slowPathExecute(NFIContext ctx, LibFFISignature signature, long functionPointer, NativeArgumentBuffer.Array buffer) {
            return signature.execute(ctx, functionPointer, buffer);
        }
    }
}

/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.genscavenge;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPointContext;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.word.Pointer;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.annotate.RestrictHeapAccess;
import com.oracle.svm.core.code.CodeInfoTable;
import com.oracle.svm.core.deopt.DeoptimizedFrame;
import com.oracle.svm.core.heap.ObjectReferenceVisitor;
import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.stack.JavaStackWalker;
import com.oracle.svm.core.stack.StackFrameVisitor;
import com.oracle.svm.core.thread.VMThreads;

/**
 * Walk a thread stack verifying the Objects pointed to from the frames.
 *
 * This duplicates a lot of the other stack walking and pointer map iteration code, but that's
 * intentional, in case that code is broken.
 */
public final class StackVerifier {

    /*
     * Final state.
     */

    /** A singleton instance of the ObjectReferenceVisitor. */
    private static final VerifyFrameReferencesVisitor verifyFrameReferencesVisitor = new VerifyFrameReferencesVisitor();

    /** A singleton instance of the StackFrameVerifierVisitor. */
    private final StackFrameVerifierVisitor stackFrameVisitor = new StackFrameVerifierVisitor();

    /** Constructor. */
    StackVerifier() {
        // Mutable data are passed as arguments.
    }

    public boolean verifyInAllThreads(Pointer currentSp, CodePointer currentIp, String message) {
        final Log trace = getTraceLog();
        trace.string("[StackVerifier.verifyInAllThreads:").string(message).newline();
        // Flush thread-local allocation data.
        ThreadLocalAllocation.disableThreadLocalAllocation();
        trace.string("Current thread ").hex(CEntryPointContext.getCurrentIsolateThread()).string(": [").newline();
        if (!JavaStackWalker.walkCurrentThread(currentSp, currentIp, stackFrameVisitor)) {
            return false;
        }
        trace.string("]").newline();
        if (SubstrateOptions.MultiThreaded.getValue()) {
            for (IsolateThread vmThread = VMThreads.firstThread(); VMThreads.isNonNullThread(vmThread); vmThread = VMThreads.nextThread(vmThread)) {
                if (vmThread == CEntryPointContext.getCurrentIsolateThread()) {
                    continue;
                }
                trace.string("Thread ").hex(vmThread).string(": [").newline();
                if (!JavaStackWalker.walkThread(vmThread, stackFrameVisitor)) {
                    return false;
                }
                trace.string("]").newline();
            }
        }
        trace.string("]").newline();
        return true;
    }

    private static boolean verifyFrame(Pointer frameSP, CodePointer frameIP, DeoptimizedFrame deoptimizedFrame) {
        final Log trace = getTraceLog();
        trace.string("[StackVerifier.verifyFrame:");
        trace.string("  frameSP: ").hex(frameSP);
        trace.string("  frameIP: ").hex(frameIP);
        trace.string("  pc: ").hex(frameIP);
        trace.newline();

        if (!CodeInfoTable.visitObjectReferences(frameSP, frameIP, deoptimizedFrame, verifyFrameReferencesVisitor)) {
            return false;
        }

        trace.string("  returns true]").newline();
        return true;
    }

    /** A StackFrameVisitor to verify a frame. */
    private static class StackFrameVerifierVisitor implements StackFrameVisitor {

        @Override
        @RestrictHeapAccess(access = RestrictHeapAccess.Access.NO_ALLOCATION, reason = "Must not allocate while verifying the stack.")
        public boolean visitFrame(Pointer currentSP, CodePointer currentIP, DeoptimizedFrame deoptimizedFrame) {
            final Log trace = getTraceLog();
            long totalFrameSize = CodeInfoTable.lookupTotalFrameSize(currentIP);
            trace.string("  currentIP: ").hex(currentIP);
            trace.string("  currentSP: ").hex(currentSP);
            trace.string("  frameSize: ").signed(totalFrameSize).newline();

            if (!verifyFrame(currentSP, currentIP, deoptimizedFrame)) {
                final Log witness = Log.log();
                witness.string("  frame fails to verify");
                witness.string("  returns false]").newline();
                return false;
            }
            return true;
        }
    }

    /** An ObjectReferenceVisitor to verify references from stack frames. */
    private static class VerifyFrameReferencesVisitor implements ObjectReferenceVisitor {

        @Override
        public boolean visitObjectReference(Pointer objRef, boolean compressed) {
            Pointer objAddr = ReferenceAccess.singleton().readObjectAsUntrackedPointer(objRef, compressed);

            final Log trace = StackVerifier.getTraceLog();
            trace.string("  objAddr: ").hex(objAddr);
            trace.newline();
            if (!objAddr.isNull() && !HeapImpl.getHeapImpl().getHeapVerifier().verifyObjectAt(objAddr)) {
                final Log witness = HeapImpl.getHeapImpl().getHeapVerifier().getWitnessLog();
                witness.string("[StackVerifier.verifyFrame:");
                witness.string("  objAddr: ").hex(objAddr);
                witness.string("  fails to verify");
                witness.string("]").newline();
                return false;
            }
            return true;
        }
    }

    private static Log getTraceLog() {
        return (HeapOptions.TraceStackVerification.getValue() ? Log.log() : Log.noopLog());
    }
}

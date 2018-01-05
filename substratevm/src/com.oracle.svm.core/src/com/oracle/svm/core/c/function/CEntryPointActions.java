/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.svm.core.c.function;

import org.graalvm.nativeimage.Isolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.word.WordBase;

/**
 * Advanced entry and leave actions for entry point methods annotated with {@link CEntryPoint}.
 * These are an alternative to automatically entering and leaving a context passed as a parameter
 * and also enable creating an isolate or attaching a thread on demand.
 *
 * A method that is annotated with {@link CEntryPoint} may explicitly call one of the enter* methods
 * (such as {@link #enterCreateIsolate(CEntryPointCreateIsolateParameters)} as the first statement
 * to set up its execution context. A method can additionally choose to call one of the leave*
 * methods before each return. If no leave* methods are called, the leave action defaults to
 * {@link CEntryPointActions#leave()}.
 */
public final class CEntryPointActions {
    private CEntryPointActions() {
    }

    /**
     * Creates a new isolate on entry.
     *
     * @param params initialization parameters.
     * @return 0 on success, otherwise non-zero.
     */
    public static native int enterCreateIsolate(CEntryPointCreateIsolateParameters params);

    /**
     * Creates a context for the current thread in the specified existing isolate, then enters that
     * context.
     *
     * @param isolate existing virtual machine.
     * @return 0 on success, otherwise non-zero.
     */
    public static native int enterAttachThread(Isolate isolate);

    /**
     * Enters an existing context for the current thread (for example, one created with
     * {@link #enterAttachThread(Isolate)}).
     *
     * @param thread existing context for the current thread.
     * @return 0 on success, otherwise non-zero.
     */
    public static native int enter(IsolateThread thread);

    /**
     * Enters an existing context for the current thread that has been created in the given isolate.
     *
     * @param isolate isolate in which a context for the current thread exists.
     * @return 0 on success, otherwise non-zero.
     */
    public static native int enterIsolate(Isolate isolate);

    /**
     * In the prologue, stop execution and return to the entry point method's caller with the given
     * return value. The passed word is cast to the entry point method's return type, which must be
     * a {@link WordBase} type.
     */
    public static native void bailoutInPrologue(WordBase value);

    /**
     * In the prologue, stop execution and return to the entry point method's caller with the given
     * return value. The passed integer is narrowed to the entry point method's return type, which
     * must be one of {@code long}, {@code int}, {@code short}, {@code char}, or {@code byte}.
     */
    public static native void bailoutInPrologue(long value);

    /**
     * In the prologue, stop execution and return to the entry point method's caller with the given
     * return value. The entry point method's return type must be {@code double}, or can also be
     * {@code float}, in which case a cast is applied.
     */
    public static native void bailoutInPrologue(double value);

    /**
     * In the prologue, stop execution and return to the entry point method's caller with the given
     * return value. The entry point method's return type must be {@code boolean}.
     */
    public static native void bailoutInPrologue(boolean value);

    /**
     * In the prologue, stop execution and return to the entry point method's caller. The entry
     * point method's return type must be {@code void}.
     */
    public static native void bailoutInPrologue();

    /**
     * Leaves the current thread's current context.
     *
     * @return 0 on success, otherwise non-zero.
     */
    public static native int leave();

    /**
     * Leaves the current thread's current context, then discards that context.
     *
     * @return 0 on success, otherwise non-zero.
     */
    public static native int leaveDetachThread();

    /**
     * Leaves the current thread's current context, then shuts down all other threads in the
     * context's isolate and discards the isolate entirely.
     *
     * @return 0 on success, otherwise non-zero.
     */
    public static native int leaveTearDownIsolate();

}

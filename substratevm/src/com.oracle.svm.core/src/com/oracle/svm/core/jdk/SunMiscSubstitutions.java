/*
 * Copyright (c) 2012, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.jdk;

// Checkstyle: allow reflection

import static com.oracle.svm.core.annotate.RecomputeFieldValue.Kind.ArrayBaseOffset;
import static com.oracle.svm.core.annotate.RecomputeFieldValue.Kind.ArrayIndexScale;
import static com.oracle.svm.core.annotate.RecomputeFieldValue.Kind.Reset;

import java.io.FileDescriptor;
import java.io.IOException;

import org.graalvm.compiler.nodes.extended.MembarNode;
import org.graalvm.compiler.word.Word;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.MemoryUtil;
import com.oracle.svm.core.UnsafeAccess;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Delete;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.os.OSInterface;
import com.oracle.svm.core.snippets.KnownIntrinsics;

import jdk.vm.ci.code.MemoryBarriers;
import sun.misc.Cleaner;
import sun.misc.JavaAWTAccess;
import sun.misc.Unsafe;

@TargetClass(sun.misc.Unsafe.class)
@SuppressWarnings({"static-method"})
final class Target_sun_misc_Unsafe {

    // Checkstyle: stop

    @Alias @RecomputeFieldValue(kind = ArrayBaseOffset, declClass = boolean[].class, isFinal = true) private static int ARRAY_BOOLEAN_BASE_OFFSET;
    @Alias @RecomputeFieldValue(kind = ArrayBaseOffset, declClass = byte[].class, isFinal = true) private static int ARRAY_BYTE_BASE_OFFSET;
    @Alias @RecomputeFieldValue(kind = ArrayBaseOffset, declClass = short[].class, isFinal = true) private static int ARRAY_SHORT_BASE_OFFSET;
    @Alias @RecomputeFieldValue(kind = ArrayBaseOffset, declClass = char[].class, isFinal = true) private static int ARRAY_CHAR_BASE_OFFSET;
    @Alias @RecomputeFieldValue(kind = ArrayBaseOffset, declClass = int[].class, isFinal = true) private static int ARRAY_INT_BASE_OFFSET;
    @Alias @RecomputeFieldValue(kind = ArrayBaseOffset, declClass = long[].class, isFinal = true) private static int ARRAY_LONG_BASE_OFFSET;
    @Alias @RecomputeFieldValue(kind = ArrayBaseOffset, declClass = float[].class, isFinal = true) private static int ARRAY_FLOAT_BASE_OFFSET;
    @Alias @RecomputeFieldValue(kind = ArrayBaseOffset, declClass = double[].class, isFinal = true) private static int ARRAY_DOUBLE_BASE_OFFSET;
    @Alias @RecomputeFieldValue(kind = ArrayBaseOffset, declClass = Object[].class, isFinal = true) private static int ARRAY_OBJECT_BASE_OFFSET;
    @Alias @RecomputeFieldValue(kind = ArrayIndexScale, declClass = boolean[].class, isFinal = true) private static int ARRAY_BOOLEAN_INDEX_SCALE;
    @Alias @RecomputeFieldValue(kind = ArrayIndexScale, declClass = byte[].class, isFinal = true) private static int ARRAY_BYTE_INDEX_SCALE;
    @Alias @RecomputeFieldValue(kind = ArrayIndexScale, declClass = short[].class, isFinal = true) private static int ARRAY_SHORT_INDEX_SCALE;
    @Alias @RecomputeFieldValue(kind = ArrayIndexScale, declClass = char[].class, isFinal = true) private static int ARRAY_CHAR_INDEX_SCALE;
    @Alias @RecomputeFieldValue(kind = ArrayIndexScale, declClass = int[].class, isFinal = true) private static int ARRAY_INT_INDEX_SCALE;
    @Alias @RecomputeFieldValue(kind = ArrayIndexScale, declClass = long[].class, isFinal = true) private static int ARRAY_LONG_INDEX_SCALE;
    @Alias @RecomputeFieldValue(kind = ArrayIndexScale, declClass = float[].class, isFinal = true) private static int ARRAY_FLOAT_INDEX_SCALE;
    @Alias @RecomputeFieldValue(kind = ArrayIndexScale, declClass = double[].class, isFinal = true) private static int ARRAY_DOUBLE_INDEX_SCALE;
    @Alias @RecomputeFieldValue(kind = ArrayIndexScale, declClass = Object[].class, isFinal = true) private static int ARRAY_OBJECT_INDEX_SCALE;

    // Checkstyle: resume

    @Substitute
    private long allocateMemory(long bytes) {
        if (bytes < 0L || (Unsafe.ADDRESS_SIZE == 4 && bytes > Integer.MAX_VALUE)) {
            throw new IllegalArgumentException();
        }
        Pointer result = UnmanagedMemory.malloc(WordFactory.unsigned(bytes));
        if (result.equal(0)) {
            throw new OutOfMemoryError();
        }
        return result.rawValue();
    }

    @Substitute
    private long reallocateMemory(long address, long bytes) {
        if (bytes == 0) {
            return 0L;
        } else if (bytes < 0L || (Unsafe.ADDRESS_SIZE == 4 && bytes > Integer.MAX_VALUE)) {
            throw new IllegalArgumentException();
        }
        Pointer result;
        if (address != 0L) {
            result = UnmanagedMemory.realloc(WordFactory.unsigned(address), WordFactory.unsigned(bytes));
        } else {
            result = UnmanagedMemory.malloc(WordFactory.unsigned(bytes));
        }
        if (result.equal(0)) {
            throw new OutOfMemoryError();
        }
        return result.rawValue();
    }

    @Substitute
    private void freeMemory(long address) {
        if (address != 0L) {
            UnmanagedMemory.free(WordFactory.unsigned(address));
        }
    }

    @Substitute
    @Uninterruptible(reason = "Converts Object to Pointer.")
    private void copyMemory(Object srcBase, long srcOffset, Object destBase, long destOffset, long bytes) {
        MemoryUtil.copyConjointMemoryAtomic(Word.objectToUntrackedPointer(srcBase).add(WordFactory.signed(srcOffset)), Word.objectToUntrackedPointer(destBase).add(WordFactory.signed(destOffset)),
                        WordFactory.unsigned(bytes));
    }

    @Substitute
    @Uninterruptible(reason = "Converts Object to Pointer.")
    private void setMemory(Object destBase, long destOffset, long bytes, byte bvalue) {
        MemoryUtil.fillToMemoryAtomic(Word.objectToUntrackedPointer(destBase).add(WordFactory.signed(destOffset)), WordFactory.unsigned(bytes), bvalue);
    }

    @Substitute
    private int pageSize() {
        // This assumes that the page size of the Substrate VM
        // is the same as the page size of the hosted VM.
        return Util_sun_misc_Unsafe.hostedVMPageSize;
    }

    @Substitute
    public int arrayBaseOffset(Class<?> clazz) {
        return (int) LayoutEncoding.getArrayBaseOffset(DynamicHub.fromClass(clazz).getLayoutEncoding()).rawValue();
    }

    @Substitute
    public int arrayIndexScale(Class<?> clazz) {
        return LayoutEncoding.getArrayIndexScale(DynamicHub.fromClass(clazz).getLayoutEncoding());
    }

    @Substitute
    private void throwException(Throwable t) {
        /* Make the Java compiler happy by pretending we are throwing a non-checked exception. */
        throw KnownIntrinsics.unsafeCast(t, RuntimeException.class);
    }

    @Substitute
    public void loadFence() {
        final int fence = MemoryBarriers.LOAD_LOAD | MemoryBarriers.LOAD_STORE;
        MembarNode.memoryBarrier(fence);
    }

    @Substitute
    public void storeFence() {
        final int fence = MemoryBarriers.STORE_LOAD | MemoryBarriers.STORE_STORE;
        MembarNode.memoryBarrier(fence);
    }

    @Substitute
    public void fullFence() {
        final int fence = MemoryBarriers.LOAD_LOAD | MemoryBarriers.LOAD_STORE | MemoryBarriers.STORE_LOAD | MemoryBarriers.STORE_STORE;
        MembarNode.memoryBarrier(fence);
    }

    @Substitute
    public void ensureClassInitialized(Class<?> c) {
        if (c == null) {
            throw new NullPointerException();
        }
        // no-op: all classes that exist in our image must have been initialized
    }
}

final class Util_sun_misc_Unsafe {
    /**
     * Cache the size of a page in the hosted VM for use in the SubstrateVM.
     */
    static final int hostedVMPageSize = UnsafeAccess.UNSAFE.pageSize();
}

@TargetClass(sun.misc.MessageUtils.class)
final class Target_sun_misc_MessageUtils {

    @Substitute
    private static void toStderr(String msg) {
        Util_sun_misc_MessageUtils.output(FileDescriptor.err, msg);
    }

    @Substitute
    private static void toStdout(String msg) {
        Util_sun_misc_MessageUtils.output(FileDescriptor.out, msg);
    }
}

final class Util_sun_misc_MessageUtils {

    static void output(FileDescriptor target, String msg) {
        byte[] bytes = new byte[msg.length()];
        for (int i = 0; i < msg.length(); i++) {
            bytes[i] = (byte) msg.charAt(i);
        }

        OSInterface os = ConfigurationValues.getOSInterface();
        try {
            os.writeBytes(target, bytes);
        } catch (IOException ex) {
            // Ignore, since we are in low-level debug printing code.
        }
    }
}

@TargetClass(sun.misc.Cleaner.class)
final class Target_sun_misc_Cleaner {
    @Alias @RecomputeFieldValue(kind = Reset)//
    private static Cleaner first;
}

@TargetClass(sun.misc.SharedSecrets.class)
final class Target_sun_misc_SharedSecrets {
    @Substitute
    private static JavaAWTAccess getJavaAWTAccess() {
        return null;
    }
}

@TargetClass(value = sun.misc.URLClassPath.class, innerClass = "JarLoader")
@Delete
final class Target_sun_misc_URLClassPath_JarLoader {
}

@TargetClass(java.net.JarURLConnection.class)
@Delete
final class Target_java_net_JarURLConnection {
}

/** Dummy class to have a class with the file's name. */
public final class SunMiscSubstitutions {
}

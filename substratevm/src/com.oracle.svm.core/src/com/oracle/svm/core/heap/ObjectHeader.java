/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.heap;

import org.graalvm.compiler.word.ObjectAccess;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.snippets.KnownIntrinsics;

/**
 * Manipulations of an Object header.
 * <p>
 * An ObjectHeader is a Pointer-sized collection of bits in each Object instance. It holds
 * meta-information about this instance. The ObjectHeader holds a DynamicHub, which identifies the
 * Class of the instance, and flags used by the garbage collector. Alternatively, e.g., during
 * garbage collection, the ObjectHeader may hold a forwarding pointer to the new location of this
 * instance if the Object has been moved by the collector.
 * <p>
 * I treat an ObjectHeader as an Unsigned, until careful examination allows me to cast it to a
 * Pointer, or to an Object. Since an ObjectHeader is just a collection of bits, rather than a
 * Object, the methods in this class are all static methods.
 * <p>
 * These methods operate on a bewildering mixture of Object *or* Pointer. Because a Pointer *is* an
 * Object, the methods have different names, rather than just different signatures. Also a mixture
 * of Object and Unsigned (and Unsigned) to distinguish methods that read from Objects, from methods
 * that operate on a previously read ObjectHeader, or methods that operate on just the low-order
 * bits of an ObjectHeader. The variants that take an Object as a parameter have the reasonable
 * names because they are used from outside. The variants that take an Unsigned ObjectHeader have
 * "Header" in their name or the name of the argument, and the variants that take an Unsigned with
 * just the header bits in them have "HeaderBits" in their name or in the name of the argument. (I
 * hope I did that consistently.)
 */

public abstract class ObjectHeader {

    /*
     * Read and write of Object headers.
     *
     * These know that Object headers are one Word.
     */

    public static UnsignedWord readHeaderFromPointer(Pointer p) {
        return p.readWord(getHubOffset());
    }

    public static UnsignedWord readHeaderFromObject(Object o) {
        return ObjectAccess.readWord(o, getHubOffset());
    }

    protected static void writeHeaderToObject(Object o, UnsignedWord value) {
        ObjectAccess.writeWord(o, getHubOffset(), value);
    }

    public static DynamicHub readDynamicHubFromObject(Object o) {
        return KnownIntrinsics.readHub(o);
    }

    public static void writeDynamicHubToPointer(Pointer p, DynamicHub hub) {
        p.writeObject(getHubOffset(), hub);
    }

    /** Decode a DynamicHub from an Object header. */
    protected static DynamicHub dynamicHubFromObjectHeader(UnsignedWord header) {
        // Turn the Unsigned header into a Pointer, and then to an Object of type DynamicHub.
        final UnsignedWord pointerBits = clearBits(header);
        final Pointer pointerValue = (Pointer) pointerBits;
        final Object objectValue = pointerValue.toObject();
        final DynamicHub result = KnownIntrinsics.unsafeCast(objectValue, DynamicHub.class);
        return result;
    }

    /*
     * Unpacking methods.
     */

    /** Clear the object header bits from a header. */
    protected static UnsignedWord clearBits(UnsignedWord header) {
        return header.and(BITS_CLEAR);
    }

    /*
     * Forwarding pointer methods.
     */

    /** Is this header a forwarding pointer? */
    public abstract boolean isForwardedHeader(UnsignedWord header);

    /** Extract a forwarding Pointer from a header. */
    public abstract Pointer getForwardingPointer(UnsignedWord header);

    /** Extract a forwarded Object from a header. */
    public abstract Object getForwardedObject(UnsignedWord header);

    /*
     * ObjectHeaders record (among other things) if it was allocated by a SystemAllocator or in the
     * heap. If the object is in the heap, it might be either aligned or unaligned.
     *
     * The default is heap-allocated aligned objects, so the others have "set" methods.
     */

    /** A special method for use during native image construction. */
    @Platforms(Platform.HOSTED_ONLY.class)
    public abstract long setBootImageOnLong(long l);

    /** Objects are aligned by default. This marks them as unaligned. */
    protected abstract void setUnaligned(Object o);

    /*
     * Complex predicates.
     */

    protected abstract boolean isHeapAllocated(Object o);

    public abstract boolean isNonHeapAllocatedHeader(UnsignedWord header);

    protected abstract boolean isNonHeapAllocated(Object o);

    public abstract boolean isAlignedObject(Object o);

    public abstract boolean isUnalignedObject(Object o);

    /*
     * Convenience methods.
     */

    private static int getHubOffset() {
        return ConfigurationValues.getObjectLayout().getHubOffset();
    }

    /*
     * Debugging.
     */

    public String toStringFromObject(Object o) {
        final UnsignedWord header = readHeaderFromObject(o);
        return toStringFromHeader(header);
    }

    public abstract String toStringFromHeader(UnsignedWord header);

    // Private static final state.

    /** Constructor for concrete subclasses. */
    protected ObjectHeader() {
        // All-static class: no instances.
    }

    // Constants.

    protected static final UnsignedWord BITS_MASK = WordFactory.unsigned(0b111);
    public static final UnsignedWord BITS_CLEAR = BITS_MASK.not();
}

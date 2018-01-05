/*
 * Copyright (c) 2014, 2017, Oracle and/or its affiliates. All rights reserved.
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

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.config.ObjectLayout;
import com.oracle.svm.core.heap.ReferenceMapEncoder.OffsetIterator;

import jdk.vm.ci.code.ReferenceMap;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.meta.Value;

public class SubstrateReferenceMap extends ReferenceMap implements ReferenceMapEncoder.Input {

    public static int getSlotSizeInBytes() {
        ObjectLayout layout = ConfigurationValues.getObjectLayout();
        return Math.min(layout.getReferenceSize(), layout.getCompressedReferenceSize());
    }

    private final BitSet input;
    private final boolean defaultCompressed;
    private BitSet nondefaultInput;

    private Map<Integer, Object> debugAllUsedRegisters;
    private Map<Integer, Object> debugAllUsedStackSlots;

    public SubstrateReferenceMap() {
        this(new BitSet());
    }

    public SubstrateReferenceMap(BitSet input) {
        this.input = input;
        this.defaultCompressed = SubstrateOptions.UseHeapBaseRegister.getValue();
    }

    public boolean isIndexMarked(int index) {
        return input.get(index);
    }

    public boolean isIndexCompressed(int index) {
        boolean compressed = defaultCompressed;
        if (nondefaultInput != null) {
            compressed ^= nondefaultInput.get(index);
        }
        return compressed;
    }

    public void markReferenceAtIndex(int index) {
        markReferenceAtIndex(index, defaultCompressed);
    }

    public void markReferenceAtIndex(int index, boolean compressed) {
        assert isValidToMark(index, compressed) : "already marked or would overlap with predecessor or successor";
        input.set(index);
        if (compressed != defaultCompressed) {
            if (nondefaultInput == null) {
                nondefaultInput = new BitSet(index + 1);
            }
            nondefaultInput.set(index);
        }
    }

    private boolean isValidToMark(int index, boolean isCompressed) {
        int slotSizeInBytes = getSlotSizeInBytes();
        int uncompressedSlots = ConfigurationValues.getObjectLayout().getReferenceSize() / slotSizeInBytes;
        int compressedSlots = ConfigurationValues.getObjectLayout().getCompressedReferenceSize() / slotSizeInBytes;
        assert compressedSlots <= uncompressedSlots;

        int previousIndex = input.previousSetBit(index - 1);
        if (previousIndex != -1) {
            int previousSlots = isIndexCompressed(previousIndex) ? compressedSlots : uncompressedSlots;
            if (previousIndex + previousSlots > index) {
                return false;
            }
        }
        int slots = isCompressed ? compressedSlots : uncompressedSlots;
        int nextIndex = input.nextSetBit(index);
        return (nextIndex == -1) || (index + slots <= nextIndex);
    }

    public Map<Integer, Object> getDebugAllUsedRegisters() {
        return debugAllUsedRegisters;
    }

    boolean debugMarkRegister(int idx, Value value) {
        if (debugAllUsedRegisters == null) {
            debugAllUsedRegisters = new HashMap<>();
        }
        debugAllUsedRegisters.put(idx, value);
        return true;
    }

    public Map<Integer, Object> getDebugAllUsedStackSlots() {
        return debugAllUsedStackSlots;
    }

    boolean debugMarkStackSlot(int idx, StackSlot value) {
        if (debugAllUsedStackSlots == null) {
            debugAllUsedStackSlots = new HashMap<>();
        }
        debugAllUsedStackSlots.put(idx, value);
        return true;
    }

    @Override
    public boolean isEmpty() {
        return input.isEmpty();
    }

    @Override
    public OffsetIterator getOffsets() {
        return new OffsetIterator() {
            private int nextIndex = input.nextSetBit(0);

            @Override
            public boolean hasNext() {
                return (nextIndex != -1);
            }

            @Override
            public int nextInt() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                int index = nextIndex;
                nextIndex = input.nextSetBit(index + 1);
                return index;
            }

            @Override
            public boolean isNextCompressed() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return isIndexCompressed(nextIndex);
            }
        };
    }

    @Override
    public int hashCode() {
        return (input.hashCode() * 31 + Boolean.hashCode(defaultCompressed)) * 31 + Objects.hashCode(nondefaultInput);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        } else if (obj instanceof SubstrateReferenceMap) {
            SubstrateReferenceMap other = (SubstrateReferenceMap) obj;
            return Objects.equals(input, other.input) && defaultCompressed == other.defaultCompressed && Objects.equals(nondefaultInput, other.nondefaultInput);
        } else {
            return false;
        }
    }
}

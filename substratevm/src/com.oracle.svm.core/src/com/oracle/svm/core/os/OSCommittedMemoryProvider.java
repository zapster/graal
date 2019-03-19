/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.os;

import static com.oracle.svm.core.Isolates.IMAGE_HEAP_WRITABLE_BEGIN;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_WRITABLE_END;
import static org.graalvm.word.WordFactory.nullPointer;

import org.graalvm.compiler.word.Word;
import org.graalvm.nativeimage.Feature;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.c.function.CEntryPointContext;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.Isolates;
import com.oracle.svm.core.MemoryUtil;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.c.function.CEntryPointCreateIsolateParameters;
import com.oracle.svm.core.c.function.CEntryPointErrors;
import com.oracle.svm.core.c.function.CEntryPointSetup;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.os.VirtualMemoryProvider.Access;
import com.oracle.svm.core.util.PointerUtils;
import com.oracle.svm.core.util.UnsignedUtils;

@AutomaticFeature
class OSCommittedMemoryProviderFeature implements Feature {
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        if (!ImageSingletons.contains(CommittedMemoryProvider.class)) {
            ImageSingletons.add(CommittedMemoryProvider.class, new OSCommittedMemoryProvider());
        }
    }
}

public class OSCommittedMemoryProvider implements CommittedMemoryProvider {

    @Override
    @Uninterruptible(reason = "Still being initialized.")
    public int initialize(WordPointer isolatePointer, CEntryPointCreateIsolateParameters parameters) {
        if (!SubstrateOptions.SpawnIsolates.getValue()) {
            isolatePointer.write(CEntryPointSetup.SINGLE_ISOLATE_SENTINEL);
            return CEntryPointErrors.NO_ERROR;
        }

        Word begin = Isolates.IMAGE_HEAP_BEGIN.get();
        Word size = Isolates.IMAGE_HEAP_END.get().subtract(begin);

        Pointer heap = VirtualMemoryProvider.get().commit(nullPointer(), size, Access.READ | Access.WRITE);
        if (heap.isNull()) {
            return CEntryPointErrors.MAP_HEAP_FAILED;
        }

        MemoryUtil.copyConjointMemoryAtomic(begin, heap, size);

        UnsignedWord pageSize = getGranularity();
        UnsignedWord writableBeginPageOffset = UnsignedUtils.roundDown(IMAGE_HEAP_WRITABLE_BEGIN.get().subtract(begin), pageSize);
        if (writableBeginPageOffset.aboveThan(0)) {
            if (VirtualMemoryProvider.get().protect(heap, writableBeginPageOffset, Access.READ) != 0) {
                return CEntryPointErrors.PROTECT_HEAP_FAILED;
            }
        }
        UnsignedWord writableEndPageOffset = UnsignedUtils.roundUp(IMAGE_HEAP_WRITABLE_END.get().subtract(begin), pageSize);
        if (writableEndPageOffset.belowThan(size)) {
            Pointer afterWritableBoundary = heap.add(writableEndPageOffset);
            Word afterWritableSize = size.subtract(writableEndPageOffset);
            if (VirtualMemoryProvider.get().protect(afterWritableBoundary, afterWritableSize, Access.READ) != 0) {
                return CEntryPointErrors.PROTECT_HEAP_FAILED;
            }
        }

        isolatePointer.write(heap);
        return CEntryPointErrors.NO_ERROR;
    }

    @Override
    @Uninterruptible(reason = "Tear-down in progress.")
    public int tearDown() {
        if (!SubstrateOptions.SpawnIsolates.getValue()) {
            return CEntryPointErrors.NO_ERROR;
        }

        PointerBase heapBase = Isolates.getHeapBase(CEntryPointContext.getCurrentIsolate());
        Word size = Isolates.IMAGE_HEAP_END.get().subtract(Isolates.IMAGE_HEAP_BEGIN.get());
        if (VirtualMemoryProvider.get().free(heapBase, size) != 0) {
            return CEntryPointErrors.MAP_HEAP_FAILED;
        }
        return CEntryPointErrors.NO_ERROR;
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public UnsignedWord getGranularity() {
        return VirtualMemoryProvider.get().getGranularity();
    }

    /**
     * Allocate the requested amount of virtual memory at the requested alignment.
     *
     * @return A Pointer to the aligned memory, or a null Pointer.
     */
    @Override
    public Pointer allocate(UnsignedWord size, UnsignedWord alignment, boolean executable) {
        final int access = Access.READ | Access.WRITE | (executable ? Access.EXECUTE : 0);

        if (alignment.equal(UNALIGNED)) {
            Pointer start = VirtualMemoryProvider.get().commit(nullPointer(), size, access);
            if (start.isNonNull()) {
                trackVirtualMemory(size);
            }
            return start;
        }

        // This happens in stages:
        // (1) Reserve a container that is large enough for the requested size *and* the alignment.
        // (2) Locate the result at the requested alignment within the container.
        // (3) Clean up any over-allocated prefix and suffix pages.

        // All communication with mmap and munmap happen in terms of page_sized objects.
        final UnsignedWord pageSize = getGranularity();
        // (1) Reserve a container that is large enough for the requested size *and* the alignment.
        // - The container occupies the open-right interval [containerStart .. containerEnd).
        // - This will be too big, but I'll give back the extra later.
        final UnsignedWord containerSize = alignment.add(size);
        final UnsignedWord pagedContainerSize = UnsignedUtils.roundUp(containerSize, pageSize);
        final Pointer containerStart = VirtualMemoryProvider.get().commit(nullPointer(), pagedContainerSize, access);
        if (containerStart.isNull()) {
            // No exception is needed: this is just a failure to reserve the virtual address space.
            return nullPointer();
        }
        trackVirtualMemory(pagedContainerSize);
        final Pointer containerEnd = containerStart.add(pagedContainerSize);
        // (2) Locate the result at the requested alignment within the container.
        // - The result occupies [start .. end).
        final Pointer start = PointerUtils.roundUp(containerStart, alignment);
        final Pointer end = start.add(size);
        if (virtualMemoryVerboseDebugging) {
            Log.log().string("allocate(size: ").unsigned(size).string(" ").hex(size).string(", alignment: ").unsigned(alignment).string(" ").hex(alignment).string(")").newline();
            Log.log().string("  container:   [").hex(containerStart).string(" .. ").hex(containerEnd).string(")").newline();
            Log.log().string("  result:      [").hex(start).string(" .. ").hex(end).string(")").newline();
        }
        // (3) Clean up any over-allocated prefix and suffix pages.
        // - The prefix occupies [containerStart .. pagedStart).
        final Pointer pagedStart = PointerUtils.roundDown(start, pageSize);
        final Pointer prefixStart = containerStart;
        final Pointer prefixEnd = pagedStart;
        final UnsignedWord prefixSize = prefixEnd.subtract(prefixStart);
        if (prefixSize.aboveOrEqual(pageSize)) {
            if (virtualMemoryVerboseDebugging) {
                Log.log().string("  prefix:      [").hex(prefixStart).string(" .. ").hex(prefixEnd).string(")").newline();
            }
            final boolean prefixUnmap = (VirtualMemoryProvider.get().free(prefixStart, prefixSize) == 0);
            if (!prefixUnmap) {
                // Throwing an exception would be better.
                // If this unmap fails, I will have reserved virtual address space
                // that I won't be able to give back.
                return nullPointer();
            }
            untrackVirtualMemory(prefixSize);
        }
        // - The suffix occupies [pagedEnd .. containerEnd).
        final Pointer pagedEnd = PointerUtils.roundUp(end, pageSize);
        final Pointer suffixStart = pagedEnd;
        final Pointer suffixEnd = containerEnd;
        final UnsignedWord suffixSize = suffixEnd.subtract(suffixStart);
        if (suffixSize.aboveOrEqual(pageSize)) {
            if (virtualMemoryVerboseDebugging) {
                Log.log().string("  suffix:      [").hex(suffixStart).string(" .. ").hex(suffixEnd).string(")").newline();
            }
            final boolean suffixUnmap = (VirtualMemoryProvider.get().free(suffixStart, suffixSize) == 0);
            if (!suffixUnmap) {
                // Throwing an exception would be better.
                // If this unmap fails, I will have reserved virtual address space
                // that I won't be able to give back.
                return nullPointer();
            }
            untrackVirtualMemory(suffixSize);
        }
        return start;
    }

    @Override
    public boolean free(PointerBase start, UnsignedWord nbytes, UnsignedWord alignment, boolean executable) {
        final UnsignedWord pageSize = getGranularity();
        // Re-discover the paged-aligned ends of the memory region.
        final Pointer end = ((Pointer) start).add(nbytes);
        final Pointer pagedStart = PointerUtils.roundDown(start, pageSize);
        final Pointer pagedEnd = PointerUtils.roundUp(end, pageSize);
        final UnsignedWord pagedSize = pagedEnd.subtract(pagedStart);
        // Return that virtual address space to the operating system.
        untrackVirtualMemory(pagedSize);
        return (VirtualMemoryProvider.get().free(pagedStart, pagedSize) == 0);
    }

    protected void trackVirtualMemory(UnsignedWord size) {
        tracker.track(size);
    }

    protected void untrackVirtualMemory(UnsignedWord size) {
        tracker.untrack(size);
    }

    // Verbose debugging.
    private static final boolean virtualMemoryVerboseDebugging = false;

    private final VirtualMemoryTracker tracker = new VirtualMemoryTracker();

    protected static class VirtualMemoryTracker {

        private UnsignedWord totalAllocated;

        protected VirtualMemoryTracker() {
            this.totalAllocated = WordFactory.zero();
        }

        public void track(UnsignedWord size) {
            totalAllocated = totalAllocated.add(size);
        }

        public void untrack(UnsignedWord size) {
            totalAllocated = totalAllocated.subtract(size);
        }
    }
}

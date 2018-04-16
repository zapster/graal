/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.posix;

import static com.oracle.svm.core.posix.headers.Mman.MAP_ANON;
import static com.oracle.svm.core.posix.headers.Mman.MAP_FAILED;
import static com.oracle.svm.core.posix.headers.Mman.MAP_PRIVATE;
import static com.oracle.svm.core.posix.headers.Mman.PROT_EXEC;
import static com.oracle.svm.core.posix.headers.Mman.PROT_READ;
import static com.oracle.svm.core.posix.headers.Mman.PROT_WRITE;
import static com.oracle.svm.core.posix.headers.Mman.mmap;
import static com.oracle.svm.core.posix.headers.Mman.munmap;

import org.graalvm.nativeimage.Feature;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platform.LINUX;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.UnsafeAccess;
import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.os.VirtualMemoryProvider;
import com.oracle.svm.core.posix.linux.LinuxOSVirtualMemoryProvider;
import com.oracle.svm.core.util.PointerUtils;
import com.oracle.svm.core.util.UnsignedUtils;

@AutomaticFeature
class PosixVirtualMemoryProviderFeature implements Feature {
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        if (!ImageSingletons.contains(VirtualMemoryProvider.class)) {
            VirtualMemoryProvider provider = Platform.includedIn(LINUX.class) ? new LinuxOSVirtualMemoryProvider() : new PosixOSVirtualMemoryProvider();
            ImageSingletons.add(VirtualMemoryProvider.class, provider);
        }
    }
}

public class PosixOSVirtualMemoryProvider implements VirtualMemoryProvider {
    public UnsignedWord getPageSize() {
        return WordFactory.unsigned(UnsafeAccess.UNSAFE.pageSize());
    }

    @Override
    public Pointer allocateVirtualMemory(UnsignedWord size, boolean executable) {
        trackVirtualMemory(size);
        int protect = PROT_READ() | PROT_WRITE() | (executable ? PROT_EXEC() : 0);
        int flags = MAP_ANON() | MAP_PRIVATE();
        final Pointer result = mmap(WordFactory.nullPointer(), size, protect, flags, -1, 0);
        if (result.equal(MAP_FAILED())) {
            return WordFactory.nullPointer();
        }
        return result;
    }

    @Override
    public boolean freeVirtualMemory(PointerBase start, UnsignedWord size) {
        untrackVirtualMemory(size);
        final int unmapResult = munmap(start, size);
        return (unmapResult == 0);
    }

    /**
     * Allocate the requested amount of virtual memory at the requested alignment.
     *
     * @return A Pointer to the aligned memory, or a null Pointer.
     */
    @Override
    public Pointer allocateVirtualMemoryAligned(UnsignedWord size, UnsignedWord alignment) {
        // This happens in stages:
        // (1) Reserve a container that is large enough for the requested size *and* the alignment.
        // (2) Locate the result at the requested alignment within the container.
        // (3) Clean up any over-allocated prefix and suffix pages.

        // All communication with mmap and munmap happen in terms of page_sized objects.
        final UnsignedWord pageSize = getPageSize();
        // (1) Reserve a container that is large enough for the requested size *and* the alignment.
        // - The container occupies the open-right interval [containerStart .. containerEnd).
        // - This will be too big, but I'll give back the extra later.
        final UnsignedWord containerSize = alignment.add(size);
        final UnsignedWord pagedContainerSize = UnsignedUtils.roundUp(containerSize, pageSize);
        final Pointer containerStart = allocateVirtualMemory(pagedContainerSize, false);
        if (containerStart.isNull()) {
            // No exception is needed: this is just a failure to reserve the virtual address space.
            return WordFactory.nullPointer();
        }
        final Pointer containerEnd = containerStart.add(pagedContainerSize);
        // (2) Locate the result at the requested alignment within the container.
        // - The result occupies [start .. end).
        final Pointer start = PointerUtils.roundUp(containerStart, alignment);
        final Pointer end = start.add(size);
        if (virtualMemoryVerboseDebugging) {
            Log.log().string("allocateVirtualMemoryAligned(size: ").unsigned(size).string(" ").hex(size).string(", alignment: ").unsigned(alignment).string(" ").hex(alignment).string(")").newline();
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
            final boolean prefixUnmap = freeVirtualMemory(prefixStart, prefixSize);
            if (!prefixUnmap) {
                // Throwing an exception would be better.
                // If this unmap fails, I will have reserved virtual address space
                // that I won't be able to give back.
                return WordFactory.nullPointer();
            }
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
            final boolean suffixUnmap = freeVirtualMemory(suffixStart, suffixSize);
            if (!suffixUnmap) {
                // Throwing an exception would be better.
                // If this unmap fails, I will have reserved virtual address space
                // that I won't be able to give back.
                return WordFactory.nullPointer();
            }
        }
        return start;
    }

    @Override
    public boolean freeVirtualMemoryAligned(PointerBase start, UnsignedWord size, UnsignedWord alignment) {
        final UnsignedWord pageSize = getPageSize();
        // Re-discover the paged-aligned ends of the memory region.
        final Pointer end = ((Pointer) start).add(size);
        final Pointer pagedStart = PointerUtils.roundDown(start, pageSize);
        final Pointer pagedEnd = PointerUtils.roundUp(end, pageSize);
        final UnsignedWord pagedSize = pagedEnd.subtract(pagedStart);
        // Return that virtual address space to the operating system.
        return freeVirtualMemory(pagedStart, pagedSize);
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

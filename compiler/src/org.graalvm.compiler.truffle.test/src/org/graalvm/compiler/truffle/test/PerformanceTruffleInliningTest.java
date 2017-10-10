/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.truffle.test;

import java.util.concurrent.TimeUnit;

import org.graalvm.compiler.truffle.OptimizedCallTarget;
import org.graalvm.compiler.truffle.TruffleInlining;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class PerformanceTruffleInliningTest extends TruffleInliningTest {

    @Test
    public void testThreeTangledRecursions() {
        // @formatter:off
        OptimizedCallTarget target = builder.
                target("three").
                    calls("three").
                    calls("two").
                    calls("one").
                target("two").
                    calls("two").
                    calls("one").
                    calls("three").
                target("one").
                    calls("one").
                    calls("two").
                    calls("three").
                buildTarget();
        // @formatter:on
        assertDecidingTakesLessThan(target, 500);
    }

    @Test
    public void testFourTangledRecursions() {
        // @formatter:off
        OptimizedCallTarget target = builder.
                target("four").
                    calls("four").
                    calls("three").
                    calls("two").
                    calls("one").
                target("three").
                    calls("three").
                    calls("two").
                    calls("one").
                target("two").
                    calls("two").
                    calls("one").
                    calls("three").
                    calls("four").
                target("one").
                    calls("one").
                    calls("two").
                    calls("three").
                    calls("four").
                buildTarget();
        // @formatter:on
        assertDecidingTakesLessThan(target, 500);
    }

    @Test
    public void testTangledGraph() {
        int depth = 15;
        for (int i = 0; i < depth; i++) {
            builder.target(Integer.toString(i));
            for (int j = i; j < depth; j++) {
                builder.calls(Integer.toString(j));
            }
        }
        OptimizedCallTarget target = builder.target("main").calls("0").buildTarget();
        assertDecidingTakesLessThan(target, 500);

    }

    long targetCount = 0;

    private void hugeGraphBuilderHelper(final int depth, final int width, final String targetIndex) {
        builder.target(targetIndex);
        targetCount++;
        if (depth == 0) {
            return;
        }
        for (int i = 0; i < width; i++) {
            builder.calls(targetIndex + i);
        }
        for (int i = 0; i < width; i++) {
            hugeGraphBuilderHelper(depth - 1, width, targetIndex + i);
        }
    }

    @Test
    @Ignore("Flaky. Sometimes (very rarely) takes over 500ms. Seems that this can only be due to outside interference during execution")
    public void testHugeGraph() {
        hugeGraphBuilderHelper(10, 4, "1");
        OptimizedCallTarget target = builder.target("main").calls("1").buildTarget();
        assertDecidingTakesLessThan(target, 500);

    }

    protected void assertDecidingTakesLessThan(OptimizedCallTarget target, long maxDuration) {
        long duration = Long.MAX_VALUE;
        for (int i = 0; i < 10; i++) {
            duration = Math.min(executionTime(target), duration);
        }
        Assert.assertTrue("Took too long: " + TimeUnit.NANOSECONDS.toMillis(duration) + "ms", duration < TimeUnit.MILLISECONDS.toNanos(maxDuration));
    }

    @SuppressWarnings("unused")
    protected long executionTime(OptimizedCallTarget target) {
        long start = System.nanoTime();
        TruffleInlining decisions = new TruffleInlining(target, policy);
        return System.nanoTime() - start;
    }

}

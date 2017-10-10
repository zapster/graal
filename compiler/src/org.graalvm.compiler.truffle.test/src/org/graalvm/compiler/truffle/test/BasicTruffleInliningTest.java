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

import static org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleFunctionInlining;
import static org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleInliningMaxCallerSize;
import static org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleMaximumRecursiveInlining;
import static org.graalvm.compiler.truffle.TruffleCompilerOptions.overrideOptions;

import org.graalvm.compiler.truffle.TruffleCompilerOptions;
import org.graalvm.compiler.truffle.TruffleInlining;
import org.junit.Assert;
import org.junit.Test;

public class BasicTruffleInliningTest extends TruffleInliningTest {

    @Test
    public void testSimpleInline() {
        // @formatter:off
        TruffleInlining decisions = builder.
                target("callee").
                target("caller").
                    calls("callee").
                buildDecisions();
        // @formatter:on
        assertInlined(decisions, "callee");
    }

    @Test
    public void testMultipleInline() {
        // @formatter:off
        TruffleInlining decisions = builder.
                target("callee").
                target("caller").
                    calls("callee").
                    calls("callee").
                buildDecisions();
        // @formatter:on
        Assert.assertTrue(countInlines(decisions, "callee") == 2);

        int inlineCount = 100;
        builder.target("callee").target("caller", inlineCount);
        for (int i = 0; i < inlineCount; i++) {
            builder.calls("callee");
        }
        Assert.assertEquals(inlineCount, countInlines(builder.buildDecisions(), "callee"));
    }

    @Test
    public void testInlineBigFunctions() {
        // @formatter:off
        TruffleInlining decisions = builder.
                target("callee", TruffleCompilerOptions.getValue(TruffleInliningMaxCallerSize) - 3).
                target("caller").
                    calls("callee").
                buildDecisions();
        // @formatter:on
        assertInlined(decisions, "callee");
    }

    @Test
    public void testDontInlineBigFunctions() {
        // @formatter:off
        TruffleInlining decisions = builder.
                target("callee", TruffleCompilerOptions.getValue(TruffleInliningMaxCallerSize)).
                target("caller").
                    calls("callee").
                buildDecisions();
        // @formatter:on
        assertNotInlined(decisions, "callee");
    }

    @Test
    public void testInlineIntoBigFunctions() {
        // @formatter:off
        TruffleInlining decisions = builder.
                target("callee").
                target("caller", TruffleCompilerOptions.getValue(TruffleInliningMaxCallerSize) - 3).
                calls("callee").
                buildDecisions();
        // @formatter:on
        assertInlined(decisions, "callee");
    }

    @Test
    public void testDontInlineIntoBigFunctions() {
        // @formatter:off
        TruffleInlining decisions = builder.
                target("callee").
                target("caller", TruffleCompilerOptions.getValue(TruffleInliningMaxCallerSize)).
                    calls("callee").
                buildDecisions();
        // @formatter:on
        assertNotInlined(decisions, "callee");
    }

    @Test
    public void testRecursiveInline() {
        TruffleInlining decisions = builder.target("recursive").calls("recursive").buildDecisions();
        Assert.assertEquals(TruffleCompilerOptions.getValue(TruffleMaximumRecursiveInlining).intValue(), countInlines(decisions, "recursive"));
    }

    @Test
    public void testDoubleRecursiveInline() {
        TruffleInlining decisions = builder.target("recursive").calls("recursive").calls("recursive").buildDecisions();
        int n = TruffleCompilerOptions.getValue(TruffleMaximumRecursiveInlining).intValue();
        long geometricSum = 2 * (1 - ((long) Math.pow(2, n))) / (1 - 2); // sum of geometric
                                                                         // progression a*r^n is
                                                                         // (a(1-r^n))/(1-r)
                                                                         // for 2*2^n it is
                                                                         // 2*(1-2^n)/(1-2)
        Assert.assertEquals(geometricSum, countInlines(decisions, "recursive"));
    }

    @Test
    public void testIndirectRecursiveInline() {
        // @formatter:off
        TruffleInlining decisions = builder.
                target("callee").
                    calls("recursive").
                target("recursive").
                    calls("callee").
                buildDecisions();
        // @formatter:on
        Assert.assertEquals(TruffleCompilerOptions.getValue(TruffleMaximumRecursiveInlining).intValue(), countInlines(decisions, "recursive"));
        Assert.assertEquals(TruffleCompilerOptions.getValue(TruffleMaximumRecursiveInlining) + 1, countInlines(decisions, "callee"));
    }

    @Test
    public void testInlineBigWithCallSites() {
        // @formatter:off
        TruffleInlining decisions = builder.
                target("callee", (TruffleCompilerOptions.getValue(TruffleInliningMaxCallerSize) / 3) - 3).
                target("caller").
                    calls("callee").
                    calls("callee").
                    calls("callee").
                buildDecisions(true);
        // @formatter:on
        Assert.assertEquals(3, countInlines(decisions, "callee"));
    }

    @Test
    public void testDontInlineBigWithCallSites() {
        // Do not inline a function if it's size * cappedCallSites is too big
        // @formatter:off
        TruffleInlining decisions = builder.
                target("callee", TruffleCompilerOptions.getValue(TruffleInliningMaxCallerSize) / 3).
                target("caller").
                    calls("callee").
                    calls("callee").
                    calls("callee").
                buildDecisions(true);
        // @formatter:on
        assertNotInlined(decisions, "callee");
        Assert.assertTrue("Wrong reason for not inlining!", decisions.getCallSites().get(0).getProfile().getFailedReason().startsWith("deepNodeCount * callSites  >"));
    }

    @Test
    public void testDeepInline() {
        // Limited to 14 at the moment because of TruffleInlining:97
        int depth = 14;
        builder.target("0");
        for (Integer count = 0; count < depth; count++) {
            Integer nextCount = count + 1;
            builder.target(nextCount.toString()).calls(count.toString());
        }
        final int[] inlineDepth = {0};
        TruffleInlining decisions = builder.buildDecisions();
        traverseDecisions(decisions.getCallSites(), decision -> {
            Assert.assertTrue(decision.isInline());
            inlineDepth[0]++;
        });
        Assert.assertEquals(depth, inlineDepth[0]);
    }

    @Test
    public void testWideInline() {
        int width = 1000;
        builder.target("leaf").target("main");
        for (Integer i = 0; i < width; i++) {
            builder.calls("leaf");
        }
        TruffleInlining decisions = builder.buildDecisions();
        Assert.assertEquals(width, countInlines(decisions, "leaf"));
    }

    @Test
    public void testFrequency() {
        // @formatter:off
        TruffleInlining decisions = builder.
                target("callee").
                target("caller").execute(4).
                    calls("callee", 2).
                buildDecisions();
        // @formatter:on
        assertInlined(decisions, "callee");
        Assert.assertEquals(0.5, decisions.getCallSites().get(0).getProfile().getFrequency(), 0);
    }

    @Test
    @SuppressWarnings("try")
    public void testTruffleFunctionInliningFlag() {
        try (TruffleCompilerOptions.TruffleOptionsOverrideScope scope = overrideOptions(TruffleFunctionInlining, false)) {
            // @formatter:off
            TruffleInlining decisions = builder.
                    target("callee").
                    target("caller").
                        calls("callee", 2).
                    buildDecisions();
            // @formatter:on
            Assert.assertTrue("Decisions where made!", decisions.getCallSites().isEmpty());
        }
    }
}

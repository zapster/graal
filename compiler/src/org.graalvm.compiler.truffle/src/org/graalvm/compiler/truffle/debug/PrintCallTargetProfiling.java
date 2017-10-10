/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.truffle.debug;

import static org.graalvm.compiler.truffle.TruffleCompilerOptions.TruffleCallTargetProfiling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.graalvm.compiler.truffle.GraalTruffleRuntime;
import org.graalvm.compiler.truffle.OptimizedCallTarget;
import org.graalvm.compiler.truffle.OptimizedCompilationProfile;
import org.graalvm.compiler.truffle.TraceCompilationProfile;
import org.graalvm.compiler.truffle.TruffleCompilerOptions;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;

public final class PrintCallTargetProfiling extends AbstractDebugCompilationListener {

    public static void install(GraalTruffleRuntime runtime) {
        if (TruffleCompilerOptions.getValue(TruffleCallTargetProfiling)) {
            runtime.addCompilationListener(new PrintCallTargetProfiling());
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void notifyShutdown(GraalTruffleRuntime runtime) {

        Map<OptimizedCallTarget, List<OptimizedCallTarget>> groupedTargets = new HashMap<>();
        for (RootCallTarget target : Truffle.getRuntime().getCallTargets()) {
            OptimizedCallTarget optimizedTarget = (OptimizedCallTarget) target;
            List<OptimizedCallTarget> targetsList = groupedTargets.get(optimizedTarget);
            if (targetsList == null) {
                targetsList = new ArrayList<>();
                groupedTargets.put(optimizedTarget, targetsList);
            }
            targetsList.add(optimizedTarget.getSourceCallTarget() != null ? optimizedTarget.getSourceCallTarget() : optimizedTarget);
        }

        List<OptimizedCallTarget> uniqueSortedTargets = new ArrayList<>();
        uniqueSortedTargets.addAll(groupedTargets.keySet());
        Collections.sort(uniqueSortedTargets,
                        (target1, target2) -> sumCalls(groupedTargets.get(target2), p -> p.getTotalCallCount()) - sumCalls(groupedTargets.get(target1), p -> p.getTotalCallCount()));

        int totalDirectCallCount = 0;
        int totalInlinedCallCount = 0;
        int totalIndirectCallCount = 0;
        int totalTotalCallCount = 0;
        int totalInterpretedCallCount = 0;
        int totalInvalidationCount = 0;

        runtime.log(""); // empty line
        runtime.log(String.format(" %-50s  | %-15s || %-15s | %-15s || %-15s | %-15s | %-15s || %3s ", "Call Target", "Total Calls", "Interp. Calls", "Opt. Calls", "Direct Calls", "Inlined Calls",
                        "Indirect Calls", "Invalidations"));
        for (OptimizedCallTarget uniqueCallTarget : uniqueSortedTargets) {
            List<OptimizedCallTarget> allCallTargets = groupedTargets.get(uniqueCallTarget);
            int directCallCount = sumCalls(allCallTargets, p -> p.getDirectCallCount());
            int indirectCallCount = sumCalls(allCallTargets, p -> p.getIndirectCallCount());
            int inlinedCallCount = sumCalls(allCallTargets, p -> p.getInlinedCallCount());
            int interpreterCallCount = sumCalls(allCallTargets, p -> p.getInterpreterCallCount());
            int totalCallCount = sumCalls(allCallTargets, p -> p.getTotalCallCount());
            int invalidationCount = sumCalls(allCallTargets, p -> p.getInvalidationCount());

            totalDirectCallCount += directCallCount;
            totalInlinedCallCount += inlinedCallCount;
            totalIndirectCallCount += indirectCallCount;
            totalInvalidationCount += invalidationCount;
            totalInterpretedCallCount += interpreterCallCount;
            totalTotalCallCount += totalCallCount;

            if (totalCallCount > 0) {
                runtime.log(String.format("  %-50s | %15d || %15d | %15d || %15d | %15d | %15d || %3d", uniqueCallTarget, totalCallCount, interpreterCallCount, totalCallCount - interpreterCallCount,
                                directCallCount, inlinedCallCount, indirectCallCount, invalidationCount));
            }

        }

        runtime.log(String.format(" %-50s  | %15d || %15d | %15d || %15d | %15d | %15d || %3d", "Total", totalTotalCallCount, totalInterpretedCallCount, totalTotalCallCount -
                        totalInterpretedCallCount, totalDirectCallCount, totalInlinedCallCount, totalIndirectCallCount, totalInvalidationCount));

    }

    private static int sumCalls(List<OptimizedCallTarget> targets, Function<TraceCompilationProfile, Integer> function) {
        int sum = 0;
        for (OptimizedCallTarget target : targets) {
            OptimizedCompilationProfile profile = target.getCompilationProfile();
            /*
             * Profiles created before the TruffleCallTargetProfiling has been turned on cannot be
             * printed because they have the wrong profile class. It is OK to just ignore these call
             * targets here.
             */
            if (profile instanceof TraceCompilationProfile) {
                sum += function.apply((TraceCompilationProfile) profile);
            }
        }
        return sum;
    }
}

/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.lir.alloc.graphcoloring;

import org.graalvm.compiler.core.common.alloc.RegisterAllocationConfig;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.lir.alloc.RegisterAllocationPhase;
import org.graalvm.compiler.lir.alloc.lsra.LinearScanPhase;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool.MoveFactory;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;

import jdk.vm.ci.code.TargetDescription;

public class GraphColoringPhase extends RegisterAllocationPhase {
    private static final LinearScanPhase phase = new LinearScanPhase();

    public static class Options {
        // @formatter:off
        @Option(help = "", type = OptionType.Debug)
        public static final OptionKey<Boolean> LIROptGraphColoringPhase = new OptionKey<>(false);
        // @formatter:on
        // @formatter:off
        @Option(help = "", type = OptionType.Debug)
        public static final OptionKey<Boolean> LIROptGcIrSpilling = new OptionKey<>(false);
        // @formatter:on
    }

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        if (useGC(lirGenRes)) {
            MoveFactory spillMoveFactory = context.spillMoveFactory;
            RegisterAllocationConfig registerAllocationConfig = context.registerAllocationConfig;

            Chaitin allocation = new Chaitin(target, lirGenRes, spillMoveFactory, registerAllocationConfig);

            DebugContext debug = lirGenRes.getLIR().getDebug();
            debug.dump(1, lirGenRes.getLIR(), "After Graphcoloring Register Alloction");
            debug.dump(1, allocation, "After Graphcoloring Register Alloction");
        } else {

            phase.apply(target, lirGenRes, context);
        }

    }

    private static boolean useGC(LIRGenerationResult lirGenRes) {

        if (lirGenRes.getCompilationUnitName().contains("AMD64MathStub")) {
            return false;
        }
// if (lirGenRes.getCompilationUnitName().contains("Stub")) {
// return false;
// }

        return true;
    }

}

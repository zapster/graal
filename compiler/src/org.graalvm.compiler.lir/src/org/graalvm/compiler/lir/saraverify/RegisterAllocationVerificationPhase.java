/*
 * Copyright (c) 2017 Oracle and/or its affiliates. All rights reserved.
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

package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.EnumSet;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;

import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.Value;

public class RegisterAllocationVerificationPhase extends LIRPhase<AllocationContext> {

    public static class Options {
        // @formatter:off
        @Option(help = "Enable static analysis register allocation verification.", type = OptionType.Debug)
        public static final OptionKey<Boolean> SARAVerify = new OptionKey<>(false);
        // @formatter:on
    }

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        LIR lir = lirGenRes.getLIR();
        DebugContext debug = lir.getDebug();
        AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();

        InstructionValueConsumer proc = new InstructionValueConsumer() {

            @Override
            public void visitValue(LIRInstruction inst, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
                debug.log(3, "%s", value);

            }
        };
        for (AbstractBlockBase<?> block : blocks) {

            try (Indent i = debug.logAndIndent(3, "Processing %s", block)) {
                ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);

                for (LIRInstruction inst : instructions) {
                    try (Indent i2 = debug.logAndIndent(3, "Processing %s", inst)) {
                        inst.visitEachInput(proc);
                        inst.visitEachOutput(proc);
                        inst.visitEachAlive(proc);
                        inst.visitEachTemp(proc);
                        inst.visitEachState(proc);
                    }
                }
            }
        }

    }

}

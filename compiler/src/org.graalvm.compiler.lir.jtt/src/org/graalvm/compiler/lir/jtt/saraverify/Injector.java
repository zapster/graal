package org.graalvm.compiler.lir.jtt.saraverify;

import java.util.ArrayList;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.StandardOp.ValueMoveOp;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase;

import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.AllocatableValue;

class Injector extends AllocationPhase {

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        LIR lir = lirGenRes.getLIR();
        DebugContext debug = lir.getDebug();
        AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();

        // target.arch.
        // context.registerAllocationConfig.getAllocatableRegisters(kind)

        for (AbstractBlockBase<?> block : blocks) {
            ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);

            for (LIRInstruction instruction : instructions) {
                if (instruction.isValueMoveOp()) {
                    ValueMoveOp move = ValueMoveOp.asValueMoveOp(instruction);
                    AllocatableValue input = move.getInput();
                    AllocatableValue result = move.getResult();

                    instruction.forEachOutput((value, mode, flags) -> input);
                    instruction.forEachInput((value, mode, flags) -> result);
                }
            }
        }
    }

}
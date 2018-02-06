package org.graalvm.compiler.lir.jtt.saraverify;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugContext.Scope;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.StandardOp.ValueMoveOp;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterArray;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.AllocatableValue;

class Injector extends AllocationPhase {
    class CopyInjector extends Injector {

        @Override
        protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
            LIR lir = lirGenRes.getLIR();
            DebugContext debugContext = lir.getDebug();
            AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();

            // target.arch.
            // context.registerAllocationConfig.getAllocatableRegisters(kind)

            RegisterArray ra = target.arch.getAvailableValueRegisters();
            RegisterArray ra2 = context.registerAllocationConfig.getAllocatableRegisters();

            try (Scope s = debugContext.scope("Injector")) {
                debugContext.log(3, "Available Value Registers: " + ra.toString());
                debugContext.log(3, "Allocatable Registers: " + ra2.toString());
                List<Register> registers = ra.asList().stream().filter(reg -> !(ra2.asList().contains(reg))).collect(Collectors.toList());
                debugContext.log(3, registers.toString());
            }

            for (AbstractBlockBase<?> block : blocks) {
                ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);

                for (LIRInstruction instruction : instructions) {
                    debugContext.log(3, instruction.toString());

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

    class LabelInjector extends Injector {

        @Override
        protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
            LIR lir = lirGenRes.getLIR();
            AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();

            for (AbstractBlockBase<?> block : blocks) {
                ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);

                LIRInstruction instruction = instructions.get(0);

                if (instruction instanceof LabelOp) {
                    LabelOp labelOp = (LabelOp) instruction;
                    labelOp.clearIncomingValues();
                }
            }
        }

    }

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        return;
    }

}
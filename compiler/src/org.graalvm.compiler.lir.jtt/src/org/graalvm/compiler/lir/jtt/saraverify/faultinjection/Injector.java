package org.graalvm.compiler.lir.jtt.saraverify.faultinjection;

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
import jdk.vm.ci.meta.Value;

class Injector extends AllocationPhase {
    class CopyInjector extends Injector {

        @Override
        protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
            LIR lir = lirGenRes.getLIR();
            DebugContext debugContext = lir.getDebug();
            AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();

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

    class DuplicateInstructionInjector extends Injector {
        @Override
        protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
            LIR lir = lirGenRes.getLIR();
            AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();

            for (AbstractBlockBase<?> block : blocks) {
                ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);

                if (instructions.size() >= 2) {
                    LIRInstruction instruction = instructions.get(0);
                    instructions.set(1, instruction);
                    return;
                }
            }
        }
    }

    class PhiLabelInjector extends Injector {
        @Override
        protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
            LIR lir = lirGenRes.getLIR();
            AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();

            for (AbstractBlockBase<?> block : blocks) {
                ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);

                LIRInstruction instruction = instructions.get(0);

                if (instruction instanceof LabelOp) {
                    LabelOp labelOp = (LabelOp) instruction;

                    if (labelOp.getPhiSize() > 1 && labelOp.getIncomingSize() > 1) {
                        Value[] incomingValues = new Value[labelOp.getIncomingSize()];

                        for (int i = 0; i < labelOp.getIncomingSize(); i++) {
                            incomingValues[i] = labelOp.getIncomingValue(i);
                        }

                        Value v0 = incomingValues[0];
                        Value v1 = incomingValues[1];

                        incomingValues[0] = v1;
                        incomingValues[1] = v0;

                        labelOp.setIncomingValues(incomingValues);
                        return;
                    }
                }
            }
        }
    }

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        return;
    }

}
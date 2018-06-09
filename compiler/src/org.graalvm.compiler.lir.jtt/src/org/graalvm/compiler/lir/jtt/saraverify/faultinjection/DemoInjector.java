package org.graalvm.compiler.lir.jtt.saraverify.faultinjection;

import java.util.ArrayList;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.StandardOp.ValueMoveOp;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase;

import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.Value;

class DemoInjector extends AllocationPhase {

    public DemoWrongOperandInjector getDemoWrongOperandInjector() {
        return new DemoWrongOperandInjector();
    }

    public DemoStaleInjector getDemoStaleInjector() {
        return new DemoStaleInjector();
    }

    public DemoEvictedInjector geDemoEvictedInjector() {
        return new DemoEvictedInjector();
    }

    private class DemoWrongOperandInjector extends DemoInjector {
        @Override
        protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
            LIR lir = lirGenRes.getLIR();

            if (lir.getControlFlowGraph().getBlocks().length == 1) {
                return;
            }

            // get start block
            AbstractBlockBase<?> block0 = lir.getControlFlowGraph().getStartBlock();
            // get Block 1 (else branch)
            AbstractBlockBase<?> block1 = lir.getControlFlowGraph().getBlocks()[1];

            LabelOp labelOp = (LabelOp) lir.getLIRforBlock(block0).get(0);
            Value n = labelOp.getIncomingValue(0);

            LIRInstruction move = lir.getLIRforBlock(block1).get(1);

            move.forEachOutput((operand, mode, flags) -> n);

            lirGenRes.setComment(move, "injected");
        }
    }

    private class DemoStaleInjector extends DemoInjector {
        @Override
        protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
            LIR lir = lirGenRes.getLIR();

            if (lir.getControlFlowGraph().getBlocks().length == 1) {
                return;
            }

            AbstractBlockBase<?> block0 = lir.getControlFlowGraph().getBlocks()[0];
            ArrayList<LIRInstruction> instructionsB0 = lir.getLIRforBlock(block0);

            ValueMoveOp valueMoveOp = (ValueMoveOp) instructionsB0.get(3);

            AbstractBlockBase<?> block1 = lir.getControlFlowGraph().getBlocks()[1];
            ArrayList<LIRInstruction> instructionsB1 = lir.getLIRforBlock(block1);

            LIRInstruction leaOp = instructionsB1.get(2);

            LIRInstruction move = instructionsB1.get(4);
            leaOp.visitEachOutput((operand, mode, flags) -> move.forEachOutput((operand2, mode2, flags2) -> operand));
            move.forEachInput((operand, mode, flags) -> valueMoveOp.getResult());

            lirGenRes.setComment(move, "injected");
        }
    }

    private class DemoEvictedInjector extends DemoInjector {
        @Override
        protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
            LIR lir = lirGenRes.getLIR();

            if (lir.getControlFlowGraph().getBlocks().length == 1) {
                return;
            }

            // get start block
            AbstractBlockBase<?> block0 = lir.getControlFlowGraph().getStartBlock();
            // get Block 1 (else branch)
            AbstractBlockBase<?> block1 = lir.getControlFlowGraph().getBlocks()[1];

            LabelOp labelOp = (LabelOp) lir.getLIRforBlock(block0).get(0);
            Value n = labelOp.getIncomingValue(0);

            LIRInstruction move = lir.getLIRforBlock(block1).get(2);

            move.forEachOutput((operand, mode, flags) -> n);

            lirGenRes.setComment(move, "injected");
        }
    }

// private class DemoEvictedInjector extends DemoInjector {
// @Override
// protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext
// context) {
// LIR lir = lirGenRes.getLIR();
//
// if (lir.getControlFlowGraph().getBlocks().length == 1) {
// return;
// }
//
// AbstractBlockBase<?> block3 = lir.getControlFlowGraph().getBlocks()[3];
// ArrayList<LIRInstruction> instructionsB3 = lir.getLIRforBlock(block3);
//
// LIRInstruction moveInst = instructionsB3.get(2);
// ValueMoveOp move = (ValueMoveOp) moveInst;
//
// Value originalResult = move.getResult();
// moveInst.forEachOutput((operand, mode, flags) -> move.getInput());
// moveInst.forEachInput((operand, mode, flags) -> originalResult);
//
// lirGenRes.setComment(moveInst, "injected");
// }
// }

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        return;
    }
}
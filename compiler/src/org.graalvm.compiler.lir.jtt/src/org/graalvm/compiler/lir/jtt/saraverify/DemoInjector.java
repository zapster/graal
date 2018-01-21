package org.graalvm.compiler.lir.jtt.saraverify;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase;

import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.Value;

class DemoInjector extends AllocationPhase {

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
    }
}
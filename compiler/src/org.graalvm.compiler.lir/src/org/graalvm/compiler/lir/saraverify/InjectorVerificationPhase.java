package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.core.common.cfg.AbstractControlFlowGraph;
import org.graalvm.compiler.core.common.cfg.BlockMap;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.StandardOp.ValueMoveOp;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;

import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.AllocatableValue;

public class InjectorVerificationPhase extends LIRPhase<AllocationContext> {

    public static class Options {
        // @formatter:off
        @Option(help = "Enable fault injector for the static analysis register allocation verification.", type = OptionType.Debug)
        public static final OptionKey<Boolean> SARAVerifyInjector = new OptionKey<>(false);
        // @formatter:on
    }

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        injectErrors(lirGenRes);

// try {
        VerificationPhase.runVerification(lirGenRes, context);
// assert false : "Injected errors were not detected.";
// } catch (SARAVerifyError error) {
//
// }
    }

    private static void injectErrors(LIRGenerationResult lirGenRes) {
        LIR lir = lirGenRes.getLIR();
        AbstractControlFlowGraph<?> controlFlowGraph = lir.getControlFlowGraph();
        AbstractBlockBase<?>[] blocks = controlFlowGraph.getBlocks();

        int missingSpill = 0;
        int missingLoad = 0;

        BlockMap<ArrayList<LIRInstruction>> missingSpillsLoadsMap = new BlockMap<>(controlFlowGraph);

        for (AbstractBlockBase<?> block : blocks) {
            ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);
            ArrayList<LIRInstruction> missingSpillsLoads = new ArrayList<>();

            for (LIRInstruction instruction : instructions) {
                // inject missing spill, load
                if (instruction.isValueMoveOp()) {
                    ValueMoveOp valueMoveOp = (ValueMoveOp) instruction;
                    AllocatableValue result = valueMoveOp.getResult();
                    AllocatableValue input = valueMoveOp.getInput();

                    if (LIRValueUtil.isStackSlotValue(result) && missingSpill < 5) {
                        // inject missing spill
                        missingSpillsLoads.add(instruction);
                        missingSpill++;
                    } else if (LIRValueUtil.isStackSlotValue(input) && missingLoad < 5) {
                        // inject missing load
                        missingSpillsLoads.add(instruction);
                        missingLoad++;
                    }
                }
            }

            missingSpillsLoadsMap.put(block, missingSpillsLoads);
        }

        // remove instructions to inject missing spills and loads
        for (AbstractBlockBase<?> block : blocks) {
            ArrayList<LIRInstruction> missingSpillLoads = missingSpillsLoadsMap.get(block);

            if (missingSpillLoads != null) {
                ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);
                instructions.removeAll(missingSpillLoads);
            }
        }
    }
}

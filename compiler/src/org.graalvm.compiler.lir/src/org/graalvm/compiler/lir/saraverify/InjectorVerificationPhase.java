package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.core.common.cfg.AbstractControlFlowGraph;
import org.graalvm.compiler.core.common.cfg.BlockMap;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugContext.Scope;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.StandardOp.ValueMoveOp;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;

import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

public class InjectorVerificationPhase extends LIRPhase<AllocationContext> {

    private final static String DEBUG_SCOPE = "SARAVerifyInjectorVerification";

    public static class Options {
        // @formatter:off
        @Option(help = "Enable fault injector for the static analysis register allocation verification.", type = OptionType.Debug)
        public static final OptionKey<Boolean> SARAVerifyInjector = new OptionKey<>(false);
        // @formatter:on
    }

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        LIR lir = lirGenRes.getLIR();
        DebugContext debugContext = lir.getDebug();

        boolean injectedErrors = injectErrors(lir);

        // log that no errors were injected
        if (!injectedErrors) {
            try (Indent i = debugContext.indent(); Scope s = debugContext.scope(DEBUG_SCOPE)) {
                debugContext.log(3, "%s", "No injected errors.");
            }
        }

        try {
            VerificationPhase.runVerification(lirGenRes, context);
            if (injectedErrors) {
                GraalError.shouldNotReachHere("Injected errors were not detected.");
            }
        } catch (SARAVerifyError error) {
            if (!injectedErrors) {
                GraalError.shouldNotReachHere("SARAVerify error without having injected errors.");
            }
        }
    }

    private static boolean injectErrors(LIR lir) {
        AbstractControlFlowGraph<?> controlFlowGraph = lir.getControlFlowGraph();
        AbstractBlockBase<?>[] blocks = controlFlowGraph.getBlocks();

        int missingSpill = 0;
        int missingLoad = 0;

        BlockMap<ArrayList<LIRInstruction>> missingSpillsLoadsMap = new BlockMap<>(controlFlowGraph);

        // get the label instruction from block 0
        LabelOp functionLabel = (LabelOp) lir.getLIRforBlock(blocks[0]).get(0);

        // get the last incoming value from the label instruction, which is the value for the base
        // pointer
        Value basePointer = functionLabel.getIncomingValue(functionLabel.getIncomingSize() - 1);

        for (AbstractBlockBase<?> block : blocks) {
            ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);
            ArrayList<LIRInstruction> missingSpillsLoads = new ArrayList<>();

            for (LIRInstruction instruction : instructions) {
                // inject missing spill, load
                if (instruction.isValueMoveOp()) {
                    ValueMoveOp valueMoveOp = (ValueMoveOp) instruction;
                    AllocatableValue result = valueMoveOp.getResult();
                    AllocatableValue input = valueMoveOp.getInput();

                    if (LIRValueUtil.isStackSlotValue(result) && !input.equals(basePointer) && missingSpill < 5) {
                        // inject missing spill
                        missingSpillsLoads.add(instruction);
                        missingSpill++;
                    } else if (LIRValueUtil.isStackSlotValue(input) && !result.equals(basePointer) && missingLoad < 5) {
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

        return missingSpill != 0 || missingLoad != 0;
    }
}

package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;

import jdk.vm.ci.code.TargetDescription;

public class VerificationPhase extends LIRPhase<AllocationContext> {

    private DebugContext debug;

    public VerificationPhase() {
        super();
    }

    public VerificationPhase(DebugContext debug) {
        super();
        this.debug = debug;
        this.debug.scope("Verification");
    }

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        LIR lir = lirGenRes.getLIR();
        debug = lir.getDebug();
        AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();

        if (blocks.length != 1) {
            // Control Flow for more than 1 Block not yet supported
            return;
        }

        AnalysisResult result = context.contextLookup(AnalysisResult.class);
        ArrayList<DuSequence> inputDuSequences = result.getInputDuSequences();

        AbstractBlockBase<?> block = blocks[0];
        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();
        duSequenceAnalysis.determineDuSequenceWebs(lir.getLIRforBlock(block));

        ArrayList<DuSequence> outputDuSequences = duSequenceAnalysis.getDuSequences();

        if (!verifyDataFlow(inputDuSequences, outputDuSequences)) {
            throw GraalError.shouldNotReachHere("SARA verify error");
        }
    }

    public boolean verifyDataFlow(ArrayList<DuSequence> inputDuSequences, ArrayList<DuSequence> outputDuSequences) {
        if (inputDuSequences.size() != outputDuSequences.size()) {
            debug.logAndIndent(3, "The numbers of du-sequences from the input and output do not match.");
            return false;
        }

        assert inputDuSequences.stream().distinct().count() == outputDuSequences.stream().distinct().count();

        for (DuSequence inputDuSequence : inputDuSequences) {
            LIRInstruction inputDefInstruction = inputDuSequence.peekFirst().getDefInstruction();
            LIRInstruction inputUseInstruction = inputDuSequence.peekLast().getUseInstruction();
            int inputOperandDefPosition = inputDuSequence.peekFirst().getOperandDefPosition();
            int inputOperandUsePosition = inputDuSequence.peekLast().getOperandUsePosition();

            boolean match = outputDuSequences.stream().anyMatch(duSequence -> duSequence.peekFirst().getOperandDefPosition() == inputOperandDefPosition &&
                            duSequence.peekLast().getOperandUsePosition() == inputOperandUsePosition &&
                            duSequence.peekFirst().getDefInstruction().equals(inputDefInstruction) &&
                            duSequence.peekLast().getUseInstruction().equals(inputUseInstruction));

            if (!match) {
                debug.logAndIndent(3, "Input Sequence with wrong or missing output sequence: " + inputDuSequence);
                return false;
            }
        }

        return true;
    }
}

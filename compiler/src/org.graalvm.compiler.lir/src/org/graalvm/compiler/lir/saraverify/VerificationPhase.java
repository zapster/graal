package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;

import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugContext.Scope;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;

import jdk.vm.ci.code.TargetDescription;

public class VerificationPhase extends LIRPhase<AllocationContext> {

    private final String DEBUG_SCOPE = "SARAVerifyVerificationPhase";

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        AnalysisResult inputResult = context.contextLookup(AnalysisResult.class);

        if (inputResult == null) {
            // no input du-sequences were created by the RegisterAllocationVerificationPhase
            return;
        }

        ArrayList<DuSequence> inputDuSequences = inputResult.getDuSequences();

        LIR lir = lirGenRes.getLIR();
        DebugContext debugContext = lir.getDebug();

        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();
        AnalysisResult outputResult = duSequenceAnalysis.determineDuSequenceWebs(lirGenRes);
        ArrayList<DuSequence> outputDuSequences = outputResult.getDuSequences();

        if (!verifyDataFlow(inputDuSequences, outputDuSequences, debugContext)) {
            throw GraalError.shouldNotReachHere("SARA verify error: Data Flow not equal");
        }

        if (!verifyOperandCount(inputResult.getInstructionDefOperandCount(), inputResult.getInstructionUseOperandCount(),
                        outputResult.getInstructionDefOperandCount(), outputResult.getInstructionUseOperandCount())) {
            throw GraalError.shouldNotReachHere("SARA verify error: Operand numbers not equal");
        }
    }

    public boolean verifyDataFlow(ArrayList<DuSequence> inputDuSequences, ArrayList<DuSequence> outputDuSequences, DebugContext debugContext) {
        if (inputDuSequences.size() != outputDuSequences.size()) {
            try (Indent i = debugContext.indent(); Scope s = debugContext.scope(DEBUG_SCOPE)) {
                debugContext.logAndIndent(3, "The numbers of du-sequences from the input and output do not match.");
            }
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
                try (Indent i = debugContext.indent(); Scope s = debugContext.scope(DEBUG_SCOPE)) {
                    debugContext.logAndIndent(3, "Input Sequence with wrong or missing output sequence: " + inputDuSequence);
                }
                return false;
            }
        }

        return true;
    }

    public static boolean verifyOperandCount(Map<LIRInstruction, Integer> inputInstructionDefOperandCount,
                    Map<LIRInstruction, Integer> inputInstructionUseOperandCount,
                    Map<LIRInstruction, Integer> outputInstructionDefOperandCount,
                    Map<LIRInstruction, Integer> outputInstructionUseOperandCount) {

        for (Entry<LIRInstruction, Integer> entry : inputInstructionDefOperandCount.entrySet()) {
            Integer count = outputInstructionDefOperandCount.get(entry.getKey());

            if (count != null && count.compareTo(entry.getValue()) != 0) {
                return false;
            }
        }

        for (Entry<LIRInstruction, Integer> entry : inputInstructionUseOperandCount.entrySet()) {
            Integer count = outputInstructionUseOperandCount.get(entry.getKey());

            if (count != null && count.compareTo(entry.getValue()) != 0) {
                return false;
            }
        }

        return true;
    }
}

package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;

import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugContext.Scope;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyDef;

import jdk.vm.ci.code.Register;
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

        List<DuSequence> inputDuSequences = inputResult.getDuSequences();
        Map<Register, DummyDef> inputDummyDefs = inputResult.getDummyDefs();

        LIR lir = lirGenRes.getLIR();
        DebugContext debugContext = lir.getDebug();

        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();
        AnalysisResult outputResult = duSequenceAnalysis.determineDuSequenceWebs(lirGenRes, context.registerAllocationConfig.getRegisterConfig().getAttributesMap(), inputDummyDefs);

        List<DuSequence> outputDuSequences = outputResult.getDuSequences();

        if (!verifyDataFlow(inputDuSequences, outputDuSequences, debugContext)) {
            throw GraalError.shouldNotReachHere(DuSequenceAnalysis.ERROR_MSG_PREFIX + "Data Flow not equal");
        }

        if (!verifyOperandCount(inputResult.getInstructionDefOperandCount(), inputResult.getInstructionUseOperandCount(),
                        outputResult.getInstructionDefOperandCount(), outputResult.getInstructionUseOperandCount())) {
            throw GraalError.shouldNotReachHere(DuSequenceAnalysis.ERROR_MSG_PREFIX + "Operand numbers not equal");
        }
    }

    public boolean verifyDataFlow(List<DuSequence> inputDuSequences, List<DuSequence> outputDuSequences, DebugContext debugContext) {
        List<DuSequence> matchedOutputDuSequences = new ArrayList<>();
        List<DuSequence> unmatchedInputDuSequences = new ArrayList<>();
        List<DuSequence> distinctInputDuSequences = inputDuSequences.stream().distinct().collect(Collectors.toList());
        List<DuSequence> distinctOutputDuSequences = outputDuSequences.stream().distinct().collect(Collectors.toList());

        try (Indent i = debugContext.indent(); Scope s = debugContext.scope(DEBUG_SCOPE)) {
            debugContext.log(3, "Number of distinct input Du-Sequences: " + distinctInputDuSequences.size() + " | Number of distinct output Du-Sequences: " + distinctOutputDuSequences.size());
        }

        for (DuSequence inputDuSequence : distinctInputDuSequences) {
            LIRInstruction inputDefInstruction = inputDuSequence.peekFirst().getDefInstruction();
            LIRInstruction inputUseInstruction = inputDuSequence.peekLast().getUseInstruction();
            int inputOperandDefPosition = inputDuSequence.peekFirst().getOperandDefPosition();
            int inputOperandUsePosition = inputDuSequence.peekLast().getOperandUsePosition();

            Optional<DuSequence> match = distinctOutputDuSequences.stream().filter(duSequence -> duSequence.peekFirst().getOperandDefPosition() == inputOperandDefPosition &&
                            duSequence.peekLast().getOperandUsePosition() == inputOperandUsePosition &&
                            duSequence.peekFirst().getDefInstruction().equals(inputDefInstruction) &&
                            duSequence.peekLast().getUseInstruction().equals(inputUseInstruction) &&
                            !matchedOutputDuSequences.contains(duSequence)).findAny();

            if (match.isPresent()) {
                matchedOutputDuSequences.add(match.get());
            } else {
                unmatchedInputDuSequences.add(inputDuSequence);
            }
        }

        List<DuSequence> unmatchedOutputDuSequences = distinctOutputDuSequences.stream().filter(duSequence -> !matchedOutputDuSequences.contains(duSequence)).collect(Collectors.toList());

        // log unmatched input du-sequences
        if (unmatchedInputDuSequences.size() > 0) {
            try (Indent i = debugContext.indent(); Scope s = debugContext.scope(DEBUG_SCOPE)) {
                debugContext.log(3, "Unmatched input du-sequences: ");
                unmatchedInputDuSequences.stream().forEach(duSequence -> debugContext.log(3, duSequence.toString()));
            }
        }

        // log unmatched output du-sequences
        if (unmatchedOutputDuSequences.size() > 0) {
            try (Indent i = debugContext.indent(); Scope s = debugContext.scope(DEBUG_SCOPE)) {
                debugContext.log(3, "Unmatched output du-sequences: ");
                unmatchedOutputDuSequences.stream().forEach(duSequence -> debugContext.log(3, duSequence.toString()));
            }
        }

        return unmatchedOutputDuSequences.size() == 0 ? true : false;
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

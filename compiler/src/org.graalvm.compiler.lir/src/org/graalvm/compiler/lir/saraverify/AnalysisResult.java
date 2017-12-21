package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.Map;

import org.graalvm.compiler.lir.LIRInstruction;

public class AnalysisResult {

    private ArrayList<DuSequence> inputDuSequences;
    private Map<LIRInstruction, Integer> instructionDefOperandCount;
    private Map<LIRInstruction, Integer> instructionUseOperandCount;

    public AnalysisResult(ArrayList<DuSequence> inputDuSequences, Map<LIRInstruction, Integer> instructionDefOperandCount, Map<LIRInstruction, Integer> instructionUseOperandCount) {
        this.inputDuSequences = inputDuSequences;
        this.instructionDefOperandCount = instructionDefOperandCount;
        this.instructionUseOperandCount = instructionUseOperandCount;
    }

    public ArrayList<DuSequence> getInputDuSequences() {
        return inputDuSequences;
    }

    public Map<LIRInstruction, Integer> getInstructionDefOperandCount() {
        return instructionDefOperandCount;
    }

    public Map<LIRInstruction, Integer> getInstructionUseOperandCount() {
        return instructionUseOperandCount;
    }

}

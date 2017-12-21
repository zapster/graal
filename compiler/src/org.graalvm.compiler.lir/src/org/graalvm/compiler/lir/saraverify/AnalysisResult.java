package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.Map;

import org.graalvm.compiler.lir.LIRInstruction;

public class AnalysisResult {

    private ArrayList<DuPair> duPairs;
    private ArrayList<DuSequence> duSequences;
    private ArrayList<DuSequenceWeb> duSequenceWebs;

    private Map<LIRInstruction, Integer> instructionDefOperandCount;
    private Map<LIRInstruction, Integer> instructionUseOperandCount;

    public AnalysisResult(ArrayList<DuPair> duPairs, ArrayList<DuSequence> duSequences, ArrayList<DuSequenceWeb> duSequenceWebs, Map<LIRInstruction, Integer> instructionDefOperandCount,
                    Map<LIRInstruction, Integer> instructionUseOperandCount) {
        this.duPairs = duPairs;
        this.duSequences = duSequences;
        this.duSequenceWebs = duSequenceWebs;
        this.instructionDefOperandCount = instructionDefOperandCount;
        this.instructionUseOperandCount = instructionUseOperandCount;
    }

    public ArrayList<DuPair> getDuPairs() {
        return duPairs;
    }

    public ArrayList<DuSequence> getDuSequences() {
        return duSequences;
    }

    public ArrayList<DuSequenceWeb> getDuSequenceWebs() {
        return duSequenceWebs;
    }

    public Map<LIRInstruction, Integer> getInstructionDefOperandCount() {
        return instructionDefOperandCount;
    }

    public Map<LIRInstruction, Integer> getInstructionUseOperandCount() {
        return instructionUseOperandCount;
    }

}

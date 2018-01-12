package org.graalvm.compiler.lir.saraverify;

import java.util.List;
import java.util.Map;

import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyDef;

import jdk.vm.ci.code.Register;

public class AnalysisResult {

    private List<DuPair> duPairs;
    private List<DuSequence> duSequences;
    private List<DuSequenceWeb> duSequenceWebs;

    private Map<LIRInstruction, Integer> instructionDefOperandCount;
    private Map<LIRInstruction, Integer> instructionUseOperandCount;

    private Map<Register, DummyDef> dummyDefs;

    public AnalysisResult(List<DuPair> duPairs, List<DuSequence> duSequences, List<DuSequenceWeb> duSequenceWebs, Map<LIRInstruction, Integer> instructionDefOperandCount,
                    Map<LIRInstruction, Integer> instructionUseOperandCount, Map<Register, DummyDef> dummyDefs) {
        this.duPairs = duPairs;
        this.duSequences = duSequences;
        this.duSequenceWebs = duSequenceWebs;
        this.instructionDefOperandCount = instructionDefOperandCount;
        this.instructionUseOperandCount = instructionUseOperandCount;
        this.dummyDefs = dummyDefs;
    }

    public List<DuPair> getDuPairs() {
        return duPairs;
    }

    public List<DuSequence> getDuSequences() {
        return duSequences;
    }

    public List<DuSequenceWeb> getDuSequenceWebs() {
        return duSequenceWebs;
    }

    public Map<LIRInstruction, Integer> getInstructionDefOperandCount() {
        return instructionDefOperandCount;
    }

    public Map<LIRInstruction, Integer> getInstructionUseOperandCount() {
        return instructionUseOperandCount;
    }

    public Map<Register, DummyDef> getDummyDefs() {
        return dummyDefs;
    }
}

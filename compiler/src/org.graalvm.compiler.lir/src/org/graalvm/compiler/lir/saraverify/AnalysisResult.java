package org.graalvm.compiler.lir.saraverify;

import java.util.List;
import java.util.Map;

import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyConstDef;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyRegDef;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.Constant;

public class AnalysisResult {

    private List<DuPair> duPairs;
    private List<DuSequence> duSequences;
    private List<DuSequenceWeb> duSequenceWebs;

    private Map<LIRInstruction, Integer> instructionDefOperandCount;
    private Map<LIRInstruction, Integer> instructionUseOperandCount;

    private Map<Register, DummyRegDef> dummyRegDefs;
    private Map<Constant, DummyConstDef> dummyConstDefs;

    public AnalysisResult(List<DuPair> duPairs, List<DuSequence> duSequences, List<DuSequenceWeb> duSequenceWebs, Map<LIRInstruction, Integer> instructionDefOperandCount,
                    Map<LIRInstruction, Integer> instructionUseOperandCount, Map<Register, DummyRegDef> dummyRegDefs, Map<Constant, DummyConstDef> dummyConstDefs) {
        this.duPairs = duPairs;
        this.duSequences = duSequences;
        this.duSequenceWebs = duSequenceWebs;
        this.instructionDefOperandCount = instructionDefOperandCount;
        this.instructionUseOperandCount = instructionUseOperandCount;
        this.dummyRegDefs = dummyRegDefs;
        this.dummyConstDefs = dummyConstDefs;
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

    public Map<Register, DummyRegDef> getDummyRegDefs() {
        return dummyRegDefs;
    }

    public Map<Constant, DummyConstDef> getDummyConstDefs() {
        return dummyConstDefs;
    }
}

package org.graalvm.compiler.lir.saraverify;

import java.util.List;
import java.util.Map;

import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyConstDef;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyRegDef;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;

public class AnalysisResult {

    private Map<Value, List<Node>> duSequenceWebs;

    private Map<LIRInstruction, Integer> instructionDefOperandCount;
    private Map<LIRInstruction, Integer> instructionUseOperandCount;

    private Map<Register, DummyRegDef> dummyRegDefs;
    private Map<Constant, DummyConstDef> dummyConstDefs;

// private Map<DuSequence, String> duSequencesToString;

    public AnalysisResult(Map<Value, List<Node>> duSequenceWebs, Map<LIRInstruction, Integer> instructionDefOperandCount,
                    Map<LIRInstruction, Integer> instructionUseOperandCount, Map<Register, DummyRegDef> dummyRegDefs, Map<Constant, DummyConstDef> dummyConstDefs) {
        this.duSequenceWebs = duSequenceWebs;
        this.instructionDefOperandCount = instructionDefOperandCount;
        this.instructionUseOperandCount = instructionUseOperandCount;
        this.dummyRegDefs = dummyRegDefs;
        this.dummyConstDefs = dummyConstDefs;
// this.duSequencesToString = generateDuSequencesToStringMap();
    }

    public Map<Value, List<Node>> getDuSequenceWebs() {
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

// public Map<DuSequence, String> getDuSequencesToString() {
// return duSequencesToString;
// }

// private Map<DuSequence, String> generateDuSequencesToStringMap() {
// Map<DuSequence, String> map = new HashMap<>();
//
// for (DuSequence duSequence : duSequences) {
// map.put(duSequence, duSequence.toString());
// }
//
// return map;
// }
}

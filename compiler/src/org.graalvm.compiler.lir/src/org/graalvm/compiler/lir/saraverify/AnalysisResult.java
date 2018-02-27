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

    private Map<Value, List<DefNode>> duSequences;

    private Map<LIRInstruction, Integer> instructionDefOperandCount;
    private Map<LIRInstruction, Integer> instructionUseOperandCount;

    private Map<Register, DummyRegDef> dummyRegDefs;
    private Map<Constant, DummyConstDef> dummyConstDefs;

    public AnalysisResult(Map<Value, List<DefNode>> duSequences, Map<LIRInstruction, Integer> instructionDefOperandCount,
                    Map<LIRInstruction, Integer> instructionUseOperandCount, Map<Register, DummyRegDef> dummyRegDefs, Map<Constant, DummyConstDef> dummyConstDefs) {
        this.duSequences = duSequences;
        this.instructionDefOperandCount = instructionDefOperandCount;
        this.instructionUseOperandCount = instructionUseOperandCount;
        this.dummyRegDefs = dummyRegDefs;
        this.dummyConstDefs = dummyConstDefs;
    }

    public Map<Value, List<DefNode>> getDuSequences() {
        return duSequences;
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

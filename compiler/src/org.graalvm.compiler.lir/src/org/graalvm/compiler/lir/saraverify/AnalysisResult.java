package org.graalvm.compiler.lir.saraverify;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.graalvm.compiler.core.common.cfg.BlockMap;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyConstDef;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyRegDef;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;

public class AnalysisResult {

    private final Map<Value, Set<DefNode>> duSequences;

    private final Map<LIRInstruction, Integer> instructionDefOperandCount;
    private final Map<LIRInstruction, Integer> instructionUseOperandCount;

    private final Map<Register, DummyRegDef> dummyRegDefs;
    private final Map<Constant, DummyConstDef> dummyConstDefs;

    private final BlockMap<List<Value>> blockPhiOutValue;
    private final BlockMap<List<Value>> blockPhiInValue;

    public AnalysisResult(Map<Value, Set<DefNode>> duSequences, Map<LIRInstruction, Integer> instructionDefOperandCount,
                    Map<LIRInstruction, Integer> instructionUseOperandCount, Map<Register, DummyRegDef> dummyRegDefs, Map<Constant, DummyConstDef> dummyConstDefs,
                    BlockMap<List<Value>> blockPhiOutValue, BlockMap<List<Value>> blockPhiInValue) {
        this.duSequences = duSequences;
        this.instructionDefOperandCount = instructionDefOperandCount;
        this.instructionUseOperandCount = instructionUseOperandCount;
        this.dummyRegDefs = dummyRegDefs;
        this.dummyConstDefs = dummyConstDefs;
        this.blockPhiOutValue = blockPhiOutValue;
        this.blockPhiInValue = blockPhiInValue;
    }

    public Map<Value, Set<DefNode>> getDuSequences() {
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

    public BlockMap<List<Value>> getBlockPhiOutValue() {
        return blockPhiOutValue;
    }

    public BlockMap<List<Value>> getBlockPhiInValue() {
        return blockPhiInValue;
    }
}

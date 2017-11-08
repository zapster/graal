package org.graalvm.compiler.lir.saraverify;

import org.graalvm.compiler.lir.LIRInstruction;

import jdk.vm.ci.meta.AllocatableValue;

public class DuPair {

    private AllocatableValue value;
    private LIRInstruction defInstruction;
    private LIRInstruction useInstruction;
    // position counting from 1
    private int operandUsePosition;

    public DuPair(AllocatableValue value, LIRInstruction defInstruction, LIRInstruction useInstruction, int operandUsePosition) {
        this.value = value;
        this.defInstruction = defInstruction;
        this.useInstruction = useInstruction;
        this.operandUsePosition = operandUsePosition;
    }

    public AllocatableValue getValue() {
        return value;
    }

    public LIRInstruction getDefInstruction() {
        return defInstruction;
    }

    public LIRInstruction getUseInstruction() {
        return useInstruction;
    }

    public int getOperandUsePosition() {
        return operandUsePosition;
    }

    @Override
    public String toString() {
        return "\nValue: " + value + "\nDef: " + defInstruction + "\nUse: " + useInstruction + "\nUse Operand Pos: " + operandUsePosition;
    }
}

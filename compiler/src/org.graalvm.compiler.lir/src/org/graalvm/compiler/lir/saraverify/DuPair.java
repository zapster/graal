package org.graalvm.compiler.lir.saraverify;

import org.graalvm.compiler.lir.LIRInstruction;

import jdk.vm.ci.meta.Value;

public class DuPair {

    private Value value;
    private LIRInstruction defInstruction;
    private LIRInstruction useInstruction;
    private int operandDefPosition;
    private int operandUsePosition;

    public DuPair(Value value, LIRInstruction defInstruction, LIRInstruction useInstruction, int operandDefPosition, int operandUsePosition) {
        this.value = value;
        this.defInstruction = defInstruction;
        this.useInstruction = useInstruction;
        this.operandDefPosition = operandDefPosition;
        this.operandUsePosition = operandUsePosition;
    }

    public Value getValue() {
        return value;
    }

    public LIRInstruction getDefInstruction() {
        return defInstruction;
    }

    public LIRInstruction getUseInstruction() {
        return useInstruction;
    }

    public int getOperandDefPosition() {
        return operandDefPosition;
    }

    public int getOperandUsePosition() {
        return operandUsePosition;
    }

    @Override
    public String toString() {
        return "\nValue: " + value + "\nDef: " + defInstruction + " Pos: " + operandDefPosition + "\nUse: " + useInstruction + " Pos: " + operandUsePosition;

    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof DuPair) {
            DuPair duPair = (DuPair) obj;
            return this.value.equals(duPair.value) && this.defInstruction.equals(duPair.defInstruction) && this.useInstruction.equals(duPair.useInstruction) &&
                            (this.operandDefPosition == duPair.operandDefPosition) && (this.operandUsePosition == duPair.operandUsePosition);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}

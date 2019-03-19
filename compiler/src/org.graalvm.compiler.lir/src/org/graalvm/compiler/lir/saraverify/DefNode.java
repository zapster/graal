package org.graalvm.compiler.lir.saraverify;

import org.graalvm.compiler.lir.LIRInstruction;

import jdk.vm.ci.meta.Value;

public class DefNode extends Node {

    private Value value;
    private int defOperandPosition;

    public DefNode(Value value, LIRInstruction instruction, int defOperandPosition) {
        super(instruction);
        this.value = value;
        this.defOperandPosition = defOperandPosition;
    }

    public Value getValue() {
        return value;
    }

    public int getDefOperandPosition() {
        return defOperandPosition;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + defOperandPosition;
        result = prime * result + value.hashCode();
        return result + super.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DefNode)) {
            return false;
        }

        DefNode defNode = (DefNode) obj;
        return equalsInstructionAndPosition(defNode) && defNode.value.equals(this.value);
    }

    /**
     * Indicates whether some other defNode is equal to this one regarding the instruction and the
     * definition operand position.
     *
     * @param defNode
     * @return true if this defNode is the same as the defNode argument regarding the instruction
     *         and the definition operand position, otherwise false
     */
    public boolean equalsInstructionAndPosition(DefNode defNode) {
        return super.equals(defNode) && this.defOperandPosition == defNode.defOperandPosition;
    }

    @Override
    public String toString() {
        return "DEF:" + value + ":" + defOperandPosition + ":" + getInstruction().name();
    }

    @Override
    public boolean isDefNode() {
        return true;
    }

    @Override
    public boolean isUseNode() {
        return false;
    }
}

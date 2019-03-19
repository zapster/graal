package org.graalvm.compiler.lir.saraverify;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.graalvm.compiler.lir.LIRInstruction;

import jdk.vm.ci.meta.Value;

public class UseNode extends Node {

    private Value value;
    private int useOperandPosition;

    public UseNode(Value value, LIRInstruction instruction, int useOperandPosition) {
        super(instruction);
        this.value = value;
        this.useOperandPosition = useOperandPosition;
    }

    public Value getValue() {
        return value;
    }

    public int getUseOperandPosition() {
        return useOperandPosition;
    }

    @Override
    public void addNextNodes(Node nextNode) {
        return;
    }

    @Override
    public void addAllNextNodes(Collection<? extends Node> nextNodesArg) {
        return;
    }

    @Override
    public Set<Node> getNextNodes() {
        return new HashSet<>();
    }

    @Override
    public int hashCode() {
        final int prime = 37;
        int result = 1;
        result = prime * result + useOperandPosition;
        result = prime * result + value.hashCode();
        return result + super.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof UseNode)) {
            return false;
        }

        UseNode useNode = (UseNode) obj;
        return equalsInstructionAndPosition(useNode) && useNode.value.equals(this.value);
    }

    /**
     * Indicates whether some other useNode is equal to this one regarding the instruction and the
     * use operand position.
     *
     * @param useNode
     * @return true if this useNode is the same as the useNode argument regarding the instruction
     *         and the use operand position, otherwise false
     */
    public boolean equalsInstructionAndPosition(UseNode useNode) {
        return super.equals(useNode) && this.useOperandPosition == useNode.useOperandPosition;
    }

    @Override
    public String toString() {
        return "USE:" + value + ":" + useOperandPosition + ":" + getInstruction().name();
    }

    @Override
    public boolean isDefNode() {
        return false;
    }

    @Override
    public boolean isUseNode() {
        return true;
    }

}

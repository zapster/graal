package org.graalvm.compiler.lir.saraverify;

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
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + System.identityHashCode(instruction);
        result = prime * result + useOperandPosition;
        result = prime * result + value.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof UseNode)) {
            return false;
        }

        UseNode useNode = (UseNode) obj;
        return useNode.value.equals(this.value) && useNode.instruction.equals(this.instruction) && useNode.useOperandPosition == this.useOperandPosition ? true : false;
    }

    @Override
    public String toString() {
        return "USE:" + value + ":" + useOperandPosition + ":" + instruction.name();
    }

    @Override
    public String duSequenceToString() {
        return " -> " + toString();
    }
}

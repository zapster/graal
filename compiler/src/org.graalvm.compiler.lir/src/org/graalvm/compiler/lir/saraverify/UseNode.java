package org.graalvm.compiler.lir.saraverify;

import org.graalvm.compiler.lir.LIRInstruction;

import jdk.vm.ci.meta.Value;

public class UseNode extends Node {

    private Value value;
    private LIRInstruction useInstruction;
    private int useOperandPosition;

    public UseNode(Value value, LIRInstruction useInstruction, int useOperandPosition) {
        this.value = value;
        this.useInstruction = useInstruction;
        this.useOperandPosition = useOperandPosition;
    }

    public Value getValue() {
        return value;
    }

    public LIRInstruction getUseInstruction() {
        return useInstruction;
    }

    public int getUseOperandPosition() {
        return useOperandPosition;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + System.identityHashCode(useInstruction);
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
        return useNode.value.equals(this.value) && useNode.useInstruction.equals(this.useInstruction) && useNode.useOperandPosition == this.useOperandPosition ? true : false;
    }

}

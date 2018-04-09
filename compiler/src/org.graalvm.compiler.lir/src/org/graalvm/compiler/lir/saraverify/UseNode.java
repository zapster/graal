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
        return super.equals(useNode) && useNode.value.equals(this.value) && useNode.useOperandPosition == this.useOperandPosition ? true : false;
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

    public boolean verify(UseNode useNode) {
        return super.equals(useNode) && this.useOperandPosition == useNode.useOperandPosition;
    }
}

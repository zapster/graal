package org.graalvm.compiler.lir.saraverify;

import org.graalvm.compiler.lir.LIRInstruction;

public abstract class Node {

    protected LIRInstruction instruction;

    public Node(LIRInstruction instruction) {
        this.instruction = instruction;
    }

    public LIRInstruction getInstruction() {
        return instruction;
    }

    public abstract boolean isDefNode();

    public abstract boolean isUseNode();

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Node)) {
            return false;
        }

        Node node = (Node) obj;
        return instruction.equals(node.instruction);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + System.identityHashCode(instruction);
        return result;
    }

    public abstract String duSequenceToString();
}

package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.graalvm.compiler.lir.LIRInstruction;

public abstract class Node {

    protected LIRInstruction instruction;
    protected List<Node> nextNodes;

    public Node(LIRInstruction instruction) {
        this.instruction = instruction;
        this.nextNodes = new ArrayList<>();
    }

    public LIRInstruction getInstruction() {
        return instruction;
    }

    public List<Node> getNextNodes() {
        return nextNodes;
    }

    public void addNextNodes(Node nextNode) {
        nextNodes.add(nextNode);
    }

    public void addAllNextNodes(Collection<? extends Node> nextNodesArg) {
        nextNodes.addAll(nextNodesArg);
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

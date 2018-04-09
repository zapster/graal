package org.graalvm.compiler.lir.saraverify;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.graalvm.compiler.lir.LIRInstruction;

public abstract class Node {

    private LIRInstruction instruction;
    private Set<Node> nextNodes;

    public Node(LIRInstruction instruction) {
        this.instruction = instruction;
        this.nextNodes = new HashSet<>();
    }

    public LIRInstruction getInstruction() {
        return instruction;
    }

    public Set<Node> getNextNodes() {
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
}

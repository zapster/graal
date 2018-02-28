package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.graalvm.compiler.lir.LIRInstruction;

import jdk.vm.ci.meta.Value;

public class DefNode extends Node {

    private Value value;
    private int defOperandPosition;
    private List<Node> nextNodes;

    public DefNode(Value value, LIRInstruction instruction, int defOperandPosition) {
        super(instruction);
        this.value = value;
        this.defOperandPosition = defOperandPosition;
        nextNodes = new ArrayList<>();
    }

    public Value getValue() {
        return value;
    }

    public int getDefOperandPosition() {
        return defOperandPosition;
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
        return super.equals(defNode) && defNode.value.equals(this.value) && defNode.defOperandPosition == this.defOperandPosition ? true : false;
    }

    @Override
    public String toString() {
        return "DEF:" + value + ":" + defOperandPosition + ":" + instruction.name();
    }

    @Override
    public String duSequenceToString() {
        String string = "";

        for (Node node : nextNodes) {
            string = string + toString() + node.duSequenceToString() + "\n";
        }

        return string;
    }

    @Override
    public boolean isDefNode() {
        return true;
    }

    @Override
    public boolean isUseNode() {
        return false;
    }

    public boolean verify(DefNode defNode) {
        return super.equals(defNode) && this.defOperandPosition == defNode.defOperandPosition;
    }
}

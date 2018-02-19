package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.compiler.lir.LIRInstruction;

import jdk.vm.ci.meta.Value;

public class DefNode extends Node {

    private Value value;
    private LIRInstruction defInstruction;
    private int defOperandPosition;
    private List<Node> nextNodes;

    public DefNode(Value value, LIRInstruction defInstruction, int defOperandPosition) {
        this.value = value;
        this.defInstruction = defInstruction;
        this.defOperandPosition = defOperandPosition;
        nextNodes = new ArrayList<>();
    }

    public Value getValue() {
        return value;
    }

    public LIRInstruction getDefInstruction() {
        return defInstruction;
    }

    public int getDefOperandPosition() {
        return defOperandPosition;
    }

    public void addNextNode(Node nextNode) {
        nextNodes.add(nextNode);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + System.identityHashCode(defInstruction);
        result = prime * result + defOperandPosition;
        result = prime * result + value.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DefNode)) {
            return false;
        }

        DefNode defNode = (DefNode) obj;
        return defNode.value.equals(this.value) && defNode.defInstruction.equals(this.defInstruction) && defNode.defOperandPosition == this.defOperandPosition ? true : false;
    }
}

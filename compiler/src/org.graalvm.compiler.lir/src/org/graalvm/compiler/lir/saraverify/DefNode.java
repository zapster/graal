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
}

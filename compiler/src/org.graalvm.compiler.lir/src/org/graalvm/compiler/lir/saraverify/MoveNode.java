package org.graalvm.compiler.lir.saraverify;

import java.util.List;

import org.graalvm.compiler.lir.LIRInstruction;

import jdk.vm.ci.meta.Value;

public class MoveNode {

    private Value result;
    private Value input;
    private LIRInstruction moveInstruction;
    private int resultOperandPosition;
    private int inputOperandPosition;
    private List<Node> nextNodes;

    public MoveNode(Value result, Value input, LIRInstruction moveInstruction, int resultOperandPosition, int inputOperandPosition) {
        this.result = result;
        this.input = input;
        this.moveInstruction = moveInstruction;
        this.resultOperandPosition = resultOperandPosition;
        this.inputOperandPosition = inputOperandPosition;
    }

    public Value getResult() {
        return result;
    }

    public Value getInput() {
        return input;
    }

    public LIRInstruction getMoveInstruction() {
        return moveInstruction;
    }

    public int getResultOperandPosition() {
        return resultOperandPosition;
    }

    public int getInputOperandPosition() {
        return inputOperandPosition;
    }

    public void addNextNode(Node nextNode) {
        nextNodes.add(nextNode);
    }
}

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

    @Override
    public int hashCode() {
        final int prime = 31;
        int hashCode = 1;
        hashCode = prime * hashCode + input.hashCode();
        hashCode = prime * hashCode + inputOperandPosition;
        hashCode = prime * hashCode + System.identityHashCode(moveInstruction);
        hashCode = prime * hashCode + result.hashCode();
        hashCode = prime * hashCode + resultOperandPosition;
        return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof MoveNode)) {
            return false;
        }

        MoveNode moveNode = (MoveNode) obj;
        return moveNode.input.equals(this.input) && moveNode.inputOperandPosition == this.inputOperandPosition && moveNode.moveInstruction.equals(this.moveInstruction) &&
                        moveNode.result.equals(this.result) && moveNode.resultOperandPosition == this.resultOperandPosition ? true : false;
    }

}

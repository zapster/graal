package org.graalvm.compiler.lir.saraverify;

import org.graalvm.compiler.lir.LIRInstruction;

import jdk.vm.ci.meta.Value;

public class MoveNode extends Node {

    private Value result;
    private Value input;
    private int resultOperandPosition;
    private int inputOperandPosition;

    public MoveNode(Value result, Value input, LIRInstruction instruction, int resultOperandPosition,
                    int inputOperandPosition) {
        super(instruction);
        this.result = result;
        this.input = input;
        this.resultOperandPosition = resultOperandPosition;
        this.inputOperandPosition = inputOperandPosition;
    }

    public Value getResult() {
        return result;
    }

    public Value getInput() {
        return input;
    }

    public int getResultOperandPosition() {
        return resultOperandPosition;
    }

    public int getInputOperandPosition() {
        return inputOperandPosition;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int hashCode = 1;
        hashCode = prime * hashCode + input.hashCode();
        hashCode = prime * hashCode + inputOperandPosition;
        hashCode = prime * hashCode + result.hashCode();
        hashCode = prime * hashCode + resultOperandPosition;
        return hashCode + super.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof MoveNode)) {
            return false;
        }

        MoveNode moveNode = (MoveNode) obj;
        return super.equals(moveNode) && moveNode.input.equals(this.input) && moveNode.inputOperandPosition == this.inputOperandPosition &&
                        moveNode.result.equals(this.result) && moveNode.resultOperandPosition == this.resultOperandPosition ? true : false;
    }

    @Override
    public String toString() {
        return "MOVE:" + result + ":" + resultOperandPosition + "=" + input + ":" + inputOperandPosition + ":" + getInstruction().name();
    }

    @Override
    public boolean isDefNode() {
        return false;
    }

    @Override
    public boolean isUseNode() {
        return false;
    }
}

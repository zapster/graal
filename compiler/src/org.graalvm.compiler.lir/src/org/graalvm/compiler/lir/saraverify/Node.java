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

    public abstract String duSequenceToString();
}

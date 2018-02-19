package org.graalvm.compiler.lir.saraverify;

import org.graalvm.compiler.lir.LIRInstruction;

import jdk.vm.ci.meta.Value;

public class UseNode extends Node {

    private Value value;
    private LIRInstruction useInstruction;
    private int useOperandPosition;

    public UseNode(Value value, LIRInstruction useInstruction, int useOperandPosition) {
        this.value = value;
        this.useInstruction = useInstruction;
        this.useOperandPosition = useOperandPosition;
    }

    public Value getValue() {
        return value;
    }

    public LIRInstruction getUseInstruction() {
        return useInstruction;
    }

    public int getUseOperandPosition() {
        return useOperandPosition;
    }

}

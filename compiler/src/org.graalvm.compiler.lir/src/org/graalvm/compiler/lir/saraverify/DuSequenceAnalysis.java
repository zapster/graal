package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;

import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

public class DuSequenceAnalysis {

    public static List<DuPair> determineDuPairs(ArrayList<LIRInstruction> instructions) {
        ArrayList<DuPair> duPairs = new ArrayList<>();
        HashMap<Value, LinkedList<ValUsage>> valUseInstructions = new HashMap<>();

        DefInstructionValueConsumer defConsumer = new DefInstructionValueConsumer(valUseInstructions, duPairs);
        UseInstructionValueConsumer useConsumer = new UseInstructionValueConsumer(valUseInstructions);

        List<LIRInstruction> reverseInstructions = new ArrayList<>(instructions);
        Collections.reverse(reverseInstructions);

        for (LIRInstruction inst : reverseInstructions) {
            System.out.println(inst);
            inst.visitEachOutput(defConsumer);
            useConsumer.setOperandUsePosition(1);
            inst.visitEachInput(useConsumer);
        }

        return duPairs;
    }

}

class ValUsage {
    private LIRInstruction useInstruction;
    private int operandPosition;

    public ValUsage(LIRInstruction useInstruction, int operandPosition) {
        this.useInstruction = useInstruction;
        this.operandPosition = operandPosition;
    }

    public LIRInstruction getUseInstruction() {
        return useInstruction;
    }

    public int getOperandPosition() {
        return operandPosition;
    }
}

class DefInstructionValueConsumer implements InstructionValueConsumer {

    private HashMap<Value, LinkedList<ValUsage>> valUseInstructions;
    private ArrayList<DuPair> duPairs;

    public DefInstructionValueConsumer(HashMap<Value, LinkedList<ValUsage>> valUseInstructions, ArrayList<DuPair> duPairs) {
        this.valUseInstructions = valUseInstructions;
        this.duPairs = duPairs;
    }

    @Override
    public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
        System.out.println("Def of value: " + value);

        LinkedList<ValUsage> useInstructions = valUseInstructions.get(value);
        if (useInstructions == null) {
            return;
        }

        AllocatableValue allocatableValue = ValueUtil.asAllocatableValue(value);
        useInstructions.stream().forEach(usage -> duPairs.add(new DuPair(allocatableValue, instruction, usage.getUseInstruction(), usage.getOperandPosition())));

        valUseInstructions.remove(value);
    }

}

class UseInstructionValueConsumer implements InstructionValueConsumer {

    private HashMap<Value, LinkedList<ValUsage>> valUseInstructions;
    private int operandUsePosition;

    public UseInstructionValueConsumer(HashMap<Value, LinkedList<ValUsage>> valUseInstructions) {
        this.valUseInstructions = valUseInstructions;
        this.operandUsePosition = 1;
    }

    @Override
    public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
        System.out.println(value);
        System.out.println("Use Operand Position: " + operandUsePosition);

        LinkedList<ValUsage> useInstructions = valUseInstructions.get(value);

        if (useInstructions == null) {
            useInstructions = new LinkedList<>();
        }
        useInstructions.add(new ValUsage(instruction, operandUsePosition));
        valUseInstructions.put(value, useInstructions);

        operandUsePosition++;
    }

    public void setOperandUsePosition(int operandUsePosition) {
        this.operandUsePosition = operandUsePosition;
    }
}

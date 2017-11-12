package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
        Map<Value, ArrayList<ValUsage>> valUseInstructions = new TreeMap<>(new SARAVerifyValueComparator());

        DefInstructionValueConsumer defConsumer = new DefInstructionValueConsumer(valUseInstructions, duPairs);
        UseInstructionValueConsumer useConsumer = new UseInstructionValueConsumer(valUseInstructions);

        List<LIRInstruction> reverseInstructions = new ArrayList<>(instructions);
        Collections.reverse(reverseInstructions);

        for (LIRInstruction inst : reverseInstructions) {
            System.out.println(inst);

            defConsumer.resetOperandDefPosition();
            inst.visitEachOutput(defConsumer);

            useConsumer.resetOperandUsePosition();
            inst.visitEachInput(useConsumer);
        }

        return duPairs;
    }

    static class ValUsage {
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

    static class DefInstructionValueConsumer implements InstructionValueConsumer {

        private Map<Value, ArrayList<ValUsage>> valUseInstructions;
        private ArrayList<DuPair> duPairs;
        private int operandDefPosition;

        public DefInstructionValueConsumer(Map<Value, ArrayList<ValUsage>> valUseInstructions, ArrayList<DuPair> duPairs) {
            this.valUseInstructions = valUseInstructions;
            this.duPairs = duPairs;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            System.out.println("Def of value: " + value);

            ArrayList<ValUsage> useInstructions = valUseInstructions.get(value);
            if (useInstructions == null) {
                // definition of a value, which is not used
                return;
            }

            AllocatableValue allocatableValue = ValueUtil.asAllocatableValue(value);
            useInstructions.stream().forEach(usage -> duPairs.add(new DuPair(allocatableValue, instruction, usage.getUseInstruction(), operandDefPosition, usage.getOperandPosition())));

            valUseInstructions.remove(value);

            operandDefPosition++;
        }

        public void resetOperandDefPosition() {
            operandDefPosition = 0;
        }

    }

    static class UseInstructionValueConsumer implements InstructionValueConsumer {

        private Map<Value, ArrayList<ValUsage>> valUseInstructions;
        private int operandUsePosition;

        public UseInstructionValueConsumer(Map<Value, ArrayList<ValUsage>> valUseInstructions) {
            this.valUseInstructions = valUseInstructions;
            resetOperandUsePosition();
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            System.out.println(value);
            System.out.println("Use Operand Position: " + operandUsePosition);

            ArrayList<ValUsage> useInstructions = valUseInstructions.get(value);

            if (useInstructions == null) {
                useInstructions = new ArrayList<>();
            }
            useInstructions.add(new ValUsage(instruction, operandUsePosition));
            valUseInstructions.put(value, useInstructions);

            operandUsePosition++;
        }

        public void resetOperandUsePosition() {
            this.operandUsePosition = 0;
        }
    }

}

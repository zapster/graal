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

    private int operandDefPosition;
    private int operandUsePosition;

    private ArrayList<DuPair> duPairs;
    private ArrayList<DuSequence> duSequences;
    private ArrayList<DuSequenceWeb> duSequenceWebs;
    private Map<Value, ArrayList<ValUsage>> valUseInstructions;
    private ArrayList<String> output;

    public List<DuSequenceWeb> determineDuSequenceWebs(ArrayList<LIRInstruction> instructions) {
        valUseInstructions = new TreeMap<>(new SARAVerifyValueComparator());
        duPairs = new ArrayList<>();
        duSequences = new ArrayList<>();
        duSequenceWebs = new ArrayList<>();
        output = new ArrayList<>();

        DefInstructionValueConsumer defConsumer = new DefInstructionValueConsumer();
        UseInstructionValueConsumer useConsumer = new UseInstructionValueConsumer();

        List<LIRInstruction> reverseInstructions = new ArrayList<>(instructions);
        Collections.reverse(reverseInstructions);

        for (LIRInstruction inst : reverseInstructions) {
            operandDefPosition = 0;
            operandUsePosition = 0;

            visitValues(inst, defConsumer, useConsumer, useConsumer);

            output.add("\n" + inst);
        }

        Collections.reverse(output);
        // output.stream().forEach(x -> System.out.println(x));

        return duSequenceWebs;
    }

    public ArrayList<DuPair> getDuPairs() {
        return duPairs;
    }

    public ArrayList<DuSequence> getDuSequences() {
        return duSequences;
    }

    private static void visitValues(LIRInstruction instruction, InstructionValueConsumer defConsumer,
                    InstructionValueConsumer useConsumer, InstructionValueConsumer aliveConsumer) {
        instruction.visitEachOutput(defConsumer);
        instruction.visitEachInput(useConsumer);
        instruction.visitEachAlive(aliveConsumer);
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

    class DefInstructionValueConsumer implements InstructionValueConsumer {

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            output.add(mode + ": " + value + " @ pos: " + operandDefPosition);

            ArrayList<ValUsage> useInstructions = valUseInstructions.get(value);
            if (useInstructions == null) {
                // definition of a value, which is not used
                operandDefPosition++;
                return;
            }

            AllocatableValue allocatableValue = ValueUtil.asAllocatableValue(value);
            DuSequenceWeb duSequenceWeb = new DuSequenceWeb();

            for (ValUsage valUsage : useInstructions) {
                DuPair duPair = new DuPair(allocatableValue, instruction, valUsage.getUseInstruction(),
                                operandDefPosition, valUsage.getOperandPosition());
                duPairs.add(duPair);

                if (valUsage.getUseInstruction().isValueMoveOp()) {
                    // copy use instruction
                    duSequences.stream().filter(duSequence -> duSequence.peekFirst().getDefInstruction().equals(valUsage.getUseInstruction())).forEach(x -> x.addFirst(duPair));
                } else {
                    // non copy use instruction
                    DuSequence duSequence = new DuSequence(duPair);
                    duSequences.add(duSequence);
                    duSequenceWeb.add(duSequence);
                }
            }

            if (duSequenceWeb.size() > 0) {
                duSequenceWebs.add(duSequenceWeb);
            }

            valUseInstructions.remove(value);

            operandDefPosition++;
        }

    }

    class UseInstructionValueConsumer implements InstructionValueConsumer {

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            output.add(mode + ": " + value + " @ pos: " + operandUsePosition);

            ArrayList<ValUsage> useInstructions = valUseInstructions.get(value);

            if (useInstructions == null) {
                useInstructions = new ArrayList<>();
            }
            useInstructions.add(new ValUsage(instruction, operandUsePosition));
            valUseInstructions.put(value, useInstructions);

            operandUsePosition++;
        }

    }

}

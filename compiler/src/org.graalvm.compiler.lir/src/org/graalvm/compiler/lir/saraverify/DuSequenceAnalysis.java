package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;

import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

public class DuSequenceAnalysis {

    private int operandDefPosition;
    private int operandUsePosition;

    private ArrayList<DuPair> duPairs;
    private ArrayList<DuSequence> duSequences;
    private ArrayList<DuSequenceWeb> duSequenceWebs;

    private Map<LIRInstruction, Integer> instructionDefOperandCount;
    private Map<LIRInstruction, Integer> instructionUseOperandCount;

    HashMap<AbstractBlockBase<?>, Map<Value, ArrayList<ValUsage>>> blockValUseInstructions;

    public AnalysisResult determineDuSequenceWebs(LIRGenerationResult lirGenRes) {
        LIR lir = lirGenRes.getLIR();
        AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();
        HashSet<AbstractBlockBase<?>> blockQueue = new HashSet<>();

        if (!(lir.getControlFlowGraph().getLoops().isEmpty())) {
            // control flow contains one or more loops
            return null;
        }

        initializeCollections();

        // start with leaf blocks
        for (AbstractBlockBase<?> block : blocks) {
            if (block.getSuccessorCount() == 0) {
                blockQueue.add(block);
            }
        }

        ArrayList<AbstractBlockBase<?>> visitedBlocks = new ArrayList<>();
        blockValUseInstructions = new HashMap<>();

        while (!blockQueue.isEmpty()) {
            // get any block, whose successors have already been visited, remove it from the queue and add its
            // predecessors to the queue
            AbstractBlockBase<?> block = blockQueue.stream().filter(b -> visitedBlocks.containsAll(Arrays.asList(b.getSuccessors()))).findFirst().get();
            blockQueue.remove(block);
            blockQueue.addAll(Arrays.asList(block.getPredecessors()));
            visitedBlocks.add(block);

            // get instructions of block
            ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);

            Map<Value, ArrayList<ValUsage>> valUseInstructions = mergeSuccessorValUseInstructions(block);
            blockValUseInstructions.put(block, valUseInstructions);

            determineDuSequenceWebs(instructions, valUseInstructions);
        }

        assert visitedBlocks.containsAll(Arrays.asList(blocks));

        return new AnalysisResult(duPairs, duSequences, duSequenceWebs, instructionDefOperandCount, instructionUseOperandCount);
    }

    public AnalysisResult determineDuSequenceWebs(ArrayList<LIRInstruction> instructions) {
        initializeCollections();
        return determineDuSequenceWebs(instructions, new TreeMap<>(new SARAVerifyValueComparator()));
    }

    private AnalysisResult determineDuSequenceWebs(ArrayList<LIRInstruction> instructions, Map<Value, ArrayList<ValUsage>> valUseInstructions) {
        DefInstructionValueConsumer defConsumer = new DefInstructionValueConsumer(valUseInstructions);
        UseInstructionValueConsumer useConsumer = new UseInstructionValueConsumer(valUseInstructions);

        List<LIRInstruction> reverseInstructions = new ArrayList<>(instructions);
        Collections.reverse(reverseInstructions);

        for (LIRInstruction inst : reverseInstructions) {
            operandDefPosition = 0;
            operandUsePosition = 0;

            visitValues(inst, defConsumer, useConsumer, useConsumer);

            instructionDefOperandCount.put(inst, operandDefPosition);
            instructionUseOperandCount.put(inst, operandUsePosition);
        }

        return new AnalysisResult(duPairs, duSequences, duSequenceWebs, instructionDefOperandCount, instructionUseOperandCount);
    }

    private void initializeCollections() {
        this.duPairs = new ArrayList<>();
        this.duSequences = new ArrayList<>();
        this.duSequenceWebs = new ArrayList<>();

        instructionDefOperandCount = new IdentityHashMap<>();
        instructionUseOperandCount = new IdentityHashMap<>();
    }

    private Map<Value, ArrayList<ValUsage>> mergeSuccessorValUseInstructions(AbstractBlockBase<?> block) {
        AbstractBlockBase<?>[] successors = block.getSuccessors();

        Map<Value, ArrayList<ValUsage>> merged = new TreeMap<>(new SARAVerifyValueComparator());

        for (AbstractBlockBase<?> successor : successors) {
            Map<Value, ArrayList<ValUsage>> successorValUseInstructions = blockValUseInstructions.get(successor);

            for (Entry<Value, ArrayList<ValUsage>> entry : successorValUseInstructions.entrySet()) {
                ArrayList<ValUsage> valUsages = merged.get(entry.getKey());

                if (valUsages == null) {
                    valUsages = new ArrayList<>();
                    merged.put(entry.getKey(), valUsages);
                }

                valUsages.addAll(entry.getValue());
            }
        }
        return merged;
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

        private Map<Value, ArrayList<ValUsage>> valUseInstructions;

        public DefInstructionValueConsumer(Map<Value, ArrayList<ValUsage>> valUseInstructions) {
            this.valUseInstructions = valUseInstructions;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            if (ValueUtil.isIllegal(value)) {
                // value is part of a composite value
                operandDefPosition++;
                return;
            }

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

        private Map<Value, ArrayList<ValUsage>> valUseInstructions;

        public UseInstructionValueConsumer(Map<Value, ArrayList<ValUsage>> valUseInstructions) {
            this.valUseInstructions = valUseInstructions;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            if (ValueUtil.isIllegal(value)) {
                // value is part of a composite value
                operandUsePosition++;
                return;
            }

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

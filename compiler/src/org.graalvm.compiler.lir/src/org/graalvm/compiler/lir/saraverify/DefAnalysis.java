package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugContext.Scope;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.StandardOp.JumpOp;
import org.graalvm.compiler.lir.StandardOp.LoadConstantOp;
import org.graalvm.compiler.lir.StandardOp.ValueMoveOp;
import org.graalvm.compiler.lir.saraverify.DefAnalysisInfo.Triple;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyConstDef;

import jdk.vm.ci.code.RegisterArray;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class DefAnalysis {

    protected final static String DEBUG_SCOPE = "SARAVerifyDefAnalysis";

    public static DefAnalysisResult analyse(LIR lir, Map<Node, DuSequenceWeb> mapping, RegisterArray callerSaveRegisters, Map<Constant, DummyConstDef> dummyConstDefs) {
        DebugContext debugContext = lir.getDebug();
        AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();

        // the map stores the sets after the analysis of the particular block/instruction
        Map<AbstractBlockBase<?>, DefAnalysisInfo> blockInfos = new HashMap<>();

        // create a list of register values with value kind illegal of caller saved registers
        List<Value> callerSaveRegisterValues = callerSaveRegisters.asList() //
                        .stream().map(register -> register.asValue(ValueKind.Illegal)).collect(Collectors.toList());

        int blockCount = blocks.length;
        BitSet blockQueue = new BitSet(blockCount);
        blockQueue.set(0, blockCount);

        Set<AbstractBlockBase<?>> visited = new HashSet<>();

        // TODO: setInitialization?

        while (!blockQueue.isEmpty()) {
            int blockIndex = blockQueue.nextSetBit(0);
            blockQueue.clear(blockIndex);
            AbstractBlockBase<?> block = blocks[blockIndex];
            visited.add(block);

            try (Indent i = debugContext.indent(); Scope s = debugContext.scope(DEBUG_SCOPE)) {
                System.out.println("Visit Block: " + blockIndex);
            }

            DefAnalysisInfo mergedDefAnalysisInfo;
            if (blockIndex == 0) {
                mergedDefAnalysisInfo = new DefAnalysisInfo();
                initializeDefAnalysisInfoWithConstants(mapping, dummyConstDefs, mergedDefAnalysisInfo);
            } else {
                mergedDefAnalysisInfo = mergeDefAnalysisInfo(blockInfos, block.getPredecessors());
            }

            computeLocalFlow(lir.getLIRforBlock(block), mergedDefAnalysisInfo, mapping, callerSaveRegisterValues);
            DefAnalysisInfo previousDefAnalysisSets = blockInfos.get(block);

            if (!mergedDefAnalysisInfo.equals(previousDefAnalysisSets)) {
                blockInfos.put(block, mergedDefAnalysisInfo);

                for (AbstractBlockBase<?> successor : block.getSuccessors()) {
                    blockQueue.set(successor.getId());
                }
            }

            mergedDefAnalysisInfo.logSetSizes(debugContext);
        }

        assert Arrays.stream(blocks).allMatch(block -> visited.contains(block)) : "Not all blocks were visited during the defAnalysis.";

        try (Indent i = debugContext.indent(); Scope s = debugContext.scope(DEBUG_SCOPE)) {
            System.out.println("Analysis done!");
        }

        return new DefAnalysisResult(blockInfos);
    }

    protected static void initializeDefAnalysisInfoWithConstants(Map<Node, DuSequenceWeb> mapping, Map<Constant, DummyConstDef> dummyConstDefs, DefAnalysisInfo defAnalysisInfo) {
        // add locations of constants
        for (Entry<Constant, DummyConstDef> entry : dummyConstDefs.entrySet()) {
            Value constantValue = SARAVerifyUtil.asConstantValue(entry.getKey());
            LIRInstruction dummyConstDef = entry.getValue();

            DefNode defNode = new DefNode(constantValue, dummyConstDef, 0);
            DuSequenceWeb web = mapping.get(defNode);
            defAnalysisInfo.addLocation(constantValue, web, dummyConstDef, false);
        }
    }

    private static void computeLocalFlow(ArrayList<LIRInstruction> instructions, DefAnalysisInfo defAnalysisInfo,
                    Map<Node, DuSequenceWeb> mapping, List<Value> callerSaveRegisterValues) {

        List<Value> tempValues = new ArrayList<>();

        DefAnalysisNonCopyValueConsumer nonCopyValueConsumer = new DefAnalysisNonCopyValueConsumer(defAnalysisInfo, mapping);
        DefAnalysisTempValueConsumer tempValueConsumer = new DefAnalysisTempValueConsumer(tempValues);

        for (LIRInstruction instruction : instructions) {
            computeLocalFlowInstruction(defAnalysisInfo, callerSaveRegisterValues, tempValues, nonCopyValueConsumer, tempValueConsumer, instruction);
        }
    }

    protected static void computeLocalFlowInstruction(DefAnalysisInfo defAnalysisInfo, List<Value> callerSaveRegisterValues, List<Value> tempValues,
                    DefAnalysisNonCopyValueConsumer nonCopyValueConsumer,
                    DefAnalysisTempValueConsumer tempValueConsumer, LIRInstruction instruction) {
        tempValues.clear();
        nonCopyValueConsumer.defOperandPosition = 0;

        if (instruction instanceof JumpOp) {
            // phi values from the input code are replaced by move operations in the output code
            assert ((JumpOp) instruction).getPhiSize() == 0 : "phi in output code";
        }

        if (instruction.destroysCallerSavedRegisters()) {
            defAnalysisInfo.destroyValuesAtLocations(callerSaveRegisterValues, instruction);
        }

        // temp values are treated like caller saved registers
        instruction.visitEachTemp(tempValueConsumer);
        defAnalysisInfo.destroyValuesAtLocations(tempValues, instruction);

        if (instruction.isValueMoveOp()) {
            // copy instruction
            ValueMoveOp valueMoveOp = (ValueMoveOp) instruction;

            defAnalysisInfo.propagateValue(valueMoveOp.getResult(), valueMoveOp.getInput(), instruction);
        } else if (instruction.isLoadConstantOp()) {
            LoadConstantOp loadConstantOp = (LoadConstantOp) instruction;

            defAnalysisInfo.propagateValue(loadConstantOp.getResult(), SARAVerifyUtil.asConstantValue(loadConstantOp.getConstant()), instruction);
        } else {
            // non copy instruction
            instruction.visitEachOutput(nonCopyValueConsumer);
        }
    }

    protected static <T> DefAnalysisInfo mergeDefAnalysisInfo(Map<T, DefAnalysisInfo> map, T[] mergeKeys) {
        List<DefAnalysisInfo> defAnalysisInfo = new ArrayList<>();

        for (T key : mergeKeys) {
            if (map.containsKey(key)) {
                defAnalysisInfo.add(map.get(key));
            }
        }

        Set<Triple> locationIntersection = DefAnalysisInfo.locationSetIntersection(defAnalysisInfo);
        Set<Triple> staleUnion = DefAnalysisInfo.staleSetUnion(defAnalysisInfo);
        Set<Triple> evicted = DefAnalysisInfo.evictedSetUnion(defAnalysisInfo);

        Set<Triple> locationInconsistent = DefAnalysisInfo  //
                        .locationSetUnionStream(defAnalysisInfo)   //
                        .filter(triple -> !DefAnalysisInfo.containsTriple(triple, locationIntersection))    //
                        .collect(Collectors.toSet());

        Set<Triple> stale = staleUnion.stream() //
                        .filter(triple -> !DefAnalysisInfo.containsTriple(triple, locationInconsistent))    //
                        .collect(Collectors.toSet());

        evicted.addAll(locationInconsistent);

        return new DefAnalysisInfo(locationIntersection, stale, evicted);
    }

    private static void analyzeDefinition(Value value, LIRInstruction instruction, DuSequenceWeb mappedWeb, DefAnalysisInfo defAnalysisInfo) {
        defAnalysisInfo.destroyValuesAtLocations(Arrays.asList(value), instruction);

        if (mappedWeb == null) {
            return;
        }

        defAnalysisInfo.addLocation(value, mappedWeb, instruction, true);
        defAnalysisInfo.removeFromEvicted(value, mappedWeb);
    }

    private static void analyzeCopy(AllocatableValue result, Value input, LIRInstruction instruction, DuSequenceWeb mappedWeb, DefAnalysisInfo defAnalysisInfo) {
        defAnalysisInfo.destroyValuesAtLocations(Arrays.asList(result), instruction);
        defAnalysisInfo.propagateValue(result, input, instruction);
        defAnalysisInfo.removeFromEvicted(result, mappedWeb);
    }

    protected static class DefAnalysisNonCopyValueConsumer implements InstructionValueConsumer {

        private int defOperandPosition;
        private DefAnalysisInfo defAnalysisSets;
        private Map<Node, DuSequenceWeb> mapping;

        public DefAnalysisNonCopyValueConsumer(DefAnalysisInfo defAnalysisSets, Map<Node, DuSequenceWeb> mapping) {
            defOperandPosition = 0;
            this.defAnalysisSets = defAnalysisSets;
            this.mapping = mapping;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            if (ValueUtil.isIllegal(value)) {
                // value is part of a composite value
                defOperandPosition++;
                return;
            }

            DefNode defNode = new DefNode(value, instruction, defOperandPosition);
            DuSequenceWeb mappedWeb = mapping.get(defNode);
            analyzeDefinition(value, instruction, mappedWeb, defAnalysisSets);
            defOperandPosition++;
        }

    }

    protected static class DefAnalysisTempValueConsumer implements InstructionValueConsumer {

        private List<Value> tempValues;

        public DefAnalysisTempValueConsumer(List<Value> tempValues) {
            this.tempValues = tempValues;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            tempValues.add(value);
        }

    }

}

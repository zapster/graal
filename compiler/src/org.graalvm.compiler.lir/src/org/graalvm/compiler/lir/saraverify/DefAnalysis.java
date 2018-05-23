package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class DefAnalysis {

    protected final static String DEBUG_SCOPE = "SARAVerifyDefAnalysis";

    public static DefAnalysisResult analyse(LIR lir, Map<Node, DuSequenceWeb> mapping, RegisterArray callerSaveRegisters, Map<Constant, DummyConstDef> dummyConstDefs) {
        DebugContext debugContext = lir.getDebug();
        AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();

        // the map stores the sets after the analysis of the particular block/instruction
        Map<AbstractBlockBase<?>, DefAnalysisInfo> blockSets = new HashMap<>();

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

            DefAnalysisInfo mergedDefAnalysisSets = mergeDefAnalysisSets(blockSets, block.getPredecessors());
            computeLocalFlow(lir.getLIRforBlock(block), mergedDefAnalysisSets, mapping, callerSaveRegisterValues, dummyConstDefs);
            DefAnalysisInfo previousDefAnalysisSets = blockSets.get(block);

            if (!mergedDefAnalysisSets.equals(previousDefAnalysisSets)) {
                blockSets.put(block, mergedDefAnalysisSets);

                for (AbstractBlockBase<?> successor : block.getSuccessors()) {
                    blockQueue.set(successor.getId());
                }
            }

            mergedDefAnalysisSets.logSetSizes(debugContext);
        }

        assert Arrays.stream(blocks).allMatch(block -> visited.contains(block)) : "Not all blocks were visited during the defAnalysis.";

        try (Indent i = debugContext.indent(); Scope s = debugContext.scope(DEBUG_SCOPE)) {
            System.out.println("Analysis done!");
        }

        return new DefAnalysisResult();
    }

    private static void computeLocalFlow(ArrayList<LIRInstruction> instructions, DefAnalysisInfo defAnalysisSets,
                    Map<Node, DuSequenceWeb> mapping, List<Value> callerSaveRegisterValues, Map<Constant, DummyConstDef> dummyConstDefs) {

        List<Value> tempValues = new ArrayList<>();

        DefAnalysisNonCopyValueConsumer nonCopyValueConsumer = new DefAnalysisNonCopyValueConsumer(defAnalysisSets, mapping);
        DefAnalysisTempValueConsumer tempValueConsumer = new DefAnalysisTempValueConsumer(tempValues);

        for (LIRInstruction instruction : instructions) {
            tempValues.clear();
            nonCopyValueConsumer.defOperandPosition = 0;

            if (instruction instanceof JumpOp) {
                // phi values from the input code are replaced by move operations in the output code
                assert ((JumpOp) instruction).getPhiSize() == 0 : "phi in output code";
            }

            if (instruction.destroysCallerSavedRegisters()) {
                defAnalysisSets.destroyValuesAtLocations(callerSaveRegisterValues, instruction);
            }

            // temp values are treated like caller saved registers
            instruction.visitEachTemp(tempValueConsumer);
            defAnalysisSets.destroyValuesAtLocations(tempValues, instruction);

            if (instruction.isValueMoveOp()) {
                // copy instruction
                ValueMoveOp valueMoveOp = (ValueMoveOp) instruction;

                defAnalysisSets.propagateValue(valueMoveOp.getResult(), valueMoveOp.getInput(), instruction);
            } else if (instruction.isLoadConstantOp()) {
                LoadConstantOp loadConstantOp = (LoadConstantOp) instruction;
                Constant constant = loadConstantOp.getConstant();
                Value result = loadConstantOp.getResult();

                DummyConstDef dummyConstDef = dummyConstDefs.get(constant);
                assert dummyConstDef != null : "No dummy definition for constant: " + loadConstantOp.getConstant() + ".";

                // get mapping
                DefNode defNode = new DefNode(SARAVerifyUtil.asConstantValue(constant), dummyConstDef, 0);
                DuSequenceWeb mappedWeb = mapping.get(defNode);

                analyzeDefinition(result, instruction, mappedWeb, defAnalysisSets);
            } else {
                instruction.visitEachOutput(nonCopyValueConsumer);
            }
        }
    }

    private static <T> DefAnalysisInfo mergeDefAnalysisSets(Map<T, DefAnalysisInfo> map, T[] mergeKeys) {
        List<DefAnalysisInfo> defAnalysisSets = new ArrayList<>();

        for (T key : mergeKeys) {
            if (map.containsKey(key)) {
                defAnalysisSets.add(map.get(key));
            }
        }

        Set<Triple> locationIntersection = DefAnalysisInfo.locationSetIntersection(defAnalysisSets);
        Set<Triple> staleUnion = DefAnalysisInfo.staleSetUnion(defAnalysisSets);
        Set<Triple> evicted = DefAnalysisInfo.evictedSetUnion(defAnalysisSets);

        Set<Triple> locationInconsistent = DefAnalysisInfo  //
                        .locationSetUnionStream(defAnalysisSets)   //
                        .filter(triple -> !DefAnalysisInfo.containsTriple(triple, locationIntersection))    //
                        .collect(Collectors.toSet());

        Set<Triple> stale = staleUnion.stream() //
                        .filter(triple -> !DefAnalysisInfo.containsTriple(triple, locationInconsistent))    //
                        .collect(Collectors.toSet());

        evicted.addAll(locationInconsistent);

        return new DefAnalysisInfo(locationIntersection, stale, evicted);
    }

    private static void analyzeDefinition(Value value, LIRInstruction instruction, DuSequenceWeb mappedWeb, DefAnalysisInfo defAnalysisSets) {
        defAnalysisSets.destroyValuesAtLocations(Arrays.asList(value), instruction);

        if (mappedWeb == null) {
            return;
        }

        defAnalysisSets.addLocation(value, mappedWeb, instruction);
        defAnalysisSets.removeFromEvicted(value, mappedWeb);
    }

    private static class DefAnalysisNonCopyValueConsumer implements InstructionValueConsumer {

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

    private static class DefAnalysisTempValueConsumer implements InstructionValueConsumer {

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

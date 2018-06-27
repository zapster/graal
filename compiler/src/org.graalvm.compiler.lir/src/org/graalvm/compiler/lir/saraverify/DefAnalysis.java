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
import org.graalvm.compiler.core.common.cfg.BlockMap;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugContext.Scope;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.StandardOp.JumpOp;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.StandardOp.LoadConstantOp;
import org.graalvm.compiler.lir.StandardOp.ValueMoveOp;
import org.graalvm.compiler.lir.saraverify.DefAnalysisInfo.Triple;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyConstDef;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyRegDef;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterArray;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class DefAnalysis {

    protected final static String DEBUG_SCOPE = "SARAVerifyDefAnalysis";

    public static DefAnalysisResult analyse(LIR lir, Map<Node, DuSequenceWeb> mapping, RegisterArray callerSaveRegisters, Map<Register, DummyRegDef> dummyRegDefs,
                    Map<Constant, DummyConstDef> dummyConstDefs, BlockMap<List<Value>> blockPhiInValues, BlockMap<List<Value>> blockPhiOutValues) {
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

            DefAnalysisInfo mergedDefAnalysisInfo;
            if (blockIndex == 0) {
                mergedDefAnalysisInfo = new DefAnalysisInfo();
                initializeDefAnalysisInfo(mapping, dummyRegDefs, dummyConstDefs, mergedDefAnalysisInfo);
            } else {
                mergedDefAnalysisInfo = mergeDefAnalysisInfo(lir, blockInfos, block, mapping, blockPhiInValues, blockPhiOutValues);
            }

            computeLocalFlow(lir.getLIRforBlock(block), mergedDefAnalysisInfo, mapping, callerSaveRegisterValues);
            DefAnalysisInfo previousDefAnalysisSets = blockInfos.get(block);

            if (!mergedDefAnalysisInfo.equals(previousDefAnalysisSets)) {
                blockInfos.put(block, mergedDefAnalysisInfo);

                for (AbstractBlockBase<?> successor : block.getSuccessors()) {
                    blockQueue.set(successor.getId());
                }
            }

            // log information
            try (Indent i = debugContext.indent(); Scope s = debugContext.scope(DEBUG_SCOPE)) {
                debugContext.log(3, "Visited Block: %d", blockIndex);
            }
            mergedDefAnalysisInfo.logSetSizes(debugContext);
        }

        assert Arrays.stream(blocks).allMatch(block -> visited.contains(block)) : "Not all blocks were visited during the defAnalysis.";

        return new DefAnalysisResult(blockInfos);
    }

    protected static void initializeDefAnalysisInfo(Map<Node, DuSequenceWeb> mapping, Map<Register, DummyRegDef> dummyRegDefs, Map<Constant, DummyConstDef> dummyConstDefs,
                    DefAnalysisInfo defAnalysisInfo) {
        // add locations of constants
        for (Entry<Constant, DummyConstDef> entry : dummyConstDefs.entrySet()) {
            Value constantValue = SARAVerifyUtil.asConstantValue(entry.getKey());
            LIRInstruction dummyConstDef = entry.getValue();

            DefNode defNode = new DefNode(constantValue, dummyConstDef, 0);
            DuSequenceWeb web = mapping.get(defNode);
            defAnalysisInfo.addLocation(constantValue, web, dummyConstDef, false);
        }

        // add location of non allocatable registers
        for (Entry<Register, DummyRegDef> entry : dummyRegDefs.entrySet()) {
            DummyRegDef dummyRegDef = entry.getValue();
            AllocatableValue value = dummyRegDef.value;
            DefNode defNode = new DefNode(value, dummyRegDef, 0);
            DuSequenceWeb mappedWeb = mapping.get(defNode);

            defAnalysisInfo.addLocation(value, mappedWeb, dummyRegDef, false);
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

    protected static DefAnalysisInfo mergeDefAnalysisInfo(LIR lir, Map<AbstractBlockBase<?>, DefAnalysisInfo> blockDefAnalysisInfos, AbstractBlockBase<?> mergeBlock,
                    Map<Node, DuSequenceWeb> mapping, BlockMap<List<Value>> blockPhiInValues, BlockMap<List<Value>> blockPhiOutValues) {
        // filter visited predecessors
        List<AbstractBlockBase<?>> visitedPredecessors = Arrays.stream(mergeBlock.getPredecessors())    //
                        .filter(block -> blockDefAnalysisInfos.get(block) != null)      //
                        .collect(Collectors.toList());

        // get all DefAnalysisInfos for visited predecessors
        List<DefAnalysisInfo> defAnalysisInfos = visitedPredecessors.stream()           //
                        .map(block -> blockDefAnalysisInfos.get(block)) //
                        .collect(Collectors.toList());

        // merge of phi
        Map<Value, List<Value>> phiInLocations = new HashMap<>();

        List<Value> phiInValues = blockPhiInValues.get(mergeBlock);
        LabelOp labelInstruction = (LabelOp) lir.getLIRforBlock(mergeBlock).get(0);

        // check if label instruction is phi and if all predecessors are visited
        if (labelInstruction.isPhiIn()) {

            // get jump instructions for predecessor blocks
            BlockMap<LIRInstruction> blockJumpInstructions = new BlockMap<>(lir.getControlFlowGraph());
            for (AbstractBlockBase<?> predecessor : visitedPredecessors) {
                ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(predecessor);
                LIRInstruction jumpInst = instructions.get(instructions.size() - 1);
                assert jumpInst instanceof JumpOp : "Instruction is no jump instruction.";
                blockJumpInstructions.put(predecessor, jumpInst);
            }

            for (int i = 0; i < phiInValues.size(); i++) {
                BlockMap<List<Triple>> phiOutLocationTriplesMap = new BlockMap<>(lir.getControlFlowGraph());

                // get all locations from the predecessors that hold a value
                List<Value> locations = DefAnalysisInfo.distinctLocations(defAnalysisInfos);

                for (AbstractBlockBase<?> predecessor : visitedPredecessors) {
                    // mapping for phi out
                    Value phiOutValue = blockPhiOutValues.get(predecessor).get(i);
                    LIRInstruction jumpInstruction = blockJumpInstructions.get(predecessor);
                    UseNode useNode = new UseNode(phiOutValue, jumpInstruction, i);
                    DuSequenceWeb mappedWeb = mapping.get(useNode);

                    // search for triples (locations) that hold the phi out value
                    List<Triple> phiOutLocationTriples = blockDefAnalysisInfos.get(predecessor).getLocationTriples(mappedWeb);
                    phiOutLocationTriplesMap.put(predecessor, phiOutLocationTriples);

                    // remove all locations, that hold no phi out value
                    locations.removeIf(location -> !phiOutLocationTriples.stream().anyMatch(triple -> triple.getLocation().equals(location)));
                }

                // store locations for phi in value in map
                phiInLocations.put(phiInValues.get(i), locations);
            }
        }

        // merge of sets
        Set<Triple> locationIntersection = DefAnalysisInfo.locationSetIntersection(defAnalysisInfos);
        Set<Triple> staleUnion = DefAnalysisInfo.staleSetUnion(defAnalysisInfos);
        Set<Triple> evicted = DefAnalysisInfo.evictedSetUnion(defAnalysisInfos);

        Set<Triple> locationInconsistent = DefAnalysisInfo  //
                        .locationSetUnionStream(defAnalysisInfos)   //
                        .filter(triple -> !DefAnalysisInfo.containsTriple(triple, locationIntersection))    //
                        .collect(Collectors.toSet());

        Set<Triple> stale = staleUnion.stream() //
                        .filter(triple -> !DefAnalysisInfo.containsTriple(triple, locationInconsistent))    //
                        .collect(Collectors.toSet());

        evicted.addAll(locationInconsistent);

        DefAnalysisInfo mergedDefAnalysisInfo = new DefAnalysisInfo(locationIntersection, stale, evicted);

        if (phiInValues == null) {
            return mergedDefAnalysisInfo;
        }

        // add new triples for phi in
        int i = 0;
        for (Value phiInValue : phiInValues) {
            // get locations for phi in value
            List<Value> locations = phiInLocations.get(phiInValue);

            // get mapping for phi in value
            DefNode defNode = new DefNode(phiInValue, labelInstruction, i);
            DuSequenceWeb mappedWeb = mapping.get(defNode);

            // add phi in triples to the merged def analysis info location set
            locations.stream().forEach(location -> mergedDefAnalysisInfo.addLocation(location, mappedWeb, labelInstruction, true));
            i++;
        }

        return mergedDefAnalysisInfo;
    }

    private static void analyseDefinition(Value value, LIRInstruction instruction, DuSequenceWeb mappedWeb, DefAnalysisInfo defAnalysisInfo) {
        defAnalysisInfo.destroyValuesAtLocations(Arrays.asList(value), instruction);

        if (mappedWeb == null) {
            return;
        }

        defAnalysisInfo.addLocation(value, mappedWeb, instruction, true);
        defAnalysisInfo.removeFromEvicted(value, mappedWeb);
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
            analyseDefinition(value, instruction, mappedWeb, defAnalysisSets);
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

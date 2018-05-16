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
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.saraverify.DefAnalysisSets.Triple;

import jdk.vm.ci.meta.Value;

public class DefAnalysis {

    public static DefAnalysisResult analyse(LIR lir, Map<Node, DuSequenceWeb> mapping) {
        AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();

        // the map stores the sets after the analysis of the particular block/instruction
        Map<AbstractBlockBase<?>, DefAnalysisSets> blockSets = new HashMap<>();

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

            DefAnalysisSets mergedDefAnalysisSets = mergeDefAnalysisSets(blockSets, block.getPredecessors());
            computeLocalFlow(lir.getLIRforBlock(block), mergedDefAnalysisSets, mapping);
            DefAnalysisSets previousDefAnalysisSets = blockSets.get(block);

            if (!mergedDefAnalysisSets.equals(previousDefAnalysisSets)) {
                blockSets.put(block, mergedDefAnalysisSets);

                for (AbstractBlockBase<?> successor : block.getSuccessors()) {
                    blockQueue.set(successor.getId());
                }
            }
        }

        assert Arrays.stream(blocks).allMatch(block -> visited.contains(block)) : "Not all blocks were visited during the defAnalysis.";

        return new DefAnalysisResult();
    }

    private static void computeLocalFlow(ArrayList<LIRInstruction> instructions, DefAnalysisSets defAnalysisSets,
                    Map<Node, DuSequenceWeb> mapping) {

        DefAnalysisCopyValueConsumer copyValueConsumer = new DefAnalysisCopyValueConsumer(defAnalysisSets, mapping);
        DefAnalysisNonCopyValueConsumer nonCopyValueConsumer = new DefAnalysisNonCopyValueConsumer(defAnalysisSets, mapping);

        for (LIRInstruction instruction : instructions) {
            if (instruction.destroysCallerSavedRegisters()) {
                // TODO
            }
        }
    }

    private static <T> DefAnalysisSets mergeDefAnalysisSets(Map<T, DefAnalysisSets> map, T[] mergeKeys) {
        List<DefAnalysisSets> defAnalysisSets = new ArrayList<>();

        for (T key : mergeKeys) {
            defAnalysisSets.add(map.get(key));
        }

        Set<Triple> locationIntersection = DefAnalysisSets.locationIntersection(defAnalysisSets);
        Set<Triple> staleUnion = DefAnalysisSets.staleUnion(defAnalysisSets);
        Set<Triple> evicted = DefAnalysisSets.evictedUnion(defAnalysisSets);

        Set<Triple> locationInconsistent = DefAnalysisSets  //
                        .locationUnionStream(defAnalysisSets)   //
                        .filter(triple -> !DefAnalysisSets.containsTriple(triple, locationIntersection))    //
                        .collect(Collectors.toSet());

        Set<Triple> stale = staleUnion.stream() //
                        .filter(triple -> !DefAnalysisSets.containsTriple(triple, locationInconsistent))    //
                        .collect(Collectors.toSet());

        evicted.addAll(locationInconsistent);

        return new DefAnalysisSets(locationIntersection, stale, evicted);
    }

    static class DefAnalysisCopyValueConsumer implements InstructionValueConsumer {

        private DefAnalysisSets defAnalysisSets;
        private Map<Node, DuSequenceWeb> mapping;

        public DefAnalysisCopyValueConsumer(DefAnalysisSets defAnalysisSets, Map<Node, DuSequenceWeb> mapping) {
            this.defAnalysisSets = defAnalysisSets;
            this.mapping = mapping;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            // TODO Auto-generated method stub

        }

    }

    static class DefAnalysisNonCopyValueConsumer implements InstructionValueConsumer {

        private DefAnalysisSets defAnalysisSets;
        private Map<Node, DuSequenceWeb> mapping;

        public DefAnalysisNonCopyValueConsumer(DefAnalysisSets defAnalysisSets, Map<Node, DuSequenceWeb> mapping) {
            this.defAnalysisSets = defAnalysisSets;
            this.mapping = mapping;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            // TODO Auto-generated method stub

        }

    }

}

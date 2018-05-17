package org.graalvm.compiler.lir.saraverify;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.lir.ConstantValue;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.StandardOp.LoadConstantOp;
import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyConstDef;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyRegDef;
import org.graalvm.compiler.lir.phases.LIRPhase;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class VerificationPhase extends LIRPhase<AllocationContext> {

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        assert UniqueInstructionVerifier.verify(lirGenRes);

        AnalysisResult inputResult = context.contextLookup(AnalysisResult.class);

        if (inputResult == null) {
            // no input du-sequences were created by the RegisterAllocationVerificationPhase
            return;
        }

        LIR lir = lirGenRes.getLIR();
        List<DuSequenceWeb> inputDuSequenceWebs = DuSequenceAnalysis.createDuSequenceWebs(inputResult.getDuSequences());
        Map<Node, DuSequenceWeb> mapping = generateMapping(lir, inputDuSequenceWebs, inputResult.getDummyRegDefs(), inputResult.getDummyConstDefs());
        DefAnalysisResult defAnalysisResult = DefAnalysis.analyse(lir, mapping, lirGenRes.getRegisterConfig().getCallerSaveRegisters());
    }

    private static Map<Node, DuSequenceWeb> generateMapping(LIR lir, List<DuSequenceWeb> webs, Map<Register, DummyRegDef> dummyRegDefs, Map<Constant, DummyConstDef> dummyConstDefs) {
        Map<Node, DuSequenceWeb> map = new HashMap<>();

        MappingDefInstructionValueConsumer defConsumer = new MappingDefInstructionValueConsumer(map, webs);
        MappingUseInstructionValueConsumer useConsumer = new MappingUseInstructionValueConsumer(map, webs);

        for (AbstractBlockBase<?> block : lir.getControlFlowGraph().getBlocks()) {
            for (LIRInstruction instruction : lir.getLIRforBlock(block)) {
                defConsumer.defOperandPosition = 0;
                useConsumer.useOperandPosition = 0;

                if (instruction.isLoadConstantOp()) {
                    LoadConstantOp loadConstantOp = (LoadConstantOp) instruction;
                    Constant constant = loadConstantOp.getConstant();
                    DummyConstDef dummyConstDef = dummyConstDefs.get(constant);

                    insertDefMapping(new ConstantValue(ValueKind.Illegal, constant), dummyConstDef, 0, webs, map);
                } else if (!instruction.isValueMoveOp()) {
                    SARAVerifyUtil.visitValues(instruction, defConsumer, useConsumer);
                }
            }
        }

        // generate mappings for dummy register definitions
        for (Entry<Register, DummyRegDef> dummyRegDef : dummyRegDefs.entrySet()) {
            insertDefMapping(dummyRegDef.getKey().asValue(), dummyRegDef.getValue(), 0, webs, map);
        }

        // TODO: move assert to method
        assert webs.stream()        //
                        .flatMap(web -> web.getDefNodes().stream())     //
                        .allMatch(node -> map.keySet().stream()     //
                                        .anyMatch(keyNode -> {
                                            if (!keyNode.isDefNode()) {
                                                return false;
                                            }
                                            DefNode defNode = (DefNode) keyNode;
                                            return node.equalsInstructionAndPosition(defNode);
                                        })) : "unmapped definitions";

        assert webs.stream()        //
                        .flatMap(web -> web.getUseNodes().stream())     //
                        .allMatch(node -> map.keySet().stream()     //
                                        .anyMatch(keyNode -> {
                                            if (!keyNode.isUseNode()) {
                                                return false;
                                            }
                                            UseNode useNode = (UseNode) keyNode;
                                            return node.equalsInstructionAndPosition(useNode);
                                        })) : "unmapped usages";

        return map;
    }

    private static void insertDefMapping(Value value, LIRInstruction instruction, int defOperandPosition, List<DuSequenceWeb> webs, Map<Node, DuSequenceWeb> map) {
        DefNode defNode = new DefNode(value, instruction, defOperandPosition);

        Optional<DuSequenceWeb> webOptional = webs.stream()     //
                        .filter(web -> web.getDefNodes().stream().anyMatch(node -> node.equalsInstructionAndPosition(defNode)))      //
                        .findAny();

        if (webOptional.isPresent()) {
            map.put(defNode, webOptional.get());
        }
    }

    static class MappingDefInstructionValueConsumer implements InstructionValueConsumer {

        private int defOperandPosition = 0;
        private Map<Node, DuSequenceWeb> map;
        private List<DuSequenceWeb> webs;

        public MappingDefInstructionValueConsumer(Map<Node, DuSequenceWeb> map, List<DuSequenceWeb> webs) {
            this.map = map;
            this.webs = webs;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            if (ValueUtil.isIllegal(value)) {
                // value is part of a composite value
                defOperandPosition++;
                return;
            }

            insertDefMapping(value, instruction, defOperandPosition, webs, map);

            defOperandPosition++;
        }

    }

    static class MappingUseInstructionValueConsumer implements InstructionValueConsumer {

        private int useOperandPosition = 0;
        private Map<Node, DuSequenceWeb> map;
        private List<DuSequenceWeb> webs;

        public MappingUseInstructionValueConsumer(Map<Node, DuSequenceWeb> map, List<DuSequenceWeb> webs) {
            this.map = map;
            this.webs = webs;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            if (ValueUtil.isIllegal(value) || LIRValueUtil.isConstantValue(value) || flags.contains(OperandFlag.UNINITIALIZED)) {
                // value is part of a composite value, uninitialized or a constant
                useOperandPosition++;
                return;
            }

            UseNode useNode = new UseNode(value, instruction, useOperandPosition);
            Optional<DuSequenceWeb> webOptional = webs.stream()     //
                            .filter(web -> web.getUseNodes().stream().anyMatch(node -> node.equalsInstructionAndPosition(useNode)))      //
                            .findAny();

            if (webOptional.isPresent()) {
                map.put(useNode, webOptional.get());
            }

            useOperandPosition++;
        }

    }
}

package org.graalvm.compiler.lir.saraverify;

import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.core.common.cfg.AbstractControlFlowGraph;
import org.graalvm.compiler.core.common.cfg.BlockMap;
import org.graalvm.compiler.debug.CounterKey;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.ConstantValue;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.StandardOp.JumpOp;
import org.graalvm.compiler.lir.StandardOp.LoadConstantOp;
import org.graalvm.compiler.lir.StandardOp.ValueMoveOp;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.ssa.SSAUtil;
import org.graalvm.compiler.lir.ssa.SSAUtil.PhiValueVisitor;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterAttributes;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;

public class DuSequenceAnalysis {

    public static class DummyConstDef extends LIRInstruction {
        public static final LIRInstructionClass<DummyConstDef> TYPE = LIRInstructionClass.create(DummyConstDef.class);
        protected Constant constant;

        public DummyConstDef(Constant constant) {
            super(TYPE);
            this.constant = constant;
        }

        public Constant getValue() {
            return constant;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }

    }

    public static class DummyRegDef extends LIRInstruction {
        public static final LIRInstructionClass<DummyRegDef> TYPE = LIRInstructionClass.create(DummyRegDef.class);
        @Def({REG}) protected AllocatableValue value;

        public DummyRegDef(AllocatableValue value) {
            super(TYPE);
            this.value = value;
        }

        public void setValue(AllocatableValue value) {
            this.value = value;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }
    }

    private int defOperandPosition;
    private int useOperandPosition;

    private Map<LIRInstruction, Integer> instructionDefOperandCount;
    private Map<LIRInstruction, Integer> instructionUseOperandCount;

    private Set<Node> nodes;

    public static final String ERROR_MSG_PREFIX = "SARA verify error: ";

    public static final CounterKey skippedCompilationUnits = DebugContext.counter("SARAVerify[skipped]");
    public static final CounterKey executedCompilationUnits = DebugContext.counter("SARAVerify[executed]");

    public AnalysisResult determineDuSequences(LIRGenerationResult lirGenRes, RegisterAttributes[] registerAttributes, Map<Register, DummyRegDef> dummyRegDefs,
                    Map<Constant, DummyConstDef> dummyConstDefs) {
        LIR lir = lirGenRes.getLIR();
        AbstractControlFlowGraph<?> controlFlowGraph = lir.getControlFlowGraph();
        AbstractBlockBase<?>[] blocks = controlFlowGraph.getBlocks();
        AbstractBlockBase<?> startBlock = controlFlowGraph.getStartBlock();

        int blockCount = blocks.length;
        BitSet blockQueue = new BitSet(blockCount);

        Map<Value, Set<DefNode>> duSequences = new ValueHashMap<>();
        Map<AbstractBlockBase<?>, Map<Value, Set<Node>>> blockUnfinishedDuSequences = new HashMap<>();

        BlockMap<List<Value>> blockPhiOutValues = new BlockMap<>(controlFlowGraph);
        BlockMap<List<Value>> blockPhiInValues = new BlockMap<>(controlFlowGraph);

        initializeCollections();

        blockQueue.set(0, blockCount);

        Set<AbstractBlockBase<?>> visited = new HashSet<>();

        while (!blockQueue.isEmpty()) {
            int blockIndex = blockQueue.previousSetBit(blockCount);
            AbstractBlockBase<?> block = blocks[blockIndex];
            blockQueue.clear(blockIndex);
            visited.add(block);

            Map<Value, Set<Node>> mergedUnfinishedDuSequences = SARAVerifyUtil.mergeMaps(blockUnfinishedDuSequences, block.getSuccessors());
            determineDuSequences(lir, block, lir.getLIRforBlock(block), duSequences, mergedUnfinishedDuSequences, blockPhiOutValues, blockPhiInValues);

            Map<Value, Set<Node>> previousUnfinishedDuSequences = blockUnfinishedDuSequences.get(block);

            if (!mergedUnfinishedDuSequences.equals(previousUnfinishedDuSequences)) {
                blockUnfinishedDuSequences.put(block, mergedUnfinishedDuSequences);

                for (AbstractBlockBase<?> predecessor : block.getPredecessors()) {
                    blockQueue.set(predecessor.getId());
                }
            }
        }

        assert Arrays.stream(blocks).allMatch(block -> visited.contains(block)) : "Not all blocks were visited during the Du-Sequence analysis.";

        Map<Value, Set<Node>> startBlockUnfinishedDuSequences = blockUnfinishedDuSequences.get(startBlock);
        analyseUndefinedValues(duSequences, startBlockUnfinishedDuSequences, registerAttributes, dummyRegDefs, dummyConstDefs);

        return new AnalysisResult(duSequences, instructionDefOperandCount, instructionUseOperandCount, dummyRegDefs, dummyConstDefs, blockPhiOutValues, blockPhiInValues);
    }

    public AnalysisResult determineDuSequences(List<LIRInstruction> instructions, RegisterAttributes[] registerAttributes, Map<Register, DummyRegDef> dummyRegDefs,
                    Map<Constant, DummyConstDef> dummyConstDefs) {
        initializeCollections();

        // analysis of given instructions
        Map<Value, Set<DefNode>> duSequences = new ValueHashMap<>();
        Map<Value, Set<Node>> unfinishedDuSequences = new ValueHashMap<>();
        determineDuSequences(null, null, instructions, duSequences, unfinishedDuSequences, null, null);

        // analysis of dummy instructions
        analyseUndefinedValues(duSequences, unfinishedDuSequences, registerAttributes,
                        dummyRegDefs, dummyConstDefs);

        return new AnalysisResult(duSequences, instructionDefOperandCount, instructionUseOperandCount, dummyRegDefs, dummyConstDefs, null, null);
    }

    private void determineDuSequences(LIR lir, AbstractBlockBase<?> block, List<LIRInstruction> instructions, Map<Value, Set<DefNode>> duSequences,
                    Map<Value, Set<Node>> unfinishedDuSequences,
                    BlockMap<List<Value>> blockPhiOutValues, BlockMap<List<Value>> blockPhiInValues) {
        DefInstructionValueConsumer defConsumer = new DefInstructionValueConsumer(duSequences, unfinishedDuSequences);
        UseInstructionValueConsumer useConsumer = new UseInstructionValueConsumer(unfinishedDuSequences);

        List<LIRInstruction> reverseInstructions = new ArrayList<>(instructions);
        Collections.reverse(reverseInstructions);

        for (LIRInstruction inst : reverseInstructions) {
            defOperandPosition = 0;
            useOperandPosition = 0;

            if (inst instanceof JumpOp) {
                // jump
                JumpOp jumpInst = (JumpOp) inst;

                if (jumpInst.getPhiSize() > 0 && blockPhiOutValues.get(block) == null) {
                    List<Value> phiOutValues = new ArrayList<>();
                    blockPhiOutValues.put(block, phiOutValues);

                    assert block.getSuccessorCount() == 1 : "There are more than one successor block.";
                    AbstractBlockBase<?> successorBlock = block.getSuccessors()[0];
                    List<Value> phiInValues = blockPhiInValues.get(successorBlock);
                    if (phiInValues == null) {
                        phiInValues = new ArrayList<>();
                        blockPhiInValues.put(successorBlock, phiInValues);
                    }

                    // phi
                    PhiValueVisitor visitor = new SARAVerifyPhiOutValueVisitor(phiOutValues, phiInValues);
                    SSAUtil.forEachPhiValuePair(lir, successorBlock, block, visitor);

                    assert phiOutValues.size() == phiInValues.size() : "The number of phi values do not match.";
                }
            }

            // inserting move, definition and use nodes
            if (inst.isValueMoveOp()) {
                assert !inst.destroysCallerSavedRegisters() : "caller saved registers are not handled";

                // value move
                ValueMoveOp moveInst = (ValueMoveOp) inst;
                insertMoveNode(unfinishedDuSequences, moveInst.getResult(), moveInst.getInput(), inst);
            } else if (inst.isLoadConstantOp()) {
                assert !inst.destroysCallerSavedRegisters() : "caller saved registers are not handled";

                // load constant
                LoadConstantOp loadConstantOp = (LoadConstantOp) inst;
                insertMoveNode(unfinishedDuSequences, loadConstantOp.getResult(), SARAVerifyUtil.asConstantValue(loadConstantOp.getConstant()), inst);
            } else {
                SARAVerifyUtil.visitValues(inst, defConsumer, useConsumer);

                instructionDefOperandCount.put(inst, defOperandPosition);
                instructionUseOperandCount.put(inst, useOperandPosition);
            }
        }
    }

    private void initializeCollections() {
        instructionDefOperandCount = new IdentityHashMap<>();
        instructionUseOperandCount = new IdentityHashMap<>();
        nodes = new HashSet<>();
    }

    public static <KeyType, SetType> Set<SetType> getOrCreateSet(Map<KeyType, Set<SetType>> map, KeyType key) {
        Set<SetType> set = map.get(key);

        if (set == null) {
            set = new HashSet<>();
            map.put(key, set);
        }

        return set;
    }

    private static void analyseUndefinedValues(Map<Value, Set<DefNode>> duSequences, Map<Value, Set<Node>> unfinishedDuSequences,
                    RegisterAttributes[] registerAttributes, Map<Register, DummyRegDef> dummyRegDefs, Map<Constant, DummyConstDef> dummyConstDefs) {

        for (Entry<Value, Set<Node>> entry : unfinishedDuSequences.entrySet()) {
            Value value = entry.getKey();
            LIRInstruction dummyDef;

            if (LIRValueUtil.isConstantValue(value)) {
                // constant
                ConstantValue constantValue = LIRValueUtil.asConstantValue(value);
                Constant constant = constantValue.getConstant();
                DummyConstDef dummyConstDef = dummyConstDefs.get(constant);
                if (dummyConstDef == null) {
                    dummyConstDef = new DummyConstDef(constant);
                    dummyConstDefs.put(constant, dummyConstDef);
                }
                dummyDef = dummyConstDef;
            } else {
                if (!ValueUtil.isRegister(value)) {
                    // other than register or constant
                    GraalError.shouldNotReachHere(ERROR_MSG_PREFIX + "Used value " + value + " is not defined.");
                }

                // register
                Register register = ValueUtil.asRegister(value);
                if (registerAttributes[register.number].isAllocatable()) {
                    // register is allocatable
                    GraalError.shouldNotReachHere(ERROR_MSG_PREFIX + "Used register " + register + " is not defined.");
                }

                DummyRegDef dummyRegDef = dummyRegDefs.get(register);
                if (dummyRegDef == null) {
                    dummyRegDef = new DummyRegDef(register.asValue());
                    dummyRegDefs.put(register, dummyRegDef);
                }
                dummyDef = dummyRegDef;
            }

            DefNode dummyDefNode = new DefNode(value, dummyDef, 0);
            dummyDefNode.addAllNextNodes(entry.getValue());
            duSequences.put(value, Stream.of(dummyDefNode).collect(Collectors.toSet()));
        }

        unfinishedDuSequences.clear();
    }

    private void insertDefNode(LIRInstruction instruction, Value value, Map<Value, Set<DefNode>> duSequences, Map<Value, Set<Node>> unfinishedDuSequences) {
        if (ValueUtil.isIllegal(value)) {
            // value is part of a composite value
            defOperandPosition++;
            return;
        }

        Set<Node> unfinishedNodes = unfinishedDuSequences.get(value);
        if (unfinishedNodes == null) {
            // definition of a value, which is not used or usage is not present yet (loops)
            defOperandPosition++;
            return;
        }

        AllocatableValue allocatableValue = ValueUtil.asAllocatableValue(value);

        assert unfinishedNodes.stream().allMatch(node -> !node.isDefNode()) : "finished du-sequence in unfinished du-sequences map";

        DefNode defNode = new DefNode(allocatableValue, instruction, defOperandPosition);
        Optional<Node> optionalDefNode = nodes.stream().filter(node -> node.equals(defNode)).findFirst();

        if (!optionalDefNode.isPresent()) {
            Set<DefNode> defNodes = getOrCreateSet(duSequences, value);
            defNodes.add(defNode);
            defNode.addAllNextNodes(unfinishedNodes);
            nodes.add(defNode);
        } else {
            optionalDefNode.get().addAllNextNodes(unfinishedNodes);
        }

        unfinishedDuSequences.remove(value);

        defOperandPosition++;
    }

    private void insertMoveNode(Map<Value, Set<Node>> unfinishedDuSequences, Value result, Value input, LIRInstruction instruction) {
        Set<Node> resultNodes = unfinishedDuSequences.get(result);
        unfinishedDuSequences.remove(result);

        defOperandPosition = 1;
        useOperandPosition = 1;

        if (resultNodes == null) {
            // no usage for copied value
            return;
        }

        assert resultNodes.stream().allMatch(node -> !node.isDefNode()) : "unfinished du-sequence with definition node";

        MoveNode moveNode = new MoveNode(result, input, instruction, 0, 0);
        Optional<Node> optionalMoveNode = nodes.stream().filter(node -> node.equals(moveNode)).findFirst();

        Set<Node> inputNodes = getOrCreateSet(unfinishedDuSequences, input);

        if (!optionalMoveNode.isPresent()) {
            nodes.add(moveNode);
            moveNode.addAllNextNodes(resultNodes);
            inputNodes.add(moveNode);
        } else {
            Node node = optionalMoveNode.get();
            node.addAllNextNodes(resultNodes);
            inputNodes.add(node);
        }
    }

    private void insertUseNode(LIRInstruction instruction, Value value, Map<Value, Set<Node>> unfinishedDuSequences) {
        if (ValueUtil.isIllegal(value)) {
            // value is part of a composite value or uninitialized
            useOperandPosition++;
            return;
        }

        Set<Node> nodesList = getOrCreateSet(unfinishedDuSequences, value);

        UseNode useNode = new UseNode(value, instruction, useOperandPosition);
        Optional<Node> optionalUseNode = nodes.stream().filter(node -> node.equals(useNode)).findFirst();

        if (!optionalUseNode.isPresent()) {
            nodes.add(useNode);
            nodesList.add(useNode);
        } else {
            nodesList.add(optionalUseNode.get());
        }

        useOperandPosition++;
    }

    class DefInstructionValueConsumer implements InstructionValueConsumer {

        private final Map<Value, Set<DefNode>> duSequences;
        private final Map<Value, Set<Node>> unfinishedDuSequences;

        public DefInstructionValueConsumer(Map<Value, Set<DefNode>> duSequences, Map<Value, Set<Node>> unfinishedDuSequences) {
            this.duSequences = duSequences;
            this.unfinishedDuSequences = unfinishedDuSequences;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            insertDefNode(instruction, value, duSequences, unfinishedDuSequences);
        }

    }

    class UseInstructionValueConsumer implements InstructionValueConsumer {

        private final Map<Value, Set<Node>> unfinishedDuSequences;

        public UseInstructionValueConsumer(Map<Value, Set<Node>> unfinishedDuSequences) {
            this.unfinishedDuSequences = unfinishedDuSequences;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            if (flags.contains(OperandFlag.UNINITIALIZED)) {
                useOperandPosition++;
                return;
            }
            insertUseNode(instruction, value, unfinishedDuSequences);
        }

    }

    class SARAVerifyPhiOutValueVisitor implements PhiValueVisitor {

        private int operandPos;
        private final List<Value> phiOutValues;
        private final List<Value> phiInValues;

        public SARAVerifyPhiOutValueVisitor(List<Value> phiOutValues, List<Value> phiInValues) {
            operandPos = 0;
            this.phiOutValues = phiOutValues;
            this.phiInValues = phiInValues;
        }

        @Override
        public void visit(Value phiIn, Value phiOut) {
            phiOutValues.add(phiOut);

            if (phiInValues.size() <= operandPos) {
                phiInValues.add(phiIn);
            }

            operandPos++;
        }
    }

    public static List<DuSequenceWeb> createDuSequenceWebs(Map<Value, Set<DefNode>> nodes) {
        List<DuSequenceWeb> duSequenceWebs = new ArrayList<>();
        Map<Node, DuSequenceWeb> nodeDuSequenceWeb = new HashMap<>();

        for (Set<DefNode> nodeList : nodes.values()) {
            for (DefNode node : nodeList) {
                List<Node> visitedNodes = new ArrayList<>();

                DuSequenceWeb web = createDuSequenceWeb(node, nodeDuSequenceWeb, visitedNodes, duSequenceWebs);

                if (web == null) {
                    web = new DuSequenceWeb();
                    duSequenceWebs.add(web);
                }
                web.addNodes(visitedNodes);

                if (!duSequenceWebs.contains(web)) {
                    duSequenceWebs.add(web);
                }

                final DuSequenceWeb finalWeb = web;

                Set<DefNode> defNodes = web.getDefNodes();
                defNodes.stream().forEach(defNode -> nodeDuSequenceWeb.put(defNode, finalWeb));

                Set<MoveNode> moveNodes = web.getMoveNodes();
                moveNodes.stream().forEach(moveNode -> nodeDuSequenceWeb.put(moveNode, finalWeb));

                Set<UseNode> useNodes = web.getUseNodes();
                useNodes.stream().forEach(useNode -> nodeDuSequenceWeb.put(useNode, finalWeb));
            }
        }

        return duSequenceWebs;
    }

    private static DuSequenceWeb createDuSequenceWeb(Node node, Map<Node, DuSequenceWeb> nodeDuSequenceWeb, List<Node> visitedNodes, List<DuSequenceWeb> duSequenceWebs) {
        if (nodeDuSequenceWeb.containsKey(node) || visitedNodes.contains(node)) {
            return nodeDuSequenceWeb.get(node);
        }

        visitedNodes.add(node);

        return node.getNextNodes().stream()        //
                        .map(nextNode -> createDuSequenceWeb(nextNode, nodeDuSequenceWeb, visitedNodes, duSequenceWebs))           //
                        .filter(web -> web != null)                     //
                        .reduce(null, (web1, web2) -> mergeDuSequenceWebs(web1, web2, duSequenceWebs));
    }

    private static DuSequenceWeb mergeDuSequenceWebs(DuSequenceWeb web1, DuSequenceWeb web2, List<DuSequenceWeb> duSequenceWebs) {
        assert web2 != null;

        if (web1 == null || web1 == web2) {
            return web2;
        }

        duSequenceWebs.remove(web2);

        web1.addDefNodes(web2.getDefNodes());
        web1.addMoveNodes(web2.getMoveNodes());
        web1.addUseNodes(web2.getUseNodes());

        return web1;
    }
}
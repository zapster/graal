package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyConstDef;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyRegDef;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;

public class VerificationPhase extends LIRPhase<AllocationContext> {

    private final String DEBUG_SCOPE = "SARAVerifyVerificationPhase";

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        AnalysisResult inputResult = context.contextLookup(AnalysisResult.class);

        if (inputResult == null) {
            // no input du-sequences were created by the RegisterAllocationVerificationPhase
            return;
        }

        Map<Value, List<DefNode>> inputDuSequences = inputResult.getDuSequences();
        Map<Register, DummyRegDef> inputDummyRegDefs = inputResult.getDummyRegDefs();
        Map<Constant, DummyConstDef> inputDummyConstDefs = inputResult.getDummyConstDefs();

        LIR lir = lirGenRes.getLIR();
        DebugContext debugContext = lir.getDebug();

        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();
        AnalysisResult outputResult = duSequenceAnalysis.determineDuSequences(lirGenRes, context.registerAllocationConfig.getRegisterConfig().getAttributesMap(), inputDummyRegDefs,
                        inputDummyConstDefs);

        Map<Value, List<DefNode>> outputDuSequences = outputResult.getDuSequences();

        if (!verifyDataFlow(inputDuSequences, outputDuSequences, debugContext)) {
            throw GraalError.shouldNotReachHere(DuSequenceAnalysis.ERROR_MSG_PREFIX + "Data Flow not equal");
        }

        if (!verifyOperandCount(inputResult.getInstructionDefOperandCount(),
                        inputResult.getInstructionUseOperandCount(),
                        outputResult.getInstructionDefOperandCount(), outputResult.getInstructionUseOperandCount())) {
            throw GraalError.shouldNotReachHere(DuSequenceAnalysis.ERROR_MSG_PREFIX + "Operand numbers not equal");
        }
    }

    public boolean verifyDataFlow(Map<Value, List<DefNode>> inputDuSequences, Map<Value, List<DefNode>> outputDuSequences, DebugContext debugContext) {
        List<DuSequenceWeb> inputDuSequenceWebs = createDuSequenceWebs(inputDuSequences);
        List<DuSequenceWeb> outputDuSequenceWebs = createDuSequenceWebs(outputDuSequences);

        for (DuSequenceWeb inputDuSequenceWeb : inputDuSequenceWebs) {
            if (!outputDuSequenceWebs.stream().anyMatch(outputDuSequenceWeb -> verifyDuSequenceWebs(inputDuSequenceWeb, outputDuSequenceWeb))) {
                return false;
            }
        }

        return true;
    }

    private static boolean verifyDuSequenceWebs(DuSequenceWeb web1, DuSequenceWeb web2) {
        Set<DefNode> defNodes1 = web1.getDefNodes();
        Set<DefNode> defNodes2 = web2.getDefNodes();
        Set<UseNode> useNodes1 = web1.getUseNodes();
        Set<UseNode> useNodes2 = web2.getUseNodes();

        for (DefNode defNode : defNodes1) {
            if (!defNodes2.stream().anyMatch(node -> node.verify(defNode))) {
                return false;
            }
        }

        for (UseNode useNode : useNodes1) {
            if (!useNodes2.stream().anyMatch(node -> node.verify(useNode))) {
                return false;
            }
        }

        return true;
    }

    public static boolean verifyOperandCount(Map<LIRInstruction, Integer> inputInstructionDefOperandCount,
                    Map<LIRInstruction, Integer> inputInstructionUseOperandCount,
                    Map<LIRInstruction, Integer> outputInstructionDefOperandCount,
                    Map<LIRInstruction, Integer> outputInstructionUseOperandCount) {

        for (Entry<LIRInstruction, Integer> entry : inputInstructionDefOperandCount.entrySet()) {
            Integer count = outputInstructionDefOperandCount.get(entry.getKey());

            if (count != null && count.compareTo(entry.getValue()) != 0) {
                return false;
            }
        }

        for (Entry<LIRInstruction, Integer> entry : inputInstructionUseOperandCount.entrySet()) {
            Integer count = outputInstructionUseOperandCount.get(entry.getKey());

            if (count != null && count.compareTo(entry.getValue()) != 0) {
                return false;
            }
        }

        return true;
    }

    public List<DuSequenceWeb> createDuSequenceWebs(Map<Value, List<DefNode>> nodes) {
        List<DuSequenceWeb> duSequenceWebs = new ArrayList<>();
        Map<Node, DuSequenceWeb> nodeDuSequenceWeb = new HashMap<>();

        for (List<DefNode> nodeList : nodes.values()) {
            for (DefNode node : nodeList) {
                List<Node> visitedNodes = new ArrayList<>();
                DuSequenceWeb web = createDuSequenceWeb(node, nodeDuSequenceWeb, visitedNodes);

                if (web == null) {
                    web = new DuSequenceWeb();
                    duSequenceWebs.add(web);
                }
                web.addNodes(visitedNodes);

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

    private DuSequenceWeb createDuSequenceWeb(Node node, Map<Node, DuSequenceWeb> nodeDuSequenceWeb, List<Node> visitedNodes) {
        if (nodeDuSequenceWeb.containsKey(node)) {
            return nodeDuSequenceWeb.get(node);
        }

        visitedNodes.add(node);

        List<DuSequenceWeb> nextNodeWebs = node.getNextNodes()   //
                        .stream()                       //
                        .map(nextNode -> createDuSequenceWeb(nextNode, nodeDuSequenceWeb, visitedNodes)) //
                        .filter(web -> web != null)             //
                        .collect(Collectors.toList());

        return mergeDuSequenceWebs(nextNodeWebs);
    }

    private static DuSequenceWeb mergeDuSequenceWebs(List<DuSequenceWeb> duSequenceWebs) {
        if (duSequenceWebs.isEmpty()) {
            return null;
        }

        DuSequenceWeb duSequenceWeb = duSequenceWebs.get(0);

        for (DuSequenceWeb web : duSequenceWebs) {
            duSequenceWeb.addDefNodes(web.getDefNodes());
            duSequenceWeb.addMoveNodes(web.getMoveNodes());
            duSequenceWeb.addUseNodes(web.getUseNodes());
        }

        return duSequenceWeb;
    }

}

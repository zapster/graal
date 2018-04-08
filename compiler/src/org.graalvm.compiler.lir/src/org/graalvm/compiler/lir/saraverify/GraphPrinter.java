package org.graalvm.compiler.lir.saraverify;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.VirtualStackSlot;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.Value;

public class GraphPrinter {

    public static void printGraphs(Map<Value, Set<DefNode>> inputDuSequences, List<DuSequenceWeb> inputDuSequenceWebs,
                    Map<Value, Set<DefNode>> outputDuSequences, List<DuSequenceWeb> outputDuSequenceWebs, DebugContext debugContext) {

        String dirName = Long.toString(System.currentTimeMillis()) + "_" + debugContext.getDescription().toString();
        Path dir = FileSystems.getDefault().getPath("SARAVerifyGraphs").resolve(dirName);

        // print input graphs
        Path inputDir = dir.resolve("input");
        createDirectory(inputDir);
        printDuSequences(inputDuSequences, inputDir);
        printDuSequenceWebs(inputDuSequenceWebs, inputDir);

        // print output graphs
        Path outputDir = dir.resolve("output");
        createDirectory(outputDir);
        printDuSequences(outputDuSequences, outputDir);
        printDuSequenceWebs(outputDuSequenceWebs, outputDir);
    }

    private static void printDuSequences(Map<Value, Set<DefNode>> duSequences, Path dir) {

        for (Entry<Value, Set<DefNode>> entry : duSequences.entrySet()) {
            Path file = dir.resolve("DS_" + entry.getKey() + ".gv");

            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write("digraph finite_state_machine {\n" +           //
                                "    graph [ " + "fontname = \"Helvetica-Oblique\",\n" +        //
                                " fontsize = 18,\n" +                          //
                                "label=\"\\n\\n\\nDu Sequence of value " + entry.getKey() + "\" ];\n" +         //
                                "    node [shape = rectangle];\n");

                HashSet<Node> visited = new HashSet<>();

                for (Node node : entry.getValue()) {
                    printDuSequence(node, visited, writer);
                }

                assert visited.size() == visited.stream().mapToInt(node -> node.hashCode()).distinct().count()  //
                : "Hashcode collision of visited nodes.";

                printNodeLabels(visited, writer);

                writer.write("}\n");
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    private static void printDuSequence(Node node, Set<Node> visited, BufferedWriter writer) throws IOException {
        if (visited.contains(node)) {
            return;
        }

        visited.add(node);

        int nodeHashCode = node.hashCode();

        for (Node nextNode : node.getNextNodes()) {
            writer.write("\"n" + nodeHashCode + "\" -> \"n" + nextNode.hashCode() + "\";\n");
            printDuSequence(nextNode, visited, writer);
        }
    }

    private static void printNodeLabels(Set<? extends Node> nodes, BufferedWriter writer) throws IOException {
        for (Node node : nodes) {
            writer.write("\"n" + node.hashCode() + "\" [ label = \"" + getNodeLabel(node) + "\" ];\n");
        }
    }

    private static String getNodeLabel(Node node) {
        String nodeLabel = "";

        if (node.isDefNode()) {
            DefNode defNode = (DefNode) node;
            nodeLabel = "Def:" + defNode.getDefOperandPosition() + ": " + getValueLabel(defNode.getValue());
        } else if (node.isUseNode()) {
            UseNode useNode = (UseNode) node;
            nodeLabel = "Use:" + useNode.getUseOperandPosition() + ": " + getValueLabel(useNode.getValue());
        } else {
            MoveNode moveNode = (MoveNode) node;
            nodeLabel = "Move:" + moveNode.getResultOperandPosition() + ":" + moveNode.getInputOperandPosition() + ": "  //
                            + getValueLabel(moveNode.getResult()) + " = " + getValueLabel(moveNode.getInput());
        }

        return nodeLabel + "\\n" + node.getInstruction().name() + " (" + System.identityHashCode(node.getInstruction()) + ")";
    }

    private static String getValueLabel(Value value) {
        if (ValueUtil.isRegister(value)) {
            Register register = ValueUtil.asRegister(value);
            return register.name;
        }

        if (LIRValueUtil.isVariable(value)) {
            Variable variable = LIRValueUtil.asVariable(value);

            if (variable.getName() != null) {
                return variable.getName();
            } else {
                return "v" + variable.index;
            }
        }

        if (ValueUtil.isStackSlot(value)) {
            StackSlot stackSlot = ValueUtil.asStackSlot(value);
            int rawOffset = stackSlot.getRawOffset();

            if (!stackSlot.getRawAddFrameSize()) {
                return "out:" + rawOffset;
            } else if (rawOffset >= 0) {
                return "in:" + rawOffset;
            } else {
                return "stack:" + (-rawOffset);
            }
        }

        if (LIRValueUtil.isVirtualStackSlot(value)) {
            VirtualStackSlot virtualStackSlot = LIRValueUtil.asVirtualStackSlot(value);
            return "vstack:" + virtualStackSlot.getId();
        }

        return value.toString();
    }

    private static void printDuSequenceWebs(List<DuSequenceWeb> duSequenceWebs, Path dir) {
        int i = 0;

        for (DuSequenceWeb duSequenceWeb : duSequenceWebs) {
            Path file = dir.resolve("DSW_" + i + ".gv");

            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write("digraph finite_state_machine {\n" +
                                "    graph [ fontname = \"Helvetica-Oblique\",\n" +        //
                                " fontsize = 18,\n" +                           //
                                "label=\"\\n\\n\\nDu Sequence Web " + i + "\" ];\n" +       //
                                "    node [shape = rectangle];\n");

                printDuSequenceWeb(duSequenceWeb, writer);

                writer.write("}\n");
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

            i++;
        }
    }

    private static void printDuSequenceWeb(DuSequenceWeb duSequenceWeb, BufferedWriter writer) throws IOException {
        printNodeLabels(duSequenceWeb.getDefNodes(), writer);
        printNodeLabels(duSequenceWeb.getMoveNodes(), writer);
        printNodeLabels(duSequenceWeb.getUseNodes(), writer);

        for (DefNode node : duSequenceWeb.getDefNodes()) {
            int nodeHashCode = node.hashCode();
            for (Node nextNode : node.getNextNodes()) {
                writer.write("\"n" + nodeHashCode + "\" -> \"n" + nextNode.hashCode() + "\";\n");
            }
        }

        for (MoveNode node : duSequenceWeb.getMoveNodes()) {
            int nodeHashCode = node.hashCode();
            for (Node nextNode : node.getNextNodes()) {
                writer.write("\"n" + nodeHashCode + "\" -> \"n" + nextNode.hashCode() + "\";\n");
            }
        }

        for (UseNode node : duSequenceWeb.getUseNodes()) {
            int nodeHashCode = node.hashCode();
            for (Node nextNode : node.getNextNodes()) {
                writer.write("\"n" + nodeHashCode + "\" -> \"n" + nextNode.hashCode() + "\";\n");
            }
        }
    }

    private static void createDirectory(Path dir) {
        if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }
}

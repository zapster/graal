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

import jdk.vm.ci.meta.Value;

public class GraphPrinter {

    private static Path dir = FileSystems.getDefault().getPath("SARAVerifyGraphs");

    public static void printGraphs(Map<Value, Set<DefNode>> duSequences, List<DuSequenceWeb> duSequenceWebs) {
        if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        printDuSequences(duSequences);
        printDuSequenceWebs(duSequenceWebs);
    }

    private static void printDuSequences(Map<Value, Set<DefNode>> duSequences) {

        for (Entry<Value, Set<DefNode>> entry : duSequences.entrySet()) {
            Path file = dir.resolve("DS_" + entry.getKey() + ".gv");

            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write("digraph finite_state_machine {\n" +
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

    private static void printNodeLabels(Set<Node> nodes, BufferedWriter writer) throws IOException {
        for (Node node : nodes) {
            writer.write("\"n" + node.hashCode() + "\" [ label = \"" + getNodeLabel(node) + "\" ];\n");
        }
    }

    private static String getNodeLabel(Node node) {
        if (node.isDefNode()) {
            DefNode defNode = (DefNode) node;
            return "Def:" + defNode.getDefOperandPosition() + ": " + defNode.getValue() + " " + defNode.getInstruction().name();
        } else if (node.isUseNode()) {
            UseNode useNode = (UseNode) node;
            return "Use:" + useNode.getUseOperandPosition() + ": " + useNode.getValue() + " " + useNode.getInstruction().name();
        } else {
            MoveNode moveNode = (MoveNode) node;
            return "Move:" + moveNode.getResultOperandPosition() + ":" + moveNode.getInputOperandPosition() + ": "  //
                            + moveNode.getResult() + " = " + moveNode.getInput();
        }
    }

    private static void printDuSequenceWebs(List<DuSequenceWeb> duSequenceWebs) {

    }
}

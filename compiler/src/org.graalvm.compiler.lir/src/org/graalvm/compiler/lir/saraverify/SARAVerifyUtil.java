package org.graalvm.compiler.lir.saraverify;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIRInstruction;

import jdk.vm.ci.meta.Value;

public class SARAVerifyUtil {

    public static void visitValues(LIRInstruction instruction, InstructionValueConsumer defConsumer,
                    InstructionValueConsumer useConsumer) {

        instruction.visitEachAlive(useConsumer);
        instruction.visitEachState(useConsumer);
        instruction.visitEachOutput(defConsumer);

// // caller saved registers are handled like temp values
// if (instruction.destroysCallerSavedRegisters()) {
// callerSavedRegisters.forEach(register -> {
// RegisterValue registerValue = register.asValue();
// insertUseNode(instruction, registerValue, unfinishedDuSequences);
// insertDefNode(instruction, registerValue, duSequences, unfinishedDuSequences);
// });
// }
//
// instruction.visitEachTemp(useConsumer);
// instruction.visitEachTemp(defConsumer);
        instruction.visitEachInput(useConsumer);
    }

    public static <T, V> Map<Value, Set<V>> mergeMaps(Map<T, Map<Value, Set<V>>> map, T[] mergeKeys) {
        Map<Value, Set<V>> mergedMap = new ValueHashMap<>();

        for (T mergeKey : mergeKeys) {
            Map<Value, Set<V>> mergeValueMap = map.get(mergeKey);

            if (mergeValueMap != null) {
                for (Entry<Value, Set<V>> entry : mergeValueMap.entrySet()) {
                    Set<V> mergedMapValue = mergedMap.get(entry.getKey());

                    if (mergedMapValue == null) {
                        mergedMapValue = new HashSet<>();
                        mergedMap.put(entry.getKey(), mergedMapValue);
                    }

                    Set<V> newValues = entry.getValue().stream().filter(x -> !(mergedMap.get(entry.getKey()).contains(x))).collect(Collectors.toSet());
                    mergedMapValue.addAll(newValues);
                }
            }
        }
        return mergedMap;
    }
}

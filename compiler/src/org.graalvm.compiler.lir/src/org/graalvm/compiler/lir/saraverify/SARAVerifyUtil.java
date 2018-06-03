package org.graalvm.compiler.lir.saraverify;

import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.ConstantValue;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.VirtualStackSlot;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class SARAVerifyUtil {

    public static void visitValues(LIRInstruction instruction, InstructionValueConsumer defConsumer,
                    InstructionValueConsumer useConsumer) {

        instruction.visitEachAlive(useConsumer);
        instruction.visitEachState(useConsumer);

        if (defConsumer != null) {
            instruction.visitEachOutput(defConsumer);
        }

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

    /**
     * Returns the argument with illegal value kind.
     *
     * @return the argument with illegal value kind
     */
    public static Value getValueIllegalValueKind(Value value) {
        if (value.getValueKind().equals(ValueKind.Illegal)) {
            return value;
        }

        if (ValueUtil.isRegister(value)) {
            Register register = ValueUtil.asRegister(value);
            return register.asValue(ValueKind.Illegal);
        }

        if (LIRValueUtil.isVariable(value)) {
            Variable variable = LIRValueUtil.asVariable(value);
            return new Variable(ValueKind.Illegal, variable.index);
        }

        if (ValueUtil.isStackSlot(value)) {
            StackSlot stackSlot = ValueUtil.asStackSlot(value);
            return StackSlot.get(ValueKind.Illegal, stackSlot.getRawOffset(), stackSlot.getRawAddFrameSize());
        }

        if (LIRValueUtil.isVirtualStackSlot(value)) {
            VirtualStackSlot virtualStackSlot = LIRValueUtil.asVirtualStackSlot(value);
            return new VirtualStackSlot(virtualStackSlot.getId(), ValueKind.Illegal) {
            };
        }

        throw GraalError.shouldNotReachHere("Type " + value.getClass() + "not implemented.");
    }

    public static ConstantValue asConstantValue(Constant constant) {
        return new ConstantValue(ValueKind.Illegal, constant);
    }
}

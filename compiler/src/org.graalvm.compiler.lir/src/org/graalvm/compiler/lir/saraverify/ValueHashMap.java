package org.graalvm.compiler.lir.saraverify;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.ConstantValue;
import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.VirtualStackSlot;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class ValueHashMap<T> implements Map<Value, T> {

    private HashMap<RegisterValue, T> registerValueMap;
    private HashMap<Variable, T> variableMap;
    private HashMap<ConstantValue, T> constantValueMap;
    private HashMap<StackSlot, T> stackSlotMap;
    private HashMap<VirtualStackSlot, T> virtualStackSlotMap;

    public ValueHashMap() {
        registerValueMap = new HashMap<>();
        variableMap = new HashMap<>();
        constantValueMap = new HashMap<>();
        stackSlotMap = new HashMap<>();
        virtualStackSlotMap = new HashMap<>();
    }

    @Override
    public int size() {
        return registerValueMap.size() + variableMap.size() + constantValueMap.size() + stackSlotMap.size() + virtualStackSlotMap.size();
    }

    @Override
    public boolean isEmpty() {
        return registerValueMap.isEmpty() && variableMap.isEmpty() && constantValueMap.isEmpty() && stackSlotMap.isEmpty() && virtualStackSlotMap.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        if (!(key instanceof Value)) {
            return false;
        }

        Value keyValue = (Value) key;

        if (ValueUtil.isRegister(keyValue)) {
            return registerValueMap.containsKey(getRegisterValue(keyValue));
        }

        if (LIRValueUtil.isVariable(keyValue)) {
            return variableMap.containsKey(getVariable(keyValue));
        }

        if (LIRValueUtil.isConstantValue(keyValue)) {
            return constantValueMap.containsKey(getConstantValue(keyValue));
        }

        if (ValueUtil.isStackSlot(keyValue)) {
            return stackSlotMap.containsKey(getStackSlot(keyValue));
        }

        if (LIRValueUtil.isVirtualStackSlot(keyValue)) {
            return virtualStackSlotMap.containsKey(getVirtualStackSlot(keyValue));
        }

        throw GraalError.shouldNotReachHere("ValueHashMap not implemented for Type " + key.getClass());
    }

    @Override
    public boolean containsValue(Object value) {
        return registerValueMap.containsValue(value) || variableMap.containsValue(value) || constantValueMap.containsValue(value) || stackSlotMap.containsValue(value) ||
                        virtualStackSlotMap.containsValue(value);
    }

    @Override
    public T get(Object key) {
        if (!(key instanceof Value)) {
            return null;
        }

        Value keyValue = (Value) key;

        if (ValueUtil.isRegister(keyValue)) {
            return registerValueMap.get(getRegisterValue(keyValue));
        }

        if (LIRValueUtil.isVariable(keyValue)) {
            return variableMap.get(getVariable(keyValue));
        }

        if (LIRValueUtil.isConstantValue(keyValue)) {
            return constantValueMap.get(getConstantValue(keyValue));
        }

        if (ValueUtil.isStackSlot(keyValue)) {
            return stackSlotMap.get(getStackSlot(keyValue));
        }

        if (LIRValueUtil.isVirtualStackSlot(keyValue)) {
            return virtualStackSlotMap.get(getVirtualStackSlot(keyValue));
        }

        throw GraalError.shouldNotReachHere("ValueHashMap not implemented for Type " + key.getClass());
    }

    @Override
    public T put(Value key, T value) {
        if (ValueUtil.isRegister(key)) {
            return registerValueMap.put((RegisterValue) getRegisterValue(key), value);
        }

        if (LIRValueUtil.isVariable(key)) {
            return variableMap.put((Variable) getVariable(key), value);
        }

        if (LIRValueUtil.isConstantValue(key)) {
            return constantValueMap.put((ConstantValue) getConstantValue(key), value);
        }

        if (ValueUtil.isStackSlot(key)) {
            return stackSlotMap.put((StackSlot) getStackSlot(key), value);
        }

        if (LIRValueUtil.isVirtualStackSlot(key)) {
            return virtualStackSlotMap.put((VirtualStackSlot) getVirtualStackSlot(key), value);
        }

        throw GraalError.shouldNotReachHere("ValueHashMap not implemented for Type " + key.getClass());
    }

    @Override
    public T remove(Object key) {
        if (!(key instanceof Value)) {
            return null;
        }

        Value keyValue = (Value) key;

        if (ValueUtil.isRegister(keyValue)) {
            return registerValueMap.remove(getRegisterValue(keyValue));
        }

        if (LIRValueUtil.isVariable(keyValue)) {
            return variableMap.remove(getVariable(keyValue));
        }

        if (LIRValueUtil.isConstantValue(keyValue)) {
            return constantValueMap.remove(getConstantValue(keyValue));
        }

        if (ValueUtil.isStackSlot(keyValue)) {
            return stackSlotMap.remove(getStackSlot(keyValue));
        }

        if (LIRValueUtil.isVirtualStackSlot(keyValue)) {
            return virtualStackSlotMap.remove(getVirtualStackSlot(keyValue));
        }

        throw GraalError.shouldNotReachHere("ValueHashMap not implemented for Type " + key.getClass());
    }

    @Override
    public void putAll(Map<? extends Value, ? extends T> m) {
        for (Entry<? extends Value, ? extends T> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void clear() {
        registerValueMap.clear();
        variableMap.clear();
        constantValueMap.clear();
        stackSlotMap.clear();
        virtualStackSlotMap.clear();
    }

    @Override
    public Set<Value> keySet() {
        return Stream.of(registerValueMap.keySet(), variableMap.keySet(), constantValueMap.keySet(), stackSlotMap.keySet(), virtualStackSlotMap.keySet())   //
                        .flatMap(Collection::stream)    //
                        .collect(Collectors.toSet());
    }

    @Override
    public Collection<T> values() {
        return Stream.of(registerValueMap.values(), variableMap.values(), constantValueMap.values(), stackSlotMap.values(), virtualStackSlotMap.values())   //
                        .flatMap(Collection::stream)    //
                        .collect(Collectors.toList());
    }

    @Override
    public Set<Entry<Value, T>> entrySet() {

        Set<Entry<Value, T>> entrySet = new HashSet<>();

        for (Entry<RegisterValue, T> entry : registerValueMap.entrySet()) {
            entrySet.add(new ValueHashMapEntry<Value, T>(entry.getKey(), entry.getValue()));
        }

        for (Entry<Variable, T> entry : variableMap.entrySet()) {
            entrySet.add(new ValueHashMapEntry<Value, T>(entry.getKey(), entry.getValue()));
        }

        for (Entry<ConstantValue, T> entry : constantValueMap.entrySet()) {
            entrySet.add(new ValueHashMapEntry<Value, T>(entry.getKey(), entry.getValue()));
        }

        for (Entry<StackSlot, T> entry : stackSlotMap.entrySet()) {
            entrySet.add(new ValueHashMapEntry<Value, T>(entry.getKey(), entry.getValue()));
        }

        for (Entry<VirtualStackSlot, T> entry : virtualStackSlotMap.entrySet()) {
            entrySet.add(new ValueHashMapEntry<Value, T>(entry.getKey(), entry.getValue()));
        }

        return entrySet;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ValueHashMap) {
            ValueHashMap<T> valueHashMap = (ValueHashMap<T>) obj;
            return this.registerValueMap.equals(valueHashMap.registerValueMap) &&
                            this.variableMap.equals(valueHashMap.variableMap) &&
                            this.constantValueMap.equals(valueHashMap.constantValueMap) &&
                            this.stackSlotMap.equals(valueHashMap.stackSlotMap) &&
                            this.virtualStackSlotMap.equals(valueHashMap.virtualStackSlotMap);
        }

        return false;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + constantValueMap.hashCode();
        result = prime * result + registerValueMap.hashCode();
        result = prime * result + stackSlotMap.hashCode();
        result = prime * result + variableMap.hashCode();
        result = prime * result + virtualStackSlotMap.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return entrySet().stream().map(entry -> entry.toString()).collect(Collectors.joining(","));
    }

    private static Value getRegisterValue(Value value) {
        if (value.getValueKind().equals(ValueKind.Illegal)) {
            return value;
        }

        Register register = ValueUtil.asRegister(value);
        return register.asValue(ValueKind.Illegal);
    }

    private static Value getVariable(Value value) {
        if (value.getValueKind().equals(ValueKind.Illegal)) {
            return value;
        }

        Variable variable = LIRValueUtil.asVariable(value);
        return new Variable(ValueKind.Illegal, variable.index);
    }

    private static Value getConstantValue(Value value) {
        if (value.getValueKind().equals(ValueKind.Illegal)) {
            return value;
        }

        Constant constant = LIRValueUtil.asConstant(value);
        return new ConstantValue(ValueKind.Illegal, constant);
    }

    private static Value getStackSlot(Value value) {
        if (value.getValueKind().equals(ValueKind.Illegal)) {
            return value;
        }

        StackSlot stackSlot = ValueUtil.asStackSlot(value);
        return StackSlot.get(ValueKind.Illegal, stackSlot.getRawOffset(), stackSlot.getRawAddFrameSize());
    }

    private static Value getVirtualStackSlot(Value value) {
        if (value.getValueKind().equals(ValueKind.Illegal)) {
            return value;
        }

        VirtualStackSlot virtualStackSlot = LIRValueUtil.asVirtualStackSlot(value);
        return new VirtualStackSlot(virtualStackSlot.getId(), ValueKind.Illegal) {
        };
    }

    private class ValueHashMapEntry<K, V> implements Map.Entry<K, V> {

        private K key;
        private V value;

        public ValueHashMapEntry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public V setValue(V value) {
            throw GraalError.unimplemented("ValueHashMapEntry.setValue(V value) is not implemented.");
        }

        @Override
        public String toString() {
            return key.toString() + "=" + value.toString();
        }
    }
}

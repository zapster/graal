package org.graalvm.compiler.lir.saraverify;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.Variable;

import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class ValueHashMap<T> implements Map<Value, T> {

    private HashMap<Value, T> map;

    public ValueHashMap() {
        map = new HashMap<>();
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        if (key instanceof Value) {
            Value keyValue = (Value) key;
            return map.containsKey(getKey(keyValue));
        }

        return false;
    }

    @Override
    public boolean containsValue(Object value) {
        return map.containsValue(value);
    }

    @Override
    public T get(Object key) {
        if (key instanceof Value) {
            Value keyValue = (Value) key;
            return map.get(getKey(keyValue));
        }

        return null;
    }

    @Override
    public T put(Value key, T value) {
        return map.put(getKey(key), value);
    }

    @Override
    public T remove(Object key) {
        if (key instanceof Value) {
            Value keyValue = (Value) key;
            return map.remove(getKey(keyValue));
        }
        return null;
    }

    @Override
    public void putAll(Map<? extends Value, ? extends T> m) {
        for (Entry<? extends Value, ? extends T> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public Set<Value> keySet() {
        return map.keySet();
    }

    @Override
    public Collection<T> values() {
        return map.values();
    }

    @Override
    public Set<Entry<Value, T>> entrySet() {
        return map.entrySet();
    }

    private static Value getKey(Value key) {
        if (ValueUtil.isRegister(key)) {
            return ValueUtil.asRegister(key).asValue();
        }

        if (LIRValueUtil.isVariable(key)) {
            Variable variable = LIRValueUtil.asVariable(key);
            return new Variable(ValueKind.Illegal, variable.index);
        }

        return key;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ValueHashMap) {
            ValueHashMap<T> valueHashMap = (ValueHashMap<T>) obj;
            return this.map.equals(valueHashMap.map);
        }

        return false;
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }
}

package org.graalvm.compiler.lir.saraverify;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import jdk.vm.ci.meta.Value;

public class ValueHashMap<T> implements Map<Value, T> {

    private HashMap<Object, T> map;

    public ValueHashMap() {
        map = new HashMap<>();
    }

    @Override
    public int size() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public boolean isEmpty() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean containsKey(Object key) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean containsValue(Object value) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public T get(Object key) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public T put(Value key, T value) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public T remove(Object key) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void putAll(Map<? extends Value, ? extends T> m) {
        // TODO Auto-generated method stub

    }

    @Override
    public void clear() {
        // TODO Auto-generated method stub

    }

    @Override
    public Set<Value> keySet() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Collection<T> values() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Set<Entry<Value, T>> entrySet() {
        // TODO Auto-generated method stub
        return null;
    }

}

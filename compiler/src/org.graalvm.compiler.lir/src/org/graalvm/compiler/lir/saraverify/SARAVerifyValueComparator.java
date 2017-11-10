package org.graalvm.compiler.lir.saraverify;

import java.util.Comparator;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.Value;

public class SARAVerifyValueComparator implements Comparator<Value> {

    @Override
    public int compare(Value o1, Value o2) {
        if (ValueUtil.isRegister(o1) && ValueUtil.isRegister(o2)) {
            Register r1 = ValueUtil.asRegister(o1);
            Register r2 = ValueUtil.asRegister(o2);
            if (r1.number == r2.number) {
                assert r1.name.equals(r2.name);
                return 0;
            }
        }
        // TODO comparison for other value types
        return -1;
    }

}

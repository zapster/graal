package org.graalvm.compiler.lir.test;

import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.alloc.graphcoloring.Interval;
import org.graalvm.compiler.lir.alloc.graphcoloring.Interval.RegisterPriority;
import org.graalvm.compiler.lir.alloc.graphcoloring.LifeRange;
import org.junit.Assert;
import org.junit.Test;

public class GraphColoringIntervalTest {

    @Test
    public void testHasInterference() {
        Interval inter = new Interval(new Variable(LIRKind.Illegal, 0), 0);

        inter.addLiveRange(2, 24);
        inter.addLiveRange(26, 32);
        LifeRange range = new LifeRange(0, 20, 22, LifeRange.EndMarker);

        Assert.assertTrue(inter.hasInterference(range, false));
    }

    @Test
    public void testHasInterference1() {
        Interval inter = new Interval(new Variable(LIRKind.Illegal, 0), 0);

        inter.addLiveRange(20, 22);

        LifeRange range = new LifeRange(0, 2, 24, LifeRange.EndMarker);

        Assert.assertTrue(inter.hasInterference(range, false));
    }

    @Test
    public void testHasInterference2() {
        Interval inter = new Interval(new Variable(LIRKind.Illegal, 0), 0);

        inter.addLiveRange(20, 22);

        LifeRange range = new LifeRange(0, 26, 32, LifeRange.EndMarker);

        Assert.assertFalse(inter.hasInterference(range, false));
    }

    @Test
    public void testHasInterference3() {
        Interval inter = new Interval(new Variable(LIRKind.Illegal, 0), 0);

        inter.addLiveRange(32, 36);

        LifeRange range = new LifeRange(0, 36, 36, LifeRange.EndMarker);

        Assert.assertTrue(inter.hasInterference(range, false));
    }

    @Test
    public void testHasInterference4() {
        Interval inter = new Interval(new Variable(LIRKind.Illegal, 0), 0);

        // inter.addTempRange(6, 6);
// inter.addTempRange(18, 18);
// inter.addTempRange(24, 24);
        inter.addTempRange(32, 32);
// inter.addTempRange(34, 34);

        LifeRange range = new LifeRange(0, 30, 31, LifeRange.EndMarker);

        Assert.assertFalse(inter.hasInterference(range, false));
    }

    @Test
    public void testNewLiveRanges() {
// [1258 MustHaveRegister, 1252 MustHaveRegister, 1130 MustHaveRegister, 1130 ShouldHaveRegister,
// 1128 MustHaveRegister]
//// [from: 1128 to: 1130, from: 1252 to: 1258]
// spilled Regions: [from: 1256 to: 1257]
// def: 1128
// LifeRange spilledRegion = new LifeRange(0, 1256, 1257, null);
        Interval inter = new Interval(new Variable(null, 1), 1);
        inter.addUse(1158, RegisterPriority.MustHaveRegister);
        inter.addUse(1252, RegisterPriority.MustHaveRegister);
        inter.addUse(1130, RegisterPriority.MustHaveRegister);
        inter.addUse(1130, RegisterPriority.ShouldHaveRegister);
        inter.addUse(1128, RegisterPriority.MustHaveRegister);

        inter.addLiveRange(1252, 1258);
        inter.addLiveRange(1128, 1130);

        inter.addDef(1128);
// Chaitin allocator = new Chaitin(target, lirGenRes, spillMoveFactory, registerAllocationConfig)

    }

}

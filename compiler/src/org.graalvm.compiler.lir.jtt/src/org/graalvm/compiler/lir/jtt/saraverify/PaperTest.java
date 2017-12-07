package org.graalvm.compiler.lir.jtt.saraverify;

import org.graalvm.compiler.api.directives.GraalDirectives;
import org.graalvm.compiler.jtt.JTTTest;
import org.junit.Test;

public class PaperTest extends JTTTest {

    public static int test(int m) {
        int c = 0;
        for (int d = 1; d <= m; d++) {
            if (m % d == 0) {
                c++;
            }
        }
        return c;
    }

    @Test
    public void run0() {
        runTest("test", 10);
    }

    @Test
    public void run1() {
        runTest("testBranch", 5);
    }

    public static int testBranch(int i) {
        int result;
        if (i > 10) {
            result = 5;
        } else {
            result = 10;
        }
        GraalDirectives.controlFlowAnchor();
        return result;
    }
}

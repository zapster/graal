package org.graalvm.compiler.lir.saraverify;

import org.graalvm.compiler.debug.GraalError;

public class SARAVerifyError extends GraalError {

    private static final long serialVersionUID = 4616134308925793588L;

    public SARAVerifyError(String msg) {
        super(msg);
    }

    public SARAVerifyError(String msg, Object... args) {
        super(msg, args);
    }

    public static RuntimeException unimplemented() {
        throw new SARAVerifyError("unimplemented");
    }

    public static RuntimeException unimplemented(String msg) {
        throw new SARAVerifyError("unimplemented: %s", msg);
    }

    public static RuntimeException shouldNotReachHere() {
        throw new SARAVerifyError("should not reach here");
    }

    public static RuntimeException shouldNotReachHere(String msg) {
        throw new SARAVerifyError("should not reach here: %s", msg);
    }
}

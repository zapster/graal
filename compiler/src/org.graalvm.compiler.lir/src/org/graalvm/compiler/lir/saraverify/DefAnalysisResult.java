package org.graalvm.compiler.lir.saraverify;

import java.util.Map;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;

public class DefAnalysisResult {

    private Map<AbstractBlockBase<?>, DefAnalysisInfo> blockSets;

    public DefAnalysisResult(Map<AbstractBlockBase<?>, DefAnalysisInfo> blockSets) {
        this.blockSets = blockSets;
    }

    public Map<AbstractBlockBase<?>, DefAnalysisInfo> getBlockSets() {
        return blockSets;
    }

}

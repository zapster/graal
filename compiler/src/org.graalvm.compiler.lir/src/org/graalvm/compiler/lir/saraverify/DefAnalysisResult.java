package org.graalvm.compiler.lir.saraverify;

import java.util.Map;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;

public class DefAnalysisResult {

    private Map<AbstractBlockBase<?>, DefAnalysisInfo> blockInfos;

    public DefAnalysisResult(Map<AbstractBlockBase<?>, DefAnalysisInfo> blockInfos) {
        this.blockInfos = blockInfos;
    }

    public Map<AbstractBlockBase<?>, DefAnalysisInfo> getBlockSets() {
        return blockInfos;
    }

}

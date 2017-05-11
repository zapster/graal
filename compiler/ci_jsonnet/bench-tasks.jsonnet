# imports
local Java8 = (import "common.libsonnet").Java8;
local MxBenchmarkBuild = (import "bench-common.libsonnet").MxBenchmarkBuild;

local X52 = (import "machines.libsonnet").X52;
local HwLoc = (import "hwloc.libsonnet").HwLoc;

local conf = import "jvmconfig.libsonnet";
local cap = import "capabilities.libsonnet";

local bench = import "benchmarks.libsonnet";
local Dacapo = bench.Dacapo;
local DacapoTiming = bench.DacapoTiming;

{
  builds: [
    MxBenchmarkBuild + Java8 + vm_config + bench + platform
      for vm_config in [conf.Graal, conf.TraceRA]
        for bench in [Dacapo, DacapoTiming]
          for platform in [cap.NoFrequencyScaling + cap.Tmpfs10G + HwLoc + X52]
  ],
}

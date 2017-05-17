# imports
local common = import "common.libsonnet";
local Java8 = common.Java8;

local mach = import "machines.libsonnet";
local HwLoc = (import "hwloc.libsonnet").HwLoc;
local ScalaDacapoHwLoc = (import "scala-dacapo-hwloc.libsonnet").ScalaDacapoHwLoc;

local target = common.Target;
local conf = import "jvmconfig.libsonnet";
local cap = import "capabilities.libsonnet";

local bench = import "benchmarks.libsonnet";

local DacapoX52 = bench.Dacapo + cap.Tmpfs10G + HwLoc;
local ScalaDacapoX52 = bench.ScalaDacapo + cap.NoFrequencyScaling + cap.Tmpfs10G + ScalaDacapoHwLoc;
{
  builds+: [
    # post-merge
    ## x52
    bench + Java8 + conf.Graal + cap.NoFrequencyScaling + mach.X52 + target.PostMerge
      for bench in [ DacapoX52, ScalaDacapoX52, bench.SPECjvm2008Single]
    ] + [
    ## x32
    bench.Renaissance + Java8 + conf.Graal + cap.NoFrequencyScaling + cap.Tmpfs25G + cap.NoFrequencyScaling + mach.X32 + target.PostMerge
    ] + [
    ## M7
    bench + Java8 + conf.Graal + mach.M7 + target.PostMerge
      for bench in [ bench.Dacapo, bench.ScalaDacapo, bench.SPECjvm2008Single ]
  ],
}

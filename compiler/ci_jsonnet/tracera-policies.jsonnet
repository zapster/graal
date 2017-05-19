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

local DacapoHwLocLinux = bench.Dacapo + cap.Tmpfs10G + HwLoc;
local ScalaDacapoHwLocLinux = bench.ScalaDacapo + cap.NoFrequencyScaling + cap.Tmpfs10G + ScalaDacapoHwLoc;
{
  tracera_policy_configs::[
   c {name+: "-tracera-policy-experiment", timelimit:"1:00:00"} for c in [
      conf.Graal,
      conf.TraceRA,
      conf.TraceRA + { jvm_config+: "-bu" },
      conf.TraceRA + { jvm_config+: "-loops" },
    ] + [
      conf.TraceRA + { jvm_config+: "-ratio-0.%d" % ratio } for ratio in std.range(1,9)
    ] + [
      conf.TraceRA + { jvm_config+: "-maxfreq-0.%d" % ratio } for ratio in std.range(1,9)
    ] + [
      conf.TraceRA + { jvm_config+: "-freqbudget-0.%d" % ratio } for ratio in std.range(1,9)
    ] + [
      conf.TraceRA + { jvm_config+: "-almosttrivial-%s" % ratio } for ratio in std.range(2,10) + ["all"]
    ]
  ],
  builds+: [
    bench + Java8 + config + cap.NoFrequencyScaling + mach.X32 + target.Bench
      for bench in [ DacapoHwLocLinux + bench.Timing, ScalaDacapoHwLocLinux + bench.Timing]
        for config in self.tracera_policy_configs
  ],
}

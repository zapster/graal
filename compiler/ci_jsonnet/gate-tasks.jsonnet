local gate = import "gate.libsonnet";
local common = import "common.libsonnet";
local Java8 = common.Java8;
{
  builds+: [
    gate.GateTest + Java8 + mach
      for mach in [
        common.Linux + common.AMD64,
        common.Darwin + common.AMD64,
        common.Solaris + common.SPARCv9,
      ]
  ]
}

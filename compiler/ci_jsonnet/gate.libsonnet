local common = import "common.libsonnet";
local CompilerCommonBuild = common.CompilerCommonBuild;
local target = common.Target;

{
  GateBuild:: CompilerCommonBuild + target.Gate {
    tags:: error "no gate tags specified",
    extra_vm_args:: ["-Dgraal.DumpOnError=true"],
    cmd:: ["mx", "-v", "--kill-with-sigquit", "--strict-compliance", "gate", "--strict-mode", "-Dgraal.PrintGraphFile=true"] +
           ["--extra-vm-argument=%s" % arg for arg in self.extra_vm_args] +
           ["--tags", std.join(",", self.tags)],
    run: [
      self.cmd
    ],
    name: "gate",
  },
  GateTest:: self.GateBuild + {
      tags::["build","test"],
      name+: "-test",
  },
  GateTestCTW:: self.GateBuild + {
      tags::["build","ctw"]
  },
  GateTestBenchmark:: self.GateBuild + {
      tags::["build","benchmarktest"]
  },
  GateBootstrap:: self.GateBuild + {
      tags::["build","bootstrap"]
  },
  GateBootstrapLite:: self.GateBuild + {
      tags::["build","bootstraplite"]
  },
  GateBootstrapFullVerify:: self.GateBuild + {
      tags::["build","bootstrapfullverify"]
  },
}

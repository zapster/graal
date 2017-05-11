local common = import "common.libsonnet";

{
  X52:: common.Linux + common.AMD64 {
    num_nodes:: 2,
    capabilities+: ["x52"],
    max_heap_size: "64g",
    init_heap_size: "64g",
    machine_name: "x52",
    name+: "-" + self.machine_name,
  },
  X32:: common.Linux + common.AMD64 {
    num_nodes:: 2,
    capabilities+: ["x32"],
    max_heap_size: "8g",
    init_heap_size: "8g",
    machine_name: "x32",
    name+: "-" + self.machine_name,
  },
  M7:: common.Solaris + common.SPARCv9 {
    capabilities+: ["m7_eighth"],
    max_heap_size: "16g",
    init_heap_size: "16g",
    machine_name: "m7_eighth",
    name+: "-" + self.machine_name,
  },
}

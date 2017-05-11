local Linux = (import "common.libsonnet").Linux;
local AMD64 = (import "common.libsonnet").AMD64;

{
  X52:: Linux + AMD64 {
    num_nodes:: 2,
    capabilities+: ["x52"],
    max_heap_size: "64g",
    init_heap_size: "64g",
    machine_name: "x52",
    name+: "-" + self.machine_name,
  },
}

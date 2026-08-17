#!/usr/bin/env python3

import json
import pathlib
import sys


if len(sys.argv) != 2:
    raise SystemExit(f"usage: {sys.argv[0]} RUN_TAG")

run_tag = sys.argv[1]
run_path = pathlib.Path(__file__).parent / "runs" / run_tag
sta_steps = sorted(run_path.glob("*-openroad-stamidpnr-1"))
if len(sta_steps) != 1:
    raise SystemExit(f"expected one post-CTS STA step below {run_path}, found {len(sta_steps)}")
state_path = sta_steps[0] / "state_out.json"
metrics = json.loads(state_path.read_text())["metrics"]
wns = metrics["timing__setup__wns"]
period_ns = 10.0
fmax_mhz = 1000.0 / (period_ns - wns)

print(f"run tag:       {run_tag}")
print(f"setup WNS:     {wns:.5f} ns")
print(f"full-cycle Fmax: {fmax_mhz:.2f} MHz")
print(f"setup TNS:     {metrics['timing__setup__tns']:.3f} ns")
print(f"std-cell area: {metrics['design__instance__area__stdcell']:.0f} um^2")
macro_area = metrics.get("design__instance__area__macros", 0.0)
if macro_area:
    print(f"macro area:    {macro_area:.0f} um^2")
    print(
        "cell+macro:    "
        f"{metrics['design__instance__area__stdcell'] + macro_area:.0f} um^2"
    )
print(f"instances:     {metrics['design__instance__count']}")
print(f"power:         {metrics['power__total'] * 1000.0:.3f} mW")

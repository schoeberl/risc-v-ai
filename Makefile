.PHONY: coremark coremark-build embench embench-build rtl rtl-five-stages rtl-six-stages rtl-six-stages-memory-split rtl-multicycle rtl-two-stages rtl-three-stages rtl-three-stages-predecode \
	rtl-three-stages-execute-memory rtl-cached rtl-sky130-cached \
	rtl-sky130-cached-five-stages rtl-sky130-cached-six-stages rtl-sky130-cached-six-stages-memory-split rtl-sky130-cached-multicycle rtl-sky130-cached-two-stages \
	rtl-sky130-cached-three-stages rtl-sky130-cached-three-stages-predecode \
	rtl-sky130-cached-three-stages-execute-memory ppa-sky130 \
	ppa-sky130-three-stages ppa-sky130-cached \
	ppa-sky130-three-stages-predecode ppa-sky130-sram-cached \
	ppa-sky130-sram-cached-three-stages ppa-sky130-sram-cached-three-stages-predecode \
	ppa-sky130-sram-cached-three-stages-execute-memory \
	ppa-sky130-sram-cached-five-stages ppa-sky130-sram-cached-six-stages ppa-sky130-sram-cached-six-stages-memory-split ppa-sky130-sram-cached-multicycle ppa-sky130-sram-cached-two-stages \
	ppa-sky130-sram-cached-post-cts ppa-sky130-sram-cached-three-stages-post-cts \
	ppa-sky130-sram-cached-three-stages-predecode-post-cts \
	ppa-sky130-sram-cached-three-stages-execute-memory-post-cts \
	ppa-sky130-sram-cached-two-stages-post-cts \
	ppa-sky130-sram-cached-multicycle-post-cts \
	ppa-sky130-sram-cached-five-stages-post-cts ppa-sky130-sram-cached-six-stages-post-cts \
	ppa-sky130-sram-cached-six-stages-memory-split-post-cts
.PHONY: coremark-ideal embench-ideal \
	rtl-ideal-multicycle rtl-ideal-two-stages rtl-ideal-three-stages \
	rtl-ideal-three-stages-predecode rtl-ideal-three-stages-execute-memory \
	rtl-ideal-four-stages rtl-ideal-five-stages rtl-ideal-six-stages \
	rtl-ideal-six-stages-memory-split \
	ppa-ideal-multicycle ppa-ideal-two-stages ppa-ideal-three-stages \
	ppa-ideal-three-stages-predecode ppa-ideal-three-stages-execute-memory \
	ppa-ideal-four-stages ppa-ideal-five-stages ppa-ideal-six-stages \
	ppa-ideal-six-stages-memory-split ppa-ideal-all

RTL_DIR := generated
LIBRELANE_ROOT ?= $(CURDIR)/external/librelane
SKY130_PDK_ROOT ?= $(HOME)/.ciel
PPA_RUN_TAG ?= 100mhz
COREMARK_BIN := $(CURDIR)/build/coremark/coremark.bin
EMBENCH_BIN_DIR := $(CURDIR)/build/embench
POST_CTS_ARGS := --skip Checker.PowerGridViolations --to OpenROAD.STAMidPNR-1

coremark-build:
	$(MAKE) -C benchmarks/riscvai-coremark all

coremark: coremark-build
	COREMARK_BIN=$(COREMARK_BIN) sbt "testOnly riscvai.CoreMarkBenchmarkSpec"

embench-build:
	$(MAKE) -C benchmarks/riscvai-embench all

embench: embench-build
	EMBENCH_BIN_DIR=$(EMBENCH_BIN_DIR) sbt "testOnly riscvai.EmbenchBenchmarkSpec"

coremark-ideal: coremark-build
	COREMARK_BIN=$(COREMARK_BIN) sbt "testOnly riscvai.IdealMemoryCoreMarkBenchmarkSpec"

embench-ideal: embench-build
	EMBENCH_BIN_DIR=$(EMBENCH_BIN_DIR) sbt "testOnly riscvai.IdealMemoryEmbenchBenchmarkSpec"

rtl-ideal-multicycle:
	sbt "runMain riscvai.ElaborateIdealMemoryMulticycle --target-dir $(RTL_DIR)"

rtl-ideal-two-stages:
	sbt "runMain riscvai.ElaborateIdealMemoryTwoStages --target-dir $(RTL_DIR)"

rtl-ideal-three-stages:
	sbt "runMain riscvai.ElaborateIdealMemoryThreeStages --target-dir $(RTL_DIR)"

rtl-ideal-three-stages-predecode:
	sbt "runMain riscvai.ElaborateIdealMemoryThreeStagesPredecode --target-dir $(RTL_DIR)"

rtl-ideal-three-stages-execute-memory:
	sbt "runMain riscvai.ElaborateIdealMemoryThreeStagesExecuteMemory --target-dir $(RTL_DIR)"

rtl-ideal-four-stages:
	sbt "runMain riscvai.ElaborateIdealMemoryFourStages --target-dir $(RTL_DIR)"

rtl-ideal-five-stages:
	sbt "runMain riscvai.ElaborateIdealMemoryFiveStages --target-dir $(RTL_DIR)"

rtl-ideal-six-stages:
	sbt "runMain riscvai.ElaborateIdealMemorySixStages --target-dir $(RTL_DIR)"

rtl-ideal-six-stages-memory-split:
	sbt "runMain riscvai.ElaborateIdealMemorySixStagesMemorySplit --target-dir $(RTL_DIR)"

rtl:
	sbt "runMain riscvai.Elaborate --target-dir $(RTL_DIR)"

rtl-five-stages:
	sbt "runMain riscvai.ElaborateFiveStages --target-dir $(RTL_DIR)"

rtl-six-stages:
	sbt "runMain riscvai.ElaborateSixStages --target-dir $(RTL_DIR)"

rtl-six-stages-memory-split:
	sbt "runMain riscvai.ElaborateSixStagesMemorySplit --target-dir $(RTL_DIR)"

rtl-multicycle:
	sbt "runMain riscvai.ElaborateMulticycle --target-dir $(RTL_DIR)"

rtl-two-stages:
	sbt "runMain riscvai.ElaborateTwoStages --target-dir $(RTL_DIR)"

rtl-three-stages:
	sbt "runMain riscvai.ElaborateThreeStages --target-dir $(RTL_DIR)"

rtl-three-stages-predecode:
	sbt "runMain riscvai.ElaborateThreeStagesPredecode --target-dir $(RTL_DIR)"

rtl-three-stages-execute-memory:
	sbt "runMain riscvai.ElaborateThreeStagesExecuteMemory --target-dir $(RTL_DIR)"

rtl-cached:
	sbt "runMain riscvai.ElaborateCached --target-dir $(RTL_DIR)"

rtl-sky130-cached:
	sbt "runMain riscvai.ElaborateSky130Cached --target-dir $(RTL_DIR)"

rtl-sky130-cached-five-stages:
	sbt "runMain riscvai.ElaborateSky130CachedFiveStages --target-dir $(RTL_DIR)"

rtl-sky130-cached-six-stages:
	sbt "runMain riscvai.ElaborateSky130CachedSixStages --target-dir $(RTL_DIR)"

rtl-sky130-cached-six-stages-memory-split:
	sbt "runMain riscvai.ElaborateSky130CachedSixStagesMemorySplit --target-dir $(RTL_DIR)"

rtl-sky130-cached-multicycle:
	sbt "runMain riscvai.ElaborateSky130CachedMulticycle --target-dir $(RTL_DIR)"

rtl-sky130-cached-two-stages:
	sbt "runMain riscvai.ElaborateSky130CachedTwoStages --target-dir $(RTL_DIR)"

rtl-sky130-cached-three-stages:
	sbt "runMain riscvai.ElaborateSky130CachedThreeStages --target-dir $(RTL_DIR)"

rtl-sky130-cached-three-stages-predecode:
	sbt "runMain riscvai.ElaborateSky130CachedThreeStagesPredecode --target-dir $(RTL_DIR)"

rtl-sky130-cached-three-stages-execute-memory:
	sbt "runMain riscvai.ElaborateSky130CachedThreeStagesExecuteMemory --target-dir $(RTL_DIR)"

ppa-sky130: rtl
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config.yaml'

ppa-sky130-three-stages: rtl-three-stages
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-three-stages.yaml'

ppa-sky130-three-stages-predecode: rtl-three-stages-predecode
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-three-stages-predecode.yaml'

ppa-sky130-cached: rtl-cached
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-cached.yaml'

ppa-sky130-sram-cached: rtl-sky130-cached
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-sram-cached.yaml'

ppa-sky130-sram-cached-five-stages: rtl-sky130-cached-five-stages
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-sram-cached-five-stages.yaml'

ppa-sky130-sram-cached-six-stages: rtl-sky130-cached-six-stages
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-sram-cached-six-stages.yaml'

ppa-sky130-sram-cached-six-stages-memory-split: rtl-sky130-cached-six-stages-memory-split
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-sram-cached-six-stages-memory-split.yaml'

ppa-sky130-sram-cached-multicycle: rtl-sky130-cached-multicycle
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-sram-cached-multicycle.yaml'

ppa-sky130-sram-cached-two-stages: rtl-sky130-cached-two-stages
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-sram-cached-two-stages.yaml'

ppa-sky130-sram-cached-three-stages: rtl-sky130-cached-three-stages
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-sram-cached-three-stages.yaml'

ppa-sky130-sram-cached-three-stages-predecode: rtl-sky130-cached-three-stages-predecode
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-sram-cached-three-stages-predecode.yaml'

ppa-sky130-sram-cached-three-stages-execute-memory: rtl-sky130-cached-three-stages-execute-memory
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-sram-cached-three-stages-execute-memory.yaml'

ppa-sky130-sram-cached-post-cts: rtl-sky130-cached
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-sram-cached.yaml'

ppa-sky130-sram-cached-five-stages-post-cts: rtl-sky130-cached-five-stages
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-sram-cached-five-stages.yaml'

ppa-sky130-sram-cached-six-stages-post-cts: rtl-sky130-cached-six-stages
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-sram-cached-six-stages.yaml'

ppa-sky130-sram-cached-six-stages-memory-split-post-cts: rtl-sky130-cached-six-stages-memory-split
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-sram-cached-six-stages-memory-split.yaml'

ppa-sky130-sram-cached-multicycle-post-cts: rtl-sky130-cached-multicycle
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-sram-cached-multicycle.yaml'

ppa-sky130-sram-cached-two-stages-post-cts: rtl-sky130-cached-two-stages
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-sram-cached-two-stages.yaml'

ppa-sky130-sram-cached-three-stages-post-cts: rtl-sky130-cached-three-stages
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-sram-cached-three-stages.yaml'

ppa-sky130-sram-cached-three-stages-predecode-post-cts: rtl-sky130-cached-three-stages-predecode
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-sram-cached-three-stages-predecode.yaml'

ppa-sky130-sram-cached-three-stages-execute-memory-post-cts: rtl-sky130-cached-three-stages-execute-memory
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-sram-cached-three-stages-execute-memory.yaml'

ppa-ideal-multicycle: rtl-ideal-multicycle
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-ideal-multicycle.yaml'

ppa-ideal-two-stages: rtl-ideal-two-stages
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-ideal-two-stages.yaml'

ppa-ideal-three-stages: rtl-ideal-three-stages
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-ideal-three-stages.yaml'

ppa-ideal-three-stages-predecode: rtl-ideal-three-stages-predecode
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-ideal-three-stages-predecode.yaml'

ppa-ideal-three-stages-execute-memory: rtl-ideal-three-stages-execute-memory
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-ideal-three-stages-execute-memory.yaml'

ppa-ideal-four-stages: rtl-ideal-four-stages
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-ideal-four-stages.yaml'

ppa-ideal-five-stages: rtl-ideal-five-stages
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-ideal-five-stages.yaml'

ppa-ideal-six-stages: rtl-ideal-six-stages
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-ideal-six-stages.yaml'

ppa-ideal-six-stages-memory-split: rtl-ideal-six-stages-memory-split
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) $(POST_CTS_ARGS) ppa/librelane/config-ideal-six-stages-memory-split.yaml'

ppa-ideal-all:
	$(MAKE) ppa-ideal-multicycle PPA_RUN_TAG=ideal-core-multicycle-100mhz
	$(MAKE) ppa-ideal-two-stages PPA_RUN_TAG=ideal-core-two-100mhz
	$(MAKE) ppa-ideal-three-stages PPA_RUN_TAG=ideal-core-three-100mhz
	$(MAKE) ppa-ideal-three-stages-predecode PPA_RUN_TAG=ideal-core-three-predecode-100mhz
	$(MAKE) ppa-ideal-three-stages-execute-memory PPA_RUN_TAG=ideal-core-three-execute-memory-100mhz
	$(MAKE) ppa-ideal-four-stages PPA_RUN_TAG=ideal-core-four-100mhz
	$(MAKE) ppa-ideal-five-stages PPA_RUN_TAG=ideal-core-five-100mhz
	$(MAKE) ppa-ideal-six-stages PPA_RUN_TAG=ideal-core-six-100mhz
	$(MAKE) ppa-ideal-six-stages-memory-split PPA_RUN_TAG=ideal-core-six-memory-split-100mhz

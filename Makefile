.PHONY: coremark coremark-build rtl rtl-three-stages rtl-three-stages-predecode rtl-cached rtl-sky130-cached \
	rtl-sky130-cached-three-stages rtl-sky130-cached-three-stages-predecode ppa-sky130 \
	ppa-sky130-three-stages ppa-sky130-cached \
	ppa-sky130-three-stages-predecode ppa-sky130-sram-cached \
	ppa-sky130-sram-cached-three-stages ppa-sky130-sram-cached-three-stages-predecode

RTL_DIR := generated
LIBRELANE_ROOT ?= $(HOME)/librelane
SKY130_PDK_ROOT ?= $(HOME)/.ciel
PPA_RUN_TAG ?= 100mhz
COREMARK_BIN := $(CURDIR)/build/coremark/coremark.bin

coremark-build:
	$(MAKE) -C benchmarks/riscvai-coremark all

coremark: coremark-build
	COREMARK_BIN=$(COREMARK_BIN) sbt "testOnly riscvai.CoreMarkBenchmarkSpec"

rtl:
	sbt "runMain riscvai.Elaborate --target-dir $(RTL_DIR)"

rtl-three-stages:
	sbt "runMain riscvai.ElaborateThreeStages --target-dir $(RTL_DIR)"

rtl-three-stages-predecode:
	sbt "runMain riscvai.ElaborateThreeStagesPredecode --target-dir $(RTL_DIR)"

rtl-cached:
	sbt "runMain riscvai.ElaborateCached --target-dir $(RTL_DIR)"

rtl-sky130-cached:
	sbt "runMain riscvai.ElaborateSky130Cached --target-dir $(RTL_DIR)"

rtl-sky130-cached-three-stages:
	sbt "runMain riscvai.ElaborateSky130CachedThreeStages --target-dir $(RTL_DIR)"

rtl-sky130-cached-three-stages-predecode:
	sbt "runMain riscvai.ElaborateSky130CachedThreeStagesPredecode --target-dir $(RTL_DIR)"

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

ppa-sky130-sram-cached-three-stages: rtl-sky130-cached-three-stages
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-sram-cached-three-stages.yaml'

ppa-sky130-sram-cached-three-stages-predecode: rtl-sky130-cached-three-stages-predecode
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-sram-cached-three-stages-predecode.yaml'

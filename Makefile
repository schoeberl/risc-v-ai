.PHONY: rtl rtl-cached rtl-sky130-cached ppa-sky130 ppa-sky130-cached \
	ppa-sky130-sram-cached

RTL_DIR := generated
LIBRELANE_ROOT ?= $(HOME)/librelane
SKY130_PDK_ROOT ?= $(HOME)/.ciel
PPA_RUN_TAG ?= 100mhz

rtl:
	sbt "runMain riscvai.Elaborate --target-dir $(RTL_DIR)"

rtl-cached:
	sbt "runMain riscvai.ElaborateCached --target-dir $(RTL_DIR)"

rtl-sky130-cached:
	sbt "runMain riscvai.ElaborateSky130Cached --target-dir $(RTL_DIR)"

ppa-sky130: rtl
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config.yaml'

ppa-sky130-cached: rtl-cached
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-cached.yaml'

ppa-sky130-sram-cached: rtl-sky130-cached
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config-sram-cached.yaml'

ENTRY(_start)

MEMORY {
  ram (rwx) : ORIGIN = 0x00000000, LENGTH = 256K
}

SECTIONS {
  .text : {
    KEEP(*(.text.init))
    *(.text .text.*)
  } > ram
  .rodata : ALIGN(4) { *(.rodata .rodata.*) } > ram
  .data : ALIGN(4) { *(.sdata .sdata.*) *(.data .data.*) } > ram
  .bss (NOLOAD) : ALIGN(4) {
    _bss_start = .;
    *(.sbss .sbss.*) *(.bss .bss.*) *(COMMON)
    . = ALIGN(4);
    _bss_end = .;
  } > ram
  _stack_top = ORIGIN(ram) + LENGTH(ram);
}

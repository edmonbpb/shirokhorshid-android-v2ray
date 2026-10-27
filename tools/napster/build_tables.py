#!/usr/bin/env python3
"""Build Napster legacy table asset from npvt.go literal arrays.

Reads tyBoxes, mbl, tboxesLast, xorTable from pantegnos/npvt.go
and writes exactly 61,440 bytes into app/src/main/assets/napster/legacy_tables.bin.
Also copies gen2_tables.bin.z verbatim and byte-compares.
"""

import os
import re
import shutil
import struct
import sys

def main():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(base_dir, "..", ".."))
    pantegnos_dir = os.path.abspath(os.path.join(project_root, "..", "slipnet-research", "pantegnos"))
    npvt_go = os.path.join(pantegnos_dir, "npvt.go")
    gen2_src = os.path.join(pantegnos_dir, "gen2_tables.bin.z")
    
    assets_dir = os.path.join(project_root, "app", "src", "main", "assets", "napster")
    os.makedirs(assets_dir, exist_ok=True)
    legacy_out = os.path.join(assets_dir, "legacy_tables.bin")
    gen2_out = os.path.join(assets_dir, "gen2_tables.bin.z")
    
    print(f"Reading {npvt_go}...")
    with open(npvt_go, "r", encoding="utf-8") as f:
        text = f.read()

    def parse_array_body(decl_name, end_name=None):
        pos = text.find("var " + decl_name)
        if pos == -1:
            raise ValueError(f"Could not find var {decl_name}")
        brace = text.find("{", pos)
        if end_name:
            end_pos = text.find("var " + end_name)
            body = text[brace+1:end_pos]
        else:
            body = text[brace+1:]
        return [int(x) for x in re.findall(r"\b\d+\b", body)]

    ty_list = parse_array_body("tyBoxes", "mbl")
    mbl_list = parse_array_body("mbl", "tboxesLast")
    tlast_list = parse_array_body("tboxesLast", "xorTable")
    xor_list = parse_array_body("xorTable")

    assert len(ty_list) == 16 * 256, f"tyBoxes count mismatch: {len(ty_list)}"
    assert len(mbl_list) == 16 * 256, f"mbl count mismatch: {len(mbl_list)}"
    assert len(tlast_list) == 16 * 256, f"tboxesLast count mismatch: {len(tlast_list)}"
    assert len(xor_list) == 96 * 16 * 16, f"xorTable count mismatch: {len(xor_list)}"

    # Assert sample values from npvt.go
    assert ty_list[0] == 3213434693
    assert ty_list[1] == 2400509502
    assert ty_list[255] == 377157257
    assert ty_list[-1] == 3954330219
    assert mbl_list[0] == 0
    assert mbl_list[1] == 1451429075
    assert mbl_list[255] == 207363256
    assert mbl_list[-1] == 2495367704
    assert tlast_list[0] == 82
    assert tlast_list[1] == 156
    assert tlast_list[255] == 2
    assert tlast_list[-1] == 94
    assert xor_list[0] == 0
    assert xor_list[1] == 1
    assert xor_list[16] == 1
    assert xor_list[17] == 0
    assert xor_list[1] == 1
    assert xor_list[16] == 1
    assert xor_list[17] == 0

    out_bytes = bytearray()
    for v in ty_list:
        out_bytes.extend(struct.pack(">I", v))
    for v in mbl_list:
        out_bytes.extend(struct.pack(">I", v))
    for v in tlast_list:
        out_bytes.append(v)
    for v in xor_list:
        out_bytes.append(v)

    assert len(out_bytes) == 61440, f"Expected 61440 bytes, got {len(out_bytes)}"
    
    with open(legacy_out, "wb") as f:
        f.write(out_bytes)
    print(f"Wrote {legacy_out} ({len(out_bytes)} bytes)")

    print(f"Copying {gen2_src} to {gen2_out}...")
    shutil.copyfile(gen2_src, gen2_out)
    
    # Byte-compare
    with open(gen2_src, "rb") as f1, open(gen2_out, "rb") as f2:
        src_bytes = f1.read()
        dst_bytes = f2.read()
        assert src_bytes == dst_bytes, "gen2_tables.bin.z byte compare failed!"
    print(f"gen2_tables.bin.z copied and verified byte-for-byte ({len(dst_bytes)} bytes)")

if __name__ == "__main__":
    main()

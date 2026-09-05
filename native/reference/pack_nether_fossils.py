"""Pack one vanilla oracle log into a NetherFossilNativeTest fixture row."""

import argparse
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("version")
parser.add_argument("log", type=Path)
args = parser.parse_args()
rows = [line[line.index("FOSSIL,"):].split(",")
        for line in args.log.read_text().splitlines() if "FOSSIL," in line]
if len(rows) != 1024:
    raise ValueError(f"Expected 1024 candidates, got {len(rows)}")
seed = rows[0][1]
for index, row in enumerate(rows):
    if (row[1] != seed or int(row[2]) != (index % 32 - 16) * 32
            or int(row[3]) != (index // 32 - 16) * 32 or row[7] not in ("0", "1")):
        raise ValueError(f"Unexpected candidate at index {index}: {row[:8]}")
bits = "".join(format(sum(int(rows[i + bit][7]) << bit for bit in range(4)), "x")
               for i in range(0, 1024, 4))
print(f"{args.version},{seed},{bits}")

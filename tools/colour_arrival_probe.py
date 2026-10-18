#!/usr/bin/env python3
"""When does the page wash reach its final colour? Reads raw screen frames, stdlib only.

usage: colour_arrival_probe.py FRAMES.bin [--band TOP BOTTOM] [--tol N]

FRAMES.bin is what `adb exec-out screenrecord --output-format=frames --size 336x748 - > f.bin`
writes (on the sandbox VM's AVD, write to a file on the device and `adb pull` it: piped through
`exec-out` the stream stalls after the first frame). One frame per screen change, no timestamps:
a 20-byte little-endian header `<IIIII` (a size field, width, height, stride in BYTES per row,
bytes per pixel), then stride x height bytes of pixels, R G B first.

For each frame it prints `index mean_r mean_g mean_b dist_to_final`: the mean colour of a
horizontal band (default rows 12% to 30% of the height: the wash under the title, clear of the
status bar) and its RGB distance to the last frame's. The last line is `settled_frame=<i> of <n>`,
the first frame after which every frame stays within --tol (default 24) of the final colour.
"""
import argparse
import math
import struct

HEADER = struct.Struct("<IIIII")


def read_frames(path):
	"""[(width, height, stride, bpp, pixels)] for every whole frame in the file."""
	frames = []
	with open(path, "rb") as f:
		while True:
			head = f.read(HEADER.size)
			if len(head) < HEADER.size:
				break
			_, width, height, stride, bpp = HEADER.unpack(head)
			data = f.read(stride * height)
			if len(data) < stride * height:
				break
			frames.append((width, height, stride, bpp, data))
	return frames


def band_mean(frame, top=0.12, bottom=0.30):
	"""Mean (r, g, b) over rows int(top*h) to int(bottom*h), inclusive, every column."""
	width, height, stride, bpp, data = frame
	y0 = int(height * top)
	y1 = max(y0, min(height - 1, int(height * bottom)))
	r = g = b = 0
	n = 0
	for y in range(y0, y1 + 1):
		row = y * stride
		for x in range(width):
			i = row + x * bpp
			r += data[i]
			g += data[i + 1]
			b += data[i + 2]
			n += 1
	return (r / n, g / n, b / n)


def distance(a, b):
	return math.sqrt(sum((p - q) ** 2 for p, q in zip(a, b)))


def run(path, top=0.12, bottom=0.30, tol=24.0):
	"""The probe's output lines (also what main() prints)."""
	frames = read_frames(path)
	if not frames:
		return ["settled_frame=-1 of 0"]
	means = [band_mean(f, top, bottom) for f in frames]
	final = means[-1]
	dists = [distance(m, final) for m in means]
	lines = [f"{i} {m[0]:.1f} {m[1]:.1f} {m[2]:.1f} {d:.1f}" for i, (m, d) in enumerate(zip(means, dists))]
	settled = len(frames) - 1
	for i in range(len(frames) - 1, -1, -1):
		if dists[i] > tol:
			break
		settled = i
	lines.append(f"settled_frame={settled} of {len(frames)}")
	return lines


def main():
	ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
	ap.add_argument("frames")
	ap.add_argument("--band", nargs=2, type=float, metavar=("TOP", "BOTTOM"), default=(0.12, 0.30),
		help="band as fractions of the height (default 0.12 0.30)")
	ap.add_argument("--tol", type=float, default=24.0, help="RGB distance counted as settled (default 24)")
	args = ap.parse_args()
	for line in run(args.frames, args.band[0], args.band[1], args.tol):
		print(line)


if __name__ == "__main__":
	main()

"""Tests for colour_arrival_probe.py. Run: python3 -I tools/test_colour_arrival_probe.py"""
import os
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import colour_arrival_probe as probe  # noqa: E402


def frames_file(greys, width=4, height=10, band_rows=(1, 3), stride=None, bpp=4):
	"""A screenrecord-style frames file: each frame a 20-byte header, then stride*height bytes.
	Rows in band_rows (inclusive) are the frame's grey, every other row black."""
	stride = stride if stride is not None else width * bpp
	out = bytearray()
	for g in greys:
		out += struct.pack("<IIIII", stride * height, width, height, stride, bpp)
		for y in range(height):
			v = g if band_rows[0] <= y <= band_rows[1] else 0
			row = bytearray()
			for _ in range(width):
				row += bytes([v, v, v] + [255] * (bpp - 3))
			row += bytes(stride - len(row))
			out += row
	f = tempfile.NamedTemporaryFile(delete=False, suffix=".bin")
	f.write(out)
	f.close()
	return f.name


class ProbeTest(unittest.TestCase):
	def test_settled_frame_is_first_frame_after_which_all_stay_near_final(self):
		path = frames_file([10, 10, 200, 200, 200])
		lines = probe.run(path, tol=24)
		self.assertEqual(lines[-1], "settled_frame=2 of 5")

	def test_a_late_flicker_moves_the_settle_point(self):
		path = frames_file([10, 200, 10, 200, 200])
		lines = probe.run(path, tol=24)
		self.assertEqual(lines[-1], "settled_frame=3 of 5")

	def test_header_is_20_bytes(self):
		# Row padding: a stride wider than width x bytes-per-pixel still parses frame by frame.
		path = frames_file([50, 60], stride=4 * 4 + 12)
		frames = probe.read_frames(path)
		self.assertEqual(len(frames), 2)
		lines = probe.run(path, tol=24)
		self.assertEqual(lines[-1], "settled_frame=0 of 2")

	def test_one_line_per_frame(self):
		path = frames_file([10, 200])
		lines = probe.run(path, tol=24)
		self.assertEqual(len(lines), 3)
		self.assertTrue(lines[0].startswith("0 10.0 10.0 10.0 "))


if __name__ == "__main__":
	unittest.main()

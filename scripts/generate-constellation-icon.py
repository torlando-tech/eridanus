#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""
Generate a constellation app icon SVG from a star pattern SVG.

Takes star positions and constellation lines from an Adobe Illustrator
pattern SVG (1080x1080) and produces a 512x512 circular app icon
matching the style of Carina and Columba icons.

Usage:
    python generate-constellation-icon.py

Edit the STARS, POLYLINE_POINTS, GRADIENT_COLORS, and OUTPUT_FILE
variables below for different constellations.
"""

import math

# --- Configuration ---

OUTPUT_FILE = "eridanus-icon.svg"

# Gradient: (start_color, end_color) - diagonal top-left to bottom-right
GRADIENT_COLORS = ("#0D47A1", "#FFA726")  # Deep blue to amber (river theme)
# Carina uses:  ("#43CEA2", "#5C3D9B")   # Teal to purple
# Columba uses: ("#C471ED", "#F64F59")   # Lavender to coral

# Stars from pattern SVG: (x, y, radius)
# Radius reflects star brightness (larger = brighter)
STARS = [
    (190.1, 232.7, 3.5),
    (465.5, 322.9, 3.5),
    (269.7, 193.1, 2.4),
    (306.9, 189.8, 2.4),
    (407.9, 230.1, 2.4),
    (507.5, 300.7, 2.4),
    (518.8, 267.3, 2.4),
    (556.4, 262.7, 2.4),
    (693.9, 260.9, 2.4),
    (732.6, 336.8, 2.4),
    (721.8, 403.0, 2.4),
    (656.5, 470.4, 2.4),
    (600.4, 440.4, 2.4),
    (552.6, 438.2, 2.4),
    (508.6, 461.3, 2.4),
    (487.3, 482.0, 2.4),
    (466.2, 474.3, 2.4),
    (365.6, 565.2, 2.4),
    (401.5, 623.3, 2.4),
    (541.2, 706.2, 2.4),
    (588.5, 747.6, 2.4),
    (700.5, 709.7, 2.4),
    (697.2, 753.1, 2.4),
    (720.5, 825.7, 2.4),
    (785.2, 894.8, 2.4),
    (805.2, 983.6, 5.9),  # Achernar - brightest
    (505.8, 648.1, 1.8),
    (419.3, 618.6, 3.3),
    (650.1, 711.9, 4.0),
    (360.8, 577.9, 2.4),
]

# Constellation line path (ordered points)
POLYLINE_POINTS = [
    (805.2, 983.6), (785.2, 894.8), (720.5, 825.7), (697.2, 753.1),
    (700.5, 709.7), (650.1, 712.1), (588.5, 747.6), (541.2, 706.2),
    (505.8, 648.1), (419.3, 618.6), (401.5, 624.0), (360.8, 577.9),
    (365.6, 565.2), (466.2, 474.3), (487.3, 482.0), (508.6, 461.3),
    (552.6, 438.2), (600.4, 440.4), (656.5, 470.4), (721.8, 403.0),
    (732.6, 336.8), (693.9, 260.9), (556.4, 262.7), (518.0, 267.3),
    (507.5, 300.7), (465.5, 322.9), (407.9, 230.1), (306.9, 189.8),
    (269.7, 193.1), (190.1, 232.7),
]

# --- End Configuration ---


def star_sizes(r):
    """Map source radius to (glow_radius, solid_radius) for the icon."""
    if r >= 5.0:
        return 25, 10
    elif r >= 4.0:
        return 19, 7.5
    elif r >= 3.3:
        return 16, 6.4
    elif r >= 3.0:
        return 15, 6.0
    elif r >= 2.4:
        return 12, 4.8
    else:
        return 9, 3.6


def generate_icon(stars, polyline_points, gradient_colors, output_file):
    xs = [s[0] for s in stars]
    ys = [s[1] for s in stars]
    src_cx = (min(xs) + max(xs)) / 2
    src_cy = (min(ys) + max(ys)) / 2

    # Find max distance from center to any star
    max_dist = max(
        math.sqrt((x - src_cx) ** 2 + (y - src_cy) ** 2)
        for x, y, _ in stars
    )

    # Scale to fit within radius 232 (padding from 256 circle edge)
    target_radius = 232
    scale = target_radius / max_dist

    def transform(x, y):
        nx = (x - src_cx) * scale + 256
        ny = (y - src_cy) * scale + 256
        return round(nx, 1), round(ny, 1)

    lines = []
    lines.append('<?xml version="1.0" encoding="utf-8"?>')
    lines.append('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">')
    lines.append("  <defs>")
    lines.append(
        '    <linearGradient id="bg" x1="0%" y1="0%" x2="100%" y2="100%">'
    )
    lines.append(
        f'      <stop offset="0" style="stop-color:{gradient_colors[0]}"/>'
    )
    lines.append(
        f'      <stop offset="1" style="stop-color:{gradient_colors[1]}"/>'
    )
    lines.append("    </linearGradient>")
    lines.append('    <radialGradient id="starGlow">')
    lines.append(
        '      <stop offset="0" style="stop-color:#FFFFFF;stop-opacity:0.8"/>'
    )
    lines.append(
        '      <stop offset="0.5" style="stop-color:#FFFFFF;stop-opacity:0.3"/>'
    )
    lines.append(
        '      <stop offset="1" style="stop-color:#FFFFFF;stop-opacity:0"/>'
    )
    lines.append("    </radialGradient>")
    lines.append("  </defs>")
    lines.append('  <circle cx="256" cy="256" r="256" fill="url(#bg)"/>')

    # Constellation lines
    for i in range(len(polyline_points) - 1):
        x1, y1 = transform(*polyline_points[i])
        x2, y2 = transform(*polyline_points[i + 1])
        lines.append(
            f'  <path stroke="#FFFFFF" stroke-width="4.5" fill="none"'
            f' stroke-linecap="round" opacity="0.6"'
            f' d="M {x1} {y1} L {x2} {y2}"/>'
        )

    # Stars (glow halo + solid core)
    for sx, sy, sr in stars:
        nx, ny = transform(sx, sy)
        glow_r, solid_r = star_sizes(sr)
        lines.append(
            f'  <circle cx="{nx}" cy="{ny}" r="{glow_r}"'
            f' fill="url(#starGlow)"/>'
        )
        lines.append(
            f'  <circle cx="{nx}" cy="{ny}" r="{solid_r}" fill="#FFFFFF"/>'
        )

    lines.append("</svg>")

    svg = "\n".join(lines)
    with open(output_file, "w") as f:
        f.write(svg)
    print(f"Generated {output_file}")
    print(f"  {len(stars)} stars, {len(polyline_points)-1} line segments")
    print(f"  Scale: {scale:.4f}, Center: ({src_cx:.1f}, {src_cy:.1f})")


if __name__ == "__main__":
    generate_icon(STARS, POLYLINE_POINTS, GRADIENT_COLORS, OUTPUT_FILE)

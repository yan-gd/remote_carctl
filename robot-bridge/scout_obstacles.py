"""Read-only laser evidence for alerts; never replaces local planner collision checks."""
import math


def forward_obstacle(points, x, y, yaw):
    """Cloud and measured pose must share a frame. Return nearest forward distance.

    Two returns in the 0.8m x +/-0.35m corridor are required. The origin is
    base_link, not the bumper. Rear/side walls are excluded; this is not AI.
    """
    if not all(math.isfinite(v) for v in (x, y, yaw)):
        return None
    c, s = math.cos(yaw), math.sin(yaw)
    hits = []
    for p in points:
        if not math.isfinite(p.x) or not math.isfinite(p.y):
            continue
        dx, dy = p.x-x, p.y-y
        ahead, side = c*dx+s*dy, -s*dx+c*dy
        if 0 < ahead <= 0.8 and abs(side) <= 0.35:
            hits.append(ahead)
    return min(hits) if len(hits) >= 2 else None

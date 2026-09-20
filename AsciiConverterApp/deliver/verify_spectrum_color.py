STOPS = [
    (0.00, (0x3B, 0x1F, 0x6E)),
    (0.25, (0x2D, 0x6B, 0xE0)),
    (0.50, (0x24, 0xC9, 0xA8)),
    (0.75, (0xF2, 0xC2, 0x30)),
    (1.00, (0xF2, 0x44, 0x4E)),
]


def spectrum(luma):
    t = max(0, min(255, luma)) / 255.0
    i = 0
    while i < len(STOPS) - 2 and t > STOPS[i + 1][0]:
        i += 1
    t0, c0 = STOPS[i]
    t1, c1 = STOPS[i + 1]
    span = t1 - t0
    k = 0.0 if span <= 0 else max(0.0, min(1.0, (t - t0) / span))
    rgb = tuple(round(c0[j] + (c1[j] - c0[j]) * k) for j in range(3))
    return tuple(max(0, min(255, v)) for v in rgb)


LEVELS = [0.0, 0.0751, 0.0829, 0.0848, 0.1227, 0.1403, 0.1559, 0.185, 0.2183, 0.2417, 0.2571, 0.2852,
          0.2902, 0.2919, 0.3099, 0.3192, 0.3232, 0.3294, 0.3384, 0.3609, 0.3619, 0.3667, 0.3737, 0.3747,
          0.3838, 0.3921, 0.396, 0.3984, 0.3993, 0.4075, 0.4091, 0.4101, 0.42, 0.423, 0.4247, 0.4274,
          0.4293, 0.4328, 0.4382, 0.4385, 0.442, 0.4473, 0.4477, 0.4503, 0.4562, 0.458, 0.461, 0.4638,
          0.4667, 0.4686, 0.4693, 0.4703, 0.4833, 0.4881, 0.4944, 0.4953, 0.4992, 0.5509, 0.5567, 0.5569,
          0.5591, 0.5602, 0.5602, 0.565, 0.5776, 0.5777, 0.5818, 0.587, 0.5972, 0.5999, 0.6043, 0.6049,
          0.6093, 0.6099, 0.6465, 0.6561, 0.6595, 0.6631, 0.6714, 0.6759, 0.6809, 0.6816, 0.6925, 0.7039,
          0.7086, 0.7235, 0.7302, 0.7332, 0.7602, 0.7834, 0.8037, 0.9999]
CHARS = " `.-':_,^=;><+!rc*/z?sLTv)J7(|Fi{C}fI31tlu[neoZ5Yxjya]2ESwqkP6h9d4VpOGbUAKXHm8RD#$Bg0MNWQ%&@"

print("字符表长度 %d / 亮度表长度 %d" % (len(CHARS), len(LEVELS)))
print()
print("%4s %4s %5s %9s" % ("下标", "字符", "亮度", "颜色"))
for idx in (0, 10, 25, 45, 57, 70, 80, 85, 90, 91):
    luma = round(LEVELS[idx] * 255)
    r, g, b = spectrum(luma)
    print("%4d %4s %5d  #%02X%02X%02X" % (idx, CHARS[idx], luma, r, g, b))

print()
print("边界：", spectrum(0), spectrum(255))
thr = int(0.65 * 255)
below = sum(1 for lv in LEVELS if round(lv * 255) < thr)
print("colorFill=0.65 -> 阈值 luma<%d，低于它的字符 %d/92（这些上彩色，其余用前景色白）" % (thr, below))
rs = [spectrum(round(lv * 255))[0] for lv in LEVELS]
print("R 通道单调不减:", all(rs[i] <= rs[i + 1] for i in range(len(rs) - 1)))

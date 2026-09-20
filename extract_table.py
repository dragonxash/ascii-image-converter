import io, re, json

p = r"D:\WorkBuddyWorkSpace\2026-09-15-22-55-59\lib-ref\AsciiArtImageFilter.kt"
s = io.open(p, encoding="utf-8").read()

m = re.search(r'asciiChars = ("(?:[^"\\]|\\.)*")', s)
lit = m.group(1)
print("literal repr:", repr(lit))
body = lit[1:-1]
dec = body.replace("\\$", "$").replace('\\"', '"').replace("\\\\", "\\")
print("decoded len:", len(dec))
print("decoded repr:", repr(dec))
print("decoded str :", dec)

lv = re.search(r'asciiCharsLightLevel = doubleArrayOf\((.*?)\)\n', s, re.S).group(1)
nums = [x.strip() for x in lv.split(',') if x.strip()]
print("light level count:", len(nums))
print("first10:", nums[:10])
print("last5:", nums[-5:])

# sanity: verify bucket table generation against a real luminance ramp
def find_closest(levels, v):
    pre, nxt = 0, -1
    for i, l in enumerate(levels):
        if l < v:
            pre = i
        if l > v:
            nxt = i
            break
    if nxt < 0:
        return len(levels) - 1
    return nxt if abs(v - levels[pre]) > abs(v - levels[nxt]) else pre

levels = [float(x) for x in nums]
idx = [find_closest(levels, i / 255.0) for i in range(256)]
print("idx[0]=", idx[0], "->", repr(dec[idx[0]]))
print("idx[128]=", idx[128], "->", repr(dec[idx[128]]))
print("idx[255]=", idx[255], "->", repr(dec[idx[255]]))

json.dump({
    "chars": dec,
    "levels": levels,
    "index": idx,
}, io.open(os.path.join(LIB_REF, "ascii_table.json"), "w", encoding="utf-8"), ensure_ascii=False)
print("table dumped ->", os.path.join(LIB_REF, "ascii_table.json"))

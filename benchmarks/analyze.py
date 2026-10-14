import json, collections, sys, datetime

path = sys.argv[1]

reqs = collections.Counter()
bucket_counts = collections.Counter()
first = last = None

# latency bucket scheme (ms): 0.25w(0..400) + 1.0w(400..1600) + 5.0w(1600..2500) + overflow
WIDTHS = [0.25]*400 + [1.0]*1200 + [5.0]*900 + [5.0]
BUCKET_LOW, cum = [], 0.0
for w in WIDTHS:
    BUCKET_LOW.append(cum); cum += w

def bidx(ms):
    if ms < 100: return int(ms/0.25)
    if ms < 500: return 400 + int((ms-100)/1.0)
    if ms < 5000: return 1600 + int((ms-500)/5.0)
    return len(WIDTHS)-1

with open(path) as f:
    for line in f:
        o = json.loads(line)
        if o.get("type") != "Point": continue
        t = o["data"]["tags"]; m = o["metric"]
        if m == "http_reqs":
            reqs[t.get("status")] += 1
            ts = o["data"]["time"]
            first = ts if first is None else first
            last = ts
        elif m == "http_req_duration":
            bucket_counts[bidx(o["data"]["value"])] += 1

total = sum(reqs.values())
print("=== status distribution ===")
for s in sorted(reqs):
    print(f"  {s}: {reqs[s]:>12}  ({100*reqs[s]/total:.2f}%)")

try:
    fmt = "%Y-%m-%dT%H:%M:%S.%f"
    a = datetime.datetime.strptime(first[:26], fmt)
    b = datetime.datetime.strptime(last[:26], fmt)
    secs = (b-a).total_seconds()
except Exception:
    secs = 136.0
print(f"\n=== total http_reqs: {total:,} | window: {secs:.1f}s | avg throughput: {total/secs:,.0f} req/s ===")

n = sum(bucket_counts.values())
cumulative, running = [], 0
for b in range(len(WIDTHS)):
    running += bucket_counts.get(b, 0); cumulative.append(running)

def pct(p):
    target = p/100.0*n
    for i in range(len(WIDTHS)):
        if cumulative[i] >= target:
            prev = cumulative[i-1] if i>0 else 0
            span = cumulative[i]-prev
            frac = (target-prev)/span if span>0 else 0.0
            return BUCKET_LOW[i]+frac*WIDTHS[i]
    return BUCKET_LOW[-1]+WIDTHS[-1]

print("\n=== latency percentiles (ms) ===")
for p in (50,90,95,99,100):
    print(f"  p{p:>2}: {pct(p):7.2f} ms   (samples={n:,})")

fails = sum(v for k,v in reqs.items() if k not in ("200","201","204","302","304","301"))
print(f"\n=== http_req_failed: {fails} ({100*fails/total:.4f}%) | threshold was rate<0.01 ===")

import os
files = []
for root, _, fs in os.walk('src/main/java'):
    for f in fs:
        if f.endswith('.java'):
            p = os.path.join(root, f).replace(os.sep, '/')
            with open(p, encoding='utf-8', errors='replace') as fh:
                n = sum(1 for _ in fh)
            files.append((p, n))
files.sort(key=lambda x: -x[1])
total = sum(n for _, n in files)
print('total files:', len(files))
print('total lines:', total)
print('---top 30 by size---')
for p, n in files[:30]:
    print(f'{n:6d} {p}')
print('---smallest 10---')
for p, n in files[-10:]:
    print(f'{n:6d} {p}')
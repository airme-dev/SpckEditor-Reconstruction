with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

import re

matches = [m.start() for m in re.finditer(r'device\.write', text)]
for idx in matches:
    print("=== device.write at", idx, "===")
    print(text[max(0, idx-100):min(len(text), idx+300)])

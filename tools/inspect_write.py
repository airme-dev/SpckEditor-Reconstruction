with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

import re

# find writeFile in the bridge or filesystem
matches = [m.start() for m in re.finditer(r'writeFile', text)]
for idx in matches:
    print("=== writeFile at", idx, "===")
    print(text[max(0, idx-50):min(len(text), idx+300)])

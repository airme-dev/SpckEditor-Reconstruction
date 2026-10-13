with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

import re

# find definition of qf
for m in re.finditer(r'qf\s*\(', text):
    idx = m.start()
    print("=== qf at", idx, "===")
    print(text[max(0, idx-100):min(len(text), idx+600)])

# find definition of ci
for m in re.finditer(r'ci\s*\([a-zA-Z0-9_, ]*\)\s*\{', text):
    idx = m.start()
    print("=== ci at", idx, "===")
    print(text[max(0, idx-100):min(len(text), idx+600)])

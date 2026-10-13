with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

import re
matches = [m.start() for m in re.finditer(r'write\s*\([a-zA-Z0-9_, ]*\)\s*\{', text)]
print(f"Total write method defs: {len(matches)}")
for idx in matches:
    snippet = text[max(0, idx-50):min(len(text), idx+300)]
    if 'mkdir' in snippet or 'encoding' in snippet or 'device' in snippet or 'buffer' in snippet.lower():
        print("=== write def at", idx, "===")
        print(snippet)

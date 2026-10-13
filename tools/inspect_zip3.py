with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

import re

# find definition of zipper module or ir
for m in re.finditer(r'ir\s*\([a-zA-Z0-9_, ]*\)\s*\{', text):
    idx = m.start()
    snippet = text[max(0, idx-50):min(len(text), idx+300)]
    if 'zip' in snippet.lower() or 'entry' in snippet.lower() or 'file' in snippet.lower() or 'dir' in snippet.lower():
        print("=== ir at", idx, "===")
        print(snippet)

# find zipper definition
for m in re.finditer(r'initialize\("zipper"\)', text):
    idx = m.start()
    print("=== initialize(zipper) at", idx, "===")
    print(text[max(0, idx-100):min(len(text), idx+200)])

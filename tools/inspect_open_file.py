with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

import re

# find open file into editor
matches = [m.start() for m in re.finditer(r'setValue|session\.open|openFile', text)]
for idx in matches[:15]:
    snippet = text[max(0, idx-80):min(len(text), idx+200)].encode('ascii', errors='replace').decode('ascii')
    print("=== at", idx, "===")
    print(snippet)

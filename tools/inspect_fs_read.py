with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

import re
matches = [m.start() for m in re.finditer(r'read\s*\([a-zA-Z0-9_, ]*\)\s*\{', text)]
print('read method defs:', len(matches))
for m in matches:
    snippet = text[m-50:m+500]
    if 'device' in snippet or 'readFile' in snippet or 'encoding' in snippet:
        print('--- read at', m)
        print(snippet.encode('ascii', errors='replace').decode('ascii'))

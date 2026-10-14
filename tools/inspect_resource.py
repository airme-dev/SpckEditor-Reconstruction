with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

import re
matches = [m.start() for m in re.finditer(r'resource\s*\([a-zA-Z0-9_, ]*\)\s*\{', text)]
print('resource method matches:', len(matches))
for m in matches:
    print('--- at', m)
    print(text[m-50:m+250].encode('ascii', errors='replace').decode('ascii'))

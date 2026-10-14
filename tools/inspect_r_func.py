with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

import re
matches = [m.start() for m in re.finditer(r'function R_\(', text)]
print('function R_ matches:', len(matches))
for m in matches:
    print('--- at', m)
    print(text[m:m+500].encode('ascii', errors='replace').decode('ascii'))

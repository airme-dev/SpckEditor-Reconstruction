with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

import re
matches = [m.start() for m in re.finditer(r'["\']play["\']', text)]
print('play matches:', len(matches))
for m in matches:
    snippet = text[max(0, m-50):min(len(text), m+200)]
    if 'icon' in snippet or 'click' in snippet or 'run' in snippet or 'preview' in snippet:
        print('--- at', m)
        print(snippet.encode('ascii', errors='replace').decode('ascii'))

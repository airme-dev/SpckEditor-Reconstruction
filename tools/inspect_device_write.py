with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

import re
matches = [m.start() for m in re.finditer(r'device\.write', text)]
print('device.write matches:', len(matches))
for m in matches:
    print('--- at', m)
    print(text[m-100:m+300].encode('ascii', errors='replace').decode('ascii'))

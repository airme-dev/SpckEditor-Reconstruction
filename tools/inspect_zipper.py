with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

import re
matches = [m.start() for m in re.finditer(r'initialize\("zipper"\)', text)]
print('zipper matches:', len(matches))
for m in matches:
    print('--- match at', m)
    print(text[m-200:m+400].encode('ascii', errors='replace').decode('ascii'))

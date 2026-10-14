with open('app/src/main/assets/assets/bundle/vendor.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    content = f.read()

import re
# find definition of function A(
matches = [m.start() for m in re.finditer(r'function A\(', content)]
print('function A matches:', len(matches))
for m in matches:
    if 470000 <= m <= 475000:
        print('at', m)
        print(content[m:m+400].encode('ascii', errors='replace').decode('ascii'))

with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

import re
matches = [m.start() for m in re.finditer(r'useWebWorkers', text)]
print(f"Total useWebWorkers matches: {len(matches)}")
for idx in matches:
    print("=== useWebWorkers at", idx, "===")
    clean = text[max(0, idx-50):min(len(text), idx+150)].encode('ascii', errors='replace').decode('ascii')
    print(clean)

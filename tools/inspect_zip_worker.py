with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

import re
matches = [m.start() for m in re.finditer(r'workerScripts', text)]
print(f"Total workerScripts matches: {len(matches)}")
for idx in matches:
    print("=== workerScripts at", idx, "===")
    clean = text[max(0, idx-100):min(len(text), idx+300)].encode('ascii', errors='replace').decode('ascii')
    print(clean)

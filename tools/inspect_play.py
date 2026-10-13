with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

import re

# find play button action
matches = [m.start() for m in re.finditer(r'play-24|launchWebView|initServer', text)]
for idx in matches:
    snippet = text[max(0, idx-80):min(len(text), idx+250)].encode('ascii', errors='replace').decode('ascii')
    if any(k in snippet.lower() for k in ['click', 'action', 'run', 'launch', 'server', 'url', 'button']):
        print("=== at", idx, "===")
        print(snippet)

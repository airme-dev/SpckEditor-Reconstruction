with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

target = 'accept=".zip"'
idx = text.find(target)
if idx != -1:
    print("Found at:", idx)
    start = max(0, idx - 150)
    end = min(len(text), idx + 800)
    print(text[start:end])
else:
    print("Not found")

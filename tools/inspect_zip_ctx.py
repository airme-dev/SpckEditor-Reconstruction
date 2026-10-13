with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

target = 'accept=".zip"'
idx = text.find(target)
start = max(0, idx - 600)
end = min(len(text), idx + 200)
print(text[start:end].encode('ascii', errors='replace').decode('ascii'))

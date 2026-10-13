with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

idx = 448769
clean = text[idx-300:idx+600].encode('ascii', errors='replace').decode('ascii')
print(clean)

with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

idx = 627597
clean = text[idx:idx+1500].encode('ascii', errors='replace').decode('ascii')
print(clean)

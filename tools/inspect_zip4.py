with open('app/src/main/assets/assets/bundle/app.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

idx = 453820
print(text[idx-50:idx+900])

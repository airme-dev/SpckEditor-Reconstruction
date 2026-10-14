with open('app/src/main/assets/assets/bundle/vendor.bundle.js', 'r', encoding='utf-8', errors='replace') as f:
    text = f.read()

idx = 480032
f_start = text.rfind('.zip=', 0, idx)
print('.zip= at', f_start)
print(text[f_start-100:f_start+300])

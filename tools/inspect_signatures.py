import zipfile, struct

with zipfile.ZipFile('Spck Editor_10.8.1.0_io.spck.apk') as z:
    dex_data = z.read('classes2.dex')

string_ids_size, string_ids_off = struct.unpack_from('<II', dex_data, 0x38)
type_ids_size, type_ids_off = struct.unpack_from('<II', dex_data, 0x40)
proto_ids_size, proto_ids_off = struct.unpack_from('<II', dex_data, 0x48)
method_ids_size, method_ids_off = struct.unpack_from('<II', dex_data, 0x58)

def get_string(idx):
    off = struct.unpack_from('<I', dex_data, string_ids_off + idx * 4)[0]
    pos = off
    length = 0
    shift = 0
    while True:
        b = dex_data[pos]
        pos += 1
        length |= (b & 0x7f) << shift
        if not (b & 0x80): break
        shift += 7
    return dex_data[pos:pos+length].decode('latin1', errors='replace')

def get_type(idx):
    desc_idx = struct.unpack_from('<I', dex_data, type_ids_off + idx * 4)[0]
    return get_string(desc_idx)

def get_proto(idx):
    shorty_idx, return_type_idx, parameters_off = struct.unpack_from('<III', dex_data, proto_ids_off + idx * 12)
    ret = get_type(return_type_idx)
    params = []
    if parameters_off != 0:
        size = struct.unpack_from('<I', dex_data, parameters_off)[0]
        for p in range(size):
            t_idx = struct.unpack_from('<H', dex_data, parameters_off + 4 + p * 2)[0]
            params.append(get_type(t_idx))
    return f"({','.join(params)}) -> {ret}"

for i in range(method_ids_size):
    class_idx, proto_idx, name_idx = struct.unpack_from('<HHI', dex_data, method_ids_off + i * 8)
    cname = get_type(class_idx)
    if 'WebAppInterface' in cname:
        mname = get_string(name_idx)
        sig = get_proto(proto_idx)
        print(f"WebAppInterface.{mname}{sig}")

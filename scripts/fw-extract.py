#!/usr/bin/env python3
"""Extract WiFi firmware files from Android sparse ext4 vendor images (Ivy retail/debug)."""
import struct, sys, os, hashlib

def sparse_to_raw(src, dst):
    """Convert Android sparse image to raw. Minimal implementation of the sparse format."""
    with open(src, 'rb') as f:
        magic, major, minor, fhs, chs, blksz, total_blks, total_chunks, _ = struct.unpack(
            '<IHHHHIIII', f.read(28))
        if magic != 0xED26FF3A:
            # not sparse — just copy
            f.seek(0)
            with open(dst, 'wb') as o:
                while True:
                    b = f.read(1 << 20)
                    if not b: break
                    o.write(b)
            return
        f.seek(fhs)
        with open(dst, 'wb') as o:
            for _ in range(total_chunks):
                ctype, _, nblks, csz = struct.unpack('<HHII', f.read(chs)[:12])
                data_len = csz - chs
                if ctype == 0xCAC1:    # raw
                    o.write(f.read(data_len))
                elif ctype == 0xCAC2:  # fill
                    fill = f.read(4)
                    o.write(fill * (nblks * blksz // 4))
                elif ctype == 0xCAC3:  # don't care
                    o.write(b'\x00' * (nblks * blksz))
                    f.seek(data_len, 1)
                else:
                    f.seek(data_len, 1)

def extract_dir(img, want_dir, outdir):
    import ext4
    os.makedirs(outdir, exist_ok=True)
    with open(img, 'rb') as f:
        vol = ext4.Volume(f)
        try:
            d = vol.root.get_inode(*[p for p in [want_dir]][0].split('/')[0])  # not used
        except Exception:
            pass
        # walk to want_dir
        node = vol.root
        for part in want_dir.strip('/').split('/'):
            if not part: continue
            found = None
            for name, inode_idx, ftype in node.open_dir():
                if name == part:
                    found = vol.get_inode(inode_idx, ftype)
                    break
            if found is None:
                print(f"  (no {want_dir} in this image)")
                return []
            node = found
        out = []
        for name, inode_idx, ftype in node.open_dir():
            if name in ('.', '..'): continue
            inode = vol.get_inode(inode_idx, ftype)
            if inode.is_dir: continue
            data = inode.open_read().read()
            p = os.path.join(outdir, name)
            with open(p, 'wb') as o:
                o.write(data)
            out.append((name, len(data), hashlib.md5(data).hexdigest()))
        return out

if __name__ == '__main__':
    src, tag = sys.argv[1], sys.argv[2]
    raw = f'/tmp_raw_{tag}.img'  # placed next to scratch by caller cwd
    raw = os.path.join(sys.argv[3], f'raw_{tag}.img')
    if not os.path.exists(raw):
        print(f"converting {src} -> raw...")
        sparse_to_raw(src, raw)
    outdir = os.path.join(sys.argv[3], f'fw_{tag}')
    files = extract_dir(raw, '/firmware', outdir)
    for name, size, md5 in sorted(files):
        print(f"{md5}  {size:>9}  {name}")

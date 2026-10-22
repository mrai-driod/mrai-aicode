# Debugging other devices with adb over Wi-Fi

Once adb is installed in AiCode's Linux container, you can connect over wireless debugging (Android 11 and above) to another Android phone, or to the very phone AiCode is running on — no computer needed at any point.

## When to use this

- **Debugging another phone**: install apps, capture logs, take screenshots, simulate input, upload and download files.
- **Debugging this phone**: run the same operations on the phone you're using, from inside AiCode.
- Operations that require root are out of scope: adb runs as the `shell` user (uid 2000).

## Environment baseline

| Item | Value |
| --- | --- |
| System | Debian GNU/Linux 12 (bookworm) aarch64 (PRoot container) |
| adb | 34.0.5 (Debian backports; the version in the default repository is too old and lacks the `pair` command) |
| Target device | Android 11 or above (tested in this article on Android 15 and Android 16) |
| Network | When debugging another phone, both devices must be on the same LAN; debugging this phone goes through `127.0.0.1` and doesn't depend on the LAN |
| Permissions | shell (uid 2000), non-root |

## 1. Install adb

The adb version shipped in the container's repository is too old and lacks the `pair` command needed for wireless debugging, so install it from backports:

```bash
apt update
apt install -y -t bookworm-backports adb
adb version
# Should output Version 34.x
```

**Note**: adb is installed into the container's system directories, so after reinstalling the container you need to install it again, and the pairing records are lost along with it.

## 2. Pair (only once)

First, do this on the target phone:

1. Settings → About phone → tap "Build number" 7 times to enable Developer options;
2. Developer options → turn on "Wireless debugging";
3. Tap "Pair device with pairing code" — the screen will show an **IP address and port** and a **6-digit pairing code**.

It's a good idea to show the "Wireless debugging" settings page and AiCode side by side in split screen and operate while you look — as soon as the pairing dialog is switched away from or closed, the port and pairing code become invalid immediately. The pairing port can also be discovered automatically with the script at the end of this article; only the 6-digit pairing code must be read from the screen.

Back in the AiCode terminal, replace the port and pairing code with the values on the screen:

```bash
adb pair 192.168.1.23:37123 123456
# On success it outputs Successfully paired to ...
```

**Note**: the pairing port and pairing code live for a very short time; they become invalid as soon as the dialog closes, and you'll need to open it again. Once pairing succeeds, the device remembers this host, so you won't need to pair again afterwards.

## 3. Connect

```bash
adb connect 192.168.1.23:40001
adb devices -l
```

`192.168.1.23:40001` is the "IP address and port" shown on the wireless debugging home page. Note that it is not the same as the port used for pairing.

The connect port changes every time you reopen wireless debugging. You can check the phone screen again, or discover it automatically with the script at the end of this article.

## 4. Debugging this phone

When debugging the phone AiCode itself is running on, use `127.0.0.1` as the address (the container and the phone share the network, so this doesn't depend on the LAN):

```bash
adb pair 127.0.0.1:37123 123456
adb connect 127.0.0.1:40001
adb devices
```

**Note**: connecting via the phone's LAN IP also works, but the same device will then appear as two records (one `127.0.0.1`, one LAN IP). Use `adb -s <serial>` to specify the device, or `adb disconnect` to remove the extra one.

Debugging this phone is valuable because Android restricts regular apps from invoking system commands directly (`screencap`, `pm`, `dumpsys`, etc. get rejected), while running them through adb as the `shell` user is not subject to that restriction.

## Common operations

```bash
adb shell getprop ro.product.model      # Device model
adb shell pm list packages              # Installed apps
adb shell logcat -d                     # Dump current logs
adb exec-out screencap -p > screen.png  # Take a screenshot
adb push local.txt /sdcard/             # Upload a file
adb pull /sdcard/photo.jpg ./           # Download a file
adb shell input keyevent KEYCODE_HOME   # Simulate a key press
```

Install apps with `adb install app.apk`. On some devices you also need to allow "Install apps via USB" in Developer options.

All of these commands run as the `shell` user. They can read and write `/sdcard`, but cannot access restricted directories such as `/data/data`.

## Troubleshooting

**Error: `protocol fault (couldn't read status message)`**

The most common cause is that the pairing port has expired (the dialog was closed or reopened). Reopen the dialog and pair again with the new port and pairing code. If you're sure the port is current and it still errors, just retry the same command — some adb versions have this known issue, and the second attempt often succeeds.

**Error: `failed to connect to ...: Connection refused`**

The connect port has expired, or the device hasn't been paired yet. Check the port on the wireless debugging home page again, and re-pair if necessary.

**Every new terminal needs to connect again**

The adb service exits when the terminal session ends, so a new session needs to run `adb connect` again. Just run `adb start-server`, `adb connect`, and the follow-up commands in the same terminal.

**`adb mdns services` returns an empty list**

The mDNS discovery built into adb can't work inside the container — the port 5353 it needs is already occupied by the phone system's mDNS service. This doesn't affect manual connections; for automatic discovery, use the script below.

## Appendix: automatic port discovery

Once wireless debugging is on, the device broadcasts `_adb-tls-pairing._tcp` (the pairing service, only while the pairing dialog is open) and `_adb-tls-connect._tcp` (the connect service) on the LAN. Save the script below as `discover_adb.py`; when run, it lists the "IP:port" of each:

```python
#!/usr/bin/env python3
"""Scan the LAN for adb wireless debugging endpoints"""
import socket
import struct
import time

SERVICES = {
    'Pairing port': '_adb-tls-pairing._tcp.local',
    'Connect port': '_adb-tls-connect._tcp.local',
}


def encode(name):
    out = b''
    for part in name.split('.'):
        out += bytes([len(part)]) + part.encode()
    return out + b'\x00'


def read_name(data, off):
    labels, jumped, start = [], False, off
    while True:
        length = data[off]
        if length == 0:
            off += 1
            break
        if length & 0xC0 == 0xC0:
            ptr = struct.unpack('!H', data[off:off + 2])[0] & 0x3FFF
            if not jumped:
                start = off + 2
            off, jumped = ptr, True
            continue
        labels.append(data[off + 1:off + 1 + length].decode())
        off += 1 + length
    return '.'.join(labels), (start if jumped else off)


def scan(service, seconds=6):
    # Setting the top bit of the class requests a unicast response: the pairing service only
    # answers regular queries with multicast, which you won't receive without binding 5353;
    # the connect service replies with unicast
    query = (struct.pack('!HHHHHH', 0, 0, 1, 0, 0, 0)
             + encode(service)
             + struct.pack('!HH', 12, 0x8001))
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                    struct.pack('4s4s', socket.inet_aton('224.0.0.251'),
                                socket.inet_aton('0.0.0.0')))
    sock.bind(('', 0))
    sock.settimeout(0.3)
    endpoints = set()
    deadline = time.time() + seconds
    while time.time() < deadline:
        sock.sendto(query, ('224.0.0.251', 5353))
        try:
            data, addr = sock.recvfrom(9000)
        except socket.timeout:
            continue
        qd, an, ns, ar = struct.unpack('!HHHH', data[4:12])
        off = 12
        for _ in range(qd):
            _, off = read_name(data, off)
            off += 4
        for _ in range(an + ns + ar):
            name, off = read_name(data, off)
            rtype, _, _, rdlen = struct.unpack('!HHIH', data[off:off + 10])
            off += 10
            rdata = data[off:off + rdlen]
            off += rdlen
            if rtype == 33 and service.split('.')[0] in name:
                port = struct.unpack('!HHH', rdata[:6])[2]
                endpoints.add(f'{addr[0]}:{port}')
    sock.close()
    return endpoints


for label, service in SERVICES.items():
    for endpoint in sorted(scan(service)):
        print(f'{label}  {endpoint}')
```

Usage:

```bash
python3 discover_adb.py
# Pairing port  192.168.1.23:37123
# Connect port  192.168.1.23:40001
adb pair 192.168.1.23:37123 123456
adb connect 192.168.1.23:40001
```

**Note**: the pairing service only broadcasts while the "Pair device with pairing code" dialog is open, so run the script while the dialog is open; the 6-digit pairing code always has to be read from the phone screen. The list may contain multiple results (including stale ports from before a device reboot) — just try `adb connect` on each one.

# Android companion web app

1. Connect the tablet and phone to the same trusted Wi-Fi or Ethernet network.
2. On the tablet, unlock Settings with the parent PIN. Enable Companion and
   keep port 8420 unless it conflicts with another device service.
3. Scan the pairing QR from the phone and open the displayed address. The
   browser stores this pairing. Pair a second phone the same way.
4. To revoke existing pairings, select **Unpair all** in Settings and pair the
   phones you still want to use. If the router assigns a new tablet IP,
   pair the phone at the new origin. A router DHCP reservation keeps the URL stable.

The app serves the original mobile React bundle and its allowlisted RPC routes.
Tablet and phone changes use the same SQLite database and service validation.
A companion bearer is a parent credential. Only its hash is stored on the
tablet. Failed requests are rate limited, and the server caps request sizes and
the number of workers. Android displays a foreground-service notification while
companion is enabled.

The local URL uses HTTP, like the desktop kiosk. Use private networks you
control. Other devices with access to the local traffic can see unencrypted
requests. Keep the port off public networks and router port forwarding. The
listener accepts requests addressed to the tablet's current private LAN IP on
Wi-Fi or Ethernet.

## Acceptance test

With a second physical device, load the URL and verify that requests without a
pairing are rejected. Pair, create a grocery item on the phone and check it on the tablet. Create a
calendar event or complete a chore on the tablet, then refresh the phone. The
companion calendar is read-only, as on Linux.
Revoke all pairings and confirm the previous browser receives a pairing prompt.
Re-pair, lock the tablet screen, and repeat after 15 minutes, a Wi-Fi disconnect
and reconnect, and a tablet restart. Record the installed Android version and
Samsung battery-management settings. If you enable managed kiosk startup, test
both a clean reboot and a recovery after Wi-Fi returns.

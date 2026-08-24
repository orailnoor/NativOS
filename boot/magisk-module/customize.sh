#!/system/bin/sh
# NativOS Boot Animation — Magisk Module Install Script
#
# This module replaces /system/media/bootanimation.zip systemlessly.
# The original boot animation is preserved and restored if the module is removed.

MODPATH="${0%/*}"

ui_print "╔══════════════════════════════════════════╗"
ui_print "║     NativOS Boot Animation Installer     ║"
ui_print "╚══════════════════════════════════════════╝"
ui_print ""

# Verify bootanimation.zip exists in module
if [ ! -f "$MODPATH/system/media/bootanimation.zip" ]; then
    ui_print "[!] bootanimation.zip not found in module!"
    ui_print "    Build it first: see boot/animation-source/README"
    abort "Installation failed"
fi

ui_print "[+] Boot animation will be replaced on next reboot"
ui_print "[+] Remove this module in Magisk to restore the original"
ui_print ""

# Set permissions
set_perm_recursive "$MODPATH/system" 0 0 0755 0644

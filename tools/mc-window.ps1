# Window / input primitives for the Minecraft dev client (ASCII-only on purpose).
#
# ASCII-only is deliberate: Windows PowerShell 5.1 reads .ps1 files as ANSI unless
# they have a BOM, and a mangled multibyte sequence can swallow the following
# newline (that bug cost an hour once: the struct attribute ended up inside a
# comment). Keep every string in this file ASCII.
#
# Usage:
#   iex (Get-Content ".\tools\mc-window.ps1" -Raw -Encoding utf8)
#   $h = [McWin]::Find("Minecraft* 1.21.1")
#   [McWin]::Rect($h)        # physical pixels (call SetProcessDPIAware first!)
#   [McWin]::Pid($h)
#
# Never match on plain "*Minecraft*": another Minecraft (e.g. a Forge 1.20.1
# instance someone else left running) would match too, and you would screenshot
# or kill the wrong game.
param()

Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public class McWin {
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc cb, IntPtr l);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowTextW(IntPtr h, StringBuilder sb, int n);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);

    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
    [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint code, uint mapType);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);

    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }

    /** Every visible window title seen by the last Find() call (for diagnostics). */
    public static List<string> Titles = new List<string>();

    /** All visible windows whose title contains `like`, in enumeration order. */
    public static IntPtr[] FindAll(string like) {
        var hits = new List<IntPtr>();
        EnumWindows(delegate(IntPtr h, IntPtr l) {
            if (!IsWindowVisible(h)) return true;
            var sb = new StringBuilder(512);
            GetWindowTextW(h, sb, 512);
            if (sb.ToString().IndexOf(like, StringComparison.OrdinalIgnoreCase) >= 0) hits.Add(h);
            return true;
        }, IntPtr.Zero);
        return hits.ToArray();
    }

    public static IntPtr Find(string like) {
        Titles = new List<string>();
        IntPtr found = IntPtr.Zero;
        EnumWindows(delegate(IntPtr h, IntPtr l) {
            if (!IsWindowVisible(h)) return true;
            var sb = new StringBuilder(512);
            GetWindowTextW(h, sb, 512);
            string t = sb.ToString();
            if (t.Length > 0) Titles.Add(t);
            if (t.IndexOf(like, StringComparison.OrdinalIgnoreCase) >= 0 && found == IntPtr.Zero) found = h;
            return true;
        }, IntPtr.Zero);
        return found;
    }

    /** Physical-pixel rect. Call SetProcessDPIAware() first or you get logical pixels. */
    public static int[] Rect(IntPtr h) {
        RECT r;
        if (!GetWindowRect(h, out r)) return new int[]{0,0,0,0};
        return new int[]{r.Left, r.Top, r.Right - r.Left, r.Bottom - r.Top};
    }

    /** Owning process id. Use this to close the client instead of killing by name. */
    public static uint Pid(IntPtr h) {
        uint pid;
        GetWindowThreadProcessId(h, out pid);
        return pid;
    }

    const uint SWP_NOSIZE = 0x0001;
    const uint SWP_NOMOVE = 0x0002;
    static readonly IntPtr HWND_TOPMOST = new IntPtr(-1);
    static readonly IntPtr HWND_NOTOPMOST = new IntPtr(-2);

    public static void Focus(IntPtr h) {
        ShowWindow(h, 9);                 // SW_RESTORE
        SetWindowPos(h, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOSIZE | SWP_NOMOVE);
        SetWindowPos(h, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOSIZE | SWP_NOMOVE);
        SetForegroundWindow(h);
        BringWindowToTop(h);
    }

    public static string Key(IntPtr h, uint vk) {
        uint sc = MapVirtualKey(vk, 0);
        IntPtr down = (IntPtr)(long)((sc << 16) | 1);
        IntPtr up = (IntPtr)(long)((sc << 16) | 0xC0000001);
        bool a = PostMessage(h, 0x0100, (IntPtr)(long)vk, down);   // WM_KEYDOWN
        System.Threading.Thread.Sleep(80);
        bool b = PostMessage(h, 0x0101, (IntPtr)(long)vk, up);      // WM_KEYUP
        return "vk=" + vk + " down=" + a + " up=" + b;
    }

    public static string Char(IntPtr h, char c) {
        bool a = PostMessage(h, 0x0102, (IntPtr)(long)c, IntPtr.Zero);   // WM_CHAR
        return "char=" + c + " ok=" + a;
    }

    static IntPtr Lp(int x, int y) { return (IntPtr)(long)((y << 16) | (x & 0xFFFF)); }

    public static string Mouse(IntPtr h, string what, int clientX, int clientY) {
        IntPtr lp = Lp(clientX, clientY);
        if (what == "left") {
            PostMessage(h, 0x0200, IntPtr.Zero, lp);
            System.Threading.Thread.Sleep(40);
            PostMessage(h, 0x0201, (IntPtr)1, lp);
            System.Threading.Thread.Sleep(70);
            PostMessage(h, 0x0202, IntPtr.Zero, lp);
        } else {
            PostMessage(h, 0x0200, IntPtr.Zero, lp);
            System.Threading.Thread.Sleep(40);
            PostMessage(h, 0x0204, (IntPtr)2, lp);
            System.Threading.Thread.Sleep(70);
            PostMessage(h, 0x0205, IntPtr.Zero, lp);
        }
        return what + " @ " + clientX + "," + clientY;
    }
}
'@ -ErrorAction Stop

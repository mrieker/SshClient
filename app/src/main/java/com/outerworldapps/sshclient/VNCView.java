/**
 * Access a remote host via VNC protocol tunneled via SSH.
 */

//    Copyright (C) 2015, Mike Rieker, Beverly, MA USA
//    www.outerworldapps.com
//
//    This program is free software; you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation; version 2 of the License.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    EXPECT it to FAIL when someone's HeALTh or PROpeRTy is at RISk.
//
//    You should have received a copy of the GNU General Public License
//    along with this program; if not, write to the Free Software
//    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
//    http://www.gnu.org/licenses/gpl-2.0.html

package com.outerworldapps.sshclient;


import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jcraft.jsch.DirectTCPIPTunnel;
import com.jcraft.jsch.Session;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

@SuppressWarnings("CharsetObjectCanBeUsed")
@SuppressLint({ "SetTextI18n", "ViewConstructor" })
public class VNCView extends KeyboardableView implements SshClient.HasMainMenu {
    private final static String TAG = "SshClient";

    public final static byte MOUSE_BUTTON_LEFT   = 1;
    @SuppressWarnings("unused")
    public final static byte MOUSE_BUTTON_MIDDLE = 2;
    public final static byte MOUSE_BUTTON_RIGHT  = 4;

    private final static boolean useBpp16     = true;
    private final static long longPressDelay  = 2000;
    private final static long msBtwnUpdates   = 128;
    private final static long shortPressDelay = 500;

    private final Object promptLock = new Object ();
    private final Object tunnelLock = new Object ();

    private Bitmap    bitmap;
    private boolean   ctrlkeyflag;
    private byte      lastMouseToHostM;
    private int       updreqpend;
    private int       vncPortNumber;
    private MySession session;
    private Rect      bitmapRect = new Rect ();   // what host has for desktop size; not changed by local scaling/translating
    private RectF     canvasRect = new RectF ();  // where the bitmapRect edges map on the canvas; changed by local scaling/translating
    private SshClient sshclient;
    private short     lastMouseToHostX;
    private short     lastMouseToHostY;
    private String    vncPassword;
    private Viewer    viewer;
    private VNCChan   vncChan;

    private static MyHandler myHandler;

    public VNCView (MySession s)
    {
        super (s.getSshClient ());
        session   = s;
        sshclient = s.getSshClient ();

        if (myHandler == null) {
            myHandler = new MyHandler ();
        }

        viewer = new Viewer ();
        SetEditor (viewer);
        LoadSettings ();
    }

    /**
     * Display menu appropriate for the VNC Viewer.
     */
    @Override  // HasMainMenu
    public void MakeMainMenu ()
    {
        String conStr;
        Runnable conRun;
        if (vncChan == null) {
            conStr = "Connect";
            conRun = new Runnable () {
                public void run ()
                {
                    synchronized (tunnelLock) {
                        if (vncChan == null) {
                            StartVNCConnection ();
                        }
                    }
                }
            };
        } else {
            conStr = "Discon";
            conRun = new Runnable () {
                public void run ()
                {
                    synchronized (tunnelLock) {
                        if (vncChan != null) {
                            vncChan.Close ();
                        }
                    }
                }
            };
        }

        sshclient.SetMMenuItems (
                "Ctrl-...", new Runnable () {
                    public void run ()
                    {
                        ctrlkeyflag = true;
                    }
                },
                "Ctrl-C", new Runnable () {
                    public void run ()
                    {
                        ctrlkeyflag = false;
                        SendCtrlCharToHost ((char) 3);
                    }
                },
                conStr, conRun,
                "Paste", new Runnable () {
                    public void run ()
                    {
                        sshclient.PasteFromClipboard (new SshClient.PFC () {
                            @Override
                            public void run (String str)
                            {
                                SendPasteToHost (str);
                            }
                        });
                    }
                },
                "Tab", new Runnable () {
                    public void run ()
                    {
                        ctrlkeyflag = false;
                        SendCtrlCharToHost ((char) 9);
                    }
                });
    }

    /**
     * Select keyboard from settings file.
     */
    public void LoadSettings ()
    {
        Settings settings = sshclient.getSettings ();
        boolean usepckb = settings.pc_keybd.GetValue ();
        SelectKeyboard (usepckb);
    }
    @Override  // KeyboardableView
    protected View GetAltKeyboard ()
    {
        return new PeeCeeKBView (session, this);
    }

    /**
     * Prompt to start connection if we receive focus and aren't already connected.
     */
    @Override  // KeyboardableView
    public void onFocusChange (View v, boolean focus)
    {
        super.onFocusChange (v, focus);

        synchronized (tunnelLock) {
            if (focus && (vncChan == null)) {
                StartVNCConnection ();
            }
        }
    }

    /**
     * Start the VNCChan thread and it will prompt user for port and password
     * then connect and process messages from the server.
     */
    private void StartVNCConnection ()
    {
        // thread startup requests initial contents
        // so don't ask for updates until we receive it
        updreqpend = 2;

        // start tunnel and connect in background
        // thread also processes messages from server
        vncChan = new VNCChan ();
        vncChan.start ();

        // set up Discon menu item
        MakeMainMenu ();
    }

    /**
     * Send keyboard characters to the host.
     *
     * @param chr = ascii/utf-8 character to send
     */
    public void SendPrintableToHost (char chr)
    {
        synchronized (tunnelLock) {
            if (bitmap != null) {
                vncChan.WriteU8  ((byte) 4);
                vncChan.WriteU8  ((byte) 1);
                vncChan.WriteU16 ((short) 0);
                vncChan.WriteU32 (chr);

                vncChan.WriteU8  ((byte) 4);
                vncChan.WriteU8  ((byte) 0);
                vncChan.WriteU16 ((short) 0);
                vncChan.WriteU32 (chr);

                vncChan.WriteFlush ();
            }
        }
    }

    /**
     * Send single keypress event to the host
     *
     * @param code = key code (/usr/include/X11/keysymdef.h)
     * @param down = 0: key up; 1: key down
     */
    public void SendCodeToHost (int code, int down)
    {
        synchronized (tunnelLock) {
            if (bitmap != null) {
                vncChan.WriteU8  ((byte) 4);
                vncChan.WriteU8  ((byte) down);
                vncChan.WriteU16 ((short) 0);
                vncChan.WriteU32 (code);
                vncChan.WriteFlush ();
            }
        }
    }

    /**
     * Send control character to host.
     *
     * @param chr = either letter char or control char
     */
    private void SendCtrlCharToHost (char chr)
    {
        synchronized (tunnelLock) {
            if (bitmap != null) {
                SendCtrlCharLocked (chr);
                vncChan.WriteFlush ();
            }
        }
    }

    private void SendCtrlCharLocked (char chr)
    {
        chr = (char) ((chr & 0x1F) + 0x60);

        vncChan.WriteU8  ((byte) 4);   // key event
        vncChan.WriteU8  ((byte) 1);   // key down
        vncChan.WriteU16 ((short) 0);  // padding
        vncChan.WriteU32 (0xFFE3);     // control-left

        vncChan.WriteU8  ((byte) 4);   // key event
        vncChan.WriteU8  ((byte) 1);   // key down
        vncChan.WriteU16 ((short) 0);  // padding
        vncChan.WriteU32 (chr);        // corresponding letter key

        vncChan.WriteU8  ((byte) 4);   // key event
        vncChan.WriteU8  ((byte) 0);   // key up
        vncChan.WriteU16 ((short) 0);  // padding
        vncChan.WriteU32 (chr);        // corresponding letter key

        vncChan.WriteU8  ((byte) 4);   // key event
        vncChan.WriteU8  ((byte) 0);   // key up
        vncChan.WriteU16 ((short) 0);  // padding
        vncChan.WriteU32 (0xFFE3);     // control-left
    }

    /**
     * Send mouse click to the host.
     *
     * @param buttonMask = mouse buttons that are down
     * @param xPos       = absolute X bitmap position
     * @param yPos       = absolute Y bitmap position
     */
    public void SendMouseToHost (byte buttonMask, short xPos, short yPos)
    {
        if ((buttonMask != lastMouseToHostM) || (xPos != lastMouseToHostX) || (yPos != lastMouseToHostY)) {
            if ((xPos >= bitmapRect.left) && (xPos <= bitmapRect.right) &&
                    (yPos >= bitmapRect.top) && (yPos <= bitmapRect.bottom)) {
                synchronized (tunnelLock) {
                    if (bitmap != null) {
                        vncChan.WriteU8 ((byte) 5);
                        vncChan.WriteU8 (buttonMask);
                        vncChan.WriteU16 (xPos);
                        vncChan.WriteU16 (yPos);
                        vncChan.WriteFlush ();
                    }
                }
                lastMouseToHostM = buttonMask;
                lastMouseToHostX = xPos;
                lastMouseToHostY = yPos;
            }
        }
    }

    /**
     * Send paste string to host.
     */
    public void SendPasteToHost (String str)
    {
        byte[] utf8;
        try {
            utf8 = str.getBytes ("UTF-8");
        } catch (UnsupportedEncodingException uee) {
            throw new RuntimeException (uee);
        }
        synchronized (tunnelLock) {
            if (bitmap != null) {
                vncChan.WriteU8 ((byte) 6);
                vncChan.WriteU8 ((byte) 0);
                vncChan.WriteU8 ((byte) 0);
                vncChan.WriteU8 ((byte) 0);
                vncChan.WriteU32 (utf8.length);
                for (byte b : utf8) {
                    vncChan.WriteU8 (b);
                }
                vncChan.WriteFlush ();
            }
        }
    }

    /**
     * Display error message.  Can be called from any thread.
     * @param msg = message to be displayed
     */
    private void DisplayError (String msg)
    {
        Object[] prms = new Object[] { sshclient, msg, null };
        Message m = myHandler.obtainMessage ();
        m.what = MH_ALERT;
        m.obj  = prms;
        m.sendToTarget ();
    }

    /**
     * Map canvas pixels to bitmap pixels.
     */
    private float Canvas2BitmapX (float canvasx)
    {
        int bl = bitmapRect.left;
        float cl = canvasRect.left;
        return (canvasx - cl) / (canvasRect.right - cl) * (bitmapRect.right - bl) + bl;
    }

    private float Canvas2BitmapY (float canvasy)
    {
        int bt = bitmapRect.top;
        float ct = canvasRect.top;
        return (canvasy - ct) / (canvasRect.bottom - ct) * (bitmapRect.bottom - bt) + bt;
    }

    private static byte SwapBits (byte b)
    {
        return (byte) (
                ((b << 7) & 0x80) |
                        ((b << 5) & 0x40) |
                        ((b << 3) & 0x20) |
                        ((b << 1) & 0x10) |
                        ((b >> 1) & 0x08) |
                        ((b >> 3) & 0x04) |
                        ((b >> 5) & 0x02) |
                        ((b >> 7) & 0x01));
    }

    private static class Pointer {
        public int id;
        public float canvasLastX, canvasLastY;
        public float bitmapStartX, bitmapStartY;
        public float canvasStartX, canvasStartY;
    }

    /**
     * Widget to wrap the host screen bitmap image.
     * Also contains the Android keyboard (when active) and handles mouse/touch events.
     */
    private class Viewer extends EditText implements TextWatcher {
        private boolean readingkb;
        private boolean seenTwoFingers;

        private float bitmapStartMidX;  // when two fingers down, midpoint in bitmap pixels between fingers when starting
        private float bitmapStartMidY;
        private float canvasStartDist;  // when two fingers down, distance in canvas pixels between fingers when starting
        private long mouseDownAt;       // time first finger went down
        private Pointer firstPointer;   // first finger down data
        private Pointer secondPointer;  // second finger down data
        private RectF startCanvasRect;  // canvasRect when second finger went down
        private short longPressX;       // bitmap pixel of first finger down location
        private short longPressY;

        public Viewer ()
        {
            super (sshclient);

            setBackgroundColor (Color.BLACK);
            setTextSize (SshClient.UNIFORM_TEXT_SIZE);
            addTextChangedListener (this);
            setInputType (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            setImeOptions (EditorInfo.IME_ACTION_NONE);
            requestFocus ();
        }

        // process enter and delete keys
        @Override  // View
        public boolean dispatchKeyEvent (@NonNull KeyEvent ke)
        {
            if (ke.getAction () == KeyEvent.ACTION_DOWN) {
                switch (ke.getKeyCode ()) {
                    case KeyEvent.KEYCODE_ENTER: {
                        // X11/keysymdef.h XK_Return
                        ctrlkeyflag = false;
                        SendPrintableToHost ('\uFF0D');
                        return true;
                    }
                    case KeyEvent.KEYCODE_DEL: {
                        // X11/keysymdef.h XK_BackSpace
                        ctrlkeyflag = false;
                        SendPrintableToHost ('\uFF08');
                        return true;
                    }
                }
            }
            return super.dispatchKeyEvent (ke);
        }

        /*
         * All this bologna to capture printable keys...
         *
         * TextWatcher sequence is:
         *   beforeTextChanged() once
         *   onTextChanged() twice!
         *   afterTextChanged() once
         */

        @Override  // TextWatcher
        public void beforeTextChanged (CharSequence s, int start, int count, int after)
        { }

        @Override  // TextWatcher
        public void onTextChanged (CharSequence s, int start, int before, int count)
        { }

        @Override  // TextWatcher
        public void afterTextChanged (Editable s)
        {
            if (!readingkb) {
                readingkb = true;
                String str = s.toString ();
                s.replace (0, str.length (), "", 0, 0);
                for (char chr : str.toCharArray ()) {
                    if (ctrlkeyflag && (chr >= 0x40) && (chr <= 0x7F)) {
                        SendCtrlCharToHost (chr);
                    } else {
                        SendPrintableToHost (chr);
                    }
                    ctrlkeyflag = false;
                }
                readingkb = false;
            }
        }

        /**
         * Draw current bitmap to the canvas then request an update from the host.
         */
        @Override
        public void onDraw (@NonNull Canvas canvas)
        {
            if (vncChan == null) {
                int y = getLineHeight ();
                Paint p = getPaint ();
                p.setColor (Color.WHITE);
                //noinspection PointlessArithmeticExpression
                canvas.drawText ("Click menu button", 10, y * 1 + 10, p);
                canvas.drawText ("   then Connect", 10, y * 2 + 10, p);
                canvas.drawText ("   to connect", 10, y * 3 + 10, p);
            } else if (bitmap != null) {

                // drawr bitmap to canvas using current scaling/translation
                canvas.drawBitmap (bitmap, bitmapRect, canvasRect, null);

                // drawr the screen name string below the bitmap
                int y = getLineHeight ();
                Paint p = getPaint ();
                p.setColor (Color.GREEN);
                canvas.drawText (vncChan.nameString, 10, canvasRect.bottom + y + 10, p);

                // if we have received an update since we last sent a request,
                // send a new request after a short delay
                if (updreqpend == 0) {
                    updreqpend = 1;
                    long now = SystemClock.uptimeMillis ();
                    long delay = msBtwnUpdates - (now % msBtwnUpdates);
                    Message m = myHandler.obtainMessage (MH_REQUPDT, VNCView.this);
                    myHandler.sendMessageDelayed (m, delay);
                }
            }
        }

        /**
         * Handle touch events.
         */
        @SuppressLint("ClickableViewAccessibility")
        @Override  // View
        public boolean onTouchEvent (@NonNull MotionEvent event)
        {
            switch (event.getActionMasked ()) {
                case MotionEvent.ACTION_DOWN: {
                    mouseDownAt = SystemClock.uptimeMillis ();
                    Message m = myHandler.obtainMessage (MH_RTMOUSE, VNCView.this);
                    myHandler.sendMessageDelayed (m, longPressDelay);
                    longPressX = (short) Canvas2BitmapX (event.getX ());
                    longPressY = (short) Canvas2BitmapY (event.getY ());
                    seenTwoFingers = false;
                    // fall through
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int i = event.getActionIndex ();
                    int id = event.getPointerId (i);
                    float x = event.getX (i);
                    float y = event.getY (i);
                    if (firstPointer == null) {
                        firstPointer = new Pointer ();
                        firstPointer.id = id;
                        firstPointer.canvasStartX = x;
                        firstPointer.canvasStartY = y;
                        firstPointer.bitmapStartX = Canvas2BitmapX (x);
                        firstPointer.bitmapStartY = Canvas2BitmapY (y);
                    } else if (secondPointer == null) {
                        secondPointer = new Pointer ();
                        secondPointer.id = id;
                        secondPointer.canvasStartX = x;
                        secondPointer.canvasStartY = y;
                        secondPointer.bitmapStartX = Canvas2BitmapX (x);
                        secondPointer.bitmapStartY = Canvas2BitmapY (y);

                        startCanvasRect = new RectF (canvasRect);
                        bitmapStartMidX = (firstPointer.bitmapStartX + secondPointer.bitmapStartX) / 2;
                        bitmapStartMidY = (firstPointer.bitmapStartY + secondPointer.bitmapStartY) / 2;
                        canvasStartDist = (float) Math.hypot (
                                secondPointer.canvasStartX - firstPointer.canvasStartX,
                                secondPointer.canvasStartY - firstPointer.canvasStartY);

                        // two fingers down disables sending mouse down stuff to host
                        seenTwoFingers = true;
                    }
                    break;
                }

                case MotionEvent.ACTION_UP: {

                    // a quick tap is a left mouse button down and up
                    long now = SystemClock.uptimeMillis ();
                    if (!seenTwoFingers && (now - shortPressDelay < mouseDownAt)) {
                        short x = (short) Canvas2BitmapX (event.getX ());
                        short y = (short) Canvas2BitmapY (event.getY ());
                        SendMouseToHost (MOUSE_BUTTON_LEFT, x, y);
                    }

                    // make sure we don't send a long press down
                    mouseDownAt = Long.MAX_VALUE;

                    // if there were intervening drags and/or a long click,
                    // make sure host knows the button(s) are all up now
                    if (lastMouseToHostM != 0) {
                        SendMouseToHost ((byte) 0, lastMouseToHostX, lastMouseToHostY);
                    }

                    // fall through
                }
                case MotionEvent.ACTION_POINTER_UP: {
                    int i = event.getActionIndex ();
                    int id = event.getPointerId (i);
                    if ((firstPointer != null) && (firstPointer.id == id)) firstPointer = null;
                    if ((secondPointer != null) && (secondPointer.id == id)) secondPointer = null;
                    break;
                }

                /*
                 * If single finger move, send those to host as left-button down messages
                 * If double finger move, process locally as translate/scale operation
                 */
                case MotionEvent.ACTION_MOVE: {

                    // try to collect up to two known pointers
                    // we should have received ACTION_DOWN/ACTION_POINTER_DOWN for them already
                    Pointer thisptr = null;
                    Pointer thatptr = null;
                    int n = event.getPointerCount ();
                    for (int i = 0; i < n; i++) {
                        int id = event.getPointerId (i);
                        float x = event.getX (i);
                        float y = event.getY (i);
                        Pointer p = null;
                        if ((firstPointer != null) && (firstPointer.id == id)) p = firstPointer;
                        if ((secondPointer != null) && (secondPointer.id == id)) p = secondPointer;
                        if (p != null) {
                            if (thisptr == null) {

                                // one finger found, save latest x,y
                                thisptr = p;
                                p.canvasLastX = x;
                                p.canvasLastY = y;
                            } else if (thatptr == null) {

                                // another finger found, save latest x,y
                                thatptr = p;
                                p.canvasLastX = x;
                                p.canvasLastY = y;
                            }
                        }
                    }
                    if (thisptr != null) {

                        // if we only have one finger down, it is an host drag
                        // so send the events to the host, provided we haven't seen two fingers
                        if (!seenTwoFingers) {
                            short x = (short) Canvas2BitmapX (thisptr.canvasLastX);
                            short y = (short) Canvas2BitmapY (thisptr.canvasLastY);
                            SendMouseToHost (MOUSE_BUTTON_LEFT, x, y);
                        }

                        // two fingers down is a local drag/scale
                        // scale by the ratio of new to start distances between fingers
                        // translate such that starting bitmap midpoint matches new canvas midpoint
                        if (thatptr != null) {
                            float canvasNewDist = (float) Math.hypot (
                                    thisptr.canvasLastX - thatptr.canvasLastX,
                                    thisptr.canvasLastY - thatptr.canvasLastY);
                            float zoomInBy = canvasNewDist / canvasStartDist;
                            float canvasNewWidth = startCanvasRect.width () * zoomInBy;
                            float canvasNewHeight = startCanvasRect.height () * zoomInBy;
                            float canvasNewMidX = (thisptr.canvasLastX + thatptr.canvasLastX) / 2;
                            float canvasNewMidY = (thisptr.canvasLastY + thatptr.canvasLastY) / 2;
                            float canvasNewLeft = (bitmapRect.left - bitmapStartMidX) / bitmapRect.width () * canvasNewWidth + canvasNewMidX;
                            float canvasNewTop = (bitmapRect.top - bitmapStartMidY) / bitmapRect.height () * canvasNewHeight + canvasNewMidY;

                            if (canvasNewLeft > 0) canvasNewLeft = 0;
                            if (canvasNewTop > 0) canvasNewTop = 0;

                            canvasRect.left = canvasNewLeft;
                            canvasRect.right = canvasNewLeft + canvasNewWidth;
                            canvasRect.top = canvasNewTop;
                            canvasRect.bottom = canvasNewTop + canvasNewHeight;

                            // re-draw screen with that transform
                            invalidate ();
                        }
                    }
                    break;
                }

                case MotionEvent.ACTION_CANCEL: {
                    firstPointer = null;
                    secondPointer = null;
                    mouseDownAt = Long.MAX_VALUE;
                    break;
                }
            }

            // get Anroid keyboard to appear when tapped on
            return super.onTouchEvent (event);
        }
    }

    /**
     * Connect to the host via a temporary tunnel then process messages from the host.
     */
    private class VNCChan extends Thread implements DirectTCPIPTunnel.Recv {
        private int[] palette = new int[128];
        private int[] pixelRow;
        private short framebufferHeight;
        private short framebufferWidth;
        public  String nameString;

        private boolean zrleactive;
        private byte[] readbuf;
        private byte[] writebuf;
        private DirectTCPIPTunnel tunnel;
        private InputStream gzipis;
        private int readend;
        private int readidx;
        private int writebeg;
        private int writeend;
        private int writeidx;
        private ZRLEInputStream zrleis;

        @Override  // Thread
        public void run ()
        {
            try {
                do {
                    FullClose ();
                    readidx  = 0;
                    readend  = 0;
                    writebeg = 0;
                    writeidx = 0;
                    writeend = 0;
                } while (!OpenTunnel () ||
                         !ExchangeVersionNumbers () ||
                         !Authenticate ());

                ExchangeInitMessages ();
                ProcessIncomingMessages ();
            } catch (InterruptedException ie) {
                Log.w (TAG, "VNC receive error", ie);
            } catch (Throwable e) {
                Log.w (TAG, "VNC receive error", e);
                if (writeend >= 0) {
                    String msg = e.getMessage ();
                    if (msg == null) msg = e.getClass ().toString ();
                    DisplayError ("Error receiving: " + msg);
                }
            } finally {
                FullClose ();
                myHandler.obtainMessage (MH_DISCOD, VNCView.this).sendToTarget ();
            }
        }

        /**
         * Open tunnel to server and connect.
         */
        private boolean OpenTunnel ()
                throws InterruptedException
        {
            // prompt user for port number and wait for response
            // exit if user cancels
            if (vncPortNumber == 0) {
                myHandler.obtainMessage (MH_PORTNO, VNCView.this).sendToTarget ();
                synchronized (promptLock) {
                    while (vncPortNumber == 0) {
                        promptLock.wait ();
                    }
                }
            }

            // create an ssh tunnel to the remote host
            // re-prompt port number if error setting up tunnel
            try {
                Session jsession = session.getScreendatathread ().jsession;
                tunnel = new DirectTCPIPTunnel (jsession, "localhost", vncPortNumber, this);
            } catch (Exception e) {
                String msg = e.getMessage ();
                if (msg == null) msg = e.getClass ().toString ();
                DisplayErrorWait ("Error tunneling to port " + vncPortNumber + ": " + msg);
                vncPortNumber = 0;
                return false;
            }

            return true;
        }

        /**
         * Exchane version numbers with the server.
         */
        private boolean ExchangeVersionNumbers ()
        {
            try {
                // synchronously fill first time
                // to force gotData() to block
                // so it can't change read{buf,idx,end} on us
                ReadFill ();
                // now that gotData() is blocked,
                // read{buf,idx,end} are stable

                String serverVersion = ReadLine ();
                if (!serverVersion.startsWith ("RFB 003.")) {
                    throw new Exception ("server version '" + serverVersion + "' does not start with 'RFB 003.'");
                }
                int minorVersion = Integer.parseInt (serverVersion.substring (8, 11));
                if (minorVersion < 3) {
                    throw new Exception ("unsupported server version " + serverVersion);
                }
                synchronized (tunnelLock) {
                    WriteLine ("RFB 003.003");
                    WriteFlush ();
                }
            } catch (Exception e) {
                String msg = e.getMessage ();
                if (msg == null) msg = e.getClass ().toString ();
                DisplayErrorWait ("Error communicating with port " + vncPortNumber + ": " + msg);
                vncPortNumber = 0;
                return false;
            }
            return true;
        }

        /**
         * Provide server with this user's credentials.
         */
        private boolean Authenticate ()
                throws Exception
        {
            int serverSecurityType = ReadU32 ();
            switch (serverSecurityType) {
                // connection failed
                case 0: {
                    int reasonLength = ReadU32 ();
                    byte[] reasonBytes = ReadBytes (new byte[reasonLength]);
                    throw new Exception ("server connect error: " + new String (reasonBytes, "UTF-8"));
                }

                // no authentication
                case 1: {
                    break;
                }

                // VNC authentication
                case 2: {

                    // prompt user for password and wait for response
                    vncPassword = null;
                    myHandler.obtainMessage (MH_PASSWD, VNCView.this).sendToTarget ();
                    synchronized (promptLock) {
                        while (vncPassword == null) {
                            promptLock.wait ();
                        }
                    }

                    // VNC uses first 8 bytes of password directly as the key
                    // ** but with the bits in reverse order **
                    byte[] pwbytes = vncPassword.getBytes ("UTF-8");
                    byte[] mdbytes = new byte[8];
                    for (int i = 0; (i < pwbytes.length) && (i < mdbytes.length); i ++) {
                        mdbytes[i] = SwapBits (pwbytes[i]);
                    }
                    SecretKeySpec passwordKeySpec = new SecretKeySpec (mdbytes, "DES");
                    @SuppressLint("GetInstance")
                    Cipher cipher = Cipher.getInstance ("DES/ECB/NoPadding");
                    cipher.init (Cipher.ENCRYPT_MODE, passwordKeySpec);

                    byte[] chal = ReadBytes (new byte[16]);
                    byte[] resp = cipher.doFinal (chal);
                    WriteBytes (resp);
                    WriteFlush ();

                    // if password bad, close everything up and start over
                    // but keep the port number so they don't have to re-enter it
                    int serverSecurityResult = ReadU32 ();
                    if (serverSecurityResult != 0) {
                        DisplayErrorWait ("Password not accepted");
                        return false;
                    }

                    vncPassword = "";
                    break;
                }

                default: throw new Exception ("unsupported security type " + serverSecurityType);
            }
            return true;
        }

        /**
         * Configure message types and data formats.
         */
        private void ExchangeInitMessages ()
                throws Exception  // plus OutOfMemoryError
        {
            // send Client Init message
            synchronized (tunnelLock) {
                WriteU8 ((byte) 1);  // shared-flag = 1 : allow others to connect to this desktop
                WriteFlush ();
            }

            // read Server Init message
            framebufferWidth  =    ReadU16 ();
            framebufferHeight =    ReadU16 ();
            /* bitsPerPixel   = */ ReadU8  ();
            /* depth          = */ ReadU8  ();
            /* bigEndianFlag  = */ ReadU8  ();
            /* trueColourFlag = */ ReadU8  ();
            /* redMax         = */ ReadU16 ();
            /* greenMax       = */ ReadU16 ();
            /* blueMax        = */ ReadU16 ();
            /* redShift       = */ ReadU8  ();
            /* greenShift     = */ ReadU8  ();
            /* blueShift      = */ ReadU8  ();
            ReadU8 (); ReadU8 (); ReadU8 ();
            int nameLength = ReadU32 ();
            byte[] nameBytes = ReadBytes (new byte[nameLength]);
            nameString = new String (nameBytes, "UTF-8");

            int prl = framebufferWidth;
            if (prl < 256) prl = 256;
            pixelRow = new int[prl];

            synchronized (tunnelLock) {

                // write Set Pixel Format message
                WriteU8  ((byte) 0);                       // message-type = 0
                WriteU8  ((byte) 0);
                WriteU8  ((byte) 0);
                WriteU8  ((byte) 0);
                WriteU8  ((byte) (useBpp16 ? 16 : 32));    // bits-per-pixel
                WriteU8  ((byte) (useBpp16 ? 16 : 24));    // depth
                WriteU8  ((byte) 1);                       // big-endian-flag
                WriteU8  ((byte) 1);                       // true-colour-flag
                WriteU16 ((short) (useBpp16 ? 31 : 255));  // red-max
                WriteU16 ((short) (useBpp16 ? 63 : 255));  // green-max
                WriteU16 ((short) (useBpp16 ? 31 : 255));  // blue-max
                WriteU8  ((byte)  (useBpp16 ? 11 :  16));  // red-shift
                WriteU8  ((byte)  (useBpp16 ?  5 :   8));  // green-shift
                WriteU8  ((byte)                      0);  // blue-shift
                WriteU8  ((byte) 0);
                WriteU8  ((byte) 0);
                WriteU8  ((byte) 0);

                // now that we can send normal messages to the server, create bitmap to indicate the fact
                // bitmap create might throw out of mem error
                bitmapRect.right  = framebufferWidth;
                bitmapRect.bottom = framebufferHeight;
                canvasRect.right  = framebufferWidth;
                canvasRect.bottom = framebufferHeight;
                bitmap = Bitmap.createBitmap (framebufferWidth, framebufferHeight,
                        useBpp16 ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888);

                // tell server which encodings we accept
                // ... in addition to raw
                WriteU8  ((byte)  2);
                WriteU8  ((byte)  0);
                WriteU16 ((short) 4);
                WriteU32 (16);
                WriteU32  (5);
                WriteU32  (2);
                WriteU32  (1);

                // request initial frame contents
                WriteU8  ((byte)  3);
                WriteU8  ((byte)  0);
                WriteU16 ((short) 0);
                WriteU16 ((short) 0);
                WriteU16 (framebufferWidth);
                WriteU16 (framebufferHeight);

                WriteFlush ();
            }
        }

        /**
         * Process messages from the server until connection is closed.
         */
        private void ProcessIncomingMessages ()
                throws Exception
        {
            while (true) {

                // read message type
                // if eof, that's the normal end
                byte messageType;
                try {
                    messageType = ReadU8 ();
                } catch (EOFException eofe) {
                    break;
                }
                switch (messageType) {

                    // framebuffer update
                    case 0: {
                        FramebufferUpdate ();
                        break;
                    }

                    // ring bell
                    case 2: {
                        sshclient.MakeBeepSound ();
                        break;
                    }

                    // server cut text
                    case 3: {
                        ReadU8 (); ReadU8 (); ReadU8 ();
                        int length = ReadU32 ();
                        String text = new String (ReadBytes (new byte[length]), "UTF-8");
                        Object[] args = new Object[] { sshclient, text };
                        myHandler.obtainMessage (MH_PASTE, args).sendToTarget ();
                        break;
                    }

                    default: throw new Exception ("unsupported server message type " + messageType);
                }
            }
        }

        /**
         * Process incoming Framebuffer Update messages.
         */
        private void FramebufferUpdate ()
                throws Exception
        {
            ReadU8 ();
            short numberOfRectangles = ReadU16 ();
            while (-- numberOfRectangles >= 0) {
                short xPosition   = ReadU16 ();
                short yPosition  = ReadU16 ();
                short width      = ReadU16 ();
                short height     = ReadU16 ();
                int encodingType = ReadS32 ();

                switch (encodingType) {
                    case 0: {
                        FU_Raw (xPosition, yPosition, width, height);
                        break;
                    }

                    case 1: {
                        FU_CopyRect (xPosition, yPosition, width, height);
                        break;
                    }

                    case 2: {
                        FU_RRE (xPosition, yPosition, width, height);
                        break;
                    }

                    case 5: {
                        FU_HexTile (xPosition, yPosition, width, height);
                        break;
                    }

                    case 16: {
                        FU_ZRLE (xPosition, yPosition, width, height);
                        break;
                    }

                    default: throw new Exception ("unsupported encoding type " + encodingType);
                }
            }

            // tell GUI thread to update the display
            myHandler.obtainMessage (MH_UPDRCVD, VNCView.this).sendToTarget ();
        }

        private void FU_Raw (short xPosition, short yPosition, short width, short height)
                throws IOException
        {
            for (int y = 0; y < height; y ++) {
                for (int x = 0; x < width; x ++) {
                    pixelRow[x] = ReadPixel ();
                }
                bitmap.setPixels (pixelRow, 0, width, xPosition, yPosition + y, width, 1);
            }
        }
  
        private void FU_CopyRect (short xPosition, short yPosition, short width, short height)
                throws IOException
        {
            short srcXPosition = ReadU16 ();
            short srcYPosition = ReadU16 ();
            for (int y = 0; y < height; y ++) {
                bitmap.getPixels (pixelRow, 0, width, srcXPosition, srcYPosition + y, width, 1);
                bitmap.setPixels (pixelRow, 0, width,    xPosition,    yPosition + y, width, 1);
            }
        }

        private void FU_RRE (short xPosition, short yPosition, short width, short height)
                throws IOException
        {
            int numberOfSubRects = ReadU32 ();
            int pixel = ReadPixel ();
            FillBitmapRect (xPosition, yPosition, width, height, pixel);
            while (-- numberOfSubRects >= 0) {
                pixel     = ReadPixel ();
                short xS  = ReadU16 ();
                short yS  = ReadU16 ();
                width     = ReadU16 ();
                height    = ReadU16 ();
                FillBitmapRect (xPosition + xS, yPosition + yS, width, height, pixel);
            }
        }

        private void FU_HexTile (short xPosition, short yPosition, short width, short height)
                throws IOException
        {
            int bgPixel = 0;
            int fgPixel = 0;

            // rectangle is chopped up into 16pix x 16pix tiles
            // the ones on the right and/or bottom might be smaller
            for (int yTile = 0; yTile < height; yTile += 16) {
                int hTile = height - yTile;
                if (hTile > 16) hTile = 16;
                for (int xTile = 0; xTile < width; xTile += 16) {
                    int wTile = width - xTile;
                    if (wTile > 16) wTile = 16;

                    // each tile starts with a sub-encoding byte
                    byte subEnc = ReadU8 ();

                    // if subEnc<0> is set, tile is just a matrix of pixels
                    if ((subEnc & 1) != 0) {
                        int i = 0;
                        for (int y = 0; y < hTile; y ++) {
                            for (int x = 0; x < wTile; x ++) {
                                pixelRow[i++] = ReadPixel ();
                            }
                        }
                        bitmap.setPixels (pixelRow, 0, wTile, xPosition + xTile, yPosition + yTile, wTile, hTile);
                        continue;
                    }

                    // otherwise, maybe pick up new background/foreground colors
                    if ((subEnc & 2) != 0) bgPixel = ReadPixel ();
                    if ((subEnc & 4) != 0) fgPixel = ReadPixel ();

                    // set background for the tile
                    FillBitmapRect (xPosition + xTile, yPosition + yTile, wTile, hTile, bgPixel);

                    // loop through any sub-rectangles within the tile
                    int numSubrects = 0;
                    if ((subEnc & 8) != 0) numSubrects = ReadU8 () & 255;
                    while (-- numSubrects >= 0) {
                        // maybe sub-rectangle has an explicit color
                        // otherwise use foreground color
                        int pixel = fgPixel;
                        if ((subEnc & 16) != 0) pixel = ReadPixel ();

                        // get sub-rectangle position and size then fill it
                        byte pos = ReadU8 ();
                        byte siz = ReadU8 ();
                        int x =  (pos >> 4) & 15;
                        int y =   pos       & 15;
                        int w = ((siz >> 4) & 15) + 1;
                        int h = ( siz       & 15) + 1;
                        FillBitmapRect (xPosition + xTile + x, yPosition + yTile + y, w, h, pixel);
                    }
                }
            }
        }

        private void FU_ZRLE (short xPosition, short yPosition, short width, short height)
                throws IOException
        {
            // if first time for this connection, open unzipping stream
            if (zrleis == null) zrleis = new ZRLEInputStream ();
            zrleis.remaining = ReadU32 ();
            if (gzipis == null) gzipis = new com.jcraft.jzlib.InflaterInputStream (zrleis);

            //Log.d (TAG, "FU_ZRLE*: starting");

            // deflect all subsequent Read*() calls to the gunzipper
            zrleactive = true;

            try {
                // process zrle chunk
                FU_ZRLE_Work (xPosition, yPosition, width, height);

                // sometimes there are a few dangling bytes
                // try to flush them by reading through unzipper
                if (zrleis.remaining > 0) {
                    Log.w (TAG, "VNCView FU_ZRLE: zrleis remaining " + zrleis.remaining);
                    try {
                        do ReadU8 ();
                        while (zrleis.remaining > 0);
                    } catch (EOFException ignored) {
                    }
                }
            } catch (Exception e) {
                Log.w (TAG, "VNCView FU_ZRLE: abandoning ZRLE mode", e);

                synchronized (tunnelLock) {

                    // tell server not to send any more ZRLE encodings
                    // though there may be some already on the way here
                    WriteU8  ((byte)  2);
                    WriteU8  ((byte)  0);
                    WriteU16 ((short) 3);
                    WriteU32 (5);
                    WriteU32 (2);
                    WriteU32 (1);

                    // request whole frame contents
                    WriteU8  ((byte)  3);
                    WriteU8  ((byte)  0);
                    WriteU16 ((short) 0);
                    WriteU16 ((short) 0);
                    WriteU16 (framebufferWidth);
                    WriteU16 (framebufferHeight);

                    WriteFlush ();
                }
            } finally {

                // go back to reading directly from network link
                zrleactive = false;

                // flush remaining dangling bytes directly from network
                if (zrleis.remaining > 0) {
                    Log.w (TAG, "VNCView FU_ZRLE:  still remaining " + zrleis.remaining);
                    do ReadU8 ();
                    while (zrleis.remaining > 0);
                }
            }

            //Log.d (TAG, "FU_ZRLE*: finished");
        }

        private void FU_ZRLE_Work (short xPosition, short yPosition, short width, short height)
                throws IOException
        {
            // rectangle is chopped up into 64pix x 64pix tiles
            // the ones on the right and/or bottom might be smaller
            for (int yDiff = 0; yDiff < height; yDiff += 64) {
                int hTile = height - yDiff;
                if (hTile > 64) hTile = 64;
                int yTile = yPosition + yDiff;
                for (int xDiff = 0; xDiff < width; xDiff += 64) {
                    int wTile = width - xDiff;
                    if (wTile > 64) wTile = 64;
                    int xTile = xPosition + xDiff;

                    // get subencoding type
                    int subencoding = ReadU8 () & 255;
                    //Log.d (TAG, "FU_ZRLE*: subencoding=" + subencoding);

                    // read palette entries if any
                    int npe = subencoding & 127;
                    for (int i = 0; i < npe; i ++) {
                        palette[i] = ReadCPixel ();
                    }

                    // raw pixel data
                    if (subencoding == 0) {
                        // one network pixel per tile pixel
                        for (int h = 0; h < hTile; h ++) {
                            for (int w = 0; w < wTile; w ++) {
                                pixelRow[w] = ReadCPixel ();
                            }
                            bitmap.setPixels (pixelRow, 0, wTile, xTile, yTile + h, wTile, 1);
                        }
                    }

                    // single color
                    else if (subencoding == 1) {
                        // one network pixel for all tile pixels
                        FillBitmapRect (xTile, yTile, wTile, hTile, palette[0]);
                    }

                    // packed palette type
                    else if (subencoding <= 127) {
                        // calc number of bits needed for palette index, 1, 2, 4 or 8
                        int nbitsneeded = (subencoding <= 2) ? 1 : (subencoding <= 4) ? 2 : (subencoding <= 16) ? 4 : 8;
                        // loop through all rows for the tile
                        for (int h = 0; h < hTile; h ++) {
                            // ain't got no bits for this row yet
                            int bits = 0;
                            int nbitsavail = 0;
                            // loop through all pixels of tile row
                            for (int w = 0; w < wTile; w ++) {
                                // get more bits if we have run out
                                nbitsavail -= nbitsneeded;
                                if (nbitsavail < 0) {
                                    bits = ReadU8 ();
                                    nbitsavail = 8 - nbitsneeded;
                                }
                                // palette index is top 'nbitsneeded' from the bits
                                int index = (bits & 255) >> (8 - nbitsneeded);
                                pixelRow[w] = palette[index];
                                // shift bits over for next time
                                bits <<= nbitsneeded;
                            }
                            // flush pixel row out to bitmap
                            bitmap.setPixels (pixelRow, 0, wTile, xTile, yTile + h, wTile, 1);
                        }
                    }

                    // must be plain RLE or palette RLE
                    else {
                        // ain't got no more pixels for this run
                        int length = 0;
                        int pixel  = 0;
                        // loop through all rows for the tile
                        for (int h = 0; h < hTile; h ++) {
                            // loop through all pixels of tile row
                            for (int w = 0; w < wTile; w ++) {
                                // get another run if we have run out
                                if (-- length < 0) {
                                    length = 0;
                                    if (subencoding == 128) {
                                        // plain RLE, read pixel directly
                                        // count follows
                                        pixel = ReadCPixel ();
                                    } else {
                                        // palette RLE, read palette index
                                        int index = ReadU8 () & 255;
                                        // small index means just one pixel of that color
                                        if (index <= 127) {
                                            pixelRow[w] = palette[index];
                                            continue;
                                        }
                                        // big index means run of pixels of that color
                                        // count follows
                                        pixel = palette[index-128];
                                    }
                                    // read number of pixels of that color
                                    int b;
                                    do {
                                        b = ReadU8 () & 255;
                                        length += b;
                                    } while (b == 255);
                                }
                                // write pixel to row buffer
                                pixelRow[w] = pixel;
                            }
                            // flush pixel row out to bitmap
                            bitmap.setPixels (pixelRow, 0, wTile, xTile, yTile + h, wTile, 1);
                        }
                        if (length > 0) throw new IOException ("residual length " + length);
                    }
                }
            }
        }

        /**
         * Fill rectangular area in bitmap with a single color.
         */
        private void FillBitmapRect (int xpos, int ypos, int wid, int hei, int pix)
        {
            for (int x = 0; x < wid; x ++) pixelRow[x] = pix;
            for (int y = 0; y < hei; y ++) {
                bitmap.setPixels (pixelRow, 0, wid, xpos, ypos + y, wid, 1);
            }
        }

        /**
         * Display error message and wait for acknowledgement.
         * @param msg = message to be displayed
         */
        private void DisplayErrorWait (String msg)
        {
            final Object[] prms = new Object[3];
            prms[0] = sshclient;
            prms[1] = msg;
            prms[2] = new Runnable () {
                @Override
                public void run ()
                {
                    synchronized (promptLock) {
                        prms[2] = null;
                        promptLock.notifyAll ();
                    }
                }
            };
            Message m = myHandler.obtainMessage ();
            m.what = MH_ALERT;
            m.obj  = prms;
            m.sendToTarget ();
            synchronized (promptLock) {
                while (prms[2] != null) {
                    try { promptLock.wait (); } catch (InterruptedException ignored) { }
                }
            }
        }

        /**
         * Got some data from the remote host.
         * Since we always theoretically process it quickly,
         * just process directly from SSH buffer without copying.
         */
        @Override  // DirectTCPIPTunnel.Recv
        public void gotData (byte[] data, int offset, int length)
        {
            synchronized (tunnelLock) {

                // if already seen eof or have been closed, ignore this packet
                if (readend >= 0) {

                    // save new packet parameters
                    readbuf = data;
                    readidx = offset;
                    readend = offset + length;

                    // tell ReadFill() that there is something for it now
                    tunnelLock.notifyAll ();

                    // wait for Read*() to process it all
                    // or for Close() to be called
                    while (readbuf != null) {
                        try { tunnelLock.wait (); } catch (InterruptedException ignored) { }
                    }

                    // ReadFill() is now blocked waiting for more
                    // or Close() is about to finish up
                }
            }
        }

        @Override  // DirectTCPIPTunnel.Recv
        public void gotExtData (byte[] data, int offset, int length)
        { }

        @Override  // DirectTCPIPTunnel.Recv
        public void gotEof ()
        {
            synchronized (tunnelLock) {

                // save eof packet parameters
                readbuf = null;
                readidx = -1;
                readend = -1;

                // tell ReadFill() that there is nothing more to process
                tunnelLock.notifyAll ();
            }
        }

        private String ReadLine () throws IOException
        {
            StringBuilder sb = new StringBuilder ();
            byte by;
            while ((by = ReadU8 ()) != '\n') {
                sb.append ((char) by);
            }
            return sb.toString ();
        }

        private int ReadCPixel () throws IOException
        {
            if (useBpp16) return ReadPixel ();
            int r = ReadU8 () & 255;
            int g = ReadU8 () & 255;
            int b = ReadU8 () & 255;
            return (r << 16) | (g << 8) | b | 0xFF000000;
        }

        private int ReadPixel () throws IOException
        {
            int pix;
            if (useBpp16) {
                pix = ReadU16 ();
                pix = (((((pix >> 11) & 31) * 255 + 15) / 31) << 16) |
                      (((((pix >>  5) & 63) * 255 + 31) / 63) <<  8) |
                       ((( pix        & 31) * 255 + 15) / 31);
            } else {
                pix = ReadU32 ();
            }
            return pix | 0xFF000000;
        }

        private int ReadU32 () throws IOException
        {
            byte b1 = ReadU8 ();
            byte b2 = ReadU8 ();
            byte b3 = ReadU8 ();
            byte b4 = ReadU8 ();
            return ((b1 & 0xFF) << 24) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 8) | (b4 & 0xFF);
        }

        private short ReadU16 () throws IOException
        {
            byte b1 = ReadU8 ();
            byte b2 = ReadU8 ();
            return (short) (((b1 & 0xFF) << 8) | (b2 & 0xFF));
        }

        private int ReadS32 () throws IOException
        {
            byte b1 = ReadU8 ();
            byte b2 = ReadU8 ();
            byte b3 = ReadU8 ();
            byte b4 = ReadU8 ();
            return ((b1 & 0xFF) << 24) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 8) | (b4 & 0xFF);
        }

        private byte[] ReadBytes (byte[] array) throws IOException
        {
            for (int i = 0; i < array.length; i ++) {
                array[i] = ReadU8 ();
            }
            return array;
        }

        private byte ReadU8 () throws IOException
        {
            if (zrleactive) {
                int rc = gzipis.read ();
                if (rc < 0) throw new EOFException ();
                return (byte) rc;
            }

            // unlocked access ok cuz we know gotData() is blocked
            // so read{buf,idx,end} are stable
            if (readidx >= readend) {
                ReadFill ();
            }
            return readbuf[readidx++];
        }

        private void ReadFill ()
                throws IOException
        {
            synchronized (tunnelLock) {
                while (readidx >= readend) {

                    // check for eof mark received or Close()d
                    if (readend < 0) throw new EOFException ();

                    // tell gotData() we are done with old packet
                    readbuf = null;
                    tunnelLock.notifyAll ();

                    // wait for gotData() to supply a new one
                    try {
                        tunnelLock.wait ();
                    } catch (InterruptedException ie) {
                        throw new IOException (ie.getMessage ());
                    }

                    // gotData() will block until we are called again
                    // to release readbuf
                }
            }
        }

        /**
         * Stop sending and receiving and close channel to server.
         * Call without tunnelLock so gotData() won't block.
         */
        private void FullClose ()
        {
            // don't try to send or receive any more
            // after this, gotData() will just skip right through
            //             and sendBuf() etc will not be called
            synchronized (tunnelLock) {
                Close ();
            }

            // tell server and ssh library we are done with channel
            if (tunnel != null) {
                try { tunnel.sendEof    (); } catch (Exception ignored) { }
                try { tunnel.disconnect (); } catch (Exception ignored) { }
                tunnel = null;
            }
        }

        // the below must be called with tunnelLock locked

        public void WriteLine (String str)
        {
            byte[] bytes;
            try {
                bytes = str.getBytes ("UTF-8");
            } catch (UnsupportedEncodingException uee) {
                throw new RuntimeException (uee);
            }
            for (byte b : bytes) WriteU8 (b);
            WriteU8 ((byte) '\n');
        }

        public void WriteU32 (int s)
        {
            WriteU8 ((byte) (s >> 24));
            WriteU8 ((byte) (s >> 16));
            WriteU8 ((byte) (s >>  8));
            WriteU8 ((byte) s);
        }

        public void WriteU16 (short s)
        {
            WriteU8 ((byte) (s >>  8));
            WriteU8 ((byte) s);
        }

        public void WriteBytes (byte[] array)
        {
            for (byte b : array) WriteU8 (b);
        }

        public void WriteU8 (byte b)
        {
            if (writeidx >= writeend) WriteFlush ();
            writebuf[writeidx++] = b;
        }

        public void WriteFlush ()
        {
            if (writeend >= 0) {
                if (writeidx > writebeg) {
                    try {
                        tunnel.sendBuf (writeidx - writebeg);
                    } catch (Exception e) {
                        Log.w (TAG, "VNC transmit error", e);
                        String msg = e.getMessage ();
                        if (msg == null) msg = e.getClass ().toString ();
                        DisplayError ("Error sending: " + msg);
                        Close ();
                        return;
                    }
                }
                writebuf = tunnel.getSendBuf ();
                writebeg = tunnel.getSendOfs ();
                writeend = tunnel.getSendLen () + writebeg;
                writeidx = writebeg;
            }
        }

        private void Close ()
        {
            // release read and write buffers
            // skip over any future sends and receives
            readbuf  = null;
            readidx  = -1;
            readend  = -1;
            writebuf = null;
            writebeg = -1;
            writeidx = -1;
            writeend = -1;

            zrleactive = false;
            zrleis = null;
            gzipis = null;

            // wake gotData() and ReadFill()
            // they will see eof mark and exit
            tunnelLock.notifyAll ();
        }

        /**
         * Read compressed bytes for ZRLE framebuffer update.
         */
        private class ZRLEInputStream extends InputStream {
            public int remaining;

            private byte[] temp = new byte[1];

            @Override
            public int read ()
                    throws IOException
            {
                int rc = read (temp, 0, 1);
                if (rc > 0) rc = temp[0] & 0xFF;
                return rc;
            }

            @Override
            public int read (@NonNull byte[] buffer, int offset, int length)
                    throws IOException
            {
                if (remaining <= 0) return -1;
                if (length > remaining) length = remaining;
                if (length > 0) {
                    int avail;
                    while ((avail = readend - readidx) <= 0) {
                        ReadFill ();
                    }
                    if (length > avail) length = avail;
                    System.arraycopy (readbuf, readidx, buffer, offset, length);
                    readidx   += length;
                    remaining -= length;
                }
                return length;
            }
        }
    }

    /**
     * Prompt user for VNC port number and wake the VNCChan thread when the user supplies it.
     * Kill the VNCChan thread if the user cancels.
     */
    @SuppressWarnings("SameParameterValue")
    private void GetVNCPortNumber (boolean useSavedPn)
    {
        KeyedValue kv = new KeyedValue () {
            @Override
            public boolean GotValue (String value)
            {
                try {
                    int pn = Integer.parseInt (value);
                    if ((pn > 0) && (pn <= 65535)) {
                        vncPortNumber = pn;
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                }
                return false;
            }
        };

        kv.desc = "port number";
        kv.path = new File (sshclient.getFilesDir (), "vncportnumbers.enc").getPath ();
        kv.key  = session.getHostnametext ().getText ().toString () + ":";
        kv.et.setSingleLine ();
        kv.et.setInputType (InputType.TYPE_CLASS_NUMBER);
        kv.et.setHint ("5901");

        kv.GetKeyedValue (useSavedPn);
    }

    /**
     * Prompt user for VNC password and wake the VNCChan thread when the user supplies it.
     * Kill the VNCChan thread if the user cancels.
     */
    @SuppressWarnings("SameParameterValue")
    private void GetVNCPassword (boolean useSavedPw)
    {
        KeyedValue kv = new KeyedValue () {
            @Override
            public boolean GotValue (String value)
            {
                vncPassword = value;
                return true;
            }
        };

        kv.desc = "password";
        kv.path = new File (sshclient.getFilesDir (), "vncpasswords.enc").getPath ();
        kv.key  = session.getHostnametext ().getText ().toString () + ":" + vncPortNumber + ":";
        kv.et.setSingleLine ();
        kv.et.setInputType (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        kv.GetKeyedValue (useSavedPw);
    }

    /**
     * Prompt for savable value.
     */
    private abstract class KeyedValue implements TextView.OnEditorActionListener {
        public String desc;
        public String path;
        public String key;

        public abstract boolean GotValue (String value);

        public CheckBox cb = sshclient.MyCheckBox ();
        public EditText et = sshclient.MyEditText ();

        public void GetKeyedValue (boolean useSaved)
        {
            et.setOnEditorActionListener (this);

            cb.setText ("Save " + desc);

            if (useSaved) {
                String val = FetchKeyedValue ();
                if (val != null) {
                    et.setText (val);
                    cb.setChecked (true);
                }
            }

            // the checkbox goes below the text input field
            LinearLayout ll = new LinearLayout (sshclient);
            ll.setOrientation (LinearLayout.VERTICAL);
            ll.addView (et);
            ll.addView (cb);

            AlertDialog.Builder adb = new AlertDialog.Builder (sshclient);
            adb.setTitle ("VNC " + desc);
            adb.setView (ll);
            adb.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
                @Override
                public void onClick (DialogInterface dialogInterface, int i)
                {
                    GotKeyedValue ();
                }
            });
            adb.setNegativeButton ("Cancel", new DialogInterface.OnClickListener () {
                @Override
                public void onClick (DialogInterface dialogInterface, int i)
                {
                    vncChan.interrupt ();
                }
            });
            et.setTag (adb.show ());
        }

        @Override  // OnEditorActionListener
        public boolean onEditorAction (TextView v, int actionId, KeyEvent ke)
        {
            if ((actionId == EditorInfo.IME_ACTION_DONE) || (actionId == EditorInfo.IME_ACTION_NEXT)) {
                ((AlertDialog) et.getTag ()).dismiss ();
                GotKeyedValue ();
                return true;
            }
            return false;
        }

        private void GotKeyedValue ()
        {
            synchronized (promptLock) {
                String value = et.getText ().toString ();
                if (GotValue (value)) {
                    promptLock.notifyAll ();
                    if (cb.isChecked ()) {
                        StoreKeyedValue (value);
                    }
                } else {
                    GetKeyedValue (false);
                }
            }
        }

        private String FetchKeyedValue ()
        {
            MasterPassword mp = sshclient.getMasterPassword ();
            try {
                try {
                    BufferedReader reader = new BufferedReader (mp.EncryptedFileReader (path), 4096);
                    try {
                        String line;
                        while ((line = reader.readLine ()) != null) {
                            if (line.startsWith (key)) {
                                return line.substring (key.length ());
                            }
                        }
                    } finally {
                        reader.close ();
                    }
                } catch (FileNotFoundException ignored) {
                }
            } catch (Exception e) {
                String msg = e.getMessage ();
                if (msg == null) msg = e.getClass ().toString ();
                DisplayError ("Error retrieving " + desc + ": " + msg);
            }
            return null;
        }

        private void StoreKeyedValue (String value)
        {
            String newline = key + value;
            MasterPassword mp = sshclient.getMasterPassword ();
            try {
                boolean found = false;
                BufferedWriter writer = mp.EncryptedFileWriter (path + ".tmp");
                try {
                    try {
                        BufferedReader reader = new BufferedReader (mp.EncryptedFileReader (path), 4096);
                        try {
                            String line;
                            while ((line = reader.readLine ()) != null) {
                                if (!line.startsWith (key)) {
                                    writer.write (line);
                                    writer.newLine ();
                                } else if (line.equals (newline)) {
                                    found = true;
                                    break;
                                }
                            }
                        } finally {
                            reader.close ();
                        }
                    } catch (FileNotFoundException ignored) {
                    }
                    if (!found) {
                        writer.write (newline);
                        writer.newLine ();
                    }
                } finally {
                    writer.close ();
                }
                if (found) {
                    //noinspection ResultOfMethodCallIgnored
                    new File (path + ".tmp").delete ();
                } else {
                    MasterPassword.RenameTempToPerm (path);
                }
            } catch (Exception e) {
                String msg = e.getMessage ();
                if (msg == null) msg = e.getClass ().toString ();
                DisplayError ("Error saving " + desc + ": " + msg);
            }
        }
    }

    /**
     * Perform operations in the GUI thread.
     */
    private final static int MH_ALERT   = 99;
    private final static int MH_PASTE   = 98;
    private final static int MH_UPDRCVD = 97;
    private final static int MH_RTMOUSE = 96;
    private final static int MH_REQUPDT = 95;
    private final static int MH_PASSWD  = 94;
    private final static int MH_PORTNO  = 93;
    private final static int MH_DISCOD  = 92;

    private static class MyHandler extends Handler {
        @Override
        public void handleMessage (Message m)
        {
            switch (m.what) {

                // display alert error dialog box
                //   m.obj[0] = sshclient
                //   m.obj[1] = message to display
                //   m.obj[2] = null: no callback when done
                //              else: callback when user clicks OK button
                case MH_ALERT: {
                    SshClient sshclient = (SshClient) ((Object[]) m.obj)[0];
                    String msg = (String) ((Object[]) m.obj)[1];
                    final Runnable done = (Runnable) ((Object[]) m.obj)[2];
                    AlertDialog.Builder adb = new AlertDialog.Builder (sshclient);
                    adb.setTitle ("VNC error");
                    adb.setMessage (msg);
                    adb.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
                        @Override
                        public void onClick (DialogInterface dialogInterface, int i)
                        {
                            if (done != null) done.run ();
                        }
                    });
                    adb.show ();
                    break;
                }

                // host has sent us some paste text
                case MH_PASTE: {
                    SshClient sshclient = (SshClient) ((Object[]) m.obj)[0];
                    String text = (String) ((Object[]) m.obj)[1];
                    sshclient.CopyToClipboard (text, null);
                    break;
                }

                // bitmap update received
                case MH_UPDRCVD: {
                    VNCView vncv = (VNCView) m.obj;

                    // if already armed to start MH_REQUPDT timer (0), let onDraw() start it
                    // if MT_REQUPDT timer running (1), leave it running
                    // but if MH_REQUPDT timer expired (2), arm onDraw() to start another
                    if (vncv.updreqpend == 2) {
                        vncv.updreqpend = 0;
                    }

                    // trigger onDraw() to be called soon
                    vncv.viewer.invalidate ();
                    break;
                }

                // send right mouse button click after long delay
                case MH_RTMOUSE: {
                    VNCView vncv = (VNCView) m.obj;
                    Viewer viewer = vncv.viewer;
                    long now = SystemClock.uptimeMillis ();

                    // make sure we haven't seen two fingers since the first finger down
                    // and make sure this message isn't a lingering message from long ago
                    if (!viewer.seenTwoFingers && (now - longPressDelay >= viewer.mouseDownAt)) {

                        // tell host right button down
                        // when we get ACTION_UP we will send the buttons-up message to host
                        vncv.SendMouseToHost (MOUSE_BUTTON_RIGHT, viewer.longPressX, viewer.longPressY);

                        // make sure we don't send any more mouse downs to host until next first finger down
                        // including quick tap and drag
                        viewer.seenTwoFingers = true;
                    }
                    break;
                }

                // request screen update from host
                case MH_REQUPDT: {
                    VNCView vncv = (VNCView) m.obj;

                    // if MT_REQUPDT timer was running (1),
                    // say it is now expired (2) and request update from host
                    if (vncv.updreqpend == 1) {
                        vncv.updreqpend = 2;
                        synchronized (vncv.tunnelLock) {
                            if (vncv.bitmap != null) {
                                VNCChan vncChan = vncv.vncChan;
                                vncChan.WriteU8  ((byte)  3);
                                vncChan.WriteU8  ((byte)  1);
                                vncChan.WriteU16 ((short) 0);
                                vncChan.WriteU16 ((short) 0);
                                vncChan.WriteU16 ((short) vncv.bitmap.getWidth ());
                                vncChan.WriteU16 ((short) vncv.bitmap.getHeight ());
                                vncChan.WriteFlush ();
                            }
                        }
                    }
                    break;
                }

                // prompt for and return VNC password
                case MH_PASSWD: {
                    VNCView vncv = (VNCView) m.obj;
                    vncv.GetVNCPassword (true);
                    break;
                }

                case MH_PORTNO: {
                    VNCView vncv = (VNCView) m.obj;
                    vncv.GetVNCPortNumber (true);
                    break;
                }

                // VNCChan thread has exited so we are disconnected from server
                case MH_DISCOD: {
                    VNCView vncv = (VNCView) m.obj;
                    vncv.bitmap  = null;
                    vncv.vncChan = null;
                    vncv.vncPortNumber = 0;
                    vncv.viewer.invalidate ();
                    vncv.MakeMainMenu ();
                    break;
                }
            }
        }
    }
}

/**
 * Abstract keyboard widget.
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


import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v7.widget.GridLayout;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedList;

public abstract class KeybdView extends LinearLayout {
    protected final static String TAG = "SshClient";
    protected final static int MATCH_PARENT = LayoutParams.MATCH_PARENT;
    protected final static int WRAP_CONTENT = LayoutParams.WRAP_CONTENT;

    private final static int KEY_DOWN_DELAY = 500;
    private final static int MH_REPEAT      = 96;
    private final static int MH_DELDWN      = 97;
    private final static int MH_KBSCALE     = 98;
    private final static int MH_BUTSIZE     = 99;
    private final static int REPEAT_INIT    = 1000;
    private final static int REPEAT_MULT    = 200;

    protected final static int MODE_OFF  = 0;
    protected final static int MODE_TEMP = 1;
    protected final static int MODE_LOCK = 2;
    private final static int[] modeColors = { 0, Color.GREEN, Color.RED };

    private static MyHandler myHandler;

    protected boolean capsLocked;
    protected float scaleX;
    protected float scaleY;
    protected float textSizeBase;
    protected int butSize;
    protected LinkedList<KeyButton> allKeyButtons;
    protected LinkedList<KeyButton> allShiftButtons;
    protected LinkedList<KeypadButton> allKeypadButtons;
    protected Lockable altButtons;
    protected Lockable ctrlButtons;
    protected Lockable shiftButtons;
    protected SshClient sshclient;

    private boolean lastShifted;
    private int touchSlop;
    private KeyboardableView keyboardableView;
    private SharedPreferences prefs;
    private String prefName;

    public KeybdView (MySession s, KeyboardableView kv)
    {
        super (s.getSshClient ());
        sshclient = s.getSshClient ();
        keyboardableView = kv;

        if (myHandler == null) myHandler = new MyHandler ();

        TextView tv = sshclient.MyTextView ();
        textSizeBase = tv.getTextSize ();

        prefName = getClass ().getSimpleName ();
        prefs  = sshclient.getPreferences (Activity.MODE_PRIVATE);
        scaleX = prefs.getFloat (prefName + ".scaleX", 1.0F);
        scaleY = prefs.getFloat (prefName + ".scaleY", 1.0F);

        ViewConfiguration vc = ViewConfiguration.get (sshclient);
        touchSlop = vc.getScaledTouchSlop ();

        altButtons   = new Lockable ();
        ctrlButtons  = new Lockable ();
        shiftButtons = new Lockable ();

        BuildKeyboard ();
    }

    /**
     * Build keyboard one button at a time.
     */
    protected abstract void BuildKeyboard ();

    /**
     * Send the given key code to the host.
     */
    protected abstract void SendCodeToHost (Object code, int down);

    /**
     * Get the code for a given lowercase/uppercase key code logo string
     * @param str = logo string on keytop
     * @return code to give to SendCodeToHost()
     */
    protected abstract Object GetLcUcCode (CharSequence str);

    /**
     * Get whether or not the keypad is in numeric or application mode.
     * @return true: send numeric codes to host
     *        false: send application codes to host
     */
    protected abstract boolean GetKeypadAppMode ();

    /**
     * Handle processing in GUI thread at top level.
     */
    private static class MyHandler extends Handler {
        public void handleMessage (Message m)
        {
            switch (m.what) {

                case MH_REPEAT: {
                    TransientButton tb = (TransientButton) m.obj;
                    tb.RepeatTimer ();
                    break;
                }

                case MH_DELDWN: {
                    KeyButton kb = (KeyButton) m.obj;
                    kb.DelayedDown ();
                    break;
                }

                // keyboard resized
                case MH_KBSCALE: {
                    KeybdView kbv = (KeybdView) m.obj;
                    kbv.BuildKeyboard ();

                    SharedPreferences.Editor editr = kbv.prefs.edit ();
                    editr.putFloat (kbv.prefName + ".scaleX", kbv.scaleX);
                    editr.putFloat (kbv.prefName + ".scaleY", kbv.scaleY);
                    editr.commit ();
                    break;
                }

                // buttons resized
                case MH_BUTSIZE: {
                    KeybdView kbv = (KeybdView) m.obj;
                    if (kbv.butSize != m.arg1) {
                        kbv.butSize = m.arg1;
                        for (KeyButton b : kbv.allKeyButtons) {
                            b.SetStdSize ();
                            b.requestLayout ();
                        }
                        kbv.requestLayout ();
                    }
                    break;
                }
            }
        }
    }

    /**
     * Grouping of all like locking keys like the two shift keys or two ctrl keys etc.
     */
    protected class Lockable {
        public Object lastSentDownCode;
        public int mode;
        public LinkedList<KeyButton> buttons = new LinkedList<> ();

        /**
         * Transient key (non-lockable key) was just released.
         * If this lockable key was in temp mode, release it.
         */
        public void TransientUp ()
        {
            if (mode == MODE_TEMP) {
                mode = MODE_OFF;
                SetColors (null);
            }
        }

        /**
         * Set the color of the key given its mode.
         * If there was a transition from pressed <-> released, send code to host.
         * @param code = which of these keys was just pressed/released
         */
        public void SetColors (Object code)
        {
            // update colors on all like keys
            for (KeyButton kb : buttons) {
                kb.SetButtonColor (modeColors[mode]);
            }

            // if button is now up and host thinks it is down,
            // tell host the key was released
            if (mode == MODE_OFF) {
                if (lastSentDownCode != null) {
                    SendCodeToHost (lastSentDownCode, 0);
                    lastSentDownCode = null;
                }
            }

            // if button is now down and host thinks it is up,
            // tell host the key was pressed
            else {
                if (lastSentDownCode == null) {
                    SendCodeToHost (code, 1);
                    lastSentDownCode = code;
                }
            }

            // if this is a shift button, update some keytop logos
            if (this == shiftButtons) {
                boolean shifted = (mode != MODE_OFF);
                if (lastShifted != shifted) {
                    lastShifted = shifted;
                    for (KeyButton kb : allShiftButtons) kb.invalidate ();
                }
            }
        }
    }

    //   CapsLockButton -> SizedButton
    //   LockableButton -> SizedButton
    //                     SizedButton -> TransientButton
    //                     ArrowButton -> TransientButton
    //      LetterButton -> LcUcButton -> TransientButton
    // KPNumLockButton -> KeypadButton
    //    KPArowButton -> KeypadButton
    //                    KeypadButton -> TransientButton
    //                                    TransientButton -> KeyButton
    //                                        InvisButton -> KeyButton
    //                                                       KeyButton -> Button

    /**
     * Super, Shift, Control, Alt buttons
     * Click once to arm for next single button.
     * Click twice to arm until clicked a third time.
     */
    protected class LockableButton extends SizedButton {
        public Lockable lockable;

        public LockableButton (Object code, SizedButton stsa, Lockable lock, float widthFactor, String logo)
        {
            super (code, widthFactor, logo, stsa);

            lockable = lock;
            lockable.buttons.addLast (this);

            SetButtonColor (modeColors[lockable.mode]);
        }

        @Override  // TransientButton
        public void OnTouchEvent (int action)
        {
            if (action == MotionEvent.ACTION_DOWN) {
                // step through to next mode and update color on all like buttons
                // causes a down-button or up-button code to be sent on transition
                lockable.mode = (lockable.mode + 1) % 3;
                lockable.SetColors (GetCode ());
            }
        }
    }

    /**
     * Button that has a arrow-like logo and code unaffected by Shift/Ctrl/Alt
     */
    protected class ArrowButton extends TransientButton {
        public final static int UP = 0;
        public final static int DN = 1;
        public final static int LF = 2;
        public final static int RT = 3;

        private int[][] rawpaths = {
                new int[] { 4, 2, 2, 6, 6, 6, 4, 2 },  // up
                new int[] { 4, 6, 2, 2, 6, 2, 4, 6 },  // down
                new int[] { 2, 4, 6, 2, 6, 6, 2, 4 },  // left
                new int[] { 6, 4, 2, 2, 2, 6, 6, 4 }   // right
        };

        private int dir;
        private Object code;
        public  Path guipath = new Path ();

        public ArrowButton (Object c, int d)
        {
            code = c;
            dir  = d;

            setLayoutParams (new LinearLayout.LayoutParams (WRAP_CONTENT, WRAP_CONTENT));
        }

        @Override  // KeyButton
        public void SetStdSize ()
        {
            stdWidth  = butSize;
            stdHeight = butSize;
        }

        @Override  // KeyButton
        public String GetLogo ()
        {
            // onDraw() draws the arrows
            return null;
        }

        @Override  // KeyButton
        public Object GetCode ()
        {
            return code;
        }

        @Override  // TransientButton
        public void OnTouchEvent (int action)
        {
            if ((action == MotionEvent.ACTION_DOWN) &&
                    (ctrlButtons.mode == MODE_LOCK) &&
                    (shiftButtons.mode == MODE_LOCK)) {
                switch (dir) {
                    case UP: {  // up
                        scaleY *= 1.0625F;
                        break;
                    }
                    case DN: {  // down
                        scaleY /= 1.0625F;
                        break;
                    }
                    case RT: {  // right
                        scaleX *= 1.0625F;
                        break;
                    }
                    case LF: {  // left
                        scaleX /= 1.0625F;
                        break;
                    }
                }
                Log.d (TAG, "KeybdView: scaleX,Y=" + scaleX + "," + scaleY);
                Message m = myHandler.obtainMessage ();
                m.what = MH_KBSCALE;
                m.obj  = KeybdView.this;
                myHandler.sendMessage (m);
            } else {
                super.OnTouchEvent (action);
            }
        }

        @Override  // Button
        public void onDraw (@NonNull Canvas c)
        {
            // draw blank button
            super.onDraw (c);

            // draw arrow
            int w = getWidth  () - myPaddingLeft - myPaddingRight;
            int h = getHeight () - myPaddingTop  - myPaddingBottom;

            int cx = myPaddingLeft + w / 2;
            int cy = myPaddingTop  + h / 2;

            guipath.rewind ();

            int[] rawpath = rawpaths[dir];
            for (int i = 0; i < rawpath.length;) {
                float dx = w * (rawpath[i++] - 4) / 4.0F;
                float dy = h * (rawpath[i++] - 4) / 4.0F;
                if (i == 0) guipath.moveTo (cx + dx, cy + dy);
                else guipath.lineTo (cx + dx, cy + dy);
            }

            c.drawPath (guipath, getPaint ());
        }
    }

    /**
     * Button that has a long multi-line logo that must be sized
     * onto a given aspect-ratio (possibly small) keytop.
     */
    protected class SizedButton extends TransientButton {
        private float widthFactor;
        private Object code;
        private String logo;

        /**
         * Constructor
         * @param c = code to send to host
         * @param w = width/height ratio (inverse aspect ratio)
         * @param l = logo string to put on keytop
         */
        public SizedButton (Object c, float w, String l)
        {
            this (c, w, l, null);
        }

        /**
         * Constructor
         * @param c = code to send to host
         * @param w = width/height ratio (inverse aspect ratio)
         * @param l = logo string to put on keytop
         * @param stsa = make this button's text same size as stsa's text
         */
        public SizedButton (Object c, float w, String l, SizedButton stsa)
        {
            code = c;
            widthFactor = w;
            logo = l;
            sameTextSizeAs = stsa;

            setLayoutParams (new LinearLayout.LayoutParams (WRAP_CONTENT, WRAP_CONTENT));
        }

        @Override  // KeyButton
        public void SetStdSize ()
        {
            stdWidth  = (int) (butSize * widthFactor);
            stdHeight = butSize;
        }

        @Override  // KeyButton
        public String GetLogo ()
        {
            return logo;
        }

        @Override  // KeyButton
        public Object GetCode ()
        {
            return code;
        }
    }

    /**
     * These are the letter buttons, A-Z
     */
    protected class LetterButton extends LcUcButton {
        public LetterButton (char uc)
        {
            super ((char) (uc - 'A' + 'a'), uc);
        }
    }

    /**
     * These are the keys that have upper/lower case characters, eg, ! 1
     */
    protected class LcUcButton extends TransientButton {
        @SuppressWarnings("FieldCanBeLocal")
        private Paint myPaint;
        private String clstr;  // shift off; capslock on
        private String custr;  // shift on; capslock on
        private String lcstr;  // shift off; capslock off
        private String ucstr;  // shift on; capslock off

        public LcUcButton (char lc, char uc)
        {
            lcstr = new String (new char[] { lc });
            ucstr = new String (new char[] { uc });
            clstr = ((lc >= 'a') && (lc <= 'z')) ? ucstr : lcstr;
            custr = ((lc >= 'a') && (lc <= 'z')) ? lcstr : ucstr;

            setLayoutParams (new LinearLayout.LayoutParams (WRAP_CONTENT, WRAP_CONTENT));

            myPaint = new Paint (getPaint ());
            myPaint.setTextAlign (Paint.Align.CENTER);

            // we need to be re-drawn whenever shiftmode changes
            allShiftButtons.addLast (this);
        }

        @Override  // KeyButton
        public void SetStdSize ()
        {
            stdWidth  = butSize;
            stdHeight = butSize;
        }

        @Override  // KeyButton
        public String GetLogo ()
        {
            return lastShifted ?
                    (capsLocked ? custr : ucstr) :
                    (capsLocked ? clstr : lcstr);
        }

        @Override  // KeyButton
        public Object GetCode ()
        {
            return GetLcUcCode (GetLogo ());
        }

        @Override  // Button
        public void onDraw (@NonNull Canvas canvas)
        {
            // draw logo'd button
            super.onDraw (canvas);

            // maybe set up standard button size
            if ((butSize == 0) && (ucstr.charAt (0) == 'M')) {
                if (myHandler != null) {
                    Message m = myHandler.obtainMessage ();
                    m.what = MH_BUTSIZE;
                    m.obj  = KeybdView.this;
                    m.arg1 = (int) (getHeight () * scaleY);
                    myHandler.sendMessage (m);
                }
            }
        }
    }

    /**
     * These are the keypad buttons
     */
    protected class KeypadButton extends TransientButton {
        private int width, height;
        private Object codeCtl, codeNum;
        private String logoCtl, logoNum;

        public KeypadButton (int x, int y, int w, int h, Object c, String str)
        {
            this (x, y, w, h, c, str, c, str);
        }
        public KeypadButton (int x, int y, int w, int h, Object nc, String num, Object cc, String ctl)
        {
            this (x, y, w, h, nc, num, cc, ctl, null);
        }
        /**
         * Constructor
         * @param x    = column index in grid
         * @param y    = row index in grid
         * @param w    = width in cells
         * @param h    = height in cells
         * @param nc   = numlock XK_KP code
         * @param num  = numlock logo
         * @param cc   = !numlock XP_KP code
         * @param ctl  = !numlock logo
         * @param stsa = make this key's text same size as stsa's text
         */
        public KeypadButton (int x, int y, int w, int h, Object nc, String num, Object cc, String ctl, KeypadButton stsa)
        {
            width = w;
            height = h;
            codeNum = nc;
            codeCtl = cc;
            logoNum = num;
            logoCtl = ctl;
            sameTextSizeAs = stsa;

            GridLayout.LayoutParams glp = new GridLayout.LayoutParams ();
            glp.columnSpec = GridLayout.spec (x, w);
            glp.rowSpec    = GridLayout.spec (y, h);
            setLayoutParams (glp);

            allKeypadButtons.addLast (this);
        }

        @Override  // KeyButton
        public void SetStdSize ()
        {
            stdWidth  = butSize * width;
            stdHeight = butSize * height;
        }

        @Override  // KeyButton
        public String GetLogo ()
        {
            return GetKeypadAppMode () ? logoCtl : logoNum;
        }

        @Override  // KeyButton
        public Object GetCode ()
        {
            return GetKeypadAppMode () ? codeCtl : codeNum;
        }
    }

    /**
     * These buttons send a down code when pressed and an up code when released.
     * They are everything except the LockableButtons.
     * They will also do repeats.
     */
    private abstract class TransientButton extends KeyButton {
        private long nextRepeatAt = Long.MAX_VALUE;

        /**
         * The key was pressed or released.  Send code to host.
         */
        @Override  // KeyButton
        public void OnTouchEvent (int action)
        {
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    SendCodeToHost (GetCode (), 1);
                    nextRepeatAt = SystemClock.uptimeMillis () + REPEAT_INIT;
                    Message m = myHandler.obtainMessage (MH_REPEAT, this);
                    myHandler.sendMessageAtTime (m, nextRepeatAt);
                    break;
                }
                case MotionEvent.ACTION_UP: {
                    SendCodeToHost (GetCode (), 0);
                    nextRepeatAt = Long.MAX_VALUE;

                    altButtons.TransientUp ();
                    ctrlButtons.TransientUp ();
                    shiftButtons.TransientUp ();
                    break;
                }
            }
        }

        public void RepeatTimer ()
        {
            long now = SystemClock.uptimeMillis ();
            if (now >= nextRepeatAt) {
                SendCodeToHost (GetCode (), 1);
                nextRepeatAt = now + REPEAT_MULT;
                Message m = myHandler.obtainMessage (MH_REPEAT, this);
                myHandler.sendMessageAtTime (m, nextRepeatAt);
            }
        }
    }

    /**
     * Button that takes up square space on the screen but otherwise does nothing.
     */
    protected class InvisButton extends KeyButton {
        public InvisButton ()
        {
            setEnabled (false);
            setLayoutParams (new LinearLayout.LayoutParams (WRAP_CONTENT, WRAP_CONTENT));
            setVisibility (INVISIBLE);
        }

        @Override  // KeyButton
        public void SetStdSize ()
        {
            stdWidth  = butSize;
            stdHeight = butSize;
        }

        @Override  // KeyButton
        public String GetLogo ()
        {
            return null;
        }

        @Override  // KeyButton
        public Object GetCode ()
        {
            return null;
        }

        @Override  // KeyButton
        public void OnTouchEvent (int action)
        { }
    }

    /**
     * Base class for keyboard buttons.
     * - draw button of specified aspect ratio
     * - scale logo text to fit the button
     * - send key codes to host
     */
    protected abstract class KeyButton extends Button {
        private final static int MARGIN = 8;

        protected int myPaddingBottom;  // number of blank rows on bottom
        protected int myPaddingLeft;    // number of blank columns on left
        protected int myPaddingRight;   // number of blank columns on right
        protected int myPaddingTop;     // number of blank rows on top
        protected int stdWidth;         // total cell width
        protected int stdHeight;        // total cell height

        public KeyButton sameTextSizeAs;

        private boolean needPaddings;
        @SuppressWarnings({ "FieldCanBeLocal", "unused" })
        private boolean noOutline;
        private int keyDownScrollX;
        private long delayedDownQueued = Long.MAX_VALUE;

        private float lastTextSize;
        private int buttonColor;
        private int lastDrawHeight, lastDrawWidth;
        private int textHeight;
        private Paint bgPaint;
        private Paint myPaint;
        private Rect bounds = new Rect ();
        private String lastLogo;
        private String[] logoLines;

        public KeyButton ()
        {
            super (sshclient);

            setTextSize (TypedValue.COMPLEX_UNIT_PX, textSizeBase * scaleY);
            setText (" ");
            //setTypeface (Typeface.MONOSPACE);

            myPaint = new Paint (getPaint ());
            myPaint.setTextAlign (Paint.Align.CENTER);

            allKeyButtons.addLast (this);
        }

        /**
         * Set the stdWidth,stdHeight button pixel sizes.
         */
        public abstract void SetStdSize ();

        /**
         * Get multi-line logo text to display on key top.
         * @return null: key button does its own onDraw() to draw logo
         *         else: string to draw on keytop
         */
        public abstract String GetLogo ();

        /**
         * Get keycode to pass to SendCodeToHost()
         */
        public abstract Object GetCode ();

        /**
         * What to do for key pressed and key released.
         */
        public abstract void OnTouchEvent (int action);

        /**
         * Set button color
         * @param color = 0: use default
         *             else: use given color
         */
        public void SetButtonColor (int color)
        {
            if (color != 0) {
                if (bgPaint == null) {
                    bgPaint = new Paint ();
                    bgPaint.setStyle (Paint.Style.FILL_AND_STROKE);
                    bgPaint.setStrokeWidth (10);
                }
                bgPaint.setColor (color);
            }
            if (buttonColor != color) {
                buttonColor = color;
                invalidate ();
            }
        }

        /**
         * Force size to the exact size calculated by SetStdSize().
         */
        @Override  // TextView
        protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec)
        {
            if ((stdWidth == 0) || (stdHeight == 0)) {
                super.onMeasure (widthMeasureSpec, heightMeasureSpec);
            } else {
                setMeasuredDimension (stdWidth, stdHeight);
            }
        }

        /**
         * Button is being pressed, released, dragged, etc.
         */
        @Override  // Button
        public final boolean onTouchEvent (@NonNull MotionEvent me)
        {
            int action = me.getActionMasked ();
            switch (action) {

                /*
                 * Key down, delay processing for a few milliseconds in case user is dragging.
                 */
                case MotionEvent.ACTION_DOWN: {
                    if (delayedDownQueued == Long.MAX_VALUE) {
                        keyDownScrollX = keyboardableView.AltKeyboardScrollX ();
                        delayedDownQueued = SystemClock.uptimeMillis () + KEY_DOWN_DELAY;
                        Message m = myHandler.obtainMessage (MH_DELDWN, this);
                        myHandler.sendMessageAtTime (m, delayedDownQueued);
                    }
                    break;
                }

                /*
                 * Key up, process immediately.
                 * And if we have a delayed key down, process that first.
                 */
                case MotionEvent.ACTION_UP:        // normal lifted finger off button
                case MotionEvent.ACTION_CANCEL: {  // dragged finger off button laterally
                    if (delayedDownQueued != Long.MAX_VALUE) {
                        delayedDownQueued = Long.MAX_VALUE;
                        OnTouchEvent (MotionEvent.ACTION_DOWN);
                    }
                    OnTouchEvent (MotionEvent.ACTION_UP);
                    break;
                }
            }
            return super.onTouchEvent (me);
        }

        /**
         * It has been KEY_DOWN_DELAY ms since we saw the key down event.
         * If we aren't being dragged, process the key down event.
         */
        public void DelayedDown ()
        {
            if (SystemClock.uptimeMillis () >= delayedDownQueued) {
                delayedDownQueued = Long.MAX_VALUE;
                int keyDeltaScrollX = keyboardableView.AltKeyboardScrollX () - keyDownScrollX;
                if (keyDeltaScrollX < 0) keyDeltaScrollX = -keyDeltaScrollX;
                if (keyDeltaScrollX <= touchSlop) {
                    OnTouchEvent (MotionEvent.ACTION_DOWN);
                }
            }
        }

        @Override  // View
        public void requestLayout ()
        {
            needPaddings = true;
            super.requestLayout ();
        }

        /**
         * Draw a blank button and measure it.
         */
        @Override  // TextView
        public void onDraw (@NonNull Canvas canvas)
        {
            // find padding boundaries by drawing button to bitmap and scanning
            if (needPaddings) {
                needPaddings = false;
                int w = getWidth  ();
                int h = getHeight ();
                Bitmap bm = Bitmap.createBitmap (w, h, Bitmap.Config.RGB_565);
                bm.eraseColor (Color.BLACK);
                Canvas ca = new Canvas (bm);
                noOutline = true;
                super.draw (ca);
                noOutline = false;

                myPaddingLeft   = w;
                myPaddingRight  = 0;
                myPaddingTop    = h;
                myPaddingBottom = 0;
                for (int y = 0; y < h; y ++) {
                    for (int x = 0; x < w; x ++) {
                        int c = bm.getPixel (x, y);
                        if (c != Color.BLACK) {
                            if (myPaddingLeft   > x) myPaddingLeft   = x;
                            if (myPaddingRight  < x) myPaddingRight  = x;
                            if (myPaddingTop    > y) myPaddingTop    = y;
                            if (myPaddingBottom < y) myPaddingBottom = y;
                        }
                    }
                }
                myPaddingLeft  += MARGIN;
                myPaddingRight  = MARGIN + w - 1 - myPaddingRight;
                myPaddingTop   += MARGIN;
                myPaddingBottom = MARGIN + h - 1 - myPaddingBottom;

                /*if (false && "m".equals (GetLogo ())) {
                    Log.d (TAG, "onDraw*:  width=" + getWidth  () + " " + myPaddingLeft + " " + myPaddingRight);
                    Log.d (TAG, "onDraw*: height=" + getHeight () + " " + myPaddingTop  + " " + myPaddingBottom);
                    for (int y = 0; y < h; y ++) {
                        StringBuilder sb = new StringBuilder ();
                        for (int x = 0; x < w; x ++) {
                            int c = bm.getPixel (x, y);
                            sb.append ((c == Color.BLACK) ? '-' : '*');
                        }
                        Log.d (TAG, "onDraw*: " + y + ": " + sb.toString ());
                    }
                }*/
            }

            // draw blank button (cuz we did setText (" "))
            super.onDraw (canvas);

            // draw padding box outline on button
            /*if (false && !noOutline) {
                Paint p = new Paint ();
                p.setStrokeWidth (5);
                p.setColor (Color.CYAN);
                int cx = myPaddingLeft;
                int cy = myPaddingTop;
                canvas.drawLine (cx, cy, cx + 50, cy, p);
                canvas.drawLine (cx, cy, cx, cy + 50, p);
                p.setColor (Color.MAGENTA);
                int bw = getWidth () - myPaddingRight;
                int bh = getHeight () - myPaddingBottom;
                canvas.drawLine (bw, bh, bw - 50, bh, p);
                canvas.drawLine (bw, bh, bw, bh - 50, p);
            }*/

            // measure the size of the button
            int buttonWidth  = getWidth  ();
            int buttonHeight = getHeight ();

            // get the size of the drawable area of the button
            int drawWidth  = buttonWidth - myPaddingLeft - myPaddingRight;
            int drawHeight = buttonHeight - myPaddingTop - myPaddingBottom;

            // maybe we have a coloured background
            if (buttonColor != 0) {
                canvas.drawRect (myPaddingLeft, myPaddingTop, buttonWidth - myPaddingRight,
                        buttonHeight - myPaddingBottom, bgPaint);
            }

            // get text that goes on keytop
            String logo = GetLogo ();
            if (logo != null) {
                if (!logo.equals (lastLogo)) {
                    lastLogo     = logo;
                    logoLines    = logo.split ("\n");
                    lastTextSize = 0.0F;
                }

                // maybe make this text same size as another button's text
                if ((logo.length () > 1) && (sameTextSizeAs != null)) {
                    myPaint.setTextSize (sameTextSizeAs.myPaint.getTextSize ());
                    textHeight = sameTextSizeAs.textHeight;
                } else {

                    // scale text to fit drawable area
                    boolean first = true;
                    while ((myPaint.getTextSize () != lastTextSize) || (drawWidth != lastDrawWidth) || (drawHeight != lastDrawHeight)) {
                        lastTextSize   = myPaint.getTextSize ();
                        lastDrawWidth  = drawWidth;
                        lastDrawHeight = drawHeight;

                        // measure the size of the string if we drew it with the current text size
                        int textWidth = 0;
                        textHeight = 0;
                        for (String s : logoLines) {
                            // make sure we don't blow a single '.' up into a big blob
                            if (s.length () == 1) s = "M";
                            // measure the string size and save longest width and height
                            myPaint.getTextBounds (s, 0, s.length (), bounds);
                            if (textWidth  < bounds.width  ()) textWidth  = bounds.width  ();
                            if (textHeight < bounds.height ()) textHeight = bounds.height ();
                        }

                        if (!first) break;

                        // see how big we would have to scale the text up to fix the button in width and height
                        float scaleWidth  = (float) drawWidth  / (float) textWidth;
                        float scaleHeight = (float) drawHeight / (float) textHeight / logoLines.length;

                        // scale by the smaller of the two so we don't push something off the keytop
                        float scale = Math.min (scaleWidth, scaleHeight);
                        if (logo.length () == 1) scale *= 0.75F;
                        myPaint.setTextSize (lastTextSize * scale);
                        first = false;
                    }
                }

                // nothing changed, draw text
                int centerx = myPaddingLeft + drawWidth / 2;
                int y = myPaddingTop + (drawHeight - logoLines.length * textHeight) / 2;
                for (String s : logoLines) {
                    y += textHeight;
                    canvas.drawText (s, centerx, y, myPaint);
                }
            }
        }
    }
}

package com.outerworldapps.sshclient;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Message;
import android.support.v7.widget.GridLayout;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedList;

/**
 * Implementation of a VT-100 keyboard including keypad.
 */
public class VT100KBView extends LinearLayout {
    private static final String TAG = "SshClient";

    private static final int CLICKFEEDBACK  = 100;
    private static final int MATCH_PARENT   = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int MH_STOPFB      = 96;
    private static final int MH_REPEATS     = 97;
    private static final int MH_KBSCALE     = 98;
    private static final int MH_BUTSIZE     = 99;
    private static final int REPEAT_INITIAL = 500;
    private static final int REPEAT_REPEAT  = 200;
    private static final int WRAP_CONTENT   = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String ESC = "\033";

    private static Handler myHandler;

    private boolean cntrllock, cntrlmode;
    private boolean shiftlock, shiftmode, shiftsaved;
    private Button kbContrl;
    private Button kbShift1;
    private Button kbShift2;
    private float scaleX;
    private float scaleY;
    private int butSize;
    private LinkedList<KeyButton> allKeyButtons;
    private LinkedList<KeyButton> allShiftButtons;
    private MySession session;
    private SharedPreferences prefs;
    private SshClient sshclient;
    private String repeatStr;
    private Toast fbToast;

    public VT100KBView (MySession s)
    {
        super (s.getSshClient ());
        session   = s;
        sshclient = s.getSshClient ();

        if (myHandler == null) myHandler = new MyHandler ();

        prefs  = sshclient.getPreferences (Activity.MODE_PRIVATE);
        scaleX = prefs.getFloat ("VT100KBView.scaleX", 1.0F);
        scaleY = prefs.getFloat ("VT100KBView.scaleY", 1.0F);

        BuildKeyboard ();
    }

    /**
     * Build keyboard one button at a time.
     */
    private void BuildKeyboard ()
    {
        removeAllViews ();

        allKeyButtons   = new LinkedList<KeyButton> ();
        allShiftButtons = new LinkedList<KeyButton> ();
        butSize = 0;

        LinearLayout ll1 = this;
            ll1.setOrientation (LinearLayout.HORIZONTAL);

            LinearLayout ll2 = new LinearLayout (sshclient);
                LinearLayout.LayoutParams ll2lp = new LinearLayout.LayoutParams (MATCH_PARENT, MATCH_PARENT);
                ll2lp.gravity = Gravity.CENTER_VERTICAL | Gravity.RIGHT;
                ll2.setOrientation (LinearLayout.VERTICAL);
                ll2.setLayoutParams (ll2lp);

                LinearLayout ll3 = new LinearLayout (sshclient);
                    ll3.setOrientation (LinearLayout.HORIZONTAL);
                    ll3.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                    ll3.addView (new WideButton (ESC, "ESC"));
                    ll3.addView (new LcUcButton ('1', '!'));
                    ll3.addView (new LcUcButton ('2', '@'));
                    ll3.addView (new LcUcButton ('3', '#'));
                    ll3.addView (new LcUcButton ('4', '$'));
                    ll3.addView (new LcUcButton ('5', '%'));
                    ll3.addView (new LcUcButton ('6', '^'));
                    ll3.addView (new LcUcButton ('7', '&'));
                    ll3.addView (new LcUcButton ('8', '*'));
                    ll3.addView (new LcUcButton ('9', '('));
                    ll3.addView (new LcUcButton ('0', ')'));
                    ll3.addView (new LcUcButton ('-', '_'));
                    ll3.addView (new LcUcButton ('=', '+'));
                    ll3.addView (new LcUcButton ('`', '~'));
                ll2.addView (ll3);

                LinearLayout ll4 = new LinearLayout (sshclient);
                    ll4.setOrientation (LinearLayout.HORIZONTAL);
                    ll4.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                    ll4.addView (new WideButton ("\t", " TAB "));
                    ll4.addView (new LetterButton ('Q'));
                    ll4.addView (new LetterButton ('W'));
                    ll4.addView (new LetterButton ('E'));
                    ll4.addView (new LetterButton ('R'));
                    ll4.addView (new LetterButton ('T'));
                    ll4.addView (new LetterButton ('Y'));
                    ll4.addView (new LetterButton ('U'));
                    ll4.addView (new LetterButton ('I'));
                    ll4.addView (new LetterButton ('O'));
                    ll4.addView (new LetterButton ('P'));
                    ll4.addView (new LcUcButton ('[', '{'));
                    ll4.addView (new LcUcButton (']', '}'));
                    ll4.addView (new LcUcButton ('\\', '|'));
                ll2.addView (ll4);

                LinearLayout ll5 = new LinearLayout (sshclient);
                    ll5.setOrientation (LinearLayout.HORIZONTAL);
                    ll5.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                    kbContrl = new CtrlButton ();
                    ll5.addView (kbContrl);

                    ll5.addView (new LetterButton ('A'));
                    ll5.addView (new LetterButton ('S'));
                    ll5.addView (new LetterButton ('D'));
                    ll5.addView (new LetterButton ('F'));
                    ll5.addView (new LetterButton ('G'));
                    ll5.addView (new LetterButton ('H'));
                    ll5.addView (new LetterButton ('J'));
                    ll5.addView (new LetterButton ('K'));
                    ll5.addView (new LetterButton ('L'));
                    ll5.addView (new LcUcButton (';', ':'));
                    ll5.addView (new LcUcButton ('\'', '"'));
                    ll5.addView (new WideButton ("\r", "RETURN"));
                ll2.addView (ll5);

                LinearLayout ll6 = new LinearLayout (sshclient);
                    ll6.setOrientation (LinearLayout.HORIZONTAL);
                    ll6.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                    kbShift1 = new ShiftButton ();
                    ll6.addView (kbShift1);

                    ll6.addView (new LetterButton ('Z'));
                    ll6.addView (new LetterButton ('X'));
                    ll6.addView (new LetterButton ('C'));
                    ll6.addView (new LetterButton ('V'));
                    ll6.addView (new LetterButton ('B'));
                    ll6.addView (new LetterButton ('N'));
                    ll6.addView (new LetterButton ('M'));
                    ll6.addView (new LcUcButton (',', '<'));
                    ll6.addView (new LcUcButton ('.', '>'));
                    ll6.addView (new LcUcButton ('/', '?'));

                    kbShift2 = new ShiftButton ();
                    ll6.addView (kbShift2);

                    ll6.addView (new WideButton ("\177", "  DEL  "));
                ll2.addView (ll6);

                LinearLayout ll7 = new LinearLayout (sshclient);
                    ll7.setOrientation (LinearLayout.HORIZONTAL);
                    ll7.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                    ll7.addView (new WideButton ("\n", "LINEFEED"));
                    ll7.addView (new WideButton ("\b", "BACKSPACE"));
                    ll7.addView (new WideButton (" ", "                                                              "));
                    // http://unicode-table.com/en
                    ll7.addView (new ArrowButton ('A', 0x25B2, new int[] { 4, 2, 2, 6, 6, 6, 4, 2 }));
                    ll7.addView (new ArrowButton ('B', 0x25BC, new int[] { 4, 6, 2, 2, 6, 2, 4, 6 }));
                    ll7.addView (new ArrowButton ('D', 0x25C0, new int[] { 2, 4, 6, 2, 6, 6, 2, 4 }));
                    ll7.addView (new ArrowButton ('C', 0x25B6, new int[] { 6, 4, 2, 2, 2, 6, 6, 4 }));
                ll2.addView (ll7);
            ll1.addView (ll2);

            TextView tv1 = new TextView (sshclient);
                tv1.setLayoutParams (new LinearLayout.LayoutParams (WRAP_CONTENT, MATCH_PARENT));
                tv1.setText ("   ");
            ll1.addView (tv1);

            GridLayout gl1 = new GridLayout (sshclient);
                gl1.setLayoutParams (new LinearLayout.LayoutParams (WRAP_CONTENT, MATCH_PARENT));

                gl1.addView (new KPFuncButton (ESC + "OP", "PF1", 0));
                gl1.addView (new KPFuncButton (ESC + "OQ", "PF2", 1));
                gl1.addView (new KPFuncButton (ESC + "OR", "PF3", 2));
                gl1.addView (new KPFuncButton (ESC + "OS", "PF4", 3));

                gl1.addView (new KPDigitButton ('7', ESC + "Ow", 0, 1, 1, 1));
                gl1.addView (new KPDigitButton ('8', ESC + "Ox", 1, 1, 1, 1));
                gl1.addView (new KPDigitButton ('9', ESC + "Oy", 2, 1, 1, 1));
                gl1.addView (new KPDigitButton ('-', ESC + "Om", 3, 1, 1, 1));

                gl1.addView (new KPDigitButton ('4', ESC + "Ot", 0, 2, 1, 1));
                gl1.addView (new KPDigitButton ('5', ESC + "Ou", 1, 2, 1, 1));
                gl1.addView (new KPDigitButton ('6', ESC + "Ov", 2, 2, 1, 1));
                gl1.addView (new KPDigitButton (',', ESC + "Ol", 3, 2, 1, 1));

                gl1.addView (new KPDigitButton ('1', ESC + "Oq", 0, 3, 1, 1));
                gl1.addView (new KPDigitButton ('2', ESC + "Or", 1, 3, 1, 1));
                gl1.addView (new KPDigitButton ('3', ESC + "Os", 2, 3, 1, 1));
                gl1.addView (new KPEnterButton ());

                gl1.addView (new KPDigitButton ('0', ESC + "Op", 0, 4, 2, 1));
                gl1.addView (new KPDigitButton ('.', ESC + "On", 2, 4, 1, 1));
            ll1.addView (gl1);

        SetCtrlShiftColors ();
    }

    /**
     * After key is released, reset ctrl & shift modes.
     * Invalidate shiftable buttons if the shift mode changed so they can re-draw their legends.
     */
    private void KeyReleased (boolean sent)
    {
        if (sent) {
            cntrlmode = cntrllock;
            shiftmode = shiftlock;
        }
        SetCtrlShiftColors ();
        if (shiftsaved != shiftmode) {
            shiftsaved = shiftmode;
            for (KeyButton b : allShiftButtons) {
                b.invalidate ();
            }
        }
    }

    /**
     * Set the colors of the ctrl & shift keys.
     */
    private void SetCtrlShiftColors ()
    {
        kbContrl.setTextColor (cntrllock ? Color.RED : cntrlmode ? Color.GREEN : Color.BLACK);
        kbShift1.setTextColor (shiftlock ? Color.RED : shiftmode ? Color.GREEN : Color.BLACK);
        kbShift2.setTextColor (shiftlock ? Color.RED : shiftmode ? Color.GREEN : Color.BLACK);
    }

    /**
     * Send the given string to the host.
     * It may be either a single character or multiple chars as in an escape sequence.
     */
    private void SendToHost (String str)
    {
        /*{
            StringBuffer sb = new StringBuffer ();
            for (char c : str.toCharArray ()) {
                if ((c >= 32) && (c != '\\') && (c != 127)) sb.append (c);
                else switch (c) {
                    case '\b': sb.append ("\\b"); break;
                    case '\n': sb.append ("\\n"); break;
                    case '\r': sb.append ("\\r"); break;
                    case '\t': sb.append ("\\t"); break;
                    case '\\': sb.append ("\\\\"); break;
                    default: {
                        sb.append ('\\');
                        for (int i = 9; (i -= 3) >= 0;) {
                            sb.append ((char) ('0' + ((c >> i) & 7)));
                        }
                        break;
                    }
                }
            }
            Log.d (TAG, "to host '" + sb.toString () + "'");
        }*/

        session.getScreentextview ().ProcessKeyboardString (str);
        performHapticFeedback (HapticFeedbackConstants.KEYBOARD_TAP);
    }

    /**
     * Handle processing in GUI thread at top level.
     */
    private static class MyHandler extends Handler {
        public void handleMessage (Message m) {
            switch (m.what) {

                // stop feedback
                case MH_STOPFB: {
                    KeyButton kb = (KeyButton) m.obj;
                    kb.StopFeedback ();
                    break;
                }

                // repeated key
                case MH_REPEATS: {
                    VT100KBView kbv = (VT100KBView) m.obj;
                    if (kbv.repeatStr != null) {
                        kbv.SendToHost (kbv.repeatStr);
                        m = myHandler.obtainMessage ();
                        m.what = MH_REPEATS;
                        m.obj = kbv;
                        myHandler.sendMessageDelayed (m, REPEAT_REPEAT);
                    }
                    break;
                }

                // keyboard resized
                case MH_KBSCALE: {
                    VT100KBView kbv = (VT100KBView) m.obj;
                    kbv.BuildKeyboard ();

                    SharedPreferences.Editor editr = kbv.prefs.edit ();
                    editr.putFloat ("VT100KBView.scaleX", kbv.scaleX);
                    editr.putFloat ("VT100KBView.scaleY", kbv.scaleY);
                    editr.commit ();
                    break;
                }

                // buttons resized
                case MH_BUTSIZE: {
                    VT100KBView kbv = (VT100KBView) m.obj;
                    int bs = kbv.butSize;
                    for (KeyButton b : kbv.allKeyButtons) {
                        b.setStdSize (bs);
                    }
                    kbv.requestLayout ();
                    break;
                }
            }
        }
    }

    /**
     * These are the letter buttons, A-Z
     */
    private class LetterButton extends SingleButton {
        private String ctrlstr;
        private String lcstr;
        private String ucstr;

        public LetterButton (char uc)
        {
            super (uc);

            setLayoutParams (new LinearLayout.LayoutParams (WRAP_CONTENT, MATCH_PARENT));

            ctrlstr = new String (new char [] { (char) (uc - 64) });
            lcstr   = new String (new char [] { (char) (uc + 32) });
            ucstr   = new String (new char [] { uc });

            // we need to be re-drawn whenever shiftmode changes
            allShiftButtons.addLast (this);
        }

        @Override  // KeyButton
        public void setStdSize (int size)
        {
            setWidth (size);
            setHeight (size);
        }

        @Override  // KeyButton
        public String decode ()
        {
            if (cntrlmode) return ctrlstr;
            if (shiftmode) return ucstr;
            return lcstr;
        }

        @Override  // KeyButton
        public CharSequence feedback ()
        {
            if (cntrlmode) return "^" + ucstr;
            if (shiftmode) return ucstr;
            return lcstr;
        }

        @Override  // SingleButton
        public void onDraw (Canvas c)
        {
            letter[0] = (shiftmode ? ucstr : lcstr).charAt (0);
            super.onDraw (c);
        }
    }

    /**
     * These are the keypad digit buttons, 0-9
     */
    private class KPDigitButton extends SingleButton {
        private int wid, hei;
        private String appstr;
        private String numstr;

        public KPDigitButton (char num, String app, int x, int y, int w, int h)
        {
            super (num);

            wid = w;
            hei = h;

            GridLayout.LayoutParams glp = new GridLayout.LayoutParams ();
            glp.columnSpec = GridLayout.spec (x, w);
            glp.rowSpec    = GridLayout.spec (y, h);
            setLayoutParams (glp);

            appstr = app;
            numstr = new String (new char[] { num });
        }

        @Override  // KeyButton
        public void setStdSize (int size)
        {
            setWidth  (size * wid);
            setHeight (size * hei);
        }

        @Override  // KeyButton
        public String decode ()
        {
            boolean appmode = session.getScreentextbuffer ().keypadappmode;
            return appmode ? appstr : numstr;
        }

        @Override  // KeyButton
        public CharSequence feedback ()
        {
            return numstr;
        }
    }

    /**
     * A keyboard or keypad button with a single character legend.
     */
    private abstract class SingleButton extends KeyButton {
        protected char[] letter;
        private Paint paint;
        private Paint.FontMetrics metrics;

        public SingleButton (char l)
        {
            setTypeface (Typeface.MONOSPACE);
            setText ("   ");

            letter = new char[] { l };

            paint = new Paint ();
            paint.setColor (getPaint ().getColor ());
            paint.setStrokeWidth (getPaint ().getStrokeWidth () * 2);
            paint.setTextAlign (Paint.Align.CENTER);
            paint.setTextSize (getTextSize () * 2.0F);

            metrics = new Paint.FontMetrics ();
        }

        /**
         * Draw the single character twice as big as Android thinks it is.
         * We have the normal button draw 3 monospaced spaces to make room.
         */
        @Override  // Button
        public void onDraw (Canvas canvas)
        {
            // this should give us a button with spaces big enough for us
            super.onDraw (canvas);

            // draw the oversized character
            paint.getFontMetrics (metrics);

            float x = getWidth () / 2.0F;
            float y = (getHeight () - metrics.ascent - metrics.descent) / 2.0F;

            canvas.drawText (letter, 0, 1, x, y, paint);

            // also, if this is the '0' button,
            // resize all the buttons relative to this one
            if ((butSize == 0) && (letter[0] == '0')) {
                butSize = (int) (getHeight () * scaleY);
                if (butSize > 0) {
                    Log.d (TAG, "VT100KBView.onDraw: butSize=" + butSize);
                    if (myHandler != null) {
                        Message m = myHandler.obtainMessage ();
                        m.what = MH_BUTSIZE;
                        m.obj  = VT100KBView.this;
                        myHandler.sendMessage (m);
                    }
                }
            }
        }
    }

    /**
     * These are the keys that have upper/lower case characters, eg, ! 1
     */
    private class LcUcButton extends KeyButton {
        private Paint bigpaint, smlpaint;
        private Paint.FontMetrics metrics;
        private String lcstr;
        private String ucstr;

        public LcUcButton (char lc, char uc)
        {
            setLayoutParams (new LinearLayout.LayoutParams (WRAP_CONTENT, MATCH_PARENT));

            setTypeface (Typeface.MONOSPACE);
            setText ("   ");

            bigpaint = new Paint ();
            bigpaint.setColor (getPaint ().getColor ());
            bigpaint.setStrokeWidth (getPaint ().getStrokeWidth () * 2);
            bigpaint.setTextAlign (Paint.Align.CENTER);
            bigpaint.setTextSize (getTextSize () * 2.0F);

            smlpaint = new Paint ();
            smlpaint.setColor (getPaint ().getColor ());
            smlpaint.setStrokeWidth (getPaint ().getStrokeWidth ());
            smlpaint.setTextAlign (Paint.Align.CENTER);
            smlpaint.setTextSize (getTextSize ());

            metrics = new Paint.FontMetrics ();

            lcstr = new String (new char[] { lc });
            ucstr = new String (new char[] { uc });

            // we need to be re-drawn whenever shiftmode changes
            allShiftButtons.addLast (this);
        }

        @Override  // KeyButton
        public void setStdSize (int size)
        {
            setWidth (size);
            setHeight (size);
        }

        @Override  // KeyButton
        public String decode ()
        {
            String ret = shiftmode ? ucstr : lcstr;
            if (cntrlmode) {
                char chr = ret.charAt (0);
                if ((chr >= 64) && (chr <= 95)) {
                    ret = new String (new char[] { (char) (chr & 31) });
                }
            }
            return ret;
        }

        @Override  // KeyButton
        public CharSequence feedback ()
        {
            String ret = shiftmode ? ucstr : lcstr;
            if (cntrlmode) {
                char chr = ret.charAt (0);
                if ((chr >= 64) && (chr <= 95)) {
                    ret = "^" + ret;
                }
            }
            return ret;
        }

        /**
         * Draw active character oversized.
         * Draw inactive characer undersized in upper left corner.
         */
        @Override  // Button
        public void onDraw (Canvas canvas)
        {
            super.onDraw (canvas);

            String fgstr = shiftmode ? ucstr : lcstr;
            String bgstr = shiftmode ? lcstr : ucstr;

            bigpaint.getFontMetrics (metrics);

            float fgx = getWidth () / 2.0F;
            float fgy = (getHeight () - metrics.ascent - metrics.descent) / 2.0F;

            canvas.drawText (fgstr, 0, 1, fgx, fgy, bigpaint);

            smlpaint.getFontMetrics (metrics);

            float bgx = getWidth ()  / 5.0F;
            float bgy = getHeight () / 5.0F - metrics.ascent / 2.0F - metrics.descent / 2.0F;

            canvas.drawText (bgstr, 0, 1, bgx, bgy, smlpaint);
        }
    }

    /**
     * Up, Down, Left, Right arrow keys.
     */
    private class ArrowButton extends KeyButton implements View.OnClickListener {
        private char letter;
        private int[] rawpath;
        private Path guipath = new Path ();
        private String appstr, curstr, fbstr;

        public ArrowButton (char l, int fbchr, int[] p)
        {
            setLayoutParams (new LinearLayout.LayoutParams (WRAP_CONTENT, MATCH_PARENT));

            letter  = l;
            appstr  = ESC + "O" + l;
            curstr  = ESC + "[" + l;
            rawpath = p;

            fbstr = new String (new char[] { (char) fbchr });

            // set up a button same size as letter buttons
            setTypeface (Typeface.MONOSPACE);
            setText ("   ");
        }

        @Override  // KeyButton
        public void setStdSize (int size)
        {
            setHeight (size);
            setWidth (size);

            int w = getWidth ();
            int h = getHeight ();

            guipath.rewind ();

            for (int i = 0; i < rawpath.length;) {
                float x = w * rawpath[i++] / 8.0F;
                float y = h * rawpath[i++] / 8.0F;
                if (i == 0) guipath.moveTo (x, y);
                else guipath.lineTo (x, y);
            }

            invalidate ();
        }

        @Override  // KeyButton
        public String decode ()
        {
            boolean appmode = session.getScreentextbuffer ().cursorappmode;
            return appmode ? appstr : curstr;
        }

        @Override  // KeyButton
        public CharSequence feedback ()
        {
            return fbstr;
        }

        @Override  // KeyButton
        public void onClick (View v)
        {
            if (cntrllock && shiftlock) {
                switch (letter) {
                    case 'A': {
                        scaleY *= 1.125F;
                        break;
                    }
                    case 'B': {
                        scaleY /= 1.125F;
                        break;
                    }
                    case 'C': {
                        scaleX *= 1.125F;
                        break;
                    }
                    case 'D': {
                        scaleX /= 1.125F;
                        break;
                    }
                }
                Log.d (TAG, "VT100KBView: scaleX,Y=" + scaleX + "," + scaleY);
                Message m = myHandler.obtainMessage ();
                m.what = MH_KBSCALE;
                m.obj  = VT100KBView.this;
                myHandler.sendMessage (m);
            } else {
                super.onClick (v);
            }
        }

        @Override  // Button
        public void onDraw (Canvas c)
        {
            // draw blank button
            super.onDraw (c);

            // draw arrow
            c.drawPath (guipath, getPaint ());
        }
    }

    /**
     * Buttons with wide strings such as TAB and RETURN.
     */
    private class WideButton extends KeyButton {
        protected String str;

        public WideButton (String s, String logo)
        {
            str = s;

            setLayoutParams (new LinearLayout.LayoutParams (WRAP_CONTENT, MATCH_PARENT));
            setText (logo);
        }

        @Override  // KeyButton
        public void setStdSize (int size)
        {
            setHeight (size);
        }

        @Override  // KeyButton
        public String decode ()
        {
            return str;
        }

        @Override  // KeyButton
        public CharSequence feedback ()
        {
            return str.equals (" ") ? null : getText ();
        }
    }

    /**
     * Keypad PF<n> button.
     */
    private class KPFuncButton extends KeyButton {
        private String str;

        public KPFuncButton (String s, String logo, int x)
        {
            str = s;

            GridLayout.LayoutParams glp = new GridLayout.LayoutParams ();
            glp.columnSpec = GridLayout.spec (x, 1);
            glp.rowSpec    = GridLayout.spec (0, 1);
            setLayoutParams (glp);
            setTypeface (Typeface.MONOSPACE);
            setText (logo);
        }

        @Override  // KeyButton
        public void setStdSize (int size)
        {
            setWidth (size);
            setHeight (size);
        }

        @Override  // KeyButton
        public String decode ()
        {
            return str;
        }

        @Override  // KeyButton
        public CharSequence feedback ()
        {
            return getText ();
        }
    }

    /**
     * Keypad ENTER key.
     */
    private class KPEnterButton extends KeyButton {
        public KPEnterButton ()
        {
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams ();
            glp.columnSpec = GridLayout.spec (3, 1);
            glp.rowSpec    = GridLayout.spec (3, 2);
            setLayoutParams (glp);
            setTypeface (Typeface.MONOSPACE);
            setText (" E \n N \n T \n E \n R ");
            setTextSize (TypedValue.COMPLEX_UNIT_PX, getTextSize () * 0.8F);
        }

        @Override
        public void setStdSize (int size)
        {
            setWidth (size);
            setHeight (size * 2);
        }

        @Override
        public String decode ()
        {
            boolean appmode = session.getScreentextbuffer ().keypadappmode;
            return appmode ? ESC + "OM" : "\r";
        }

        @Override  // KeyButton
        public CharSequence feedback ()
        {
            return "ENTER";
        }
    }

    /**
     * Control (CTRL) button.
     */
    private class CtrlButton extends KeyButton {
        public CtrlButton ()
        {
            setLayoutParams (new LinearLayout.LayoutParams (WRAP_CONTENT, MATCH_PARENT));
            setText (" CTRL ");
        }

        @Override  // KeyButton
        public void setStdSize (int size)
        {
            setHeight (size);
        }

        @Override  // KeyButton
        public String decode ()
        {
            cntrllock = cntrlmode & !cntrllock;
            cntrlmode = cntrllock | !cntrlmode;
            return null;
        }

        @Override  // KeyButton
        public CharSequence feedback ()
        {
            return null;
        }
    }

    /**
     * Shift (SHIFT) button.
     */
    private class ShiftButton extends KeyButton {
        public ShiftButton ()
        {
            setLayoutParams (new LinearLayout.LayoutParams (WRAP_CONTENT, MATCH_PARENT));
            setText (" SHIFT ");
        }

        @Override  // KeyButton
        public void setStdSize (int size)
        {
            setHeight (size);
        }

        @Override  // KeyButton
        public String decode ()
        {
            shiftlock = shiftmode & !shiftlock;
            shiftmode = shiftlock | !shiftmode;
            return null;
        }

        @Override  // KeyButton
        public CharSequence feedback ()
        {
            return null;
        }
    }

    /**
     * Just like a button, except provides method to decode to get key's string.
     */
    private abstract class KeyButton extends Button
            implements View.OnClickListener, View.OnLongClickListener {
        public KeyButton ()
        {
            super (sshclient);

            setTextSize (TypedValue.COMPLEX_UNIT_PX, getTextSize () * scaleY);

            setOnClickListener (this);
            setOnLongClickListener (this);

            allKeyButtons.addLast (this);
        }

        public abstract void setStdSize (int size);
        public abstract String decode ();
        public abstract CharSequence feedback ();

        /**
         * The key was clicked.  Decode it and send string to host.
         */
        @Override  // View.OnClickListener
        public void onClick (View v)
        {
            // decode the key pressed and send string to host
            String str = decode ();
            if (str != null) {
                SendToHost (str);
                StartFeedback ();
                Message m = myHandler.obtainMessage ();
                m.what = MH_STOPFB;
                m.obj  = this;
                myHandler.sendMessageDelayed (m, CLICKFEEDBACK);
            }

            // update state of ctrl & shift keys
            KeyReleased (str != null);
        }

        @Override  // View.OnLongClickListener
        public boolean onLongClick (View v)
        {
            repeatStr = ((KeyButton) v).decode ();
            if (repeatStr != null) {
                SendToHost (repeatStr);
                StartFeedback ();
                Message m = myHandler.obtainMessage ();
                m.what = MH_REPEATS;
                m.obj  = VT100KBView.this;
                myHandler.sendMessageDelayed (m, REPEAT_INITIAL);
            } else {
                KeyReleased (false);
            }
            return true;
        }

        @Override  // Button
        public boolean onTouchEvent (MotionEvent event)
        {
            switch (event.getAction ()) {
                case MotionEvent.ACTION_UP: {
                    StopFeedback ();
                    if (repeatStr != null) {
                        repeatStr = null;
                        KeyReleased (true);
                    }
                    break;
                }
            }

            return super.onTouchEvent (event);
        }

        /**
         * Display visual feedback showing which key was pressed.
         */
        private void StartFeedback ()
        {
            // if some feedback already shown for any key, get rid of it
            StopFeedback ();

            // see if this key provides any feedback
            CharSequence fb = feedback ();
            if (fb != null) {

                // get size and absolute position of this button
                int w = getWidth ();
                int h = getHeight ();

                int x = getLeft () - getScrollX ();
                int y = getTop ()  - getScrollY ();
                ViewParent parent;
                for (parent = getParent (); parent != null; parent = parent.getParent ()) {
                    if (parent instanceof View) {
                        View pv = (View) parent;
                        x += pv.getLeft () - pv.getScrollX ();
                        y += pv.getTop ()  - pv.getScrollY ();
                    }
                }

                // create a simple text box to display the feedback string
                TextView tv = new TextView (sshclient);
                tv.setBackgroundColor (Color.DKGRAY);
                tv.setTextColor (Color.WHITE);
                float ts = getTextSize ();
                if (fb.length () <= 2) ts *= 2.0F;
                tv.setTextSize (TypedValue.COMPLEX_UNIT_PX, ts);
                tv.setGravity (Gravity.CENTER);  // center the text in the box
                tv.setWidth (w);
                tv.setHeight (h);
                tv.setText (fb);

                // display it as a popup toast message just above the key button
                fbToast = new Toast (sshclient);
                fbToast.setView (tv);
                fbToast.setGravity (Gravity.TOP | Gravity.LEFT, x, y - h * 2);
                fbToast.setDuration (Toast.LENGTH_LONG);
                fbToast.show ();
            }
        }

        /**
         * Remove visual feedback.
         */
        public void StopFeedback ()
        {
            if (fbToast != null) {
                fbToast.cancel ();
                fbToast = null;
            }
        }
    }
}

/**
 * Implementation of a VT-100 keyboard including keypad.
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
import android.support.v7.widget.GridLayout;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedList;

@SuppressLint("ViewConstructor")
public class VT100KBView extends KeybdView {
    private final static String ESC = "\033";

    private MySession session;

    public VT100KBView (MySession s, KeyboardableView kv)
    {
        super (s, kv);
        session = s;
    }

    /**
     * Build keyboard one button at a time.
     */
    @Override
    protected void BuildKeyboard ()
    {
        removeAllViews ();

        allKeyButtons    = new LinkedList<> ();
        allKeypadButtons = new LinkedList<> ();
        allShiftButtons  = new LinkedList<> ();
        butSize          = 0;

        altButtons.buttons.clear ();
        ctrlButtons.buttons.clear ();
        shiftButtons.buttons.clear ();

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

                    ll3.addView (new SizedButton (ESC, 1.0F, "ESC"));
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

                    ll4.addView (new SizedButton ("\t", 1.3F, "TAB"));
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

                    SizedButton retbut = new SizedButton ("\r", 1.6F, "RETURN");

                    ll5.addView (new LockableButton (null, null, ctrlButtons, 1.7F, "CTRL"));
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
                    ll5.addView (retbut);
                ll2.addView (ll5);

                LinearLayout ll6 = new LinearLayout (sshclient);
                    ll6.setOrientation (LinearLayout.HORIZONTAL);
                    ll6.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                    ll6.addView (new LockableButton (null, null, shiftButtons, 2.0F, "SHIFT"));
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

                    ll6.addView (new LockableButton (null, null, shiftButtons, 2.0F, "SHIFT"));
                ll2.addView (ll6);

                LinearLayout ll7 = new LinearLayout (sshclient);
                    ll7.setOrientation (LinearLayout.HORIZONTAL);
                    ll7.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                    ll7.addView (new SizedButton ("\n", 2.0F, "LINEFEED", retbut));
                    ll7.addView (new SizedButton ("\b", 2.1F, "BACKSPACE", retbut));
                    ll7.addView (new SizedButton (" ", 4.3F, " "));
                    ll7.addView (new ArrowButton (ESC + "[A", ArrowButton.UP));
                    ll7.addView (new ArrowButton (ESC + "[B", ArrowButton.DN));
                    ll7.addView (new ArrowButton (ESC + "[D", ArrowButton.LF));
                    ll7.addView (new ArrowButton (ESC + "[C", ArrowButton.RT));
                    ll7.addView (new SizedButton ("\177", 1.6F, "DEL"));
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

                gl1.addView (new KPDigitButton ("7", ESC + "Ow", 0, 1, 1, 1));
                gl1.addView (new KPDigitButton ("8", ESC + "Ox", 1, 1, 1, 1));
                gl1.addView (new KPDigitButton ("9", ESC + "Oy", 2, 1, 1, 1));
                gl1.addView (new KPDigitButton ("-", ESC + "Om", 3, 1, 1, 1));

                gl1.addView (new KPDigitButton ("4", ESC + "Ot", 0, 2, 1, 1));
                gl1.addView (new KPDigitButton ("5", ESC + "Ou", 1, 2, 1, 1));
                gl1.addView (new KPDigitButton ("6", ESC + "Ov", 2, 2, 1, 1));
                gl1.addView (new KPDigitButton (",", ESC + "Ol", 3, 2, 1, 1));

                gl1.addView (new KPDigitButton ("1", ESC + "Oq", 0, 3, 1, 1));
                gl1.addView (new KPDigitButton ("2", ESC + "Or", 1, 3, 1, 1));
                gl1.addView (new KPDigitButton ("3", ESC + "Os", 2, 3, 1, 1));
                gl1.addView (new KeypadButton (3, 3, 1, 2, "\r", "E\nn\nt\ne\nr", ESC + "OM", "E\nn\nt\ne\nr", null));

                gl1.addView (new KPDigitButton ("0", ESC + "Op", 0, 4, 2, 1));
                gl1.addView (new KPDigitButton (".", ESC + "On", 2, 4, 1, 1));
            ll1.addView (gl1);
    }

    /**
     * Send the given key code to the host.
     * It may be either a single character or multiple chars as in an escape sequence.
     */
    @Override
    protected void SendCodeToHost (Object code, int down)
    {
        if ((code != null) && (down != 0)) {
            String str = (String) code;

            // maybe control key is in effect for letter keys
            if ((str.length () == 1) && (ctrlButtons.mode != MODE_OFF)) {
                char chr = str.charAt (0);
                if ((chr >= 0x40) && (chr <= 0x7F)) {
                    str = new String (new char[] { (char) (chr & 0x1F) });
                }
            }

            // maybe cursor app mode is in effect for arrow keys
            if ((str.length () == 3) && (str.charAt (0) == ESC.charAt (0)) && (str.charAt(1) == '[') && session.getScreentextbuffer ().cursorappmode) {
                char chr = str.charAt (2);
                if ((chr >= 'A') && (chr <= 'D')) str = ESC + "O" + chr;
            }

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
    }

    /**
     * Get the code for a given lowercase/uppercase key code logo string
     * @param str = logo string on keytop
     * @return code to give to SendCodeToHost()
     */
    @Override
    protected Object GetLcUcCode (CharSequence str)
    {
        return str;
    }

    /**
     * Get whether or not the keypad is in numeric or application mode.
     * @return true: send numeric codes to host
     *        false: send application codes to host
     */
    @Override
    protected boolean GetKeypadAppMode ()
    {
        return (session != null) && session.getScreentextbuffer ().keypadappmode;
    }

    /**
     * These are the keypad digit buttons, 0-9
     */
    private class KPDigitButton extends KeypadButton {
        public KPDigitButton (String num, String app, int x, int y, int w, int h)
        {
            super (x, y, w, h, num, num, app, num);
        }
    }

    /**
     * Keypad PF<n> button.
     */
    private class KPFuncButton extends KeypadButton {
        public KPFuncButton (String s, String logo, int x)
        {
            super (x, 0, 1, 1, s, logo, s, logo);
        }
    }
}

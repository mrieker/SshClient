/**
 * Implementation of a PC-style keyboard including keypad.
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


import android.graphics.Canvas;
import android.graphics.Color;
import android.support.annotation.NonNull;
import android.support.v7.widget.GridLayout;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedList;

public class PeeCeeKBView extends KeybdView {

    private boolean numLocked;
    private MySession session;

    public PeeCeeKBView (MySession s, KeyboardableView kv)
    {
        super (s, kv);
        session = s;
    }

    /**
     * Build keyboard one button at a time.
     */
    @Override  // KeybdView
    protected void BuildKeyboard ()
    {
        removeAllViews ();

        allKeyButtons    = new LinkedList<KeyButton> ();
        allKeypadButtons = new LinkedList<KeypadButton> ();
        allShiftButtons  = new LinkedList<KeyButton> ();
        butSize          = 0;

        altButtons.buttons.clear ();
        ctrlButtons.buttons.clear ();
        shiftButtons.buttons.clear ();

        setOrientation (LinearLayout.HORIZONTAL);

        // keycodes come from /usr/include/X11/keysymmap.h
        // can be verified with shell xev command

        LinearLayout llLeft = new LinearLayout (sshclient);
            LinearLayout.LayoutParams llLeftlp = new LinearLayout.LayoutParams (WRAP_CONTENT, MATCH_PARENT);
            llLeft.setOrientation (LinearLayout.VERTICAL);
            llLeft.setLayoutParams (llLeftlp);

            LinearLayout llLeftA = new LinearLayout (sshclient);
                llLeftA.setOrientation (LinearLayout.HORIZONTAL);
                llLeftA.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                SizedButton escbut = new SizedButton (0xFF1B, 1.0F, "ESC");

                llLeftA.addView (escbut);
                llLeftA.addView (HorizSpacer ());
                llLeftA.addView (new SizedButton (0xFFBE, 1.0F, "F1",  escbut));
                llLeftA.addView (new SizedButton (0xFFBF, 1.0F, "F2",  escbut));
                llLeftA.addView (new SizedButton (0xFFC0, 1.0F, "F3",  escbut));
                llLeftA.addView (new SizedButton (0xFFC1, 1.0F, "F4",  escbut));
                llLeftA.addView (HorizSpacer ());
                llLeftA.addView (new SizedButton (0xFFC2, 1.0F, "F5",  escbut));
                llLeftA.addView (new SizedButton (0xFFC3, 1.0F, "F6",  escbut));
                llLeftA.addView (new SizedButton (0xFFC4, 1.0F, "F7",  escbut));
                llLeftA.addView (new SizedButton (0xFFC5, 1.0F, "F8",  escbut));
                llLeftA.addView (HorizSpacer ());
                llLeftA.addView (new SizedButton (0xFFC6, 1.0F, "F9",  escbut));
                llLeftA.addView (new SizedButton (0xFFC7, 1.0F, "F10", escbut));
                llLeftA.addView (new SizedButton (0xFFC8, 1.0F, "F11", escbut));
                llLeftA.addView (new SizedButton (0xFFC9, 1.0F, "F12", escbut));
            llLeft.addView (llLeftA);

            LinearLayout llLeftB = new LinearLayout (sshclient);
                llLeftB.setOrientation (LinearLayout.HORIZONTAL);
                llLeftB.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                llLeftB.addView (new LcUcButton ('`', '~'));
                llLeftB.addView (new LcUcButton ('1', '!'));
                llLeftB.addView (new LcUcButton ('2', '@'));
                llLeftB.addView (new LcUcButton ('3', '#'));
                llLeftB.addView (new LcUcButton ('4', '$'));
                llLeftB.addView (new LcUcButton ('5', '%'));
                llLeftB.addView (new LcUcButton ('6', '^'));
                llLeftB.addView (new LcUcButton ('7', '&'));
                llLeftB.addView (new LcUcButton ('8', '*'));
                llLeftB.addView (new LcUcButton ('9', '('));
                llLeftB.addView (new LcUcButton ('0', ')'));
                llLeftB.addView (new LcUcButton ('-', '_'));
                llLeftB.addView (new LcUcButton ('=', '+'));
                llLeftB.addView (new SizedButton (0xFF08, 1.3F, "Backspace"));
            llLeft.addView (llLeftB);

            LinearLayout llLeftC = new LinearLayout (sshclient);
                llLeftC.setOrientation (LinearLayout.HORIZONTAL);
                llLeftC.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                SizedButton tabbut = new SizedButton (0xFF09, 1.3F, "Tab");

                llLeftC.addView (tabbut);
                llLeftC.addView (new LetterButton ('Q'));
                llLeftC.addView (new LetterButton ('W'));
                llLeftC.addView (new LetterButton ('E'));
                llLeftC.addView (new LetterButton ('R'));
                llLeftC.addView (new LetterButton ('T'));
                llLeftC.addView (new LetterButton ('Y'));
                llLeftC.addView (new LetterButton ('U'));
                llLeftC.addView (new LetterButton ('I'));
                llLeftC.addView (new LetterButton ('O'));
                llLeftC.addView (new LetterButton ('P'));
                llLeftC.addView (new LcUcButton ('[', '{'));
                llLeftC.addView (new LcUcButton (']', '}'));
                llLeftC.addView (new LcUcButton ('\\', '|'));
            llLeft.addView (llLeftC);

            LinearLayout llLeftD = new LinearLayout (sshclient);
                llLeftD.setOrientation (LinearLayout.HORIZONTAL);
                llLeftD.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                llLeftD.addView (new CapsLockButton ());
                llLeftD.addView (new LetterButton ('A'));
                llLeftD.addView (new LetterButton ('S'));
                llLeftD.addView (new LetterButton ('D'));
                llLeftD.addView (new LetterButton ('F'));
                llLeftD.addView (new LetterButton ('G'));
                llLeftD.addView (new LetterButton ('H'));
                llLeftD.addView (new LetterButton ('J'));
                llLeftD.addView (new LetterButton ('K'));
                llLeftD.addView (new LetterButton ('L'));
                llLeftD.addView (new LcUcButton (';', ':'));
                llLeftD.addView (new LcUcButton ('\'', '"'));
                llLeftD.addView (new SizedButton (0xFF0D, 1.6F, "Enter", tabbut));
            llLeft.addView (llLeftD);

            LinearLayout llLeftE = new LinearLayout (sshclient);
                llLeftE.setOrientation (LinearLayout.HORIZONTAL);
                llLeftE.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                llLeftE.addView (new LockableButton (0xFFE1, tabbut, shiftButtons, 2.1F, "Shift"));
                llLeftE.addView (new LetterButton ('Z'));
                llLeftE.addView (new LetterButton ('X'));
                llLeftE.addView (new LetterButton ('C'));
                llLeftE.addView (new LetterButton ('V'));
                llLeftE.addView (new LetterButton ('B'));
                llLeftE.addView (new LetterButton ('N'));
                llLeftE.addView (new LetterButton ('M'));
                llLeftE.addView (new LcUcButton (',', '<'));
                llLeftE.addView (new LcUcButton ('.', '>'));
                llLeftE.addView (new LcUcButton ('/', '?'));
                llLeftE.addView (new LockableButton (0xFFE2, tabbut, shiftButtons, 2.1F, "Shift"));
            llLeft.addView (llLeftE);

            LinearLayout llLeftF = new LinearLayout (sshclient);
                llLeftF.setOrientation (LinearLayout.HORIZONTAL);
                llLeftF.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                llLeftF.addView (new LockableButton (0xFFE3, tabbut, ctrlButtons, 1.4F, "Ctrl"));
                llLeftF.addView (new SizedButton    (0xFFEB, 1.4F, "Flag", tabbut));
                llLeftF.addView (new LockableButton (0xFFE9, tabbut, altButtons,  1.4F, "Alt"));
                llLeftF.addView (new SizedButton    (0x0020, 4.4F, " "));
                llLeftF.addView (new LockableButton (0xFFEA, tabbut, altButtons,  1.4F, "Alt"));
                llLeftF.addView (new SizedButton    (0xFFEC, 1.4F, "Flag", tabbut));
                llLeftF.addView (new SizedButton    (0xFF67, 1.4F, "Menu", tabbut));
                llLeftF.addView (new LockableButton (0xFFE4, tabbut, ctrlButtons, 1.4F, "Ctrl"));
            llLeft.addView (llLeftF);
        addView (llLeft);

        addView (HorizSpacer ());

        LinearLayout llMid = new LinearLayout (sshclient);
            LinearLayout.LayoutParams llMidlp = new LinearLayout.LayoutParams (WRAP_CONTENT, MATCH_PARENT);
            llMid.setOrientation (LinearLayout.VERTICAL);
            llMid.setLayoutParams (llMidlp);

            LinearLayout llMidA = new LinearLayout (sshclient);
                llMidA.setOrientation (LinearLayout.HORIZONTAL);
                llMidA.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                SizedButton slkbut = new SizedButton (0xFF14, 1.0F, "Scroll\nLock");

                llMidA.addView (new SizedButton (0xFF61, 1.0F, "Print\nScreen\nSysRq"));
                llMidA.addView (slkbut);
                llMidA.addView (new SizedButton (0xFF13, 1.0F, "Pause\nBreak", slkbut));
            llMid.addView (llMidA);

            LinearLayout llMidB = new LinearLayout (sshclient);
                llMidB.setOrientation (LinearLayout.HORIZONTAL);
                llMidB.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                llMidB.addView (new SizedButton (0xFFC2, 1.0F, "Insert",   slkbut));
                llMidB.addView (new SizedButton (0xFFC3, 1.0F, "Home",     slkbut));
                llMidB.addView (new SizedButton (0xFFC4, 1.0F, "Page\nUp", slkbut));
            llMid.addView (llMidB);

            LinearLayout llMidC = new LinearLayout (sshclient);
                llMidC.setOrientation (LinearLayout.HORIZONTAL);
                llMidC.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                llMidC.addView (new SizedButton (0xFFC6, 1.0F, "Delete", slkbut));
                llMidC.addView (new SizedButton (0xFFC7, 1.0F, "End",        slkbut));
                llMidC.addView (new SizedButton (0xFFC8, 1.0F, "Page\nDown", slkbut));
            llMid.addView (llMidC);

            LinearLayout llMidD = new LinearLayout (sshclient);
                llMidD.setOrientation (LinearLayout.HORIZONTAL);
                llMidD.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                llMidD.addView (new InvisButton ());
                llMidD.addView (new InvisButton ());
                llMidD.addView (new InvisButton ());
            llMid.addView (llMidD);

            LinearLayout llMidE = new LinearLayout (sshclient);
                llMidE.setOrientation (LinearLayout.HORIZONTAL);
                llMidE.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                ArrowButton upArrow = new ArrowButton (0xFF52, ArrowButton.UP);

                llMidE.addView (new InvisButton ());
                llMidE.addView (upArrow);
                llMidE.addView (new InvisButton ());
            llMid.addView (llMidE);

            LinearLayout llMidF = new LinearLayout (sshclient);
                llMidF.setOrientation (LinearLayout.HORIZONTAL);
                llMidF.setLayoutParams (new LinearLayout.LayoutParams (MATCH_PARENT, WRAP_CONTENT));

                ArrowButton leftArrow = new ArrowButton (0xFF51, ArrowButton.LF);
                ArrowButton downArrow = new ArrowButton (0xFF54, ArrowButton.DN);
                ArrowButton riteArrow = new ArrowButton (0xFF53, ArrowButton.RT);

                llMidF.addView (leftArrow);
                llMidF.addView (downArrow);
                llMidF.addView (riteArrow);
            llMid.addView (llMidF);
        addView (llMid);

        addView (HorizSpacer ());

        GridLayout glRite = new GridLayout (sshclient);
            glRite.setLayoutParams (new LinearLayout.LayoutParams (WRAP_CONTENT, MATCH_PARENT));

            Button ib1 = new KeypadButton (0, 0, 1, 1, 0, " ");
            ib1.setEnabled (false);
            ib1.setVisibility (INVISIBLE);
            glRite.addView (ib1);

            KPNumLockButton kpnlb = new KPNumLockButton (0, 1, 1, 1, 0xFF7F);

            glRite.addView (kpnlb);
            glRite.addView (new KeypadButton (1, 1, 1, 1, 0xFFAF, "/"));
            glRite.addView (new KeypadButton (2, 1, 1, 1, 0xFFAA, "*"));
            glRite.addView (new KeypadButton (3, 1, 1, 1, 0xFFAD, "-"));

            glRite.addView (new KeypadButton (0, 2, 1, 1, 0xFFB7, "7", 0xFF95, "Home", kpnlb));
            glRite.addView (new KPArowButton (1, 2, 1, 1, 0xFFB8, "8", 0xFF97, upArrow));
            glRite.addView (new KeypadButton (2, 2, 1, 1, 0xFFB9, "9", 0xFF9A, "PgUp", kpnlb));
            glRite.addView (new KeypadButton (3, 2, 1, 2, 0xFFAB, "+"));

            glRite.addView (new KPArowButton (0, 3, 1, 1, 0xFFB4, "4", 0xFF96, leftArrow));
            glRite.addView (new KeypadButton (1, 3, 1, 1, 0xFFB5, "5", 0xFF9D, "Begin", kpnlb));
            glRite.addView (new KPArowButton (2, 3, 1, 1, 0xFFB6, "6", 0xFF98, riteArrow));

            glRite.addView (new KeypadButton (0, 4, 1, 1, 0xFFB1, "1", 0xFF9C, "End", kpnlb));
            glRite.addView (new KPArowButton (1, 4, 1, 1, 0xFFB2, "2", 0xFF99, downArrow));
            glRite.addView (new KeypadButton (2, 4, 1, 1, 0xFFB3, "3", 0xFF9B, "PgDn", kpnlb));
            glRite.addView (new KeypadButton (3, 4, 1, 2, 0xFF8D, "E\nn\nt\ne\nr", 0xFF8D, "E\nn\nt\ne\nr", kpnlb));

            glRite.addView (new KeypadButton (0, 5, 2, 1, 0xFFB0, "0", 0xFF9E, "Ins", kpnlb));
            glRite.addView (new KeypadButton (2, 5, 1, 1, 0xFFAE, ".", 0xFF9F, "Del", kpnlb));
        addView (glRite);
    }

    private View HorizSpacer ()
    {
        TextView tv1 = new TextView (sshclient);
        tv1.setLayoutParams (new LinearLayout.LayoutParams (WRAP_CONTENT, MATCH_PARENT));
        tv1.setText ("   ");
        tv1.setTextSize (TypedValue.COMPLEX_UNIT_PX, textSizeBase * scaleY);
        return tv1;
    }

    /**
     * Send the given key code to the host.
     */
    @Override
    protected void SendCodeToHost (Object code, int down)
    {
        // Log.d (TAG, "PeeCeeKBView.SendCodeToHost*: code=" + Integer.toHexString ((Integer) code) + " down=" + down);
        session.getVNCClientView ().SendCodeToHost ((Integer) code, down);
        performHapticFeedback (HapticFeedbackConstants.KEYBOARD_TAP);
    }

    /**
     * Get the code for a given lowercase/uppercase key code logo string
     * @param str = logo string on keytop
     * @return code to give to SendCodeToHost()
     */
    @Override
    protected Object GetLcUcCode (CharSequence str)
    {
        return (int) str.charAt (0);
    }

    /**
     * Get whether or not the keypad is in numeric or application mode.
     * @return true: send numeric codes to host
     *        false: send application codes to host
     */
    @Override
    protected boolean GetKeypadAppMode ()
    {
        return !numLocked;
    }

    /**
     * The Caps Lock button.
     */
    private class CapsLockButton extends SizedButton {
        public CapsLockButton ()
        {
            super (0xFFE5, 1.7F, "Caps Lock");
            SetButtonColor (capsLocked ? Color.RED : 0);
        }

        @Override  // TransientButton
        public void OnTouchEvent (int action)
        {
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    capsLocked = !capsLocked;
                    SetButtonColor (capsLocked ? Color.RED : 0);
                    for (KeyButton kb : allShiftButtons) kb.invalidate ();
                    break;
                }
            }

            super.OnTouchEvent (action);
        }
    }

    /**
     * NumLock : we need to save the locked/unlocked state
     */
    protected class KPNumLockButton extends KeypadButton {
        public KPNumLockButton (int x, int y, int w, int h, Object c)
        {
            super (x, y, w, h, c, "Num\nLock");
            SetButtonColor (numLocked ? Color.RED : 0);
        }

        @Override  // TransientButton
        public void OnTouchEvent (int action)
        {
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    numLocked = !numLocked;
                    SetButtonColor (numLocked ? Color.RED : 0);
                    for (KeypadButton kpb : allKeypadButtons) {
                        kpb.invalidate ();
                    }
                    break;
                }
            }

            super.OnTouchEvent (action);
        }
    }

    /**
     * Keypad Arrow : number if numLocked, arrow if not
     */
    protected class KPArowButton extends KeypadButton {
        private ArrowButton arrowButton;

        public KPArowButton (int x, int y, int w, int h, Object nc, String num, Object cc, ArrowButton ctl)
        {
            super (x, y, w, h, nc, num, cc, " ");
            arrowButton = ctl;
        }

        @Override  // KeypadButton
        public void onDraw (@NonNull Canvas canvas)
        {
            //  numLocked: draw button with digit
            // !numLocked: draw blank button (cuz we passed " " to super constructor)
            super.onDraw (canvas);

            // !numLocked: draw graphical arrow just like middle section arrows
            if (GetKeypadAppMode ()) {
                canvas.drawPath (arrowButton.guipath, getPaint ());
            }
        }
    }
}

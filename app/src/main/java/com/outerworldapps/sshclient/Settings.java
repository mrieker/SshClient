/**
 * Settings management.
 */

//    Copyright (C) 2014, Mike Rieker, Beverly, MA USA
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
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.TreeMap;

public class Settings {
    public static final String TAG = "SshClient";

    public static final int TEXT_SIZE_MIN = 5;
    public static final int TEXT_SIZE_MAX = 200;

    /*
     * Use field.GetValue() to get the setting's value.
     */
    public _Bool incl_hid   = new _Bool ("inclHidden", "Include hidden files", false);
    public _Bool show_eols  = new _Bool ("showEOLs",   "Show EOL markers",     false);
    public _Bool wrap_lines = new _Bool ("wrapLines",  "Wrap long lines",      true);

    public _FontSize font_size = new _FontSize ("fontSize", "Font size", 20, TEXT_SIZE_MIN, TEXT_SIZE_MAX);
    public _MaxChars max_chars = new _MaxChars ("maxChars", "Max total chars", 65536, 64, 1024*1024);

    public _Radio cursor_style = new _Radio ("cursorStyle", "Cursor style",
            0,
            new int[]    {  0,      1,     2,       -1    },
            new String[] { "line", "box", "block", "none" }
    );

    public _Radio term_type = new _Radio ("termType", "Term type",
            0,
            new int[] { 0, 1},
            new String [] { "dumb", "vt100" }
    );

    public _Radio txt_colors = new _Radio ("txtColors", "Text colors",
            1,
            new int[]    {  0,                1               },
            new String[] { "black-on-white", "white-on-black" }
    );
    private final int[] fgcolors = new int[] { Color.BLACK, Color.WHITE };
    private final int[] bgcolors = new int[] { Color.WHITE, Color.BLACK };

    public _Radio scr_orien = new _Radio ("scrOrien", "Screen orientation",
            ActivityInfo.SCREEN_ORIENTATION_USER,
            new int[] {
                    ActivityInfo.SCREEN_ORIENTATION_USER,
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            },
            new String[] {
                    "Unlocked",
                    "Portrait",
                    "Landscape"
            }
    );

    public _Radio xfr_prog = new _Radio ("xfrProg", "Transfer progress",
            PDiagdFileTasks.PROG_HIERARC | PDiagdFileTasks.PROG_PRESCAN,
            new int[] {
                    0,
                    PDiagdFileTasks.PROG_HIERARC,
                    PDiagdFileTasks.PROG_HIERARC | PDiagdFileTasks.PROG_PRESCAN
            },
            new String[] {
                    "Data files only",
                    "Hierarchy w/out dir sizes",
                    "Hierarchy with dir sizes"
            }
    );

    private SharedPreferences prefs;
    private SshClient sshclient;
    private TreeMap<String,_Value> values;

    /**
     * Construct the class -
     * - reads the values if any in from the preferences file
     * - leaves default values for any that aren't there
     */
    public Settings (SshClient sc)
    {
        sshclient = sc;
        prefs = sshclient.getPreferences (Activity.MODE_PRIVATE);
        for (_Value v : values.values ()) {
            v.FromString (prefs.getString (v.name, v.toString ()));
        }
    }

    /**
     * Construct and display menu for editing the values.
     */
    public void ShowMenu ()
    {
        // put settings in a vertical layout
        LinearLayout llv = new LinearLayout (sshclient);
        llv.setOrientation (LinearLayout.VERTICAL);

        // put all the values and their descriptions in a vertical list
        TreeMap<String,_Value> sortedByLabel = new TreeMap<String,_Value> ();
        for (_Value v : values.values ()) sortedByLabel.put (v.label, v);
        for (_Value v : sortedByLabel.values ()) {
            TextView lbl = new TextView (sshclient);
            lbl.setTextSize (SshClient.UNIFORM_TEXT_SIZE * 1.5F);
            lbl.setText (" " + v.label);
            llv.addView (lbl);
            LinearLayout llh = new LinearLayout (sshclient);
            llh.setOrientation (LinearLayout.HORIZONTAL);
            TextView spr = sshclient.MyTextView ();
            spr.setText ("      ");
            llh.addView (spr);
            llh.addView (v.GetView ());
            llv.addView (llh);
            TextView sep = sshclient.MyTextView ();
            sep.setText ("      ");
            llv.addView (sep);
        }

        // set up a save button to save values to preferences file, apply them and go back to main screen
        Button save = new Button (sshclient);
        save.setText ("SAVE");
        save.setOnClickListener (new View.OnClickListener () {
            @Override
            public void onClick (View view) {
                SharedPreferences.Editor editr = prefs.edit ();
                for (_Value v : values.values ()) {
                    v.UpdateFromView ();
                    editr.putString (v.name, v.toString ());
                }
                editr.commit ();
                ApplySettings ();
                sshclient.onBackPressed ();
            }
        });
        llv.addView (save);

        // revert just shows another one of these menus created from scratch,
        // using the currently stored values
        Button revert = new Button (sshclient);
        revert.setText ("REVERT");
        revert.setOnClickListener (new View.OnClickListener () {
            @Override
            public void onClick (View view) {
                ShowMenu ();
            }
        });
        llv.addView (revert);

        // wrap them in a scroll view in case they are too long for screen
        ScrollView sv = new ScrollView (sshclient);
        sv.addView (llv);

        // display the menu always in portrait orientation
        sshclient.setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        sshclient.setContentView (sv);

        // set up method to be called if this screen is back-buttoned to
        sshclient.pushBackAction (new SshClient.BackAction () {
            @Override
            public boolean okToPop () {
                // it is always ok to back-button away from this page
                return true;
            }
            @Override
            public void reshow ()
            {
                ShowMenu ();
            }
            @Override
            public String name ()
            {
                return "settings";
            }
            @Override
            public MySession session ()
            {
                return null;
            }
        });
    }

    /**
     * @brief Misc value retrievals.
     */
    public String GetTermTypeStr ()
    {
        return term_type.GetKey ();
    }
    public int GetFGColor ()
    {
        int i = txt_colors.GetValue ();
        return fgcolors[i];
    }
    public int GetBGColor ()
    {
        int i = txt_colors.GetValue ();
        return bgcolors[i];
    }

    /**
     * Apply settings to wherever they go.
     */
    private void ApplySettings ()
    {
        for (MySession s : sshclient.getAllsessions ()) {
            s.LoadSettings ();
        }
    }

    /**
     * Contains a settable value and methods to manipulate it.
     */
    public abstract class _Value {
        protected String name;
        public String label;
        protected _Value (String n, String l)
        {
            name = n;
            label = l;
            if (values == null) values = new TreeMap<String,_Value> ();
            values.put (name, this);
        }

        // convert setting from string that was read from preferences file
        public abstract void FromString (String s);

        // convert setting to string that gets written to preferences file
        @Override
        public abstract String toString ();

        // get a view that can edit the setting's value
        // and fill it in with setting's current value
        public abstract View GetView ();

        // convert setting from string in view
        public abstract void UpdateFromView ();
    }

    // boolean settings
    public class _Bool extends _Value {
        private boolean value;
        private CheckBox txt;

        public _Bool (String name, String label, boolean value)
        {
            super (name, label);
            this.value = value;
        }

        @Override
        public void FromString (String s)
        {
            value = Boolean.parseBoolean (s);
        }
        @Override
        public String toString ()
        {
            return Boolean.toString (value);
        }

        public boolean GetValue ()
        {
            return value;
        }

        @Override
        public View GetView ()
        {
            txt = sshclient.MyCheckBox ();
            txt.setChecked (value);

            TextView lbl = sshclient.MyTextView ();

            LinearLayout llh = new LinearLayout (sshclient);
            llh.setOrientation (LinearLayout.HORIZONTAL);
            llh.addView (txt);
            llh.addView (lbl);
            return llh;
        }

        @Override
        public void UpdateFromView ()
        {
            value = txt.isChecked ();
        }
    }

    // font size setting
    public class _FontSize extends _Value {
        private EditText txt;
        private int value, temp, min, max;

        public _FontSize (String name, String label, int value, int min, int max)
        {
            super (name, label);
            this.value = value;
            this.min   = min;
            this.max   = max;
        }

        @Override
        public void FromString (String s)
        {
            value = Integer.parseInt (s);
        }
        @Override
        public String toString ()
        {
            return Integer.toString (value);
        }

        public int GetValue ()
        {
            return value;
        }

        @Override
        public View GetView ()
        {
            temp = value;

            // set up text box to demo size and show the number
            final TextView current = sshclient.MyTextView ();
            current.setText (" [" + temp + "] ");
            current.setTextSize (temp);
            current.setTypeface (Typeface.MONOSPACE);

            // set up button to increase the size
            Button bigger = sshclient.MyButton ();
            bigger.setText ("BIGGER");
            bigger.setOnClickListener (new View.OnClickListener () {
                @Override
                public void onClick (View view)
                {
                    if (temp < max) {
                        current.setText (" [" + (++ temp) + "] ");
                        current.setTextSize (temp);
                    }
                }
            });

            // set up button to decrease the size
            Button smaller = sshclient.MyButton ();
            smaller.setText ("smaller");
            smaller.setOnClickListener (new View.OnClickListener () {
                @Override
                public void onClick (View view) {
                    if (temp > min) {
                        current.setText (" [" + (--temp) + "] ");
                        current.setTextSize (temp);
                    }
                }
            });

            // display the two buttons horizontally
            // and display the current value below them
            LinearLayout llh = new LinearLayout (sshclient);
            llh.setOrientation (LinearLayout.HORIZONTAL);
            llh.addView (bigger);
            llh.addView (smaller);
            LinearLayout llv = new LinearLayout (sshclient);
            llv.setOrientation (LinearLayout.VERTICAL);
            llv.addView (llh);
            llv.addView (current);
            return llv;
        }

        @Override
        public void UpdateFromView ()
        {
            value = temp;
        }
    }

    // integer settings
    public class _Int extends _Value implements View.OnFocusChangeListener {
        private EditText txt;
        private int value, min, max;

        public _Int (String name, String label, int value, int min, int max)
        {
            super (name, label);
            this.value = value;
            this.min   = min;
            this.max   = max;
        }

        @Override
        public void FromString (String s)
        {
            value = Integer.parseInt (s);
        }
        @Override
        public String toString ()
        {
            return Integer.toString (value);
        }

        public int GetValue ()
        {
            return value;
        }

        @Override
        public View GetView ()
        {
            txt = sshclient.MyEditText ();
            txt.setText (Integer.toString (value));
            txt.setSingleLine (true);
            txt.setInputType (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            txt.setOnFocusChangeListener (this);

            TextView lbl = sshclient.MyTextView ();
            lbl.setText (" (min " + min + ", max " + max + ")");

            LinearLayout llh = new LinearLayout (sshclient);
            llh.setOrientation (LinearLayout.HORIZONTAL);
            llh.addView (txt);
            llh.addView (lbl);
            return llh;
        }

        @Override
        public void UpdateFromView ()
        {
            value = Integer.parseInt (txt.getText ().toString ());
            if (value < min) value = min;
            if (value > max) value = max;
            txt.setText (Integer.toString (value));
        }

        /**
         * Whenever field gets focus while in disconnected state, switch to enter state.
         */
        @Override // View.OnFocusChangeListener
        public void onFocusChange (View v, boolean hasFocus)
        {
            if (!hasFocus) {
                try {
                    int val = Integer.parseInt (txt.getText ().toString ());
                    if ((val < min) || (val > max)) {
                        throw new NumberFormatException ("out of range " + min + " to " + max);
                    }
                } catch (NumberFormatException nfe) {
                    sshclient.ErrorAlert (this.label, SshClient.GetExMsg (nfe) + "\n- reverted to original value");
                    txt.setText (Integer.toString (value));
                }
            }
        }
    }

    // max chars setting
    public class _MaxChars extends _Value {
        private EditText txt;
        private int value, temp, min, max;

        public _MaxChars (String name, String label, int value, int min, int max)
        {
            super (name, label);
            this.value = value;
            this.min   = min;
            this.max   = max;
        }

        @Override
        public void FromString (String s)
        {
            value = Integer.parseInt (s);
        }
        @Override
        public String toString ()
        {
            return Integer.toString (value);
        }

        public int GetValue ()
        {
            return value;
        }

        @Override
        public View GetView ()
        {
            temp = value;

            // set up text box to demo size and show the number
            final TextView current = sshclient.MyTextView ();
            current.setText (" [" + temp + "] power of two");

            // set up button to increase the size
            Button bigger = sshclient.MyButton ();
            bigger.setText ("BIGGER");
            bigger.setOnClickListener (new View.OnClickListener () {
                @Override
                public void onClick (View view)
                {
                    if (temp < max) {
                        temp *= 2;
                        current.setText (" [" + temp + "] power of two");
                    }
                }
            });

            // set up button to decrease the size
            Button smaller = sshclient.MyButton ();
            smaller.setText ("smaller");
            smaller.setOnClickListener (new View.OnClickListener () {
                @Override
                public void onClick (View view) {
                    if (temp > min) {
                        temp /= 2;
                        current.setText (" [" + temp + "] power of two");
                    }
                }
            });

            // display the two buttons horizontally
            // and display the current value below them
            LinearLayout llh = new LinearLayout (sshclient);
            llh.setOrientation (LinearLayout.HORIZONTAL);
            llh.addView (bigger);
            llh.addView (smaller);
            LinearLayout llv = new LinearLayout (sshclient);
            llv.setOrientation (LinearLayout.VERTICAL);
            llv.addView (llh);
            llv.addView (current);
            return llv;
        }

        @Override
        public void UpdateFromView ()
        {
            value = temp;
        }
    }

    // radio buttons
    public class _Radio extends _Value {
        private int value;
        private int[] ints;
        private RadioGroup radgrp;
        private String[] keys;

        public _Radio (String name, String label, int value,
                       int[] ints, String[] keys)
        {
            super (name, label);
            this.value = value;
            this.ints  = ints;
            this.keys  = keys;
        }

        @Override
        public void FromString (String s)
        {
            value = Integer.parseInt (s);
        }
        @Override
        public String toString ()
        {
            return Integer.toString (value);
        }

        public int GetValue ()
        {
            return value;
        }

        public String GetKey ()
        {
            return keys[value];
        }

        public void SetValue (int val)
        {
            value = val;
            SharedPreferences.Editor editr = prefs.edit ();
            editr.putString (name, Integer.toString (value));
            editr.commit ();
            ApplySettings ();
        }

        @Override
        public View GetView ()
        {
            radgrp = new RadioGroup (sshclient);
            radgrp.setOrientation (LinearLayout.VERTICAL);
            radgrp.setLayoutParams (new LinearLayout.LayoutParams (
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    0.0F
            ));
            RadioButton active = null;
            for (int i = 0; i < ints.length; i ++) {
                RadioButton radbut = sshclient.MyRadioButton ();
                radbut.setText (keys[i]);
                radgrp.addView (radbut, i, new LinearLayout.LayoutParams (
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        0.0F
                ));
                if (ints[i] == value) active = radbut;
            }
            if (active != null) active.toggle ();

            return radgrp;
        }

        @Override
        public void UpdateFromView ()
        {
            int butid = radgrp.getCheckedRadioButtonId ();
            if (butid >= 0) {
                RadioButton radbut = (RadioButton)radgrp.findViewById (butid);
                String key = radbut.getText ().toString ();
                for (int i = keys.length; -- i >= 0;) {
                    if (key.equals (keys[i])) {
                        value = ints[i];
                        break;
                    }
                }
            }
        }
    }
}

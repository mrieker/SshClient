/**
 * This widget contains an EditText widget and a keyboard widget.
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
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

public abstract class KeyboardableView extends LinearLayout implements View.OnFocusChangeListener {
    public static final String TAG = "SshClient";

    private Activity sshclient;          // what activity we are part of
    private EditText edtx;               // holds the text display
    private HorizontalScrollView kbhsv;  // non-null means we are using non-Android keyboard

    public KeyboardableView (SshClient act)
    {
        super (act);
        sshclient = act;
        setLayoutParams (new LinearLayout.LayoutParams (ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setOrientation (VERTICAL);
    }

    protected void SetEditor (EditText et)
    {
        edtx = et;
        edtx.setLayoutParams (new LinearLayout.LayoutParams (ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1.0F));
        edtx.setOnFocusChangeListener (this);
    }

    /**
     * Set up which keyboard to use.
     * @param useAltKb = false: use Android keyboard; true: use Alt Keyboard
     */
    protected void SelectKeyboard (boolean useAltKb)
    {
        if (!useAltKb) {
            kbhsv = null;
            // no spelling interference
            edtx.setInputType (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            // make sure we get RETURN key instead of DONE key
            edtx.setImeOptions (EditorInfo.IME_ACTION_NONE);
            removeAllViews ();
            addView (edtx);
        } else if (kbhsv == null) {
            kbhsv = new HorizontalScrollView (sshclient);
            kbhsv.setLayoutParams (new LinearLayout.LayoutParams (ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0.0F));
            kbhsv.addView (GetAltKeyboard ());
            kbhsv.setVisibility (GONE);
            edtx.setInputType (InputType.TYPE_NULL);
            removeAllViews ();
            addView (edtx);
            addView (kbhsv);
        }
    }
    protected abstract View GetAltKeyboard ();

    public int AltKeyboardScrollX ()
    {
        if (kbhsv == null) return 0;
        return kbhsv.getScrollX ();
    }

    @Override  // OnFocusChangeListener
    public void onFocusChange (View v, boolean focus)
    {
        if (kbhsv != null) {
            kbhsv.setVisibility (focus ? VISIBLE : GONE);
            if (focus) {
                // turn off android keyboard
                InputMethodManager imm = (InputMethodManager) sshclient.getSystemService (Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow (v.getWindowToken (), 0);
            }
        } else {
            if (focus) {
                // turn on android keyboard
                InputMethodManager imm = (InputMethodManager) sshclient.getSystemService (Context.INPUT_METHOD_SERVICE);
                imm.showSoftInput (v, 0);
            }
        }
    }
}
